package edu.jxslu.schedule.domain

import edu.jxslu.schedule.data.DefaultData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 今日页的状态推导。
 *
 * 这些断言锁的是需求本身：
 * 已结束的课不进列表；同一节课不在焦点卡与列表里各出现一次；
 * 明天只在今天没有待上课程时上桌；明天没有课 → 休息提示。
 * 时间相关的分支很容易在改动中被碰坏，所以每个时间点都固定下来。
 */
class TodayStateTest {

    /** 第 1 周 = 8/31–9/6，所以 9/17（周四）是第 3 周 */
    private val semester = SemesterConfig(startDate = "2026-08-31", totalWeeks = 20)
    private val slots = DefaultData.defaultTimeSlots

    /** 2026-09-17 是周四 */
    private val thursday = LocalDate.parse("2026-09-17")

    private fun course(
        id: Long,
        name: String,
        day: Int,
        startSection: Int,
        endSection: Int,
        weeks: Set<Int> = (1..10).toSet(),
    ) = Course(
        id = id,
        name = name,
        teacher = "老师$id",
        position = "教学北大楼(北B10$id)",
        day = day,
        startSection = startSection,
        endSection = endSection,
        weeks = weeks,
    )

    /** 周四三节课：3-4 节、5-6 节、7-8 节 */
    private val thursdayCourses = listOf(
        course(1, "机电传动控制B", day = 4, startSection = 3, endSection = 4),
        course(2, "机械制造基础A", day = 4, startSection = 5, endSection = 6),
        course(3, "人机交互技术", day = 4, startSection = 7, endSection = 8),
    )

    /** 周五两节课 */
    private val fridayCourses = listOf(
        course(4, "机械制造基础A", day = 5, startSection = 1, endSection = 2),
        course(5, "现代机械设计方法", day = 5, startSection = 3, endSection = 4),
    )

    private fun stateAt(time: LocalTimeLike, courses: List<Course> = thursdayCourses + fridayCourses) =
        buildTodayState(semester, slots, courses, thursday, time)

    @Test
    fun beforeFirstClass_showsAllThreeAndNextIsTheFirst() {
        val s = stateAt(LocalTimeLike(7, 0))
        assertEquals(3, s.remaining.size)
        assertNull(s.ongoing)
        assertEquals("机电传动控制B", s.next?.name)
        assertEquals(195, s.minutesToNext)
        assertFalse(s.todayAllDone)
        assertFalse(s.todayEmpty)
        assertEquals(3, s.todayTotal)
        assertNull(s.ongoingCountdown)
        // 焦点课（下一节）从列表里拿走：列表 2 门 + 焦点卡 1 门 = remaining 3 门，不重复
        assertEquals(2, s.listCourses.size)
        assertFalse(s.listCourses.any { it.name == s.next?.name })
        // 今天还有课 → 明天先不上桌
        assertFalse(s.tomorrowVisible)
    }

    @Test
    fun duringThirdSection_ongoingIsFlaggedAndEndedOnesDropped() {
        val s = stateAt(LocalTimeLike(10, 30))
        assertEquals("机电传动控制B", s.ongoing?.name)
        assertEquals(70, s.minutesToOngoingEnd)
        assertEquals("机械制造基础A", s.next?.name)
        // 正在上的课只进焦点卡，列表只放未开始的两节（避免同一节课两处重复）
        assertEquals(2, s.remaining.size)
        assertEquals(listOf("机械制造基础A", "人机交互技术"), s.remaining.map { it.name })
        // 焦点是正在上的课，本来就不在 remaining 里 → 列表 = remaining，且不含正在上的课
        assertEquals(s.remaining.map { it.name }, s.listCourses.map { it.name })
        assertFalse(s.listCourses.any { it.name == s.ongoing?.name })
        assertFalse(s.tomorrowVisible)
    }

    @Test
    fun onlyLastClassOngoing_remainingIsEmptyButNotAllDone() {
        // 16:40：7-8 节在上，3-4/5-6 已结束 → 列表空、焦点卡仍有焦点
        val s = stateAt(LocalTimeLike(16, 40))
        assertEquals("人机交互技术", s.ongoing?.name)
        assertTrue(s.remaining.isEmpty())
        assertEquals(0, s.listCourses.size)
        assertNull(s.next)
        assertFalse(s.todayAllDone)
        assertNotNull(s.ongoingCountdown)
        // 还在上课 → 明天不上桌
        assertFalse(s.tomorrowVisible)
    }

