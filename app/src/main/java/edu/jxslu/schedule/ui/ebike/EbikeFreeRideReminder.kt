package edu.jxslu.schedule.ui.ebike

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.calendar.EbikeCalendarEvents
import edu.jxslu.schedule.domain.EbikeFreeRide
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * 共享单车免费时长 → 系统日历（DESIGN §3.9，2026-09-23 由 App 通知改系统日历）。
 *
 * 只做三件事：点「打开微信扫一扫」时按当前提前量写一条日历事件（锚在免费结束时刻，
 * 挂「提前 N 分钟」与「结束那一刻」两条提醒）；「结束骑行」时按 id 删掉；
 * 计时过期后把状态清干净。提醒由日历 App 发，所以这套东西不需要 App 进程活着、
 * 不需要 POST_NOTIFICATIONS，也不再占一个精确闹钟——2026-09-22 首版的 `setAlarmClock`、
 * 通知 channel `ebike_free_ride`、[EbikeFreeRideReceiver] 整套删掉。
 *
 * 删事件有三条路：用户点「结束骑行」（立即）、免费结束后的兜底任务（[scheduleCleanup]，
 * 尽力触发，省电模式下可能晚几分钟）、进页 / 冷启动 / 周期核对（[check]）。
 * 进程被杀且兜底任务没跑成时，事件会留到下次打开 App —— 这是交给系统日历的代价。
 * 开机补排**不做**（用户拍板 2026-09-22）：重启后进行中的计时静默失效，残留事件
 * 由下一次 [check] 清掉。
 */
object EbikeFreeRideReminder {

    private const val TAG = "EbikeFreeRide"
    private const val CLEANUP_WORK = "ebike_free_ride_cleanup"
    private const val PERIODIC_WORK = "ebike_free_ride_periodic"

    /** 2026-09-22 首版建的 notification channel（2026-09-23 已不再发通知，见 [deleteLegacyChannel]）。 */
    private const val LEGACY_CHANNEL_ID = "ebike_free_ride"

    /**
     * 到期清理的宽限（毫秒）：事件最后一条提醒就在免费结束那一刻，
     * 早删一秒会把它一起抹掉，宁可晚一分钟。
     */
    private const val CLEANUP_GRACE_MS = 60_000L

    /** 一次动作的结果，供调用方拼提示文案。 */
    sealed interface Outcome {
        /** 日历里已有事件（新建或重建）。 */
        data object Written : Outcome

        /** 日历里的事件已删除。 */
        data object Removed : Outcome

        /** 这次动作不该或不用碰日历（开关关着、没有在案事件）。 */
        data object None : Outcome

        data object NoPermission : Outcome

        data object NoCalendarAccount : Outcome

        data class Failed(val message: String) : Outcome
    }

    /**
     * 点「打开微信扫一扫」：记起点、排到期清理、写日历事件（开关关着只记起点）。
     * 换车再点一次 = 重新计时，旧事件被删掉重建。
     */
    suspend fun startRide(context: Context, startAtMillis: Long): Outcome = guarded {
        val prefs = Graph.displayPrefs(context)
        prefs.setEbikeRideStartAt(startAtMillis)
        scheduleCleanup(context, EbikeFreeRide.freeEndMillis(startAtMillis))
        if (!prefs.ebikeFreeReminderEnabled.first()) return@guarded Outcome.None
        writeEvent(context, startAtMillis)
    }

    /**
     * 结束骑行：清起点、撤到期清理、删日历事件。
     */
    suspend fun endRide(context: Context): Outcome = guarded {
        val prefs = Graph.displayPrefs(context)
        prefs.setEbikeRideStartAt(0L)
        cancelCleanup(context)
        removeEvent(context)
    }

    /**
     * 开关 / 提前量变更后重建：提前量变了旧的那条提醒要跟着变，
     * 所以这里是「删掉再写」而不是改 Reminders。开关关 → 只删。
     */
    suspend fun reschedule(context: Context): Outcome = guarded {
        val prefs = Graph.displayPrefs(context)
        val startAt = prefs.ebikeRideStartAt.first()
        val enabled = prefs.ebikeFreeReminderEnabled.first()
        if (!enabled || !EbikeFreeRide.isActive(startAt, System.currentTimeMillis())) {
            cancelCleanup(context)
            return@guarded removeEvent(context)
        }
        scheduleCleanup(context, EbikeFreeRide.freeEndMillis(startAt))
        writeEvent(context, startAt)
    }

    /**
     * 兜底核对（进页 / 冷启动 / 兜底任务）：计时中缺事件就补建，
     * 计时结束或开关关着就清掉事件与计时状态。幂等；无权限时静默返回。
     */
    suspend fun check(context: Context): Outcome = guarded {
        val prefs = Graph.displayPrefs(context)
        val startAt = prefs.ebikeRideStartAt.first()
        val enabled = prefs.ebikeFreeReminderEnabled.first()
        val eventId = prefs.ebikeFreeEventId.first()
        val now = System.currentTimeMillis()
        val active = enabled && EbikeFreeRide.isActive(startAt, now)

        if (!active && startAt != 0L && !EbikeFreeRide.isActive(startAt, now)) {
            // 计时已结束（或开关被关掉后放着）：起点与兜底任务都不该再留着
            prefs.setEbikeRideStartAt(0L)
            cancelCleanup(context)
        }
        if (active) {
            if (eventId != 0L) return@guarded Outcome.None
            // 缺事件：权限刚授予、事件被用户在日历里删了、上次写入失败
            return@guarded writeEvent(context, startAt)
        }
        if (enabled || eventId != 0L) return@guarded removeEvent(context)
        Outcome.None
    }

