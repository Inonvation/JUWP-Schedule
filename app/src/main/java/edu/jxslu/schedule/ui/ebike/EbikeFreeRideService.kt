package edu.jxslu.schedule.ui.ebike

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import edu.jxslu.schedule.domain.EbikeFreeRide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 免费时长常驻倒计时（DESIGN §3.9，2026-09-24 新增）。
 *
 * **它只做一件事：让通知栏上那条倒计时活着。** 到点提醒不归它管——那是
 * `setAlarmClock` 的活（见 `EbikeFreeRideReminder`）。理由：屏幕关闭后 CPU 会挂起，
 * 服务里的协程 `delay` 要等到下一个唤醒点才跑，会迟到几分钟；而 `setAlarmClock`
 * 是系统级精确闹钟，到点唤醒设备，不受 Doze/省电推迟。
 *
 * 刷新策略（2026-09-24 省电口径）：
 * - **亮屏**：每秒把剩余时间写进通知（`EbikeFreeRide.countdownText`），用户看得到跳秒；
 * - **灭屏**：**一个 notify 都不发**——通知栏此刻没人看，这是 15 分钟里最大的一块浪费。
 *   协程阻塞在屏幕事件上等（零 CPU），计时到点也由 `withTimeoutOrNull` 兜住；
 * - **重新亮屏**：立刻补刷一次，所以点亮屏幕的瞬间数值就是准的（不会停在一个旧值上）。
 *
 * 生命周期：点「打开微信扫一扫」时启动（此刻 App 在前台，`startForegroundService`
 * 合法）；用户点「结束骑行」、或免费结束后的核对、或本服务自己的兜底延时到点
 * （[STOP_GRACE_MS]）时停止。**不做开机补排**：15 分钟的计时重启后必然过期
 * （用户拍板 2026-09-22）。
 *
 * 启动方一律用 [start] / [stop]，别自己拼 Intent。
 */
