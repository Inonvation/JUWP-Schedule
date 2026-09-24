package edu.jxslu.schedule.data.power

import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缴费账单的月度聚合（DESIGN §3.13「缴费账单页」）。
 *
 * 这一层是「账单」的全部口径：月份归属、充值/退款分列、月切换窗口。UI 只负责画，
 * 所以这几条断言就是账单数字的正确性保证。
 */
class PowerBillTest {

    private fun row(
        id: Long,
        date: String,
        amountYuan: Double,
        feerange: String? = null,
        refund: Boolean = false,
    ) = PowerTurnover(
        turnoverId = id,
        dateText = date,
        epochMs = PowerModels.parseTimeMs(date),
        month = feerange,
        amountFen = PowerModels.fen(amountYuan),
        room = "校区-江西水利电力大学;楼栋-9A;房间-9A101",
        refund = refund,
    )

    @Test
    fun `月份按缴费日期归，不按费用所属月`() {
        // 9 月 1 日交 8 月的费：用户问的是「这个月花了多少」，所以算 9 月
        val paidInSeptember = row(1, "2026-09-01 09:30:00", 50.0, feerange = "202608")
        assertEquals("2026-09", PowerBill.monthKeyOf(paidInSeptember))
    }

    @Test
    fun `日期解析不出来才退回 feerange，两者都没有归未知月`() {
        val brokenDate = row(2, "不是时间", 20.0, feerange = "202608")
        assertEquals(0L, brokenDate.epochMs)
        assertEquals("2026-08", PowerBill.monthKeyOf(brokenDate))

        val nothing = row(3, "", 20.0)
        assertEquals(PowerBill.UNKNOWN_MONTH, PowerBill.monthKeyOf(nothing))
    }

    @Test
    fun `按月聚合分列充值退款，未知月排最后`() {
        val rows = listOf(
            row(1, "2026-08-25 12:20:23", 20.0),
            row(2, "2026-08-26 10:00:00", 50.0),
            row(3, "2026-08-27 10:00:00", 20.0, refund = true),
            row(4, "2026-07-03 13:08:57", 30.0),
            row(5, "", 5.0),
        )
        val months = PowerBill.monthsOf(rows)
        assertEquals(listOf("2026-08", "2026-07", PowerBill.UNKNOWN_MONTH), months.map { it.key })

        val august = months.first()
        assertEquals(PowerModels.fen(70.0), august.rechargeFen)
        assertEquals(PowerModels.fen(20.0), august.refundFen)
        assertEquals(3, august.count)

        val july = months[1]
        assertEquals(PowerModels.fen(30.0), july.rechargeFen)
        assertEquals(0L, july.refundFen)
    }

    @Test
    fun `明细按时间倒序，同刻按 id 降序`() {
        val rows = listOf(
            row(1, "2026-08-25 12:20:23", 20.0),
            row(3, "2026-08-26 09:00:00", 10.0),
            row(2, "2026-08-26 09:00:00", 30.0),
            row(4, "2026-07-03 13:08:57", 30.0),
        )
        val august = PowerBill.rowsOf(rows, "2026-08")
        assertEquals(listOf(3L, 2L, 1L), august.map { it.turnoverId })
        // 别的月不混进来
        assertTrue(PowerBill.rowsOf(rows, "2026-09").isEmpty())
    }

    @Test
    fun `月窗口跨年且含当月`() {
        val keys = PowerBill.recentMonthKeys(YearMonth.of(2026, 1), count = 3)
        assertEquals(listOf("2025-11", "2025-12", "2026-01"), keys)
    }

    @Test
    fun `月份标签与金额文案`() {
        assertEquals("2026 年 8 月", PowerBill.monthLabel("2026-08"))
        assertEquals("2026 年 12 月", PowerBill.monthLabel("2026-12"))
        assertEquals("月份未知", PowerBill.monthLabel(PowerBill.UNKNOWN_MONTH))
        // 认不出的键原样回显，不抛异常
        assertEquals("乱码", PowerBill.monthLabel("乱码"))

        assertEquals("¥20.00", PowerBill.amountText(2000))
        assertEquals("¥0.00", PowerBill.amountText(0))
        assertEquals("8", PowerBill.shortMonthLabel("2026-08"))
        assertFalse(PowerBill.shortMonthLabel("2026-12").startsWith("0"))
    }
}
