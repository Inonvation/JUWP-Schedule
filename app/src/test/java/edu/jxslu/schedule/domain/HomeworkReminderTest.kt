package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 作业截止提醒的时刻计算（DESIGN §3.11 规则 + §4.20 纯函数、去重键）。
 *
 * 提醒算错的表现是「不提醒 / 越界补发 / 重复提醒」——后台链路上肉眼不可见，
 * 纯函数口径必须在 JVM 单测里钉死（与 [ReminderPlannerTest] 同一纪律）。
 */
class HomeworkReminderTest {

    /** 截止日：2026-09-22（前一提醒点 = 9/21 20:00，当天提醒点 = 9/22 20:00）。 */
    private val due = LocalDate.parse("2026-09-22")

    private fun homework(
        id: Long = 12,
        dueDate: LocalDate? = due,
        done: Boolean = false,
    ) = Homework(
        id = id,
        courseName = "高等数学",
        dueDate = dueDate,
        done = done,
    )

    private fun at(date: LocalDate, hour: Int, minute: Int): LocalDateTime =
        LocalDateTime.of(date, LocalTime.of(hour, minute))

    // ---- 提醒点是什么 ----

    @Test
    fun tomorrowPoint_isPreviousDay20() {
        // 截止日前一天 20:30：正在「明天截止」的有效窗口内
        val plan = homeworkReminderPlan(homework(), at(due.minusDays(1), 20, 30))
        assertEquals(LocalDate.parse("2026-09-21"), plan?.noticeDate)
        assertEquals(LocalDateTime.of(LocalDate.parse("2026-09-21"), LocalTime.of(20, 0)), plan?.triggerAt)
        assertEquals(HomeworkReminderPlan.Kind.Tomorrow, plan?.kind)
        assertEquals("明天截止", plan?.kind?.label)
    }

    @Test
    fun todayPoint_isDueDay20() {
        val plan = homeworkReminderPlan(homework(), at(due, 20, 30))
        assertEquals(due, plan?.noticeDate)
        assertEquals(LocalDateTime.of(due, LocalTime.of(20, 0)), plan?.triggerAt)
        assertEquals(HomeworkReminderPlan.Kind.Today, plan?.kind)
        assertEquals("今天截止", plan?.kind?.label)
    }

    // ---- 有效期窗口的边界 ----

    @Test
    fun windowStartsAt20Sharp() {
        // 20:00 整点即有效（与上课提醒一致：触发时刻本身算「已到」）
        assertEquals(
            HomeworkReminderPlan.Kind.Tomorrow,
            homeworkReminderPlan(homework(), at(due.minusDays(1), 20, 0))?.kind,
        )
        // 19:59 还没到触发时刻
        assertNull(homeworkReminderPlan(homework(), at(due.minusDays(1), 19, 59)))
    }

    @Test
    fun windowEndsAtNextMidnight() {
        // 23:59 仍在有效期内（闹钟被 Doze 推迟也不丢）
        assertEquals(
            HomeworkReminderPlan.Kind.Tomorrow,
            homeworkReminderPlan(homework(), at(due.minusDays(1), 23, 59))?.kind,
        )
        // 越过次日 00:00 即失效（00:00 整已不属于前一天）
        assertNull(homeworkReminderPlan(homework(), at(due, 0, 0)))
        assertNull(homeworkReminderPlan(homework(), at(due, 0, 1)))
    }

    @Test
    fun todayPoint_stillValidAt21() {
        // 口径 = DESIGN §3.11「各点有效期至下一个自然日 00:00」：20:00 的「今天截止」
        // 拖到 21:00 发出来仍然有用（还没到午夜），越过 00:00 才算越过
        assertEquals(
            HomeworkReminderPlan.Kind.Today,
            homeworkReminderPlan(homework(), at(due, 21, 0))?.kind,
        )
    }

    @Test
    fun missedWindow_isNotBackfilled() {
        // 9/21 20:00 的提醒点整晚没发出去（手机关机/无网络）：过了午夜就跳过，不补发
        assertNull(homeworkReminderPlan(homework(), at(due, 9, 0)))
    }

    // ---- 不该发的情形 ----

    @Test
    fun doneHomework_returnsNull() {
        assertNull(homeworkReminderPlan(homework(done = true), at(due.minusDays(1), 20, 30)))
        assertNull(homeworkReminderPlan(homework(done = true), at(due, 20, 30)))
    }

    @Test
    fun overdueHomework_returnsNull() {
        // 已逾期（今天 > 截止日）：两个提醒点的窗口都已关闭
        val overdue = homework(dueDate = LocalDate.parse("2026-09-20"))
        assertNull(homeworkReminderPlan(overdue, at(due, 10, 0)))
    }

    @Test
    fun noDueDate_returnsNull() {
        // 布置了但没给期限：没有提醒点
        assertNull(homeworkReminderPlan(homework(dueDate = null), at(due.minusDays(1), 20, 30)))
    }

