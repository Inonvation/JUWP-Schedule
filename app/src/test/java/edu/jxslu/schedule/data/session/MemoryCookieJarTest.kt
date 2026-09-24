package edu.jxslu.schedule.data.session

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 会话 cookie jar（DESIGN §4.27）：分桶、快照、回灌。 */
class MemoryCookieJarTest {

    private val jw = "http://jiaowu.juwp.edu.cn:8080/jsxsd/xskb/xskb_list.do".toHttpUrl()
    private val cas = "https://eapp2.juwp.edu.cn:9443/cas/login".toHttpUrl()

    private fun cookie(name: String, value: String, host: String, path: String = "/") =
        Cookie.Builder().name(name).value(value).hostOnlyDomain(host).path(path).build()

    @Test
    fun cookiesAreBucketedByHost() {
        val jar = MemoryCookieJar()
        jar.saveFromResponse(jw, listOf(cookie("JSESSIONID", "jw", "jiaowu.juwp.edu.cn")))
        jar.saveFromResponse(cas, listOf(cookie("CASTGC", "cas", "eapp2.juwp.edu.cn")))

        assertEquals("JSESSIONID=jw", CookieBridge.headerValue(jar.loadForRequest(jw)))
        assertEquals("CASTGC=cas", CookieBridge.headerValue(jar.loadForRequest(cas)))
        assertEquals(setOf("jiaowu.juwp.edu.cn", "eapp2.juwp.edu.cn"), jar.hosts())
    }

    /** 同名 cookie 覆盖，不堆叠。 */
    @Test
    fun sameNameOverwrites() {
        val jar = MemoryCookieJar()
        jar.saveFromResponse(jw, listOf(cookie("JSESSIONID", "v1", "jiaowu.juwp.edu.cn")))
        jar.saveFromResponse(jw, listOf(cookie("JSESSIONID", "v2", "jiaowu.juwp.edu.cn")))
        assertEquals("JSESSIONID=v2", CookieBridge.headerValue(jar.loadForRequest(jw)))
        assertEquals(1, jar.snapshot().size)
    }

    /** 回灌：WebView 手登之后抄回来的 cookie 要能按同一个 host 桶取到。 */
    @Test
    fun adopt_roundTripsThroughSameBucket() {
        val jar = MemoryCookieJar()
        jar.adopt(listOf(cookie("sid", "s1", "jwxyxx.juwp.edu.cn")))
        val url = "http://jwxyxx.juwp.edu.cn/ptwork/mainIndex".toHttpUrl()
        assertEquals("sid=s1", CookieBridge.headerValue(jar.loadForRequest(url)))
    }

    /**
     * 回灌的桶键必须与 `saveFromResponse` 的桶键一致（都按 host 去点），
     * 否则「先回灌、后跟随一次 302」会变成两份互不可见的 cookie。
     */
    @Test
    fun adopt_usesSameBucketKeyAsSave() {
        val jar = MemoryCookieJar()
        jar.adopt(
            listOf(
                Cookie.Builder().name("x").value("1").domain("jiaowu.juwp.edu.cn").path("/").build(),
            ),
        )
        assertEquals(setOf("jiaowu.juwp.edu.cn"), jar.hosts())
        jar.saveFromResponse(jw, listOf(cookie("JSESSIONID", "jw", "jiaowu.juwp.edu.cn")))
        assertEquals(2, jar.loadForRequest(jw).size)
    }

    /** path 不匹配的 cookie 不该被发出去（CAS 的 TGC 挂在 /cas 下）。 */
    @Test
    fun pathMismatch_isNotSent() {
        val jar = MemoryCookieJar()
        jar.saveFromResponse(cas, listOf(cookie("CASTGC", "t", "eapp2.juwp.edu.cn", path = "/cas")))
        val other = "https://eapp2.juwp.edu.cn:9443/other/page".toHttpUrl()
        assertTrue(jar.loadForRequest(other).isEmpty())
        assertFalse(jar.loadForRequest(cas).isEmpty())
    }

    @Test
    fun clear_emptiesEverything() {
        val jar = MemoryCookieJar()
        jar.saveFromResponse(jw, listOf(cookie("JSESSIONID", "jw", "jiaowu.juwp.edu.cn")))
        jar.clear()
        assertTrue(jar.isEmpty())
        assertTrue(jar.snapshot().isEmpty())
    }
}
