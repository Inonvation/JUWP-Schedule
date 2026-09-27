package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 用水状态与文案换算（DESIGN §3.18）。
 *
 * 金额只认服务端返回的厘值，这里锁的是换算本身——真机上「预扣 0.04 元」显示成
 * 「¥0.04」还是「¥4.00」全靠它。
 */
class QzxyWateringTest {

    @Test
    fun `厘换算成元保留两位`() {
        assertEquals("¥0.04", QzxyWateringFormat.money("40"))
        assertEquals("¥0.00", QzxyWateringFormat.money("0"))
        assertEquals("¥2.00", QzxyWateringFormat.money("2000"))
        assertEquals("¥12.36", QzxyWateringFormat.money("12360"))
    }

    @Test
    fun `金额缺失或不可解析时给兜底文案`() {
        assertEquals("金额待服务端结算", QzxyWateringFormat.money(null))
        assertEquals("金额待服务端结算", QzxyWateringFormat.money(""))
        assertEquals("金额待服务端结算", QzxyWateringFormat.money("—"))
    }

    @Test
    fun `金额两端空白不影响换算`() {
        assertEquals("¥0.04", QzxyWateringFormat.money(" 40 "))
    }

    @Test
    fun `时长不足一小时用分秒`() {
        assertEquals("00:00", QzxyWateringFormat.duration(0L))
        assertEquals("01:15", QzxyWateringFormat.duration(75_000L))
        assertEquals("59:59", QzxyWateringFormat.duration(3_599_000L))
    }

    @Test
    fun `时长超过一小时带上小时位`() {
        assertEquals("1:00:00", QzxyWateringFormat.duration(3_600_000L))
        assertEquals("1:02:05", QzxyWateringFormat.duration(3_725_000L))
    }

    @Test
    fun `负时长按零处理`() {
        assertEquals("00:00", QzxyWateringFormat.duration(-5_000L))
    }

    @Test
    fun `已用时长在起点晚于当前时刻时为零`() {
        val watering = QzxyWatering(
            startedAtMillis = 10_000L,
            deviceAddress = "A4:C1:38:00:00:01",
            deviceName = "测试热水器",
        )
        assertEquals(0L, watering.elapsedMillis(nowMillis = 9_000L))
        assertEquals(5_000L, watering.elapsedMillis(nowMillis = 15_000L))
    }

    @Test
    fun `开阀时刻按二十四小时制显示`() {
        val watering = QzxyWatering(
            startedAtMillis = 1_700_000_000_000L,
            deviceAddress = "A4:C1:38:00:00:01",
            deviceName = "测试热水器",
            preDeductMilli = "2000",
        )
        assertEquals("2000", watering.preDeductMilli)
        val clock = QzxyWateringFormat.clockText(watering.startedAtMillis)
        assertEquals(5, clock.length)
        assertEquals(":", clock.substring(2, 3))
    }
}
