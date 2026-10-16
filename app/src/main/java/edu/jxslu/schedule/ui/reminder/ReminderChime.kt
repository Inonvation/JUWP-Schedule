package edu.jxslu.schedule.ui.reminder

import android.content.Context
import android.media.RingtoneManager
import android.util.Log

/**
 * 提醒发出时的**直接提示音**（不经过通知渠道）。
 *
 * 与 [ReminderVibration] 同族、同用途：蹲点「命中」那一刻用户可能没盯着屏幕，
 * 震动之外再叠一层声音更容易被注意到。走**系统默认通知音**，不打包音频资源。
 *
 * 播不出来不算错（静音 / 没设默认铃声 / ROM 拦下都可能是 false）：提示音是叠加信号，
 * 它失败不该影响提醒本身。
 */
internal object ReminderChime {

    private const val TAG = "ReminderChime"

    fun play(context: Context) {
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            if (uri != null) {
                RingtoneManager.getRingtone(context.applicationContext, uri)?.play()
            }
        }.onFailure { Log.w(TAG, "play failed", it) }
    }
}
