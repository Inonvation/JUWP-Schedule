package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 生活页流水混排（DESIGN §3.13）：时间倒序、稳定、限量。 */
class LifeFeedTest {

    private fun ykt(ms: Long, title: String = "消费") = LifeFeedItem(
        kind = LifeFeedKind.CampusCard,
        epochMs = ms,
        timeText = "t$ms",
        title = title,
        subtitle = "一卡通",
        amountFen = -100,
        income = false,
    )

    private fun power(ms: Long, title: String = "电费充值") = LifeFeedItem(
        kind = LifeFeedKind.Power,
        epochMs = ms,
        timeText = "t$ms",
        title = title,
        subtitle = "9A101",
        amountFen = 2000,
        income = true,
    )

    @Test
    fun mergesBothSourcesByTimeDescending() {
        val merged = LifeFeed.merge(
            campusCard = listOf(ykt(100), ykt(300)),
            power = listOf(power(200)),
        )
        assertEquals(listOf(300L, 200L, 100L), merged.map { it.epochMs })
        assertEquals(LifeFeedKind.Power, merged[1].kind)
    }

    @Test
    fun keepsLimitAndDropsOldest() {
        val merged = LifeFeed.merge(
            campusCard = (1..6).map { ykt(it * 10L) },
            power = listOf(power(999)),
            limit = 3,
        )
        assertEquals(3, merged.size)
        assertEquals(listOf(999L, 60L, 50L), merged.map { it.epochMs })
    }

    /** 同一批数据每次进页顺序一致：同刻记录保持入参顺序（一卡通在前）。 */
    @Test
    fun equalTimestampsKeepStableOrder() {
        val merged = LifeFeed.merge(
            campusCard = listOf(ykt(500, "一卡通条目")),
            power = listOf(power(500, "电费条目")),
        )
        assertEquals(listOf("一卡通条目", "电费条目"), merged.map { it.title })
    }

    /** 时间解析不出来的记录（epochMs = 0）沉底，不会被顶到最上面。 */
    @Test
    fun unparsableTimeSinks() {
        val merged = LifeFeed.merge(
            campusCard = listOf(ykt(0, "老数据")),
            power = listOf(power(10, "新充值")),
        )
        assertTrue(merged.first().title == "新充值")
        assertEquals("老数据", merged.last().title)
    }

    @Test
    fun zeroLimitYieldsEmpty() {
        assertTrue(LifeFeed.merge(listOf(ykt(1)), listOf(power(2)), limit = 0).isEmpty())
    }
}
