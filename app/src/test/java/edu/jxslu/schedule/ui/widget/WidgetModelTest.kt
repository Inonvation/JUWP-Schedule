package edu.jxslu.schedule.ui.widget

import edu.jxslu.schedule.data.DefaultData
import edu.jxslu.schedule.domain.Course
import edu.jxslu.schedule.domain.LocalTimeLike
import edu.jxslu.schedule.domain.SemesterConfig
import edu.jxslu.schedule.domain.buildTodayState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 小组件展示模型的口径。
 *
 * 这些断言锁的是桌面上的观感：
 * - 焦点卡标签与今日页同款（「正在上课 · 第3-4节」）；
 * - 倒计时按「刷新时刻」计算，远于 60 分钟退化为「10:15 上课」；
 * - 今天上完/没课时焦点位给落点文案，明日块只在 4×4（Large）渲染；
 * - 每档尺寸裁多少行由 [WidgetSnapshot.forSize] 决定，截断必须带「还有 N 节」。
 */
class WidgetModelTest {

    /** 第 1 周 = 8/31–9/6，9/17（周四）是第 3 周 */
    private val semester = SemesterConfig(startDate = "2026-08-31", totalWeeks = 20)
    private val slots = DefaultData.defaultTimeSlots

    /** 2026-09-17 是周四 */
    private val thursday = LocalDate.parse("2026-09-17")

    private fun course(id: Long, start: Int, end: Int, name: String = "课$id", day: Int = 4) = Course(
        id = id,
        name = name,
        teacher = "老师$id",
        position = "教学北大楼(北B10$id)",
        day = day,
        startSection = start,
        endSection = end,
        weeks = (1..10).toSet(),
    )

    private fun stateAt(time: LocalTimeLike, courses: List<Course>) = buildTodayState(
        semester, slots, courses, thursday, time,
    )

    // ---------- 焦点：下一节 ----------

    @Test
    fun nextCourse_usesTodayPageLabelAndCountdown() {
        val state = stateAt(LocalTimeLike(7, 0), listOf(course(1, 3, 4)))
        val focus = buildWidgetSnapshot(state).focus as WidgetFocus.Course

        assertEquals("下一节 · 第3-4节", focus.label)
        assertEquals("课1", focus.name)
        // 7:00 距 10:15 还有 195 分钟 > 60 → 退化为「上课时刻」文案
        assertNull(focus.countdown)
        assertEquals("10:15 上课", focus.fallbackNote)
        assertNull(focus.progress)
    }

    @Test
    fun nextCourseWithinHour_showsMinutesCountdown() {
        val state = stateAt(LocalTimeLike(9, 45), listOf(course(1, 3, 4)))
        val focus = buildWidgetSnapshot(state).focus as WidgetFocus.Course

        assertEquals("还有 30 分钟上课", focus.countdown)
        assertNull(focus.fallbackNote)
    }

    // ---------- 焦点：正在上课 ----------

    @Test
    fun ongoingCourse_showsOngoingLabelWithProgress() {
        val state = stateAt(LocalTimeLike(10, 30), listOf(course(1, 3, 4)))
        val focus = buildWidgetSnapshot(state).focus as WidgetFocus.Course

        assertEquals("正在上课 · 第3-4节", focus.label)
        // 细粒度口径优先：10:30 在第 3 节（10:15–10:55）内，说「第3节 · 还有 25 分钟」
        assertEquals("第3节 · 还有 25 分钟下课", focus.countdown)
        assertTrue(focus.progress!! in 0f..1f)
    }

    // ---------- 副行口径：不带时刻（时刻在行首/焦点位，避免出现两遍 10:15） ----------

    @Test
    fun rowMetaHasNoClockAndCompactsPosition() {
        val state = stateAt(LocalTimeLike(7, 0), listOf(course(1, 3, 4), course(2, 5, 6)))
        val snapshot = buildWidgetSnapshot(state)

        // 焦点课在第 1 列（remaining 含焦点，listCourses 不含）
        assertEquals(1, snapshot.rows.size)
        val row = snapshot.rows.first()
        assertEquals("14:00", row.clock)
        assertEquals("@北B102 · 老师2", row.meta)
    }

    // ---------- 尺寸裁剪 ----------

    @Test
    fun forSize_tallKeepsThreeRowsWithMoreLabel() {
        val courses = (1..5).map { course(it.toLong(), 1, 2) }
        val snapshot = buildWidgetSnapshot(stateAt(LocalTimeLike(7, 0), courses))

        val model = snapshot.forSize(WidgetSize.Tall)
        assertEquals(3, model.rows.size)
        assertEquals("还有 1 节", model.rowsMoreLabel)
        assertNull(model.tomorrow)
    }

