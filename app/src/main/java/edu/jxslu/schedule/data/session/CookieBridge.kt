package edu.jxslu.schedule.data.session

import okhttp3.Cookie

/**
 * OkHttp Cookie 与 WebView `CookieManager` 之间的字符串桥（DESIGN §4.27）。
 *
 * 自己拼字符串，不用 `Cookie.toString()`——那是日志用的展示形态，不保证是
 * `Set-Cookie` 头的语法，而 `CookieManager.setCookie` 要的是后者。
 */
object CookieBridge {

    /** 请求头 `Cookie:` 的值。空集合返回空串，调用方判空后再决定带不带这个头。 */
    fun headerValue(cookies: List<Cookie>): String =
        cookies.joinToString("; ") { "${it.name}=${it.value}" }

    /**
     * 单条 cookie 转 `Set-Cookie` 形态，喂给 `CookieManager.setCookie`。
     *
     * `hostOnly` 的 cookie **不写 Domain**：写了会把作用域从「这一个 host」扩大到
     * 整个域，那是权限放大。`HttpOnly` 照抄——CAS 的 TGC 就是 HttpOnly 的，
     * 不带这个属性反而会把它的处理方式改掉。
     */
    fun setCookieValue(cookie: Cookie): String = buildString {
        append(cookie.name).append('=').append(cookie.value)
        // OkHttp 的 domain 可能带前导点（域 cookie 的写法），Set-Cookie 两种都收，
        // 统一去点，便于比对与日志。
        if (!cookie.hostOnly) append("; Domain=").append(cookie.domain.removePrefix("."))
        append("; Path=").append(cookie.path.ifBlank { "/" })
        if (cookie.secure) append("; Secure")
        if (cookie.httpOnly) append("; HttpOnly")
    }
}
