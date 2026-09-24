package edu.jxslu.schedule

import edu.jxslu.schedule.domain.EbikeFreeRide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 共享单车免费时长倒计时（DESIGN §3.9）：提醒点、迟到窗口、去重键、提前量吸附、
 * 倒计时格式化、有效期判断。纯 JVM，口径见 [EbikeFreeRide]。
 */
class EbikeFreeRideTest {

    private val start = 1_000_000_000_000L

    /** 起点后第 n 分钟（毫秒）。 */
    private fun minutesAfterStart(n: Long): Long = start + n * 60_000L

    @Test
    fun `免费结束是起点加十五分钟`() {
        assertEquals(minutesAfterStart(15), EbikeFreeRide.freeEndMillis(start))
    }

    @Test
    fun `提前量提醒落在免费结束前 N 分钟`() {
        // lead=3 → 起点后第 12 分钟（免费结束前 3 分钟）
        assertEquals(minutesAfterStart(12), EbikeFreeRide.leadReminderAt(start, 3))
        assertEquals(minutesAfterStart(14), EbikeFreeRide.leadReminderAt(start, 1))
        assertEquals(minutesAfterStart(10), EbikeFreeRide.leadReminderAt(start, 5))
        // 越界吸附，提醒点永远落在计时区间内（不会早于起点）
        assertEquals(minutesAfterStart(14), EbikeFreeRide.leadReminderAt(start, 0))
        assertEquals(minutesAfterStart(10), EbikeFreeRide.leadReminderAt(start, 9))
        assertTrue(EbikeFreeRide.leadReminderAt(start, 5) > start)
    }

    @Test
    fun `结束提醒落在免费结束那一刻`() {
        assertEquals(minutesAfterStart(15), EbikeFreeRide.endReminderAt(start))
    }

    @Test
    fun `下一个提醒时刻取更早的未过点`() {
        // 起点时：两个点都在未来，取提前量点
        assertEquals(
            EbikeFreeRide.leadReminderAt(start, 3),
            EbikeFreeRide.nextReminderAt(start, 3, start),
        )
        // 提前量点已过（第 13 分钟）：只剩结束点
        assertEquals(
            EbikeFreeRide.endReminderAt(start),
            EbikeFreeRide.nextReminderAt(start, 3, minutesAfterStart(13)),
        )
        // 两点都过：没有可排的
        assertNull(EbikeFreeRide.nextReminderAt(start, 3, minutesAfterStart(16)))
        // 恰好等于触发时刻不算「未过」（严格大于才排，免得排一个立刻响的闹钟）
        assertNull(EbikeFreeRide.nextReminderAt(start, 3, EbikeFreeRide.endReminderAt(start)))
    }

    @Test
    fun `提前量提醒只要还没结束就该发`() {
        // 触发点已到、免费时段未结束
        assertTrue(EbikeFreeRide.isLeadDue(start, 3, minutesAfterStart(12)))
        // 被 ROM 推迟到只剩 1 分钟：仍要发（用户拍板 2026-09-24）
        assertTrue(EbikeFreeRide.isLeadDue(start, 3, minutesAfterStart(14)))
        // 触发点还没到
        assertFalse(EbikeFreeRide.isLeadDue(start, 3, minutesAfterStart(11)))
        // 免费时段已结束：交给结束提醒，不再发这一条
        assertFalse(EbikeFreeRide.isLeadDue(start, 3, minutesAfterStart(15)))
        // 无计时
        assertFalse(EbikeFreeRide.isLeadDue(0L, 3, minutesAfterStart(12)))
    }

    @Test
    fun `结束提醒有五分钟迟到窗口`() {
        assertTrue(EbikeFreeRide.isEndDue(start, minutesAfterStart(15)))
        assertTrue(EbikeFreeRide.isEndDue(start, minutesAfterStart(15) + EbikeFreeRide.END_WINDOW_MS - 1))
        // 窗口右端开区间：正好越过就不发
        assertFalse(EbikeFreeRide.isEndDue(start, minutesAfterStart(15) + EbikeFreeRide.END_WINDOW_MS))
        // 还没结束
        assertFalse(EbikeFreeRide.isEndDue(start, minutesAfterStart(14)))
        // 无计时
        assertFalse(EbikeFreeRide.isEndDue(0L, minutesAfterStart(15)))
    }

