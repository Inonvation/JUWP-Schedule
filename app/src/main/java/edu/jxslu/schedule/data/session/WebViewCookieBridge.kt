package edu.jxslu.schedule.data.session

import android.webkit.CookieManager
import android.util.Log
import edu.jxslu.schedule.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * OkHttp 会话 ↔ WebView `CookieManager` 的搬运接口（DESIGN §4.27）。
 *
 * 抽接口是为了让 `CasSession.adoptFromWebView` 能在 JVM 单测里跑——真实现要
 * `android.webkit.CookieManager`，单测里只有 stub，一调就炸。
 */
interface WebCookieBridge {
    suspend fun inject(cookies: List<Cookie>): Int
    suspend fun adopt(urls: List<String>): List<Cookie>
}

/**
 * 真机实现。
 *
 * 方向是**单向为主**：`CasSession` 的 jar 是真源，注入给 WebView；只在用户在 WebView 里
 * 手登过（导入页、学工、签章授权）之后，才把 `CookieManager` 里的 cookie 回灌。
 * 两边都写而不区分主次，会变成谁都说不清哪个是新的。
 *
 * 两条硬约束：
 * 1. `CookieManager` 的读写要在有 Looper 的线程 → 全部包 `Dispatchers.Main`；
 * 2. 注入必须发生在 `loadUrl` **之前**（`setCookie` 落盘是异步的，先加载会拿到没 cookie
 *    的第一个请求），调用点在 `JwImportScreen` / `XgFormScreen` / `TranscriptScreen`。
 */
object WebViewCookieBridge : WebCookieBridge {

    /**
     * 把 jar 里的 cookie 写进 `CookieManager`，返回写入条数。
     *
     * 每条 cookie 拼一个落在**它自己作用域内**的 URL 当入口：`setCookie` 要求 URL 与
     * cookie 的域匹配，而且 `Secure` 属性的 cookie 只有 https URL 才收。url 由
     * `scheme + domain + path` 推出来，不猜端口——cookie 本来就不带端口。
     */
    override suspend fun inject(cookies: List<Cookie>): Int = withContext(Dispatchers.Main) {
        val manager = CookieManager.getInstance()
        manager.setAcceptCookie(true)
        var written = 0
        val pending = ArrayList<Pair<String, String>>(cookies.size)
        cookies.forEach { cookie ->
            val url = cookieScopeUrl(cookie) ?: return@forEach
            // setCookie 是 void，没有成功与否的返回值；没抛异常就算写进去了
            val ok = runCatching { manager.setCookie(url, CookieBridge.setCookieValue(cookie)) }.isSuccess
            if (ok) {
                written++
                pending += url to cookie.name
            }
        }
        manager.flush()
        // **关键：setCookie 与 flush 都是排队到 WebView 的 cookie 线程、不等完成。**
        // 写完立刻 loadUrl 会发出一个「还没有 cookie」的请求——真机上表现为 CM 里最终确实
        // 有 TGC，但首跳 sso.jsp 仍然落到 CAS 登录页（2026-09-24 实测）。
        // 这里轮询读回到位再返回，最多等 SETTLE_TIMEOUT_MS。
        val settled = awaitSettled(manager, pending)
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "inject: $written/${cookies.size} 条, 落位${if (settled) "" else "超时"} pending=${pending.size}")
        }
        written
    }

    /**
     * 等 cookie 真正落到位：逐条 `getCookie` 读回（该 API 是**同步阻塞**的，能拿到 cookie
     * 线程的当前状态）。全部读到即返回 true；超时返回 false（照常放行，不阻塞用户）。
     */
    private suspend fun awaitSettled(
        manager: CookieManager,
        pending: List<Pair<String, String>>,
    ): Boolean {
        if (pending.isEmpty()) return true
        repeat(SETTLE_TRIES) {
            val allPresent = pending.all { (url, name) ->
                runCatching { manager.getCookie(url) }.getOrNull()?.contains("$name=") == true
            }
            if (allPresent) return true
            delay(SETTLE_STEP_MS)
        }
        return false
    }

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
     * 该 cookie 的入口 URL。
     *
     * 域级的 cookie（`hostOnly == false`）走不带点的 domain，host-only 的走 domain 自身
     * ——两者在 OkHttp 里都由 `domain` 字段承载，差异只在是否向前导点。
     */
    private fun cookieScopeUrl(cookie: Cookie): String? {
        val host = cookie.domain.removePrefix(".")
        if (host.isBlank()) return null
        val scheme = if (cookie.secure) "https" else "http"
        val path = cookie.path.ifBlank { "/" }
        return "$scheme://$host$path"
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

    private const val TAG = "JwCookie"

    /** 落位轮询：10 次 × 50ms = 最多 500ms。正常一两次就到位。 */
    private const val SETTLE_TRIES = 10
    private const val SETTLE_STEP_MS = 50L
}
