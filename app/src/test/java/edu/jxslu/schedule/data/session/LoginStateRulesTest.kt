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

    /** 身份区标题的兜底：三格里有一个在登录态，就别给「未登录」这个词。 */
    @Test
    fun overall_prefersLoggedInOverExpiredOverNotLoggedIn() {
        assertEquals(
            LoginState.LoggedIn,
            LoginStateRules.overall(LoginState.NotLoggedIn, LoginState.LoggedIn, LoginState.Expired),
        )
        assertEquals(
            LoginState.Expired,
            LoginStateRules.overall(LoginState.NotLoggedIn, LoginState.Expired),
        )
        assertEquals(
            LoginState.NotLoggedIn,
            LoginStateRules.overall(LoginState.NotLoggedIn, LoginState.NotLoggedIn),
        )
    }
}
