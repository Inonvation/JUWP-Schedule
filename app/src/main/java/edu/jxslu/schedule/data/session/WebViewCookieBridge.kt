package edu.jxslu.schedule.data.session

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * WebView 会话 → OkHttp 会话的**单向回灌**（DESIGN §4.27）。
 *
 * **只回灌，不注入。** 2026-09-24 真机定性：把 OkHttp 登录拿到的 cookie 注入
 * `CookieManager` 这条路走不通——cookie 确实写进去了（逐条读回验证 7/7 条落位），
 * 但 WebView 发请求时就是不带（属性缺 `SameSite=None`，跨站跳转不带）。
 *
 * 登录现在交给 WebView 自己完成（`JwAutoLogin` 填表提交，cookie 由 CAS 亲自下发），
 * 这里只把它**用出来的**会话抄回 OkHttp，好让冷启动预登录少登一次。
 *
 * 抽接口是为了让 `CasSession.adoptFromWebView` 能在 JVM 单测里跑——真实现要
 * `android.webkit.CookieManager`，单测里只有 stub，一调就炸。
 */
interface WebCookieBridge {
    suspend fun adopt(urls: List<String>): List<Cookie>

    /**
     * 这些域里有没有**会话标识**（[SessionCookieRules]）。只读 `CookieManager`，不发请求。
     *
     * `adopt` 会把站点标记（`bzb_njw` 这类）一起抄回来，值不值得抄先由它判：
     * 抄一份没有会话的 cookie 只会让 `CasSession` 白探一次教务。
     */
    fun hasAnyCookie(urls: List<String> = CasSession.ADOPT_URLS): Boolean

    /**
     * 清掉 WebView 的全部 cookie（2026-09-28，退出登录用）。
     *
     * `CookieManager` 没有按域删除的公开 API（`removeAllCookies` 是整库、
     * `setCookie` 只能覆写单条且要求完整的过期属性拼装）。取**整库清空**：
     * App 内 WebView 只访问学校域（教务导入 / 学工表单 / 成绩单授权三个入口），
     * 整库清掉不会误伤第三方登录态；各入口下次打开时按需重建会话。
     *
     * 不清这里的代价：用户「退出登录并清除密码」后，WebView 里留着的手登会话
     * 仍然有效——导入页探针通过照常直进教务，「我的」页状态卡也一直「已登录」，
     * 退出形同虚设。
     */
    suspend fun clearAll()
}

/** 真机实现：`CookieManager` 的读写都要在有 Looper 的线程上，所以全部包 `Dispatchers.Main`。 */
object WebViewCookieBridge : WebCookieBridge {

    /**
     * 从 `CookieManager` 抄回白名单域的 cookie。
     *
     * `getCookie(url)` 只给 `Cookie:` 头原文（`a=1; b=2`），没有 Domain / Path / Secure，
     * 所以 domain 从 URL 推、path 记 `/`、secure 按 URL 的协议推。这足以在 OkHttp 侧
     * 匹配得上——我们需要的只是「带着它去请求同一个域」。
     */
    override suspend fun adopt(urls: List<String>): List<Cookie> = withContext(Dispatchers.Main) {
        val manager = CookieManager.getInstance()
        urls.flatMap { url ->
            val parsed = url.toHttpUrlOrNull() ?: return@flatMap emptyList()
            val header = runCatching { manager.getCookie(url) }.getOrNull()
            if (header.isNullOrBlank()) return@flatMap emptyList()
            header.split(';').mapNotNull { part ->
                val name = part.substringBefore('=', "").trim()
                if (name.isEmpty()) return@mapNotNull null
                val value = part.substringAfter('=', "").trim()
                runCatching {
                    Cookie.Builder()
                        .name(name)
                        .value(value)
                        .hostOnlyDomain(parsed.host)
                        .path("/")
                        .apply { if (parsed.isHttps) secure() }
                        .build()
                }.getOrNull()
            }
        }
    }

    /**
     * 同步探测：这些域里有没有 cookie。**只读 `CookieManager`，一个请求都不发。**
     *
     * 给「我的」页状态卡用（DESIGN §3.16）：升级用户没存凭证，但 WebView 里可能还留着
     * 有效会话。不认这一点，状态卡就会出现「说未登录、点进导入却能用」的自相矛盾。
     *
     * **只认会话标识**（[SessionCookieRules]）：`bzb_njw` 这类站点标记也在 `CookieManager`
     * 里长期存着，把它们算成会话会让状态卡报出一个干不了任何事的「已登录」。
     *
     * 必须在有 Looper 的线程调用——组合期的主线程正合适。
     */
    override fun hasAnyCookie(urls: List<String>): Boolean {
        val manager = CookieManager.getInstance()
        return urls.any { url ->
            val header = runCatching { manager.getCookie(url) }.getOrNull()
            SessionCookieRules.hasSessionCookie(header)
        }
    }

    override suspend fun clearAll() = withContext(Dispatchers.Main) {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }
}
