package edu.jxslu.schedule

import edu.jxslu.schedule.domain.EbikeUseMode
import edu.jxslu.schedule.domain.capabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 快趣出行「使用方式」（DESIGN §3.9 / §4.32，2026-09-29）。
 *
 * 两档的差异是**能力全集**而不是文案（小程序方式不碰账号、不做写操作；账号方式去掉
 * 微信扫一扫），页面里所有判据都从这里出发，所以默认档、脏值兜底与
 * 两个判据值得钉住。
 */
class EbikeUseModeTest {

    @Test
    fun `默认是微信小程序方式`() {
        // 账号方式要凭证、有计费后果，只能由用户显式选择；默认必须是不需要凭证的那一档
        assertEquals(EbikeUseMode.MiniProgram, EbikeUseMode.Default)
        assertTrue(EbikeUseMode.Default.isMiniProgram)
        assertFalse(EbikeUseMode.Default.isAccount)
    }

    @Test
    fun `存储 id 往返`() {
        EbikeUseMode.entries.forEach { mode ->
            assertEquals(mode, EbikeUseMode.fromId(mode.id))
        }
    }

    @Test
    fun `认不出的存储值一律退回默认档`() {
        // 脏值落回小程序方式：宁可回到不需要凭证、没有写操作的那一档
        assertEquals(EbikeUseMode.Default, EbikeUseMode.fromId(null))
        assertEquals(EbikeUseMode.Default, EbikeUseMode.fromId(""))
        assertEquals(EbikeUseMode.Default, EbikeUseMode.fromId("wechat"))
        assertEquals(EbikeUseMode.Default, EbikeUseMode.fromId("account2"))
    }

    @Test
    fun `存储值大小写不敏感`() {
        assertEquals(EbikeUseMode.Account, EbikeUseMode.fromId("ACCOUNT"))
        assertEquals(EbikeUseMode.MiniProgram, EbikeUseMode.fromId("Mini_Program"))
    }

    @Test
    fun `两个能力判据互斥`() {
        EbikeUseMode.entries.forEach { mode ->
            assertTrue(mode.isAccount != mode.isMiniProgram)
        }
        assertTrue(EbikeUseMode.Account.isAccount)
        assertTrue(EbikeUseMode.MiniProgram.isMiniProgram)
    }

    @Test
    fun `就两档且 id 与显示名不重复`() {
        // 加第三档要先想清能力矩阵（哪些能力归哪一档），所以在这里挡一道
        assertEquals(2, EbikeUseMode.entries.size)
        assertEquals(EbikeUseMode.entries.size, EbikeUseMode.entries.map { it.id }.toSet().size)
        assertEquals(EbikeUseMode.entries.size, EbikeUseMode.entries.map { it.label }.toSet().size)
    }

    // ---------- 能力矩阵（页面与监听器只消费它，别再各自写 if） ----------

    @Test
    fun `小程序方式只出码与扫一扫`() {
        val caps = EbikeUseMode.MiniProgram.capabilities(loggedIn = true, hasRide = true)
        assertTrue(caps.wechatScan)
        assertFalse("小程序方式没有任何 App 内用车", caps.inAppRide)
        assertFalse("小程序方式不给直接开锁", caps.directUnlock)
        // 内置相机扫一扫是账号方式的能力（小程序方式的目标动作是打开微信扫一扫）
        assertFalse("小程序方式不给内置相机扫一扫", caps.cameraScan)
    }

    @Test
    fun `账号方式去掉微信扫一扫`() {
        val caps = EbikeUseMode.Account.capabilities(loggedIn = true, hasRide = false)
        assertFalse("账号方式没有微信扫一扫（保留出码）", caps.wechatScan)
        assertTrue(caps.inAppRide)
        assertTrue(caps.directUnlock)
        // 2026-09-29 加：内置相机扫一扫扫的是车身码，账号方式专有
        assertTrue(caps.cameraScan)
    }

    @Test
    fun `账号方式的直接开锁要求已登录且无在案订单`() {
        assertFalse(EbikeUseMode.Account.capabilities(loggedIn = false, hasRide = false).directUnlock)
        assertFalse(EbikeUseMode.Account.capabilities(loggedIn = true, hasRide = true).directUnlock)
        assertTrue(EbikeUseMode.Account.capabilities(loggedIn = true, hasRide = false).directUnlock)
        // 登录态与订单不影响 inAppRide 本身（骑行卡、账号行、地图用车入口照旧）
        assertTrue(EbikeUseMode.Account.capabilities(loggedIn = false, hasRide = true).inAppRide)
    }

    @Test
    fun `默认档给的是不需要凭证的那一套`() {
        val caps = EbikeUseMode.Default.capabilities()
        assertTrue(caps.wechatScan)
        assertFalse(caps.inAppRide)
        assertFalse(caps.directUnlock)
    }
}
