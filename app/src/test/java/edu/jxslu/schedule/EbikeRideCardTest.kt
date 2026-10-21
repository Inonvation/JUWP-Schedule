package edu.jxslu.schedule

import edu.jxslu.schedule.domain.EbikeRideCard
import edu.jxslu.schedule.domain.KqcxAuth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/**
 * 开锁前「有没有可用骑行卡」的口径（2026-10-22 用户要求）。
 *
 * 判据要保守：**缺数据不下断言**（到期时间 / 次数缺失都不把卡判死），只有明确
 * 「已过期」或「剩余次数为 0」才算不可用——误报一次「你的卡过期了」比漏报更烦人。
 */
class EbikeRideCardTest {

    private val zone = ZoneOffset.UTC

    /** 2026-10-22 12:00:00 UTC。 */
    private val now = 1_792_670_400_000L

    private fun coupon(
        endAt: String? = "2026-12-31 23:59:59",
        remain: Long? = 10L,
    ) = KqcxAuth.AssetCoupon(
        deviceType = 2,
        remainFrequency = remain,
        freeMinutes = 15L,
        endAt = endAt,
    )

    @Test
    fun `有未过期的卡就算可用`() {
        assertTrue(EbikeRideCard.hasUsableCard(listOf(coupon()), now, zone))
    }

    @Test
    fun `卡已过期不可用`() {
        assertFalse(EbikeRideCard.hasUsableCard(listOf(coupon(endAt = "2026-10-01 00:00:00")), now, zone))
    }

    @Test
    fun `剩余次数为零不可用`() {
        assertFalse(EbikeRideCard.hasUsableCard(listOf(coupon(remain = 0L)), now, zone))
    }

    @Test
    fun `无限次数卡在到期前可用`() {
        assertTrue(EbikeRideCard.hasUsableCard(listOf(coupon(remain = 999_999L)), now, zone))
    }

    @Test
    fun `缺到期时间或次数时不把卡判死`() {
        assertTrue(EbikeRideCard.hasUsableCard(listOf(coupon(endAt = null)), now, zone))
        assertTrue(EbikeRideCard.hasUsableCard(listOf(coupon(endAt = "")), now, zone))
        assertTrue(EbikeRideCard.hasUsableCard(listOf(coupon(remain = null)), now, zone))
    }

    @Test
    fun `有多张卡时任意一张可用就不提醒`() {
        val coupons = listOf(
            coupon(endAt = "2026-10-01 00:00:00"),
            coupon(endAt = "2026-12-31 23:59:59"),
        )
        assertTrue(EbikeRideCard.hasUsableCard(coupons, now, zone))
        assertNull(EbikeRideCard.warningText(coupons, now, zone))
    }

    @Test
    fun `没有卡时的提醒说清后果与确认`() {
        val text = EbikeRideCard.warningText(emptyList(), now, zone)!!
        assertTrue(text.contains("没有骑行卡"))
        assertTrue(text.contains("计费"))
        assertTrue(text.contains("确认要开车吗"))
    }

    @Test
    fun `卡过期时的提醒带最近一张的有效期`() {
        val text = EbikeRideCard.warningText(
            listOf(
                coupon(endAt = "2026-10-01 00:00:00"),
                coupon(endAt = "2026-10-15 08:00:00"),
            ),
            now,
            zone,
        )!!
        assertTrue(text.contains("已过期"))
        assertTrue(text.contains("2026-10-15"))
    }

    @Test
    fun `剩余次数用完时的提醒说明次数`() {
        val text = EbikeRideCard.warningText(listOf(coupon(remain = 0L)), now, zone)!!
        assertTrue(text.contains("次数已用完"))
    }

    @Test
    fun `今日键按本地日历日`() {
        assertEquals("2026-10-22", EbikeRideCard.dayKey(now, zone))
        // 同一天不同时刻同键：勾选后当天剩余时间里都不再提醒
        assertEquals(
            EbikeRideCard.dayKey(now, zone),
            EbikeRideCard.dayKey(now + 6 * 60 * 60 * 1000L, zone),
        )
    }
}
