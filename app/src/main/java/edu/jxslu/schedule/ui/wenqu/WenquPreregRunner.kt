package edu.jxslu.schedule.ui.wenqu

import android.content.Context
import android.util.Log
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.prefs.DisplayPrefsStore
import edu.jxslu.schedule.data.wenqu.WenquPreregAttempt
import edu.jxslu.schedule.data.wenqu.WenquPreregCheck
import edu.jxslu.schedule.data.wenqu.WenquServerClock
import edu.jxslu.schedule.data.wenqu.WenquSessionExpiredException
import edu.jxslu.schedule.domain.WenquRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * 预报名执行器（DESIGN §4.45，2026-10-16；用户拍板）：到点后「校对 → 限次试发 → 只读核对 → 通知」。
 *
 * 时序（报名开始时刻 T，服务器钟口径）：
 * 1. **T−60s**（闹钟提前量）：预热 token（要重登就现在登）→ 并发拉列表第一页 + 本条详情
 *    校对状态 / 名额 / 报名时间（平台改过时间就顺延重排），顺手用响应 `Date` 头校准服务器钟；
 * 2. **T−15s 起**：进入试发段——**串行**试发报名，结果不明时先只读核对（「其实报上了」
 *    不当失败；平台明确回「尚未开始」时不核对），发与发之间
 *    [WenquRules.PREREG_ATTEMPT_GAP_MS]；
 * 3. **收工**：成功 / 平台硬拒（已满 / 被限制 / 已下架）/ 次数与时长触顶
 *    （[WenquRules.preregShouldStop]）；结果通知 + 清落盘；
 * 4. **收工终检（2026-10-21）**：触顶收工前最后一次**耐心**核对（10 秒档）——高峰期
 *    「某发超时但其实已落库」会把已报上的误报成没抢到（2026-10-19 用户实测），终检把它捞回来。
 *
 * 幂等与并发：进程内 [running] + 落盘双闸；闹钟与兜底 Work 同时打进来也只跑一份。
 * 中途被用户取消（落盘被清）：本轮立即收手、不打扰。
 *
 * **手动报名 × 自动抢互锁（2026-10-21）**：每轮试发前查 [WenquRepository.appliedInIndex]
 * （用户在 App 里手动报名成功会就地置「已报名」），为真即按成功收工，脚本不对自己人抢。
 *
 * **仪表板（2026-10-21）**：执行全程把阶段 / 次数 / 最近平台话术推 [dash]，通知与前台
 * 弹层各读一份；终态文案只写一次，通知与面板天然同源。重试轮（用户点「重试」）走同一
 * 执行器，预算独立（[WenquRules.PREREG_RETRY_MAX_ATTEMPTS] / [WenquRules.PREREG_RETRY_RUN_MS]）。
 */
object WenquPreregRunner {

    private const val TAG = "WenquPrereg"

    private val running = AtomicBoolean(false)

    private val _dash = MutableStateFlow(WenquPreregDashState())

    /** 仪表板状态（前台弹层 / 悬浮球 / 结果页唯一数据源）。 */
    val dash: StateFlow<WenquPreregDashState> = _dash.asStateFlow()

    /** 进程内写口（同模块：服务中断上报、面板手动报名回填「最近返回」）。 */
    internal fun updateDash(transform: (WenquPreregDashState) -> WenquPreregDashState) {
        _dash.value = transform(_dash.value)
    }

    /** 执行结果（通知与日志用；UI 消费 [dash]）。 */
    data class Outcome(val kind: Kind, val attempts: Int, val elapsedMs: Long) {
        enum class Kind { Success, Failure, Rescheduled, Skipped }
    }

