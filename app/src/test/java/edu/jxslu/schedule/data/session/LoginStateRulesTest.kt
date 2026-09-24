package edu.jxslu.schedule.data.session

import org.junit.Assert.assertEquals
import org.junit.Test

/** 状态卡三档推导（DESIGN §3.16「状态判定口径」）。 */
class LoginStateRulesTest {

    @Test
    fun noCredentialNoSession_isNotLoggedIn() {
        assertEquals(
            LoginState.NotLoggedIn,
            LoginStateRules.derive(credentialExists = false, webSessionExists = false, suspended = false),
        )
    }

    @Test
    fun credentialPresent_isLoggedIn() {
        assertEquals(
            LoginState.LoggedIn,
            LoginStateRules.derive(credentialExists = true, webSessionExists = false, suspended = false),
        )
    }

    /** 升级用户：没存凭证但 WebView 会话还有效，不能显示「未登录」。 */
    @Test
    fun webSessionOnly_isLoggedIn() {
        assertEquals(
            LoginState.LoggedIn,
            LoginStateRules.derive(credentialExists = false, webSessionExists = true, suspended = false),
        )
    }

    /** 停用优先：凭证还在也判失效，等用户更新密码。 */
    @Test
    fun suspended_wins() {
        assertEquals(
            LoginState.Expired,
            LoginStateRules.derive(credentialExists = true, webSessionExists = true, suspended = true),
        )
    }
}
