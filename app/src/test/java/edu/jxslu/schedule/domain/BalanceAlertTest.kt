package edu.jxslu.schedule.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 余额提醒固定口径（DESIGN §3.10 / §3.13）：档位表、换算、阈值判定、每日闸门。
 * 设置页轮选器、DataStore 默认值、后台核对 Worker 三处共用这一份，锁死不变量。
 */
class BalanceAlertTest {

    @Test
    fun `电费档位 10 到 80 步长 5`() {
        val choices = BalanceAlert.POWER_CHOICES
        assertEquals(10, choices.first())
        assertEquals(80, choices.last())
        assertEquals(15, choices.size)
        assertEquals((10..80 step 5).toList(), choices)
    }

    @Test
    fun `一卡通档位 10 到 50 步长 5`() {
        val choices = BalanceAlert.YKT_CHOICES
        assertEquals(10, choices.first())
        assertEquals(50, choices.last())
        assertEquals(9, choices.size)
        assertEquals((10..50 step 5).toList(), choices)
    }

    @Test
    fun `默认阈值都是 20 元`() {
        assertEquals(20, BalanceAlert.DEFAULT_POWER_YUAN)
        assertEquals(20, BalanceAlert.DEFAULT_YKT_YUAN)
        // 默认值必须落在候选表内，否则设置页轮选器初始下标会飘
        assertTrue(BalanceAlert.DEFAULT_POWER_YUAN in BalanceAlert.POWER_CHOICES)
        assertTrue(BalanceAlert.DEFAULT_YKT_YUAN in BalanceAlert.YKT_CHOICES)
    }

    @Test
    fun `阈值夹取`() {
        assertEquals(10, BalanceAlert.coercePowerYuan(0))
        assertEquals(80, BalanceAlert.coercePowerYuan(999))
        assertEquals(20, BalanceAlert.coercePowerYuan(20))
        assertEquals(10, BalanceAlert.coerceYktYuan(-5))
        assertEquals(50, BalanceAlert.coerceYktYuan(999))
    }

    @Test
    fun `存储值到选项下标`() {
        assertEquals(0, BalanceAlert.powerChoiceIndex(10))
        assertEquals(2, BalanceAlert.powerChoiceIndex(20))
        assertEquals(14, BalanceAlert.powerChoiceIndex(80))
        // 夹边：非法值不越界
        assertEquals(0, BalanceAlert.powerChoiceIndex(-1))
        assertEquals(14, BalanceAlert.powerChoiceIndex(81))
        // 非 5 倍数向下取档（23 → 20 那一档）
        assertEquals(2, BalanceAlert.powerChoiceIndex(23))

        assertEquals(0, BalanceAlert.yktChoiceIndex(10))
        assertEquals(8, BalanceAlert.yktChoiceIndex(50))
        assertEquals(8, BalanceAlert.yktChoiceIndex(60))
    }

    @Test
    fun `阈值文案`() {
        assertEquals("低于 ¥20 时提醒", BalanceAlert.powerLabel(20))
        assertEquals("低于 ¥80 时提醒", BalanceAlert.powerLabel(999)) // 夹取后
        assertEquals("低于 ¥10 时提醒", BalanceAlert.yktLabel(10))
    }

    @Test
    fun `电费剩余金额等于电量乘单价`() {
        assertEquals(34.33, BalanceAlert.remainingYuan(55.37, 0.62)!!, 0.005)
        assertEquals(0.0, BalanceAlert.remainingYuan(0.0, 0.62)!!, 0.0)
    }

    @Test
    fun `缺单价或电量不猜`() {
        // 单价取不到时不能按默认单价编一个数出来报警
        assertNull(BalanceAlert.remainingYuan(55.37, null))
        assertNull(BalanceAlert.remainingYuan(null, 0.62))
        assertNull(BalanceAlert.remainingYuan(null, null))
    }

    @Test
    fun `低于阈值才提醒 等于阈值不算低`() {
        assertTrue(BalanceAlert.isLow(8.0, 20))
        assertTrue(BalanceAlert.isLow(19.99, 20))
        // 边界：正好等于阈值不提醒（「低于」= 严格小于）
        assertFalse(BalanceAlert.isLow(20.0, 20))
        assertFalse(BalanceAlert.isLow(20.01, 20))
    }

    @Test
    fun `每天最多一条的日期闸门`() {
        val today = LocalDate.of(2026, 9, 24)
        assertTrue("从未成功检查过 → 该查", BalanceAlert.isDueToday(null, today))
        assertTrue("昨天查过 → 今天该查", BalanceAlert.isDueToday("2026-09-23", today))
        assertFalse("今天查过 → 不再查（也即不再发）", BalanceAlert.isDueToday("2026-09-24", today))
        assertEquals("2026-09-24", BalanceAlert.dateKey(today))
    }
}
