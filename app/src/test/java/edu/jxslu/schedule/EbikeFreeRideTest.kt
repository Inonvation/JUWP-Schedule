package edu.jxslu.schedule

import edu.jxslu.schedule.domain.EbikeFreeRide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 共享单车免费时长倒计时（DESIGN §3.9）：事件锚点、提醒偏移、提前量吸附、
 * 倒计时格式化、有效期判断。纯 JVM，口径见 [EbikeFreeRide]。
 */
class EbikeFreeRideTest {

    private val start = 1_000_000_000_000L

    @Test
    fun `日历事件锚在免费结束时刻`() {
        assertEquals(start + 15 * 60_000L, EbikeFreeRide.freeEndMillis(start))
    }

    @Test
    fun `两条提醒偏移 提前量与事件开始`() {
        // 事件开始 = 免费结束，所以「提前 3 分钟」＝免费结束前 3 分钟响，第二条在结束那一刻
        assertEquals(listOf(3, 0), EbikeFreeRide.reminderOffsets(3))
        assertEquals(listOf(1, 0), EbikeFreeRide.reminderOffsets(1))
        assertEquals(listOf(5, 0), EbikeFreeRide.reminderOffsets(5))
        // 越界走吸附，不出现 0 或负数（MINUTES 非负是日历 Provider 的硬要求）
        assertEquals(listOf(1, 0), EbikeFreeRide.reminderOffsets(0))
        assertEquals(listOf(5, 0), EbikeFreeRide.reminderOffsets(9))
    }

    @Test
    fun `提醒时刻都不早于计时起点`() {
        val freeEnd = EbikeFreeRide.freeEndMillis(start)
        EbikeFreeRide.reminderOffsets(4).forEach { minutes ->
            assertTrue(freeEnd - minutes * 60_000L >= start)
        }
        // lead=4 → 11 分钟后响（免费结束前 4 分钟）
        assertEquals(start + 11 * 60_000L, freeEnd - 4 * 60_000L)
    }

    @Test
    fun `提前量越界吸附到 1到5`() {
        assertEquals(1, EbikeFreeRide.coerceLead(0))
        assertEquals(1, EbikeFreeRide.coerceLead(-3))
        assertEquals(5, EbikeFreeRide.coerceLead(6))
        assertEquals(4, EbikeFreeRide.coerceLead(4))
    }

    @Test
    fun `剩余秒数不过界`() {
        // 刚开始：整 15 分钟
        assertEquals(15 * 60, EbikeFreeRide.remainingSeconds(start, start))
        // 7.5 分钟后：剩一半
        assertEquals(7 * 60 + 30, EbikeFreeRide.remainingSeconds(start, start + 7 * 60_000L + 30_000L))
        // 到点及以后：0，不为负
        assertEquals(0, EbikeFreeRide.remainingSeconds(start, start + 15 * 60_000L))
        assertEquals(0, EbikeFreeRide.remainingSeconds(start, start + 60 * 60_000L))
    }

    @Test
    fun `进度条从1递减到0`() {
        assertEquals(1f, EbikeFreeRide.progressFraction(start, start))
        assertEquals(0.5f, EbikeFreeRide.progressFraction(start, start + 7 * 60_000L + 30_000L), 0.001f)
        assertEquals(0f, EbikeFreeRide.progressFraction(start, start + 60 * 60_000L))
    }

    @Test
    fun `倒计时文案`() {
        assertEquals("15:00", EbikeFreeRide.formatRemaining(900))
        assertEquals("12:07", EbikeFreeRide.formatRemaining(12 * 60 + 7))
        assertEquals("0:00", EbikeFreeRide.formatRemaining(0))
        // 负数兜底为 0
        assertEquals("0:00", EbikeFreeRide.formatRemaining(-5))
    }

    @Test
    fun `有效期判断`() {
        assertTrue(EbikeFreeRide.isActive(start, start))
        assertTrue(EbikeFreeRide.isActive(start, start + 14 * 60_000L))
        assertFalse(EbikeFreeRide.isActive(start, start + 15 * 60_000L))
        // 无计时（起点为 0）恒无效
        assertFalse(EbikeFreeRide.isActive(0L, start))
    }

    @Test
    fun `事件描述含免费时长与提前分钟数`() {
        val text = EbikeFreeRide.eventDescription(3)
        assertTrue(text.contains("15"))
        assertTrue(text.contains("3"))
    }
}
