package edu.jxslu.schedule.ui.ebike

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
import edu.jxslu.schedule.domain.EbikeFreeRide
import edu.jxslu.schedule.subpageLaunchIntent

/**
 * 免费时长提醒的通知层（DESIGN §3.9；2026-09-24 由系统日历改回 App 通知）。
 *
 * 两个 channel 分工明确，别合并：
 * - [EbikeFreeRide.NotificationIds.COUNTDOWN_CHANNEL]（LOW）：前台服务的常驻倒计时。
 *   无声、不弹、`ongoing`，剩余时间由**系统 chronometer** 渲染（`setWhen` + countDown），
 *   App 不需要每秒 notify 一次；
 * - [EbikeFreeRide.NotificationIds.ALERT_CHANNEL]（HIGH）：两条到点提醒，要响要弹。
 *
 * 三个通知的落点都是出码页（[SubpageScreen.EBIKE]）：提醒响的时候用户要么在微信里
 * 扫码、要么刚还完车，点通知回到「能看码、能结束计时」的那一页最直接。
 * 每个通知的 requestCode 各不相同（[REQUEST_LEAD] / [REQUEST_END] / [REQUEST_COUNTDOWN]）
 * ——PendingIntent 的身份是「requestCode + filterEquals」，共用 requestCode 会让落点
 * 互相改写。**requestCode 不能复用通知 id**：余额提醒（`BalanceAlertReminder`）的
 * 通知 id 也是 1005/1006，且它的落点 intent 同样指向 MainActivity、无 action——
 * filterEquals 相同，requestCode 撞了就会把彼此的落点改掉（2026-09-24 审计发现）。
 */
internal object EbikeFreeRideNotifier {

    private const val TAG = "EbikeFreeRideNotify"

    /**
     * 提醒的震动节奏（毫秒，首项是延迟）：震 400 → 停 250 → 震 400。
     * 双震比单次更容易被注意到，总时长不到 1 秒，不烦人。
     */
    private val ALERT_VIBRATION_PATTERN = longArrayOf(0L, 400L, 250L, 400L)

    /** 通知点击落点的 requestCode：与通知 id（1000/1005/1006）和余额提醒（3005/3006）都错开。 */
    private const val REQUEST_COUNTDOWN = 2000
    private const val REQUEST_LEAD = 2005
    private const val REQUEST_END = 2006

