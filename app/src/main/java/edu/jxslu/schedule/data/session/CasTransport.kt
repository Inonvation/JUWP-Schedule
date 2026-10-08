package edu.jxslu.schedule.data.session

import okhttp3.Cookie

/**
 * `CasSession` 对 HTTP 层的全部要求（DESIGN §4.27）。
 *
 * 真实实现是 [edu.jxslu.schedule.data.jw.JwHttpSession]。抽接口同样为了可测：
 * 会话策略的每条分支都要能在 JVM 上跑，不联网。
 */
interface CasTransport {

    /** 完整登录（CAS → 教务 SSO → 会话校验）。失败抛 [edu.jxslu.schedule.data.jw.JwHttpSession.JwHttpException]。 */
    suspend fun login(username: String, password: String)

    /** 只探会话有效性（学生主页）。网络层失败抛 `Network`。 */
    suspend fun verifySession(): Boolean

    /** 带会话取一个教务页面。给学籍卡这类一次性抓取用（DESIGN §3.3 的班级来源）。 */
    suspend fun fetchHtml(url: String): String

    /**
     * 带会话 **POST 表单**（2026-10-08 起；选课中心的列表查询用它——教务那个接口是
     * POST + 旧版 DataTables 参数，见 `JwUrls.SELECTION_PLAN_LIST_API`）。
     *
     * [form] 是 `(name, value)` 对；`columns[0][data]` 这类键值由实现方统一编码。
     * 失败抛 `JwHttpException`（登录页 / 被顶 = `SessionInvalid`）——与 [fetchHtml] 同一套判据。
     */
    suspend fun postForm(url: String, form: List<Pair<String, String>>): String

    fun hasCookies(): Boolean

    fun cookies(): List<Cookie>

    fun adoptCookies(cookies: List<Cookie>)

    fun clearCookies()

    fun shutdown()
}
