package edu.jxslu.schedule.data.jw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 教务导入失败诊断的契约测试。
 *
 * 三条被真机截图钉死的规则（改动前先读 [JwImportDiagnosis] 的类注释）：
 * 1. 认证链上的失败必须回 [JwUrls.SSO_WARMUP]（先写 bzb_njw 的预热入口），
 *    不能重放含 ticket 的 URL；
 * 2. 已登录后的页面失败（如课表页自身 5xx）才允许就地 reload；
 * 3. VPN/代理开着时，正文必须点名「关代理」——这是 2026-09-18 实测的诱因。
 */
class JwImportDiagnosisTest {

    private val ticketUrl =
        "https://eapp2.juwp.edu.cn:9443/cas/login?service=http://jiaowu.juwp.edu.cn/sso.jsp&ticket=ST-1-abc"

    // ---- retryUrl ----

    @Test
    fun retryUrl_casPageGoesBackToEntry() {
        assertEquals(JwUrls.SSO_WARMUP, JwImportDiagnosis.retryUrl(JwUrls.ENTRY))
    }

    @Test
    fun retryUrl_ticketIsNeverReplayed() {
        // CAS ticket 一次性：重放必然失败，必须回入口重新认证
        assertEquals(JwUrls.SSO_WARMUP, JwImportDiagnosis.retryUrl(ticketUrl))
    }

    @Test
    fun retryUrl_ssoJspGoesBackToEntry() {
        assertEquals(JwUrls.SSO_WARMUP, JwImportDiagnosis.retryUrl("http://jiaowu.juwp.edu.cn/sso.jsp?ticket=ST-x"))
    }

    @Test
    fun retryUrl_blankUrlGoesBackToEntry() {
        assertEquals(JwUrls.SSO_WARMUP, JwImportDiagnosis.retryUrl(null))
        assertEquals(JwUrls.SSO_WARMUP, JwImportDiagnosis.retryUrl(""))
    }

    @Test
    fun retryUrl_loggedInPageIsReloadedInPlace() {
        // 课表页自己 5xx 时不该把用户踢回登录页
        assertNull(JwImportDiagnosis.retryUrl(JwUrls.SCHEDULE_LIST))
        assertNull(JwImportDiagnosis.retryUrl(JwUrls.XSD_BASE + "/jsxsd/framework/xsMainV.htmlx"))
    }

    // ---- httpFailure ----

    @Test
    fun httpFailure_500_isTransientAndKeepsPageForReload() {
        val d = JwImportDiagnosis.httpFailure(500, JwUrls.SCHEDULE_LIST, vpnActive = false)
        assertTrue(d.title.contains("500"))
        assertTrue(d.body.contains("瞬时"))
        assertNull("已登录页面 5xx 应就地重放", d.retryUrl)
        assertFalse("未开代理时不该出现代理提示", d.body.contains(JwImportDiagnosis.VPN_HINT))
    }

    @Test
    fun httpFailure_500OnSsoChainGoesBackToEntry() {
        val d = JwImportDiagnosis.httpFailure(500, JwUrls.ENTRY, vpnActive = false)
        assertEquals(JwUrls.SSO_WARMUP, d.retryUrl)
    }

    @Test
    fun httpFailure_404_mentionsProxyExit() {
        val d = JwImportDiagnosis.httpFailure(404, JwUrls.ENTRY, vpnActive = false)
        assertTrue(d.title.contains("404"))
        assertTrue("404 的已知诱因要写进正文", d.body.contains("出口"))
    }

    @Test
    fun httpFailure_reportsUrlSoUserCanTellWhereItFailed() {
        val d = JwImportDiagnosis.httpFailure(500, JwUrls.SCHEDULE_LIST, vpnActive = false)
        assertTrue(d.body.contains(JwUrls.SCHEDULE_LIST))
    }

    // ---- networkFailure ----

