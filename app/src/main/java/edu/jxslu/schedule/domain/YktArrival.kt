package edu.jxslu.schedule.domain

/**
 * 充值到账判定（纯逻辑，DESIGN §4.19「充值」）。
 *
 * 余额口径**必须限定在同一张卡**：付款前基线由下单链路（`queryCard?scene=recharge`）
 * 给出，只有这张卡自己的余额涨了 ≥ 订单金额才算到账。按总额（全部卡求和）比对会
 * 把别的卡的入账算进来——2026-09-22 修的就是这条口径。
 *
 * 基线或卡号缺失（升级前落库的旧记录）时本口径**不可用**，返回 null 交给流水口径，
 * 不拿「现在的余额」反推基线：那样在钱已经到账的情况下会把基线设成到账后的余额，
 * 判定永远不成立，界面就卡在「正在确认到账」。
 */
object YktArrival {

    /**
     * [cardBalances] = 卡账户 → 当前卡余额（分）。
     *
     * 返回到账后的卡余额（分）表示已到账；未到账或无法判定返回 null。
     *
     * 卡列表只有一张时允许按唯一余额比对（等值于总额）：下单用的 `scene=recharge`
     * 与检测用的全量 `queryCard` 若对卡号写法有出入，单卡场景也不至于判定整条失效。
     */
    fun balanceArrival(
        account: String?,
        balanceBeforeFen: Long?,
        orderFen: Long,
        cardBalances: Map<String, Long>,
    ): Long? {
        if (account.isNullOrBlank() || balanceBeforeFen == null || orderFen <= 0L) return null
        val now = cardBalances[account] ?: cardBalances.values.singleOrNull() ?: return null
        return if (now >= balanceBeforeFen + orderFen) now else null
    }

    /**
     * 电子账户（钱包）口径：目标 = `accinfo` 的 `<account>-000` 行。
     *
     * 2026-09-24 用户实测：充电子账户后卡余额不动、一直「正在确认到账」——旧逻辑只查
     * 卡余额，钱包进账看不见。电子账户充值**必须走本口径**（[balanceArrival] 的
     * `singleOrNull` 兜底对 `-000` 目标是错的，调用方要跳过）。
     */
    fun walletArrival(
        balanceBeforeFen: Long?,
        orderFen: Long,
        currentFen: Long?,
    ): Long? {
        if (balanceBeforeFen == null || orderFen <= 0L || currentFen == null) return null
        return if (currentFen >= balanceBeforeFen + orderFen) currentFen else null
    }
}