    /**
     * 跑一轮预报名。[retry] = true 是用户手动点的重试轮（短预算；false = 闹钟/兜底的主轮）。
     * 可重复调用：没排上 / 已在跑 / 已被取消都是空跑。
     *
     * **重试轮的落盘口径**：主轮收工时已把落盘清掉（`stillArmed` 判据靠它），
     * 重试前先从仪表板状态取最近一轮的目标**重新落盘**（`setWenquPrereg`）——
     * 不重排闹钟（轮次已经过了），只是让取消自查、幂等闸照常工作。
     */
    suspend fun run(context: Context, showProgress: Boolean, retry: Boolean = false): Outcome {
        val prefs = Graph.displayPrefs(context)
        var id = prefs.wenquPreregId.first()
        var title = prefs.wenquPreregTitle.first()
        var opensAt = prefs.wenquPreregOpensAt.first()
        if (retry) {
            // 重试轮：落盘已被主轮清掉，从仪表板状态里取最近一轮的目标重新落盘
            val dash = dash.value
            if (id <= 0L && dash.id > 0L) {
                id = dash.id
                title = dash.title
                opensAt = dash.opensAt
                prefs.setWenquPrereg(id, title, opensAt)
            }
        }
        if (id <= 0L || opensAt <= 0L) return Outcome(Outcome.Kind.Skipped, 0, 0L)
        // 提前太早被拉起（兜底 Work 早跑）：不做，交还给闹钟。
        // 重试轮不受这条限制——它本来就是「窗口过后用户手动补一刀」，是轮次内的合法迟到。
        if (!retry && System.currentTimeMillis() < WenquRules.preregAlarmAt(opensAt) - 5_000L) {
            return Outcome(Outcome.Kind.Skipped, 0, 0L)
        }
        if (!running.compareAndSet(false, true)) return Outcome(Outcome.Kind.Skipped, 0, 0L)
        try {
            return execute(context, prefs, id, title, opensAt, showProgress, retry)
        } finally {
            running.set(false)
        }
    }

