package edu.jxslu.schedule.domain

import edu.jxslu.schedule.data.DefaultData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 上课提醒的时刻计算（DESIGN §3.7）。
 *
 * 提醒算错的表现是「迟提醒 / 不提醒 / 上课了才提醒」——后台链路上肉眼不可见，
 * 纯函数口径必须在 JVM 单测里钉死。与 [TodayBoundaryTest] 用同一份学期与课程布置。
 */
class ReminderPlannerTest {

    /** 第 1 周 = 8/31–9/6，9/17（周四）是第 3 周 */
    private val semester = SemesterConfig(startDate = "2026-08-31", totalWeeks = 20)
    private val slots = DefaultData.defaultTimeSlots
    private val thursday = LocalDate.parse("2026-09-17")

    private fun course(id: Long, start: Int, end: Int) = Course(
        id = id,
        name = "课$id",
        teacher = "",
        position = "",
        day = 4,
        startSection = start,
        endSection = end,
        weeks = (1..10).toSet(),
    )

    /** 周四三节连排：3-4（10:15–11:40）、5-6（14:00–15:25）、7-8（15:45–17:10） */
    private val courses = listOf(
        course(1, 3, 4),
        course(2, 5, 6),
        course(3, 7, 8),
    )

    private fun at(hour: Int, minute: Int): LocalDateTime =
        LocalDateTime.of(thursday, LocalTime.of(hour, minute))

    @Test
    fun nextReminder_isStartMinusLead() {
        val plan = nextReminderPlan(semester, slots, courses, at(7, 0), leadMinutes = 10)
        assertEquals(10 * 60 + 15, plan?.startMinutes)
        assertEquals(LocalDateTime.of(thursday, LocalTime.of(10, 5)), plan?.triggerAt)
    }

    @Test
    fun dueReminder_insideWindow() {
        // 10:08，提前量 10：3-4 节 10:15 上课，还有 7 分钟 → 该提醒
        val plan = dueReminderPlan(semester, slots, courses, at(10, 8), leadMinutes = 10)
        assertEquals(1L, plan?.course?.id)
        // 去重键 = 日期|课程id|起始分钟，闹钟与周期核对靠它去重
        assertEquals("2026-09-17|1|615", plan?.dedupKey)
    }

    @Test
    fun dueReminder_outsideWindow_returnsNull() {
        // 9:50：下一节 10:15 还有 25 分钟，超出提前量窗口
        assertNull(dueReminderPlan(semester, slots, courses, at(9, 50), leadMinutes = 10))
    }

    @Test
    fun dueReminder_afterStart_returnsNull() {
        // 已上课不补发：10:15 整 3-4 节已开始
        assertNull(dueReminderPlan(semester, slots, courses, at(10, 15), leadMinutes = 10))
    }

    @Test
    fun classStart_atStartMinute() {
        // 14:00 整（5-6 节开始）：该发「上课」通知，窗口 15 分钟内
        val plan = dueClassStartPlan(semester, slots, courses, at(14, 0))
        assertEquals(2L, plan?.course?.id)
        // 去重键与提前量提醒同构（同一节课同一键，靠前缀区分两类）
        assertEquals("2026-09-17|2|840", plan?.dedupKey)
    }

    @Test
    fun classStart_beforeStart_returnsNull() {
        // 13:50：还没上课，「上课开始」点不该命中（这是提前量提醒的窗口）
        assertNull(dueClassStartPlan(semester, slots, courses, at(13, 50)))
    }

    @Test
    fun classStart_afterWindow_returnsNull() {
        // 14:20：已越过上课后 15 分钟的补发窗口，不再发
        assertNull(dueClassStartPlan(semester, slots, courses, at(14, 16)))
    }

    @Test
    fun nextClassStart_isStartItself() {
        val plan = nextClassStartPlan(semester, slots, courses, at(7, 0))
        assertEquals(LocalDateTime.of(thursday, LocalTime.of(10, 15)), plan?.startAt)
        // 10:08：10:15 的「开始」点还在，而它的去重键与提前量点相同（前缀在存储层区分）
        val soon = nextClassStartPlan(semester, slots, courses, at(10, 8))
        assertEquals(LocalDateTime.of(thursday, LocalTime.of(10, 15)), soon?.startAt)
    }

    @Test
    fun nextReminder_skipsPassedTrigger() {
        // 10:08：3-4 节的触发点 10:05 已过（闹钟丢失场景），下一个是 5-6 节
        val plan = nextReminderPlan(semester, slots, courses, at(10, 8), leadMinutes = 10)
        assertEquals(2L, plan?.course?.id)
        assertEquals(LocalDateTime.of(thursday, LocalTime.of(13, 50)), plan?.triggerAt)
    }

    @Test
    fun nextReminder_crossesDay() {
        // 周四放学后：下一节在下周四（同一门课，第 4 周）
        val plan = nextReminderPlan(semester, slots, courses, at(18, 0), leadMinutes = 10)
        assertEquals(LocalDate.parse("2026-09-24"), plan?.date)
        assertEquals(1L, plan?.course?.id)
    }

