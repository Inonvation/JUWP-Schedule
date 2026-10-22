package edu.jxslu.schedule.ui.reminder

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.R
import edu.jxslu.schedule.SubpageRequest
import edu.jxslu.schedule.SubpageScreen
import edu.jxslu.schedule.data.repo.PendingLaunch
import edu.jxslu.schedule.data.repo.PendingNotification
import edu.jxslu.schedule.domain.WenquRules
import edu.jxslu.schedule.subpageLaunchIntent
import kotlinx.coroutines.flow.first
import edu.jxslu.schedule.data.prefs.setWenquWeeklyAlertLastFiredAt
import edu.jxslu.schedule.data.prefs.wenquWeeklyAlertEnabled
import edu.jxslu.schedule.data.prefs.wenquWeeklyAlertLastFiredAt
import edu.jxslu.schedule.data.prefs.wenquWeeklyAlertOffsetMinutes

/**
 * 「每周日 12:00 抢活动」提醒（DESIGN §4.45，2026-10-11；用户口径）。
 *
 * **学校很多志愿活动都是周日中午 12:00 发布、当场开抢**，所以要一条事前蹲点的提醒：
 * 每周日 12:00 **前 N 分钟**（N 可调 1–10，默认 5）响一声，提醒用户去问渠蹲着。
 *
 * 与 [WenquActivityAlertReminder] 是**两件事**，别合并：
 * - 那条是**事后发现**——周期扫列表，扫到新的「招募中」活动才报（依赖接口 + 网络）；
 * - 这条是**事前蹲点**——固定时刻，发布时刻还没到、扫也扫不到新东西，
 *   所以它**一条网络请求都不发**，只有一条精确闹钟。
 *
 * 排法与选课轮次提醒同构（`SelectionAlertReminder`）：主 = `AlarmManager.setAlarmClock`
 * 单点精确闹钟（到点即触发、不受 Doze 推迟、不需要特殊权限）；兜 = 一次性 Work
 * （闹钟落点 / 开机 / 覆盖安装 / 开关变更），冷启动再补一次本地重排。
 *
 * 三条口径（改之前先读）：
 *
 * 1. **已发键是「发布时刻」不是「提醒时刻」**（[WenquRules.WeeklyAlertPoint.publishAt]）：
 *    用户中途把提前量从 5 分钟改成 10 分钟，同一周不该再报一次。
 * 2. **迟到窗口**（[WenquRules.WEEKLY_ALERT_LATE_MS]）：提醒点过去超过 10 分钟就不再补发——
 *    用户要的是「12:00 前就位」，12:30 才响只是噪音（同免扰时段对时刻型提醒的过期口径）。
 * 3. **通知被系统整体关掉时不落键**：这次没发出去，下次冷启动/开机还在窗口内就还能补
 *    （与选课提醒不同——那边有收件箱兜底，这条**不入箱**，因为它是时刻型提醒）。
 *
 * 通知走免扰时段拦截（DESIGN §3.7）：时刻型，`eventAt` = 发布时刻，时段内拦下待补发。
 * **直接震动**（[ReminderVibration]）不挂通用「通知强提醒」开关：这条是用户为「抢」
 * 显式开启的、一周一次的能力，而 HyperOS 上第三方通知的振动开关默认是关的
 * （2026-10-05 设备实测），不直接震等于静默。
 */
object WenquWeeklyAlertReminder {

    private const val TAG = "WenquWeekly"

    /** 一次性核对（闹钟落点 / 开机 / 覆盖安装 / 开关变更落点）：Receiver 里不能做长任务。 */
    private const val ONE_SHOT_WORK = "wenqu_weekly_alert_check"

    /**
     * 闹钟的 requestCode。既有占用：4001 小组件 / 4002 上课 / 4003 骑行免费时长 /
     * 4004 骑行自动还车与选课 / 4005 U净 / 4006 免扰补发 —— 取 4007。
     * PendingIntent 的身份是「requestCode + Intent.filterEquals」，撞了会互相顶掉。
     */
    private const val ALARM_REQUEST_CODE = 4007

    private const val CHANNEL_ID = "wenqu_weekly"
    private const val NOTIFICATION_TAG = "wenqu_weekly"

    /** 固定通知 id：1013 是「新增志愿活动」，这条取 1015（1014 是骑行自动还车）。 */
    private const val NOTIFICATION_ID = 1015

    /**
     * 通知点击落点的 requestCode。**不能复用通知 id**：与既有落点
     * （0 / 3005–3013…）撞了就互相改写 EXTRA。取 3014。
     */
    private const val CONTENT_REQUEST_CODE = 3014

