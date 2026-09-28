package edu.jxslu.schedule.ui.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 开水两卡小组件（胖乖开水 + 趣智开水）纯逻辑：数值解析、副行文案、快照 codec。
 * 编排（DataStore / Glance 状态 / 网络）不在纯逻辑层，不在本文件。
 */
class WaterWidgetModelsTest {

    // ------------------------------------------------------------------
    // WaterWidgetFormat：胖乖
    // ------------------------------------------------------------------

    @Test fun `小票分转元，非法输入返回null`() {
        assertEquals(12.34, WaterWidgetFormat.qiekjTicketYuan("1234")!!, 1e-9)
        assertEquals(0.0, WaterWidgetFormat.qiekjTicketYuan("0")!!, 1e-9)
        assertEquals(12.34, WaterWidgetFormat.qiekjTicketYuan(" 1234 ")!!, 1e-9)
        assertNull(WaterWidgetFormat.qiekjTicketYuan(null))
        assertNull(WaterWidgetFormat.qiekjTicketYuan(""))
        assertNull(WaterWidgetFormat.qiekjTicketYuan("abc"))
    }

    @Test fun `胖乖副行有积分带积分没有只留小票`() {
        assertEquals("小票 · 积分 456", WaterWidgetFormat.qiekjSubLine("456"))
        assertEquals("小票 · 积分 456", WaterWidgetFormat.qiekjSubLine(" 456 "))
        assertEquals("小票", WaterWidgetFormat.qiekjSubLine(null))
        assertEquals("小票", WaterWidgetFormat.qiekjSubLine(""))
    }

    // ------------------------------------------------------------------
    // WaterWidgetFormat：趣智
    // ------------------------------------------------------------------

    @Test fun `趣智余额原文合法才上卡`() {
        assertEquals("12.34", WaterWidgetFormat.qzxyBalanceText("12.34"))
        assertEquals("12.34", WaterWidgetFormat.qzxyBalanceText(" 12.34 "))
        // 服务端全空时 text 退成 "-"：等于没取到，别把 ¥- 渲染上桌面
        assertNull(WaterWidgetFormat.qzxyBalanceText("-"))
        assertNull(WaterWidgetFormat.qzxyBalanceText(""))
        assertNull(WaterWidgetFormat.qzxyBalanceText(null))
    }

    @Test fun `用水中副行按渲染时刻算分钟`() {
        val now = 1_000_000_000L
        // 12 分钟前开阀
        assertEquals(
            "已 12 分钟",
            WaterWidgetFormat.qzxyWateringLine(deviceName = null, startedAtMs = now - 12 * 60_000L, nowMs = now),
        )
        assertEquals(
            "热水器-学生公寓-3层-301 · 已 12 分钟",
            WaterWidgetFormat.qzxyWateringLine(
                deviceName = "热水器-学生公寓-3层-301",
                startedAtMs = now - 12 * 60_000L,
                nowMs = now,
            ),
        )
        // 不足 1 分钟 / 起点在未来（时钟偏差）：都算刚开阀
        assertEquals("刚开阀", WaterWidgetFormat.qzxyWateringLine(null, now - 30_000L, now))
        assertEquals("设备 · 刚开阀", WaterWidgetFormat.qzxyWateringLine("设备", now + 60_000L, now))
        // 设备名只有空白时退成纯时长
        assertEquals("已 5 分钟", WaterWidgetFormat.qzxyWateringLine("  ", now - 5 * 60_000L, now))
    }

    // ------------------------------------------------------------------
    // 快照 codec round-trip
    // ------------------------------------------------------------------

    @Test fun `胖乖卡快照编解码完整字段`() {
        val snapshot = QiekjWaterSnapshot(
            loggedIn = true,
            ticketYuan = 12.34,
            points = "456",
            fetchedAtMs = 1_790_000_000_000L,
        )
        assertEquals(snapshot, QiekjWaterSnapshotCodec.decode(QiekjWaterSnapshotCodec.encode(snapshot)))
    }

    @Test fun `胖乖卡快照编解码默认与空值`() {
        val snapshot = QiekjWaterSnapshot() // 未登录 + 从未取到的首帧态
        val decoded = QiekjWaterSnapshotCodec.decode(QiekjWaterSnapshotCodec.encode(snapshot))
        assertEquals(snapshot, decoded)
        assertNull(decoded?.ticketYuan)
        assertNull(QiekjWaterSnapshotCodec.decode(null))
        assertNull(QiekjWaterSnapshotCodec.decode("不是 JSON"))
    }

    @Test fun `趣智卡快照编解码完整字段`() {
        val snapshot = QzxyWaterSnapshot(
            loggedIn = true,
            balanceText = "12.34",
            fetchedAtMs = 1_790_000_000_000L,
            wateringStartedAtMs = 1_790_000_000_000L,
            wateringDeviceName = "热水器-3层-301",
            lastDeviceName = "热水器-3层-301",
        )
        assertEquals(snapshot, QzxyWaterSnapshotCodec.decode(QzxyWaterSnapshotCodec.encode(snapshot)))
    }

    @Test fun `趣智卡快照编解码默认与空值`() {
        val snapshot = QzxyWaterSnapshot()
        val decoded = QzxyWaterSnapshotCodec.decode(QzxyWaterSnapshotCodec.encode(snapshot))
        assertEquals(snapshot, decoded)
        assertNull(decoded?.balanceText)
        assertNull(decoded?.wateringStartedAtMs)
        assertNull(QzxyWaterSnapshotCodec.decode(null))
        assertNull(QzxyWaterSnapshotCodec.decode("不是 JSON"))
    }
}