    @Test
    fun nextReminder_customTimeCourse_usesCustomBounds() {
        // 课程时间只有一条口径：自定义时间课以 custom 字段为准，提醒不另算
        val custom = Course(
            id = 9,
            name = "自定义课",
            teacher = "",
            position = "",
            day = 4,
            startSection = 1,
            endSection = 2,
            weeks = (1..10).toSet(),
            isCustomTime = true,
            customStartTime = "12:30",
            customEndTime = "13:10",
        )
        val plan = nextReminderPlan(semester, slots, listOf(custom), at(7, 0), leadMinutes = 10)
        assertEquals(LocalDateTime.of(thursday, LocalTime.of(12, 20)), plan?.triggerAt)
    }

    @Test
    fun noSemester_or_emptyCourses_returnsNull() {
        assertNull(nextReminderPlan(null, slots, courses, at(7, 0), 10))
        assertNull(dueReminderPlan(null, slots, courses, at(7, 0), 10))
        assertNull(nextReminderPlan(semester, slots, emptyList(), at(7, 0), 10))
    }

    @Test
    fun outOfTerm_returnsNull() {
        val afterTerm = LocalDateTime.of(LocalDate.parse("2027-02-01"), LocalTime.of(7, 0))
        assertNull(nextReminderPlan(semester, slots, courses, afterTerm, 10))
    }

    @Test
    fun coerceLead_snapsToNearestChoice() {
        assertEquals(10, ReminderDefaults.coerceLead(10))
        assertEquals(5, ReminderDefaults.coerceLead(3))
        // 7 离 5 差 2、离 10 差 3 → 吸附 5
        assertEquals(5, ReminderDefaults.coerceLead(7))
        assertEquals(30, ReminderDefaults.coerceLead(45))
    }

    // ---- 日期覆盖（调休 / 停课，§4.40）进提醒口径（2026-10-07 实报修复）----
    // 纯函数层一直收 overrides 参数，但核对层曾漏传——「AI 调完课今天没课了」
    // 仍按裸课表在原时间点发提醒。以下用例把「覆盖参与提醒计算」钉在纯函数层。

    @Test
    fun suspendedDay_noReminder_evenAtOriginalTime() {
        // 9/17（周四）停课：哪怕到了原上课时刻，两个「现在该发」的触发点都不该命中。
        // 下一个闹钟顺延到下周四的行为由 suspendedDay_nextReminderMovesToFollowingWeek 钉住。
        val overrides = listOf(
            ScheduleOverride(fromDate = "2026-09-17", toDate = "2026-09-17", kind = OverrideKind.SUSPENDED),
        )
        assertNull(dueClassStartPlan(semester, slots, courses, at(14, 0), overrides))
        assertNull(dueReminderPlan(semester, slots, courses, at(10, 8), leadMinutes = 10, overrides = overrides))
    }

    @Test
    fun suspendedDay_nextReminderMovesToFollowingWeek() {
        // 周四全天停课：上午 7 点排闹钟，第一个触发点应是下周四的 3-4 节
        val overrides = listOf(
            ScheduleOverride(fromDate = "2026-09-17", toDate = "2026-09-17", kind = OverrideKind.SUSPENDED),
        )
        val plan = nextReminderPlan(semester, slots, courses, at(7, 0), leadMinutes = 10, overrides = overrides)
        assertEquals(LocalDate.parse("2026-09-24"), plan?.date)
    }

    @Test
    fun followDay_remindsForBorrowedCourse() {
        // 9/17（周四）按第 1 周周五（9/4）的课表上课；给源位置放一门 5-6 节的课
        val borrowed = course(9, 5, 6).copy(day = 5, weeks = setOf(1))
        val overrides = listOf(
            ScheduleOverride(
                fromDate = "2026-09-17", toDate = "2026-09-17", kind = OverrideKind.FOLLOW,
                sourceWeek = 1, sourceWeekday = 5,
            ),
        )
        // 补课那天按借来的课排：应提醒 course 9（14:00 上课）
        val start = dueClassStartPlan(semester, slots, courses + borrowed, at(14, 0), overrides)
        assertEquals(9L, start?.course?.id)
        // 原周四的 3 门课当天都不上，不应命中
        val original = dueClassStartPlan(semester, slots, courses, at(14, 0), overrides)
        assertNull(original)
    }

    @Test
    fun followDay_nextAlarmUsesBorrowedCourseTimes() {
        val borrowed = course(9, 5, 6).copy(day = 5, weeks = setOf(1))
        val overrides = listOf(
            ScheduleOverride(
                fromDate = "2026-09-17", toDate = "2026-09-17", kind = OverrideKind.FOLLOW,
                sourceWeek = 1, sourceWeekday = 5,
            ),
        )
        val plan = nextReminderPlan(semester, slots, courses + borrowed, at(7, 0), leadMinutes = 10, overrides = overrides)
        // 触发点 = 借来的课（5-6 节 14:00）− 10 分钟
        assertEquals(9L, plan?.course?.id)
        assertEquals(LocalDateTime.of(thursday, LocalTime.of(13, 50)), plan?.triggerAt)
    }
}
