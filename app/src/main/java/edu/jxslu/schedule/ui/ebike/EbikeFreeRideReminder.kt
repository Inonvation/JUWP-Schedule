package edu.jxslu.schedule.ui.ebike

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.R
import edu.jxslu.schedule.SubpageActivity
import edu.jxslu.schedule.SubpageScreen
import edu.jxslu.schedule.domain.EbikeFreeRide
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * 共享单车免费时长提醒（DESIGN §3.9，2026-09-22）。
 *
 * 与上课提醒（`ClassReminder`）同构但**不共用**闹钟：那边的调度和课表/作业
 * 查询耦合，硬塞会把两个功能牵连到一起。这里只管一件事——骑行计时到点前
 * （免费时长 − 提前量）发一条通知。延迟几分钟可接受，用
 * `setAndAllowWhileIdle`（不申请 `SCHEDULE_EXACT_ALARM` 敏感权限），
 * 照上课提醒的先例。
 *
 * 兜底链：页面 ON_RESUME 调 [check] 补发「已到点但没发出去」的通知（覆盖
 * 闹钟被 ROM 推迟 / 进程被杀后恢复）；用户点「结束骑行」或重新计时会重排/撤销。
 * 开机补排**不做**（用户拍板 2026-09-22）：重启后进行中的计时静默失效，可接受。
 */
object EbikeFreeRideReminder {

    private const val TAG = "EbikeFreeRide"
    private const val CHANNEL_ID = "ebike_free_ride"
    private const val NOTIFICATION_TAG = "ebike_free_ride"
    private const val NOTIFICATION_ID = 4201
    private const val ALARM_REQUEST_CODE = 4202
    private const val ONE_SHOT_WORK = "ebike_free_ride_check"
    private const val PERIODIC_WORK = "ebike_free_ride_periodic"

    /**
     * 补发宽限窗（分钟）：闹钟被 ROM 推迟、App 进程被杀后恢复时，
     * 「已过触发点但免费时段未完」的这几分钟仍补发通知。
     * 上限取提前量上限（5）：再晚发「约 N 分钟后结束」就已经失真。
     */
    private const val GRACE_MINUTES = 5L

    /**
     * 记录计时起点并重排闹钟。开关关着也记起点（数据无害），
     * 但不排闹钟；用户中途开开关时由 [check] 按已有起点补排。
     */
    suspend fun startRide(context: Context, startAtMillis: Long) {
        val prefs = Graph.displayPrefs(context)
        prefs.setEbikeRideStartAt(startAtMillis)
        reschedule(context)
    }

    /** 结束骑行：清起点、撤闹钟、撤掉可能还挂着的通知。 */
    suspend fun endRide(context: Context) {
        val prefs = Graph.displayPrefs(context)
        prefs.setEbikeRideStartAt(0L)
        val manager = context.getSystemService(AlarmManager::class.java)
        manager?.cancel(alarmPendingIntent(context))
        NotificationManagerCompat.from(context)
            .cancel(NOTIFICATION_TAG, NOTIFICATION_ID)
    }

    /**
     * 按当前起点/开关重排或撤销闹钟。起点为 0 或开关关 = 撤销；
     * 提前量改动后也要调它（提前量变了触发时刻跟着变）。
     */
    suspend fun reschedule(context: Context) {
        runCatching {
            val prefs = Graph.displayPrefs(context)
            val startAt = prefs.ebikeRideStartAt.first()
            val manager = context.getSystemService(AlarmManager::class.java)
                ?: return
            val pending = alarmPendingIntent(context)
            val enabled = prefs.ebikeFreeReminderEnabled.first()
            val now = System.currentTimeMillis()
            if (!enabled || !EbikeFreeRide.isActive(startAt, now)) {
                manager.cancel(pending)
                return
            }
            val lead = prefs.ebikeFreeLeadMinutes.first()
            val triggerAt = EbikeFreeRide.triggerAtMillis(startAt, lead)
            if (triggerAt <= now) {
                // 已过触发点（如用户中途才开开关）：立即走补发核对
                check(context)
                return
            }
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }.onFailure { Log.w(TAG, "reschedule failed", it) }
    }

    /**
     * 到点核对：计时仍有效/未超窗、开关开着、通知权限有、这条起点没发过
     * 才发。发出后落「已发」标记——同一计时只发一次，重新计时是新起点。
     */
    suspend fun check(context: Context) {
        runCatching {
            val prefs = Graph.displayPrefs(context)
            if (!prefs.ebikeFreeReminderEnabled.first()) return
            val startAt = prefs.ebikeRideStartAt.first()
            val now = System.currentTimeMillis()
            if (!EbikeFreeRide.isActive(startAt, now)) return
            val lead = prefs.ebikeFreeLeadMinutes.first()
            // 宽限窗：错过闹钟后仍补发，但最多 GRACE_MINUTES；
            // 再晚发「约 N 分钟后结束」就失真，直接放弃（等待下一个计时）
            val graceEnd = EbikeFreeRide.triggerAtMillis(startAt, lead) +
                GRACE_MINUTES * 60_000L
            if (now < EbikeFreeRide.triggerAtMillis(startAt, lead) || now >= graceEnd) return
            val notified = prefs.ebikeFreeNotifiedAt.first()
            if (startAt.toString() in notified) return
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
            if (postNotification(context, lead)) {
                prefs.addEbikeFreeNotifiedAt(startAt)
            }
        }.onFailure { Log.w(TAG, "check failed", it) }
    }

    /** 一次性核对（闹钟触发 / 设置变更落点）：BroadcastReceiver 里不能做长任务。 */
    fun enqueueCheck(context: Context) {
        val request = OneTimeWorkRequestBuilder<EbikeFreeRideCheckWorker>()
            .setConstraints(Constraints.NONE)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(ONE_SHOT_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    /** 10 分钟周期核对兜底：闹钟被 ROM 推迟 / 丢失时还能在窗口内补发。幂等（KEEP）。 */
    fun ensurePeriodicWork(context: Context) {
        val request = PeriodicWorkRequestBuilder<EbikeFreeRideCheckWorker>(10, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private fun postNotification(context: Context, lead: Int): Boolean {
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bell)
            .setContentTitle("免费时长快到了")
            .setContentText(EbikeFreeRide.noticeText(lead))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(EbikeFreeRide.noticeText(lead)),
            )
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    0,
                Intent(context, SubpageActivity::class.java)
                    .putExtra("screen", SubpageScreen.EBIKE.name),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
        // 无 POST_NOTIFICATIONS 权限时系统静默丢弃；异常不外漏（照上课提醒口径）
        return runCatching {
            NotificationManagerCompat.from(context)
                .notify(NOTIFICATION_TAG, NOTIFICATION_ID, notification)
        }.isSuccess
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "共享单车免费时长",
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
    }

    private fun alarmPendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            Intent(context, EbikeFreeRideReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}

/** 免费时长到点闹钟落点：显式 PendingIntent，无需 intent-filter。 */
class EbikeFreeRideReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // 照上课提醒的口径：不阻塞广播线程，核对排进 WorkManager
        EbikeFreeRideReminder.enqueueCheck(context)
    }
}

/** 免费时长提醒的核对 Worker：发送窗口内通知。 */
class EbikeFreeRideCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        runCatching {
            EbikeFreeRideReminder.check(applicationContext)
        }
        return Result.success()
    }
}
