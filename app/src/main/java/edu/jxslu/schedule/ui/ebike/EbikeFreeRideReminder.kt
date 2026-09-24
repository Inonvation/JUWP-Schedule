package edu.jxslu.schedule.ui.ebike

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.domain.EbikeFreeRide
import edu.jxslu.schedule.domain.WechatRentNotice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * 共享单车免费时长提醒的调度（DESIGN §3.9；2026-09-24 由系统日历改回 App 通知）。
 *
 * 三层结构，与上课提醒（`ui/reminder/ClassReminder.kt`）同构：
 *
 * | 层 | 手段 | 作用 |
 * |----|------|------|
 * | 主 | `AlarmManager.setAlarmClock` | 两个提醒点的**精确**触发，到点唤醒设备、不受 Doze/省电推迟，不需要任何特殊权限 |
 * | 中 | [EbikeFreeRideService] | 通知栏常驻倒计时（系统 chronometer 渲染），顺带抬高进程优先级 |
 * | 兜 | [EbikeFreeRideCheckWorker] 10 分钟周期 + 进页 / 冷启动 | 补发漏掉的提醒、把状态收干净 |
 *
 * **提醒不由服务里的协程负责**：屏幕关闭后 CPU 会挂起，`delay` 要等下一个唤醒点才跑，
 * 会迟到几分钟。精确到点只能靠 `setAlarmClock`。
 *
 * 为什么改回 App 通知（2026-09-23 曾改系统日历）：日历 App 发提醒时，App 自己没法
 * 展示倒计时，体验和 App 内是两套；用户拍板要「通知栏常驻倒计时 + 两个精确提醒点」。
 * 代价是重新需要 `POST_NOTIFICATIONS`，以及一个前台服务（`specialUse` 类型）。
 *
 * 去重：两个提醒点各有一个「已发键」（[EbikeFreeRide.leadDedupKey] / [EbikeFreeRide.endDedupKey]，
 * 键里带计时起点），落 DataStore，闹钟与周期核对共用——同一条提醒只发一次。
 */
object EbikeFreeRideReminder {

    private const val TAG = "EbikeFreeRide"
    private const val PERIODIC_WORK = "ebike_free_ride_periodic"

    /**
     * 骑行提醒闹钟的 requestCode。与上课提醒（`ClassReminder` 的 4002）错开：
     * PendingIntent 的身份是「requestCode + Intent.filterEquals」，撞了会互相顶掉。
     */
    private const val ALARM_REQUEST_CODE = 4003

    /**
     * 首版（2026-09-22）建的 notification channel，2026-09-24 起彻底不用
     * （新 channel 见 [EbikeFreeRide.NotificationIds]）。channel 一旦建出来就常驻系统，
     * 代码不再用它也不会自己消失，所以冷启动清一次。重复调用是 no-op。
     */
    private const val LEGACY_CHANNEL_ID = "ebike_free_ride"

    /** 一次动作的结果，供调用方拼提示文案。 */
    sealed interface Outcome {
        /** 计时已开始：常驻倒计时上了通知栏，两个提醒点已排。 */
        data object Started : Outcome

        /** 计时已开始，但提醒开关关着（不排提醒、不起服务）。 */
        data object StartedSilent : Outcome

        /** 计时已开始，但通知被系统/用户关掉，提醒发不出来。 */
        data object StartedNoNotification : Outcome

        /** 计时已结束：服务、闹钟、通知、已发键都收了。 */
        data object Ended : Outcome

        /** 这次动作不需要改动任何状态。 */
        data object Nothing : Outcome

        data class Failed(val message: String) : Outcome
    }

