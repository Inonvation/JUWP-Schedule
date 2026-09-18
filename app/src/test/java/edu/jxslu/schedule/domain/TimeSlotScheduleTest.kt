package edu.jxslu.schedule.domain

import edu.jxslu.schedule.data.DefaultData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 作息表（DESIGN 3.5）与网格几何相关的不变量。
 *
 * 这些断言的作用是：作息一旦被误改（比如手滑把某一节改成 45 分钟），单测会立刻失败，
 * 而不是等到界面上课块错位才发现。
 */
class TimeSlotScheduleTest {

    private val slots = DefaultData.defaultTimeSlots

    /** 作息共 11 小节且编号连续 */
    @Test
    fun slotsAreElevenAndContiguous() {
        assertEquals(11, slots.size)
        assertEquals((1..11).toList(), slots.map { it.number })
    }

    /** 每小节 40 分钟 */
    @Test
    fun everySlotLastsFortyMinutes() {
        slots.forEach { slot ->
            val duration = ScheduleCalculator.toMinutes(slot.endTime) -
                ScheduleCalculator.toMinutes(slot.startTime)
            assertEquals("第 ${slot.number} 节时长不是 40 分钟", 40, duration)
        }
    }

    /**
     * 大节内间隔 5 分钟；大节之间至少 20 分钟。
     * 注意 11:40→14:00（140 分钟）与 17:10→19:00（110 分钟）是午休与晚饭，
     * 不属于「换教室的 20 分钟」，所以这里分别断言而不是统一按 20 分钟。
     */
    @Test
    fun gapsMatchBigSectionBoundaries() {
        val lunchBreak = ScheduleCalculator.toMinutes(slots[4].startTime) -
            ScheduleCalculator.toMinutes(slots[3].endTime)
        val dinnerBreak = ScheduleCalculator.toMinutes(slots[8].startTime) -
            ScheduleCalculator.toMinutes(slots[7].endTime)
        assertEquals("午休", 140, lunchBreak)
        assertEquals("晚饭", 110, dinnerBreak)

        for (i in 0 until slots.size - 1) {
            val gap = ScheduleCalculator.toMinutes(slots[i + 1].startTime) -
                ScheduleCalculator.toMinutes(slots[i].endTime)
            if (ScheduleCalculator.isBigSectionEnd(slots[i].number)) {
                assertTrue("第 ${slots[i].number} 节后（跨大节）至少要有 20 分钟", gap >= 20)
            } else {
                assertEquals("第 ${slots[i].number} 节与大节内下一节之间", 5, gap)
            }
        }
    }

    /** 起点与教务页一致 */
    @Test
    fun anchorsMatchJiaowuPage() {
        assertEquals("08:30", slots.first().startTime)
        assertEquals("21:10", slots.last().endTime)
        assertEquals("10:15", slots[2].startTime)
        assertEquals("14:00", slots[4].startTime)
        assertEquals("15:45", slots[6].startTime)
        assertEquals("19:00", slots[8].startTime)
    }

    /** 大节分组覆盖 1..11 且不重叠 */
    @Test
    fun bigSectionsCoverAllSectionsOnce() {
        val groups = ScheduleCalculator.BIG_SECTIONS
        assertEquals(listOf(1..2, 3..4, 5..6, 7..8, 9..11), groups)
        val flat = groups.flatMap { it.toList() }
        assertEquals((1..11).toList(), flat)
        assertEquals(flat.size, flat.distinct().size)
    }

    /** 大节首末判定 */
    @Test
    fun bigSectionBoundaries() {
        assertTrue(ScheduleCalculator.isBigSectionStart(1))
        assertFalse(ScheduleCalculator.isBigSectionStart(2))
        assertTrue(ScheduleCalculator.isBigSectionEnd(2))
        assertFalse(ScheduleCalculator.isBigSectionEnd(3))
        assertTrue(ScheduleCalculator.isBigSectionStart(9))
        assertTrue(ScheduleCalculator.isBigSectionEnd(11))
    }

    /** 周次压缩显示 */
    @Test
    fun weeksAreCompacted() {
        assertEquals("6-11,14-15", ScheduleCalculator.formatWeeks(setOf(6, 7, 8, 9, 10, 11, 14, 15)))
        assertEquals("1", ScheduleCalculator.formatWeeks(setOf(1)))
        assertEquals("1,3,5", ScheduleCalculator.formatWeeks(setOf(5, 1, 3)))
        assertEquals("", ScheduleCalculator.formatWeeks(emptySet()))
    }

    /** 顺序占位不与已有颜色重复 */
    @Test
    fun nextColorIndexAvoidsUsedOnes() {
        val used = mutableListOf<Int>()
        repeat(ScheduleCalculator.PALETTE_SIZE) {
            val next = ScheduleCalculator.nextColorIndex(used)
            assertFalse("第 ${used.size + 1} 次分配撞色", next in used)
            used += next
        }
        // 全部槽位用满后只能复用
        assertTrue(ScheduleCalculator.nextColorIndex(used) in 0 until ScheduleCalculator.PALETTE_SIZE)
    }

    /** 整批配色与传入顺序无关，且不超过调色板容量时不撞色 */
    @Test
    fun sortedNameColorsAreStableAndDistinct() {
        val names = listOf(
            "液压与气压传动A", "PLC原理及应用B", "智能装备与物联网技术（通讯）", "机械制造基础A",
            "传感器与测试技术", "现代机械设计方法", "机电传动控制B", "机械装备结构与设计",
            "5形势与政策", "人机交互技术",
        )
        val first = ScheduleCalculator.colorIndexesBySortedName(names)
        val second = ScheduleCalculator.colorIndexesBySortedName(names.reversed())
        assertEquals(first, second)
        assertEquals(names.size, first.values.toSet().size)
    }

    /** 回归：不同课名超过旧 12 桶时（16 色扩容的场景），容量内仍不允许撞色 */
    @Test
    fun sortedNameColorsDistinctBeyondLegacyPalette() {
        val names = (1..16).map { "课程${('A' + it - 1)}" }
        val mapping = ScheduleCalculator.colorIndexesBySortedName(names)
        assertEquals(16, mapping.values.toSet().size)
    }
}
