package edu.jxslu.schedule.domain

import edu.jxslu.schedule.domain.ExamMapper.ExamEntry
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 考试安排变更检测（DESIGN §4.33）：钉住「什么算新发布、什么算调整、什么不打扰」。
 */
class ExamChangeDetectorTest {

    private fun entry(
        courseNo: String = "MATH101",
        name: String = "高等数学",
        date: String = "2026-01-13",
        startTime: String = "14:00",
        endTime: String = "16:00",
        room: String = "教一101",
        campus: String = "瑶湖校区",
        seatNo: String = "12",
    ) = ExamEntry(
        courseNo = courseNo,
        name = name,
        teacher = "张三",
        room = room,
        campus = campus,
        date = date,
        startTime = startTime,
        endTime = endTime,
        seatNo = seatNo,
        sessionNo = "第1场",
    )

    @Test
    fun `新发布的考试被识别`() {
        val changes = ExamChangeDetector.detect(old = emptyList(), new = listOf(entry()))
        assertEquals(1, changes.size)
        assertEquals(ExamChangeDetector.Kind.NEW, changes[0].kind)
        assertEquals("高等数学", changes[0].entry.name)
    }

    @Test
    fun `时间调整被识别且带旧值`() {
        val changes = ExamChangeDetector.detect(
            old = listOf(entry()),
            new = listOf(entry(startTime = "09:00", endTime = "11:00")),
        )
        assertEquals(1, changes.size)
        assertEquals(ExamChangeDetector.Kind.UPDATED, changes[0].kind)
        assertEquals("09:00", changes[0].entry.startTime)
        assertEquals("14:00", changes[0].before?.startTime)
    }

    @Test
    fun `考场或校区或座位调整被识别`() {
        val variants = listOf(
            entry().copy(room = "教二202"),
            entry().copy(campus = "青山湖校区"),
            entry().copy(seatNo = "07"),
        )
        for (changed in variants) {
            val changes = ExamChangeDetector.detect(old = listOf(entry()), new = listOf(changed))
            assertEquals(1, changes.size)
            assertEquals(ExamChangeDetector.Kind.UPDATED, changes[0].kind)
        }
    }

    @Test
    fun `完全没变的考试不报`() {
        val both = listOf(entry(), entry(courseNo = "ENG101", name = "大学英语", date = "2026-01-15"))
        assertEquals(0, ExamChangeDetector.detect(both, both).size)
    }

    @Test
    fun `撤考忽略`() {
        val changes = ExamChangeDetector.detect(
            old = listOf(entry(), entry(courseNo = "GONE1", name = "撤掉的考试")),
            new = listOf(entry()),
        )
        assertEquals(0, changes.size)
    }

    @Test
    fun `同课不同日期算两场`() {
        // 日期参与身份：改期 = 旧键消失 + 新键出现 = 一场「新增」（调整前那场不另报「撤考」）
        val changes = ExamChangeDetector.detect(
            old = listOf(entry()),
            new = listOf(entry(date = "2026-01-20")),
        )
        assertEquals(1, changes.size)
        assertEquals(ExamChangeDetector.Kind.NEW, changes[0].kind)
    }

    @Test
    fun `课程号空时用课程名做身份`() {
        val changes = ExamChangeDetector.detect(
            old = listOf(entry(courseNo = "")),
            new = listOf(entry(courseNo = "", startTime = "09:00")),
        )
        assertEquals(1, changes.size)
        assertEquals(ExamChangeDetector.Kind.UPDATED, changes[0].kind)
    }

    @Test
    fun `日期缺失的行跳过不误报`() {
        // kssj 解析失败的行 date 为空，键会漂移成「每次都是新的」，必须跳过
        val changes = ExamChangeDetector.detect(old = emptyList(), new = listOf(entry(date = "")))
        assertEquals(0, changes.size)
    }
}
