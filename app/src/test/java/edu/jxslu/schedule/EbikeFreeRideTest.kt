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
    fun `点扫一扫后的五秒缓冲内倒计时满格不走`() {
        // 2026-10-05 用户拍板：小程序方式计时起点 = 点击 + 5 秒，缓冲期内倒计时
        // 显示满格 15:00 不动（remainingSeconds 钳上限），5 秒后才开始递减。
        val delayedStart = start + EbikeFreeRide.SCAN_DELAY_MS
        assertEquals(5_000L, EbikeFreeRide.SCAN_DELAY_MS)
        // 缓冲期内（now < 起点）：满格，不出现 15:05 这样的越界值
        assertEquals(15 * 60, EbikeFreeRide.remainingSeconds(delayedStart, start))
        assertEquals(
            "免费剩余 15:00",
            EbikeFreeRide.countdownText(delayedStart, start + 4_999L),
        )
        // 进度条同样满格（elapsed 钳 0）
        assertEquals(1f, EbikeFreeRide.progressFraction(delayedStart, start))
        // 计时在案（缓冲期也算进行中：扫完即焚不删、页面照常显示倒计时块）
        assertTrue(EbikeFreeRide.isActive(delayedStart, start))
        // 缓冲期一过照常递减：免费结束 = 起点 + 15 分钟，不多算那 5 秒
        assertEquals(
            7 * 60 + 30,
            EbikeFreeRide.remainingSeconds(delayedStart, delayedStart + 7 * 60_000L + 30_000L),
        )
        // 免费结束时刻整体后移 5 秒：提醒点跟着后移，不被缓冲吃掉
        assertEquals(
            minutesAfterStart(15) + EbikeFreeRide.SCAN_DELAY_MS,
            EbikeFreeRide.freeEndMillis(delayedStart),
        )
    }

    @Test
    fun `进度条从1递减到0`() {
        assertEquals(1f, EbikeFreeRide.progressFraction(start, start))
        assertEquals(0.5f, EbikeFreeRide.progressFraction(start, start + 7 * 60_000L + 30_000L), 0.001f)
        assertEquals(0f, EbikeFreeRide.progressFraction(start, start + 60 * 60_000L))
    }

    @Test
    fun `通知进度条刻度与剩余比例同源`() {
        // Android 16 的 ProgressStyle：不设 segment 时满刻度是 100，填充 = 剩余时间
        // （官方要求「填充与剩余时间一致」），所以刻度就是 progressFraction × 100。
        assertEquals(100, EbikeFreeRide.progressPercent(start, start))
        assertEquals(50, EbikeFreeRide.progressPercent(start, start + 7 * 60_000L + 30_000L))
        assertEquals(0, EbikeFreeRide.progressPercent(start, start + 60 * 60_000L))
        // 点扫一扫后的缓冲期内满格（钳上限在 remainingSeconds / progressFraction 里）
        assertEquals(
            100,
            EbikeFreeRide.progressPercent(start + EbikeFreeRide.SCAN_DELAY_MS, start),
        )
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
        // 免费时长的前提（2026-10-07 用户要求写明）：前 15 分钟免费是骑行套餐卡的权益
        assertTrue(EbikeFreeRide.leadText(start, start).contains("套餐卡"))
        assertTrue(EbikeFreeRide.END_TEXT.contains("套餐卡"))
        assertTrue(EbikeFreeRide.COUNTDOWN_TEXT.contains("套餐卡"))
    }

    @Test
    fun `常驻倒计时文案按实际剩余`() {
        assertEquals("免费剩余 15:00", EbikeFreeRide.countdownText(start, start))
        assertEquals("免费剩余 0:30", EbikeFreeRide.countdownText(start, minutesAfterStart(14) + 30_000L))
        // 到点及以后不出现负数（服务在这一刻已退出刷新循环，这里只是防线）
        assertEquals("免费剩余 0:00", EbikeFreeRide.countdownText(start, minutesAfterStart(16)))
    }

    @Test
    fun `灵动岛芯片文案压到七字符内`() {
        // Android 16 的 Live Update 芯片宽 96dp，**超过 7 个字符就可能只留图标**
        // （官方芯片规则），所以这条文案不带「免费」二字（2026-10-16）。
        assertEquals("剩15:00", EbikeFreeRide.countdownChipText(start, start))
        assertEquals("剩0:30", EbikeFreeRide.countdownChipText(start, minutesAfterStart(14) + 30_000L))
        // 到点兜底与 countdownText 同源，不出现负数
        assertEquals("剩0:00", EbikeFreeRide.countdownChipText(start, minutesAfterStart(16)))
        // 点扫一扫后的缓冲期内满格不走（钳上限在 remainingSeconds 里）
        val delayedStart = start + EbikeFreeRide.SCAN_DELAY_MS
        assertEquals("剩15:00", EbikeFreeRide.countdownChipText(delayedStart, start))
        assertTrue(EbikeFreeRide.countdownChipText(start, start).length <= 7)
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
