package edu.jxslu.schedule.ui.water

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 充值深链的契约测试。
 *
 * 小程序 appId、充值页路径与商家店号是 2026-09-28 从官方分享短链
 * （`ur.alipay.com/_…`）的重定向实测解析得到的，写错一个字符就落到别的小程序
 * 或空白页，所以钉死整串（含 page 值的百分号编码形态）。
 */
class QiekjRechargeTest {

    @Test
    fun deepLink_pointsAtQiekjRechargeMiniApp() {
        assertEquals(
            "alipays://platformapi/startapp" +
                "?appId=2018072460764274" +
                "&page=user%2Frecharge%2FbuyGold%2FbuyGold" +
                "%3F__appxPageId%3D7%26shopId%3D202408091502510000078486084837",
            QiekjRecharge.deepLink(),
        )
    }
}
