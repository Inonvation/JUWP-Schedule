package edu.jxslu.schedule.data.repo

import edu.jxslu.schedule.domain.GrabState
import edu.jxslu.schedule.domain.GrabStop
import edu.jxslu.schedule.domain.SelectionCourse
import edu.jxslu.schedule.domain.SelectionGrabPolicy
import edu.jxslu.schedule.domain.SelectionWish
import edu.jxslu.schedule.domain.SelectionWishes
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** 一次抢课会话的输入（DESIGN §4.36）：轮次 + 预选清单。 */
data class GrabRequest(
    val roundId: String,
    val roundName: String,
    /** 轮次截止时刻（null = 未知，只用 [SelectionGrabPolicy.MAX_SESSION_MS] 兜底）。 */
    val roundEndAt: Long?,
    /** 预选清单；调用方先按优先级排好（`SelectionWishes.sorted`）。 */
    val wishes: List<SelectionWish>,
)

/**
 * 抢课引擎（DESIGN §4.36，2026-10-01 规划）。
 *
 * 一轮会话 = 循环 { 拉课程列表 → 按清单匹配 → 逐个提交 → 等 [intervalMs] 再下一轮 }，
 * 直到 [SelectionGrabPolicy.shouldStop] 给出收场理由。三条纪律（红线，见 §4.36）：
 * **单线程顺序提交**（不并发）、**失败线性退避**、**连续失败到阈值自动停止**。
 *
 * 事件流交给调用方（前台服务写通知 + [SelectionGrabStore] 记日志）；用户停止 = 取消收集
 * （流内不做「用户停止」判定）。
 *
 * 两种「失败」的口径不同（这是本引擎最容易搞混的地方）：
 * - **拉取/传输失败**：计连续失败（链路不健康，退避后重试，到阈值收场）；
 * - **提交被拒**（名额满 / 时间冲突）：**不计**连续失败——名额满是抢课期的常态，
 *   该继续轮询；只发 [Event.Failed] 让用户看见。真正的兜底是轮次截止与时长上限。
 *
 * 真实接口语义（返回码、限流）要等窗口期实测，届时只改 [SelectionCenterClient] 实现与
 * 这里的映射，不动编排。
 */
class SelectionGrabber(
    private val client: SelectionCenterClient,
    private val intervalMs: Long = SelectionGrabPolicy.INTERVAL_DEFAULT_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {

    sealed interface Event {
        /** 第 [attempt] 轮检查开始（列表拉取前）。 */
        data class Checking(val attempt: Int) : Event

        /** 已提交并成功抢到 [course]（对应清单条目 [wishId]）。 */
        data class Grabbed(val course: SelectionCourse, val wishId: String) : Event

        /** 一次提交被拒（名额满/冲突等）或传输异常，下轮还会再试。 */
        data class Failed(val courseName: String, val reason: String) : Event

        data class Stopped(
            val stop: GrabStop,
            val grabbed: List<SelectionCourse>,
            val elapsedMs: Long,
        ) : Event
    }

    fun run(request: GrabRequest): Flow<Event> = flow {
        val pending = request.wishes.associateBy { it.id }.toMutableMap()
        val grabbed = mutableListOf<SelectionCourse>()
        val startedAt = clock()
        var failures = 0
        var attempt = 0

        fun stopEvent(stop: GrabStop) = Event.Stopped(stop, grabbed.toList(), clock() - startedAt)

        while (true) {
            SelectionGrabPolicy.shouldStop(
                GrabState(
                    startedAt = startedAt,
                    consecutiveFailures = failures,
                    pendingWishIds = pending.keys.toSet(),
                    roundEndAt = request.roundEndAt,
                ),
                clock(),
            )?.let {
                emit(stopEvent(it))
                return@flow
            }

            attempt++
            emit(Event.Checking(attempt))

            val courses = try {
                client.fetchCourses(request.roundId)
            } catch (e: SelectionClientException) {
                if (e.fatal) {
                    emit(stopEvent(GrabStop.Fatal(e.message ?: "选课接口不可用")))
                    return@flow
                }
                failures++
                backOffAfterFailure(failures)
                continue
            } catch (e: Exception) {
                failures++
                backOffAfterFailure(failures)
                continue
            }
            // 拉取成功 = 链路健康，退避重置
            failures = 0

            var fatal: GrabStop.Fatal? = null
            for ((wishId, wish) in pending.toList()) {
                val candidate = courses.firstOrNull { course ->
                    !course.selected && SelectionWishes.matches(wish, course.name, course.teacher)
                } ?: continue
                when (val result = submitSafely(request.roundId, candidate.id)) {
                    is SelectionSubmitResult.Success -> {
                        pending.remove(wishId)
                        grabbed += candidate
                        emit(Event.Grabbed(candidate, wishId))
                    }
                    is SelectionSubmitResult.Rejected -> emit(
                        Event.Failed(candidate.name, result.reason),
                    )
                    is SelectionSubmitResult.Fatal -> fatal = GrabStop.Fatal(result.reason)
                }
                if (fatal != null) break
            }
            val fatalStop = fatal
            if (fatalStop != null) {
                emit(stopEvent(fatalStop))
                return@flow
            }
            if (pending.isEmpty()) {
                emit(stopEvent(GrabStop.AllMatched))
                return@flow
            }
            sleep(SelectionGrabPolicy.nextDelay(intervalMs, failures))
        }
    }

    /** 提交兜异常：fatal 透传，其余（网络/解析）算「被拒」，下轮再试。 */
    private suspend fun submitSafely(roundId: String, courseId: String): SelectionSubmitResult = try {
        client.submit(roundId, courseId)
    } catch (e: SelectionClientException) {
        if (e.fatal) {
            SelectionSubmitResult.Fatal(e.message ?: "选课接口不可用")
        } else {
            SelectionSubmitResult.Rejected(e.message ?: "网络异常")
        }
    } catch (e: Exception) {
        SelectionSubmitResult.Rejected("网络异常：${e.message ?: "未知错误"}")
    }

    /**
     * 一次可重试失败后的退避等待：第 1 次 10s、第 2 次 20s…（[SelectionGrabPolicy.nextDelay]）。
     * 已到连续失败阈值就**不等**——回循环顶由 `shouldStop` 收场，不空等最后一轮。
     */
    private suspend fun backOffAfterFailure(failures: Int) {
        if (failures >= SelectionGrabPolicy.MAX_CONSECUTIVE_FAILURES) return
        sleep(SelectionGrabPolicy.nextDelay(intervalMs, failures - 1))
    }
}