    /**
     * 点「打开微信扫一扫」：记起点、清上一轮的提醒态、起常驻倒计时、排下一个精确闹钟。
     *
     * **换车（上一轮还没结束就再扫一辆）走的是同一条路**：已发键与校准标记清空、上一轮
     * 挂在通知栏的提醒撤掉、起点重写、闹钟重排（同一个 PendingIntent，新闹钟替换旧的）；
     * 常驻倒计时由 [EbikeFreeRideService.start] 按「起点变了」自动重建 tick
     * ——2026-09-24 修：早先它在「服务已在跑」时直接返回，于是换车后倒计时不重置。
     */
    suspend fun startRide(context: Context, startAtMillis: Long): Outcome = guarded {
        val prefs = Graph.displayPrefs(context)
        // 换车重新计时：上一轮的已发键与校准标记都必须清，否则这一轮的提醒会被「已发过」吃掉
        prefs.updateEbikeFreeNotifiedKeys { emptySet() }
        prefs.setEbikePreciseCalibratedAt(0L)
        // 上一轮的提醒可能还挂在通知栏（提前量那条），换车时一并撤掉
        EbikeFreeRideNotifier.cancelReminders(context)
        prefs.setEbikeRideStartAt(startAtMillis)

        if (!prefs.ebikeFreeReminderEnabled.first()) {
            // 开关关着：不留服务、闹钟与周期兜底，只记起点（页面上的倒计时条也不显示）
            cancelAll(context)
            return@guarded Outcome.StartedSilent
        }
        val lead = prefs.ebikeFreeLeadMinutes.first()
        val now = System.currentTimeMillis()
        scheduleNextAlarm(context, startAtMillis, lead, now)
        // 周期兜底**只在计时期间**存在：平时不排，省掉「没骑行也每 15 分钟唤醒一次进程」
        ensurePeriodicWork(context)
        // 服务照起：通知权限被拒时通知不可见但服务状态与计时一致，
        // 用户去把权限打开后常驻倒计时立刻出现（不用重新点扫一扫）
        EbikeFreeRideService.start(context, startAtMillis)
        if (!EbikeFreeRideNotifier.notificationsEnabled(context)) {
            return@guarded Outcome.StartedNoNotification
        }
        Outcome.Started
    }

    /**
     * 「精确倒计时」：收到微信的租车成功通知后，把计时起点校准到通知到达那一刻
     * （DESIGN §3.9，2026-09-24）。返回 true = 这次真的校准了。
     *
     * 四道闸，缺一不可：
     * 1. 开关开着（默认关——它要「通知使用权」，得用户自己去系统设置里开）；
     * 2. 有在案的计时（没计时就无从校准）；
     * 3. 通知落在「点扫一扫」之后的 [WechatRentNotice.WINDOW_MS] 内（排除借充电宝这类
     *    同样走「先享后付」的误命中）；
     * 4. 这一轮还没校准过（微信对同一笔支付可能重复推送，重复校准会把计时一直往后推）。
     *
     * 校准 = 用新起点重跑 [startRide]（清已发键与校准标记、撤上一轮通知、写起点、重排闹钟；
     * 常驻倒计时由服务按「起点变了」自动重建 tick），再记下校准标记。起点直接取通知到达时刻、
     * 不加偏移：通知投递的延迟没有可测的固定量，宁可保守（起点晚一点 = 提醒晚一点），
     * 也不去猜一个偏移。
     */
    suspend fun calibrateRideStart(context: Context, noticeAtMillis: Long): Boolean = guardedBool {
        val prefs = Graph.displayPrefs(context)
        if (!prefs.ebikePreciseCountdownEnabled.first()) return@guardedBool false
        val startAt = prefs.ebikeRideStartAt.first()
        if (!WechatRentNotice.isWithinWindow(startAt, noticeAtMillis)) return@guardedBool false
        if (prefs.ebikePreciseCalibratedAt.first() == startAt) return@guardedBool false
        startRide(context, noticeAtMillis)
        prefs.setEbikePreciseCalibratedAt(noticeAtMillis)
        true
    }

    /** 结束骑行：清起点、已发键与校准标记，撤闹钟，停服务（常驻通知随之摘掉），清通知栏上的提醒。 */
    suspend fun endRide(context: Context): Outcome = guarded {
        val prefs = Graph.displayPrefs(context)
        prefs.setEbikeRideStartAt(0L)
        prefs.updateEbikeFreeNotifiedKeys { emptySet() }
        prefs.setEbikePreciseCalibratedAt(0L)
        cancelAll(context)
        Outcome.Ended
    }

