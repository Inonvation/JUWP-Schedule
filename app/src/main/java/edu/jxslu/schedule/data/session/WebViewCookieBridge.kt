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
     * 必须在有 Looper 的线程调用——组合期的主线程正合适。
     */
    fun hasAnyCookie(urls: List<String> = CasSession.ADOPT_URLS): Boolean {
        val manager = CookieManager.getInstance()
        return urls.any { url ->
            runCatching { manager.getCookie(url) }.getOrNull()?.isNotBlank() == true
        }
    }
}
