package edu.jxslu.schedule.domain

/**
 * 一笔付款码消费（DESIGN §3.10「扫码后自动退出」）。数据来自消费流水里的扣款记录。
 *
 * [orderId] 是服务端订单号，用于去重；其余字段只作展示。
 */
data class YktPayment(
    /** 扣款金额（分）。 */
    val amountFen: Long,
    /** 商户/地点名；流水里可能为空。 */
    val merchant: String?,
    /** 交易时间原文（服务端原样，如 `2026-09-22 12:31:04`）。 */
    val timeText: String,
    /** 交易后余额（分）；流水缺失为 null。 */
    val balanceAfterFen: Long?,
    /** 类型名（消费 / 二维码支付…）。 */
    val typeText: String,
    val orderId: String,
)

/** 到账检测用的一条流水（只保留判定与展示要用的字段）。 */
data class YktTurnoverRow(
    val orderId: String,
    /** 交易时间（epoch 毫秒，服务端时间解析而来；解析失败为 0）。 */
    val epochMs: Long,
    val timeText: String,
    val amountFen: Long,
    /** true = 收入（充值/退款），不算消费。 */
    val income: Boolean,
    val typeText: String,
    val locationName: String?,
    val balanceAfterFen: Long?,
)

/**
 * 付款码页的「扫码后自动退出」检测（纯逻辑，DESIGN §3.10）。
 *
 * 口径：进页后先**建立水位**（本轮同步完成后本地库里最新一条流水的时间，这一轮不判定），
 * 之后出现晚于水位的**支出**记录即命中。水位取服务端交易时间，不掺设备时钟：
 * 设备时间与服务端有偏差时，「晚于进页时刻」这种判法会漏判或把历史流水误判成刚付的
 * （§4.19「充值」的到账判定踩过同一个坑，2026-09-22 修）。
 *
 * 水位建立失败（那一轮同步没成功）就不判定，宁可不出提示，也不拿历史流水报假账。
 */
class YktPayWatch {

    /** 水位（epoch 毫秒）；null = 尚未建立。 */
    var watermark: Long? = null
        private set

    val established: Boolean get() = watermark != null

    /**
     * 建立水位。**只在一轮成功同步之后调用**：[latestKnownEpochMs] 传库里最新一条流水的
     * 时间，那一轮的记录全部算「进页前就有」。空库传 null，等价于水位 0。
     */
    fun establish(latestKnownEpochMs: Long?) {
        watermark = latestKnownEpochMs ?: 0L
    }

    /**
     * 判定一批新入库的流水；命中则把水位推到这笔交易并返回它。
     * 未建立水位恒返回 null；收入（充值/退款）不算消费。
     */
    fun inspect(rows: List<YktTurnoverRow>): YktPayment? {
        val base = watermark ?: return null
        val hit = rows.filter { !it.income && it.epochMs > base }.maxByOrNull { it.epochMs } ?: return null
        watermark = hit.epochMs
        return YktPayment(
            amountFen = hit.amountFen,
            merchant = hit.locationName,
            timeText = hit.timeText,
            balanceAfterFen = hit.balanceAfterFen,
            typeText = hit.typeText,
            orderId = hit.orderId,
        )
    }
}
