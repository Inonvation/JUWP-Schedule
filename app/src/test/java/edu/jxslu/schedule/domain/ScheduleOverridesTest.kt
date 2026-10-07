package edu.jxslu.schedule.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日期覆盖（调休 / 停课，DESIGN §4.40）：钉住日期 ↔ 槽位换算、覆盖摊平、
 * 以及 [ScheduleCalculator.coursesOnDay] 在覆盖下的取值口径。
 *
 * 核心契约：
 * - 覆盖只按 (周次, 星期) 生效，日期靠学期起始日换算（周一为首日）；
 * - FOLLOW 是「借源槽位的课」，**不递归**——源槽位自己停课也不影响借来的那天；
 * - 学期外 / 非法日期 / 缺源槽位的条目一律跳过，宁可少覆盖也不要把课表搞乱。
 */
class ScheduleOverridesTest {

    /** 2026-2027-1：2026-09-07（周一）开学，20 周。 */
    private val semester = SemesterConfig(startDate = "2026-09-07", totalWeeks = 20)

    private fun course(
        name: String = "高等数学",
        day: Int = 1,
        startSection: Int = 3,
        endSection: Int = 4,
        weeks: Set<Int> = (1..16).toSet(),
    ) = Course(
        id = 0,
        name = name,
        teacher = "张三",
        position = "教一101",
        day = day,
        startSection = startSection,
        endSection = endSection,
        weeks = weeks,
        kind = CourseKind.Theory,
    )

    @Test
    fun `日期换算成周次与星期`() {
        // 2026-09-07 是第 1 周周一
        assertEquals(ScheduleOverrides.Slot(1, 1), ScheduleOverrides.slotOf(semester, LocalDate.parse("2026-09-07")))
        // 2026-09-20 是周日，落在第 2 周（9/14 起）
        assertEquals(ScheduleOverrides.Slot(2, 7), ScheduleOverrides.slotOf(semester, LocalDate.parse("2026-09-20")))
        // 学期开始前 / 结束后不给槽位
        assertNull(ScheduleOverrides.slotOf(semester, LocalDate.parse("2026-08-30")))
        assertNull(ScheduleOverrides.slotOf(semester, LocalDate.parse("2027-03-01")))
    }

    @Test
    fun `槽位与日期互逆`() {
        val date = ScheduleOverrides.dateOf(semester, 3, 5)
        assertEquals(LocalDate.parse("2026-09-25"), date)
        assertEquals(ScheduleOverrides.Slot(3, 5), ScheduleOverrides.slotOf(semester, date!!))
    }

    @Test
    fun `停课区间摊平到每一天`() {
        val plan = ScheduleOverrides.plan(
            listOf(
                ScheduleOverride(
                    fromDate = "2026-09-07",
                    toDate = "2026-09-09",
                    kind = OverrideKind.SUSPENDED,
                ),
            ),
            semester,
        )
        // 周一 / 周二 / 周三 三天都停
        assertEquals(3, plan.size)
        assertTrue(ScheduleOverrides.isSuspended(plan, 1, 1))
        assertTrue(ScheduleOverrides.isSuspended(plan, 1, 3))
        assertTrue(!ScheduleOverrides.isSuspended(plan, 1, 4))
    }

    @Test
    fun `补课把目标槽位指向源槽位`() {
        val plan = ScheduleOverrides.plan(
            listOf(
                ScheduleOverride(
                    fromDate = "2026-09-20",
                    toDate = "2026-09-20",
                    kind = OverrideKind.FOLLOW,
                    sourceWeek = 3,
                    sourceWeekday = 1,
                ),
            ),
            semester,
        )
        assertEquals(
            ScheduleOverrides.Slot(3, 1),
            ScheduleOverrides.followedFrom(plan, 2, 7),
        )
    }

    @Test
    fun `补课那天借源槽位的课`() {
        val courses = listOf(course(name = "高等数学", day = 1, weeks = setOf(3)))
        val plan = ScheduleOverrides.plan(
            listOf(
                ScheduleOverride(
                    fromDate = "2026-09-20",
                    toDate = "2026-09-20",
                    kind = OverrideKind.FOLLOW,
                    sourceWeek = 3,
                    sourceWeekday = 1,
                ),
            ),
            semester,
        )
        // 第 2 周周日（= 9/20）上第 3 周周一的课
        val borrowed = ScheduleCalculator.coursesOnDay(courses, 2, 7, plan)
        assertEquals(listOf("高等数学"), borrowed.map { it.name })
        // 第 2 周周日自己本来没有课
        assertTrue(ScheduleCalculator.coursesOnDay(courses, 2, 7).isEmpty())
    }

