package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * 课表导出为图片（[ScheduleShare]）：文件名 / 周次日期范围。
 *
 * 板的网格几何不在这里——2026-10-10 起与课表页同源，测在
 * `ui/week/WeekGridLayoutTest.shareBoardGridHeightMatchesPageBudget`。
 */
class ScheduleShareTest {

    @Test
    fun `file name carries date tag and week`() {
        assertEquals("JUWPHub课表_20261009_第3周.png", ScheduleShare.exportFileName("20261009", 3))
        assertEquals("JUWPHub课表_20261009_第12周.png", ScheduleShare.exportFileName("20261009", 12))
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
}