    @Test
    fun forSize_smallKeepsHeaderButDropsRows() {
        val courses = (1..3).map { course(it.toLong(), 1, 2) }
        val snapshot = buildWidgetSnapshot(stateAt(LocalTimeLike(7, 0), courses))

        val model = snapshot.forSize(WidgetSize.Small)
        assertEquals(0, model.rows.size)
        // 2×2 也带紧凑日期行（2026-09-19 起）；整列不放行时「还有 N 节」一并省掉
        assertTrue(model.header.isNotBlank())
        assertNull(model.rowsMoreLabel)
    }

    @Test
    fun forSize_wideIsDatePlusFocusOnly() {
        val courses = (1..3).map { course(it.toLong(), 1, 2) }
        val snapshot = buildWidgetSnapshot(stateAt(LocalTimeLike(7, 0), courses))

        // 4×2 目录条目：110dp 高放不下焦点卡之外的行
        val model = snapshot.forSize(WidgetSize.Wide)
        assertEquals(0, model.rows.size)
        assertTrue(model.header.isNotBlank())
        assertNull(model.rowsMoreLabel)
        assertNull(model.tomorrow)
    }

    @Test
    fun forSize_largeKeepsAllRowsAndTomorrow() {
        val courses = (1..5).map { course(it.toLong(), 1, 2) }
        val snapshot = buildWidgetSnapshot(stateAt(LocalTimeLike(7, 0), courses))

        val model = snapshot.forSize(WidgetSize.Large)
        // 5 门同时刻课重叠：remaining 里去掉焦点那节剩 4 门，Large 全量显示
        assertEquals(4, model.rows.size)
        assertNull(model.rowsMoreLabel)
    }

    // ---------- 今日上完 / 没课 ----------

    @Test
    fun allDone_focusIsIdleAndTomorrowShownOnLarge() {
        val state = buildTodayState(
            semester, slots,
            listOf(course(1, 3, 4), course(2, 1, 2, day = 5)),
            thursday, LocalTimeLike(20, 0),
        )
        val snapshot = buildWidgetSnapshot(state)

        val focus = snapshot.focus as WidgetFocus.Idle
        assertEquals("今天的课都上完了", focus.title)
        // 明天（周五）有课 → 明日块在 Large 渲染
        val tomorrow = snapshot.tomorrow as WidgetTomorrow.Courses
        assertTrue(tomorrow.rows.isNotEmpty())
        assertTrue(tomorrow.title.startsWith("明天"))

        val small = snapshot.forSize(WidgetSize.Small)
        assertNull(small.tomorrow)
        val large = snapshot.forSize(WidgetSize.Large)
        assertTrue(large.tomorrow is WidgetTomorrow.Courses)
    }

    @Test
    fun todayAndTomorrowBothEmpty_givesRestHint() {
        val state = buildTodayState(
            semester, slots, listOf(course(1, 3, 4)), LocalDate.parse("2026-09-13"), LocalTimeLike(20, 0),
        ) // 9/13 是周日，且周日无课
        val snapshot = buildWidgetSnapshot(state)

        val focus = snapshot.focus as WidgetFocus.Idle
        assertEquals("今天没有课", focus.title)
        val tomorrow = snapshot.tomorrow as WidgetTomorrow.Rest
        assertEquals("没有课，可以放松一下", tomorrow.detail)
    }

    @Test
    fun outOfTerm_showsVacationHint() {
        val state = buildTodayState(
            semester, slots, listOf(course(1, 3, 4)), LocalDate.parse("2027-02-01"), LocalTimeLike(10, 0),
        )
        val snapshot = buildWidgetSnapshot(state)

        val focus = snapshot.focus as WidgetFocus.Idle
        assertEquals("假期中", focus.title)
        assertNull(snapshot.tomorrow)
    }

    // ---------- 尺寸分档 ----------

    @Test
    fun sizeBucketsMatchEntryDefaults() {
        assertEquals(WidgetSize.Small, widgetSizeFor(110, 110))
        assertEquals(WidgetSize.Wide, widgetSizeFor(250, 110))
        assertEquals(WidgetSize.Tall, widgetSizeFor(110, 250))
        assertEquals(WidgetSize.Large, widgetSizeFor(250, 250))
    }

    // ---------- 编解码 ----------

    @Test
    fun snapshotCodec_roundTrips() {
        val snapshot = buildWidgetSnapshot(stateAt(LocalTimeLike(10, 30), listOf(course(1, 3, 4))))
        val raw = WidgetSnapshotCodec.encode(snapshot)
        val decoded = WidgetSnapshotCodec.decode(raw)

        assertEquals(snapshot, decoded)
    }

    @Test
    fun snapshotCodec_garbageYieldsNull() {
        assertNull(WidgetSnapshotCodec.decode("not-json"))
        assertNull(WidgetSnapshotCodec.decode(null))
    }
}
