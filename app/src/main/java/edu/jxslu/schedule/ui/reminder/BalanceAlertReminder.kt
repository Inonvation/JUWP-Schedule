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
import edu.jxslu.schedule.data.prefs.DisplayPrefsStore
import edu.jxslu.schedule.data.ykt.YktCredentialStore
import edu.jxslu.schedule.domain.BalanceAlert
import edu.jxslu.schedule.domain.BalanceAlertSource
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * 余额提醒（DESIGN §3.10 / §3.13）：寝室电费与一卡通余额低于设定值时发一条通知。
 *
 * 与上课/作业提醒（[ClassReminder]）的差别：那两个是**时间点**驱动（到点即发，闹钟为主），
 * 这里是**状态**驱动（余额低了才发），没有可预排的时刻，所以只有一条路：
 * WorkManager 每天一次周期核对 + 冷启动补查 + 设置变更后立即评估。
 * 不用 `setAlarmClock`——那会在状态栏挂一枚闹钟图标，对「余额低了」是错的语义。
 *
 * 三条口径（改之前先读）：
 *
 * 1. **每天最多一条**：闸门是「上次**成功**检查日期」（[DisplayPrefsStore.alertLastCheckDate]）。
 *    成功取到数据才落日期（无论是否低于阈值），所以同一天不会重复打第三方接口、
 *    也不会重复发通知；失败不落日期，当天还有补查机会——与作业提醒「发出后才落键」同口径。
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

    /** 周期核对（每天一次）。 */
    private const val PERIODIC_WORK = "balance_alert_periodic"

    /** 即时评估（冷启动 / 开关与阈值变更）。 */
    private const val ONE_SHOT_WORK = "balance_alert_check"

    /**
     * 每日核对时刻（本地时区整点）。排在早上，用户起床后能看到；
     * WorkManager 周期任务在省电模式下可能被推迟，这只是「尽量」。
     */
    private const val CHECK_HOUR = 9

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
     * 周期核对：两个开关都关时**撤销**任务而不是留着空跑——它每天都会唤醒设备一次，
     * 能省就省（`ClassReminder` 那种「排着空跑」是因为它还要重排闹钟，这里没有这个负担）。
     * 幂等（KEEP）：周期与首次延迟已排过就不重排，免得每次冷启动都把相位推后。
     */
    suspend fun ensurePeriodicWork(context: Context) {
        runCatching {
            val prefs = Graph.displayPrefs(context)
            val workManager = WorkManager.getInstance(context)
            if (!prefs.powerAlertEnabled.first() && !prefs.yktAlertEnabled.first()) {
                workManager.cancelUniqueWork(PERIODIC_WORK)
                return
            }
            val request = PeriodicWorkRequestBuilder<BalanceAlertCheckWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(millisUntilNextCheck(), TimeUnit.MILLISECONDS)
                .setConstraints(connectedConstraint())
                .build()
            workManager.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
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
     * 设置变更（开关或阈值）后的统一入口：**清掉该来源的当日节奏**再排一次即时评估。
     *
     * 清日期的理由是语义而非省事：用户刚把提醒线从 ¥20 调到 ¥50，就是想按新规则立刻评估一次；
     * 若沿用「今天已检查过」的闸门，新阈值当天不会生效，用户会以为功能坏了。
     */
    suspend fun onSettingsChanged(context: Context, source: BalanceAlertSource) {
        runCatching {
            Graph.displayPrefs(context).clearAlertLastCheckDate(source)
            ensurePeriodicWork(context)
        }.onFailure { Log.w(TAG, "onSettingsChanged failed", it) }
        enqueueCheck(context)
    }

    /**
     * 核对一次：逐个来源判断「今天还没成功检查过」→ 取数 → 落日期 → 低于阈值才发通知。
     * 整体兜异常：后台任务里任何失败都不该让 Worker 报错重试（重试会再打一次第三方接口）。
     */
    suspend fun check(context: Context) {
        runCatching {
            val prefs = Graph.displayPrefs(context)
            val powerOn = prefs.powerAlertEnabled.first()
            val yktOn = prefs.yktAlertEnabled.first()
            if (!powerOn && !yktOn) return
            // 通知被系统/用户整体关闭时静默跳过：写不出去也不该白打一次接口
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
            val credentials = Graph.yktCredentialStore(context).read() ?: return
            val today = LocalDate.now()
            // 通知落点：生活页开关关掉时不给 extra（落今日页），免得落在一个不存在的 Tab 上
            val toLife = prefs.lifeTabEnabled.first()
            if (powerOn) checkPower(context, prefs, credentials, today, toLife)
            if (yktOn) checkYkt(context, prefs, credentials, today, toLife)
        }.onFailure { Log.w(TAG, "check failed", it) }
    }

    // ------------------------------------------------------------------
    // 寝室电费
    // ------------------------------------------------------------------

    private suspend fun checkPower(
        context: Context,
        prefs: DisplayPrefsStore,
        credentials: YktCredentialStore.Credentials,
        today: LocalDate,
        toLife: Boolean,
    ) {
        if (!BalanceAlert.isDueToday(prefs.alertLastCheckDate(BalanceAlertSource.Power), today)) return
        val snapshot = runCatching {
            Graph.powerRepository(context).snapshot(credentials.username, credentials.password)
        }.getOrNull() ?: return // 失败不落日期：当天还能补查
        prefs.setAlertLastCheckDate(BalanceAlertSource.Power, BalanceAlert.dateKey(today))

        val meter = snapshot.meter
        val yuan = BalanceAlert.remainingYuan(meter.remain, snapshot.feeItem.priceYuan) ?: return
        val threshold = prefs.powerAlertYuan.first()
        if (!BalanceAlert.isLow(yuan, threshold)) return
        val room = meter.room.room?.takeIf { it.isNotBlank() } ?: snapshot.feeItem.room?.name
        post(
            context = context,
            channelTag = POWER_NOTIFICATION_TAG,
            notificationId = POWER_NOTIFICATION_ID,
            title = "寝室电费偏低",
            text = buildString {
                if (!room.isNullOrBlank()) append("$room ")
                append("剩余 ¥%.2f".format(yuan))
                meter.remain?.let { append("（%.2f 度）".format(it)) }
                append("，低于提醒线 ¥$threshold，记得充值")
            },
            toLife = toLife,
        )
    }

    // ------------------------------------------------------------------
    // 一卡通余额
    // ------------------------------------------------------------------

    private suspend fun checkYkt(
        context: Context,
        prefs: DisplayPrefsStore,
        credentials: YktCredentialStore.Credentials,
        today: LocalDate,
        toLife: Boolean,
    ) {
        if (!BalanceAlert.isDueToday(prefs.alertLastCheckDate(BalanceAlertSource.Ykt), today)) return
        val cards = runCatching {
            Graph.yktRepository(context).cards(credentials.username, credentials.password)
        }.getOrNull() ?: return // 失败不落日期：当天还能补查
        prefs.setAlertLastCheckDate(BalanceAlertSource.Ykt, BalanceAlert.dateKey(today))

        // 口径 = **正式卡**余额（付款码实际扣款的那个钱包）。电子账户是线上缴费用的
        // 独立钱包（DESIGN §3.10 账户口径），混进同一个阈值会让「还剩多少能刷」失去意义。
        if (cards.isEmpty()) return
        val cardYuan = cards.sumOf { it.cardBalanceFen } / 100.0
        val threshold = prefs.yktAlertYuan.first()
        if (!BalanceAlert.isLow(cardYuan, threshold)) return
        post(
            context = context,
            channelTag = YKT_NOTIFICATION_TAG,
            notificationId = YKT_NOTIFICATION_ID,
            title = "校园卡余额偏低",
            text = "正式卡余额 ¥%.2f，低于提醒线 ¥$threshold，记得充值".format(cardYuan),
            toLife = toLife,
        )
    }

    // ------------------------------------------------------------------
    // 通知
    // ------------------------------------------------------------------

    /** 发一条余额提醒。无权限/被系统拒绝时静默失败（写不出去不该把核对链路搞崩）。 */
    private fun post(
        context: Context,
        channelTag: String,
        notificationId: Int,
        title: String,
        text: String,
        toLife: Boolean,
    ) {
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
        runCatching {
            NotificationManagerCompat.from(context)
                .notify(channelTag, notificationId, notification)
        }.onFailure { Log.w(TAG, "notify failed", it) }
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

    /** 距下一个 [CHECK_HOUR] 点的毫秒数（已过则取明天同一时刻）。 */
    internal fun millisUntilNextCheck(now: ZonedDateTime = ZonedDateTime.now()): Long {
        val todayTarget = now.withHour(CHECK_HOUR).withMinute(0).withSecond(0).withNano(0)
        val target = if (todayTarget.isAfter(now)) todayTarget else todayTarget.plusDays(1)
        return Duration.between(now, target).toMillis()
    }
}

/**
 * 余额核对 Worker。**类名不要改**：WorkManager 把类名存进自己的库，老版本排下的周期任务
 * 在应用升级后仍是这个名字；改名会让那些任务实例化失败，而 `KEEP` 策略又不会重排，
 * 每日兜底就永久消失了（同 `EbikeFreeRideCheckWorker` 的理由）。
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
