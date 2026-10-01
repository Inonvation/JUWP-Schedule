package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** U净 订单纯逻辑（DESIGN §4.37 P2）：状态映射 / 启停取消窗口 / 受理判定 / 倒计时文案。 */
class UjingOrderStateTest {

    // ---- 状态映射 ----

    @Test
    fun statusTextCoversKnownStates() {
        assertEquals("待支付", UjingState.statusText("10"))
        assertEquals("已支付 · 待启动", UjingState.statusText("20"))
        assertEquals("自洁启动中", UjingState.statusText("22"))
        assertEquals("自洁完成", UjingState.statusText("35"))
        assertEquals("洗涤中", UjingState.statusText("40"))
        assertEquals("已完成", UjingState.statusText("50"))
        assertEquals("已取消", UjingState.statusText("53"))
        assertEquals("已取消", UjingState.statusText("60"))
        // 未知状态不编文案
        assertEquals("状态 99", UjingState.statusText("99"))
    }

    // ---- 窗口 ----

    @Test
    fun startWindowMatchesCommunitySpec() {
        // 启动窗口只有 20 / 22 / 35（社区实测）
        assertTrue(UjingState.canStart("20"))
        assertTrue(UjingState.canStart("22"))
        assertTrue(UjingState.canStart("35"))
        assertFalse(UjingState.canStart("10"))
        assertFalse(UjingState.canStart("40"))
        assertFalse(UjingState.canStart("50"))
    }

    @Test
    fun cancelWindowOnlyBeforePaid() {
        assertTrue(UjingState.canCancel("0"))
        assertTrue(UjingState.canCancel("10"))
        assertTrue(UjingState.canCancel("17"))
        // 20 起机器已锁定
        assertFalse(UjingState.canCancel("20"))
        assertFalse(UjingState.canCancel("40"))
    }

    @Test
    fun terminalStates() {
        assertTrue(UjingState.isTerminal("50"))
        assertTrue(UjingState.isTerminal("51"))
        assertTrue(UjingState.isTerminal("52"))
        assertTrue(UjingState.isTerminal("53"))
        assertTrue(UjingState.isTerminal("60"))
        assertFalse(UjingState.isTerminal("40"))
        assertFalse(UjingState.isTerminal("20"))
    }

    // ---- 云端控制受理 ----

    @Test
    fun commandAcceptedShapes() {
        // 普通 0 受理
        assertTrue(UjingState.commandAccepted(0, null))
        assertTrue(UjingState.commandAccepted(0, -1))
        // 1703 是设备控制的受理码，内层 errorCode == 0 才算成功
        assertTrue(UjingState.commandAccepted(1703, 0))
        assertFalse(UjingState.commandAccepted(1703, 1))
        assertFalse(UjingState.commandAccepted(1703, null))
        assertFalse(UjingState.commandAccepted(-1, 0))
    }

    // ---- 倒计时文案 ----

    @Test
    fun remainTextFormats() {
        assertEquals("35 分 0 秒", UjingState.remainText(2100))
        // 不足一分钟不带「0 分」前缀
        assertEquals("42 秒", UjingState.remainText(42))
        // 负数（本地时钟追上服务端）夹到 0
        assertEquals("0 秒", UjingState.remainText(-5))
    }
}
