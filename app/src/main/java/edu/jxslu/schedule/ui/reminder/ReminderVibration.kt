package edu.jxslu.schedule.ui.reminder

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * 提醒发出时的**直接震动**（不经过通知渠道，也不看 ROM 的通知策略开关）。
 *
 * 为什么渠道配了震动还要直接震（2026-10-05 用户报「上课提醒/免费时长提醒没有震动、
 * 也不弹出」）：HyperOS 对第三方应用的通知策略（悬浮通知 / 声音 / 振动 / 锁屏）
 * 默认全部关——设备实测（Redmi K70 Pro）渠道记录里 `mVibrationEnabled=true`、
 * importance=HIGH，设置页那几个开关照样显示关，且 App 没有公开 API 能改那个策略层。
 * 渠道上的 `enableVibration` 留着（换新 id 才能对已装生效，参数对齐让非 MIUI 设备
 * 与新装用户默认正确），但「保证有感」靠的是这里：`Vibrator` API 不走通知策略，
 * 渠道开关、横幅被拦都影响不到它。
 *
 * 震不出来不算错（无马达设备、被省电策略拦下都可能是 false）：震动是叠加信号，
 * 它失败不该影响提醒本身。用户不想被打扰就把对应提醒的开关关掉——关掉后整条
 * 发送链路在核对入口就早退了，不会有通知也不会有震动。
 *
 * 第二个用途（2026-10-07）：付款码「扫码扣款命中」那一刻（`PayCodeViewModel`）也走这里。
 * 轮询特意不跟页面可见性，付款码页可能锁屏停留，震动是那一刻最可靠的反馈；一次消费
 * 只震一下，是即时操作反馈而非打扰型提醒，因此**不挂**任何开关（与提醒类口径不同）。
 */
internal object ReminderVibration {

    private const val TAG = "ReminderVibration"

    /**
     * 震动节奏（毫秒，首项是延迟）：震 400 → 停 250 → 震 400。
     * 双震比单次更容易被注意到，总时长不到 1 秒，不烦人。
     * 渠道创建（`vibrationPattern`）与直接震动共用同一个值。
     */
    val PATTERN = longArrayOf(0L, 400L, 250L, 400L)

    fun vibrate(context: Context) {
        runCatching {
            val effect = VibrationEffect.createWaveform(PATTERN, -1)
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Vibrator::class.java)
            }
            vibrator?.takeIf { it.hasVibrator() }?.vibrate(effect)
        }.onFailure { Log.w(TAG, "vibrate failed", it) }
    }
}
