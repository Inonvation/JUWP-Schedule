package edu.jxslu.schedule.ui.ebike

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.domain.EbikeAutoReturn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 用车满 15 分钟自动还车的调度（DESIGN §3.9，2026-10-10 用户口径）。
 *
 * 与免费时长提醒**两套独立**（开关、闹钟、执行都不共享）：自动还车的开关只归自己，
 * 免费提醒开着关着都不影响它，反之亦然。三层：
 *
 * | 层 | 手段 | 作用 |
 * |----|------|------|
 * | 主 | `AlarmManager.setAlarmClock`（requestCode 4004） | 到**免费结束前 [EbikeAutoReturn.DEFAULT_LEAD_SECONDS] 秒**（可设 10~60 秒）精确触发 |
 * | 执行 | [EbikeAutoReturnWorker]（WorkManager 一次性） | 网络动作 + 3 秒间隔重试，进程被杀也还在 |
 * | 兜 | 进「骑行」页 / 账号页时的 [reconcile] | 闹钟被 ROM 丢掉时补跑（到点才打开开关不追补，见下） |
 *
 * **触发点恒在免费结束之前**（提前量下限 10 秒）：还车动作要落在免费时段内完成，
 * 不许卡在 15 分钟那一刻或之后（用户 2026-10-10 明确）。
 *
 * **只跑一次**：同一轮骑行用 [EbikeAutoReturn.doneKey] 去重——成功、重试用尽、
 * 不该重试的失败都落键；换车（新起点）自然换键。别把它删掉：没有它，
 * 失败后用一次 App 就会再跑一整串重试。
 *
 * **重试是用户显式开启的自动还车自己的行为**（次数 1~3 由设置封顶）；仓库层
 * 「写操作零自动重试」的红线不变——用户手动还车仍然只发一次。
 */
object EbikeAutoReturnReminder {

    private const val TAG = "EbikeAutoReturn"

    /** 与上课（4002）、免费提醒（4003）错开。 */
    private const val ALARM_REQUEST_CODE = 4004

    /** 一次性工作的唯一名（KEEP：在跑 / 在排就不重复入队）。 */
    private const val WORK_NAME = "ebike_auto_return"

    /**
     * 设置变更 / 进页 / 开锁成功后的统一收口：按当前偏好与计时起点决定排闹钟、补跑还是撤销。
     *
     * [catchUp] = 触发点已过时要不要立刻补跑一次：
     * - `true`（进页兜底 / 开锁）：闹钟可能被 ROM 丢掉，补跑是「没白开这个开关」的保证；
     * - `false`（设置变更）：只是把开关 / 提前量应用到**还没到点的**这一轮；触发点已过
     *   就不追补——用户刚打开开关不该让 App 立刻还车。已排着的闹钟也不撤，到点按最新
     *   设置复核。
     */
    suspend fun reconcile(context: Context, catchUp: Boolean = true) {
        val app = context.applicationContext
        runCatching {
            val prefs = Graph.displayPrefs(app)
            val enabled = prefs.ebikeAutoReturnEnabled.first()
            val startAt = prefs.ebikeRideStartAt.first()
            val now = System.currentTimeMillis()
            val account = prefs.ebikeUseMode.first().isAccount
            val loggedIn = runCatching { Graph.kqcx(app).loggedIn.value }.getOrDefault(false)
            if (!enabled || !account || !loggedIn || startAt <= 0L) {
                cancelAlarm(app)
                return@runCatching
            }
            if (prefs.ebikeAutoReturnDoneKey.first() == EbikeAutoReturn.doneKey(startAt)) {
                cancelAlarm(app)
                return@runCatching
            }
            val lead = prefs.ebikeAutoReturnLeadSeconds.first()
            val trigger = EbikeAutoReturn.triggerAtMillis(startAt, lead)
            when {
                now < trigger -> scheduleAlarm(app, trigger)
                EbikeAutoReturn.isDue(startAt, lead, now) -> {
                    if (catchUp) {
                        cancelAlarm(app)
                        enqueueNow(app)
                    }
                    // catchUp = false（设置变更）：到点但未过窗口时不追补，也不动已排的闹钟
                }
                // 本轮早已过期（超过免费结束后 5 分钟）：清掉残留排程
                else -> cancelAlarm(app)
            }
        }.onFailure { Log.w(TAG, "reconcile failed", it) }
    }

    /** 撤销自动还车闹钟（关开关 / 退出登录 / 计时结束）。没排过时是空操作。 */
    fun cancel(context: Context) {
        cancelAlarm(context.applicationContext)
    }

    /** 闹钟落点：把还车动作排进 WorkManager（网络与重试都在那里，接收器不挂长任务）。 */
    fun enqueueNow(context: Context) {
        runCatching {
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(
                    WORK_NAME,
                    ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<EbikeAutoReturnWorker>().build(),
                )
        }.onFailure { Log.w(TAG, "enqueue failed", it) }
    }

