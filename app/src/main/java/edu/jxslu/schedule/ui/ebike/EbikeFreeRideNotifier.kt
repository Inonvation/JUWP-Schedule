package edu.jxslu.schedule.ui.ebike

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
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
import edu.jxslu.schedule.ui.reminder.ReminderVibration

/**
 * 免费时长提醒的通知层（DESIGN §3.9；2026-09-24 由系统日历改回 App 通知）。
 *
 * 两个 channel 分工明确，别合并：
 * - [EbikeFreeRide.NotificationIds.COUNTDOWN_CHANNEL]（LOW）：前台服务的常驻倒计时。
 *   无声、不弹、`ongoing`，卡片头部由系统 chronometer 走秒，芯片文案与进度条由服务每秒
 *   重发一次（见 [countdownNotification] 与 `EbikeFreeRideService.TICK_MS`）；
 *   Android 16+ 上它被系统提升为 Live Update（灵动岛）。
 * - [EbikeFreeRide.NotificationIds.ALERT_CHANNEL]（HIGH）：两条到点提醒，要响要弹。
 *
 * 关于「弹不出来」（2026-09-30 用户报「只出现在通知栏，不在屏幕上弹出」）：App 这一侧
 * 能做的只有「HIGH 渠道 + 不静音」——横幅（heads-up）到底弹不弹由系统与 ROM 决定，
 * 不受 App 控制。国产 ROM 常见的拦法有两种：应用被后台限制（省电策略把通知降级成静默）、
 * 以及通知设置里「悬浮通知 / 横幅」那一项被关。所以提醒除了通知，还有
 * [ReminderVibration] 这条不依赖通知策略的直接震动，设置弹层里给一条进系统
 * 通知设置的出路（见 `EbikeSettingsSheet` 的提醒区）。
 *
 * 三个通知的落点都是「骑行」页（[SubpageScreen.RIDE]，2026-09-29 结构重构后出码页与地图页
 * 已合并成一个页面）：提醒响的时候用户要么在微信里扫码、要么刚还完车，点通知回到
 * 「能看倒计时、能结束计时 / 还车」的那一页最直接。
 * 每个通知的 requestCode 各不相同（[LEAD_REQUEST_CODE] / [END_REQUEST_CODE] / REQUEST_COUNTDOWN）
 * ——PendingIntent 的身份是「requestCode + filterEquals」，共用 requestCode 会让落点
 * 互相改写。**requestCode 不能复用通知 id**：余额提醒（`BalanceAlertReminder`）的
 * 通知 id 也是 1005/1006，且它的落点 intent 同样指向 MainActivity、无 action——
 * filterEquals 相同，requestCode 撞了就会把彼此的落点改掉（2026-09-24 审计发现）。
 */
internal object EbikeFreeRideNotifier {

    private const val TAG = "EbikeFreeRideNotify"

    /** 通知点击落点的 requestCode：与通知 id（1000/1005/1006）和余额提醒（3005/3006）都错开。 */
    private const val REQUEST_COUNTDOWN = 2000

    /**
     * 提前量 / 结束两条提醒的落点 requestCode。internal：免扰时段拦下入队时
     * 要按原值存档，补发重建落点必须同 requestCode（身份纪律见类头 KDoc）。
     */
    internal const val LEAD_REQUEST_CODE = 2005
    internal const val END_REQUEST_CODE = 2006

    /**
     * 常驻倒计时**被划掉**的落点 requestCode（2026-10-16）。
     * 与 2000（点通知）/ 2005 / 2006（两条提醒）/ 2007（自动还车结果）都错开。
     */
    private const val REQUEST_COUNTDOWN_DELETE = 2008

    /**
     * 落点 PendingIntent 的**进程内缓存**（2026-10-16 二轮）。
     *
     * `PendingIntent.getActivity/getBroadcast` 每次都是一趟到 ActivityManager 的 binder
     * 往返；倒计时通知每秒重发一次，那两个落点却几乎不变（内容落点与起点无关，删除落点只
     * 随起点变），所以缓存起来——每轮少两趟 IPC，观感与行为完全不变。
     * 起点换了就换一份（`deleteIntent` 按起点比对）；进程被杀缓存即丢，重建即可。
     * `getOrPut` 非原子也没关系：同一个 `(requestCode, intent)` 取回来的是**同一个系统
     * PendingIntent**（值相等），最坏是并发时各建一次、其中一个被丢弃。
     */
    private val contentIntents = java.util.concurrent.ConcurrentHashMap<Int, PendingIntent>()

    /**
     * 删除落点的缓存。**必须是一个不可变持有者、一次原子读**（2026-10-16 二轮审查修）：
     * 早先写成两个各自 `@Volatile` 的字段（先写 intent、再写 startAt），换车时
     * `onStartCommand` 的主线程与上一轮还没取消的 tick 协程会并发进来，可能读到
     * 「新的 intent + 旧的 startAt」→ 返回一个带错起点的删除落点（划掉会作废错的那一轮）。
     * 起点只在一轮骑行里固定，所以单槽足够。
     */
    @Volatile
    private var cachedDelete: Pair<Long, PendingIntent>? = null

