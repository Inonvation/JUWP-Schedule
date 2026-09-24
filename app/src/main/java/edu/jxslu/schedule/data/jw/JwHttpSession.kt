package edu.jxslu.schedule.data.jw

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import edu.jxslu.schedule.data.session.CasLoginClassifier
import edu.jxslu.schedule.data.session.CasLoginResult
import edu.jxslu.schedule.data.session.CasTransport
import edu.jxslu.schedule.data.session.MemoryCookieJar
import okhttp3.Cookie
import okhttp3.Dns
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * 教务 HTTP 会话（DESIGN §4.17）：OkHttp 复刻 `scripts/jw_session.py` 的登录链路。
 *
 * 链路（2026-09 实测，脚本侧同源）：
 *   [1] 门户落地拿 CAS TGC：GET portal/cas/login_portal → 302 到 CAS 登录页
 *   [2] GET CAS 登录页拿 execution 字段
 *   [3] POST 用户名/密码/execution（302 有 Location = 登录成功）
 *   [4] 预热 https://jiaowu:81/（写 bzb_njw，缺了教务不认）
 *   [5] GET CAS ?service=jiaowu/sso.jsp（不跟随）→ 拿 sso.jsp?ticket=
 *   [6] 手动跟随 302 链落 xsMainV → 会话校验
 *
 * 每次检测都全量重登，不依赖上一次的会话保持——教务会话超时不构成障碍。
 * CAS（eapp2）与教务（jiaowu）的 cookie 在同一 CookieJar 按 host 分桶，互不污染。
 * 失败分类见 [JwHttpException]：凭证错单独计（连续失败会停用自动检测，防触发验证码）。
 */
