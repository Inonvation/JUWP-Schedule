package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeeksTextTest {

    @Test
    fun formatCompactsRanges() {
        assertEquals("6-8,14-15", WeeksText.format(setOf(6, 7, 8, 14, 15)))
    }

    @Test
    fun formatDropsOutOfRange() {
        assertEquals("1-3", WeeksText.format(setOf(0, 1, 2, 3, 41)))
    }

    @Test
    fun labelEmptyIsExplicit() {
        assertEquals("周次未定", WeeksText.label(emptySet()))
        assertEquals("周次未定", WeeksText.label(setOf(41)))
    }

    @Test
    fun formatThenParseRoundTrips() {
        val weeks = setOf(1, 2, 3, 5, 8, 9, 10)
        assertEquals(weeks, WeeksText.parse(WeeksText.format(weeks)))
    }

    @Test
    fun parseRangeMixedAndSeparators() {
        assertEquals(setOf(1, 2, 3, 4, 5), WeeksText.parse("1-5"))
        assertEquals(setOf(1, 3, 5), WeeksText.parse("1，3、5"))
        assertEquals(setOf(1, 2, 3, 4), WeeksText.parse("1至4"))
    }

    @Test
    fun parseOddEvenSuffix() {
        assertEquals((1..15 step 2).toSet(), WeeksText.parse("1-16单周"))
        assertEquals((2..16 step 2).toSet(), WeeksText.parse("1-16双周"))
    }

    @Test
    fun parseDirtyTokenFallsBackToDigits() {
        assertEquals(setOf(3, 7), WeeksText.parse("第3周,7周"))
    }

    @Test
    fun parseClampsAtFortyAndDropsInvalid() {
        assertEquals((1..40).toSet(), WeeksText.parse("1-99"))
        assertTrue(WeeksText.parse("单周").isEmpty())
        assertTrue(WeeksText.parse("").isEmpty())
    }
}
