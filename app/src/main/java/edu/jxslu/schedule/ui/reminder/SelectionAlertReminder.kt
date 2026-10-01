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
import edu.jxslu.schedule.domain.SelectionRounds
import edu.jxslu.schedule.subpageLaunchIntent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.first

/**
 * 选课轮次提醒（DESIGN §4.35）：轮次**开始前 30 分钟**与**截止前 6 小时**各发一条通知。
 *
 * 与上课/骑行提醒的排法同构（`ClassReminder` / `EbikeFreeRideReminder`）：
 * 主 = `AlarmManager.setAlarmClock` 单点精确闹钟（到点即触发、不受 Doze 推迟、
 * 不需要特殊权限）；兜 = 一次性 Work（闹钟落点/开机/开关变更）与既有周期核对
 * （挂在 `ScoreAlertReminder` 的周期任务上，间隔共用 `alert_interval_hours`）。
 *
 * 三条口径（改之前先读）：
 * 1. **只在能解析出时间时才排**：轮次时间来自教务原文的容错解析（`SelectionRounds.parseTimeRange`），
 *    解析不了就没有提醒点——宁可少提醒，不猜时间。
 * 2. **迟到的照发、过期的作废**：提前量已过但事件没结束的提醒点钳到「当下」发出去；
 *    事件已结束的点在 `reminderPoints` 里就不生成。已发键（`轮次id|start` / `轮次id|end`）
 *    落 DataStore，闹钟与周期核对共用——同一条提醒只发一次。
 * 3. **通知被系统关掉时不发也不行**：发不出去就不落键，下一轮还有机会（同作业提醒）。
 *
 * **不做高频教务轮询**：检查链自己会顺带刷新轮次快照（`SelectionSync.syncRounds`，
 * 30 分钟闸门），但周期任务的节奏完全由 `alert_interval_hours` 决定，没有额外轮询。
 */
object SelectionAlertReminder {

    private const val TAG = "SelectionAlert"

    /** 一次性核对（闹钟落点 / 开机 / 开关变更落点）：Receiver 里不能做长任务。 */
    private const val ONE_SHOT_WORK = "selection_alert_check"

    /**
     * 闹钟的 requestCode。与上课提醒（4002）、骑行提醒（4003）错开：
     * PendingIntent 的身份是「requestCode + Intent.filterEquals」，撞了会互相顶掉。
     */
    private const val ALARM_REQUEST_CODE = 4004

    private const val CHANNEL_ID = "selection_alert"
    private const val NOTIFICATION_TAG = "selection_alert"

    /** 固定通知 id：与上课（1001/1004）、作业（1002/1003）、余额等既有通知互不覆盖。 */
    private const val NOTIFICATION_ID = 1010

    /**
     * 通知点击落点的 requestCode。**不能复用通知 id**：PendingIntent 的身份是
     * 「requestCode + Intent.filterEquals」，与既有落点（0 / 1002 / 3008 / 3009…）
     * 撞了就互相改写 EXTRA（同 `ScoreAlertReminder` 3008/3009 的纪律）。
     */
    private const val CONTENT_REQUEST_CODE = 3010

    /** 到期判定宽限：触发时刻已到（或 1 分钟内将到）的提醒点算「该发了」。 */
    private const val DUE_GRACE_MS = 60 * 1000L

    /** 最远只排 45 天内的提醒点：更远的轮次不值当占一个系统闹钟，靠后续同步重排。 */
    private const val MAX_LEAD_MS = 45L * 24 * 60 * 60 * 1000

