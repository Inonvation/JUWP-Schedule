package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 生活页流水分段（DESIGN §3.13）：按来源分组、段内时间倒序、各自限量。 */
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

    /** 两个来源各回各的段，不再交织。 */
    @Test
    fun splitsBySource() {
        val sections = LifeFeed.sections(
            campusCard = listOf(ykt(100), ykt(300)),
            power = listOf(power(200)),
        )
        assertEquals(listOf(300L, 100L), sections.campusCard.map { it.epochMs })
        assertEquals(listOf(200L), sections.power.map { it.epochMs })
    }

    /** 限量按段算：某一来源条数多，不会把另一来源挤掉。 */
    @Test
    fun limitsPerSectionNotGlobally() {
        val sections = LifeFeed.sections(
            campusCard = (1..6).map { ykt(it * 10L) },
            power = listOf(power(5)),
            limitPerSection = 2,
        )
        assertEquals(2, sections.campusCard.size)
        assertEquals(1, sections.power.size)
        assertEquals(listOf(60L, 50L), sections.campusCard.map { it.epochMs })
    }

    /** 段内按时间倒序，最新一条在最上面。 */
    @Test
    fun sortsDescendingWithinSection() {
        val sections = LifeFeed.sections(
            campusCard = listOf(ykt(100), ykt(300), ykt(200)),
            power = listOf(power(10), power(90)),
            limitPerSection = 3,
        )
        assertEquals(listOf(300L, 200L, 100L), sections.campusCard.map { it.epochMs })
        assertEquals(listOf(90L, 10L), sections.power.map { it.epochMs })
    }

    /** 同一批数据每次进页顺序一致：同刻记录保持入参顺序。 */
    @Test
    fun equalTimestampsKeepStableOrder() {
        val sections = LifeFeed.sections(
            campusCard = listOf(ykt(500, "前"), ykt(500, "后")),
            power = emptyList(),
        )
        assertEquals(listOf("前", "后"), sections.campusCard.map { it.title })
    }

    /** 时间解析不出来的记录（epochMs = 0）沉底，不会被顶到最上面。 */
    @Test
    fun unparsableTimeSinks() {
        val sections = LifeFeed.sections(
            campusCard = listOf(ykt(0, "老数据"), ykt(10, "新消费")),
            power = emptyList(),
        )
        assertEquals("新消费", sections.campusCard.first().title)
        assertEquals("老数据", sections.campusCard.last().title)
    }

    @Test
    fun zeroLimitYieldsEmptySections() {
        val sections = LifeFeed.sections(listOf(ykt(1)), listOf(power(2)), limitPerSection = 0)
        assertTrue(sections.isEmpty)
    }

    /** 只有一段有内容时不算整卡空态。 */
    @Test
    fun oneSectionIsEnoughToBeNonEmpty() {
        assertTrue(LifeFeed.sections(emptyList(), listOf(power(1))).let { !it.isEmpty })
        assertTrue(LifeFeed.sections(emptyList(), emptyList()).isEmpty)
    }
}
