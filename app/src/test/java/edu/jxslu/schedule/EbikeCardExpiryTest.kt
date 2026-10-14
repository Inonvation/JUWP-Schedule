package edu.jxslu.schedule

import edu.jxslu.schedule.domain.EbikeCardExpiry
import edu.jxslu.schedule.domain.KqcxAuth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 骑行卡券临近到期提醒（[EbikeCardExpiry]）：`endTime` 解析、24 小时窗口边界、
 * 剩余文案、去重键与「该提醒哪些卡」的过滤。纯 JVM，口径见该文件。
 *
 * 时区固定成 [ZONE]（不用系统时区）：`endTime` 不带时区，解析结果依赖本机时区，
 * 用固定 zone 断言才不会随 CI / 开发者机器漂。
 */
class EbikeCardExpiryTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun at(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0, s: Int = 0): Long =
        LocalDateTime.of(y, mo, d, h, mi, s).atZone(zone).toInstant().toEpochMilli()

    private fun coupon(endAt: String?, title: String = "无限次数卡") =
        KqcxAuth.AssetCoupon(
            deviceType = 1,
            remainFrequency = if (title == "无限次数卡") 999_999L else 3L,
            freeMinutes = 15L,
            endAt = endAt,
        )

    @Test
    fun `解析带时分秒的到期时间`() {
        assertEquals(at(2026, 10, 15, 23, 59, 59), EbikeCardExpiry.parseEndAt("2026-10-15 23:59:59", zone))
        // 前后空白容忍
        assertEquals(at(2026, 10, 15, 8, 30, 0), EbikeCardExpiry.parseEndAt("  2026-10-15 08:30:00 ", zone))
    }

    @Test
    fun `只给到日期按当天结束收尾`() {
        assertEquals(at(2026, 10, 15, 23, 59, 59), EbikeCardExpiry.parseEndAt("2026-10-15", zone))
    }

    @Test
    fun `缺数据与非法串回 null`() {
        assertNull(EbikeCardExpiry.parseEndAt(null, zone))
        assertNull(EbikeCardExpiry.parseEndAt("", zone))
        assertNull(EbikeCardExpiry.parseEndAt("   ", zone))
        assertNull(EbikeCardExpiry.parseEndAt("有效期至 2026-10-15", zone))
        assertNull(EbikeCardExpiry.parseEndAt("2026/10/15", zone))
    }

    @Test
    fun `窗口边界`() {
        val now = at(2026, 10, 15, 12, 0, 0)
        // 还剩 1 小时：提醒
        assertTrue(EbikeCardExpiry.isExpiringSoon(at(2026, 10, 15, 13, 0, 0), now))
        // 正好 24 小时：提醒（闭区间）
        assertTrue(EbikeCardExpiry.isExpiringSoon(now + EbikeCardExpiry.WINDOW_MS, now))
        // 超过 24 小时 1 毫秒：不提醒
        assertFalse(EbikeCardExpiry.isExpiringSoon(now + EbikeCardExpiry.WINDOW_MS + 1, now))
        // 已过期（含正好到期那一刻）：不提醒
        assertFalse(EbikeCardExpiry.isExpiringSoon(now, now))
        assertFalse(EbikeCardExpiry.isExpiringSoon(now - 1, now))
        // 没有到期日
        assertFalse(EbikeCardExpiry.isExpiringSoon(null, now))
    }

    @Test
    fun `剩余文案按日历天`() {
        val now = at(2026, 10, 15, 10, 0, 0)
        assertEquals("今天到期", EbikeCardExpiry.remainingText(at(2026, 10, 15, 23, 59, 59), now, zone))
        assertEquals("明天到期", EbikeCardExpiry.remainingText(at(2026, 10, 16, 0, 1, 0), now, zone))
        assertEquals("还剩 3 天", EbikeCardExpiry.remainingText(at(2026, 10, 18, 9, 0, 0), now, zone))
    }

    @Test
    fun `去重键按原始到期串`() {
        assertEquals(EbikeCardExpiry.dedupKey("2026-10-15 23:59:59"), EbikeCardExpiry.dedupKey("2026-10-15 23:59:59"))
        // 换卡（新到期日）换键：上一张的「已提醒」吃不掉这一张
        assertTrue(
            EbikeCardExpiry.dedupKey("2026-10-15 23:59:59") != EbikeCardExpiry.dedupKey("2026-11-15 23:59:59"),
        )
    }

    @Test
    fun `只提醒未通知过且在窗口内的卡`() {
        val now = at(2026, 10, 15, 12, 0, 0)
        val soon = coupon("2026-10-15 23:00:00")       // 窗口内
        val far = coupon("2026-12-01 23:59:59")        // 还早
        val expired = coupon("2026-10-01 23:59:59")    // 已过期
        val noDate = coupon(null)                      // 没有到期日

        val pending = EbikeCardExpiry.pendingReminders(
            listOf(soon, far, expired, noDate),
            notifiedKeys = emptySet(),
            nowMillis = now,
        )
        assertEquals(listOf(soon), pending)

        // 已经提醒过的卡（同 endTime）不再进队列
        val notified = setOf(EbikeCardExpiry.dedupKey("2026-10-15 23:00:00"))
        assertTrue(
            EbikeCardExpiry.pendingReminders(listOf(soon, far), notified, now).isEmpty(),
        )
    }

    @Test
    fun `提醒明细带到期日与剩余`() {
        val now = at(2026, 10, 15, 12, 0, 0)
        val lines = EbikeCardExpiry.reminderLines(listOf(coupon("2026-10-15 23:00:00")), now, zone)
        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("无限次数卡"))
        assertTrue(lines[0].contains("2026-10-15"))
        assertTrue(lines[0].contains("今天到期"))
        // 后果说明写明套餐卡前提（与全 App 口径一致）
        assertTrue(EbikeCardExpiry.REMINDER_NOTE.contains("套餐卡"))
    }
}
