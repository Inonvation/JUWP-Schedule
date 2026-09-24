package edu.jxslu.schedule.data.session

import okhttp3.Cookie
import org.junit.Assert.assertEquals
import org.junit.Test

/** Cookie 请求头拼接（DESIGN §4.27）：拼错一个字符，服务端认的就是另一条会话。 */
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
}
