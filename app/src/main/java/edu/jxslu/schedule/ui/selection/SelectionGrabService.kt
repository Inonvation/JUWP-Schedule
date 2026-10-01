package edu.jxslu.schedule.ui.selection

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.repo.GrabRequest
import edu.jxslu.schedule.data.repo.SelectionGrabStore
import edu.jxslu.schedule.data.repo.SelectionGrabber
import edu.jxslu.schedule.domain.GrabStop
import edu.jxslu.schedule.domain.SelectionGrabPolicy
import edu.jxslu.schedule.domain.SelectionWishes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 抢课会话的前台服务（DESIGN §4.36，2026-10-01 规划）。
 *
 * 结构照 `EbikeFreeRideService`：会话期间常驻通知（进度 + 一键停止），结束时收干净。
 * 与它的差别是这里跑的是 [SelectionGrabber] 的循环——**引擎的节流/退避/停止条件全在
 * 引擎与 `SelectionGrabPolicy` 里**，服务只负责「启动、把事件写进 [SelectionGrabStore]
 * 与通知、收尾」。
 *
 * 三条边界：
 * 1. **一次只跑一场会话**：重复 start 直接忽略（`job?.isActive`）；
 * 2. **用户停止 = 取消协程**：`finally` 里统一收尾（写状态、发结果通知、撤前台、停服务），
 *    取消路径也走同一出口；
 * 3. **异常不外抛**：引擎内部已把可重试异常折算成连续失败，这里再兜一层，任何情况都要
 *    有终态通知（用户看不到「服务没了但没结果」）。
 *
 * 通知权限被拒时服务照常前台运行（通知静默不可见），与骑行服务同口径。
 */
class SelectionGrabService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            requestStop()
            return START_NOT_STICKY
        }
        val roundId = intent?.getStringExtra(EXTRA_ROUND_ID).orEmpty()
        if (roundId.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        // 已在跑：忽略重复启动（前台服务回调来自通知按钮/页面，重复点很常见）
        if (job?.isActive == true) return START_NOT_STICKY

        val roundName = intent?.getStringExtra(EXTRA_ROUND_NAME).orEmpty()
        val roundEndAt = intent?.getLongExtra(EXTRA_ROUND_END_AT, 0L)?.takeIf { it > 0 }
        if (!enterForeground(roundName)) return START_NOT_STICKY
        job = scope.launch { runSession(roundId, roundName, roundEndAt) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** 进前台并挂上「准备中」的常驻通知；失败（系统限制等）返回 false。 */
    private fun enterForeground(roundName: String): Boolean = runCatching {
        SelectionGrabNotifier.ensureChannels(this)
        ServiceCompat.startForeground(
            this,
            SelectionGrabNotifier.PROGRESS_ID,
            SelectionGrabNotifier.progressNotification(
                this,
                SelectionGrabStore.State(running = true, roundName = roundName),
            ),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }.onFailure {
        Log.w(TAG, "startForeground failed", it)
        stopSelf()
    }.isSuccess

    private suspend fun runSession(roundId: String, roundName: String, roundEndAt: Long?) {
        val prefs = Graph.displayPrefs(this)
        val wishes = SelectionWishes.sorted(prefs.selectionWishes.first())
        val intervalMs = SelectionGrabPolicy.coerceInterval(prefs.selectionGrabIntervalMs())
        SelectionGrabStore.begin(roundId, roundName, wishes.size)
        updateProgress()

        var grabbedCount = 0
        var stopText: String? = null
        try {
            SelectionGrabber(
                client = Graph.selectionCenterClient(this),
                intervalMs = intervalMs,
            ).run(
                GrabRequest(
                    roundId = roundId,
                    roundName = roundName,
                    roundEndAt = roundEndAt,
                    wishes = wishes,
                ),
            ).collect { event ->
                when (event) {
                    is SelectionGrabber.Event.Checking -> {
                        SelectionGrabStore.log("第 ${event.attempt} 轮检查…")
                        updateProgress()
                    }
                    is SelectionGrabber.Event.Grabbed -> {
                        grabbedCount++
                        SelectionGrabStore.setGrabbed(grabbedCount)
                        SelectionGrabStore.log(
                            "已抢到：${event.course.name}" +
                                event.course.teacher.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty(),
                        )
                        updateProgress()
                    }
                    is SelectionGrabber.Event.Failed ->
                        SelectionGrabStore.log("未成功：${event.courseName} —— ${event.reason}")
                    is SelectionGrabber.Event.Stopped -> {
                        grabbedCount = event.grabbed.size
                        SelectionGrabStore.setGrabbed(grabbedCount)
                        updateProgress()
                        stopText = stopLabel(event.stop)
                    }
                }
            }
        } catch (e: CancellationException) {
            stopText = "已手动停止"
            throw e
        } catch (e: Exception) {
            stopText = "异常结束：${e.message ?: "未知错误"}"
        } finally {
            val text = stopText ?: "已结束"
            SelectionGrabStore.finish(text)
            SelectionGrabNotifier.cancelProgress(this)
            SelectionGrabNotifier.postResult(this, resultTitle(grabbedCount), resultBody(text, grabbedCount, wishes.size))
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /** 用户停止：取消在跑的会话（收尾统一在 `runSession` 的 `finally`）。 */
    private fun requestStop() {
        val running = job
        if (running == null) {
            stopSelf()
            return
        }
        running.cancel()
    }

    private fun updateProgress() {
        SelectionGrabNotifier.updateProgress(this, SelectionGrabStore.state.value)
    }

    companion object {
        private const val TAG = "SelectionGrabService"

        const val ACTION_STOP = "edu.jxslu.schedule.action.STOP_SELECTION_GRAB"

        private const val EXTRA_ROUND_ID = "round_id"
        private const val EXTRA_ROUND_NAME = "round_name"
        private const val EXTRA_ROUND_END_AT = "round_end_at"

        /** 启动一场抢课会话（调用方必须在前台：抢课面板/通知动作）。 */
        fun start(context: Context, roundId: String, roundName: String, roundEndAt: Long?) {
            val intent = Intent(context, SelectionGrabService::class.java)
                .putExtra(EXTRA_ROUND_ID, roundId)
                .putExtra(EXTRA_ROUND_NAME, roundName)
                .putExtra(EXTRA_ROUND_END_AT, roundEndAt ?: 0L)
            ContextCompat.startForegroundService(context, intent)
        }

        /** 请求停止（幂等；没在跑时只把服务收掉）。 */
        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, SelectionGrabService::class.java).setAction(ACTION_STOP),
                )
            }.onFailure { Log.w(TAG, "stop failed", it) }
        }

        /** 收场文案（用户看到的那一句）。 */
        fun stopLabel(stop: GrabStop): String = when (stop) {
            GrabStop.AllMatched -> "清单全部抢到了"
            GrabStop.RoundEnded -> "轮次已截止"
            GrabStop.UserStopped -> "已手动停止"
            GrabStop.TooManyFailures -> "连续失败过多，已自动停止"
            GrabStop.TimeLimit -> "达到单次会话时长上限（2 小时）"
            is GrabStop.Fatal -> "需要人工处理：${stop.reason}"
        }

        fun resultTitle(grabbedCount: Int): String =
            if (grabbedCount > 0) "抢课结束：已抢到 $grabbedCount 门" else "抢课结束：没有抢到"

        fun resultBody(stopText: String, grabbedCount: Int, targetCount: Int): String =
            "$stopText（已抢到 $grabbedCount/$targetCount 条清单）"
    }
}