    @Test
    fun `停课那天没有课`() {
        val courses = listOf(course(day = 1, weeks = setOf(3)))
        val plan = ScheduleOverrides.plan(
            listOf(
                ScheduleOverride(
                    fromDate = "2026-09-21",
                    toDate = "2026-09-21",
                    kind = OverrideKind.SUSPENDED,
                ),
            ),
            semester,
        )
        assertTrue(ScheduleCalculator.coursesOnDay(courses, 3, 1, plan).isEmpty())
    }

    @Test
    fun `借来的课不受源槽位停课影响`() {
        // 源槽位（第 3 周周一）自己也停课，但第 2 周周日借来的课照旧要上
        val courses = listOf(course(day = 1, weeks = setOf(3)))
        val plan = ScheduleOverrides.plan(
            listOf(
                ScheduleOverride(
                    fromDate = "2026-09-20",
                    toDate = "2026-09-20",
                    kind = OverrideKind.FOLLOW,
                    sourceWeek = 3,
                    sourceWeekday = 1,
                ),
                ScheduleOverride(
                    fromDate = "2026-09-21",
                    toDate = "2026-09-21",
                    kind = OverrideKind.SUSPENDED,
                ),
            ),
            semester,
        )
        assertEquals(1, ScheduleCalculator.coursesOnDay(courses, 2, 7, plan).size)
        assertTrue(ScheduleCalculator.coursesOnDay(courses, 3, 1, plan).isEmpty())
    }

    @Test
    fun `非法与学期外的条目被跳过`() {
        val plan = ScheduleOverrides.plan(
            listOf(
                ScheduleOverride(fromDate = "不是日期", toDate = "也不是", kind = OverrideKind.SUSPENDED),
                // 学期外（开学前）
                ScheduleOverride(fromDate = "2026-08-01", toDate = "2026-08-02", kind = OverrideKind.SUSPENDED),
                // FOLLOW 缺源槽位
                ScheduleOverride(fromDate = "2026-09-20", toDate = "2026-09-20", kind = OverrideKind.FOLLOW),
            ),
            semester,
        )
        assertTrue(plan.isEmpty())
    }

    @Test
    fun `学期缺失时摊平为空`() {
        val plan = ScheduleOverrides.plan(
            listOf(ScheduleOverride(fromDate = "2026-09-20", toDate = "2026-09-20", kind = OverrideKind.SUSPENDED)),
            null,
        )
        assertTrue(plan.isEmpty())
    }

    @Test
    fun `无覆盖时 coursesOnDay 行为与旧版一致`() {
        val courses = listOf(course(day = 1, weeks = setOf(3)))
        assertEquals(1, ScheduleCalculator.coursesOnDay(courses, 3, 1).size)
        assertEquals(0, ScheduleCalculator.coursesOnDay(courses, 4, 1).size)
    }

    @Test
    fun `周网格按列取课含借来的课`() {
        val courses = listOf(course(name = "高等数学", day = 1, weeks = setOf(3)))
        val plan = ScheduleOverrides.plan(
            listOf(
                ScheduleOverride(
                    fromDate = "2026-09-20",
                    toDate = "2026-09-20",
                    kind = OverrideKind.FOLLOW,
                    sourceWeek = 3,
                    sourceWeekday = 1,
                ),
            ),
            semester,
        )
        val cells = ScheduleCalculator.weekColumns(courses, 2, listOf(1, 2, 3, 4, 5, 6, 7), plan)
        // 画在第 7 列（周日），课本身还是周一的课
        assertEquals(1, cells.size)
        assertEquals(7, cells.single().first)
        assertEquals("高等数学", cells.single().second.name)
    }

    @Test
    fun `weeksOf 汇总覆盖涉及的周次`() {
        val weeks = ScheduleOverrides.weeksOf(
            listOf(
                // 第 3 周整周（9/21-9/27）
                ScheduleOverride(fromDate = "2026-09-21", toDate = "2026-09-27", kind = OverrideKind.SUSPENDED),
                // 第 2 周周日（9/20）
                ScheduleOverride(
                    fromDate = "2026-09-20",
                    toDate = "2026-09-20",
                    kind = OverrideKind.FOLLOW,
                    sourceWeek = 5,
                    sourceWeekday = 1,
                ),
            ),
            semester,
        )
        assertEquals(listOf(2, 3), weeks)
        // 学期缺失时不猜
        assertTrue(ScheduleOverrides.weeksOf(listOf(), null).isEmpty())
    }

