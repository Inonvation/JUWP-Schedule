package edu.jxslu.schedule.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 课程编辑页的周次输入辅助（解析规则本身在 `WeeksTextTest`）。 */
class ParseWeeksInputTest {

    @Test
    fun parseOddEvenHelpers() {
        assertTrue(oddWeeks().startsWith("1,"))
        assertTrue(evenWeeks().startsWith("2,"))
    }

    @Test
    fun delegatesToWeeksText() {
        assertEquals(setOf(1, 3, 5), parseWeeksInput("1-3,5单周"))
        assertEquals("1-3,5", weeksToInput(setOf(1, 2, 3, 5)))
    }
}
