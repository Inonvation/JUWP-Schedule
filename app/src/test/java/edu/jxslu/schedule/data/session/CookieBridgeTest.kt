package edu.jxslu.schedule.data.session

import okhttp3.Cookie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cookie 字符串桥（DESIGN §4.27）：拼错一个字，注入到 WebView 的就是另一条 cookie。 */
class CookieBridgeTest {

    private fun hostOnly(name: String, value: String, host: String, path: String = "/") =
        Cookie.Builder().name(name).value(value).hostOnlyDomain(host).path(path).build()

    @Test
    fun headerValue_joinsWithSemicolon() {
        val cookies = listOf(
            hostOnly("JSESSIONID", "a", "jiaowu.juwp.edu.cn"),
            hostOnly("bzb_njw", "b", "jiaowu.juwp.edu.cn"),
        )
        assertEquals("JSESSIONID=a; bzb_njw=b", CookieBridge.headerValue(cookies))
    }

    @Test
    fun headerValue_emptyListIsEmptyString() {
        assertEquals("", CookieBridge.headerValue(emptyList()))
    }

    /** hostOnly 的 cookie 不写 Domain：写了就把作用域扩大到整个域。 */
    @Test
    fun setCookieValue_hostOnlyOmitsDomain() {
        val s = CookieBridge.setCookieValue(hostOnly("JSESSIONID", "a", "jiaowu.juwp.edu.cn"))
        assertFalse(s.contains("Domain="))
        assertTrue(s.startsWith("JSESSIONID=a; Path=/"))
    }

    @Test
    fun setCookieValue_domainCookieKeepsDomain() {
        // OkHttp 4 的 domain() 不接受前导点（RFC 早就不需要了）
        val cookie = Cookie.Builder()
            .name("sid").value("s").domain("jwxyxx.juwp.edu.cn").path("/ptwork").build()
        val s = CookieBridge.setCookieValue(cookie)
        assertTrue(s.contains("Domain=jwxyxx.juwp.edu.cn"))
        assertTrue(s.contains("Path=/ptwork"))
    }

    /** CAS 的 TGC 是 HttpOnly + Secure，两个属性都要照抄。 */
    @Test
    fun setCookieValue_copiesSecureAndHttpOnly() {
        val cookie = Cookie.Builder()
            .name("CASTGC").value("t").hostOnlyDomain("eapp2.juwp.edu.cn")
            .path("/cas").secure().httpOnly().build()
        val s = CookieBridge.setCookieValue(cookie)
        assertTrue(s.contains("; Secure"))
        assertTrue(s.contains("; HttpOnly"))
    }

    /** 没显式给 path 的 cookie，输出必须是根路径——Path 缺失会让 CookieManager 按当前 URL 推。 */
    @Test
    fun setCookieValue_defaultPathIsRoot() {
        val cookie = Cookie.Builder()
            .name("x").value("1").hostOnlyDomain("a.example.com").build()
        assertTrue(CookieBridge.setCookieValue(cookie).contains("Path=/"))
    }
}