    @Test
    fun `detail 放假时原有安排有课实际为空`() {
        val courses = listOf(course(name = "高等数学", day = 1, weeks = setOf(3)))
        val overrides = listOf(
            ScheduleOverride(
                fromDate = "2026-09-21",
                toDate = "2026-09-21",
                kind = OverrideKind.SUSPENDED,
                note = "国庆放假",
            ),
        )
        val detail = ScheduleOverrides.detail(3, 1, overrides, courses, semester)!!
        assertTrue(detail.suspended)
        assertEquals(listOf("高等数学"), detail.originalCourses.map { it.name })
        assertTrue(detail.effectiveCourses.isEmpty())
        assertEquals("国庆放假", detail.note)
        assertEquals(java.time.LocalDate.parse("2026-09-21"), detail.date)
    }

    @Test
    fun `detail 补课时给出源槽位与借来的课`() {
        val courses = listOf(course(name = "高等数学", day = 1, weeks = setOf(3)))
        val overrides = listOf(
            ScheduleOverride(
                fromDate = "2026-09-20",
                toDate = "2026-09-20",
                kind = OverrideKind.FOLLOW,
                sourceWeek = 3,
                sourceWeekday = 1,
            ),
        )
        val detail = ScheduleOverrides.detail(2, 7, overrides, courses, semester)!!
        assertTrue(!detail.suspended)
        assertEquals(ScheduleOverrides.Slot(3, 1), detail.source)
        // 周日原来没课，借来了第 3 周周一的
        assertTrue(detail.originalCourses.isEmpty())
        assertEquals(listOf("高等数学"), detail.effectiveCourses.map { it.name })
    }

    @Test
    fun `没有覆盖的槽位 detail 为 null`() {
        assertNull(ScheduleOverrides.detail(1, 1, emptyList(), emptyList(), semester))
    }

    @Test
    fun `covers 命中区间内的每一天`() {
        val override = ScheduleOverride(
            fromDate = "2026-09-21",
            toDate = "2026-09-27",
            kind = OverrideKind.SUSPENDED,
        )
        assertTrue(ScheduleOverrides.covers(override, ScheduleOverrides.Slot(3, 1), semester))
        assertTrue(ScheduleOverrides.covers(override, ScheduleOverrides.Slot(3, 7), semester))
        assertTrue(!ScheduleOverrides.covers(override, ScheduleOverrides.Slot(4, 1), semester))
    }

    @Test
    fun `文案区分单日与区间`() {
        assertEquals(
            "9月20日 按 第3周周一 的课表上课",
            ScheduleOverrides.describe(
                ScheduleOverride(
                    fromDate = "2026-09-20",
                    toDate = "2026-09-20",
                    kind = OverrideKind.FOLLOW,
                    sourceWeek = 3,
                    sourceWeekday = 1,
                ),
                semester,
            ),
        )
        assertEquals(
            "9月25日 至 10月7日 放假",
            ScheduleOverrides.describe(
                ScheduleOverride(fromDate = "2026-09-25", toDate = "2026-10-07", kind = OverrideKind.SUSPENDED),
                semester,
            ),
        )
    }

    @Test
    fun `sameEffect 按语义判等价`() {
        val suspended = ScheduleOverride(fromDate = "2026-10-01", toDate = "2026-10-07", kind = OverrideKind.SUSPENDED)
        // id / note 不参与「是不是同一件事」
        assertTrue(ScheduleOverrides.sameEffect(suspended, suspended.copy(id = 9, note = "换个理由")))
        assertFalse(ScheduleOverrides.sameEffect(suspended, suspended.copy(toDate = "2026-10-08")))

        val follow = ScheduleOverride(
            fromDate = "2026-09-20",
            toDate = "2026-09-20",
            kind = OverrideKind.FOLLOW,
            sourceWeek = 5,
            sourceWeekday = 1,
        )
        assertTrue(ScheduleOverrides.sameEffect(follow, follow.copy(id = 3)))
        assertFalse(ScheduleOverrides.sameEffect(follow, follow.copy(sourceWeekday = 2)))
        assertFalse(ScheduleOverrides.sameEffect(follow, follow.copy(fromDate = "2026-09-21", toDate = "2026-09-21")))
        // 同一天但 kind 不同：不是同一件事
        val suspendedSameDay = ScheduleOverride(fromDate = "2026-09-20", toDate = "2026-09-20", kind = OverrideKind.SUSPENDED)
        assertFalse(ScheduleOverrides.sameEffect(follow, suspendedSameDay))
    }
}
