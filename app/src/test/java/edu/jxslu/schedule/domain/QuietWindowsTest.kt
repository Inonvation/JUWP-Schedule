package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 免扰时段纯逻辑的契约（DESIGN §3.7）：
 * - 区间 `[start, end)` 左闭右开；跨零点（start > end）= `[start, 1440) ∪ [0, end)`；
 * - 多窗口取并集；重叠窗口的 [QuietWindows.activeEnd] 取最晚结束点；
 * - 缺键 / 坏 JSON 回预置两时段；仅拒绝 start == end。
 */
class QuietWindowsTest {

    private fun window(
        start: Int,
        end: Int,
        enabled: Boolean = true,
        id: String = "w",
    ) = QuietWindow(id = id, label = "测试", startMinute = start, endMinute = end, enabled = enabled)

    // ---- isQuiet：单窗口边界 ----

    @Test
    fun `起点含终点不含`() {
        val w = listOf(window(750, 810)) // 12:30–13:30
        assertTrue(QuietWindows.isQuiet(w, 750))  // 12:30 起拦
        assertTrue(QuietWindows.isQuiet(w, 809))
        assertFalse(QuietWindows.isQuiet(w, 810)) // 13:30 整放行
        assertFalse(QuietWindows.isQuiet(w, 749))
    }

    @Test
    fun `跨零点窗口首尾都拦中间放行`() {
        val w = listOf(window(1320, 420)) // 22:00–次日 07:00
        assertTrue(QuietWindows.isQuiet(w, 1320)) // 22:00 含
        assertTrue(QuietWindows.isQuiet(w, 1439)) // 23:59
        assertTrue(QuietWindows.isQuiet(w, 0))    // 00:00
        assertTrue(QuietWindows.isQuiet(w, 419))  // 06:59
        assertFalse(QuietWindows.isQuiet(w, 420)) // 07:00 整放行
        assertFalse(QuietWindows.isQuiet(w, 1319)) // 21:59 还没拦
    }

    @Test
    fun `停用窗口不拦`() {
        val w = listOf(window(750, 810, enabled = false))
        assertFalse(QuietWindows.isQuiet(w, 780))
    }

    @Test
    fun `多窗口并集`() {
        val w = listOf(
            window(1320, 420, id = "night"),
            window(750, 810, id = "noon"),
        )
        assertTrue(QuietWindows.isQuiet(w, 780))  // 午休中
        assertTrue(QuietWindows.isQuiet(w, 1380)) // 夜间中
        assertFalse(QuietWindows.isQuiet(w, 600)) // 10:00 两头都不在
    }

    // ---- activeEnd：补发闹钟落点 ----

    @Test
    fun `单窗口取自己的结束点`() {
        assertEquals(420, QuietWindows.activeEnd(listOf(window(1320, 420)), 1380))
    }

    @Test
    fun `重叠窗口取最晚的结束`() {
        val w = listOf(
            window(750, 810, id = "noon"),   // 12:30–13:30
            window(780, 840, id = "wide"),   // 13:00–14:00
        )
        // 13:10 同时落在两个窗口里，补发要等 14:00
        assertEquals(840, QuietWindows.activeEnd(w, 790))
        // 12:40 只在第一个窗口里
        assertEquals(810, QuietWindows.activeEnd(w, 760))
    }

    @Test
    fun `不在任何窗口时返回 null`() {
        assertNull(QuietWindows.activeEnd(listOf(window(750, 810)), 600))
        assertNull(QuietWindows.activeEnd(listOf(window(750, 810, enabled = false)), 780))
    }

    // ---- 序列化 ----

    @Test
    fun `encode decode 往返保留全部字段`() {
        val windows = listOf(
            window(1320, 420, id = "a"),
            window(750, 810, enabled = false, id = "b"),
        )
        val decoded = QuietWindows.decode(QuietWindows.encode(windows))
        assertEquals(windows, decoded)
    }

    @Test
    fun `缺键回预置两时段`() {
        assertEquals(QuietWindows.DEFAULT, QuietWindows.decode(null))
        assertEquals(QuietWindows.DEFAULT, QuietWindows.decode(""))
    }

    @Test
    fun `坏 JSON 回预置不抛`() {
        assertEquals(QuietWindows.DEFAULT, QuietWindows.decode("not-json"))
        assertEquals(QuietWindows.DEFAULT, QuietWindows.decode("{\"x\":1}"))
    }

    @Test
    fun `预置时段是夜间跨零点加午休`() {
        assertEquals(2, QuietWindows.DEFAULT.size)
        val night = QuietWindows.DEFAULT[0]
        assertEquals("夜间休息", night.label)
        assertEquals(22 * 60, night.startMinute)
        assertEquals(7 * 60, night.endMinute)
        assertTrue(night.enabled)
        val noon = QuietWindows.DEFAULT[1]
        assertEquals("午休", noon.label)
        assertEquals(12 * 60 + 30, noon.startMinute)
        assertEquals(13 * 60 + 30, noon.endMinute)
        // 预置时段对今天这样的时间点自洽（两个 id 不同）
        assertTrue(night.id != noon.id)
    }

    // ---- 其他 ----

    @Test
    fun `validate 只拒绝零长区间`() {
        assertTrue(QuietWindows.validate(750, 810))
        assertTrue(QuietWindows.validate(1320, 420)) // 跨零点合法
        assertFalse(QuietWindows.validate(750, 750))
    }

    @Test
    fun `分钟与 HHmm 互转`() {
        assertEquals(750, QuietWindows.minutesFromClockText("12:30"))
        assertEquals("12:30", QuietWindows.clockText(750))
        assertEquals("22:00", QuietWindows.clockText(1320))
        assertEquals("07:00", QuietWindows.clockText(420))
        assertEquals("00:00", QuietWindows.clockText(0))
        // 越界兜底
        assertEquals(0, QuietWindows.minutesFromClockText("25-00"))
        assertEquals(0, QuietWindows.minutesFromClockText("abc"))
    }
}