    @Test
    fun networkFailure_timeout_namesHostAndIsRetryable() {
        val d = JwImportDiagnosis.networkFailure(
            errorCode = JwImportDiagnosis.ERROR_TIMEOUT,
            description = "net::ERR_CONNECTION_TIMED_OUT",
            url = JwUrls.ENTRY,
            vpnActive = false,
        )
        assertTrue(d.title.contains("超时"))
        assertTrue("要指明连不上哪台机器", d.body.contains("eapp2.juwp.edu.cn:9443"))
        assertTrue(d.body.contains("ERR_CONNECTION_TIMED_OUT"))
        assertEquals(JwUrls.SSO_WARMUP, d.retryUrl)
    }

    @Test
    fun networkFailure_unknownCode_fallsBackWithoutLosingRawInfo() {
        val d = JwImportDiagnosis.networkFailure(
            errorCode = -999,
            description = "some engine error",
            url = "http://jiaowu.juwp.edu.cn:8080/jsxsd/xskb/xskb_list.do?viweType=0",
            vpnActive = false,
        )
        assertTrue(d.title.isNotBlank())
        assertTrue(d.body.contains("some engine error"))
        assertTrue(d.body.contains("jiaowu.juwp.edu.cn"))
    }

    // ---- VPN 提示（2026-09-18 实测诱因）----

    @Test
    fun vpnActive_isNamedInEveryFailureKind() {
        val timeout = JwImportDiagnosis.networkFailure(
            JwImportDiagnosis.ERROR_TIMEOUT, "timeout", JwUrls.ENTRY, vpnActive = true,
        )
        val http500 = JwImportDiagnosis.httpFailure(500, JwUrls.ENTRY, vpnActive = true)
        val session = JwImportDiagnosis.sessionLost(vpnActive = true)
        val blank = JwImportDiagnosis.blankPage(vpnActive = true)
        for (d in listOf(timeout, http500, session, blank)) {
            assertTrue("VPN 开启时必须点名关代理：${d.title}", d.body.contains(JwImportDiagnosis.VPN_HINT))
        }
    }

    // ---- sessionLost / blankPage ----

    @Test
    fun sessionLost_alwaysGoesBackToEntry() {
        val d = JwImportDiagnosis.sessionLost(vpnActive = false)
        assertEquals(JwUrls.SSO_WARMUP, d.retryUrl)
        assertTrue(d.title.contains("会话") || d.title.contains("未登录"))
    }

    @Test
    fun blankPage_alwaysGoesBackToEntry() {
        // 空页没有可重放的内容，回入口重走认证是更稳的路径
        assertEquals(JwUrls.SSO_WARMUP, JwImportDiagnosis.blankPage(vpnActive = false).retryUrl)
    }

    // ---- 登录页识别（会话失效）----

    @Test
    fun looksLikeLoginPage_requiresBothMarkers() {
        // 探针实际返回形如 "loginDiv password title=登录"（见 SESSION_PROBE_JS）。
        // 判据是「与」：单特征误报面大（本判定会拦截后续流程，误报=把正常页当会话失效）。
        assertTrue(JwImportDiagnosis.looksLikeLoginPage("loginDiv password title=登录"))

        // 缺任一特征都不算——曾因探针输出 `password` 而匹配器找 `type="password"`
        // 导致判定恒 false（不报错的静默失效），此断言钉住两边 token 对齐。
        assertFalse(JwImportDiagnosis.looksLikeLoginPage("loginDiv title=登录"))
        assertFalse(JwImportDiagnosis.looksLikeLoginPage("password title=登录"))
    }

    @Test
    fun looksLikeLoginPage_ignoresNormalPagesAndNull() {
        assertFalse(JwImportDiagnosis.looksLikeLoginPage(null))
        assertFalse(JwImportDiagnosis.looksLikeLoginPage(""))
        // 已登录的课表页：探针里不会有 loginDiv / password
        assertFalse(JwImportDiagnosis.looksLikeLoginPage("title=个人课表信息"))
        assertFalse(JwImportDiagnosis.looksLikeLoginPage("title=实验课表"))
        // 普通带密码框的页面（非教务登录页形态）：缺 loginDiv 不判登录页
        assertFalse(JwImportDiagnosis.looksLikeLoginPage("password title=统一身份认证"))
    }