    /**
     * 兜底核对（闹钟落点 / 进页 / 冷启动 / 周期任务）：把该发的提醒发出去、
     * 重排下一个闹钟、按需起停服务、把过期状态收干净。**幂等**，可任意重复调用。
     *
     * 迟到的提醒照样补发（用户拍板 2026-09-24）：提前量那条只要免费时段没结束就发，
     * 结束那条有 [EbikeFreeRide.END_WINDOW_MS] 的窗口；越过窗口就不发，避免纯噪音。
     */
    suspend fun check(context: Context): Outcome = guarded {
        val prefs = Graph.displayPrefs(context)
        val startAt = prefs.ebikeRideStartAt.first()
        val now = System.currentTimeMillis()
        val active = EbikeFreeRide.isActive(startAt, now)
        val endDue = EbikeFreeRide.isEndDue(startAt, now)

        // 1) 没有在案计时，或计时与迟到窗口都已过：收干净（服务/闹钟/通知/状态/周期兜底）
        if (startAt <= 0L || (!active && !endDue)) {
            cancelAll(context)
            if (startAt > 0L) {
                prefs.setEbikeRideStartAt(0L)
                prefs.updateEbikeFreeNotifiedKeys { emptySet() }
            }
            return@guarded Outcome.Nothing
        }

        // 2) 计时在案但提醒关着：不留服务、闹钟与周期兜底（开关关掉 = 完全回到旧语义）
        if (!prefs.ebikeFreeReminderEnabled.first()) {
            cancelAll(context)
            return@guarded Outcome.Nothing
        }

        val lead = prefs.ebikeFreeLeadMinutes.first()
        val sent = prefs.ebikeFreeNotifiedKeys.first().toMutableSet()

        // 3) 该发的提醒（各自去重）。发失败不落键，下一轮还有机会
        if (EbikeFreeRide.isLeadDue(startAt, lead, now)) {
            val key = EbikeFreeRide.leadDedupKey(startAt)
            if (key !in sent && EbikeFreeRideNotifier.postLeadReminder(context, startAt, now)) {
                sent += key
            }
        }
        if (endDue) {
            val key = EbikeFreeRide.endDedupKey(startAt)
            if (key !in sent && EbikeFreeRideNotifier.postEndReminder(context)) {
                sent += key
            }
        }
        prefs.updateEbikeFreeNotifiedKeys { sent }

        // 4) 重排下一个闹钟；周期兜底与前台服务按需起停（结束后 5 分钟窗口内不再起服务）
        if (active) {
            scheduleNextAlarm(context, startAt, lead, now)
            ensurePeriodicWork(context)
            EbikeFreeRideService.start(context, startAt)
        } else {
            cancelAlarm(context)
            EbikeFreeRideService.stop(context)
        }
        Outcome.Nothing
    }

