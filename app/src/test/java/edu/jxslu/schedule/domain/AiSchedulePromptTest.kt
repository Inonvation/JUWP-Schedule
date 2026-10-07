package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 调课 prompt 组装与脱敏（DESIGN §4.39）：
 * 编号清单从 1 起、脱敏映射按姓名排序稳定、周次压缩口径。
 */
class AiSchedulePromptTest {

    private fun course(
        name: String = "高等数学",
        teacher: String = "张三",
        position: String = "教一101",
        day: Int = 3,
        startSection: Int = 3,
        endSection: Int = 4,
        weeks: Set<Int> = (1..16).toSet(),
    ) = Course(
        id = 0,
        name = name,
        teacher = teacher,
        position = position,
        day = day,
        startSection = startSection,
        endSection = endSection,
        weeks = weeks,
    )

    @Test
    fun `编号清单从 1 起并含星期与节次`() {
        val listing = AiSchedulePrompt.listing(
            listOf(course(), course(name = "大学物理", day = 5, startSection = 1, endSection = 2)),
            maskTeacher = false,
        )
        val lines = listing.lines()
        assertTrue(lines[0].startsWith("1. 高等数学"))
        assertTrue(lines[1].startsWith("2. 大学物理"))
        assertTrue(lines[0].contains("周三 第3-4节"))
        assertTrue(lines[1].contains("周五 第1-2节"))
    }

    @Test
    fun `脱敏按姓名排序稳定编号且同名同号`() {
        val courses = listOf(
            course(teacher = "张三"),
            course(teacher = "李四"),
            course(teacher = "张三"),
        )
        val mask = AiSchedulePrompt.maskedTeachers(courses)
        assertEquals(2, mask.size)
        assertTrue(mask.containsKey("张三"))
        assertTrue(mask.containsKey("李四"))
        assertNotEquals(mask["张三"], mask["李四"])
        assertTrue(mask.values.all { it.startsWith("教师") })
    }

    @Test
    fun `脱敏映射与课表顺序无关`() {
        val a = AiSchedulePrompt.maskedTeachers(listOf(course(teacher = "张三"), course(teacher = "李四")))
        val b = AiSchedulePrompt.maskedTeachers(listOf(course(teacher = "李四"), course(teacher = "张三")))
        assertEquals(a, b)
    }

    @Test
    fun `不脱敏时清单里保留教师原名`() {
        val listing = AiSchedulePrompt.listing(listOf(course(teacher = "张三")), maskTeacher = false)
        assertTrue(listing.contains("张三"))
    }

    @Test
    fun `脱敏时清单里不出现教师原名`() {
        val listing = AiSchedulePrompt.listing(listOf(course(teacher = "张三")), maskTeacher = true)
        assertFalse(listing.contains("张三"))
        assertTrue(listing.contains("教师1"))
    }

    @Test
    fun `周次连续段压缩为零散段`() {
        assertEquals("第1-16周", AiSchedulePrompt.weeksLabel((1..16).toSet()))
        assertEquals("第1-4,6,8-9周", AiSchedulePrompt.weeksLabel(setOf(1, 2, 3, 4, 6, 8, 9)))
        assertEquals("第5周", AiSchedulePrompt.weeksLabel(setOf(5)))
    }

    @Test
    fun `空课表给占位而不是空字符串`() {
        assertEquals("（当前课表为空）", AiSchedulePrompt.listing(emptyList(), maskTeacher = true))
    }

    @Test
    fun `用户消息含清单与通知`() {
        val msg = AiSchedulePrompt.userMessage(
            notice = "周三高数调到周五",
            courses = listOf(course()),
            maskTeacher = false,
        )
        assertTrue(msg.contains("【我的课表】"))
        assertTrue(msg.contains("【调课通知】"))
        assertTrue(msg.contains("周三高数调到周五"))
    }
}