    @Test
    fun ongoingCountdown_duringSubSectionAndBreak() {
        val c = thursdayCourses.first() // 3-4 节：10:15-10:55 / 11:00-11:40
        // 第 1 小节课内：节次号用作息小节号（与焦点卡「第3-4节」同一套坐标），不再用课程内序号
        assertEquals(
            "第3节 · 还有 25 分钟下课",
            ongoingCountdownLabel(slots, c, LocalTimeLike(10, 30)),
        )
        // 小节之间课间（10:55 下课，11:00 上课）
        assertEquals(
            "课间 · 还有 5 分钟上课",
            ongoingCountdownLabel(slots, c, LocalTimeLike(10, 55)),
        )
        // 第 2 小节课内
        assertEquals(
            "第4节 · 还有 40 分钟下课",
            ongoingCountdownLabel(slots, c, LocalTimeLike(11, 0)),
        )
        // 单小节课程不带节次号（挂了也只是复读课程本身）
        val single = course(7, "单节", day = 4, startSection = 3, endSection = 3)
        assertEquals(
            "还有 25 分钟下课",
            ongoingCountdownLabel(slots, single, LocalTimeLike(10, 30)),
        )
    }

    @Test
    fun ongoingProgress_coversWholeCourseAndIsClamped() {
        val c = thursdayCourses.first() // 10:15–11:40，共 85 分钟
        assertEquals(0f, ongoingProgress(slots, c, LocalTimeLike(10, 15))!!, 0.001f)
        assertEquals(0.5f, ongoingProgress(slots, c, LocalTimeLike(10, 57))!!, 0.02f)
        assertEquals(1f, ongoingProgress(slots, c, LocalTimeLike(11, 40))!!, 0.001f)
        // 自定义时间课也按有效区间算
        val custom = Course(
            8, "自定", "", "", 4, 1, 1, setOf(1),
            isCustomTime = true, customStartTime = "10:00", customEndTime = "10:30",
        )
        assertEquals(0.5f, ongoingProgress(slots, custom, LocalTimeLike(10, 15))!!, 0.001f)
    }

    @Test
    fun lunchBreak_ongoingIsNull() {
        val s = stateAt(LocalTimeLike(11, 50))
        // 3-4 节已结束，剩下 5-6、7-8
        assertEquals(2, s.remaining.size)
        assertNull(s.ongoing)
        assertEquals("机械制造基础A", s.next?.name)
        assertNull(s.ongoingCountdown)
    }

    @Test
    fun afterLastClass_todayIsDoneAndTomorrowIsShown() {
        val s = stateAt(LocalTimeLike(17, 30))
        assertTrue(s.todayAllDone)
        assertFalse(s.todayEmpty)
        assertTrue(s.remaining.isEmpty())
        assertNull(s.ongoing)
        assertNull(s.next)
        assertEquals(3, s.todayTotal)

        assertTrue(s.tomorrowInTerm)
        assertEquals(5, s.tomorrowDay)
        assertEquals(listOf("机械制造基础A", "现代机械设计方法"), s.tomorrowCourses.map { it.name })
        // 今天全上完 → 明天上桌
        assertTrue(s.tomorrowVisible)
    }

    @Test
    fun noClassToday_isEmptyNotDone() {
        val s = stateAt(LocalTimeLike(10, 30), fridayCourses)
        assertTrue(s.todayEmpty)
        assertFalse(s.todayAllDone)
        assertTrue(s.remaining.isEmpty())
        assertEquals(0, s.todayTotal)
        // 今天没课 → 明天上桌
        assertTrue(s.tomorrowVisible)
    }

