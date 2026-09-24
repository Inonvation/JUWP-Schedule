package edu.jxslu.schedule.data.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 日历同步写到哪个日历（DESIGN §4.12）。
 *
 * 锁的需求：写进隐藏日历的事件不露脸、提醒也未必响，所以「可见的可写日历优先」；
 * 只读日历不能选；一个可写的都没有时返回 null（同步侧报「没有可用日历账户」）。
 */
class CalendarSyncerPickTest {

    private fun candidate(
        id: Long,
        accessLevel: Int = 500, // CAL_ACCESS_CONTRIBUTOR 起可写
        visible: Boolean = true,
    ) = CalendarCandidate(id = id, accessLevel = accessLevel, visible = visible)

    @Test
    fun `同条件下取第一个可写的`() {
        assertEquals(3L, pickWritableCalendarId(listOf(candidate(3), candidate(7))))
    }

    @Test
    fun `隐藏的排在前面也让位给可见的`() {
        assertEquals(7L, pickWritableCalendarId(listOf(candidate(3, visible = false), candidate(7))))
    }

    @Test
    fun `跳过只读日历`() {
        assertEquals(5L, pickWritableCalendarId(listOf(candidate(2, accessLevel = 200), candidate(5))))
    }

    @Test
    fun `只有隐藏日历时可写也不算空`() {
        assertEquals(9L, pickWritableCalendarId(listOf(candidate(9, visible = false))))
    }

    @Test
    fun `可见但只读的日历不算候选`() {
        assertEquals(
            4L,
            pickWritableCalendarId(listOf(candidate(1, accessLevel = 400), candidate(4, visible = false))),
        )
    }

    @Test
    fun `没有可写日历返回 null`() {
        assertNull(pickWritableCalendarId(emptyList()))
        assertNull(pickWritableCalendarId(listOf(candidate(1, accessLevel = 100))))
    }
}
