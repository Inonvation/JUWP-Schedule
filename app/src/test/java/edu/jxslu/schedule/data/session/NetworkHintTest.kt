package edu.jxslu.schedule.data.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 取数失败的排查提示契约（DESIGN §7.6 / §4.24）。
 *
 * 两件事会被这里钉住：分支必须跟着 VPN 探测走（开着点名关代理，没开提示换网络），
 * 以及短文案必须塞得进钱包卡那一行。
 */
class NetworkHintTest {

    @Test
    fun `开着 VPN 点名关掉它`() {
        assertEquals(NetworkHint.VPN, NetworkHint.of(vpnActive = true))
        assertEquals(NetworkHint.VPN_BRIEF, NetworkHint.briefOf(vpnActive = true))
        assertTrue(NetworkHint.of(true).contains("VPN"))
    }

    @Test
    fun `没开 VPN 提示换一条网络`() {
        assertEquals(NetworkHint.SWITCH_NETWORK, NetworkHint.of(vpnActive = false))
        assertEquals(NetworkHint.SWITCH_BRIEF, NetworkHint.briefOf(vpnActive = false))
        // 「不用校园网就用手机流量」这条建议必须在文案里，否则用户不知道该换到哪条网
        assertTrue(NetworkHint.SWITCH_NETWORK.contains("校园网"))
        assertTrue(NetworkHint.SWITCH_NETWORK.contains("流量"))
    }

    @Test
    fun `两句分支不能写成同一句`() {
        // 写反了（两份都提示关代理 / 都提示换网络）在这里红
        assertNotEquals(NetworkHint.VPN, NetworkHint.SWITCH_NETWORK)
        assertNotEquals(NetworkHint.VPN_BRIEF, NetworkHint.SWITCH_BRIEF)
    }

    @Test
    fun `短文案要能塞进钱包卡一行`() {
        // 副行是 bodySmall 单行 + 省略号，栏宽约 160dp，实测十来个汉字就会被吃掉。
        // 上限取 12（"VPN" 这类 ASCII 更窄，算字符数已经偏保守）。
        for (text in listOf(NetworkHint.VPN_BRIEF, NetworkHint.SWITCH_BRIEF)) {
            assertTrue("短文案太长会显示成省略号：$text", text.length <= 12)
        }
    }
}