    // ---- 下一个提醒（排闹钟用） ----

    @Test
    fun next_picksEarliestUpcomingTrigger() {
        // 9/22 早上 8:00：9/23 截止那条的「明天截止」点在 9/22 20:00，早于 9/25 那条
        val later = homework(id = 2, dueDate = LocalDate.parse("2026-09-25"))
        val sooner = homework(id = 3, dueDate = LocalDate.parse("2026-09-23"))
        val plan = nextHomeworkReminder(listOf(later, sooner), at(due, 8, 0))
        assertEquals(3L, plan?.homework?.id)
        assertEquals(LocalDateTime.of(due, LocalTime.of(20, 0)), plan?.triggerAt)
        assertEquals(HomeworkReminderPlan.Kind.Tomorrow, plan?.kind)
    }

    @Test
    fun next_skipsPassedTriggerAndDoneHomework() {
        // 9/21 21:00：id=12 的「明天截止」点（9/21 20:00）已过，下一个是 9/22 20:00 的当天点；
        // 已完成的那条不参与
        val pending = homework(id = 12)
        val done = homework(id = 13, done = true)
        val plan = nextHomeworkReminder(
            listOf(pending, done),
            at(LocalDate.parse("2026-09-21"), 21, 0),
        )
        assertEquals(12L, plan?.homework?.id)
        assertEquals(HomeworkReminderPlan.Kind.Today, plan?.kind)
        assertEquals(LocalDateTime.of(due, LocalTime.of(20, 0)), plan?.triggerAt)
    }

    @Test
    fun next_emptyOrNoDueOrOverdue_returnsNull() {
        assertNull(nextHomeworkReminder(emptyList(), at(due, 8, 0)))
        assertNull(nextHomeworkReminder(listOf(homework(dueDate = null)), at(due, 8, 0)))
        assertNull(
            nextHomeworkReminder(
                listOf(homework(dueDate = LocalDate.parse("2026-09-01"))),
                at(due, 8, 0),
            ),
        )
    }

    @Test
    fun next_ignoresDueDatesBeyondHorizon() {
        // DESIGN §4.20：最多向后找 30 天——更远的截止日不排闹钟
        val far = homework(dueDate = due.plusDays(31))
        assertNull(nextHomeworkReminder(listOf(far), at(due.minusDays(5), 8, 0)))

        // 30 天整仍在范围内（9/21 + 30 = 10/21）
        val edge = homework(dueDate = LocalDate.parse("2026-09-21").plusDays(30))
        assertEquals(
            edge.id,
            nextHomeworkReminder(listOf(edge), at(LocalDate.parse("2026-09-21"), 8, 0))?.homework?.id,
        )
    }

    // ---- 去重键 ----

    @Test
    fun dedupKey_isHomeworkIdPlusNoticeDate() {
        // DESIGN §3.11：去重键 = 作业 id + 提醒点日期；两个提醒点日期不同 → 键不同，各发一次
        assertEquals("12|2026-09-22", homeworkReminderPlan(homework(id = 12), at(due, 20, 30))?.dedupKey)
        assertEquals(
            "12|2026-09-21",
            homeworkReminderPlan(homework(id = 12), at(due.minusDays(1), 20, 30))?.dedupKey,
        )
    }

    // ---- 该发哪一组（通知层的汇总口径，DESIGN §3.11）----

    @Test
    fun group_prefersTodayOverTomorrow() {
        val items = listOf(
            homework(id = 1, dueDate = due),
            homework(id = 2, dueDate = due.plusDays(1)),
        )
        // 截止日 20:30：1 号在「今天截止」窗口，2 号在「明天截止」窗口
        val group = homeworkReminderGroup(items, at(due, 20, 30))
        assertEquals(listOf(1L), group.map { it.homework.id })
        assertEquals(HomeworkReminderPlan.Kind.Today, group.single().kind)
    }

    @Test
    fun group_collectsWholeWindowForSummary() {
        // 三条都落在「明天截止」窗口 → 同一组（通知层据此发汇总）
        val items = listOf(homework(id = 1), homework(id = 2), homework(id = 3))
        val group = homeworkReminderGroup(items, at(due.minusDays(1), 20, 30))
        assertEquals(3, group.size)
        assertTrue(group.all { it.kind == HomeworkReminderPlan.Kind.Tomorrow })
    }

    @Test
    fun group_emptyOutsideWindowAndForDone() {
        assertEquals(emptyList<HomeworkReminderPlan>(), homeworkReminderGroup(listOf(homework()), at(due, 19, 0)))
        assertEquals(
            emptyList<HomeworkReminderPlan>(),
            homeworkReminderGroup(listOf(homework(done = true)), at(due, 20, 30)),
        )
        assertEquals(emptyList<HomeworkReminderPlan>(), homeworkReminderGroup(emptyList(), at(due, 20, 30)))
    }
}