    /** 点通知的落点：出码页（不是今日页——用户此刻的诉求是看码或结束计时）。 */
    private fun contentIntent(context: Context, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            subpageLaunchIntent(context, SubpageRequest(SubpageScreen.EBIKE)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** 通知是否可用：系统总开关或本应用被关掉时，发了也看不见（前台服务仍会跑）。 */
    fun notificationsEnabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * 两个 channel 的创建（幂等）。**只用新 id**，不复用首版那个 `ebike_free_ride`：
     * 它在用户设备上可能还在，而 `createNotificationChannel` 改不动已存在 channel 的
     * importance，留着就会继承旧级别。
     *
     * 提醒 channel 带**震动**（2026-09-24 用户要求）：横幅和声音都可能被 ROM 的通知策略
     * 拦掉，震动是最后一道能被感知的信号。带震动的是 [_v2] 这个新 id——channel 建出来后
     * **震动和 importance 都改不动**，而「删掉重建」也无效（delete 是异步的，紧接着 create
     * 会被当成更新，真机实测过）。所以换 id，并顺手把早期那个无震动的旧 id 清掉。
     */
    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            // 早期无震动的提醒 channel：已被 ALERT_CHANNEL 取代，清掉免得设置页留死通道
            manager.deleteNotificationChannel(EbikeFreeRide.NotificationIds.LEGACY_ALERT_CHANNEL)
            if (manager.getNotificationChannel(EbikeFreeRide.NotificationIds.COUNTDOWN_CHANNEL) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        EbikeFreeRide.NotificationIds.COUNTDOWN_CHANNEL,
                        "免费时长倒计时",
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply { description = "扫码开车后在通知栏常驻显示免费剩余时间" },
                )
            }
            if (manager.getNotificationChannel(EbikeFreeRide.NotificationIds.ALERT_CHANNEL) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        EbikeFreeRide.NotificationIds.ALERT_CHANNEL,
                        "免费时长提醒",
                        NotificationManager.IMPORTANCE_HIGH,
                    ).apply {
                        description = "免费结束前与结束那一刻各提醒一次"
                        enableVibration(true)
                        vibrationPattern = ALERT_VIBRATION_PATTERN
                    },
                )
            }
        }.onFailure { Log.w(TAG, "ensureChannels failed", it) }
    }

    /**
     * 常驻倒计时通知（前台服务用）。文案由 [EbikeFreeRide.countdownText] 给出，
     * **服务每秒重发一次**——不用系统 chronometer：真机实测（Redmi K70 / 澎湃OS）
     * 面板静止时系统不主动重绘 chronometer，用户看到的是不动的数字。
     */
    fun countdownNotification(context: Context, startAtMillis: Long, nowMillis: Long) =
        NotificationCompat.Builder(context, EbikeFreeRide.NotificationIds.COUNTDOWN_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_bell)
            .setContentTitle(EbikeFreeRide.COUNTDOWN_TITLE)
            .setContentText(EbikeFreeRide.countdownText(startAtMillis, nowMillis))
            .setShowWhen(false)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(contentIntent(context, REQUEST_COUNTDOWN))
            .build()

    /** 每秒刷新常驻通知的文案（同一 id，只换正文）。 */
    fun updateCountdown(context: Context, startAtMillis: Long, nowMillis: Long) {
        runCatching {
            NotificationManagerCompat.from(context).notify(
                EbikeFreeRide.NotificationIds.COUNTDOWN,
                countdownNotification(context, startAtMillis, nowMillis),
            )
        }.onFailure { Log.w(TAG, "updateCountdown failed", it) }
    }

    /**
     * 提前量提醒。正文按**实际剩余**写（[EbikeFreeRide.leadText]）——
     * 闹钟被 ROM 推迟时，写死「还剩 3 分钟」而实际只剩 40 秒是假信息。
     */
    fun postLeadReminder(context: Context, startAtMillis: Long, nowMillis: Long): Boolean {
        ensureChannels(context)
        val text = EbikeFreeRide.leadText(startAtMillis, nowMillis)
        val notification = NotificationCompat.Builder(context, EbikeFreeRide.NotificationIds.ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_bell)
            .setContentTitle(EbikeFreeRide.LEAD_TITLE)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            // CATEGORY_EVENT 而不是 REMINDER：与上课提醒（ClassReminder）同一分类。
            // AOSP 的横幅判定不看 category，但 ROM（MIUI）可能拿它做通知归类，
            // 两个同类提醒用同一个值最不容易出意外。
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setAutoCancel(true)
            .setContentIntent(contentIntent(context, REQUEST_LEAD))
            .build()
        return post(context, EbikeFreeRide.NotificationIds.LEAD, notification)
    }

    /** 结束提醒：免费时段已过，该还车了。 */
    fun postEndReminder(context: Context): Boolean {
        ensureChannels(context)
        val notification = NotificationCompat.Builder(context, EbikeFreeRide.NotificationIds.ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_bell)
            .setContentTitle(EbikeFreeRide.END_TITLE)
            .setContentText(EbikeFreeRide.END_TEXT)
            .setStyle(NotificationCompat.BigTextStyle().bigText(EbikeFreeRide.END_TEXT))
            // 与提前量那条同一分类（见 postLeadReminder 的说明）
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setAutoCancel(true)
            .setContentIntent(contentIntent(context, REQUEST_END))
            .build()
        return post(context, EbikeFreeRide.NotificationIds.END, notification)
    }

    /**
     * 清掉通知栏上的两条提醒（用户点「结束骑行」时）。
     * 常驻倒计时不归这里管——那是前台服务的通知，由服务停掉。
     */
    fun cancelReminders(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        runCatching {
            manager.cancel(EbikeFreeRide.NotificationIds.LEAD)
            manager.cancel(EbikeFreeRide.NotificationIds.END)
        }.onFailure { Log.w(TAG, "cancelReminders failed", it) }
    }

    /** 发通知；无 POST_NOTIFICATIONS 权限时系统静默丢弃，异常也不外漏（口径同上课提醒）。 */
    private fun post(context: Context, id: Int, notification: android.app.Notification): Boolean =
        runCatching {
            NotificationManagerCompat.from(context).notify(id, notification)
        }.isSuccess
}
