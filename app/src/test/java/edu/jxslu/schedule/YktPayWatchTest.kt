package edu.jxslu.schedule

import edu.jxslu.schedule.domain.YktPayWatch
import edu.jxslu.schedule.domain.YktTurnoverRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 付款码页的扫码消费检测（DESIGN §3.10）：水位之前的不算、收入不算、命中后水位前移。
 *
 * 回归点 = 判定用服务端交易时间做水位，不掺设备时钟（§4.19「充值」到账判定同款口径）。
 */
class YktPayWatchTest {

    private fun expense(orderId: String, epochMs: Long, fen: Long) = YktTurnoverRow(
        orderId = orderId,
        epochMs = epochMs,
        timeText = "2026-09-22 12:31:04",
        amountFen = fen,
        income = false,
        typeText = "消费",
        locationName = "第一食堂",
        balanceAfterFen = 8_820,
    )

    @Test
    fun `未建立水位时不判定`() {
        val watch = YktPayWatch()
        assertTrue(!watch.established)
        assertNull(watch.inspect(listOf(expense("PO-1", 1_000L, 1_250L))))
    }

    @Test
    fun `水位之后的扣款命中`() {
        val watch = YktPayWatch()
        watch.establish(1_000L)
        val hit = watch.inspect(listOf(expense("PO-9", 2_000L, 1_250L)))
        assertEquals(1_250L, hit?.amountFen)
        assertEquals("第一食堂", hit?.merchant)
        assertEquals("2026-09-22 12:31:04", hit?.timeText)
        assertEquals(8_820L, hit?.balanceAfterFen)
        assertEquals("PO-9", hit?.orderId)
    }

    @Test
    fun `水位之前的记录不算这笔消费`() {
        val watch = YktPayWatch()
        watch.establish(5_000L)
        assertNull(watch.inspect(listOf(expense("PO-1", 1_000L, 1_250L))))
    }

    @Test
    fun `收入不算消费`() {
        val watch = YktPayWatch()
        watch.establish(1_000L)
        val income = expense("PO-2", 9_000L, 5_000L).copy(income = true)
        assertNull(watch.inspect(listOf(income)))
    }

    @Test
    fun `同一批里取最新的那笔`() {
        val watch = YktPayWatch()
        watch.establish(1_000L)
        val hit = watch.inspect(
            listOf(
                expense("PO-2", 2_000L, 300L),
                expense("PO-3", 4_000L, 700L),
                expense("PO-4", 3_000L, 500L),
            ),
        )
        assertEquals("PO-3", hit?.orderId)
        assertEquals(700L, hit?.amountFen)
    }

    @Test
    fun `命中后同一批再判不重复命中`() {
        val watch = YktPayWatch()
        watch.establish(1_000L)
        val batch = listOf(expense("PO-2", 2_000L, 300L))
        assertEquals("PO-2", watch.inspect(batch)?.orderId)
        assertNull(watch.inspect(batch))
    }

    @Test
    fun `空库建立水位后任何扣款都算这笔消费`() {
        val watch = YktPayWatch()
        watch.establish(null)
        assertEquals("PO-1", watch.inspect(listOf(expense("PO-1", 1_000L, 1_250L)))?.orderId)
    }
}