class EbikeFreeRideService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickJob: Job? = null

    /** 屏幕事件通道（CONFLATED：只关心最新状态，不关心积压了几个事件）。 */
    private val screenEvents = Channel<Boolean>(Channel.CONFLATED)

    private var screenReceiver: BroadcastReceiver? = null

    /** 屏幕是否亮着。由 [screenReceiver] 在主线程写、tick 协程在读，所以是 volatile。 */
    @Volatile
    private var screenOn = true

    override fun onCreate() {
        super.onCreate()
        screenOn = (getSystemService(PowerManager::class.java))?.isInteractive ?: true
        registerScreenReceiver()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val startAt = intent?.getLongExtra(EXTRA_START_AT, 0L) ?: 0L
        if (startAt <= 0L || !EbikeFreeRide.isActive(startAt, System.currentTimeMillis())) {
            // 没有进行中的计时（或已经结束）：常驻通知没有存在理由
            shutdown()
            return START_NOT_STICKY
        }
        if (!enterForeground(startAt)) return START_NOT_STICKY
        startTicking(startAt)
        return START_NOT_STICKY
    }

    /** 进前台并挂上常驻通知；失败（系统限制等）返回 false 并收尾。 */
    private fun enterForeground(startAt: Long): Boolean = runCatching {
        EbikeFreeRideNotifier.ensureChannels(this)
        ServiceCompat.startForeground(
            this,
            EbikeFreeRide.NotificationIds.COUNTDOWN,
            EbikeFreeRideNotifier.countdownNotification(this, startAt, System.currentTimeMillis()),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        // 记下这一轮 tick 用的起点：换车时它变了，start() 就会重建
        runningStartAt = startAt
    }.onFailure {
        // 通知权限被拒不会抛异常（通知静默不显示，服务照常前台）；
        // 走到这里通常是系统层面的限制，停掉比留一个没有通知的「前台服务」干净
        Log.w(TAG, "startForeground failed", it)
        shutdown()
    }.isSuccess

    /**
     * 亮屏每秒刷新、灭屏不刷新的 tick 循环（见类 KDoc）。
     *
     * 到点提醒**不在这里发**——那是 `setAlarmClock` 的活；本协程只负责「刷新通知」
     * 与「到点后把常驻通知摘掉」两件事。
     */
    private fun startTicking(startAt: Long) {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                if (!EbikeFreeRide.isActive(startAt, now)) break
                if (screenOn) {
                    EbikeFreeRideNotifier.updateCountdown(this@EbikeFreeRideService, startAt, now)
                    // 等满一秒，或等屏幕状态变化（灭屏立刻停手，不用等满这一秒）
                    withTimeoutOrNull(TICK_MS) { screenEvents.receive() }
                } else {
                    // 灭屏：不刷任何通知，阻塞等屏幕亮；最长等到免费结束，
                    // 免得整段灭屏时协程醒不过来、错过收尾
                    val remain = EbikeFreeRide.freeEndMillis(startAt) - System.currentTimeMillis()
                    withTimeoutOrNull(remain.coerceAtLeast(1L)) { screenEvents.receive() }
                }
            }
            // 免费结束：让结束点的闹钟先把提醒发出去，再把常驻通知摘掉。
            // 闹钟真丢了也不会让通知栏一直挂着（提醒由周期核对补发，不依赖这里）。
            delay(STOP_GRACE_MS)
            shutdown()
        }
    }

    private fun registerScreenReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val on = intent.action == Intent.ACTION_SCREEN_ON
                screenOn = on
                screenEvents.trySend(on)
            }
        }
        screenReceiver = receiver
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        // 动态注册只在服务存活期间有效，随服务一起消失；屏幕广播是系统发的，
        // 用 RECEIVER_NOT_EXPORTED 收（API 33+ 强制要求声明可见性）
        runCatching {
            ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }.onFailure { Log.w(TAG, "register screen receiver failed", it) }
    }

    override fun onDestroy() {
        tickJob?.cancel()
        tickJob = null
        scope.cancel()
        screenReceiver?.let { receiver -> runCatching { unregisterReceiver(receiver) } }
        screenReceiver = null
        runningStartAt = 0L
        // stopService 走的是这条路径（不经过 onStartCommand），通知得在这里摘
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    /** 摘掉常驻通知并结束自己。可重入（[onDestroy] 里的清理是幂等的）。 */
    private fun shutdown() {
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    companion object {
        private const val TAG = "EbikeFreeRideService"
        private const val EXTRA_START_AT = "ebike_free_start_at"

        /**
         * 服务自己的兜底收尾延时：晚于结束点闹钟一点，让闹钟先把「已结束」提醒发出去
         * 再摘常驻通知。闹钟真丢了也不会让通知栏一直挂着。
         */
        private const val STOP_GRACE_MS = 2 * 60_000L

        /**
         * 常驻通知的刷新间隔。**每秒一次是系统允许的上限**（更密会被 NotificationManager
         * 限流丢弃），也正是倒计时需要的粒度；灭屏时完全不刷，见类 KDoc。
         */
        private const val TICK_MS = 1_000L

        /**
         * 服务当前 tick 用的起点（进程内；0 = 没在跑）。进程被杀即归零——那正是要的语义。
         *
         * 它是 [start] 的唯一判据，作用有两层：
         * - **换车必须重建**：起点变了（用户又扫了一辆），tick 循环握着的是旧起点，
         *   不重新 `onStartCommand` 就还在刷旧的剩余时间。判据必须是「起点是否相同」，
         *   不能是「服务是否在跑」——2026-09-24 修：早先只看在不在跑，于是上一轮没结束
         *   就换车时倒计时不重置；
         * - 幂等：`check` 每轮都会调 [start]，起点没变时跳过，省掉一次 `startForegroundService`
         *   与 tick 循环的推倒重建。
         */
        @Volatile
        private var runningStartAt = 0L

        /**
         * 起常驻倒计时（起点变了就重建 tick）。返回 false = 起点非法、或这次没起成
         * （多为后台启动前台服务被系统拒绝，见 `restrictions-bg-start`），不抛异常。
         */
        fun start(context: Context, startAtMillis: Long): Boolean {
            if (!EbikeFreeRide.shouldStartCountdown(runningStartAt, startAtMillis)) {
                // 同一起点已经在跑 = 幂等成功；起点非法（≤ 0）= 失败
                return startAtMillis > 0L
            }
            return runCatching {
                context.startForegroundService(
                    Intent(context, EbikeFreeRideService::class.java)
                        .putExtra(EXTRA_START_AT, startAtMillis),
                )
            }.onFailure { Log.w(TAG, "start failed", it) }.isSuccess
        }

        /** 停常驻倒计时（通知随之摘掉）。没在跑时是空操作。 */
        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, EbikeFreeRideService::class.java))
            }.onFailure { Log.w(TAG, "stop failed", it) }
        }
    }
}
