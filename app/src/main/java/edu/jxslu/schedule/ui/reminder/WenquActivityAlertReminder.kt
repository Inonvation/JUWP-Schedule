package edu.jxslu.schedule.ui.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.R
import edu.jxslu.schedule.SubpageRequest
import edu.jxslu.schedule.SubpageScreen
import edu.jxslu.schedule.data.repo.PendingLaunch
import edu.jxslu.schedule.data.wenqu.WenquActivity
import edu.jxslu.schedule.data.prefs.DisplayPrefsStore
import edu.jxslu.schedule.data.repo.PendingNotification
import edu.jxslu.schedule.data.session.AutoSyncRules
import edu.jxslu.schedule.data.wenqu.WenquRepository
import edu.jxslu.schedule.domain.InboxKeys
import edu.jxslu.schedule.domain.InboxKind
import edu.jxslu.schedule.domain.WenquRules
import edu.jxslu.schedule.subpageLaunchIntent
import kotlinx.coroutines.flow.first
import edu.jxslu.schedule.data.prefs.setWenquActivityCheckMillis
import edu.jxslu.schedule.data.prefs.setWenquActivityLastSeenId
import edu.jxslu.schedule.data.prefs.setWenquGrabPinnedAlertedId
import edu.jxslu.schedule.data.prefs.setWenquGrabPinnedId
import edu.jxslu.schedule.data.prefs.wenquActivityAlertEnabled
import edu.jxslu.schedule.data.prefs.wenquActivityCheckMillis
import edu.jxslu.schedule.data.prefs.wenquActivityIntervalHours
import edu.jxslu.schedule.data.prefs.wenquActivityLastSeenId
import edu.jxslu.schedule.data.prefs.wenquGrabPinnedAlertedId
import edu.jxslu.schedule.data.prefs.wenquGrabPinnedId

/**
 * 新增志愿活动提醒（DESIGN §4.45，2026-10-08）：问渠平台上出现新的**可报名**活动时发一条通知。
 *
 * 编排搭 [ScoreAlertReminder] 的周期任务（**第五路**，不新开 WorkManager 任务），
 * 闸门与节奏在本类内：开关 → 间隔（默认 24h）→ 真抓一次列表。
 *
 * 四条口径（改之前先读）：
 *
 * 1. **首跑只建基线、不提醒**：第一次扫到的全量是基线（见 [WenquRules.isNewRecruiting]），
 *    否则用户一开开关就被几十条历史活动轰一遍。
 * 2. **只报「招募中」**：平台会把补录的老活动插进列表（实测 id 最大的一条是「补录」且已结束），
 *    按 id 无差别报会把已经结束的活动报成「新活动」。
 * 3. **扫描范围是一整页（100 条）**：`volunteer/v2` **不保证按 id 排序**（实测前 21 条严格降序、
 *    之后跳回旧 id），只扫 20 条会漏掉夹在中间的新活动。
 * 4. **检查时刻只在成功时落**：失败（未登录 / 网络 / 5xx）不写，下一轮还试——同成绩/考试的纪律。
 *
 * 通知走免扰时段拦截（DESIGN §3.7）：新活动是**事实型**（eventAt = null），
 * 时段内拦下入队、时段结束补发；收件箱照常留痕（通知被 ROM 吞了也看得到）。
 */
object WenquActivityAlertReminder {

    private const val TAG = "WenquActivity"

    private const val CHANNEL_ID = "wenqu_activity"
    private const val NOTIFICATION_TAG = "wenqu_activity"

    /** 固定通知 id：与上课（1001/1004）、作业（1002）、余额（1005/1006）、成绩（1008/1009）、
     *  选课（1010）、调课/U净（1011）互不覆盖。 */
    private const val NOTIFICATION_ID = 1013

    /** 通知点击落点的 requestCode。**不能复用通知 id**，也不能与既有落点
     *  （0 / 2000 / 3005 / 3006 / 3008–3012…）撞——撞了会互相改写 EXTRA。 */
    private const val CONTENT_REQUEST_CODE = 3013

    /** 「盯着的活动名额空出」用另一套 id / 落点，别与新活动那条互相覆盖（2026-10-15）。 */
    private const val SLOT_NOTIFICATION_TAG = "wenqu_slot"
    private const val SLOT_NOTIFICATION_ID = 1016
    private const val SLOT_REQUEST_CODE = 3015

    /** 一次扫描取多少条。见类注释口径 3：接口不保证排序，多取一点才圈得住新活动。 */
    private const val SCAN_LIMIT = 100

    /** 通知 / 收件箱正文最多列几个活动名，多出的折叠成「等 N 个」。 */
    private const val MAX_LINES = 3

