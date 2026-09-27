package edu.jxslu.schedule.data.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「网页里有没有会话」的判定（DESIGN §3.16）。
 *
 * 钉的是 2026-09-27 真机实测那个假阳性：WebView 里只剩 `bzb_njw` / `bzb_jsxsd` 这类
 * 站点标记，`verifySession` 早就探不通了，状态卡却写着「已登录」。
 */
class SessionCookieRulesTest {

    @Test
    fun sessionCookiesCount() {
        assertTrue(SessionCookieRules.hasSessionCookie("JSESSIONID=abc"))
        assertTrue(SessionCookieRules.hasSessionCookie("TGC=abc; JSESSIONID=def"))
        assertTrue(SessionCookieRules.hasSessionCookie("randomBgIndex=1; sid=xyz"))
        assertTrue(SessionCookieRules.hasSessionCookie("session_oa=oa"))
    }

    /** 站点标记不是会话：教务预热写的两个 bzb 标记正是那个假阳性的来源。 */
    @Test
    fun siteMarkersDoNotCount() {
        assertFalse(SessionCookieRules.hasSessionCookie("bzb_njw=1; bzb_jsxsd=1"))
        assertFalse(SessionCookieRules.hasSessionCookie("randomBgIndex=0"))
    }

    @Test
    fun blankHeaderIsNotASession() {
        assertFalse(SessionCookieRules.hasSessionCookie(null))
        assertFalse(SessionCookieRules.hasSessionCookie(""))
        assertFalse(SessionCookieRules.hasSessionCookie("   "))
    }

    /** 名字只做全等比较：`xJSESSIONID` 不是 `JSESSIONID`。 */
    @Test
    fun nameMatchIsExact() {
        assertFalse(SessionCookieRules.hasSessionCookie("xJSESSIONID=abc"))
        assertFalse(SessionCookieRules.hasSessionCookie("JSESSIONID2=abc"))
    }
}
