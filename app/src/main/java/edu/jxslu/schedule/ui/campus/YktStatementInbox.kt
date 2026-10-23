package edu.jxslu.schedule.ui.campus

import android.content.Context
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.SubpageScreen
import edu.jxslu.schedule.domain.InboxKind
import edu.jxslu.schedule.domain.InboxKeys
import edu.jxslu.schedule.domain.YktPayment
import edu.jxslu.schedule.domain.YktTurnoverRow

/**
 * 一卡通消费入收件箱的唯一实现（2026-10-23）：扫码命中（`PayCodeViewModel`，吃
 * [YktPayment]）与流水同步新见（`YktTurnoverSyncer.postInbox`，吃 [YktTurnoverRow]，
 * 覆盖食堂 POS 直刷等非付款码场景）两条路径共用，文案与键口径只有这一份。
 *
 * 键 = `ykt|pay|` + 服务端订单号，同一次扣款的重复到达同键落更新；缺的字段各段缺省，
 * 不编数据。落点 = 消费流水页；silent = 静默留痕（插入即已读、重投保持已读，
 * 不点红点）。`post` 内部吞错（旁路纪律），留痕失败不影响调用方主链。
 */
internal suspend fun postYktPaymentInbox(context: Context, row: YktTurnoverRow) =
    postYktPaymentInbox(
        context,
        orderId = row.orderId,
        merchant = row.locationName,
        amountFen = row.amountFen,
        balanceAfterFen = row.balanceAfterFen,
    )

internal suspend fun postYktPaymentInbox(context: Context, payment: YktPayment) =
    postYktPaymentInbox(
        context,
        orderId = payment.orderId,
        merchant = payment.merchant,
        amountFen = payment.amountFen,
        balanceAfterFen = payment.balanceAfterFen,
    )

private suspend fun postYktPaymentInbox(
    context: Context,
    orderId: String,
    merchant: String?,
    amountFen: Long,
    balanceAfterFen: Long?,
) {
    Graph.inboxRepository(context).post(
        kind = InboxKind.YKT_PAYMENT,
        dedupeKey = InboxKeys.YKT_PAYMENT_PREFIX + orderId,
        title = "一卡通消费",
        body = buildString {
            merchant?.takeIf { it.isNotBlank() }?.let { append(it).append(" · ") }
            append("扣款 ¥%.2f".format(amountFen / 100.0))
            balanceAfterFen?.let { append(" · 余额 ¥%.2f".format(it / 100.0)) }
        },
        route = SubpageScreen.CAMPUS_STATEMENT.name,
        silent = true,
    )
}