    /**
     * 设置变更（开关 / 间隔）后的入口：清自己那一路的检查时刻 + 重排周期任务 + 立刻评估一次。
     *
     * **只碰自己这一路**：`ScoreAlertReminder.onSettingsChanged` 会把五路的检查时刻一起清 0，
     * 那样用户只是动一下问渠的开关，成绩 / 考试 / 选课 / 调课四路就各打一次教务
     * （2026-10-08 复查发现）——问渠是第三方功能，不该顺带骚扰教务。
     */
    suspend fun onSettingsChanged(context: Context) {
        runCatching {
            Graph.displayPrefs(context).setWenquActivityCheckMillis(0)
        }.onFailure { Log.w(TAG, "clear check millis failed", it) }
        // 周期任务的间隔取五路最小档，开关一变就得重排；但即时评估只叫醒自己那一路
        ScoreAlertReminder.ensurePeriodicWork(context)
        ScoreAlertReminder.enqueueWenquCheck(context)
    }

    /**
     * 盯的活动变了（pin / unpin，2026-10-15）：共享周期任务要不要存在跟着变，重排一次。
     * **只重排、不立刻叫醒**——盯一条活动没必要当场打一次接口，下一轮周期会看。
     */
    suspend fun onPinChanged(context: Context) {
        runCatching { ScoreAlertReminder.ensurePeriodicWork(context) }
            .onFailure { Log.w(TAG, "ensurePeriodicWork failed", it) }
    }

    /**
     * 核对一次。幂等，可任意重复调用；任何失败都不抛出（后台任务里失败不该让 Worker 报错重试，
     * 重试会再打一次第三方接口）。
     */
    suspend fun check(context: Context) {
        runCatching {
            val prefs = Graph.displayPrefs(context)
            // 没登录就别试了：ensureToken 会直接抛，白跑一趟
            val repo = Graph.wenqu(context)
            if (!repo.loggedIn.value) return

            // 「盯的活动可以报名了」（2026-10-15）：不挂「新增活动提醒」开关——盯它本身就是
            // 用户的显式选择。也不进免扰时段：可以报名是**当下要动**的事，压到时段结束就没用了。
            checkPinnedSlot(context, prefs, repo)

            // 新增活动提醒：走它自己的开关与间隔
            if (!prefs.wenquActivityAlertEnabled.first()) return
            val intervalHours =
                WenquRules.coerceActivityIntervalHours(prefs.wenquActivityIntervalHours.first())
            val now = System.currentTimeMillis()
            if (!AutoSyncRules.shouldAttemptAt(prefs.wenquActivityCheckMillis(), intervalHours, now)) return

            val page = repo.scanActivities(SCAN_LIMIT)
            val lastSeen = prefs.wenquActivityLastSeenId()
            val maxId = page.maxOfOrNull { it.id } ?: 0L
            val fresh = page.filter { WenquRules.isNewRecruiting(it.id, it.status, lastSeen) }

            // 成功才落：检查时刻 + 基线（基线只前进不后退——接口乱序时不能让 id 缩回去）
            prefs.setWenquActivityCheckMillis(now)
            if (maxId > lastSeen) prefs.setWenquActivityLastSeenId(maxId)
            if (fresh.isEmpty()) return

            val title = if (fresh.size == 1) "新志愿活动：${fresh[0].title}" else "有 ${fresh.size} 个新的志愿活动"
            val text = activityText(fresh)

            // 收件箱留痕（DESIGN §4.38）：入箱不依赖通知——通知被 ROM 吞掉时它是唯一的兜底。
            // 键用本批最大的 id：同一批只留一条，之后更新的活动才算新事实。
            Graph.inboxRepository(context).post(
                kind = InboxKind.WENQU_ACTIVITY,
                dedupeKey = InboxKeys.WENQU_ACTIVITY_PREFIX + fresh.maxOf { it.id },
                title = title,
                body = text,
                route = SubpageScreen.WENQU.name,
            )

            val notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
            if (!notificationsEnabled) return
            // 免扰时段（DESIGN §3.7）：新活动是事实型通知（eventAt = null），拦下待补发
            val held = QuietHoursGate.intercept(
                context,
                PendingNotification(
                    tag = NOTIFICATION_TAG,
                    notificationId = NOTIFICATION_ID,
                    channelId = CHANNEL_ID,
                    category = NotificationCompat.CATEGORY_REMINDER,
                    title = title,
                    text = text,
                    enqueuedAt = now,
                    launch = PendingLaunch(
                        kind = PendingLaunch.Kind.SUBPAGE,
                        requestCode = CONTENT_REQUEST_CODE,
                        screen = SubpageScreen.WENQU.name,
                        clearTask = true,
                    ),
                ),
            )
            if (!held) postNotification(context, title, text)
        }.onFailure { Log.w(TAG, "check failed", it) }
    }

