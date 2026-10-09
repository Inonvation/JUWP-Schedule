package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * 课表导出为图片（[ScheduleShare]）：文件名 / 周次日期范围 / 导出板行高。
 */
class ScheduleShareTest {

    @Test
    fun `file name carries date tag and week`() {
        assertEquals("水贝贝课表_20261009_第3周.png", ScheduleShare.exportFileName("20261009", 3))
        assertEquals("水贝贝课表_20261009_第12周.png", ScheduleShare.exportFileName("20261009", 12))
    }

    @Test
    fun `week range formats first and last visible day`() {
        // 完整一周
        assertEquals(
            "10/5 – 10/11",
            ScheduleShare.weekRangeText(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 11)),
        )
        // 关掉周末：末列是周五
        assertEquals(
            "10/5 – 10/9",
            ScheduleShare.weekRangeText(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 9)),
        )
        // 只关周日：末列是周六（按「周一 + 6 天」硬算会错成 10/11）
        assertEquals(
            "10/5 – 10/10",
            ScheduleShare.weekRangeText(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 10)),
        )
    }

    @Test
    fun `week range crosses month and year boundaries`() {
        assertEquals(
            "9/28 – 10/4",
            ScheduleShare.weekRangeText(LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 4)),
        )
        assertEquals(
            "12/28 – 1/3",
            ScheduleShare.weekRangeText(LocalDate.of(2026, 12, 28), LocalDate.of(2027, 1, 3)),
        )
    }

    @Test
    fun `week range is null without semester dates`() {
        assertNull(ScheduleShare.weekRangeText(null, LocalDate.of(2026, 10, 11)))
        assertNull(ScheduleShare.weekRangeText(LocalDate.of(2026, 10, 5), null))
        assertNull(ScheduleShare.weekRangeText(null, null))
    }

    @Test
    fun `row height splits available space`() {
        // 11 节、固定占用 55dp、可用 660dp → 每行 55dp，落在钳位区间内
        assertEquals(
            55f,
            ScheduleShare.boardRowHeightDp(660f, 55f, 11),
            0.001f,
        )
    }

    @Test
    fun `row height is clamped on both ends`() {
        // 高屏：摊出来远超上限，夹到 64
        assertEquals(
            ScheduleShare.MAX_ROW_HEIGHT_DP,
            ScheduleShare.boardRowHeightDp(2000f, 60f, 11),
            0.001f,
        )
        // 矮屏：摊出来低于下限，夹到 44（宁可整表溢出也不让课名放不下）
        assertEquals(
            ScheduleShare.MIN_ROW_HEIGHT_DP,
            ScheduleShare.boardRowHeightDp(300f, 60f, 11),
            0.001f,
        )
    }

    @Test
    fun `row height falls back to min without sections`() {
        assertEquals(ScheduleShare.MIN_ROW_HEIGHT_DP, ScheduleShare.boardRowHeightDp(600f, 60f, 0), 0.001f)
    }
}
