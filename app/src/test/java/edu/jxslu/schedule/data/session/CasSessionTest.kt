package edu.jxslu.schedule.data.session

import edu.jxslu.schedule.data.jw.JwHttpSession.JwHttpException
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import okhttp3.Cookie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 会话编排策略（DESIGN §4.27）。
 *
 * 这里钉的是「防锁号」的每一条闸门——它们错了的代价是用户账号被学校锁定，
 * 所以每条分支都要有对应用例。全部离线跑，不碰网络。
 */
class CasSessionTest {

    private lateinit var store: FakeCredentialStore
    private lateinit var http: FakeCasTransport
    private lateinit var web: FakeWebCookieBridge
    private var now = 0L

    @Before
    fun setUp() {
        store = FakeCredentialStore()
        http = FakeCasTransport()
        web = FakeWebCookieBridge()
        now = 1_700_000_000_000L
        SessionStatus.clear(LoginTarget.Jw)
    }

    private fun newSession(proxyActive: Boolean = false) = CasSession(
        vault = store,
        http = http,
        webCookies = web,
        isProxyActive = { proxyActive },
        clock = { now },
    )

    /**
     * 代理开着时**连请求都不发**：学校对代理出口一律拒绝，试了必然失败，
     * 而失败会白白消耗一次凭证错计数。
     */
    @Test
    fun proxyActive_skipsAttemptAndDoesNotCountFailure() = runBlocking {
        store.cas = SessionCredentials("1", "p")
        val session = newSession(proxyActive = true)
        val result = session.ensureValid()
        assertTrue(result is CasEnsureResult.Failed)
        assertEquals(CasSession.PROXY_HINT, (result as CasEnsureResult.Failed).message)
        assertEquals(0, http.loginCalls)
        assertEquals(0, http.verifyCalls)
        assertEquals(0, store.gate.consecutiveCredentialFailures)

        // 引导里那条路径同样要拦住
        assertTrue(session.tryLogin("1", "p") is CasEnsureResult.Failed)
        assertEquals(0, http.loginCalls)
    }

    @Test
    fun withoutCredential_stopsAtNoCredential() = runBlocking {
        http.cookiePresent = false
        assertEquals(CasEnsureResult.NoCredential, newSession().ensureValid())
        assertEquals(0, http.loginCalls)
    }

    @Test
    fun loginSuccess_returnsReadyAndArmsGate() = runBlocking {
        store.cas = SessionCredentials("2023001", "pw")
        assertEquals(CasEnsureResult.Ready(loggedInNow = true), newSession().ensureValid())
        assertEquals(1, http.loginCalls)
        assertTrue(store.gate.lastSuccessMs > 0)
        assertFalse(store.gate.suspended)
    }

    /** 信任期内第二次调用不再碰网络——这条就是「减少请求」的那一半。 */
    @Test
    fun withinTrustWindow_doesNotTouchNetwork() = runBlocking {
        store.cas = SessionCredentials("1", "p")
        val session = newSession()
        session.ensureValid()
        assertEquals(CasEnsureResult.Ready(), session.ensureValid())
        assertEquals(1, http.loginCalls)
        assertEquals(0, http.verifyCalls)
    }

    /**
     * **信任期内但 jar 是空的 → 必须重新登录。**
     *
     * 钉的是 2026-09-24 的第二个 bug：`last_success` 落盘、cookie 不落盘，进程重启后
     * 时间戳还在而会话已丢。只看时间戳会把「空会话」当「可用会话」，WebView 拿到空 jar、
     * 什么都不注入，用户看到的还是登录页（真机实测：last_success 与当前只差 190 秒）。
     */
    @Test
    fun trustedWindowButEmptyJar_relogins() = runBlocking {
        store.gate = LoginGateRules.afterSuccess(now)   // 刚落盘的成功记录
        store.cas = SessionCredentials("1", "p")
        http.cookiePresent = false                      // 但会话没恢复（进程重启）

        assertEquals(CasEnsureResult.Ready(loggedInNow = true), newSession().ensureValid())
        assertEquals(1, http.loginCalls)
    }

    /** 已登录过（cookie 在）：先探一次，探通不重登。 */
    @Test
    fun existingCookie_probesInsteadOfRelogin() = runBlocking {
        store.cas = SessionCredentials("1", "p")
        http.cookiePresent = true
        http.verifyResult = true
        assertEquals(CasEnsureResult.Ready(), newSession().ensureValid())
        assertEquals(0, http.loginCalls)
        assertEquals(1, http.verifyCalls)
    }