    /**
     * Worker 主体：复核全部闸门 → 跑 [runAutoReturn] → 按结果善后 / 通知 → 落去重键。
     *
     * 早退（开关关 / 不是账号方式 / 没有在案计时 / 没到点 / 已执行过）都**不落键**：
     * 那是"这次不该跑"，不是"这轮跑完了"。
     */
    suspend fun run(context: Context) {
        val app = context.applicationContext
        val prefs = Graph.displayPrefs(app)
        val enabled = prefs.ebikeAutoReturnEnabled.first()
        val startAt = prefs.ebikeRideStartAt.first()
        val now = System.currentTimeMillis()
        if (!enabled || startAt <= 0L) return
        if (!prefs.ebikeUseMode.first().isAccount) return
        val lead = prefs.ebikeAutoReturnLeadSeconds.first()
        // 没到触发点（极罕见：闹钟 / 任务早到）或已越过迟到窗口都不动手；
        // 窗口外那一种由 reconcile 清掉残留排程（见 [reconcile]）
        if (!EbikeAutoReturn.isDue(startAt, lead, now)) return
        val key = EbikeAutoReturn.doneKey(startAt)
        if (prefs.ebikeAutoReturnDoneKey.first() == key) return

        val session = Graph.kqcx(app)
        val retries = prefs.ebikeAutoReturnRetries.first()
        val strongVibration = prefs.strongReminderVibration.first()
        // 闹钟已经到点（或这是补跑）：先撤掉，免得残留一个不会再响的排程
        cancelAlarm(app)

        val result = runAutoReturn(
            session = session,
            retries = retries,
            onOrderEnded = { AppKvcxSideEffects.endRideAndBurn() },
            onAttemptFailed = { error, attempt, total, state ->
                EbikeAutoReturnNotifier.postFailure(
                    app,
                    EbikeAutoReturn.failureText(kvcxErrorText(error), attempt, total, state),
                    strongVibration,
                )
            },
        )
        when (result) {
            is AutoReturnResult.Returned -> {
                AppKvcxSideEffects.recordRide(result.summary)
                EbikeRideSignal.notifyEnded("已自动还车，订单已结束")
                EbikeAutoReturnNotifier.postSuccess(
                    app,
                    EbikeAutoReturn.successText(
                        result.summary.durationText,
                        result.summary.feeText,
                    ),
                    strongVibration,
                )
            }
            // 订单已不在（其他端还了 / 已结束）：收计时、不打扰——还车的动作不是我们做的
            AutoReturnResult.AlreadyEnded -> {
                AppKvcxSideEffects.endRideAndBurn()
                EbikeRideSignal.notifyEnded("订单已在其他端结束")
            }
            AutoReturnResult.NotLoggedIn ->
                EbikeAutoReturnNotifier.postFailure(app, EbikeAutoReturn.NOT_LOGGED_IN_TEXT, strongVibration)
            is AutoReturnResult.Failed -> Unit // 每次失败都已在 onAttemptFailed 里通知过
        }
        prefs.setEbikeAutoReturnDoneKey(key)
        cancelAlarm(app)
    }

    /** 排精确闹钟：到点唤醒设备、不受 Doze / 省电推迟、不需要特殊权限（与免费提醒同手法）。 */
    private fun scheduleAlarm(context: Context, atMillis: Long) {
        runCatching {
            val manager = context.getSystemService(AlarmManager::class.java) ?: return
            val clockPending = PendingIntent.getActivity(
                context,
                1,
                Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            manager.setAlarmClock(
                AlarmManager.AlarmClockInfo(atMillis, clockPending),
                alarmPendingIntent(context),
            )
        }.onFailure { Log.w(TAG, "scheduleAlarm failed", it) }
    }

    private fun cancelAlarm(context: Context) {
        runCatching {
            context.getSystemService(AlarmManager::class.java)
                ?.cancel(alarmPendingIntent(context))
        }.onFailure { Log.w(TAG, "cancelAlarm failed", it) }
    }

    private fun alarmPendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        ALARM_REQUEST_CODE,
        Intent(context, EbikeAutoReturnAlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/**
 * 自动还车闹钟的落点。走 `goAsync` + 一次性工作入队：`onReceive` 里不做网络与重试
 * （那是 [EbikeAutoReturnWorker] 的活）。精确闹钟触发的广播属于「允许从后台启动
 * 前台服务 / 工作」的豁免场景，WorkManager 入队在任何后台态都合法。
 */
class EbikeAutoReturnAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                EbikeAutoReturnReminder.enqueueNow(context.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * 自动还车的一次性工作。**类名不要改**：WorkManager 把类名存进自己的库，
 * 改名会让已入队的旧任务实例化失败（与 `EbikeFreeRideCheckWorker` 同一纪律）。
 */
class EbikeAutoReturnWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        runCatching { EbikeAutoReturnReminder.run(applicationContext) }
        return Result.success()
    }
}
