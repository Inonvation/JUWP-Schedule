package edu.jxslu.schedule.data.session

/**
 * 「`Cookie:` 头原文里有没有会话标识」的判定（DESIGN §3.16「老用户的网页会话要认」那条）。
 *
 * **只认会话标识，不认站点标记。** 教务预热入口写的 `bzb_njw` / `bzb_jsxsd`、CAS 登录页的
 * `randomBgIndex` 都与登录无关，却会长期留在 `CookieManager` 里（Chrome 会把会话 cookie
 * 一起落盘、重启后恢复）。2026-09-27 真机实测：WebView 里只剩两个 `bzb_*`，
 * OkHttp 侧 `verifySession` 也探不通，状态卡却写着「已登录」，用户看到的是一个
 * 干不了任何事的「已登录」。
 *
 * 白名单覆盖共用 CAS 的四个系统：教务 `JSESSIONID`、CAS `TGC`、学工表单引擎 `session_oa`、
 * 签章 `sid`。放宽到「有 cookie 就算」会把站点标记算进来，收紧到只认某一个系统则会把
 * 另外三个的会话漏掉。
 */
object SessionCookieRules {

    private val SESSION_COOKIE_NAMES = setOf(
        "JSESSIONID",
        "JSESSIONIDSSO",
        "TGC",
        "session_oa",
        "sid",
    )

    /** [header] 是 `CookieManager.getCookie(url)` 的原文（`a=1; b=2`），可为 null。 */
    fun hasSessionCookie(header: String?): Boolean {
        if (header.isNullOrBlank()) return false
        return header.split(';').any { part ->
            part.substringBefore('=', "").trim() in SESSION_COOKIE_NAMES
        }
    }
}