    /** 到期清理：免费结束后 [CLEANUP_GRACE_MS] 触发一次核对。幂等（REPLACE）。 */
    fun scheduleCleanup(context: Context, freeEndAtMillis: Long) {
        runCatching {
            val delay = (freeEndAtMillis + CLEANUP_GRACE_MS - System.currentTimeMillis())
                .coerceAtLeast(0L)
            val request = OneTimeWorkRequestBuilder<EbikeFreeRideCheckWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.NONE)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(CLEANUP_WORK, ExistingWorkPolicy.REPLACE, request)
        }.onFailure { Log.w(TAG, "scheduleCleanup failed", it) }
    }

    /** 撤销到期清理（结束骑行 / 撤销计时时）。 */
    fun cancelCleanup(context: Context) {
        runCatching {
            WorkManager.getInstance(context).cancelUniqueWork(CLEANUP_WORK)
        }.onFailure { Log.w(TAG, "cancelCleanup failed", it) }
    }

    /**
     * 15 分钟周期核对兜底：兜底任务被 ROM 推迟、或事件在别处被删掉时补一次。
     * 幂等（KEEP）。**工作名不要改**——老版本排过的同名周期任务还在 WorkManager 库里，
     * 改名字会再排一条，两份空跑。
     */
    fun ensurePeriodicWork(context: Context) {
        runCatching {
            val request = PeriodicWorkRequestBuilder<EbikeFreeRideCheckWorker>(10, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }.onFailure { Log.w(TAG, "ensurePeriodicWork failed", it) }
    }

    /**
     * 删掉首版留下的 notification channel：channel 一旦建出来就常驻系统
     * （用户能在应用通知设置里看到「共享单车免费时长」这一类别），代码删掉它不会自己消失，
     * 所以冷启动时清一次。重复调用是 no-op。
     */
    fun deleteLegacyChannel(context: Context) {
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        }.onFailure { Log.w(TAG, "deleteLegacyChannel failed", it) }
    }

    /** 写（或重建）事件：先按标记清一遍残留，再插入并把 id 落库。 */
    private suspend fun writeEvent(context: Context, startAtMillis: Long): Outcome {
        val prefs = Graph.displayPrefs(context)
        val lead = prefs.ebikeFreeLeadMinutes.first()
        prefs.setEbikeFreeEventId(0L)
        EbikeCalendarEvents.deleteAll(context)
        return when (val result = EbikeCalendarEvents.create(context, startAtMillis, lead)) {
            is EbikeCalendarEvents.CreateResult.Ok -> {
                prefs.setEbikeFreeEventId(result.eventId)
                Outcome.Written
            }
            EbikeCalendarEvents.CreateResult.NoPermission -> Outcome.NoPermission
            EbikeCalendarEvents.CreateResult.NoCalendarAccount -> Outcome.NoCalendarAccount
            is EbikeCalendarEvents.CreateResult.Error -> Outcome.Failed(result.message)
        }
    }

    /**
     * 删事件：按标记删（同时只会有一条在案），并清掉落库的 id。
     * 用户已在日历里手动删了就返回 [Outcome.None]。
     */
    private suspend fun removeEvent(context: Context): Outcome {
        val prefs = Graph.displayPrefs(context)
        prefs.setEbikeFreeEventId(0L)
        return when (val result = EbikeCalendarEvents.deleteAll(context)) {
            is EbikeCalendarEvents.DeleteResult.Deleted ->
                if (result.count > 0) Outcome.Removed else Outcome.None
            EbikeCalendarEvents.DeleteResult.NoPermission -> Outcome.NoPermission
            is EbikeCalendarEvents.DeleteResult.Error -> Outcome.Failed(result.message)
        }
    }

    /** 公开入口统一兜异常：日历 / WorkManager 的失败不该把页面点崩。 */
    private suspend fun guarded(action: suspend () -> Outcome): Outcome =
        runCatching { action() }.getOrElse {
            Log.w(TAG, "ebike free ride action failed", it)
            Outcome.Failed(it.message ?: "日历操作失败")
        }
}

/**
 * 免费时长核对 Worker（闹钟没了，它现在只服务两件事：免费结束后的到期清理、
 * 15 分钟周期兜底）。
 *
 * **类名不要改**：WorkManager 把类名存进自己的库，老版本排下的周期任务在
 * 应用升级后仍是这个名字；改名会让那些任务实例化失败，而 `KEEP` 策略又不会重排，
 * 兜底就永久消失了。
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
