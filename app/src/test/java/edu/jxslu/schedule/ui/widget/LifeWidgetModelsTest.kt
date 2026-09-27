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
 * 生活小组件（校园卡 + 电费合并卡）纯逻辑（DESIGN §3.6 二条目改版）：取数闸门、
 * 副行文案格式化、快照 codec。
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
    // 窄档排版阈值（真机 MIUI 实测：2 格宽 150dp、4 格宽 344dp）
    // ------------------------------------------------------------------

    @Test fun `窄档判定按宽度分档`() {
        assertTrue(isNarrowWidth(150f))          // 2 格宽，实测值
        assertTrue(isNarrowWidth(199f))
        assertFalse(isNarrowWidth(WidgetNarrowWidthDp))
        assertFalse(isNarrowWidth(344f))         // 4 格宽，实测值
    }

    @Test fun `大数字字号随宽度降档`() {
        assertEquals(24, bigNumberFontSp(110f))
        // 150dp 是 2 格宽的实测值，落在字号阈值的上界：走 30sp
        assertEquals(30, bigNumberFontSp(150f))
        assertEquals(30, bigNumberFontSp(344f))
    }

    // ------------------------------------------------------------------
    // 电费副行文案（合并卡，宽窄两档）
    // ------------------------------------------------------------------

    @Test fun `电费副行宽窄两档文案`() {
        val full = PowerWidgetSnapshot(remainKwh = 23.72, priceYuan = 0.62)
        assertEquals(
            "寝室电费 23.7 度 · 折合 ¥14.71（0.62 元/度）",
            LifeWidgetFormat.powerLineText(full, narrow = false),
        )
        assertEquals("电费 23.7 度 · ¥14.71", LifeWidgetFormat.powerLineText(full, narrow = true))
    }

    @Test fun `电费副行缺读数或缺单价`() {
        assertEquals("寝室电费 · 暂无读数", LifeWidgetFormat.powerLineText(PowerWidgetSnapshot(), narrow = false))
        assertEquals("电费 · 暂无读数", LifeWidgetFormat.powerLineText(PowerWidgetSnapshot(), narrow = true))
        // 单价缺失只报度数，不按默认单价编（BalanceAlert.remainingYuan 同口径）
        val noPrice = PowerWidgetSnapshot(remainKwh = 5.0)
        assertEquals("寝室电费 5.0 度", LifeWidgetFormat.powerLineText(noPrice, narrow = false))
        assertEquals("电费 5.0 度", LifeWidgetFormat.powerLineText(noPrice, narrow = true))
    }

    // ------------------------------------------------------------------
    // 快照 codec round-trip
    // ------------------------------------------------------------------

    @Test fun `合并卡快照编解码完整字段`() {
        val snapshot = LifeCardSnapshot(
            campus = CampusCardSnapshot(
                cardFen = 12845L,
                fetchedAtMs = 1_790_000_000_000L,
                hasCredentials = true,
                hideBalance = true,
            ),
            power = PowerWidgetSnapshot(roomId = "1-101", remainKwh = 23.72, priceYuan = 0.62, fetchedAtMs = 42L),
        )
        assertEquals(snapshot, LifeCardSnapshotCodec.decode(LifeCardSnapshotCodec.encode(snapshot)))
    }

    @Test fun `合并卡快照编解码默认与空值`() {
        val snapshot = LifeCardSnapshot() // 从未取到的首帧态
        val decoded = LifeCardSnapshotCodec.decode(LifeCardSnapshotCodec.encode(snapshot))
        assertEquals(snapshot, decoded)
        // 余额 -1 = 从未取到；电费为 null = 无读数
        assertEquals(-1L, decoded?.campus?.cardFen)
        assertNull(decoded?.power?.remainKwh)
        assertNull(LifeCardSnapshotCodec.decode(null))
        assertNull(LifeCardSnapshotCodec.decode("不是 JSON"))
    }
}
