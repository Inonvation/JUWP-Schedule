package edu.jxslu.schedule.ui.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 校园卡 / 电费小组件纯逻辑（DESIGN §3.6 三条目改版）：取数闸门、格式化、快照 codec。
 * 编排（DataStore / Glance 状态 / 网络）不在纯逻辑层，不在本文件。
 */
class LifeWidgetModelsTest {

    // ------------------------------------------------------------------
    // CampusBalanceGate：2 小时闸门
    // ------------------------------------------------------------------

    @Test fun `从未成功取数时总是该取`() {
        assertTrue(CampusBalanceGate.shouldFetch(lastSuccessMs = null, nowMs = 1_000L))
        assertTrue(CampusBalanceGate.shouldFetch(lastSuccessMs = null, nowMs = 0L))
    }

    @Test fun `两小时以内不取`() {
        val last = 1_000_000L
        assertFalse(CampusBalanceGate.shouldFetch(last, last + 1))
        assertFalse(CampusBalanceGate.shouldFetch(last, last + CampusBalanceGate.INTERVAL_MS - 1))
    }

    @Test fun `整两小时及以后该取`() {
        val last = 1_000_000L
        assertTrue(CampusBalanceGate.shouldFetch(last, last + CampusBalanceGate.INTERVAL_MS))
        assertTrue(CampusBalanceGate.shouldFetch(last, last + CampusBalanceGate.INTERVAL_MS + 1))
    }

    @Test fun `时钟回拨视为该取`() {
        // 换机恢复备份 / 手动改时间后，now < last：宁多取一次，不出现「卡在两小时外」
        assertTrue(CampusBalanceGate.shouldFetch(lastSuccessMs = 2_000_000L, nowMs = 1_000_000L))
    }

    // ------------------------------------------------------------------
    // LifeWidgetFormat
    // ------------------------------------------------------------------

    @Test fun `分转元文本`() {
        assertEquals("0.00", LifeWidgetFormat.yuan(0))
        assertEquals("128.45", LifeWidgetFormat.yuan(12845))
        assertEquals("0.05", LifeWidgetFormat.yuan(5))
        assertEquals("999.99", LifeWidgetFormat.yuan(99999))
        // 负数不该出现（上游口径保证非负），夹成 0 兜底而不是显示 -0.xx
        assertEquals("0.00", LifeWidgetFormat.yuan(-3))
    }

    @Test fun `元转文本与度数文本`() {
        assertEquals("14.69", LifeWidgetFormat.yuanAmount(14.6886))
        assertEquals("0.00", LifeWidgetFormat.yuanAmount(0.004))
        assertEquals("23.7", LifeWidgetFormat.kwh(23.72))
        assertEquals("5.0", LifeWidgetFormat.kwh(5.0))
        assertEquals("0.62", LifeWidgetFormat.price(0.6234))
    }

    @Test fun `时刻文本无效输入返回null`() {
        assertNull(LifeWidgetFormat.timeLabel(0L))
        assertNull(LifeWidgetFormat.timeLabel(-1L))
        val zone = ZoneId.of("Asia/Shanghai")
        val ms = LocalDate.of(2026, 9, 27).atTime(LocalTime.of(12, 30)).atZone(zone).toInstant().toEpochMilli()
        assertEquals("12:30", LifeWidgetFormat.timeLabel(ms, zone))
    }

    // ------------------------------------------------------------------
    // 快照 codec round-trip
    // ------------------------------------------------------------------

    @Test fun `校园卡快照编解码完整字段`() {
        val snapshot = CampusCardSnapshot(
            cardFen = 12845L,
            accountFen = 1230L,
            fetchedAtMs = 1_790_000_000_000L,
            hasCredentials = true,
            hideBalance = true,
        )
        val decoded = CampusCardSnapshotCodec.decode(CampusCardSnapshotCodec.encode(snapshot))
        assertEquals(snapshot, decoded)
    }

    @Test fun `校园卡快照编解码默认与空值`() {
        val snapshot = CampusCardSnapshot() // 从未取到的首帧态
        val decoded = CampusCardSnapshotCodec.decode(CampusCardSnapshotCodec.encode(snapshot))
        assertEquals(snapshot, decoded)
        assertEquals(-1L, decoded?.cardFen)
        assertEquals(null, decoded?.accountFen)
    }

    @Test fun `电费快照编解码含空读数态`() {
        val full = PowerWidgetSnapshot(roomId = "9A101", remainKwh = 23.72, priceYuan = 0.62, fetchedAtMs = 42L)
        val empty = PowerWidgetSnapshot() // 无读数 / 无单价
        assertEquals(full, PowerWidgetSnapshotCodec.decode(PowerWidgetSnapshotCodec.encode(full)))
        assertEquals(empty, PowerWidgetSnapshotCodec.decode(PowerWidgetSnapshotCodec.encode(empty)))
        assertNull(PowerWidgetSnapshotCodec.decode(null))
        assertNull(PowerWidgetSnapshotCodec.decode("不是 JSON"))
    }
}