    @Test
    fun existingCookieButDeadSession_fallsBackToLogin() = runBlocking {
        store.cas = SessionCredentials("1", "p")
        http.cookiePresent = true
        http.verifyResult = false
        assertEquals(CasEnsureResult.Ready(loggedInNow = true), newSession().ensureValid())
        assertEquals(1, http.loginCalls)
    }

    /** 探针自身网络失败：不该接着去走登录（那时大概率也连不上，白撞一次）。 */
    @Test
    fun probeNetworkFailure_reportsFailedWithoutLogin() = runBlocking {
        store.cas = SessionCredentials("1", "p")
        http.cookiePresent = true
        http.verifyError = JwHttpException.Network(IOException("boom"))
        val result = newSession().ensureValid()
        assertTrue(result is CasEnsureResult.Failed)
        assertEquals(0, http.loginCalls)
    }

    @Test
    fun firstCredentialFailure_isRetryableNotSuspended() = runBlocking {
        store.cas = SessionCredentials("1", "p")
        http.loginError = JwHttpException.Credential("账号或密码错误")
        assertTrue(newSession().ensureValid() is CasEnsureResult.Failed)
        assertFalse(store.gate.suspended)
        assertEquals(1, store.gate.consecutiveCredentialFailures)
    }

    @Test
    fun secondCredentialFailure_suspendsAndStopsTrying() = runBlocking {
        store.cas = SessionCredentials("1", "p")
        http.loginError = JwHttpException.Credential("账号或密码错误")
        newSession().ensureValid()
        now += LoginGateRules.REPEAT_WINDOW_MS
        assertEquals(CasEnsureResult.Suspended, newSession().ensureValid())
        assertTrue(store.gate.suspended)

        now += LoginGateRules.REPEAT_WINDOW_MS * 10
        assertEquals(CasEnsureResult.Suspended, newSession().ensureValid())
        assertEquals(2, http.loginCalls)
    }

    /** 验证码页：**绝不计数**——密码可能是对的，计到停用等于把用户锁在门外。 */
    @Test
    fun captchaNeverCountsAsFailure() = runBlocking {
        store.cas = SessionCredentials("1", "p")
        http.loginError = JwHttpException.Manual("需要输入验证码")
        repeat(3) {
            assertTrue(newSession().ensureValid() is CasEnsureResult.NeedsManualLogin)
            now += LoginGateRules.REPEAT_WINDOW_MS
        }
        assertFalse(store.gate.suspended)
        assertEquals(0, store.gate.consecutiveCredentialFailures)
    }

    @Test
    fun networkFailureNeverCounts() = runBlocking {
        store.cas = SessionCredentials("1", "p")
        http.loginError = JwHttpException.Network(IOException("net"))
        repeat(3) {
            newSession().ensureValid()
            now += LoginGateRules.REPEAT_WINDOW_MS
        }
        assertFalse(store.gate.suspended)
        assertEquals(0, store.gate.consecutiveCredentialFailures)
    }

    /** 会话不可用时返回 null（静默降级），不抛异常打断调用方。 */
    @Test
    fun fetchHtml_returnsNullWhenSessionUnavailable() = runBlocking {
        http.cookiePresent = false
        assertEquals(null, newSession().fetchHtml("http://jiaowu/xsxx"))
        assertEquals(0, http.fetchHtmlCalls)
    }

    @Test
    fun fetchHtml_returnsBodyWhenSessionReady() = runBlocking {
        store.cas = SessionCredentials("1", "p")
        http.fetchHtmlResult = "<html>学籍卡</html>"
        assertEquals("<html>学籍卡</html>", newSession().fetchHtml("http://jiaowu/xsxx"))
        assertEquals(1, http.fetchHtmlCalls)
    }

    /** 取页面失败也吞掉：它是锦上添花的数据，不该把调用方拖进异常分支。 */
    @Test
    fun fetchHtml_swallowsErrors() = runBlocking {
        store.cas = SessionCredentials("1", "p")
        http.fetchHtmlError = JwHttpException.Protocol("页面结构变了")
        assertEquals(null, newSession().fetchHtml("http://jiaowu/xsxx"))
    }

