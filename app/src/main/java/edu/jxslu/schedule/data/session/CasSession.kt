package edu.jxslu.schedule.data.session

import edu.jxslu.schedule.data.jw.JwHttpSession
import edu.jxslu.schedule.data.jw.JwHttpSession.JwHttpException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** `CasSession.ensureValid` 的结果。UI 按它分支——三种引导的去处完全不同。 */
sealed interface CasEnsureResult {

    /**
     * 会话可用。
     *
     * [loggedInNow] 区分两种来源：`true` = 本次真的走了一遍登录（cookie 是刚拿到的）；
     * `false` = 复用已有会话，或落在 10 分钟可信期内。它只用于文案与日志区分——
     * **没有「刚登录所以要注入 WebView」这一步了**：注入已被真机证伪，WebView 的会话
     * 由 `JwAutoLogin` 填表提交自己拿（DESIGN §4.27）。
     */
    data class Ready(val loggedInNow: Boolean = false) : CasEnsureResult

    /** 没存过统一认证密码，只能走 WebView 手登。 */
    data object NoCredential : CasEnsureResult

    /** 凭证连续出错、已停用：等用户更新密码，别再自动尝试（防锁号）。 */
    data object Suspended : CasEnsureResult

    /** 需要人工在网页里登一次（验证码页 / 认不出的页面）。 */
    data class NeedsManualLogin(val message: String) : CasEnsureResult

    /** 网络或平台侧问题，稍后还能再试。 */
    data class Failed(val message: String) : CasEnsureResult
}

/**
 * 学校统一认证（CAS）会话的唯一持有者（DESIGN §4.27）。
 *
 * 覆盖教务、学工、签章三个系统——它们共用一套 CAS，但**各自的业务会话要各自建立**
 * （教务 `JSESSIONID`、学工 localStorage、签章 `sid`）。本类只负责 CAS 那一层。
 *
 * 三条策略：
 * 1. **可信期**：最近一次成功在 `LoginGateRules.SESSION_TRUST_MS` 内直接放行，不发请求；
 * 2. **先探后登**：有 cookie 时先 `verifySession`（学生主页，约 150KB 的那个是课表页），
 *    探通就续上信任期，比完整走一遍登录便宜；
 * 3. **闸门**：连续凭证错到阈值即停用，且**只有凭证错计数**（验证码/网络/5xx 都不计）。
 *
 * 并发由 [loginMutex] 串行化：「5 分钟内不重复登录」只是时间窗，两个协程同时撞到失效
 * 仍会并发登两次（`YktRepository.loginMutex` 是同一手法）。
 */
