package edu.jxslu.schedule.data.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缴费平台页面深链（DESIGN §3.13）。
 *
 * 前端只认 URL 里的 `token` 参数与 `#` 后的路由：这几条断言钉住 2026-09-23 实测可用的形态
 * （`/pays?id=181` 房间电费缴费页、`/bill` 账单页），改错就是「打开一片空白」。
 */
class PowerClientUrlTest {

    @Test
    fun payPageUrlCarriesLoginStateAndRoute() {
        val url = PowerClient.payPageUrl("TOKEN", 181)
        assertTrue("登录态走 token 参数", url.contains("token=TOKEN"))
        assertTrue("移动服务平台口径", url.contains("appsourse=ydfwpt"))
        assertTrue("收银台回跳地址需编码", url.contains("paymentUrl=https%3A%2F%2Fyktwx.juwp.edu.cn%2Fplat"))
        assertTrue("落在房间电费缴费页", url.endsWith("#/pays?id=181"))
    }

    @Test
    fun billPageUrlUsesBillRoute() {
        assertTrue(PowerClient.billPageUrl("TOKEN").endsWith("#/bill"))
    }

    @Test
    fun feeItemIdIsPinned() {
        // 平台上只有一个收费项目；改动它等于改电费读数口径，必须是有意的
        assertEquals(181, PowerModels.RECHARGE_FEE_ITEM_ID)
    }
}