    /** 一次性核对（闹钟落点 / 开机 / 覆盖安装 / 开关变更）。 */
    fun enqueueCheck(context: Context) {
        val request = OneTimeWorkRequestBuilder<WenquWeeklyAlertCheckWorker>().build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(ONE_SHOT_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    /**
     * 设置变更（开关 / 提前量）：只需重排闹钟——这条提醒不扫接口、不搭周期任务，
     * 所以**不要**去碰 `ScoreAlertReminder.onSettingsChanged`（那会顺带骚扰教务四路）。
     */
    suspend fun onSettingsChanged(context: Context) {
        scheduleNext(context)
    }

    /**
     * 重排下一个提醒闹钟（幂等）：开关关着就撤销已排闹钟——
     * 「关掉开关后通知还在响」比不响严重得多（同选课提醒）。
     */
    suspend fun scheduleNext(context: Context) {
        runCatching {
            val prefs = Graph.displayPrefs(context)
            val manager = context.getSystemService(AlarmManager::class.java) ?: return
            val pending = alarmPendingIntent(context)
            if (!prefs.wenquWeeklyAlertEnabled.first()) {
                manager.cancel(pending)
                return
            }
            val offset = WenquRules.coerceWeeklyOffsetMinutes(
                prefs.wenquWeeklyAlertOffsetMinutes.first(),
            )
            val next = WenquRules.nextWeeklyAlertPoint(System.currentTimeMillis(), offset)
            // showIntent 交给系统时钟的闹钟页（同上课 / 选课提醒：状态栏那枚图标只表示「有个闹钟排着」）
            val clockPending = PendingIntent.getActivity(
                context,
                0,
                Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            manager.setAlarmClock(AlarmManager.AlarmClockInfo(next.remindAt, clockPending), pending)
        }.onFailure { Log.w(TAG, "scheduleNext failed", it) }
    }

    /** 到点核对：该发就发，然后重排下一周。幂等，可任意重复调用；失败不抛出。 */
    suspend fun check(context: Context) {
        runCatching {
            val prefs = Graph.displayPrefs(context)
            if (!prefs.wenquWeeklyAlertEnabled.first()) {
                cancelAlarm(context)
                return
            }
            val offset = WenquRules.coerceWeeklyOffsetMinutes(
                prefs.wenquWeeklyAlertOffsetMinutes.first(),
            )
            val now = System.currentTimeMillis()
            val point = WenquRules.lastWeeklyAlertPoint(now, offset)
            val firedAt = prefs.wenquWeeklyAlertLastFiredAt()
            val due = point.publishAt > firedAt && now - point.remindAt in 0..WenquRules.WEEKLY_ALERT_LATE_MS
            if (due) {
                val (title, text) = alertCopy(point, offset, now)
                val notificationsEnabled =
                    NotificationManagerCompat.from(context).areNotificationsEnabled()
                if (notificationsEnabled) {
                    // 免扰时段：时刻型（eventAt = 发布时刻）拦下待补发
                    val held = QuietHoursGate.intercept(
                        context,
                        PendingNotification(
                            tag = NOTIFICATION_TAG,
                            notificationId = NOTIFICATION_ID,
                            channelId = CHANNEL_ID,
                            category = NotificationCompat.CATEGORY_REMINDER,
                            title = title,
                            text = text,
                            eventAt = point.publishAt,
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
                    // 通知真发出去 / 已排队待补发都算「这周报过了」；被系统整体关掉则不落键，
                    // 下次冷启动或开机还在窗口内就还能补
                    prefs.setWenquWeeklyAlertLastFiredAt(point.publishAt)
                }
                // 提醒响过就把活动缓存丢掉：用户点通知进来时不该看到发布前的旧列表
                // （进页照旧是用户动作，网络请求不提前——见 `invalidateActivities` 注释）
                Graph.wenqu(context).invalidateActivities()
            }
            scheduleNext(context)
        }.onFailure { Log.w(TAG, "check failed", it) }
    }

    private fun cancelAlarm(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(alarmPendingIntent(context))
    }

    private fun alarmPendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        ALARM_REQUEST_CODE,
        Intent(context, WenquWeeklyAlertReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** 通知文案。到点前说「提前 N 分钟」，迟到补发时不提提前量（那时说提前反而奇怪）。 */
    internal fun alertCopy(
        point: WenquRules.WeeklyAlertPoint,
        offsetMinutes: Int,
        now: Long,
    ): Pair<String, String> {
        val whenText = WenquRules.weeklyPublishLabel(point.publishAt, now)
        val title = "$whenText 开抢志愿活动"
        val text = if (now < point.publishAt) {
            "学校常在这时发布新的志愿活动，提前 $offsetMinutes 分钟提醒你，记得去问渠蹲着"
        } else {
            "学校常在这时发布新的志愿活动，记得去问渠看看"
        }
        return title to text
    }

    private fun postNotification(context: Context, title: String, text: String) {
        ensureChannel(context)
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
                    CONTENT_REQUEST_CODE,
                    subpageLaunchIntent(context, SubpageRequest(SubpageScreen.WENQU))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
        // 直接震动：不走通知渠道策略（见类注释；HyperOS 上渠道的振动开关默认是关的）
        ReminderVibration.vibrate(context)
        runCatching {
            NotificationManagerCompat.from(context)
                .notify(NOTIFICATION_TAG, NOTIFICATION_ID, notification)
        }.onFailure { Log.w(TAG, "notify failed", it) }
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        // IMPORTANCE_HIGH：这是「到点就得动手」的事，值得横幅（弹不弹由系统 / ROM 决定）
        val channel = NotificationChannel(CHANNEL_ID, "志愿活动开抢提醒", NotificationManager.IMPORTANCE_HIGH)
            .apply {
                description = "每周日 12:00 前提醒你去抢新发布的志愿活动"
                enableVibration(true)
                vibrationPattern = ReminderVibration.PATTERN
            }
        manager.createNotificationChannel(channel)
    }
}

/** 提醒闹钟落点：只把核对排进 WorkManager，立即返回。 */
class WenquWeeklyAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WenquWeeklyAlertReminder.enqueueCheck(context)
    }
}

/**
 * 每周提醒的核对 Worker。**类名不要改**：WorkManager 把类名存进自己的库，
 * 老版本排下的任务在应用升级后仍是这个名字（同 `ScoreAlertCheckWorker`）。
 */
class WenquWeeklyAlertCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        WenquWeeklyAlertReminder.check(applicationContext)
        return Result.success()
    }
}