    /** 刚失败过：窗口内连请求都不发。 */
    @Test
    fun withinRepeatWindow_doesNotAttemptAgain() = runBlocking {
        store.cas = SessionCredentials("1", "p")
        http.loginError = JwHttpException.Network(IOException("net"))
        newSession().ensureValid()
        val again = newSession().ensureValid()
        assertEquals(1, http.loginCalls)
        assertTrue(again is CasEnsureResult.Failed)
    }

    @Test
    fun onCredentialsUpdated_clearsSuspension() = runBlocking {
        store.cas = SessionCredentials("1", "p")
        http.loginError = JwHttpException.Credential("bad")
        newSession().ensureValid()
        now += LoginGateRules.REPEAT_WINDOW_MS
        newSession().ensureValid()
        assertTrue(store.gate.suspended)

        val session = newSession()
        session.onCredentialsUpdated()
        assertFalse(store.gate.suspended)

        http.loginError = null
        assertEquals(CasEnsureResult.Ready(loggedInNow = true), session.ensureValid())
    }

    /** 手登回灌后要续信任期：否则紧接着的 ensureValid 又白登一次。 */
    @Test
    fun adoptFromWebView_armsTrustWindow() = runBlocking {
        web.adoptResult = listOf(
            Cookie.Builder().name("JSESSIONID").value("x")
                .hostOnlyDomain("jiaowu.juwp.edu.cn").path("/").build(),
        )
        val session = newSession()
        session.adoptFromWebView()
        assertEquals(1, web.adoptCalls)
        assertTrue(store.gate.lastSuccessMs > 0)
        assertEquals(CasEnsureResult.Ready(), session.ensureValid())
        assertEquals(0, http.loginCalls)
    }

    @Test
    fun concurrentEnsureValid_logsInOnlyOnce() = runBlocking {
        store.cas = SessionCredentials("1", "p")
        val session = newSession()
        val results = listOf(
            async { session.ensureValid() },
            async { session.ensureValid() },
        ).awaitAll()
        assertTrue(results.all { it is CasEnsureResult.Ready })
        // 只有真正走登录的那一次标 loggedInNow（另一次复用了刚建立的会话）
        assertEquals(1, results.count { (it as CasEnsureResult.Ready).loggedInNow })
        assertEquals(1, http.loginCalls)
    }

    @Test
    fun logout_clearsCookieCredentialAndGate() = runBlocking {
        store.cas = SessionCredentials("1", "p")
        http.cookiePresent = true
        val session = newSession()
        session.ensureValid()
        session.logout()
        assertFalse(http.cookiePresent)
        assertEquals(null, store.cas)
        assertEquals(0, store.gate.consecutiveCredentialFailures)
    }
}

private class FakeCredentialStore(var cas: SessionCredentials? = null) : CredentialStore {
    var gate = LoginGateState()

    override fun readCas(): SessionCredentials? = cas

    override fun clearCas() {
        cas = null
    }

    override fun readGate(target: LoginTarget): LoginGateState = gate

    override fun writeGate(target: LoginTarget, state: LoginGateState) {
        gate = state
    }
}

private class FakeCasTransport : CasTransport {
    var loginCalls = 0
    var verifyCalls = 0
    var fetchHtmlCalls = 0
    var loginError: Throwable? = null
    var verifyError: Throwable? = null
    var fetchHtmlError: Throwable? = null
    var verifyResult = false
    var fetchHtmlResult = ""
    var cookiePresent = false

    override suspend fun login(username: String, password: String) {
        loginCalls++
        // 让出一次，给并发用例制造真正的交错（否则异步分支永远顺序执行）
        yield()
        loginError?.let { throw it }
        cookiePresent = true
    }

    override suspend fun verifySession(): Boolean {
        verifyCalls++
        verifyError?.let { throw it }
        return verifyResult
    }

    override suspend fun fetchHtml(url: String): String {
        fetchHtmlCalls++
        fetchHtmlError?.let { throw it }
        return fetchHtmlResult
    }

    override fun hasCookies(): Boolean = cookiePresent

    override fun cookies(): List<Cookie> = emptyList()

    override fun adoptCookies(cookies: List<Cookie>) {
        cookiePresent = true
    }

    override fun clearCookies() {
        cookiePresent = false
    }

    override fun shutdown() = Unit
}

private class FakeWebCookieBridge : WebCookieBridge {
    var adoptResult: List<Cookie> = emptyList()
    var adoptCalls = 0

    override suspend fun adopt(urls: List<String>): List<Cookie> {
        adoptCalls++
        return adoptResult
    }
}
