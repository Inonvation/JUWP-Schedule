package edu.jxslu.schedule.data.session

import okhttp3.Cookie

/**
 * OkHttp cookie → 请求头字符串（DESIGN §4.27）。
 *
 * 只剩这一个方向。原先还有一条「OkHttp cookie → `Set-Cookie` 形态喂给
 * `CookieManager.setCookie`」的反向桥，随注入侧一起删了——真机上注入的 cookie
 * 不会被 WebView 采用（属性缺 `SameSite=None`，跨站跳转不带）。
 */
object CookieBridge {

    /** 请求头 `Cookie:` 的值。空集合返回空串，调用方判空后再决定带不带这个头。 */
    fun headerValue(cookies: List<Cookie>): String =
        cookies.joinToString("; ") { "${it.name}=${it.value}" }
}
