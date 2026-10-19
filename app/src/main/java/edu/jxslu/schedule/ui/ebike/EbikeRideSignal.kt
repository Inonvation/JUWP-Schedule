package edu.jxslu.schedule.ui.ebike

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 「订单已在别处结束」的进程内信号（2026-10-19）。
 *
 * 自动还车在 Worker 里结束订单，它拿不到当前页面的 `KvcxRideController`；只清 DataStore
 * 的计时起点会出现「车辆卡片还在、只是时长条没了」。这个信号是页面状态与后台结果的
 * 交接点：Worker 结束订单后发一次，活着的骑行页 / 快趣账号页把它翻译成「ride = null」。
 *
 * 只在同一进程内有效：页面没开时没有订阅者，下次进页本来就会查一次在案订单；不做持久化，
 * 避免下一轮骑行读到上一轮的结束事件。
 */
internal object EbikeRideSignal {

    private val _ended = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** 文本非空时页面补一条 Snackbar；手动还车传空串（页面自己会出结果卡）。 */
    val ended: SharedFlow<String> = _ended.asSharedFlow()

    fun notifyEnded(notice: String = "") {
        _ended.tryEmit(notice)
    }
}
