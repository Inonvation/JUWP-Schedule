package edu.jxslu.schedule.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 选课轮的容错时间解析、状态判定与提醒点（DESIGN §4.35）。用固定时区，结果与机器时区无关。 */
class SelectionRoundsTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private fun dayStart(y: Int, mo: Int, d: Int): Long =
        LocalDate.of(y, mo, d).atStartOfDay(zone).toInstant().toEpochMilli()

    // ---- 时间解析 ----

    @Test
    fun parsesDateTimeRange() {
        val (start, end) = SelectionRounds.parseTimeRange(
            "2026-09-28 08:00 ~ 2026-10-05 17:00",
            zone,
        )
        assertEquals(at(2026, 9, 28, 8, 0), start)
        assertEquals(at(2026, 10, 5, 17, 0), end)
    }

    @Test
    fun parsesDateTimeRangeWithSecondsAndChineseSeparators() {
        val (start, end) = SelectionRounds.parseTimeRange(
            "2026年9月28日 08:00:00 至 2026年10月5日 17:00:00",
            zone,
        )
        assertEquals(at(2026, 9, 28, 8, 0), start)
        assertEquals(at(2026, 10, 5, 17, 0), end)
    }

    @Test
    fun parsesDateOnlyRangeAsWholeDays() {
        val (start, end) = SelectionRounds.parseTimeRange("2026-09-28~2026-10-05", zone)
        assertEquals(dayStart(2026, 9, 28), start)
        // 结束按当日 23:59:59.999：截止当天还算在窗口里
        assertEquals(dayStart(2026, 10, 6) - 1, end)
    }

    @Test
    fun singleTimeIsStartOnly() {
        val (start, end) = SelectionRounds.parseTimeRange("2026-09-28 08:00", zone)
        assertEquals(at(2026, 9, 28, 8, 0), start)
        assertNull(end)
    }

    @Test
    fun unparseableTextYieldsNulls() {
        val (start, end) = SelectionRounds.parseTimeRange("另行通知", zone)
        assertNull(start)
        assertNull(end)
    }

    // ---- 状态 ----

    @Test
    fun phaseFollowsTimes() {
        val round = SelectionRound(
            id = "A",
            name = "第一轮",
            startAt = at(2026, 9, 28, 8, 0),
            endAt = at(2026, 10, 5, 17, 0),
        )
        assertEquals(
            SelectionRoundPhase.Upcoming,
            SelectionRounds.phase(round, at(2026, 9, 27, 12, 0)),
        )
        assertEquals(
            SelectionRoundPhase.Active,
            SelectionRounds.phase(round, at(2026, 9, 30, 12, 0)),
        )
        assertEquals(
            SelectionRoundPhase.Ended,
            SelectionRounds.phase(round, at(2026, 10, 6, 0, 0)),
        )
    }

    @Test
    fun phaseUnknownWithoutTimes() {
        val round = SelectionRound(id = "A", name = "第一轮")
        assertEquals(SelectionRoundPhase.Unknown, SelectionRounds.phase(round, 0L))
        // 只有开始时间且已过：不编「进行中」，保持 Unknown
        val onlyStart = SelectionRound(id = "B", name = "第二轮", startAt = 1000L)
        assertEquals(SelectionRoundPhase.Unknown, SelectionRounds.phase(onlyStart, 2000L))
    }

    // ---- 学期后继 ----

    // ---- 目标轮次挑选 ----

    @Test
    fun pickTargetPrefersActiveThenNearestUpcoming() {
        val now = at(2026, 9, 20, 12, 0)
        val ended = SelectionRound(id = "E", name = "已结束", startAt = now - 10 * HOUR, endAt = now - HOUR)
        val upcomingLater = SelectionRound(id = "U2", name = "晚", startAt = now + 48 * HOUR, endAt = null)
        val upcomingSooner = SelectionRound(id = "U1", name = "早", startAt = now + 2 * HOUR, endAt = null)
        val active = SelectionRound(id = "A", name = "进行中", startAt = now - HOUR, endAt = now + HOUR)

        assertEquals("A", SelectionRounds.pickTarget(listOf(ended, upcomingLater, active), now)?.id)
        // 没有进行中的：取最近的即将开始；已结束的不算目标
        assertEquals(
            "U1",
            SelectionRounds.pickTarget(listOf(ended, upcomingLater, upcomingSooner), now)?.id,
        )
        assertNull(SelectionRounds.pickTarget(listOf(ended), now))
    }

    @Test
    fun nextTermSteps() {
        assertEquals("2026-2027-2", SelectionRounds.nextTerm("2026-2027-1"))
        assertEquals("2027-2028-1", SelectionRounds.nextTerm("2026-2027-2"))
        assertNull(SelectionRounds.nextTerm("2026"))
        assertNull(SelectionRounds.nextTerm("2026-2027-3"))
    }

    // ---- 提醒点 ----

    @Test
    fun reminderPointsCarryLeadsAndKeys() {
        val now = at(2026, 9, 20, 12, 0)
        val round = SelectionRound(
            id = "A1",
            name = "第一轮选课",
            startAt = now + 2 * HOUR,
            endAt = now + 10 * HOUR,
        )
        val points = SelectionRounds.reminderPoints(listOf(round), now)
        assertEquals(2, points.size)
        // 开始前 30 分钟 → now + 1.5h；截止前 6 小时 → now + 4h
        assertEquals(now + 2 * HOUR - 30 * MINUTE, points[0].at)
        assertEquals(now + 2 * HOUR, points[0].eventAt)
        assertEquals("A1|start", points[0].key)
        assertEquals(SelectionRounds.ReminderPoint.Kind.StartSoon, points[0].kind)
        assertEquals(now + 10 * HOUR - 6 * HOUR, points[1].at)
        assertEquals(now + 10 * HOUR, points[1].eventAt)
        assertEquals("A1|end", points[1].key)
    }

    @Test
    fun reminderPointsClampPastLeadToNow() {
        val now = at(2026, 9, 20, 12, 0)
        // 距截止只剩 3 小时（提前量已过但没结束）→ 钳到 now，本轮检查立刻发
        val round = SelectionRound(id = "A2", name = "补选", startAt = null, endAt = now + 3 * HOUR)
        val points = SelectionRounds.reminderPoints(listOf(round), now)
        assertEquals(1, points.size)
        assertEquals(now, points[0].at)
        assertEquals(SelectionRounds.ReminderPoint.Kind.EndSoon, points[0].kind)
    }

    @Test
    fun reminderPointsSkipEndedAndUnknown() {
        val now = at(2026, 9, 20, 12, 0)
        val rounds = listOf(
            SelectionRound(id = "A3", name = "已结束", startAt = now - 3 * HOUR, endAt = now - HOUR),
            SelectionRound(id = "A4", name = "没时间"),
        )
        assertTrue(SelectionRounds.reminderPoints(rounds, now).isEmpty())
    }

    @Test
    fun reminderPointsSortedByTime() {
        val now = at(2026, 9, 20, 12, 0)
        val rounds = listOf(
            SelectionRound(id = "B", name = "晚", endAt = now + 100 * HOUR),
            SelectionRound(id = "A", name = "早", endAt = now + 20 * HOUR),
        )
        val points = SelectionRounds.reminderPoints(rounds, now)
        assertEquals(listOf("A|end", "B|end"), points.map { it.key })
    }

    private companion object {
        const val MINUTE = 60 * 1000L
        const val HOUR = 60 * MINUTE
    }
}
