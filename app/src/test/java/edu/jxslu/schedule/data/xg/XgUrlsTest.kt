package edu.jxslu.schedule.data.xg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 学工系统地址、表单清单与判定（DESIGN §4.26）。
 *
 * 这些判断决定顶栏状态条说什么、WebView 放行哪张证书、以及直达表单的闸门开不开。
 * 判错的代价是「用户正在登录，页面却告诉他已进入学工系统」，或者登录链路被截断，
 * 所以钉住，别靠肉眼。
 */
class XgUrlsTest {

    /** 学工域按 host 精确判定：宿主、带端口、深路径都要命中。 */
    @Test
    fun xgHost_matchesExactHostOnly() {
        assertTrue(XgUrls.isXgHost("https://xgxt.juwp.edu.cn/"))
        assertTrue(XgUrls.isXgHost(XgUrls.SSO_LOGIN))
        assertTrue(XgUrls.isXgHost(XgUrls.REPAIR.applyUrl))
        assertFalse(XgUrls.isXgHost("https://jiaowu.juwp.edu.cn/jsxsd/"))
        assertFalse(XgUrls.isXgHost(null))
        assertFalse(XgUrls.isXgHost(""))
    }

    /**
     * 回归钉：**不能退化成子串匹配**。
     *
     * `JwUrls.hostOf` 的 KDoc 记着同一类事故：整串 `in` 匹配会把 query 里的域名
     * 也算成命中的域。CAS 回跳地址里同时含 `eapp2` 与 `xgxt` 两个域，判定一旦退回
     * 子串匹配，认证页就会被认成学工页，状态条说反话。攻击域名后缀同理。
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

    /** 状态条四档：认证域、表单页、学工域其他页、以及还没出结果。 */
    @Test
    fun statusHint_fourBranches() {
        val title = XgUrls.REPAIR.title
        assertEquals(
            "用学校统一身份认证登录（与教务同一个账号）",
            XgUrls.statusHint("https://eapp2.juwp.edu.cn:9443/cas/login?service=x", title),
        )
        assertEquals(
            "宿舍报修已打开，填完点页面下方的提交",
            XgUrls.statusHint(XgUrls.REPAIR.applyUrl, title),
        )
        assertEquals(
            "已进入学工系统，可从右上角回到首页找入口",
            XgUrls.statusHint(XgUrls.HOME, title),
        )
        assertEquals("正在打开宿舍报修…", XgUrls.statusHint(null, title))
        assertEquals("正在打开宿舍报修…", XgUrls.statusHint("about:blank", title))
    }

    /** 表单页判定：学工域 + 表单引擎路径，两个条件都要。 */
    @Test
    fun formPage_requiresOfficeFormsPath() {
        assertTrue(XgUrls.isFormPage(XgUrls.REPAIR.applyUrl))
        assertFalse(XgUrls.isFormPage(XgUrls.HOME))
        // 认证接入点也在学工域，但它不是表单页
        assertFalse(XgUrls.isFormPage(XgUrls.SSO_LOGIN))
        // 换到别的域、路径里却带着表单引擎路径：不算
        assertFalse(XgUrls.isFormPage("https://evil.com/office/apps/forms/x"))
    }

    /**
     * 直达表单页的闸门（回归钉）。
     *
     * 最要紧的是第二档：**认证接入点也在学工域**。若只判 `isXgHost` 就往下跳，
     * CAS 带 ticket 回跳的那一刻会被截断，登录流程走不完。
     */
    @Test
    fun shouldEnterForm_gates() {
        // 落到学工首页：该进去
        assertTrue(XgUrls.shouldEnterForm(XgUrls.HOME))
        assertTrue(XgUrls.shouldEnterForm("https://xgxt.juwp.edu.cn/wfw/index"))
        // 还在认证链路上：不许跳
        assertFalse(XgUrls.shouldEnterForm(XgUrls.SSO_LOGIN))
        assertFalse(XgUrls.shouldEnterForm("https://xgxt.juwp.edu.cn/sfrz/login343962?ticket=ST-1"))
        // 已经在表单页：不许再跳（否则自跳自，转圈）
        assertFalse(XgUrls.shouldEnterForm(XgUrls.REPAIR.applyUrl))
        // 认证域：不许跳
        assertFalse(XgUrls.shouldEnterForm("https://eapp2.juwp.edu.cn:9443/cas/login"))
        assertFalse(XgUrls.shouldEnterForm(null))
    }

