package edu.jxslu.schedule.data.repo

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 抢课会话的进程级状态（DESIGN §4.36）：前台服务写、抢课面板读。
 *
 * **只活在内存里**（不落盘）：会话是「当下这一刻」的事——进程没了，前台服务也跟着没了，
 * 没有可恢复的东西。历史日志同理（要看长期记录就去看选课结果）。
 */
object SelectionGrabStore {

    /** 日志最多保留条数（新的在前）。 */
    const val LOG_LIMIT = 40

    data class State(
        val running: Boolean = false,
        val roundId: String = "",
        val roundName: String = "",
        /** 本轮清单条数。 */
        val targetCount: Int = 0,
        /** 已抢到门数。 */
        val grabbedCount: Int = 0,
        /** 最近日志（新在前）。 */
        val log: List<String> = emptyList(),
        /** 最近一次收场的文案（null = 本进程还没跑过）。 */
        val lastStop: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun begin(roundId: String, roundName: String, targetCount: Int) {
        _state.update {
            it.copy(
                running = true,
                roundId = roundId,
                roundName = roundName,
                targetCount = targetCount,
                grabbedCount = 0,
                lastStop = null,
            )
        }
        log("开始抢课：$roundName，目标 $targetCount 条")
    }

    fun setGrabbed(count: Int) {
        _state.update { it.copy(grabbedCount = count) }
    }

    fun finish(stopText: String) {
        _state.update { it.copy(running = false, lastStop = stopText) }
        log("已结束：$stopText")
    }

    /** 追加一条日志（自动加 `HH:mm:ss` 前缀，裁剪到 [LOG_LIMIT] 条）。 */
    fun log(line: String) {
        val stamped = "${TIME_FORMAT.format(LocalTime.now())} $line"
        _state.update { it.copy(log = (listOf(stamped) + it.log).take(LOG_LIMIT)) }
    }

    /** 清空日志与最近收场（面板上的「清空」）。 */
    fun clearLog() {
        _state.update { it.copy(log = emptyList(), lastStop = null) }
    }

    private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
}
