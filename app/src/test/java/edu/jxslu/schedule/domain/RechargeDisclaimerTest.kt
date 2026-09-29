package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 充值免责声明的静默窗口与首次关闭锁（DESIGN §3.13 / §4.19，2026-09-29）。
 */
class RechargeDisclaimerTest {

    private val now = 1_790_700_000_000L

    @Test
    fun suppressUntilAddsSevenDays() {
        assertEquals(now + RechargeDisclaimer.SUPPRESS_MS, RechargeDisclaimer.suppressUntil(now))
        assertEquals(7 * 24L * 60 * 60 * 1000, RechargeDisclaimer.SUPPRESS_MS)
    }

    @Test
    fun isSuppressedOnlyWithinWindow() {
        // 从没勾过 / 过期 = 要弹
        assertFalse(RechargeDisclaimer.isSuppressed(null, now))
        assertFalse(RechargeDisclaimer.isSuppressed(0L, now))
        assertFalse(RechargeDisclaimer.isSuppressed(now, now)) // 到期那一瞬就重新弹
        assertFalse(RechargeDisclaimer.isSuppressed(now - 1, now))
        // 窗口内 = 静默
        assertTrue(RechargeDisclaimer.isSuppressed(now + 1, now))
        assertTrue(RechargeDisclaimer.isSuppressed(RechargeDisclaimer.suppressUntil(now) - 1, now))
    }

    @Test
    fun closeLockAppliesOnlyToFirstEverPopup() {
        // 从未确认过（seenAt = 0/null）：锁 5 秒
        assertEquals(RechargeDisclaimer.FIRST_CLOSE_DELAY_MS, RechargeDisclaimer.closeLockMs(null, now))
        assertEquals(RechargeDisclaimer.FIRST_CLOSE_DELAY_MS, RechargeDisclaimer.closeLockMs(0L, now))
        // 确认过（继续或取消都写 seenAt）：之后任何一次弹出立即可关——哪怕是很久以前确认的
        assertEquals(0L, RechargeDisclaimer.closeLockMs(now - 30 * 24L * 60 * 60 * 1000, now))
        assertEquals(0L, RechargeDisclaimer.closeLockMs(now + 1, now))
    }
}