    /** 点通知的落点：出码页（不是今日页——用户此刻的诉求是看码或结束计时）。 */
    private fun contentIntent(context: Context, requestCode: Int): PendingIntent =
        contentIntents.getOrPut(requestCode) {
            PendingIntent.getActivity(
                context,
                requestCode,
                subpageLaunchIntent(context, SubpageRequest(SubpageScreen.RIDE)),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

    /**
     * 常驻倒计时**被划掉**的落点（2026-10-16）。官方对 Live Updates 的要求是
     * 「别把用户划掉的再顶回去」，检测手段就是这个 `setDeleteIntent`。
     * 带上这一轮的起点：划掉只作废**这一轮**，换车换起点自然重新请求提升。
     */
    private fun deleteIntent(context: Context, startAtMillis: Long): PendingIntent {
        cachedDelete?.let { (cachedStartAt, cached) ->
            if (cachedStartAt == startAtMillis) return cached
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_COUNTDOWN_DELETE,
            Intent(context, EbikeCountdownDismissReceiver::class.java)
                .putExtra(EbikeCountdownDismissReceiver.EXTRA_START_AT, startAtMillis),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ).also { cachedDelete = startAtMillis to it }
    }

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
                    // 文案与使用方式无关：小程序方式是点「打开微信扫一扫」起计时，
                    // 账号方式是开锁成功起计时（DESIGN §3.9 / §4.32）
                    ).apply { description = "骑行开始后在通知栏常驻显示免费剩余时间" },
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
                        vibrationPattern = ReminderVibration.PATTERN
                    },
                )
            }
        }.onFailure { Log.w(TAG, "ensureChannels failed", it) }
    }

    /**
     * 常驻倒计时通知（前台服务用）。**卡片头部的秒数交给系统 chronometer**
     * （`.setWhen(免费结束)` + `.setUsesChronometer(true)` + `.setChronometerCountDown(true)`），
     * 服务每秒刷新芯片文案与进度条（[EbikeFreeRideService.TICK_MS]）。
     *
     * **卡片头部保留 chronometer，芯片仍每秒重发**（2026-10-19）：卡片头部交给系统计时器，
     * 正文用静态 [EbikeFreeRide.COUNTDOWN_TEXT]；但 HyperOS 3 的芯片不显示 chronometer，
     * 要岛上逐秒走只能每秒重发 `setShortCriticalText`。用户明确「我要看的就是倒计时」，
     * 所以接受 Android 16 Live Update 卡片每收到一次更新就重绘头部应用图标的代价
     * （下拉状态栏能看到一闪一闪）。**别再为了省重绘把芯片改成 5 秒一跳**。
     *
     * **芯片仍要用 `setShortCriticalText`**：2026-10-19 在 HyperOS 3 实测，Live Update
     * 芯片不显示系统 chronometer，没有短文案时只显示通知标题（「免费时长倒计时」），
     * 岛上就看不到秒数了。所以芯片回退到 [EbikeFreeRide.countdownChipText]，每秒重发；
     * 卡片头部仍由 chronometer 走秒。
     *
     * **灵动岛（DESIGN §3.9，2026-10-16）**：Android 16 起系统能把这条通知提升为
     * Live Update——状态栏上出现一枚芯片、通知抽屉里是一条置顶常驻卡。请求提升只要
     * 两件东西：`setRequestPromotedOngoing(true)`（compat 内部就是往 extras 塞
     * `android.requestPromotedOngoing`）+ manifest 里的 `POST_PROMOTED_NOTIFICATIONS`
     * （普通权限）。芯片文案见上（HyperOS 必须给 `setShortCriticalText`）。
     * **开启通知即默认上岛，没有开关。** 低版本设备上提升请求被系统忽略，通知照旧
     * （不是"分档实现"，就是同一份通知）。
     *
     * 官方给的资格清单我们都满足：`ongoing` ✓、有 contentTitle ✓、无自定义 RemoteViews ✓、
     * 非 group summary ✓、不 colorized ✓、渠道是 LOW（不是 IMPORTANCE_MIN）✓、
     * 样式是 `ProgressStyle`（清单里允许）✓。**别加 `setCustomContentView`、
     * 别把渠道降成 IMPORTANCE_MIN**，那会当场取消资格。
     *
     * **进度条**：卡片走 `ProgressStyle`（填满 = 免费时长还剩满格，随免费时间流干；不设
     * segment / tracker 图标，走默认「按进度着色」，无 segment 时满刻度 100）。
     * **它曾经被当成「岛抖」的嫌疑犯撤掉过一次（2026-10-16 二轮），但用户确认抖动在它之前
     * 就有，已按用户要求加回来**——别再拿它当抖动的原因；旧版每秒重发本身（系统侧重绘）
     * 才是那笔代价。2026-10-19 最终口径：卡片头部用系统 chronometer，芯片文案 + 进度
     * **每秒**重发，秒数优先。
     *
     * **被划掉就收手（2026-10-16）**：用户划掉过这一轮，就**不再请求提升**（见
     * [EbikeCountdownDismissal]）；划掉本身由 [deleteIntent] 收进 [EbikeCountdownDismissReceiver]。
     */
    fun countdownNotification(context: Context, startAtMillis: Long, nowMillis: Long) =
        NotificationCompat.Builder(context, EbikeFreeRide.NotificationIds.COUNTDOWN_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_ebike)
            .setContentTitle(EbikeFreeRide.COUNTDOWN_TITLE)
            .setContentText(EbikeFreeRide.COUNTDOWN_TEXT)
            .setWhen(EbikeFreeRide.freeEndMillis(startAtMillis))
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setStyle(
                NotificationCompat.ProgressStyle()
                    .setProgress(EbikeFreeRide.progressPercent(startAtMillis, nowMillis)),
            )
            .apply {
                // 上岛（见 KDoc）：用户把这一轮划掉过就只按普通通知发，不再顶回去
                if (!EbikeCountdownDismissal.isDismissed(startAtMillis)) {
                    setRequestPromotedOngoing(true)
                    setShortCriticalText(EbikeFreeRide.countdownChipText(startAtMillis, nowMillis))
                }
            }
            .setDeleteIntent(deleteIntent(context, startAtMillis))
            .setContentIntent(contentIntent(context, REQUEST_COUNTDOWN))
            .build()

    /** 每秒刷新常驻通知的芯片文案与进度（同一 id；卡片头部由系统 chronometer 走）。 */
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
     *
     * [strongVibration] 开（通用设置「通知强提醒」，默认关）时通知之外再补一次
     * [ReminderVibration.vibrate]：渠道配的是「通知栏里那条」的震动，直接震动是
     * 「手机本身」的，两路都走才不怕 ROM 只放行其中一路；直接震动也穿免打扰，
     * 所以收在显式开关后面。
     */
    fun postLeadReminder(
        context: Context,
        startAtMillis: Long,
        nowMillis: Long,
        strongVibration: Boolean,
    ): Boolean {
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
            .setContentIntent(contentIntent(context, LEAD_REQUEST_CODE))
            .build()
        val posted = post(context, EbikeFreeRide.NotificationIds.LEAD, notification)
        if (strongVibration) ReminderVibration.vibrate(context)
        return posted
    }

    /** 结束提醒：免费时段已过，该还车了。[strongVibration] 口径见 [postLeadReminder]。 */
    fun postEndReminder(context: Context, strongVibration: Boolean): Boolean {
        ensureChannels(context)
        val notification = NotificationCompat.Builder(context, EbikeFreeRide.NotificationIds.ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_bell)
            .setContentTitle(EbikeFreeRide.END_TITLE)
            .setContentText(EbikeFreeRide.END_TEXT)
            .setStyle(NotificationCompat.BigTextStyle().bigText(EbikeFreeRide.END_TEXT))
            // 与提前量那条同一分类（见 postLeadReminder 的说明）
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setAutoCancel(true)
            .setContentIntent(contentIntent(context, END_REQUEST_CODE))
            .build()
        val posted = post(context, EbikeFreeRide.NotificationIds.END, notification)
        if (strongVibration) ReminderVibration.vibrate(context)
        return posted
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

/**
 * 「用户把常驻倒计时划掉了」的本轮记账（2026-10-16）。
 *
 * 官方对 Live Updates 的要求是**别把用户划掉的再顶回去**。我们的通知是前台服务的，
 * 划掉之后服务也没有存在理由了（它只干「让这条通知活着」这一件事），所以这里的实际含义是：
 * **这一轮就此收手**——通知摘掉、不再请求提升；提醒闹钟与 App 内倒计时块不受影响。
 *
 * 进程内记账（与 `EbikeFreeRideService.runningStartAt` 同性质）：换车换起点自然失效
 * （[isDismissed] 按起点比），进程被杀则记账丢失、下一轮照常上岛——那正是要的语义
 * （重启后不该继承上一轮的"别打扰我"）。
 */
internal object EbikeCountdownDismissal {

    @Volatile
    private var dismissedStartAt = 0L

    fun markDismissed(startAtMillis: Long) {
        dismissedStartAt = startAtMillis
    }

    fun isDismissed(startAtMillis: Long): Boolean =
        startAtMillis > 0L && dismissedStartAt == startAtMillis
}

/**
 * 常驻倒计时被划掉的落点（[EbikeFreeRideNotifier.deleteIntent] 挂上来的）。
 * 只做两件事：记下这一轮的起点、把常驻服务停掉（通知随之摘掉）。
 */
class EbikeCountdownDismissReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val startAt = intent.getLongExtra(EXTRA_START_AT, 0L)
        if (startAt <= 0L) return
        EbikeCountdownDismissal.markDismissed(startAt)
        EbikeFreeRideService.stop(context)
    }

    companion object {
        const val EXTRA_START_AT = "ebike_countdown_dismissed_start_at"
    }
}
