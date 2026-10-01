package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 抢课节流与停止条件（DESIGN §4.36）。 */
class SelectionGrabPolicyTest {

    @Test
    fun coerceIntervalClampsToRange() {
        assertEquals(SelectionGrabPolicy.INTERVAL_MIN_MS, SelectionGrabPolicy.coerceInterval(1))
        assertEquals(SelectionGrabPolicy.INTERVAL_MAX_MS, SelectionGrabPolicy.coerceInterval(999_999))
        assertEquals(15_000L, SelectionGrabPolicy.coerceInterval(15_000L))
    }

    @Test
    fun nextDelayBacksOffLinearlyAndCaps() {
        val base = SelectionGrabPolicy.INTERVAL_DEFAULT_MS
        assertEquals(base, SelectionGrabPolicy.nextDelay(base, 0))
        assertEquals(base * 2, SelectionGrabPolicy.nextDelay(base, 1))
        assertEquals(base * 3, SelectionGrabPolicy.nextDelay(base, 2))
        // 封顶：10s × 21 已被夹到 60s
        assertEquals(SelectionGrabPolicy.INTERVAL_MAX_MS, SelectionGrabPolicy.nextDelay(base, 20))
    }

    @Test
    fun intervalChoicesAndIndex() {
        assertEquals("10 秒", SelectionGrabPolicy.intervalLabel(10_000L))
        assertEquals(SelectionGrabPolicy.INTERVAL_CHOICES_MS.size, SelectionGrabPolicy.INTERVAL_CHOICES_MS.toSet().size)
        assertEquals(1, SelectionGrabPolicy.intervalChoiceIndex(10_000L))
        // 脏数据（7 秒不在档位表）回默认档下标，不编最近值
        assertEquals(
            SelectionGrabPolicy.INTERVAL_CHOICES_MS.indexOf(SelectionGrabPolicy.INTERVAL_DEFAULT_MS),
            SelectionGrabPolicy.intervalChoiceIndex(7_000L),
        )
    }

    @Test
    fun allMatchedBeatsRoundEnd() {
        // 最后一门刚好在截止瞬间抢到：报「完成」而不是「截止」
        val state = GrabState(
            startedAt = 0,
            pendingWishIds = emptySet(),
            roundEndAt = 100,
        )
        assertEquals(GrabStop.AllMatched, SelectionGrabPolicy.shouldStop(state, 200))
    }

    @Test
    fun stopsOnRoundEnd() {
        val state = GrabState(startedAt = 0, pendingWishIds = setOf("w"), roundEndAt = 100)
        assertNull(SelectionGrabPolicy.shouldStop(state, 99))
        assertEquals(GrabStop.RoundEnded, SelectionGrabPolicy.shouldStop(state, 100))
    }

    @Test
    fun stopsAfterTooManyFailures() {
        val state = GrabState(
            startedAt = 0,
            consecutiveFailures = SelectionGrabPolicy.MAX_CONSECUTIVE_FAILURES,
            pendingWishIds = setOf("w"),
        )
        assertEquals(GrabStop.TooManyFailures, SelectionGrabPolicy.shouldStop(state, 1))
    }

    @Test
    fun stopsOnTimeLimitWhenRoundEndUnknown() {
        val state = GrabState(startedAt = 0, pendingWishIds = setOf("w"), roundEndAt = null)
        assertNull(SelectionGrabPolicy.shouldStop(state, SelectionGrabPolicy.MAX_SESSION_MS - 1))
        assertEquals(
            GrabStop.TimeLimit,
            SelectionGrabPolicy.shouldStop(state, SelectionGrabPolicy.MAX_SESSION_MS),
        )
    }
}
