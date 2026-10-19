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
import kotlinx.coroutines.flow.first
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
 *    （[WenquRules.preregShouldStop]）；结果通知 + 清落盘。
 *
 * 幂等与并发：进程内 [running] + 落盘双闸；闹钟与兜底 Work 同时打进来也只跑一份。
 * 中途被用户取消（落盘被清）：本轮立即收手、不打扰。
 */
object WenquPreregRunner {

    private const val TAG = "WenquPrereg"

    private val running = AtomicBoolean(false)

    /** 执行结果（通知与日志用；UI 不直接消费）。 */
    data class Outcome(val kind: Kind, val attempts: Int, val elapsedMs: Long) {
        enum class Kind { Success, Failure, Rescheduled, Skipped }
    }

    /**
     * 跑一轮预报名。[showProgress] = true 时把进度写进常驻通知（服务那条路）。
     * 可重复调用：没排上 / 已在跑 / 已被取消都是空跑。
     */
    suspend fun run(context: Context, showProgress: Boolean): Outcome {
        val prefs = Graph.displayPrefs(context)
        val id = prefs.wenquPreregId.first()
        val title = prefs.wenquPreregTitle.first()
        val opensAt = prefs.wenquPreregOpensAt.first()
        if (id <= 0L || opensAt <= 0L) return Outcome(Outcome.Kind.Skipped, 0, 0L)
        // 提前太早被拉起（兜底 Work 早跑）：不做，交还给闹钟
        if (System.currentTimeMillis() < WenquRules.preregAlarmAt(opensAt) - 5_000L) {
            return Outcome(Outcome.Kind.Skipped, 0, 0L)
        }
        if (!running.compareAndSet(false, true)) return Outcome(Outcome.Kind.Skipped, 0, 0L)
        try {
            return execute(context, prefs, id, title, opensAt, showProgress)
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
    ): Outcome {
        val repo = Graph.wenqu(context)
        val startedAt = System.currentTimeMillis()
        val progress: (String) -> Unit = { text ->
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
                    0 -> return abort(context, prefs, id, title, "活动已被平台取消", startedAt)
                    2 -> return abort(context, prefs, id, title, "活动已开始，报名已截止", startedAt)
                    3 -> return abort(context, prefs, id, title, "活动已结束", startedAt)
                }
                // 这里**故意不拿快照的 balance_num 判「已满」**：活动还没开放报名时，
                // 平台完全可能把剩余名额写成 0，提前 abort 等于把还能抢的活动直接判死。
                // 到点真发才是权威——第一发若回「名额已满」，下面按终结拒绝收工。
            }
        }

        // 3) 等到试发点（服务器钟口径）
        val tryStartPhone = WenquRules.preregTryStartAt(opensAt, WenquServerClock.offsetMs)
        while (true) {
            val waitMs = tryStartPhone - System.currentTimeMillis()
            if (waitMs <= 0L) break
            if (!stillArmed(prefs, id)) {
                return Outcome(Outcome.Kind.Skipped, 0, System.currentTimeMillis() - startedAt)
            }
            delay(minOf(waitMs, 500L))
        }

        // 4) 试发段：串行单发；结果不明才只读核对；平台硬拒 / 触顶收工
        var attempts = 0
        var lastSoftReason = ""
        var success = false
        var terminalText: String? = null
        while (true) {
            val phoneNow = System.currentTimeMillis()
            if (WenquRules.preregShouldStop(
                    attempts = attempts,
                    startedAt = startedAt,
                    phoneNow = phoneNow,
                    opensAt = opensAt,
                    serverOffsetMs = WenquServerClock.offsetMs,
                )
            ) {
                break
            }
            if (!stillArmed(prefs, id)) {
                return Outcome(Outcome.Kind.Skipped, attempts, phoneNow - startedAt)
            }
            attempts++
            progress("正在提交报名…（第 $attempts 发）")
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

                null -> lastSoftReason = "网络异常"
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

        // 5) 收尾：结果通知 + 清落盘
        val elapsedMs = System.currentTimeMillis() - startedAt
        val serverLateMs = System.currentTimeMillis() - WenquServerClock.offsetMs - opensAt
        return when {
            success -> {
                clearIfCurrent(prefs, id)
                WenquPreregReminder.postResult(
                    context,
                    "报名成功：$title",
                    "已自动提交成功（第 $attempts 发命中）。可在活动详情里查看「已报名」。",
                )
                Log.i(
                    TAG,
                    "run ok attempts=$attempts elapsedMs=$elapsedMs serverLateMs=$serverLateMs " +
                        "offsetMs=${WenquServerClock.offsetMs}",
                )
                Outcome(Outcome.Kind.Success, attempts, elapsedMs)
            }

            terminalText != null -> {
                clearIfCurrent(prefs, id)
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
                    "窗口内没提交上（共试 $attempts 发，$tail），可以手动去看看还有没有名额。"
                }
                WenquPreregReminder.postResult(context, "没抢到：$title", text)
                Log.i(TAG, "run exhausted attempts=$attempts elapsedMs=$elapsedMs serverLateMs=$serverLateMs")
                Outcome(Outcome.Kind.Failure, attempts, elapsedMs)
            }
        }
    }

    /** 校对发现活动不可继续时的收工（取消 / 结束 / 满员）：作废 + 说明。 */
    private suspend fun abort(
        context: Context,
        prefs: DisplayPrefsStore,
        id: Long,
        title: String,
        reason: String,
        startedAt: Long,
    ): Outcome {
        clearIfCurrent(prefs, id)
        WenquPreregReminder.postResult(context, "预报名未执行：$title", "$reason，本轮已取消。")
        return Outcome(Outcome.Kind.Failure, 0, System.currentTimeMillis() - startedAt)
    }

    /** 落盘里还是这一条吗（用户中途取消 / 换了目标就该收手）。 */
    private suspend fun stillArmed(prefs: DisplayPrefsStore, id: Long): Boolean =
        prefs.wenquPreregId.first() == id

    /** 只清自己这一轮（用户又排了新的就别动）。 */
    private suspend fun clearIfCurrent(prefs: DisplayPrefsStore, id: Long) {
        if (prefs.wenquPreregId.first() == id) prefs.clearWenquPrereg()
    }
}
