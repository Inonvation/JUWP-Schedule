package edu.jxslu.schedule.ui.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import edu.jxslu.schedule.EXTRA_ROUTE
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.MainActivity
import edu.jxslu.schedule.ROUTE_LIFE
import edu.jxslu.schedule.R
import edu.jxslu.schedule.SubpageScreen
import edu.jxslu.schedule.data.power.PowerReadingSource
import edu.jxslu.schedule.data.prefs.DisplayPrefsStore
import edu.jxslu.schedule.data.repo.PendingLaunch
import edu.jxslu.schedule.data.repo.PendingNotification
import edu.jxslu.schedule.data.session.AutoSyncRules
import edu.jxslu.schedule.data.ykt.YktCredentialStore
import edu.jxslu.schedule.domain.BalanceAlert
import edu.jxslu.schedule.domain.BalanceAlertSource
import edu.jxslu.schedule.domain.InboxKind
import edu.jxslu.schedule.domain.InboxKeys
import edu.jxslu.schedule.ui.widget.LifeWidgetSync
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * 余额提醒（DESIGN §3.10 / §3.13）：寝室电费与一卡通余额低于设定值时发一条通知。
 *
 * 与上课/作业提醒（[ClassReminder]）的差别：那两个是**时间点**驱动（到点即发，闹钟为主），
 * 这里是**状态**驱动（余额低了才发），没有可预排的时刻，所以只有一条路：
 * WorkManager 周期核对（间隔 = 两链设置的最小档）+ 冷启动补查 + 设置变更后立即评估。
 * 不用 `setAlarmClock`——那会在状态栏挂一枚闹钟图标，对「余额低了」是错的语义。
 *
 * 三条口径（改之前先读）：
 *
 * 1. **间隔与频率**：各路闸门是「上次**成功**检查时刻」（[DisplayPrefsStore.balanceAlertCheckMillis]），
 *    判据 `AutoSyncRules.shouldAttemptAt`（间隔取各自设置档位，默认 6 小时）。成功取到数据才
 *    落时刻，失败不落——闸门保持开着，下一轮还有补查机会。**偏低期间只提醒一次**：
 *    「已提醒未回升」标记（`power/ykt_alert_notified`）在通知发出或免扰拦截后置位，
 *    回升随收件箱状态消解清除；改设置也会清，让新规则立即重新评估。
 * 2. **不猜数**：电费金额 = 剩余电量 × 单价（[BalanceAlert.remainingYuan]），
 *    电量或单价缺失就当这次没有结论；余额/电费取数失败一律静默（不重试，防撞风控）。
 * 3. **凭证是前提**：两个来源共用 `YktCredentialStore` 的学号 + 查询密码；凭证缺失时
 *    静默跳过。设置页在关闭凭证时会一并关掉这两个开关，所以不会出现「开关亮着但永远不生效」。
 *
 * **工作名与 Worker 类名一经发布不要改**：`KEEP` 策略下老任务按类名实例化，
 * 改名会让已排的周期任务实例化失败且不会重排，兜底永久消失（同 `EbikeFreeRideCheckWorker`）。
 */
object BalanceAlertReminder {

    private const val TAG = "BalanceAlert"

    /** 周期核对（间隔 = 两链检查间隔的最小档）。 */
    private const val PERIODIC_WORK = "balance_alert_periodic"

    /** 即时评估（冷启动 / 开关与阈值变更）。 */
    private const val ONE_SHOT_WORK = "balance_alert_check"

    private const val CHANNEL_ID = "balance_alert"

    /** 寝室电费通知：固定 id/tag/requestCode，与一卡通那条及上课/作业通知互不覆盖。 */
    private const val POWER_NOTIFICATION_ID = 1005
    private const val POWER_NOTIFICATION_TAG = "power_alert"

    /** 一卡通余额通知：id 必须与电费不同，否则两条通知会互相顶掉。 */
    private const val YKT_NOTIFICATION_ID = 1006
    private const val YKT_NOTIFICATION_TAG = "ykt_alert"

    /**
     * 通知点击落点的 requestCode（[3005]/[3006]）。**不能复用通知 id**：骑行提醒
     * （`EbikeFreeRideNotifier`）的通知 id 也是 1005/1006，且两边落点 intent 都指向
     * MainActivity、无 action——filterEquals 相同，requestCode 撞了就会把彼此的落点
     * 改掉（2026-09-24 审计发现的真 bug：点「余额偏低」可能落到骑行出码页）。
     */
    private const val POWER_REQUEST_CODE = 3005
    private const val YKT_REQUEST_CODE = 3006

