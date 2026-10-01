package edu.jxslu.schedule.ui.selection

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import edu.jxslu.schedule.R
import edu.jxslu.schedule.SubpageRequest
import edu.jxslu.schedule.SubpageScreen
import edu.jxslu.schedule.data.repo.SelectionGrabStore
import edu.jxslu.schedule.subpageLaunchIntent

/**
 * 抢课会话的通知（DESIGN §4.36）：两条渠道 + 两个固定 id，与既有通知互不覆盖。
 *
 * - [CHANNEL_PROGRESS]（LOW）：前台服务的常驻进度条——无声、不弹，只在通知栏显示
 *   「已抢到 N/M · 第 K 轮检查」；带一个「停止」动作（直接回调服务，锁屏也能停）。
 * - [CHANNEL_RESULT]（HIGH）：收场通知（完成/失败/需要人工），要响要弹
 *   （横幅弹不弹仍由系统/ROM 决定，同骑行提醒的口径）。
 *
 * 通知 id 1020/1021、落点 requestCode 3020/3021，与既有（1001–1010、3008–3010、4002–4004）
 * 错开——**id 与 requestCode 都要错开**：前者防互相覆盖，后者防落点被改写。
 */
object SelectionGrabNotifier {

    private const val TAG = "SelectionGrab"

    const val CHANNEL_PROGRESS = "selection_grab_progress"
    const val CHANNEL_RESULT = "selection_grab_result"

    const val PROGRESS_ID = 1020
    const val RESULT_ID = 1021

    private const val CONTENT_REQUEST_CODE = 3020
    private const val STOP_REQUEST_CODE = 3021

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            if (manager.getNotificationChannel(CHANNEL_PROGRESS) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_PROGRESS,
                        "抢课进度",
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply { description = "抢课会话进行中时在通知栏显示进度，可一键停止" },
                )
            }
            if (manager.getNotificationChannel(CHANNEL_RESULT) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_RESULT,
                        "抢课结果",
                        NotificationManager.IMPORTANCE_HIGH,
                    ).apply { description = "抢课结束时告知抢到几门、为什么结束" },
                )
            }
        }.onFailure { Log.w(TAG, "ensureChannels failed", it) }
    }

    /** 常驻进度通知（由服务在会话期间调用；`ongoing` + 静默，避免反复打扰）。 */
    fun progressNotification(context: Context, state: SelectionGrabStore.State) =
        NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_bell)
            .setContentTitle("抢课中 · ${state.roundName.ifBlank { "选课轮次" }}")
            .setContentText(progressText(state))
            .setStyle(NotificationCompat.BigTextStyle().bigText(progressText(state)))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(contentPendingIntent(context))
            .addAction(
                0,
                "停止",
                PendingIntent.getService(
                    context,
                    STOP_REQUEST_CODE,
                    Intent(context, SelectionGrabService::class.java)
                        .setAction(SelectionGrabService.ACTION_STOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

    fun updateProgress(context: Context, state: SelectionGrabStore.State) {
        ensureChannels(context)
        runCatching {
            NotificationManagerCompat.from(context).notify(
                PROGRESS_ID,
                progressNotification(context, state),
            )
        }.onFailure { Log.w(TAG, "updateProgress failed", it) }
    }

    /** 收场通知（成功/失败/需要人工都走它；同一 id，新结果覆盖旧结果）。 */
    fun postResult(context: Context, title: String, text: String) {
        ensureChannels(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_stat_bell)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent(context))
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(RESULT_ID, notification)
        }.onFailure { Log.w(TAG, "postResult failed", it) }
    }

    /** 撤回常驻进度（会话结束）。 */
    fun cancelProgress(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(PROGRESS_ID) }
    }

    private fun progressText(state: SelectionGrabStore.State): String {
        val latest = state.log.firstOrNull()?.substringAfter(' ') ?: "等待第一次检查"
        return "已抢到 ${state.grabbedCount}/${state.targetCount} · $latest"
    }

    private fun contentPendingIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        CONTENT_REQUEST_CODE,
        subpageLaunchIntent(context, SubpageRequest(SubpageScreen.SELECTION_GRAB))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