    /**
     * 直达地址钉子：带上表单标识，且**不带 uuid**。
     *
     * `uuid` 在引擎里是提交时现场生成的随机值（`e.uuid = createUUID()`），
     * URL 上那一个是另一个东西且没有读取点。把它写进地址，等于把一个每次点开都不同的
     * 值当成表单标识固定下来。
     */
    @Test
    fun formApplyUrl_carriesIdentity_withoutUuid() {
        val url = XgUrls.REPAIR.applyUrl
        assertTrue(
            url.startsWith("https://xgxt.juwp.edu.cn/office/apps/forms/mobile/apply.html?"),
        )
        assertTrue(url.contains("pageEnc=2a6e9319e7a9ac992f30e587d26f435d"))
        assertTrue(url.contains("aprvAppId=278625"))
        assertTrue(url.contains("id=278625"))
        assertTrue(url.contains("formType=1"))
        assertFalse(url.contains("uuid"))
    }

    /** 脏 extra（旧版本写下的 id、空值）回退成 null，由调用方决定落到哪张表单。 */
    @Test
    fun formById_rejectsUnknownId() {
        assertSame(XgUrls.REPAIR, XgUrls.formById("dorm_repair"))
        assertNull(XgUrls.formById(null))
        assertNull(XgUrls.formById(""))
        assertNull(XgUrls.formById("no_such_form"))
    }

    /** 请假必须在清单里，且地址取自真实那一条。 */
    @Test
    fun leaveForm_isRegistered() {
        val leave = XgUrls.formById("leave")
        assertNotNull("请假没登记进 XgUrls.FORMS", leave)
        assertEquals("请假", leave!!.title)
        assertTrue(leave.applyUrl.contains("pageEnc=5f440bbcb35527e35a7c4e7169bcabc2"))
        assertTrue(leave.applyUrl.contains("aprvAppId=278616"))
        assertTrue(leave.applyUrl.contains("id=278616"))
        assertFalse(leave.applyUrl.contains("uuid"))
    }

    /**
     * 清单里每一张表单的直达地址都要成形，且能被 [XgUrls.formById] 找回。
     *
     * 这条是为「以后加新表单」写的：新加的那条写错了标识、漏了 id，这里先炸，
     * 而不是等真机上打开一张空表单才发现。
     */
    @Test
    fun everyForm_hasWellFormedApplyUrl() {
        assertTrue("清单不该是空的", XgUrls.FORMS.isNotEmpty())
        XgUrls.FORMS.forEach { form ->
            assertTrue("id 不能为空", form.id.isNotBlank())
            assertTrue("标题不能为空", form.title.isNotBlank())
            assertSame("formById 要能按 id 找回同一实例", form, XgUrls.formById(form.id))
            assertTrue(
                form.applyUrl.startsWith(
                    "https://xgxt.juwp.edu.cn/office/apps/forms/mobile/apply.html?formType=1&pageEnc=",
                ),
            )
            assertTrue(
                "pageEnc 应是 32 位十六进制（服务端下发），$form",
                Regex("pageEnc=[0-9a-f]{32}").containsMatchIn(form.applyUrl),
            )
            assertTrue(
                "aprvAppId 与 id 应同值且带 isManager=false，$form",
                Regex("aprvAppId=\\d+&id=\\d+&isManager=false").containsMatchIn(form.applyUrl),
            )
            assertFalse("直达地址不该带 uuid，$form", form.applyUrl.contains("uuid"))
        }
    }
}
