package edu.jxslu.schedule.ui.qzxy

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 充值深链的契约测试（DESIGN §3.18）。
 *
 * 深链里的小程序 appId 与页面路径是 2026-09-27 从官方分享链的重定向实测得到的，
 * 写错一个字符就落到别的小程序或空白页，所以钉死整串。
 */
class QzxyRechargeTest {

    @Test
    fun deepLink_pointsAtQzxyRechargeMiniApp() {
        assertEquals(
            "alipays://platformapi/startapp" +
                "?appId=2018090661238647" +
                "&page=pages/recharge/recharge",
            QzxyRecharge.deepLink(),
        )
    }
}
