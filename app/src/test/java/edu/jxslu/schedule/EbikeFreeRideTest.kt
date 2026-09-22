package edu.jxslu.schedule

import edu.jxslu.schedule.domain.EbikeFreeRide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 共享单车免费时长提醒（DESIGN §3.9）：时刻计算、提前量吸附、
 * 倒计时格式化、有效期判断。纯 JVM，口径见 [EbikeFreeRide]。
 */
class EbikeFreeRideTest {

    private val start = 1_000_000_000_000L

    @Test
    fun `提前量默认3触发点在12分钟后`() {
        assertEquals(
            start + 12 * 60_000L,
            EbikeFreeRide.triggerAtMillis(start, 3),
        )
    }

    @Test
    fun `提前量边界 1和5`() {
        assertEquals(start + 14 * 60_000L, EbikeFreeRide.triggerAtMillis(start, 1))
        assertEquals(start + 10 * 60_000L, EbikeFreeRide.triggerAtMillis(start, 5))
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
    fun `通知文案含提前分钟数`() {
        val text = EbikeFreeRide.noticeText(3)
        assertTrue(text.contains("15"))
        assertTrue(text.contains("3"))
    }
}