    @Test
    fun tomorrowWithoutClass_leavesTomorrowListEmpty() {
        // 只有本周（第 3 周）的课，明天周五就查不到
        val onlyThisWeek = thursdayCourses.map { it.copy(weeks = setOf(3)) } +
            fridayCourses.map { it.copy(weeks = setOf(2)) }
        val s = stateAt(LocalTimeLike(17, 30), onlyThisWeek)
        assertTrue(s.todayAllDone)
        assertTrue(s.tomorrowInTerm)
        assertTrue(s.tomorrowCourses.isEmpty())
        assertTrue(s.tomorrowVisible)
    }

    @Test
    fun tomorrowCrossesWeekBoundary() {
        // 2026-09-20 是周日（第 3 周最后一天），明天周一属于第 4 周
        val sunday = LocalDate.parse("2026-09-20")
        val mondayCourse = course(9, "周一早课", day = 1, startSection = 1, endSection = 2)
        val s = buildTodayState(semester, slots, listOf(mondayCourse), sunday, LocalTimeLike(20, 0))
        assertEquals(4, s.tomorrowWeek)
        assertEquals(1, s.tomorrowDay)
        assertEquals(listOf("周一早课"), s.tomorrowCourses.map { it.name })
        // 周日这一天没有待上课程 → 明天上桌
        assertTrue(s.tomorrowVisible)
    }

    @Test
    fun outOfTerm_nothingIsShown() {
        val beforeTerm = LocalDate.parse("2026-08-20")
        val s = buildTodayState(
            semester,
            slots,
            thursdayCourses,
            beforeTerm,
            LocalTimeLike(10, 30),
        )
        assertFalse(s.inTerm)
        assertTrue(s.remaining.isEmpty())
        assertFalse(s.tomorrowInTerm)
        assertTrue(s.tomorrowCourses.isEmpty())
        assertFalse(s.tomorrowVisible)
    }

    @Test
    fun minutesHelpersReturnNullOncePassed() {
        val c = thursdayCourses.first()
        // 11:50 时 3-4 节已下课
        assertNull(minutesUntilEnd(slots, c, LocalTimeLike(11, 50)))
        assertNull(minutesUntilStart(slots, c, LocalTimeLike(10, 30)))
        assertEquals(70, minutesUntilEnd(slots, c, LocalTimeLike(10, 30)))
    }

    /**
     * 自定义时间的课按**有效开始时刻**参与排序与列表，不按 startSection。
     *
     * 根因：旧实现用 `sortedBy { it.startSection }` 且行内时刻只查作息表，
     * 于是一门「startSection=1、实际 18:00 上」的课会排到所有课前面，
     * 并显示成 08:30。
     */
    @Test
    fun customTimeCourse_sortsByEffectiveStartNotSection() {
        val custom = Course(
            // 测试日期 9/17 属于第 3 周，周次必须含 3 才会进当天列表
            20, "晚自习自定", "", "", 4, 1, 1, setOf(3),
            isCustomTime = true, customStartTime = "18:00", customEndTime = "20:00",
        )
        val s = stateAt(LocalTimeLike(7, 0), thursdayCourses + custom)
        // 18:00 的课排在 10:15 那门之后 → 它是最后一门，不是第一门
        assertEquals("晚自习自定", s.remaining.lastOrNull()?.name)
        assertEquals(3 + 1, s.remaining.size)
        assertNull(s.ongoing)
        // 07:00 时最近的一节仍按有效时刻取：10:15 的课早于 18:00 的课
        assertEquals("机电传动控制B", s.next?.name)
        // 有效时刻参与倒计时：18:00 - 07:00 = 660 分钟
        assertEquals(660, minutesUntilStart(slots, custom, LocalTimeLike(7, 0)))
        assertEquals(120, minutesUntilEnd(slots, custom, LocalTimeLike(18, 0)))
    }

    /** 焦点卡取走的课不能同时留在列表里——两处显示同一节课是旧版显乱的第一来源。 */
    @Test
    fun focusCourseNeverDuplicatesInList() {
        for (time in listOf(LocalTimeLike(7, 0), LocalTimeLike(10, 30), LocalTimeLike(15, 0))) {
            val s = stateAt(time)
            val focusName = (s.ongoing ?: s.next)?.name
            if (focusName != null) {
                assertFalse(
                    "焦点课 $focusName 在 $time 时重复出现在列表里",
                    s.listCourses.any { it.name == focusName },
                )
            }
        }
    }
}
