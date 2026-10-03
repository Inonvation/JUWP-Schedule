package edu.jxslu.schedule.data.ujing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * U净 扫码下单的**进程内草稿**（DESIGN §4.37；2026-10-02 起「扫码 → 独立下单页」流程用）。
 *
 * 二级页是独立 Activity 窗口（`SubpageActivity.start`），主页（UJING）与下单页
 * （UJING_ORDER）各有各的 ViewModel 实例，扫码结果与所选档位要跨窗口交接：
 * 这里是 Graph 保证单进程一份的中转站——
 *
 * - [open]：主页扫码成功后开草稿，下单页读 [draft] 渲染设备与套餐；
 * - [submit]：下单页确认后交出 [OrderSelection]，窗口滑出回主页；
 * - 主页取走提交（[consumeSubmitted]）后由**主页自己的 ViewModel** 走既有的
 *   「下单 → 支付宝拉起 → 回查」链路——写操作单点发起，不因窗口切换换执行者；
 * - [close]：下单受理（或流程放弃）后清场。
 *
 * 故意**不落盘**：草稿是几秒内的一次性交接，进程被回收就丢，重扫一次即可；
 * 真正必须跨进程的订单号在 `UjingOrderStore`（社区实测教训，见其类 KDoc）。
 */
class UjingDraftStore {

    /** 一次扫码识别的完整结果（设备 + 套餐），下单页渲染的唯一数据源。 */
    data class Draft(
        val scan: UjingScanResult,
        val program: UjingProgramData,
    )

    /** 下单页交出的选择：模式（档位）+ 水温 + 投放加购。 */
    data class OrderSelection(
        val model: UjingWashModel,
        /** 水温档 id（null = 机型未开放水温 / 烘干机，下单 body 不带）。 */
        val temperatureId: Int?,
        val addDetergent: Boolean,
        val addDisinfectant: Boolean,
    )

    private val _draft = MutableStateFlow<Draft?>(null)
    val draft: StateFlow<Draft?> = _draft.asStateFlow()

    private val _submitted = MutableStateFlow<OrderSelection?>(null)
    val submitted: StateFlow<OrderSelection?> = _submitted.asStateFlow()

    /** 扫码成功：开一份新草稿（覆盖上一份），清掉未消费的旧提交。 */
    fun open(draft: Draft) {
        _draft.value = draft
        _submitted.value = null
    }

    /** 下单页点「去支付」（确认弹层里）：交出选择，随后窗口关闭回主页。 */
    fun submit(selection: OrderSelection) {
        _submitted.value = selection
    }

    /** 主页取走提交（取走即置空，防重复消费）。 */
    fun consumeSubmitted(): OrderSelection? = _submitted.value.also { _submitted.value = null }

    /** 下单已受理（订单进在案快照）或用户明确放弃流程后清场。 */
    fun close() {
        _draft.value = null
        _submitted.value = null
    }
}