    private suspend fun execute(
        context: Context,
        prefs: DisplayPrefsStore,
        id: Long,
        title: String,
        storedOpensAt: Long,
        showProgress: Boolean,
        retry: Boolean,
    ): Outcome {
        val repo = Graph.wenqu(context)
        val startedAt = System.currentTimeMillis()
        val maxAttempts = if (retry) WenquRules.PREREG_RETRY_MAX_ATTEMPTS else WenquRules.PREREG_MAX_ATTEMPTS
        val runBudgetMs = if (retry) WenquRules.PREREG_RETRY_RUN_MS else WenquRules.PREREG_RUN_MS
        val dashState = WenquPreregDashState(
            id = id, title = title, opensAt = storedOpensAt,
            phase = WenquPreregDashState.Phase.Checking,
            maxAttempts = maxAttempts,
        )
        _dash.value = dashState
        val progress: (String) -> Unit = { text ->
            _dash.value = _dash.value.copy(lastMessage = text)
            if (showProgress) WenquPreregReminder.updateProgress(context, text)
        }

        // 1) 会话预热：要重登就现在登（失败静默，后面各步自己会暴露）
        runCatching { repo.prewarmSession() }
        progress("正在核对该活动的最新状态…")

        // 2) 校对：并发拉列表第一页（账号级限制）+ 本条详情（状态 / 名额 / 报名时间）
        val snapshot = try {
            repo.preregSnapshot(id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        var opensAt = storedOpensAt
        if (snapshot != null) {
            if (snapshot.blacklisted) {
                val reason = snapshot.stopApplyReason.ifBlank { "已被限制报名" }
                clearIfCurrent(prefs, id)
                finish(
                    context, prefs, id, dashState.copy(
                        phase = WenquPreregDashState.Phase.Done,
                        success = false, lastMessage = reason,
                        resultText = "当前账号被限制报名（$reason），请找指导老师确认。",
                    ),
                )
                WenquPreregReminder.postResult(
                    context,
                    "预报名未执行：$title",
                    "当前账号被限制报名（$reason），请找指导老师确认。",
                )
                return Outcome(Outcome.Kind.Failure, 0, System.currentTimeMillis() - startedAt)
            }
            val activity = snapshot.activity
            if (activity != null) {
                if (activity.applied) {
                    // 已经报上了（用户手动报了 / 上一轮已成交）：不需要再抢
                    clearIfCurrent(prefs, id)
                    finish(
                        context, prefs, id, dashState.copy(
                            phase = WenquPreregDashState.Phase.Done, success = true,
                            resultText = "这条活动此前已经报上名了，无需再抢。",
                        ),
                    )
                    WenquPreregReminder.postResult(
                        context,
                        "报名成功：$title",
                        "这条活动此前已经报上名了，无需再抢。",
                        vibrate = false,
                    )
                    return Outcome(Outcome.Kind.Success, 0, System.currentTimeMillis() - startedAt)
                }
                // 平台改过报名时间（差 1 分钟以上才算改）：未来的顺延重排；已过的按现状继续
                val platformOpen = WenquRules.stampMillis(activity.applyStartTime)
                if (platformOpen != null && abs(platformOpen - storedOpensAt) >= 60_000L) {
                    val now = System.currentTimeMillis()
                    if (platformOpen > now) {
                        prefs.setWenquPrereg(id, title, platformOpen)
                        WenquPreregReminder.scheduleNext(context)
                        _dash.value = WenquPreregDashState()
                        WenquPreregReminder.postResult(
                            context,
                            "预报名已顺延：$title",
                            "报名时间有变化，已改到 ${WenquRules.preregOpenLabel(platformOpen, now)} 自动抢。",
                            vibrate = false,
                        )
                        return Outcome(Outcome.Kind.Rescheduled, 0, now - startedAt)
                    }
                    opensAt = platformOpen
                }
                when (activity.status) {
                    0 -> return abort(context, prefs, id, dashState, "活动已被平台取消", startedAt)
                    2 -> return abort(context, prefs, id, dashState, "活动已开始，报名已截止", startedAt)
                    3 -> return abort(context, prefs, id, dashState, "活动已结束", startedAt)
                }
                // 这里**故意不拿快照的 balance_num 判「已满」**：活动还没开放报名时，
                // 平台完全可能把剩余名额写成 0，提前 abort 等于把还能抢的活动直接判死。
                // 到点真发才是权威——第一发若回「名额已满」，下面按终结拒绝收工。
            }
        }

        // 3) 等到试发点（服务器钟口径）
        _dash.value = _dash.value.copy(
            phase = WenquPreregDashState.Phase.Waiting,
            opensAt = opensAt, lastMessage = "已就位，等报名开放",
        )
        val tryStartPhone = WenquRules.preregTryStartAt(opensAt, WenquServerClock.offsetMs)
        while (true) {
            val waitMs = tryStartPhone - System.currentTimeMillis()
            if (waitMs <= 0L) break
            if (!stillArmed(prefs, id)) {
                return skipped(context, prefs, id)
            }
            delay(minOf(waitMs, 500L))
        }

        // 4) 试发段：串行单发；结果不明才只读核对；平台硬拒 / 触顶收工
        _dash.value = _dash.value.copy(phase = WenquPreregDashState.Phase.Firing)
        var attempts = 0
        var lastSoftReason = ""
        var success = false
        var terminalText: String? = null
        while (true) {
            val phoneNow = System.currentTimeMillis()
            if (stopNow(attempts, startedAt, phoneNow, opensAt, maxAttempts, runBudgetMs, retry)) break
            if (!stillArmed(prefs, id)) {
                return skipped(context, prefs, id)
            }
            // 互锁（2026-10-21）：用户手动报名成功 → 索引那条已是「已报名」，立即按成功收工
            if (repo.appliedInIndex(id)) {
                success = true
                terminalText = null
                _dash.value = _dash.value.copy(lastMessage = "检测到已报名（手动），停止自动抢")
                Log.i(TAG, "interlock: applied in index, stop")
                break
            }
            attempts++
            _dash.value = _dash.value.copy(
                attempts = attempts, lastMessage = "正在提交报名…（第 $attempts 发）",
            )
            if (showProgress) WenquPreregReminder.updateProgress(context, "正在提交报名…（第 $attempts 发）")
            var needCheck = true
            val outcome: WenquPreregAttempt? = try {
                repo.preregApplyOnce(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: WenquSessionExpiredException) {
                // 凭证在排上之后失效（退登 / 被吊销）：没法提交，收工说清楚
                val msg = e.message?.takeIf { it.isNotBlank() } ?: "请重新登录"
                terminalText = "登录态失效（$msg），请重新登录后再试。"
                needCheck = false
                null
            } catch (_: Exception) {
                null // 网络 / 超时 / 响应残缺：结果不明，先核对
            }
            when (outcome) {
                WenquPreregAttempt.Applied -> {
                    success = true
                    needCheck = false
                }

                is WenquPreregAttempt.SessionExpired -> {
                    terminalText = "登录态失效（${outcome.msg}），请重新登录后再试。"
                    needCheck = false
                }

                is WenquPreregAttempt.Rejected -> {
                    _dash.value = _dash.value.copy(lastMessage = "平台：${outcome.msg}")
                    when {
                        WenquRules.preregRejectIsApplied(outcome.kind) -> {
                            success = true
                            needCheck = false
                        }

                        WenquRules.preregRejectIsTerminal(outcome.kind) -> {
                            terminalText = outcome.msg
                            needCheck = false
                        }

                        else -> {
                            lastSoftReason = outcome.msg
                            // 「尚未开始」= 这一发确定没落库，跳过详情核对，把下一个来回
                            // 留给真正的试发（热门活动开放后的头两秒最值钱）。
                            if (!WenquRules.preregNeedsReadBack(outcome.kind)) {
                                needCheck = false
                            }
                        }
                    }
                }

                null -> {
                    lastSoftReason = "网络异常"
                    _dash.value = _dash.value.copy(lastMessage = "请求超时，结果不明，核对中…")
                }
            }
            if (success || terminalText != null) break
            if (needCheck) {
                when (repo.preregAppliedNow(id)) {
                    WenquPreregCheck.Applied -> {
                        success = true
                        break
                    }

                    WenquPreregCheck.NotApplied, WenquPreregCheck.Unknown -> Unit
                }
            }
            delay(WenquRules.PREREG_ATTEMPT_GAP_MS)
        }

        // 5) 收工终检（2026-10-21）：不是成功也不是明确终结时，做最后一次**耐心**核对
        //    ——不再赶下一发，超时放宽到 10 秒，把「其实报上了」捞回来。
        //    试发过（触顶/网络类收工）与**一发没发**（执行启动得太晚，2026-10-21 修）都做：
        //    后者一样可能「其实已报上」（手动报过 / 上一轮残留 / 重试轮已成交），终检零成本。
        var finalCheckNote = ""
        if (!success && terminalText == null) {
            _dash.value = _dash.value.copy(phase = WenquPreregDashState.Phase.FinalCheck)
            if (showProgress) WenquPreregReminder.updateProgress(context, "收工核对：确认是否已报上…")
            when (repo.preregAppliedNowSlow(id)) {
                WenquPreregCheck.Applied -> {
                    success = true
                    finalCheckNote = if (attempts > 0) {
                        "其中一次试发其实已生效，收工核对时确认报上（旧版本会误报「没抢到」）。"
                    } else {
                        "执行启动得太晚没来得及提交，但核对发现该活动已报上（此前某次已成交），无需再抢。"
                    }
                    Log.i(TAG, "final check recovered applied attempts=$attempts")
                }

                WenquPreregCheck.NotApplied -> finalCheckNote = "收工核对已确认未报上。"

                WenquPreregCheck.Unknown -> finalCheckNote =
                    "网络拥堵导致收工核对也没成功，请进活动详情确认是否已报上，再决定是否手动报名。"
            }
            // 终检期间用户取消：静默按取消收尾，不报结果
            if (!stillArmed(prefs, id) && !success) {
                return skipped(context, prefs, id)
            }
        }

        // 6) 收尾：结果通知 + 清落盘
        val elapsedMs = System.currentTimeMillis() - startedAt
        val serverLateMs = System.currentTimeMillis() - WenquServerClock.offsetMs - opensAt
        return when {
            success -> {
                clearIfCurrent(prefs, id)
                val recovered = finalCheckNote.isNotBlank()
                val resultText = if (recovered) {
                    finalCheckNote
                } else {
                    "已自动提交成功（第 $attempts 发命中）。可在活动详情里查看「已报名」。"
                }
                finish(
                    context, prefs, id, _dash.value.copy(
                        phase = WenquPreregDashState.Phase.Done, success = true,
                        attempts = attempts, resultText = resultText, resultNote = finalCheckNote,
                    ),
                )
                WenquPreregReminder.postResult(
                    context,
                    "报名成功：$title",
                    resultText,
                )
                Log.i(
                    TAG,
                    "run ok attempts=$attempts elapsedMs=$elapsedMs serverLateMs=$serverLateMs " +
                        "offsetMs=${WenquServerClock.offsetMs} recovered=$recovered",
                )
                Outcome(Outcome.Kind.Success, attempts, elapsedMs)
            }

            terminalText != null -> {
                clearIfCurrent(prefs, id)
                finish(
                    context, prefs, id, _dash.value.copy(
                        phase = WenquPreregDashState.Phase.Done, success = false,
                        attempts = attempts, resultText = terminalText,
                        lastMessage = terminalText,
                    ),
                )
                WenquPreregReminder.postResult(context, "没抢到：$title", terminalText)
                Log.i(TAG, "run terminal attempts=$attempts elapsedMs=$elapsedMs serverLateMs=$serverLateMs")
                Outcome(Outcome.Kind.Failure, attempts, elapsedMs)
            }

            else -> {
                clearIfCurrent(prefs, id)
                val text = if (attempts == 0) {
                    "执行启动得太晚（系统延迟），没来得及提交，请手动查看。"
                } else {
                    val tail = if (lastSoftReason.isNotBlank()) {
                        "最近一次返回：$lastSoftReason"
                    } else {
                        "网络一直没通"
                    }
                    "窗口内没提交上（共试 $attempts 发，$tail）。$finalCheckNote"
                }
                finish(
                    context, prefs, id, _dash.value.copy(
                        phase = WenquPreregDashState.Phase.Done, success = false,
                        attempts = attempts, resultText = text, resultNote = finalCheckNote,
                    ),
                )
                WenquPreregReminder.postResult(context, "没抢到：$title", text)
                Log.i(TAG, "run exhausted attempts=$attempts elapsedMs=$elapsedMs serverLateMs=$serverLateMs")
                Outcome(Outcome.Kind.Failure, attempts, elapsedMs)
            }
        }
    }

    /**
     * 本轮该不该收工（主轮与重试轮共用判据，预算各自传）。
     *
     * 第三闸「报名开始后 ≤90 秒」**只约束主轮**（迟到启动兜底）：重试轮本来就是
     * 窗口过后用户手动补的，若也吃这条闸，点重试时多半已过 opensAt+90s——一发都
     * 不会发就收工。重试轮的边界由「5 发 / 30 秒」与平台明确话术（已满 / 已下架）兜着。
     */
    private fun stopNow(
        attempts: Int,
        startedAt: Long,
        phoneNow: Long,
        opensAt: Long,
        maxAttempts: Int,
        runBudgetMs: Long,
        retry: Boolean,
    ): Boolean = attempts >= maxAttempts ||
        phoneNow - startedAt >= runBudgetMs ||
        (!retry && phoneNow - WenquServerClock.offsetMs >= opensAt + WenquRules.PREREG_END_AFTER_MS)

    /** 校对发现活动不可继续时的收工（取消 / 结束 / 满员）：作废 + 说明。 */
    private suspend fun abort(
        context: Context,
        prefs: DisplayPrefsStore,
        id: Long,
        dashState: WenquPreregDashState,
        reason: String,
        startedAt: Long,
    ): Outcome {
        clearIfCurrent(prefs, id)
        finish(
            context, prefs, id, dashState.copy(
                phase = WenquPreregDashState.Phase.Done, success = false, resultText = reason,
            ),
        )
        WenquPreregReminder.postResult(context, "预报名未执行：${dashState.title}", "$reason，本轮已取消。")
        return Outcome(Outcome.Kind.Failure, 0, System.currentTimeMillis() - startedAt)
    }

    /** 用户中途取消（落盘被清）的静默收尾：面板置「已取消」，不发结果通知。 */
    private suspend fun skipped(context: Context, prefs: DisplayPrefsStore, id: Long): Outcome {
        clearIfCurrent(prefs, id)
        finish(
            context, prefs, id, _dash.value.copy(
                phase = WenquPreregDashState.Phase.Done, success = null,
                resultText = "你取消了这次自动抢。若取消前那一发已在平台生效，活动详情页会显示「已报名」。",
            ),
        )
        return Outcome(Outcome.Kind.Skipped, _dash.value.attempts, 0L)
    }

    /** 终态落进仪表板后延迟归零：结果页展示一会儿，面板自己关掉后状态回收。 */
    private fun finish(context: Context, prefs: DisplayPrefsStore, id: Long, state: WenquPreregDashState) {
        _dash.value = state
        Graph.appScope.launch {
            delay(DASH_RESULT_TTL_MS)
            // 期间用户又排了新的一轮（state 被覆盖成非 Done / 换了 id）就不动
            val cur = _dash.value
            if (cur.id == id && cur.phase == WenquPreregDashState.Phase.Done) {
                _dash.value = WenquPreregDashState()
            }
        }
    }

    /** 终态在面板上保留的时长（此后自动回 Idle；用户手动关掉面板不算「自动弹」窗口已用尽）。 */
    private const val DASH_RESULT_TTL_MS = 5 * 60_000L

    /** 落盘里还是这一条吗（用户中途取消 / 换了目标就该收手）。 */
    private suspend fun stillArmed(prefs: DisplayPrefsStore, id: Long): Boolean =
        prefs.wenquPreregId.first() == id

    /** 只清自己这一轮（用户又排了新的就别动）。 */
    private suspend fun clearIfCurrent(prefs: DisplayPrefsStore, id: Long) {
        if (prefs.wenquPreregId.first() == id) prefs.clearWenquPrereg()
    }
}