    /**
     * 周期核对：两个开关都关时**撤销**任务而不是留着空跑——它每隔一段就会唤醒设备一次，
     * 能省就省（`ClassReminder` 那种「排着空跑」是因为它还要重排闹钟，这里没有这个负担）。
     *
     * 周期 = **两条链检查间隔的最小档**（2026-10-07 起间隔各自独立，用户拍板）；
     * 周期任务只负责「按最勤的那条叫醒」，各路 check 里的 `AutoSyncRules.shouldAttemptAt`
     * 用各自的间隔与上次成功时刻决定真抓还是跳过。幂等策略同 [ScoreAlertReminder]：
     * **间隔档位没变时 KEEP** 保相位；变了用 REPLACE 换周期（2.7.1 没有 UPDATE 策略），
     * 已排档位记在 DataStore（`balance_periodic_interval`），进程重启后也能比对。
     */
    suspend fun ensurePeriodicWork(context: Context) {
        runCatching {
            val prefs = Graph.displayPrefs(context)
            val workManager = WorkManager.getInstance(context)
            if (!prefs.powerAlertEnabled.first() && !prefs.yktAlertEnabled.first()) {
                workManager.cancelUniqueWork(PERIODIC_WORK)
                prefs.setBalancePeriodicInterval(0)
                return
            }
            // 只在**开关开着**的链里取最小档：只开一链且是 48h 档时，不该让关着的那条
            // 默认 6h 把周期任务拖到 6h 一醒（醒来的核对会被那路的闸门挡住，白耗电）。
            val powerOn = prefs.powerAlertEnabled.first()
            val yktOn = prefs.yktAlertEnabled.first()
            val intervalHours = listOfNotNull(
                if (powerOn) BalanceAlert.coerceIntervalHours(prefs.powerAlertIntervalHours.first()) else null,
                if (yktOn) BalanceAlert.coerceIntervalHours(prefs.yktAlertIntervalHours.first()) else null,
            ).min()
            val request = PeriodicWorkRequestBuilder<BalanceAlertCheckWorker>(intervalHours.toLong(), TimeUnit.HOURS)
                .setConstraints(connectedConstraint())
                .build()
            if (prefs.balancePeriodicInterval() == intervalHours) {
                workManager.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
            } else {
                workManager.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.REPLACE, request)
                prefs.setBalancePeriodicInterval(intervalHours)
            }
        }.onFailure { Log.w(TAG, "ensurePeriodicWork failed", it) }
    }

    /** 即时评估（REPLACE：连着改两次只跑最后一次）。冷启动与设置变更都走这里。 */
    fun enqueueCheck(context: Context) {
        runCatching {
            val request = OneTimeWorkRequestBuilder<BalanceAlertCheckWorker>()
                .setConstraints(connectedConstraint())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(ONE_SHOT_WORK, ExistingWorkPolicy.REPLACE, request)
        }.onFailure { Log.w(TAG, "enqueueCheck failed", it) }
    }

    /**
     * 设置变更（开关 / 阈值 / 检查间隔）后的统一入口：**清掉该来源的检查时刻与
     * 「已提醒未回升」标记**再排一次即时评估。
     *
     * 清两个键的理由是语义而非省事：用户刚把提醒线从 ¥20 调到 ¥50（或改了检查间隔），
     * 就是要按新规则立刻评估一次；沿用旧时刻的话新规则要等下一个周期才生效，
     * 用户会以为改错了地方。清 notified 标记则让「刚调高的阈值」当场能再提醒一次。
     */
    suspend fun onSettingsChanged(context: Context, source: BalanceAlertSource) {
        runCatching {
            val prefs = Graph.displayPrefs(context)
            prefs.clearBalanceAlertCheckMillis(source)
            prefs.clearBalanceAlertNotified(source)
            ensurePeriodicWork(context)
        }.onFailure { Log.w(TAG, "onSettingsChanged failed", it) }
        enqueueCheck(context)
    }

    /**
     * 核对一次：逐个来源判断间隔闸门（`shouldAttemptAt`）→ 取数 → 落时刻 → 低于阈值才发通知。
     * 整体兜异常：后台任务里任何失败都不该让 Worker 报错重试（重试会再打一次第三方接口）。
     */
    suspend fun check(context: Context) {
        runCatching {
            val prefs = Graph.displayPrefs(context)
            val powerOn = prefs.powerAlertEnabled.first()
            val yktOn = prefs.yktAlertEnabled.first()
            if (!powerOn && !yktOn) return
            val credentials = Graph.yktCredentialStore(context).read() ?: return
            val nowMillis = System.currentTimeMillis()
            // 通知落点：生活页开关关掉时不给 extra（落今日页），免得落在一个不存在的 Tab 上
            val toLife = prefs.lifeTabEnabled.first()
            // 通知总开关只拦「发通知」，不拦核对本身（2026-10-06 收件箱 §4.38）：
            // 状态消解（回升移除消息）与入箱都靠这条核对链，通知全关也得照跑。
            val notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
            if (powerOn) checkPower(context, prefs, credentials, nowMillis, toLife, notificationsEnabled)
            if (yktOn) checkYkt(context, prefs, credentials, nowMillis, toLife, notificationsEnabled)
        }.onFailure { Log.w(TAG, "check failed", it) }
    }

    // ------------------------------------------------------------------
    // 寝室电费
    // ------------------------------------------------------------------

    private suspend fun checkPower(
        context: Context,
        prefs: DisplayPrefsStore,
        credentials: YktCredentialStore.Credentials,
        nowMillis: Long,
        toLife: Boolean,
        notificationsEnabled: Boolean,
    ) {
        val intervalHours = BalanceAlert.coerceIntervalHours(prefs.powerAlertIntervalHours.first())
        if (!AutoSyncRules.shouldAttemptAt(
                prefs.balanceAlertCheckMillis(BalanceAlertSource.Power),
                intervalHours,
                nowMillis,
            )
        ) {
            return
        }
        val snapshot = runCatching {
            Graph.powerRepository(context).snapshot(
                credentials.username,
                credentials.password,
                // 这一条读数是「用电统计」最稳定的采样点（按设置的间隔，与开关联动）
                source = PowerReadingSource.ALERT,
            )
        }.getOrNull() ?: return // 失败不落时刻：间隔闸门保持开着，下一轮还有补查机会
        prefs.setBalanceAlertCheckMillis(BalanceAlertSource.Power, nowMillis)
        // 桌面生活小组件的电费副行（DESIGN §3.6 二条目改版）：读数已落 Room，
        // 顺手镜像一次（零网络）
        runCatching { LifeWidgetSync.refreshLifeWidgets(context) }

        val meter = snapshot.meter
        val yuan = BalanceAlert.remainingYuan(meter.remain, snapshot.feeItem.priceYuan) ?: return
        val threshold = prefs.powerAlertYuan.first()
        val room = meter.room.room?.takeIf { it.isNotBlank() } ?: snapshot.feeItem.room?.name
        if (!BalanceAlert.isLow(yuan, threshold)) {
            // 状态消解（收件箱 §4.38）：回升了就把「电费偏低」那条撤掉，并重置提醒标记
            //（下次再降才重新提醒）。走到这里说明取数成功，不存在「失败误删」。
            Graph.inboxRepository(context).clearState(InboxKeys.POWER_LOW)
            prefs.clearBalanceAlertNotified(BalanceAlertSource.Power)
            return
        }
        // 「偏低期间只提醒一次」（2026-10-07 间隔改小时级后重立）：标记亮着说明上一轮
        // 已经提醒过且一直没回升，这轮照常取数（小组件照刷）但不再打扰。
        if (prefs.balanceAlertNotified(BalanceAlertSource.Power)) return
        val text = buildString {
            if (!room.isNullOrBlank()) append("$room ")
            append("剩余 ¥%.2f".format(yuan))
            meter.remain?.let { append("（%.2f 度）".format(it)) }
            append("，低于提醒线 ¥$threshold，记得充值")
        }
        // 收件箱留痕：状态型消息，固定一条（balance|power|low），每轮核对刷新并
        // 重新置未读（偏低期间只此一轮，之后被下方 notified 闸门挡住）；回升即被
        // 上面的 clearState 移除。
        Graph.inboxRepository(context).post(
            kind = InboxKind.POWER_LOW,
            dedupeKey = InboxKeys.POWER_LOW,
            title = "寝室电费偏低",
            body = text,
            route = SubpageScreen.POWER_BILL.name,
        )
        // 免扰时段（DESIGN §3.7）：余额偏低是事实型通知（状态型——明天复核回升即消解），
        // 拦下入队待时段结束后补发，不丢弃。入箱与状态消解都已完成，不受此影响
        val held = notificationsEnabled && QuietHoursGate.intercept(
            context,
            PendingNotification(
                tag = POWER_NOTIFICATION_TAG,
                notificationId = POWER_NOTIFICATION_ID,
                channelId = CHANNEL_ID,
                category = NotificationCompat.CATEGORY_REMINDER,
                title = "寝室电费偏低",
                text = text,
                enqueuedAt = System.currentTimeMillis(),
                launch = PendingLaunch(
                    kind = PendingLaunch.Kind.MAIN_ROUTE,
                    requestCode = POWER_REQUEST_CODE,
                    route = if (toLife) ROUTE_LIFE else null,
                ),
            ),
        )
        // 「偏低期间只提醒一次」的置位点：通知真发出去（或拦下入队待补发）就算这条
        // 提醒已送达，标记亮住直到回升清除——不置位的话小时级周期核对每一轮都重发
        if (held || (notificationsEnabled && postedPower(context, text, toLife))) {
            prefs.setBalanceAlertNotified(BalanceAlertSource.Power, true)
        }
    }

    /** 电费通知的即时发送（返回是否成功），与置位判断拆开保持原来 post 的吞错口径。 */
    private fun postedPower(context: Context, text: String, toLife: Boolean): Boolean = post(
        context = context,
        channelTag = POWER_NOTIFICATION_TAG,
        notificationId = POWER_NOTIFICATION_ID,
        title = "寝室电费偏低",
        text = text,
        toLife = toLife,
    )

    // ------------------------------------------------------------------
    // 一卡通余额
    // ------------------------------------------------------------------

    private suspend fun checkYkt(
        context: Context,
        prefs: DisplayPrefsStore,
        credentials: YktCredentialStore.Credentials,
        nowMillis: Long,
        toLife: Boolean,
        notificationsEnabled: Boolean,
    ) {
        val intervalHours = BalanceAlert.coerceIntervalHours(prefs.yktAlertIntervalHours.first())
        if (!AutoSyncRules.shouldAttemptAt(
                prefs.balanceAlertCheckMillis(BalanceAlertSource.Ykt),
                intervalHours,
                nowMillis,
            )
        ) {
            return
        }
        val cards = runCatching {
            Graph.yktRepository(context).cards(credentials.username, credentials.password)
        }.getOrNull() ?: return // 失败不落时刻：间隔闸门保持开着，下一轮还有补查机会
        prefs.setBalanceAlertCheckMillis(BalanceAlertSource.Ykt, nowMillis)

        // 口径 = **正式卡**余额（付款码实际扣款的那个钱包）。电子账户是线上缴费用的
        // 独立钱包（DESIGN §3.10 账户口径），混进同一个阈值会让「还剩多少能刷」失去意义。
        if (cards.isEmpty()) return
        // 桌面生活小组件（DESIGN §3.6 二条目改版）：日检本来就取到了余额，顺手推一次
        // （零额外请求）
        runCatching { LifeWidgetSync.pushCampusBalance(context, cards.sumOf { it.cardBalanceFen }) }
        val cardYuan = cards.sumOf { it.cardBalanceFen } / 100.0
        val threshold = prefs.yktAlertYuan.first()
        if (!BalanceAlert.isLow(cardYuan, threshold)) {
            // 状态消解（收件箱 §4.38）：同电费——取数成功且不再偏低才撤消息，并重置提醒标记。
            Graph.inboxRepository(context).clearState(InboxKeys.YKT_LOW)
            prefs.clearBalanceAlertNotified(BalanceAlertSource.Ykt)
            return
        }
        // 「偏低期间只提醒一次」（同电费口径）
        if (prefs.balanceAlertNotified(BalanceAlertSource.Ykt)) return
        val text = "正式卡余额 ¥%.2f，低于提醒线 ¥$threshold，记得充值".format(cardYuan)
        Graph.inboxRepository(context).post(
            kind = InboxKind.YKT_LOW,
            dedupeKey = InboxKeys.YKT_LOW,
            title = "校园卡余额偏低",
            body = text,
            route = SubpageScreen.CAMPUS_CARD_SETTINGS.name,
        )
        // 免扰时段（DESIGN §3.7）：同电费口径，拦下入队待时段结束后补发
        val held = notificationsEnabled && QuietHoursGate.intercept(
            context,
            PendingNotification(
                tag = YKT_NOTIFICATION_TAG,
                notificationId = YKT_NOTIFICATION_ID,
                channelId = CHANNEL_ID,
                category = NotificationCompat.CATEGORY_REMINDER,
                title = "校园卡余额偏低",
                text = text,
                enqueuedAt = System.currentTimeMillis(),
                launch = PendingLaunch(
                    kind = PendingLaunch.Kind.MAIN_ROUTE,
                    requestCode = YKT_REQUEST_CODE,
                    route = if (toLife) ROUTE_LIFE else null,
                ),
            ),
        )
        // 「偏低期间只提醒一次」的置位点（同电费）
        if (held || (notificationsEnabled && postedYkt(context, text, toLife))) {
            prefs.setBalanceAlertNotified(BalanceAlertSource.Ykt, true)
        }
    }

    /** 一卡通通知的即时发送（返回是否成功），口径同 [postedPower]。 */
    private fun postedYkt(context: Context, text: String, toLife: Boolean): Boolean = post(
        context = context,
        channelTag = YKT_NOTIFICATION_TAG,
        notificationId = YKT_NOTIFICATION_ID,
        title = "校园卡余额偏低",
        text = text,
        toLife = toLife,
    )

    // ------------------------------------------------------------------
    // 通知
    // ------------------------------------------------------------------

    /**
     * 发一条余额提醒。无权限/被系统拒绝时静默失败（写不出去不该把核对链路搞崩）；
     * 返回系统是否真收下——调用方（标记置位）需要这个区分。
     */
    private fun post(
        context: Context,
        channelTag: String,
        notificationId: Int,
        title: String,
        text: String,
        toLife: Boolean,
    ): Boolean {
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bell)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(
                contentIntent(
                    context,
                    if (notificationId == POWER_NOTIFICATION_ID) POWER_REQUEST_CODE else YKT_REQUEST_CODE,
                    toLife,
                ),
            )
            .build()
        return runCatching {
            NotificationManagerCompat.from(context)
                .notify(channelTag, notificationId, notification)
        }.onFailure { Log.w(TAG, "notify failed", it) }.isSuccess
    }

    /**
     * 点通知落**生活页**（余额卡与电费卡都在那一页）。
     *
     * 用 `NEW_TASK | CLEAR_TASK` 且 extra 走 [EXTRA_ROUTE]，与桌面小组件跳课表 Tab 同一套
     * （`ScheduleWidget.widgetIntent`，真机实测过）：`MainActivity` 是 standard，
     * `CLEAR_TOP` 匹配不上纯显式 intent，清不掉二级页；`CLEAR_TASK` 与匹配无关、行为确定。
     * requestCode 必须逐条不同（3005/3006，与通知 id 分离）——PendingIntent 的身份是
     * 「requestCode + Intent.filterEquals」，两者 Intent 只差 extra，会被系统视为同一个，
     * `FLAG_UPDATE_CURRENT` 会让两条通知的落点互相改写。
     */
    private fun contentIntent(context: Context, requestCode: Int, toLife: Boolean): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            if (toLife) putExtra(EXTRA_ROUTE, ROUTE_LIFE)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        // IMPORTANCE_DEFAULT 而非 HIGH：余额偏低不是「马上要做的事」，
        // 不值得横幅打断当前操作（上课/作业提醒才用 HIGH）。
        val channel = NotificationChannel(CHANNEL_ID, "余额提醒", NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = "寝室电费或校园卡余额低于设定值时提醒" }
        manager.createNotificationChannel(channel)
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private fun connectedConstraint(): Constraints =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
}

/**
 * 余额核对 Worker。**类名不要改**：WorkManager 把类名存进自己的库，老版本排下的周期任务
 * 在应用升级后仍是这个名字；改名会让那些任务实例化失败，而 `KEEP` 策略又不会重排，
 * 周期兜底就永久消失了（同 `EbikeFreeRideCheckWorker` 的理由）。
 */
class BalanceAlertCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        BalanceAlertReminder.check(applicationContext)
        return Result.success()
    }
}
