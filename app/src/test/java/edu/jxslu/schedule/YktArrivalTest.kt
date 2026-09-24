package edu.jxslu.schedule

import edu.jxslu.schedule.domain.YktArrival
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 充值到账的余额口径（DESIGN §4.19「充值」）：只认付款那张卡自己的增长。
 *
 * 回归点 = 2026-09-22 用户反馈「钱已到账却一直弹正在确认」的两个成因：
 * 按总额（全部卡求和）比对会被别的卡的入账误判；基线被「现在的余额」顶掉，
 * 判定门槛抬到自己头上，永远不成立。
 */
class YktArrivalTest {

    private val account = "240001"

    @Test
    fun `同一张卡余额涨满订单金额即到账`() {
        assertEquals(
            12_500L,
            YktArrival.balanceArrival(account, 10_000L, 2_500L, mapOf(account to 12_500L)),
        )
    }

    @Test
    fun `涨得比订单多也算到账`() {
        assertEquals(
            20_000L,
            YktArrival.balanceArrival(account, 10_000L, 2_500L, mapOf(account to 20_000L)),
        )
    }

    @Test
    fun `余额没动不到账`() {
        assertNull(YktArrival.balanceArrival(account, 10_000L, 2_500L, mapOf(account to 10_000L)))
    }

    @Test
    fun `余额涨了但不够订单金额不到账`() {
        assertNull(YktArrival.balanceArrival(account, 10_000L, 2_500L, mapOf(account to 10_500L)))
    }

    @Test
    fun `别的卡进钱不影响本卡判定`() {
        assertNull(
            YktArrival.balanceArrival(
                account,
                10_000L,
                2_500L,
                mapOf("240002" to 99_900L, account to 10_000L),
            ),
        )
    }

    @Test
    fun `卡号对不上但只有一张卡时按唯一余额判定`() {
        assertEquals(
            12_500L,
            YktArrival.balanceArrival(account, 10_000L, 2_500L, mapOf("999999" to 12_500L)),
        )
    }

    @Test
    fun `卡号对不上且有多张卡时不判定`() {
        assertNull(
            YktArrival.balanceArrival(
                account,
                10_000L,
                2_500L,
                mapOf("999998" to 12_500L, "999999" to 12_500L),
            ),
        )
    }

    @Test
    fun `卡号缺失时不判定`() {
        assertNull(YktArrival.balanceArrival(null, 10_000L, 2_500L, mapOf(account to 20_000L)))
    }

    @Test
    fun `基线缺失时不判定（旧记录退流水口径）`() {
        assertNull(YktArrival.balanceArrival(account, null, 2_500L, mapOf(account to 20_000L)))
    }

    @Test
    fun `订单金额为 0 时不判定`() {
        assertNull(YktArrival.balanceArrival(account, 10_000L, 0L, mapOf(account to 20_000L)))
    }

    // ---- 电子账户（钱包）口径（2026-09-24 补，YktArrival.walletArrival） ----

    @Test
    fun `钱包余额涨满订单金额即到账`() {
        assertEquals(
            15_00L,
            YktArrival.walletArrival(balanceBeforeFen = 1_400L, orderFen = 100L, currentFen = 15_00L),
        )
    }

    @Test
    fun `钱包余额没动不到账`() {
        assertNull(YktArrival.walletArrival(balanceBeforeFen = 1_400L, orderFen = 100L, currentFen = 1_400L))
    }

    @Test
    fun `钱包余额基线缺失不判定（旧记录退流水口径）`() {
        assertNull(YktArrival.walletArrival(balanceBeforeFen = null, orderFen = 100L, currentFen = 15_00L))
    }

    @Test
    fun `钱包当前余额缺失不判定`() {
        assertNull(YktArrival.walletArrival(balanceBeforeFen = 1_400L, orderFen = 100L, currentFen = null))
    }
}