    /**
     * **盯着的活动现在可以报名了吗**（2026-10-15，DESIGN §4.45「盯指定活动」的后台一路）。
     *
     * 拉的是**详情**（`refreshIndexItem`，实时名额）而不是列表——快照里深层条目的名额会冻住。
     * 可以报名了（`applyBlockReason` 回空，名额空出 / 报名开放两种情形都算）→ 发通知 + 入箱；
     * 同一条只报一次（`wenqu_grab_pinned_alerted_id`，重新盯 / 取消盯时清 0）。
     * **盯的活动转为永远等不到的状态（结束 / 取消 / 报名截止 / 已报名）时静默取消盯**
     * （2026-10-16，`WenquRules.pinFutile`）——别让它永远挂着白拉详情。
     * **不挂开关**（盯 = 用户显式选择）、**不过免扰时段**（当下要动的事，压到时段结束就没意义了）。
     */
    private suspend fun checkPinnedSlot(
        context: Context,
        prefs: DisplayPrefsStore,
        repo: WenquRepository,
    ) {
        val pinnedId = prefs.wenquGrabPinnedId.first()
        if (pinnedId <= 0L) return
        if (prefs.wenquGrabPinnedAlertedId() == pinnedId) return // 这条已经报过，别重复
        val activity = runCatching { repo.refreshIndexItem(pinnedId) }.getOrNull() ?: return
        // 已经等不到了（结束 / 取消 / 报名截止 / 已报名）：静默取消盯，别永远挂着
        // （2026-10-16；前台蹲点发现同一状态时也会清）。清完重排一次周期任务——
        // 盯是「五路全关也保留任务」的例外，例外没了就该回到全关即撤。
        if (WenquRules.pinFutile(
                status = activity.status,
                applied = activity.applied,
                applyEndTime = activity.applyEndTime,
                now = System.currentTimeMillis(),
            )
        ) {
            prefs.setWenquGrabPinnedId(0L)
            prefs.setWenquGrabPinnedAlertedId(0L)
            ScoreAlertReminder.ensurePeriodicWork(context)
            return
        }
        val blocked = WenquRules.applyBlockReason(
            status = activity.status,
            applied = activity.applied,
            peopleNum = activity.peopleNum,
            balanceNum = activity.balanceNum,
            applyStartTime = activity.applyStartTime,
            applyEndTime = activity.applyEndTime,
            blacklisted = repo.cachedIndex()?.blacklisted == true,
            now = System.currentTimeMillis(),
        )
        if (blocked.isNotEmpty()) return // 还满着 / 已结束 / 已报名…：继续等
        prefs.setWenquGrabPinnedAlertedId(pinnedId)

        val name = activity.title.ifBlank { "你盯的志愿活动" }
        val title = "可以报名了：$name"
        val text = "你盯的活动现在可以报名了。去问渠报名（报名仍要你自己点一下）。"

        Graph.inboxRepository(context).post(
            kind = InboxKind.WENQU_ACTIVITY,
            dedupeKey = InboxKeys.WENQU_SLOT_PREFIX + pinnedId,
            title = title,
            body = text,
            route = SubpageScreen.WENQU.name,
        )
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            postSlotNotification(context, title, text)
        }
    }

    /**
     * 「可以报名了」的通知（时间敏感）。渠道与新活动共用 `wenqu_activity`，但 id / 落点独立。
     * **直接震动**（同每周日那条的理由：HyperOS 上第三方通知的振动默认关，不震等于静默）。
     */
    private fun postSlotNotification(context: Context, title: String, text: String) {
        ensureChannel(context)
        ReminderVibration.vibrate(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bell)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    SLOT_REQUEST_CODE,
                    subpageLaunchIntent(context, SubpageRequest(SubpageScreen.WENQU))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
        runCatching {
            NotificationManagerCompat.from(context)
                .notify(SLOT_NOTIFICATION_TAG, SLOT_NOTIFICATION_ID, notification)
        }.onFailure { Log.w(TAG, "slot notify failed", it) }
    }

    /** 通知与收件箱共用同一份正文，两处各写迟早漂。 */
    private fun activityText(fresh: List<WenquActivity>): String {
        val lines = fresh.take(MAX_LINES).map { activity ->
            buildString {
                append(activity.title.ifBlank { "志愿服务活动" })
                val whenText = WenquRules.timeRange(activity.startTime, activity.endTime)
                if (whenText.isNotBlank()) append(' ').append(whenText)
                if (activity.address.isNotBlank()) append(' ').append(activity.address)
            }
        }
        val rest = fresh.size - lines.size
        return buildString {
            append(lines.joinToString("\n"))
            if (rest > 0) append("\n等 $rest 个")
        }
    }

    private fun postNotification(context: Context, title: String, text: String) {
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bell)
            .setContentTitle(title)
            .setContentText(text.lines().firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    CONTENT_REQUEST_CODE,
                    subpageLaunchIntent(context, SubpageRequest(SubpageScreen.WENQU))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
        runCatching {
            NotificationManagerCompat.from(context)
                .notify(NOTIFICATION_TAG, NOTIFICATION_ID, notification)
        }.onFailure { Log.w(TAG, "notify failed", it) }
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        // IMPORTANCE_DEFAULT：新活动不是「马上要做的事」，不值得横幅打断
        val channel = NotificationChannel(CHANNEL_ID, "志愿活动提醒", NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = "问渠平台上出现新的可报名志愿活动时提醒" }
        manager.createNotificationChannel(channel)
    }
}
