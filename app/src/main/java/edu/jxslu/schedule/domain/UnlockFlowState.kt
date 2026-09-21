package edu.jxslu.schedule.domain

import kotlinx.serialization.Serializable

/**
 * 开水流程的领域结果与状态机（DESIGN §4.10）。
 * UnlockResult 是业务结果而非线上 DTO（线上 DTO 在 data/qiekj/QiekjModels.kt），
 * 状态机供 ViewModel 驱动 UI 原地切换：Idle → PreChecking → Working → Success/Failed。
 */

/** 单条优惠明细：4=小票支付、8=积分抵扣，其余为其他优惠（与参考实现 promotionType 语义一致）。 */
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
    /** 服务端**现金**实付口径（在线支付；小票/积分全额支付时为 "0.00"）；null 时回退本地公式。 */
    val realPrice: String? = null,
    /** 支付方式名（如「支付宝-代扣」）。 */
    val payTypeName: String? = null,
    /** 小票（tokenCoin 余额）抵扣金额；与 promotionList type=4 同源，作 ticketCost 的回退。 */
    val tokenCoinDiscount: String? = null,
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
 * 小票支付金额（DESIGN §4.10「账单口径」，2026-09-21 修订）。
 * 小票（tokenCoin）是账户余额，优先取 promotionList type=4 的抵扣额，缺失时回退
 * 服务端 [UnlockResult.tokenCoinDiscount]；两者都没有返回 null（无法解析不猜）。
 */
fun ticketPaidAmount(result: UnlockResult): java.math.BigDecimal? =
    result.ticketCost.toBigDecimalOrNull()
        ?: result.tokenCoinDiscount?.toBigDecimalOrNull()

/**
 * 现金实付金额（在线支付口径）。服务端 `realPrice`（即 `payPrice`）是权威来源；
 * 缺失/不可解析时回退「原价 - 小票 - 积分 - 其他优惠」，夹到 0；原价也不可解析返回 null。
 */
fun cashPaidAmount(result: UnlockResult): java.math.BigDecimal? {
    result.realPrice?.toBigDecimalOrNull()?.let { return it }
    val origin = result.originPrice.toBigDecimalOrNull() ?: return null
    val integral = result.integralCost.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
    val ticket = ticketPaidAmount(result) ?: java.math.BigDecimal.ZERO
    val other = result.otherPromotions
        .mapNotNull { it.discountAmount?.toBigDecimalOrNull() }
        .fold(java.math.BigDecimal.ZERO) { acc, value -> acc.add(value) }
    return origin.subtract(integral).subtract(ticket).subtract(other)
        .coerceAtLeast(java.math.BigDecimal.ZERO)
}

/**
 * 本次花费口径（DESIGN §4.10「账单口径」，2026-09-21 修订）：
 * 用户为这单实际付出的钱 = 小票支付 + 现金实付。
 * 历史教训（2026-09-20）：曾把 `tokenCoinDiscount` 读成「平台自动优惠」，直接以服务端
 * `realPrice`（现金口径，小票全额支付时为 0.00）当花费展示，于是每天每单都显示
 * 「实付 ¥0.00」——而用户的小票余额实际被扣了钱。积分抵扣与其他优惠是省钱项，不计入花费。
 * 金额一律 BigDecimal 精确运算（0.12-0.11 用 Double 得 0.0099… 再舍入会错成 0.00）。
 */
fun calculateActualCost(result: UnlockResult): String {
    val ticket = ticketPaidAmount(result) ?: java.math.BigDecimal.ZERO
    val cash = cashPaidAmount(result) ?: return result.originPrice
    return cash.add(ticket)
        .setScale(2, java.math.RoundingMode.HALF_UP)
        .toPlainString()
}