    @Test
    fun `去重键带上计时起点`() {
        // 同一轮：键稳定（闹钟与周期核对共用，重复核对不会重复发）
        assertEquals(EbikeFreeRide.leadDedupKey(start), EbikeFreeRide.leadDedupKey(start))
        // 换车重新计时：键变了，上一轮的已发记录吃不掉这一轮的提醒
        assertTrue(EbikeFreeRide.leadDedupKey(start) != EbikeFreeRide.leadDedupKey(start + 60_000L))
        // 两个提醒点各自独立
        assertTrue(EbikeFreeRide.leadDedupKey(start) != EbikeFreeRide.endDedupKey(start))
    }

    @Test
    fun `通知 id 不与上课作业提醒相撞`() {
        // 上课 1001 / 1004、作业 1002 / 1003（ui/reminder/ClassReminder.kt）
        val taken = setOf(1001, 1002, 1003, 1004)
        val mine = listOf(
            EbikeFreeRide.NotificationIds.COUNTDOWN,
            EbikeFreeRide.NotificationIds.LEAD,
            EbikeFreeRide.NotificationIds.END,
        )
        assertEquals(mine.size, mine.toSet().size)
        assertTrue(mine.none { it in taken })
        // channel 也不复用首版那个（它的 importance 改不动）
        assertTrue(EbikeFreeRide.NotificationIds.COUNTDOWN_CHANNEL != "ebike_free_ride")
        assertTrue(EbikeFreeRide.NotificationIds.ALERT_CHANNEL != "ebike_free_ride")
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
    fun `提醒文案按实际剩余写`() {
        // 准点触发：还剩 3 分钟
        assertTrue(EbikeFreeRide.leadText(start, minutesAfterStart(12)).contains("3:00"))
        // 迟到触发：文案跟着实际剩余走，不写设定的提前量
        assertTrue(EbikeFreeRide.leadText(start, minutesAfterStart(14)).contains("1:00"))
        // 一律带上运营方口径的免责
        assertTrue(EbikeFreeRide.leadText(start, minutesAfterStart(12)).contains("15"))
        assertTrue(EbikeFreeRide.END_TEXT.contains("15"))
    }

    @Test
    fun `常驻倒计时文案按实际剩余`() {
        assertEquals("免费剩余 15:00", EbikeFreeRide.countdownText(start, start))
        assertEquals("免费剩余 0:30", EbikeFreeRide.countdownText(start, minutesAfterStart(14) + 30_000L))
        // 到点及以后不出现负数（服务在这一刻已退出刷新循环，这里只是防线）
        assertEquals("免费剩余 0:00", EbikeFreeRide.countdownText(start, minutesAfterStart(16)))
    }

    @Test
    fun `换车（起点变了）必须重建常驻倒计时`() {
        val later = start + 60_000L
        // 上一轮还在跑、用户又扫了一辆：起点变了 → 必须重新下发，否则通知栏刷的还是旧剩余时间
        assertTrue(EbikeFreeRide.shouldStartCountdown(start, later))
        // 服务没在跑（runningStartAt = 0）→ 要启动
        assertTrue(EbikeFreeRide.shouldStartCountdown(0L, start))
        // 起点没变（check 每轮都会调一次）→ 跳过，省一次 startForegroundService
        assertFalse(EbikeFreeRide.shouldStartCountdown(start, start))
        // 非法起点 → 不该启动
        assertFalse(EbikeFreeRide.shouldStartCountdown(start, 0L))
        assertFalse(EbikeFreeRide.shouldStartCountdown(0L, 0L))
    }
}
