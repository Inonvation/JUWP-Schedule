package edu.jxslu.schedule.data.xg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 学工系统地址与判定（DESIGN §4.26）。
 *
 * 这些判断决定顶栏状态条说什么，也决定 WebView 放行哪张证书。判错的代价是
 * 「用户正在登录，页面却告诉他已进入学工系统」——所以钉住，别靠肉眼。
 */
class XgUrlsTest {

    /** 学工域按 host 精确判定：宿主、带端口、深路径都要命中。 */
    @Test
    fun xgHost_matchesExactHostOnly() {
        assertTrue(XgUrls.isXgHost("https://xgxt.juwp.edu.cn/"))
        assertTrue(XgUrls.isXgHost(XgUrls.SSO_LOGIN))
        assertTrue(XgUrls.isXgHost("https://xgxt.juwp.edu.cn/office/apps/forms/mobile/apply.html"))
        assertFalse(XgUrls.isXgHost("https://jiaowu.juwp.edu.cn/jsxsd/"))
        assertFalse(XgUrls.isXgHost(null))
        assertFalse(XgUrls.isXgHost(""))
    }

    /**
     * 回归钉：**不能退化成子串匹配**。
     *
     * `JwUrls.hostOf` 的 KDoc 记着同一类事故：整串 `in` 匹配会把 query 里的域名
     * 也算成命中的域。学工入口的 URL 里恰好只有学工自己的域名，看似无害——
     * 但 CAS 回跳地址里同时含 `eapp2` 与 `xgxt` 两个域，一旦判定退回子串匹配，
     * 认证页就会被认成学工页，状态条说反话。攻击域名后缀同理。
     */
    @Test
    fun hostMatch_doesNotFallBackToSubstring() {
        assertFalse(XgUrls.isXgHost("https://evil.com/?u=https://xgxt.juwp.edu.cn/"))
        assertFalse(XgUrls.isXgHost("https://xgxt.juwp.edu.cn.evil.com/"))
        assertFalse(XgUrls.isXgHost("https://notxgxt.juwp.edu.cn/"))
    }

    /** 统一认证域就是教务那一个（`eapp2.juwp.edu.cn:9443`）。 */
    @Test
    fun casHost_isTheSameOneAsJw() {
        assertTrue(XgUrls.isCasHost("https://eapp2.juwp.edu.cn:9443/cas/login?service=x"))
        assertFalse(XgUrls.isCasHost(XgUrls.SSO_LOGIN))
        assertFalse(XgUrls.isCasHost(null))
    }

    /** 状态条三档：认证域、学工域、其他。 */
    @Test
    fun statusHint_threeBranches() {
        assertEquals(
            "用学校统一身份认证登录（与教务同一个账号）",
            XgUrls.statusHint("https://eapp2.juwp.edu.cn:9443/cas/login?service=x"),
        )
        assertEquals(
            "已进入学工系统，报修在「宿管服务 → 宿舍报修」",
            XgUrls.statusHint(XgUrls.HOME),
        )
        assertEquals("正在打开学工系统…", XgUrls.statusHint(null))
        assertEquals("正在打开学工系统…", XgUrls.statusHint("about:blank"))
    }

    /**
     * 入口常量钉子：必须是 `/sfrz/` 那条统一认证链路。
     *
     * 写成 `/passport/mlogin` 会退到超星自己的账号体系（手机号 + 学习通密码），
     * 与教务不是一个账号——用户在报修页面前会被要求输一个他从没用过的密码。
     */
    @Test
    fun ssoLogin_isCasEntry_notPassport() {
        assertTrue(XgUrls.SSO_LOGIN.startsWith(XgUrls.BASE))
        assertTrue(XgUrls.SSO_LOGIN.contains("/sfrz/"))
        assertFalse(XgUrls.SSO_LOGIN.contains("passport"))
        assertEquals("https://xgxt.juwp.edu.cn/sfrz/login343962", XgUrls.SSO_LOGIN)
    }
}