class CasSession(
    private val vault: CredentialStore,
    private val http: CasTransport = JwHttpSession.create(),
    private val webCookies: WebCookieBridge = WebViewCookieBridge,
    /**
     * 代理 / VPN 探测（DESIGN §4.27 兜底矩阵）。
     *
     * 学校对直连出口与代理出口区别对待：开着第三方 VPN 时 CAS 超时、SSO 落点 500。
     * 命中时**直接返回、连请求都不发**——省一次必然失败的往返，也避免把「代理的锅」
     * 记到凭证头上（那会白消耗一次失败计数）。
     *
     * 抽成函数只为可测：真实现要 Context（`JwVpnDetector.isVpnActive`）。
     */
    private val isProxyActive: () -> Boolean = { false },
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val loginMutex = Mutex()

    /**
     * 保证 CAS 会话可用。**这是所有教务侧取数的唯一入口。**
     *
     * 返回 [CasEnsureResult.Ready] 即可以放心发业务请求；其余分支由调用方决定
     * 是转 WebView、提示更新密码，还是稍后重试。
     */
    suspend fun ensureValid(): CasEnsureResult = loginMutex.withLock {
        if (isProxyActive()) return CasEnsureResult.Failed(PROXY_HINT)
        val now = clock()
        var gate = vault.readGate(LoginTarget.Jw)
        SessionStatus.syncSuspended(LoginTarget.Jw, gate.suspended)
        if (gate.suspended) return CasEnsureResult.Suspended
        // 信任期**必须同时要求会话还在**：`last_success` 落盘、cookie 不落盘，
        // 进程重启后时间戳还在而 jar 已空。只看时间戳就会把「空会话」当「可用会话」，
        // 于是 WebView 拿到一个空 jar、什么都不注入，用户看到的还是登录页
        // （2026-09-24 实测：last_success 与当前只差 190 秒，但会话早没了）。
        if (LoginGateRules.sessionTrusted(gate, now) && http.hasCookies()) {
            return CasEnsureResult.Ready()
        }

        val credentials = vault.readCas()

        // 有 cookie 就先探：探通比完整登录便宜，探不通再走登录。
        // 探针的失败要全接住（它和登录一样会抛 Network / Protocol），否则异常会穿透
        // 到调用方——那里只当它是「会话不可用」，反而把真实原因丢了。
        if (http.hasCookies()) {
            val verified = try {
                http.verifySession()
            } catch (e: JwHttpException) {
                vault.writeGate(LoginTarget.Jw, LoginGateRules.afterTransientFailure(gate, clock()))
                return CasEnsureResult.Failed(e.message ?: "会话校验失败")
            }
            if (verified) {
                gate = LoginGateRules.afterSuccess(clock())
                vault.writeGate(LoginTarget.Jw, gate)
                SessionStatus.syncSuspended(LoginTarget.Jw, false)
                return CasEnsureResult.Ready()
            }
        }

        if (credentials == null) return CasEnsureResult.NoCredential
        if (!LoginGateRules.canAttempt(gate, now)) {
            return CasEnsureResult.Failed("刚刚登录失败过，请稍后再试")
        }

        return try {
            http.login(credentials.username, credentials.password)
            vault.writeGate(LoginTarget.Jw, LoginGateRules.afterSuccess(clock()))
            SessionStatus.syncSuspended(LoginTarget.Jw, false)
            CasEnsureResult.Ready(loggedInNow = true)
        } catch (e: JwHttpException.Credential) {
            val next = LoginGateRules.afterCredentialFailure(gate, clock())
            vault.writeGate(LoginTarget.Jw, next)
            SessionStatus.syncSuspended(LoginTarget.Jw, next.suspended)
            if (next.suspended) CasEnsureResult.Suspended
            else CasEnsureResult.Failed(e.message ?: "统一认证登录失败")
        } catch (e: JwHttpException.Manual) {
            // 验证码 / 认不出的页面：停手转人工，**不计失败**
            vault.writeGate(LoginTarget.Jw, LoginGateRules.afterTransientFailure(gate, clock()))
            CasEnsureResult.NeedsManualLogin(e.message ?: "需要在网页里重新登录一次")
        } catch (e: JwHttpException.Network) {
            vault.writeGate(LoginTarget.Jw, LoginGateRules.afterTransientFailure(gate, clock()))
            CasEnsureResult.Failed(e.message ?: "网络不可用")
        } catch (e: JwHttpException.Protocol) {
            vault.writeGate(LoginTarget.Jw, LoginGateRules.afterTransientFailure(gate, clock()))
            CasEnsureResult.Failed(e.message ?: "教务链路异常")
        }
    }

    /**
     * 用**给定**凭证试登一次（引导第 2 步，DESIGN §3.16）。
     *
     * 与 [ensureValid] 的区别：那个读已存凭证、受信任期约束；这里是「验证用户刚敲进去的
     * 密码」，所以先清掉旧 cookie，确保成功真的来自这份凭证而不是残留会话。
     * 失败分类口径不变：凭证错计数、验证码/网络/5xx 都不计。
     *
     * 成功后由调用方落库（本类拿不到写凭证的权限，那是 [CredentialStore] 的写侧）。
     */
    suspend fun tryLogin(username: String, password: String): CasEnsureResult = loginMutex.withLock {
        if (isProxyActive()) return CasEnsureResult.Failed(PROXY_HINT)
        val gate = vault.readGate(LoginTarget.Jw)
        try {
            http.clearCookies()
            http.login(username, password)
            vault.writeGate(LoginTarget.Jw, LoginGateRules.afterSuccess(clock()))
            SessionStatus.syncSuspended(LoginTarget.Jw, false)
            CasEnsureResult.Ready(loggedInNow = true)
        } catch (e: JwHttpException.Credential) {
            val next = LoginGateRules.afterCredentialFailure(gate, clock())
            vault.writeGate(LoginTarget.Jw, next)
            SessionStatus.syncSuspended(LoginTarget.Jw, next.suspended)
            if (next.suspended) CasEnsureResult.Suspended
            else CasEnsureResult.Failed(e.message ?: "统一认证登录失败")
        } catch (e: JwHttpException.Manual) {
            vault.writeGate(LoginTarget.Jw, LoginGateRules.afterTransientFailure(gate, clock()))
            CasEnsureResult.NeedsManualLogin(e.message ?: "需要在网页里登录一次")
        } catch (e: JwHttpException.Network) {
            vault.writeGate(LoginTarget.Jw, LoginGateRules.afterTransientFailure(gate, clock()))
            CasEnsureResult.Failed(e.message ?: "网络不可用")
        } catch (e: JwHttpException.Protocol) {
            vault.writeGate(LoginTarget.Jw, LoginGateRules.afterTransientFailure(gate, clock()))
            CasEnsureResult.Failed(e.message ?: "教务链路异常")
        }
    }

    /**
     * 带会话取一个教务页面；会话不可用或取数失败返回 **null**（静默降级）。
     *
     * 给「我的」页补抓班级用（DESIGN §3.3）：抓不到就维持现状（只显示学号），
     * 一个锦上添花的字段不该把页面弄成错误态，也不该打断任何主流程。
     */
    suspend fun fetchHtml(url: String): String? {
        if (ensureValid() !is CasEnsureResult.Ready) return null
        return runCatching { http.fetchHtml(url) }.getOrNull()
    }

    /**
     * 用户在 WebView 里手登成功后调用：把 cookie 抄回会话，并续上信任期。
     *
     * 不记这一步的话，手登完紧接着的 `ensureValid` 又会去走一次登录——白撞一次风控。
     */
    suspend fun adoptFromWebView(urls: List<String> = ADOPT_URLS) {
        val cookies = webCookies.adopt(urls)
        if (cookies.isNotEmpty()) http.adoptCookies(cookies)
        vault.writeGate(LoginTarget.Jw, LoginGateRules.afterSuccess(clock()))
        SessionStatus.syncSuspended(LoginTarget.Jw, false)
    }

    /** 用户更新了统一认证密码：清零闸门，允许重新尝试。 */
    fun onCredentialsUpdated() {
        vault.writeGate(LoginTarget.Jw, LoginGateRules.reset())
        SessionStatus.syncSuspended(LoginTarget.Jw, false)
        // 凭证换过了，自动填表那道的 5 分钟节流也该放开——否则刚改完密码还得手动登一次。
        SessionStatus.clearAutoLoginThrottle()
    }

    /** 退出教务登录：清会话与凭证（一卡通那套不受影响）。 */
    fun logout() {
        http.clearCookies()
        vault.clearCas()
        onCredentialsUpdated()
    }

    /** 进程退出时释放连接池与线程池。会话本身是进程级的，别在任务结束时调。 */
    fun shutdown() = http.shutdown()

    companion object {
        /** 代理出口的提示。比 `JwImportDiagnosis.VPN_HINT` 短——引导页要给输入框留位置。 */
        const val PROXY_HINT = "检测到 VPN/代理：学校对代理出口会拒绝，请先关掉再试"

        /**
         * 回灌时要读的域。
         *
         * 覆盖 CAS 与三个业务系统：教务业务在 `:8080`、预热在 `:81`、SSO 回跳在根域，
         * 三者是**不同的 URL 但同一个 host**，`getCookie` 按 URL 取，所以都要列上。
         */
        val ADOPT_URLS: List<String> = listOf(
            "https://eapp2.juwp.edu.cn:9443/",
            "http://jiaowu.juwp.edu.cn/sso.jsp",
            "https://jiaowu.juwp.edu.cn:81/",
            "http://jiaowu.juwp.edu.cn:8080/",
            "https://xgxt.juwp.edu.cn/",
            "http://jwxyxx.juwp.edu.cn/ptwork/",
        )
    }
}
