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
import edu.jxslu.schedule.data.repo.ScheduleSync
import edu.jxslu.schedule.domain.ScheduleDiff
import edu.jxslu.schedule.subpageLaunchIntent

/**
 * 调课提醒（DESIGN §4.17，2026-10-04 重立）：教务课表与基线比对出差异时发一条通知。
 *
 * 编排搭 [ScoreAlertReminder] 的周期任务（第四路，间隔共用 `alert_interval_hours`，
 * 不为新链单开 WorkManager 任务——2026-09-24 下线的旧版带着后台自动登录的成本，
 * 这一版只在既有核对醒来时顺带查一次）。闸门在 [ScheduleSync.check]（开关 + 间隔 +
 * 成功才落时刻），本类只管「拿到差异后发通知、点通知去哪」。
 *
 * 三条口径（改之前先读）：
 * 1. **首跑 / 换学期 / 重复差异不通知**：`ScheduleSync.check` 返回的 changes 已经
 *    滤掉这些情形（空列表），这里只按非空差异发；
 * 2. **绝不写库**：点通知落处置页（`SubpageScreen.SCHEDULE_CHANGES`），差异列表、
 *    勾选合并、忽略都在那里由用户逐条决定——后台静默改课表是红线；
 * 3. **通知与 pending 缓存同生**：发通知的前提就是 `check` 写了新的待处置缓存，
 *    合并/忽略后缓存清空，同一次差异不会再发第二条。
 */
object ScheduleAlertReminder {

    private const val TAG = "ScheduleAlert"

    private const val CHANNEL_ID = "schedule_alert"

    /**
     * 通知 id/tag/requestCode 与成绩(1008/3008)、考试(1009/3009)、选课(1010/3010)错开。
     * requestCode **不能撞**：两条落点 intent 都无 action、同一个 SubpageActivity，
     * filterEquals 只看 action/data/component 不看 extra——requestCode 撞了会互相
     * 改写落点（1005/1006、3005/3006 两轮历史坑）。
     */
    private const val NOTIFICATION_ID = 1011
    private const val NOTIFICATION_TAG = "schedule_alert"
    private const val REQUEST_CODE = 3011

    /** 通知正文最多列出的差异条数，多出的折叠。 */
    private const val MAX_LINES = 4

    /**
     * 周期核对的一路（由 `ScoreAlertReminder.check` 叫醒）。开关关着直接返回，
     * 一行网络请求都不发。
     */
    suspend fun check(context: Context) {
        when (val result = Graph.scheduleSync(context).check()) {
            is ScheduleSync.CheckResult.Ok -> {
                if (result.repeated || result.changes.isEmpty()) return
                post(context, result.changes)
            }
            else -> Unit // Skipped / Failed 一律静默，失败闸门不落时刻
        }
    }

    /** 通知：正文列「调整/新增/删除：课程 摘要」，超出 [MAX_LINES] 折叠。点击落处置页。 */
    private fun post(context: Context, changes: List<ScheduleDiff.Change>) {
        val lines = changes.take(MAX_LINES).map { change ->
            val course = change.new ?: change.old
            val prefix = when (change.kind) {
                ScheduleDiff.ChangeKind.MODIFIED -> "调整"
                ScheduleDiff.ChangeKind.ADDED -> "新增"
                ScheduleDiff.ChangeKind.REMOVED -> "删除"
            }
            buildString {
                append(prefix).append('：').append(course?.name.orEmpty())
                if (change.kind == ScheduleDiff.ChangeKind.MODIFIED) {
                    val o = change.old ?: return@buildString
                    append("（原 周").append(o.day).append(" 第")
                        .append(o.startSection).append('-').append(o.endSection).append("节）")
                } else if (course != null) {
                    append("（周").append(course.day).append(" 第")
                        .append(course.startSection).append('-').append(course.endSection).append("节）")
                }
            }
        }
        val rest = changes.size - lines.size
        val text = buildString {
            append(lines.joinToString("\n"))
            if (rest > 0) append("\n等 $rest 条")
        }
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bell)
            .setContentTitle(if (changes.size == 1) "课程安排有变动" else "有 ${changes.size} 处课程变动")
            .setContentText(lines.firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    REQUEST_CODE,
                    subpageLaunchIntent(context, SubpageRequest(SubpageScreen.SCHEDULE_CHANGES))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_TAG, NOTIFICATION_ID, notification)
        }.onFailure { Log.w(TAG, "notify schedule changes failed", it) }
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        // IMPORTANCE_DEFAULT 而非 HIGH：调课不是「马上要做的事」，不值得横幅打断
        val channel = NotificationChannel(CHANNEL_ID, "调课提醒", NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = "教务课表与你的课表出现差异（可能调课）时提醒" }
        manager.createNotificationChannel(channel)
    }
}