    /** 一次性核对（闹钟落点 / 开机 / 开关变更）。 */
    fun enqueueCheck(context: Context) {
        val request = OneTimeWorkRequestBuilder<SelectionAlertCheckWorker>().build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(ONE_SHOT_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    /**
     * 重排下一个提醒闹钟（幂等）：取「未发过、在最远提前量内」的最早提醒点；
     * 一个都没有（提醒关着、没有轮次、时间解析不了、全发过）就撤销已排闹钟——
     * 「关掉开关后通知还在响」比不响严重得多。
     */
    suspend fun scheduleNext(context: Context) {
        runCatching {
            val prefs = Graph.displayPrefs(context)
            val manager = context.getSystemService(AlarmManager::class.java) ?: return
            val pending = alarmPendingIntent(context)
            if (!prefs.selectionAlertEnabled.first()) {
                manager.cancel(pending)
                return
            }
            val now = System.currentTimeMillis()
            val sent = prefs.selectionRemindedKeys()
            val next = SelectionRounds.reminderPoints(Graph.selectionSync(context).cachedRounds(), now)
                .firstOrNull { it.key !in sent && it.at <= now + MAX_LEAD_MS }
            if (next == null) {
                manager.cancel(pending)
                return
            }
            // 系统级精确闹钟：到点即触发，不受 Doze/省电推迟，无需特殊权限。
            // showIntent 交给系统时钟的闹钟页（同上课提醒：状态栏那枚图标只表示"有个闹钟排着"）
            val clockPending = PendingIntent.getActivity(
                context,
                0,
                Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            manager.setAlarmClock(AlarmManager.AlarmClockInfo(next.at, clockPending), pending)
        }.onFailure { Log.w(TAG, "scheduleNext failed", it) }
    }

    /**
     * 核对一次：顺带刷新轮次快照（走 30 分钟闸门，失败静默用旧快照），
     * 发掉到期的提醒（**一次最多一条**，剩下的留给下一轮或紧随其后的重排闹钟），
     * 再重排下一个闹钟。幂等，可任意重复调用。
     */
    suspend fun check(context: Context) {
        runCatching {
            val prefs = Graph.displayPrefs(context)
            if (!prefs.selectionAlertEnabled.first()) {
                cancelAlarm(context)
                return
            }
            Graph.selectionSync(context).syncRounds()

            val now = System.currentTimeMillis()
            val sent = prefs.selectionRemindedKeys().toMutableSet()
            val due = SelectionRounds.reminderPoints(
                Graph.selectionSync(context).cachedRounds(),
                now,
            ).firstOrNull { it.key !in sent && it.at <= now + DUE_GRACE_MS }
            if (due != null && NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                if (postNotification(context, due, now)) prefs.addSelectionRemindedKey(due.key)
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
        Intent(context, SelectionAlertReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** 发一条选课提醒；成功（系统真的收下了）返回 true，调用方才落已发键。 */
    private fun postNotification(
        context: Context,
        point: SelectionRounds.ReminderPoint,
        now: Long,
    ): Boolean {
        ensureChannel(context)
        val (title, text) = when (point.kind) {
            SelectionRounds.ReminderPoint.Kind.StartSoon ->
                if (point.eventAt > now) {
                    "选课开始 · ${point.roundName}" to
                        "${formatEventTime(point.eventAt, now)} 开始，记得去选课"
                } else {
                    "选课进行中 · ${point.roundName}" to "轮次正在进行，别忘了去选课"
                }
            SelectionRounds.ReminderPoint.Kind.EndSoon ->
                if (point.eventAt > now) {
                    "选课截止 · ${point.roundName}" to
                        "${formatEventTime(point.eventAt, now)} 截止，还没选完要抓紧"
                } else {
                    "选课即将截止 · ${point.roundName}" to "轮次快结束了，还没选完要抓紧"
                }
        }
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
                    subpageLaunchIntent(context, SubpageRequest(SubpageScreen.SELECTIONS))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
        return runCatching {
            NotificationManagerCompat.from(context)
                .notify(NOTIFICATION_TAG, NOTIFICATION_ID, notification)
        }.isSuccess
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        // IMPORTANCE_HIGH：选课是「到点就得动手」的事，值得横幅（弹不弹由系统/ROM 决定）
        val channel = NotificationChannel(CHANNEL_ID, "选课提醒", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "选课轮次开始前 30 分钟、截止前 6 小时提醒" }
        manager.createNotificationChannel(channel)
    }

    /** 事件时刻的展示：当天只给 `HH:mm`，跨天带 `MM-dd`（本地时区）。 */
    private fun formatEventTime(millis: Long, now: Long): String {
        val zone = ZoneId.systemDefault()
        val target = Instant.ofEpochMilli(millis).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val pattern = if (target.toLocalDate() == today) "HH:mm" else "MM-dd HH:mm"
        return target.format(DateTimeFormatter.ofPattern(pattern))
    }
}

/** 提醒闹钟落点：只把核对排进 WorkManager，立即返回。 */
class SelectionAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SelectionAlertReminder.enqueueCheck(context)
    }
}

/**
 * 选课提醒的核对 Worker。**类名不要改**：WorkManager 把类名存进自己的库，
 * 老版本排下的周期/一次性任务在应用升级后仍是这个名字（同 `ScoreAlertCheckWorker`）。
 */
class SelectionAlertCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        SelectionAlertReminder.check(applicationContext)
        return Result.success()
    }
}