    /**
     * 排下一个精确闹钟（两个提醒点里更早的那个；都过了就撤销）。
     * 闹钟触发 → [EbikeFreeRideAlarmReceiver] → [check] → 发提醒 + 重排下一个。
     */
    private fun scheduleNextAlarm(
        context: Context,
        startAtMillis: Long,
        leadMinutes: Int,
        nowMillis: Long,
    ) {
        runCatching {
            val manager = context.getSystemService(AlarmManager::class.java) ?: return
            val pending = alarmPendingIntent(context)
            val next = EbikeFreeRide.nextReminderAt(startAtMillis, leadMinutes, nowMillis)
            if (next == null) {
                manager.cancel(pending)
                return
            }
            // 系统级精确闹钟：到点即触发，不受 Doze/省电推迟，无需任何特殊权限
            // （与上课提醒同一手法，见 ClassReminder 的 KDoc）。
            // 代价是状态栏会短暂显示一枚闹钟图标——它只表示「有个闹钟排着」，
            // 不是提醒本身，所以 showIntent 交给系统时钟的闹钟列表页。
            val clockPending = PendingIntent.getActivity(
                context,
                1,
                Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            manager.setAlarmClock(AlarmManager.AlarmClockInfo(next, clockPending), pending)
        }.onFailure { Log.w(TAG, "scheduleNextAlarm failed", it) }
    }

    /** 撤销骑行提醒闹钟（结束骑行 / 提醒关掉 / 计时过期）。 */
    private fun cancelAlarm(context: Context) {
        runCatching {
            context.getSystemService(AlarmManager::class.java)?.cancel(alarmPendingIntent(context))
        }.onFailure { Log.w(TAG, "cancelAlarm failed", it) }
    }

    /** 清掉这一轮骑行的全部提醒态：闹钟 + 周期兜底 + 常驻服务 + 通知栏上的两条提醒。 */
    private fun cancelAll(context: Context) {
        cancelAlarm(context)
        cancelPeriodicWork(context)
        EbikeFreeRideService.stop(context)
        EbikeFreeRideNotifier.cancelReminders(context)
    }

    private fun alarmPendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        ALARM_REQUEST_CODE,
        Intent(context, EbikeFreeRideAlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * 周期核对兜底：闹钟被 ROM 丢掉、或通知被用户在通知栏划掉时补一次。
     * **只在「计时进行中」期间存在**（2026-09-24 省电口径）：平时不排——否则没骑行时
     * 每 15 分钟都会冷启动一次 App 进程（`JuwApplication.onCreate` 那一串初始化），
     * 是白白付出的电。计时结束/关掉开关时由 [cancelPeriodicWork] 撤掉。
     *
     * 周期取 15 分钟：WorkManager 的最小周期就是 15 分钟（写更小会被静默提升）。
     * 幂等（KEEP）。**工作名不要改**——老版本排过的同名周期任务还在 WorkManager 库里，
     * 改名字会再排一条，两份空跑。
     */
    fun ensurePeriodicWork(context: Context) {
        runCatching {
            val request = PeriodicWorkRequestBuilder<EbikeFreeRideCheckWorker>(15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }.onFailure { Log.w(TAG, "ensurePeriodicWork failed", it) }
    }

    /** 撤掉周期兜底（计时结束 / 提醒关掉 / 无计时）。没排过时是空操作。 */
    fun cancelPeriodicWork(context: Context) {
        runCatching {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK)
        }.onFailure { Log.w(TAG, "cancelPeriodicWork failed", it) }
    }

    /** 删掉首版留下的 notification channel（新 channel 用别的 id）。重复调用是 no-op。 */
    fun deleteLegacyChannel(context: Context) {
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        }.onFailure { Log.w(TAG, "deleteLegacyChannel failed", it) }
    }

    /** 公开入口统一兜异常：闹钟 / WorkManager / 通知的失败不该把页面点崩。 */
    private suspend fun guarded(action: suspend () -> Outcome): Outcome =
        runCatching { action() }.getOrElse {
            Log.w(TAG, "ebike free ride action failed", it)
            Outcome.Failed(it.message ?: "提醒调度失败")
        }

    /** 布尔型入口的兜异常版本（[calibrateRideStart]：失败就当没校准）。 */
    private suspend fun guardedBool(action: suspend () -> Boolean): Boolean =
        runCatching { action() }.getOrElse {
            Log.w(TAG, "ebike free ride action failed", it)
            false
        }
}

/**
 * 骑行提醒闹钟的落点：把核对排进协程（`onReceive` 里不能做长任务，读 DataStore 是挂起调用）。
 *
 * 走 [Context.goAsync] 而不是 WorkManager：闹钟本身就是精确触发，再经一层 WorkManager
 * 只会引入不确定的延迟。另外，**精确闹钟触发的广播属于「允许从后台启动前台服务」的
 * 豁免场景**，所以这里重启 [EbikeFreeRideService] 是合法的（进程被杀后靠它把常驻
 * 倒计时拉回来）；即便如此，[EbikeFreeRideReminder.check] 内部对起服务也做了兜底，
 * 起不来时提醒通知照发。
 */
class EbikeFreeRideAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                EbikeFreeRideReminder.check(context.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * 免费时长核对 Worker（10 分钟周期兜底 + 老版本遗留的一次性到期清理任务）。
 *
 * **类名不要改**：WorkManager 把类名存进自己的库，老版本排下的周期任务与
 * `ebike_free_ride_cleanup` 一次性任务在应用升级后仍是这个名字；改名会让那些任务
 * 实例化失败，而 `KEEP` 策略又不会重排，兜底就永久消失了。
 * （2026-09-24 起不再新排清理任务——`check` 一次把提醒、闹钟、服务、状态全对上。）
 */
class EbikeFreeRideCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        runCatching { EbikeFreeRideReminder.check(applicationContext) }
        return Result.success()
    }
}
