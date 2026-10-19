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
import edu.jxslu.schedule.data.repo.DormRepairSync
import edu.jxslu.schedule.data.repo.PendingLaunch
import edu.jxslu.schedule.data.repo.PendingNotification
import edu.jxslu.schedule.domain.DormRepairRecord
import edu.jxslu.schedule.domain.DormRepairRules
import edu.jxslu.schedule.subpageLaunchIntent

/**
 * 宿舍报修办结提醒（DESIGN §4.49，2026-10-19）。
 *
 * 只在**打开 App 时**核对一次（用户口径：不做后台轮询/WorkManager）：
 * [DormRepairSync.checkOnAppOpen] 返回「刚完成」的记录后，这里发一条通知。
 * 事实入箱由同步层负责（通知开关只拦通知，不拦入箱，见 `.agents/rules/inbox.md`）。
 *
 * 三条口径：
 * 1. **首跑不通知**：`DormRepairSync` 已按「上次未结束 → 这次结束」筛过；
 * 2. **过期不通知**：完成时间超过 3 天的记录只入箱，不再弹通知（用户一周后打开 App
 *    不该被一条过期的「已完成」打扰）；
 * 3. **免扰时段入队**：事实型通知（`eventAt = null`）走 `QuietHoursGate`，时段结束补发。
 */
object DormRepairReminder {

    private const val TAG = "DormRepairReminder"
    private const val CHANNEL_ID = "dorm_repair"
    private const val NOTIFICATION_TAG = "dorm_repair"

    /** 与既有提醒错开：1017 是问渠预报名，1018 给报修办结。 */
    private const val NOTIFICATION_ID = 1018
    private const val CONTENT_REQUEST_CODE = 3018

    /** App 打开：检查一次并处理完成通知。 */
    suspend fun checkOnAppOpen(context: Context) {
        runCatching {
            val result = Graph.dormRepairSync(context).checkOnAppOpen()
            if (result is DormRepairSync.CheckResult.Ok) {
                postIfNeeded(context, result.justFinished)
            }
        }.onFailure { Log.w(TAG, "check on app open failed", it) }
    }

    /**
     * 报修页关闭（可能刚提交）：同步层延迟后刷新一次，让卡片尽快出现。
     * **不发通知**——用户此刻就在 App 里，卡片自己会变；通知留给下次打开 App。
     */
    suspend fun onRepairWindowClosed(context: Context) {
        runCatching {
            Graph.dormRepairSync(context).onRepairWindowClosed()
        }.onFailure { Log.w(TAG, "refresh after repair window failed", it) }
    }

    private suspend fun postIfNeeded(context: Context, justFinished: List<DormRepairRecord>) {
        if (justFinished.isEmpty()) return
        val now = System.currentTimeMillis()
        val fresh = justFinished.filter { record ->
            val at = DormRepairRules.finishedAtOf(record) ?: now
            now - at <= DormRepairRules.RETAIN_FINISHED_MS
        }
        if (fresh.isEmpty()) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return

        val latest = fresh.maxByOrNull { it.finishedAt ?: it.updatedAt } ?: fresh.first()
        val title = if (fresh.size == 1) "宿舍报修已完成" else "${fresh.size} 条宿舍报修已完成"
        val text = buildString {
            append(completionText(latest))
            if (fresh.size > 1) append(" 等 ${fresh.size} 条已办结。")
        }
        val launch = PendingLaunch(
            kind = PendingLaunch.Kind.SUBPAGE,
            requestCode = CONTENT_REQUEST_CODE,
            screen = SubpageScreen.DORM_REPAIR.name,
            focusItemId = latest.formUserId.toString(),
            clearTask = true,
        )
        // 先建渠道再交给免扰闸门：否则补发时闸门只能用通用「提醒」渠道名兜底
        ensureChannel(context)
        val held = QuietHoursGate.intercept(
            context,
            PendingNotification(
                tag = NOTIFICATION_TAG,
                notificationId = NOTIFICATION_ID,
                channelId = CHANNEL_ID,
                category = NotificationCompat.CATEGORY_REMINDER,
                title = title,
                text = text,
                eventAt = null,
                enqueuedAt = now,
                launch = launch,
            ),
        )
        if (!held) post(context, title, text, latest.formUserId)
    }

    private fun post(context: Context, title: String, text: String, formUserId: Long) {
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
                    subpageLaunchIntent(
                        context,
                        SubpageRequest(SubpageScreen.DORM_REPAIR, focusItemId = formUserId.toString()),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
        runCatching {
            NotificationManagerCompat.from(context)
                .notify(NOTIFICATION_TAG, NOTIFICATION_ID, notification)
        }.onFailure { Log.w(TAG, "notify failed", it) }
    }

    private fun completionText(record: DormRepairRecord): String {
        val where = listOf(record.dorm, record.fault)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        return if (where.isBlank()) {
            "你的宿舍报修已办结，点按查看进度。"
        } else {
            "$where 已办结，点按查看进度。"
        }
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "报修进度",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = "宿舍报修办结时提醒" }
        manager.createNotificationChannel(channel)
    }
}
