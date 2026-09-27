package edu.jxslu.schedule.data.jw

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 调课检测登录链路的两个回归钉（DESIGN §4.17）。
 *
 * 这两个缺陷曾让「开启调课自动检测」100% 失败：
 * 1. 重定向解析参数写反 → 每轮请求同一 URL，原地打转 MAX_REDIRECTS 次后误报「重定向次数过多」；
 * 2. 校园 IPv6 在移动网络下是黑洞 → OkHttp 优先连 v6 时每步干等 TCP 超时，表现为一直转圈。
 */
class JwHttpSessionTest {

    /** 重定向解析：Location 为绝对 URL / 相对路径 / 协议相对 三种形态都要落到正确目标。 */
    @Test
    fun redirectResolve_matchesHttpSemantics() {
        val base = "https://eapp2.juwp.edu.cn:9443/cas/login?service=x"

        // 绝对 URL：解析结果就是它自己
        assertEquals(
            "http://portal.juwp.edu.cn/cas/login_portal",
            base.toHttpUrlForTest().resolve("http://portal.juwp.edu.cn/cas/login_portal").toString(),
        )
        // 站内绝对路径
        assertEquals(
            "https://eapp2.juwp.edu.cn:9443/cas/login",
            base.toHttpUrlForTest().resolve("/cas/login").toString(),
        )
        // 相对路径：以当前目录为基准
        assertEquals(
            "https://eapp2.juwp.edu.cn:9443/cas/other",
            base.toHttpUrlForTest().resolve("other").toString(),
        )
    }

    /**
     * 关键回归：**参数顺序不能反**。
     * 错误写法 `location.toHttpUrl().resolve(current)` 在以绝对 URL 为 current 时返回 current 自身，
     * 于是重定向永远不前进——这正是「开启开关失败」的根因。
     */
    @Test
    fun redirectResolve_wrongArgumentOrder_doesNotAdvance() {
        val current = "http://portal.juwp.edu.cn/cas/login_portal"
        val location = "https://eapp2.juwp.edu.cn:9443/cas/login?service=x"
        // 错误写法（供对照）：以 location 为基准解析 current
        val wrong = location.toHttpUrlForTest().resolve(current).toString()
        // 正确写法：以 current 为基准解析 location
        val right = current.toHttpUrlForTest().resolve(location).toString()

        assertEquals("错误写法会返回 current 自身（原地打转的根因）", current, wrong)
        assertEquals("正确写法落到重定向目标", location, right)
        assertTrue("两者必须不同，否则说明 resolve 语义被误解", wrong != right)
    }

    /** IPv4 优先：有 A 记录时 v4 排在最前，且不丢弃 AAAA。 */
    @Test
    fun ipv4FirstDns_prefersV4_keepsV6() {
        val v4a = InetAddress.getByName("117.40.44.51")
        val v4b = InetAddress.getByName("117.40.44.55")
        val v6 = InetAddress.getByName("2001:250:6c02:81::12")

        // 直接测生产实现：系统返回 v6 在前
        val ordered = JwHttpSession.ipv4First(listOf(v6, v4a, v4b))

        assertTrue("v4 必须排在最前", ordered.first() is Inet4Address)
        assertEquals("v4 有两条", 2, ordered.count { it is Inet4Address })
        assertEquals("v6 不丢弃，排在 v4 之后", 1, ordered.count { it is Inet6Address })
        assertTrue("末位是 v6", ordered.last() is Inet6Address)
    }

    /** 只有 AAAA 时不丢结果（纯 v6 域名仍可工作）。 */
    @Test
    fun ipv4FirstDns_onlyV6_returnsAsIs() {
        val v6 = InetAddress.getByName("2001:250:6c02:81::12")
        val ordered = JwHttpSession.ipv4First(listOf(v6))
        assertEquals(1, ordered.size)
        assertTrue(ordered.first() is Inet6Address)
    }

    /**
     * 会话探针要认得出「就地渲染的登录页」。
     *
     * 2026-09-27 真机实测：WebView 里只剩一份过期 CAS cookie 时，教务把登录页渲染在
     * `xsMainV.htmlx` 的 200 应答里（约 79KB，title「登录」，表单 `LoginToXk`），
     * 老判据「≥20KB 且不含『用户没有登录』」放它过去，于是状态卡显示已登录、
     * 学籍卡抓回一张登录页。
     */
    @Test
    fun loginPage_isNotASession() {
        val loginPage = """
            <html><head><title>登录</title></head><body>
            <form name="loginForm" action="/jsxsd/xk/LoginToXk" method="post">
            <input id="userAccount" name="userAccount">
            <input id="userPassword" name="userPassword" type="password">
            </form></body></html>
        """.trimIndent()
        assertTrue(JwHttpSession.looksLikeLoginPage(loginPage))

        // 860 字节那种提示页同样要认（老判据的另一半）
        assertTrue(JwHttpSession.looksLikeLoginPage("<html>用户没有登录</html>"))
    }

    @Test
    fun studentHome_isNotALoginPage() {
        val home = "<html><head><title>学生端</title></head><body>" +
            "<div id='xsMainV'>我的课表</div></body></html>"
        assertTrue(!JwHttpSession.looksLikeLoginPage(home))
    }

    private fun String.toHttpUrlForTest(): HttpUrl = toHttpUrl()
}