class JwHttpSession private constructor(
    private val client: OkHttpClient,
    private val jar: MemoryCookieJar,
) : CasTransport {

    sealed class JwHttpException(message: String, cause: Throwable? = null) : Exception(message, cause) {
        /** 账号或密码错误（CAS 应答明确指向凭证）。计数口径见 DESIGN §4.27。 */
        class Credential(message: String) : JwHttpException(message)

        /** 网络/超时/DNS。静默重试口径，不发通知。 */
        class Network(cause: Throwable) : JwHttpException(cause.message ?: "网络错误", cause)

        /** 链路异常：execution 缺失、落点不对、会话校验不过、页面结构变更。 */
        class Protocol(message: String) : JwHttpException(message)

        /**
         * 需要人工在网页里登录：命中验证码特征，或 CAS 返回了认不出的页面（§4.27）。
         *
         * **不计入失败计数**——成因不在密码上，计数只会把对的密码记成错的，
         * 而那正是旧实现（无 Location 一律当凭证错）踩过的坑。
         */
        class Manual(message: String) : JwHttpException(message)
    }

    companion object {
        private const val CAS = "https://eapp2.juwp.edu.cn:9443"
        private const val PORTAL = "http://portal.juwp.edu.cn"
        private const val JW_SSO_SERVICE = "http://jiaowu.juwp.edu.cn/sso.jsp"
        private const val JW_WARMUP = "https://jiaowu.juwp.edu.cn:81/"
        private const val STUDENT_HOME = JwUrls.STUDENT_HOME
        private const val THEORY_SCHEDULE = JwUrls.SCHEDULE_LIST
        private const val LAB_SCHEDULE = JwUrls.LAB_SCHEDULE

        /** 正常主页 ~150KB；退回登录提示页时只有 860 字节。 */
        private const val HOME_MIN_BYTES = 20_000
        private const val NOT_LOGGED = "用户没有登录"
        private const val MAX_REDIRECTS = 15

        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        fun create(): JwHttpSession {
            // jar 单独持一份引用：会话要跨任务常驻（DESIGN §4.27），注入 WebView 与回灌
            // 都要从它取快照，不能再让 client 独占。
            val jar = MemoryCookieJar()
            return JwHttpSession(
                OkHttpClient.Builder()
                    .cookieJar(jar)
                    // 优先 IPv4：校园域名同时有 A 与 AAAA 记录，而校园 IPv6 在移动网络下
                    // 实测是黑（连接要等到 TCP 超时才落回 v4，单步就多等十几到三十秒，
                    // 表现为「开了检测一直转圈」）。IPv4 稳定可达，故显式把 v4 排前面。
                    .dns(Ipv4FirstDns)
                    // 教务链路大量 302，统一手动跟随以便诊断落点
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .connectTimeout(20, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build(),
                jar,
            )
        }

        /**
         * IPv4 优先的 DNS：系统解析结果里只要有 A 记录，就把 v4 排到最前，
         * 让 OkHttp 先连 v4（连不上也好过连 v6 黑洞干等 TCP 超时）。
         *
         * 不丢弃 AAAA——若某域名只有 v6，仍能正常工作；纯逻辑、可 JVM 单测。
         */
        object Ipv4FirstDns : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                ipv4First(Dns.SYSTEM.lookup(hostname))
        }

        /** v4 排前、v6 保留原序殿后；无 v4 时原样返回（见 [Ipv4FirstDns]）。 */
        internal fun ipv4First(all: List<InetAddress>): List<InetAddress> {
            val v4 = all.filterIsInstance<Inet4Address>()
            if (v4.isEmpty()) return all
            return v4 + all.filterNot { it is Inet4Address }
        }

        /**
         * 从理论课表页 HTML 抽学期下拉的选中项 value（xnxq01id 口径，如 2026-2027-1）。
         * 抓不到 selected 时退第一个 option（浏览器默认行为），再抓不到为 null。
         */
        fun extractSelectedTerm(html: String): String? {
            val select = Regex("(?is)<select[^>]*id\\s*=\\s*[\"']?xnxq01id[^>]*>([\\s\\S]*?)</select>")
                .find(html)?.groupValues?.get(1) ?: return null
            val option = Regex("(?is)<option\\b[^>]*>").findAll(select).map { it.value }
                .firstOrNull { "selected" in it.lowercase() }
                ?: Regex("(?is)<option\\b[^>]*>").find(select)?.value
                ?: return null
            Regex("(?i)value\\s*=\\s*[\"']([^\"']*)[\"']").find(option)?.groupValues?.get(1)?.let {
                if (it.isNotBlank()) return it
            }
            return null
        }
    }

    /** 当前会话里的 cookie 快照，用于注入 WebView（DESIGN §4.27）。 */
    override fun cookies(): List<Cookie> = jar.snapshot()

    /** 回灌：用户在 WebView 里手登之后，把 `CookieManager` 的 cookie 抄回会话。 */
    override fun adoptCookies(cookies: List<Cookie>) = jar.adopt(cookies)

    /** 会话里有没有任何 cookie。判断「有没有可复用的登录态」用。 */
    override fun hasCookies(): Boolean = !jar.isEmpty()

    /** 清空会话（退出登录 / 用户更新密码后重登前调用）。 */
    override fun clearCookies() = jar.clear()

    /** 登录 + 会话校验。任一环节失败抛 [JwHttpException]，session 状态不可再用于取数。 */
    override suspend fun login(username: String, password: String) = withContext(Dispatchers.IO) {
        try {
            // [1][2] 门户落地（写 TGC）→ CAS 登录页拿 execution
            getFollowRedirects("$PORTAL/cas/login_portal")
            val loginUrl = "$CAS/cas/login?service=${urlEncode("$PORTAL/cas/login_portal")}"
            val loginPage = getFollowRedirects(loginUrl)
            val execution = Regex("name=\"execution\" value=\"([^\"]+)\"").find(loginPage)?.groupValues?.get(1)
                ?: throw JwHttpException.Protocol("CAS 登录页缺少 execution 字段（页面结构可能已变）")

            // [3] 提交凭证。结果**必须分四类**（DESIGN §4.27）：旧实现把「没有 Location」
            // 一律当凭证错，于是验证码页会把对的密码记成错的，累计到阈值还把账号停用。
            val postForm = FormBody.Builder()
                .add("username", username)
                .add("password", password)
                .add("execution", execution)
                .add("_eventId", "submit")
                .add("geolocation", "")
                .add("submit", "LOGIN")
                .build()
            val casLocation = client.newCall(
                Request.Builder()
                    .url(loginUrl)
                    .header("User-Agent", UA)
                    .post(postForm)
                    .build(),
            ).execute().use { r ->
                val body = runCatching { r.body?.string() }.getOrNull()
                when (val outcome = CasLoginClassifier.classify(r.code, r.header("Location"), body)) {
                    is CasLoginResult.Success -> outcome.location
                    is CasLoginResult.CredentialWrong -> throw JwHttpException.Credential(outcome.message)
                    is CasLoginResult.NeedCaptcha -> throw JwHttpException.Manual(outcome.message)
                    is CasLoginResult.Unknown -> throw JwHttpException.Manual(outcome.message)
                    is CasLoginResult.ServerError -> throw JwHttpException.Protocol(outcome.message)
                }
            }
            getFollowRedirects(casLocation)

            // [4] 预热教务域，写 bzb_njw；[5] 拿 sso ticket
            getFollowRedirects(JW_WARMUP)
            val ssoLocation = getNoRedirect("$CAS/cas/login?service=${urlEncode(JW_SSO_SERVICE)}")
                ?: throw JwHttpException.Protocol("取不到 SSO ticket（CAS 应答无重定向）")

            // [6] 教务侧跟随 302 链落到 xsMainV，再校验会话真的有效
            getFollowRedirects(ssoLocation)
            val home = getFollowRedirects(STUDENT_HOME)
            val homeOk = home.length >= HOME_MIN_BYTES && NOT_LOGGED !in home
            if (!homeOk) throw JwHttpException.Protocol("教务会话无效（已退回登录页）")
        } catch (e: IOException) {
            throw JwHttpException.Network(e)
        } catch (e: JwHttpException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 取消（如调用方的总超时）必须原样抛出：吞掉它会变成 Protocol 异常，
            // 让上层的 withTimeoutOrNull 判不出超时，用户看到的是「链路异常」而非「超时」。
            throw e
        } catch (e: Exception) {
            throw JwHttpException.Protocol("登录链路异常：${e.message}")
        }
    }

    /**
     * 只校验会话还活着：GET 学生主页，按**字节数阈值 + 未登录文案**判定（DESIGN §4.27）。
     *
     * 比拉一次课表页（约 150KB）便宜得多，是 `CasSession.ensureValid` 的探针。
     * 网络层失败照抛 [JwHttpException.Network]，调用方据此区分「会话没了」与「网不通」。
     */
    override suspend fun verifySession(): Boolean = withContext(Dispatchers.IO) {
        try {
            val home = getFollowRedirects(STUDENT_HOME)
            home.length >= HOME_MIN_BYTES && NOT_LOGGED !in home
        } catch (e: IOException) {
            throw JwHttpException.Network(e)
        }
    }

    /** 学期理论课表页（无参数 = 教务当前学期）。中途会话失效按 [JwHttpException.Protocol]。 */
    suspend fun fetchTheoryScheduleHtml(): String = fetchPage(THEORY_SCHEDULE, "个人课表")

    /** 实验课表页；[term] 为 null 时取教务默认学期。 */
    suspend fun fetchLabScheduleHtml(term: String? = null): String =
        fetchPage(
            if (term == null) LAB_SCHEDULE else "$LAB_SCHEDULE?xnxq01id=${urlEncode(term)}",
            "实验课表",
        )

    /**
     * 带会话取任意教务页面（DESIGN §3.3）。
     *
     * 给学籍卡这类「一个 GET 就够」的页面用——不必为它开一个 WebView 走注入 fetch。
     * 会话失效仍按 `Protocol` 抛（页面里出现未登录文案），网络层失败抛 `Network`。
     */
    override suspend fun fetchHtml(url: String): String = withContext(Dispatchers.IO) {
        try {
            val html = getFollowRedirects(url)
            if (NOT_LOGGED in html) {
                throw JwHttpException.Protocol("教务会话已失效（页面退回登录提示）")
            }
            html
        } catch (e: IOException) {
            throw JwHttpException.Network(e)
        }
    }

    private suspend fun fetchPage(url: String, mustContain: String): String = withContext(Dispatchers.IO) {
        try {
            val html = getFollowRedirects(url)
            if (NOT_LOGGED in html) {
                throw JwHttpException.Protocol("教务会话已失效（页面退回登录提示）")
            }
            if (mustContain !in html) {
                throw JwHttpException.Protocol("页面未包含标记「$mustContain」，页面结构可能已变")
            }
            html
        } catch (e: IOException) {
            throw JwHttpException.Network(e)
        }
    }

    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun newGet(url: String): Request =
        Request.Builder().url(url).header("User-Agent", UA).get().build()

    private fun getFollowRedirects(url: String): String {
        var current = url
        repeat(MAX_REDIRECTS) {
            client.newCall(newGet(current)).execute().use { r ->
                val location = r.header("Location")
                if (r.code in 300..399 && location != null) {
                    // 以**当前请求 URL** 为基准解析 Location（可为绝对或相对）。
                    // 根因：此前写成 location.toHttpUrl().resolve(current)，参数反了——
                    // current 是绝对 URL，绝对 URL 解析结果就是它自己，于是原地打转
                    // MAX_REDIRECTS 轮后误报「重定向次数过多」，登录链路永远失败。
                    current = current.toHttpUrl().resolve(location)?.toString()
                        ?: throw JwHttpException.Protocol("无法解析重定向地址：$location")
                    return@use
                }
                if (r.code >= 500) throw JwHttpException.Protocol("$current 返回 HTTP ${r.code}")
                return r.body?.string().orEmpty()
            }
        }
        throw JwHttpException.Protocol("重定向次数过多，链路可能已变")
    }

    private fun getNoRedirect(url: String): String? =
        client.newCall(newGet(url)).execute().use { r ->
            if (r.code in 300..399) r.header("Location") else null
        }

    /** 检测是一次性短任务，用完即弃：释放连接池与线程池，不留常驻资源。 */
    override fun shutdown() {
        runCatching { client.dispatcher.executorService.shutdown() }
        runCatching { client.connectionPool.evictAll() }
    }
}