    @Test
    fun looksLikeLoginPage_doesNotRelyOnMissingCopy() {
        // 回归钉：曾用「用户没有登录」判定，而该文案在实测页面上出现 0 次。
        // 若有人把判据改回文案，此断言会失败。
        assertFalse(
            "登录页特征不来自文案，改回文案判定会破坏该契约",
            JwImportDiagnosis.looksLikeLoginPage("用户没有登录"),
        )
    }

    // ---- 自动重试策略（sso.jsp 500 是自愈型）----

    @Test
    fun shouldAutoRetry_sso500IsSelfHealing() {
        // 实测：sso.jsp?ticket= 的 500 响应会写下 bzb_njw，重走预热入口即通过。
        // 这正是用户此前「过一会刷新又可以」的机制，现在自动完成。
        assertTrue(JwImportDiagnosis.shouldAutoRetry(500, "http://jiaowu.juwp.edu.cn/sso.jsp?ticket=ST-x"))
        assertTrue(JwImportDiagnosis.shouldAutoRetry(502, JwUrls.ENTRY))
        assertTrue(JwImportDiagnosis.shouldAutoRetry(503, "https://eapp2.juwp.edu.cn:9443/cas/login"))
    }

    @Test
    fun shouldAutoRetry_notFor4xxOrLoggedInPages() {
        // 4xx 是确定性失败，重试无意义
        assertFalse(JwImportDiagnosis.shouldAutoRetry(404, JwUrls.ENTRY))
        // 已登录页面自身 5xx：不自动打服务端，交给用户显式重试
        assertFalse(JwImportDiagnosis.shouldAutoRetry(500, JwUrls.SCHEDULE_LIST))
        assertFalse(JwImportDiagnosis.shouldAutoRetry(500, null))
    }

    // ---- host 判定（不能整串子串匹配）----

    @Test
    fun hostOf_parsesHostNotQuery() {
        // ENTRY 的 host 是 eapp2，service 参数里才有 jiaowu——整串匹配会误判
        assertEquals("eapp2.juwp.edu.cn", JwUrls.hostOf(JwUrls.ENTRY))
        assertEquals("jiaowu.juwp.edu.cn", JwUrls.hostOf(JwUrls.SCHEDULE_LIST))
        assertEquals("jiaowu.juwp.edu.cn", JwUrls.hostOf("https://jiaowu.juwp.edu.cn:81/"))
        assertNull(JwUrls.hostOf(null))
        assertNull(JwUrls.hostOf("about:blank"))
    }

    // ---- 会话失效只在教务域判定（CAS 登录页含密码框是正常流程）----

    @Test
    fun checkSessionLost_scopeIsJwHostOnly() {
        // UI 层按 JwUrls.isJwHost 收口：eapp2（CAS）与门户的登录页不能被判「会话失效」，
        // 否则用户正在登录时会被错误地盖上「未登录/会话已失效」浮层。
        assertTrue(JwUrls.isJwHost(JwUrls.SCHEDULE_LIST))
        assertFalse(JwUrls.isJwHost(JwUrls.ENTRY))
        assertFalse(JwUrls.isJwHost("https://eapp2.juwp.edu.cn:9443/cas/login"))
        assertFalse(JwUrls.isJwHost("http://portal.juwp.edu.cn/cas/login_portal"))
        // host 精确比较：把 jiaowu 放在 query 里不算教务域
        assertFalse(JwUrls.isJwHost("https://evil.example.com/?x=jiaowu.juwp.edu.cn"))
    }

    // ---- 错误码常量与 WebViewClient 对齐 ----

    @Test
    fun errorCodes_matchWebViewClientValues() {
        // 数值取自 android.webkit.WebViewClient；改错会让诊断走错分支
        assertEquals(-2, JwImportDiagnosis.ERROR_HOST_LOOKUP)
        assertEquals(-5, JwImportDiagnosis.ERROR_PROXY_AUTH)
        assertEquals(-6, JwImportDiagnosis.ERROR_CONNECT)
        assertEquals(-7, JwImportDiagnosis.ERROR_IO)
        assertEquals(-8, JwImportDiagnosis.ERROR_TIMEOUT)
        assertEquals(-9, JwImportDiagnosis.ERROR_REDIRECT_LOOP)
        assertEquals(-11, JwImportDiagnosis.ERROR_FAILED_SSL_HANDSHAKE)
    }
}
