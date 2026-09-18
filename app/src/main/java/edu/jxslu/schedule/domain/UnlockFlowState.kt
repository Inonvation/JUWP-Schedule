package edu.jxslu.schedule.domain

import kotlinx.serialization.Serializable

/**
 * 开水流程的领域结果与状态机（DESIGN §4.10）。
 * UnlockResult 是业务结果而非线上 DTO（线上 DTO 在 data/qiekj/QiekjModels.kt），
 * 状态机供 ViewModel 驱动 UI 原地切换：Idle → PreChecking → Working → Success/Failed。
 */

/** 单条优惠明细：4=小票抵扣、8=积分抵扣，其余为其他优惠（与参考实现 promotionType 语义一致）。 */
@Serializable
data class PromotionLine(
    val promotionType: Int? = null,
    val discountAmount: String? = null,
)

/** 一次成功开水的结算结果。金额字段保留服务端字符串原样（可能为 "-"），展示层再解释。 */
@Serializable
data class UnlockResult(
    val orderNo: String,
    val orderId: String,
    val originPrice: String,
    val ticketCost: String,
    val integralCost: String,
    val otherPromotions: List<PromotionLine> = emptyList(),
    val completedAt: Long,
)

/** 错误诊断结果：主因给一行文案，建议列表进「查看详情」弹窗。 */
data class DiagnosisResult(
    val primaryReason: String,
    val rawError: String,
    val step: String,
    val suggestions: List<String> = emptyList(),
)

sealed interface UnlockFlowState {
    data object Idle : UnlockFlowState

    /** 出水前的准备步骤（取 SKU / 风控 / 开后付…），step 文案直接来自仓库层 onStep 回调。 */
    data class PreChecking(val step: String = "正在准备…") : UnlockFlowState

    /** 已在出水：UI 层显示 165s 自动结算倒计时（服务端超时自动关阀）。 */
    data class Working(val step: String, val elapsedSeconds: Int = 0) : UnlockFlowState

    data class Success(val result: UnlockResult) : UnlockFlowState

    data class Failed(
        val message: String,
        val step: String,
        val rawError: String,
        val suggestions: List<String> = emptyList(),
    ) : UnlockFlowState
}

/**
 * 实付金额 = 原价 - 积分抵扣 - 小票抵扣 - 其他优惠，夹到 0。
 * 根因：金额用 Double 会出 0.12-0.11=0.0099… 的浮点误差，再 %.2f 舍入可能错成 0.00；
 * 参考实现 MoneyUtils 用 BigDecimal 精确运算，这里保持一致。
 */
fun calculateActualCost(result: UnlockResult): String {
    val origin = result.originPrice.toBigDecimalOrNull()
    if (origin == null) return result.originPrice
    val integral = result.integralCost.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
    val ticket = result.ticketCost.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
    val other = result.otherPromotions
        .mapNotNull { it.discountAmount?.toBigDecimalOrNull() }
        .fold(java.math.BigDecimal.ZERO) { acc, value -> acc.add(value) }
    return origin.subtract(integral).subtract(ticket).subtract(other)
        .coerceAtLeast(java.math.BigDecimal.ZERO)
        .setScale(2, java.math.RoundingMode.HALF_UP)
        .toPlainString()
}
