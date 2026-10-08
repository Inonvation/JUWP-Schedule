package edu.jxslu.schedule.ui.jwvw

import edu.jxslu.schedule.data.jw.JwUrls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 教务 WebView 的页面适配判据与脚本契约（DESIGN §4.4「电脑版预览」，2026-10-08）。
 *
 * 这里钉的是**注入什么**，不是"注入后长什么样"（那要靠真机点验）。三条最重要的：
 * ① 覆盖串必须整串替换（留着 `initial-scale` 就缩不小、留着 `user-scalable=no` 就放不大）；
 * ② 登录 / SSO 页**绝不覆盖**（自动填表与会话链挂在它们身上，而它们本来就没有 meta）；
 * ③ 关掉电脑版时与引入之前**逐字一致**（回归闸）。
 */
class JwWebFitTest {

    // ---- 桌面覆盖脚本本身 ----

    @Test
    fun desktopScriptReplacesWholeContentNotJustWidth() {
        val js = JwWebFit.DESKTOP_JS
        // 整串赋值：content 只能是 width=980 这一个值
        assertTrue(js.contains("'width=${JwWebFit.DESKTOP_WIDTH_PX}'"))
        assertTrue(js.contains("setAttribute('content'"))
        // 页面原 content 必须先存下来，撤销才有得还
        assertTrue(js.contains(JwWebFit.ATTR_ORIG))
        // 页面没有 meta 时自己建一个（并打标记，撤销时整节点删）
        assertTrue(js.contains(JwWebFit.ATTR_CREATED))
        // 改完触一次 reflow——实测改 meta 会重新布局，这一步是让布局立刻落位
        assertTrue(js.contains("void document.documentElement.offsetWidth"))
    }

    /**
     * 幂等判据必须是「content 已是我们的值」而不是「标记已在」：`onPageCommitVisible` 与
     * `onPageFinished` 两轮都会注入，第一轮可能跑在页面自己的 meta 之前，靠标记早退就再也补不上。
     */
    @Test
    fun desktopScriptIsIdempotentByContentSoSecondPassCanStillOverride() {
        val js = JwWebFit.DESKTOP_JS
        // 只在 content 不是目标值时改写（已是我们的值就什么都不做，反复注入无副作用）
        assertTrue(js.contains("if (m.getAttribute('content') !== 'width=${JwWebFit.DESKTOP_WIDTH_PX}') {"))
        // 已存过原文就不覆盖原文（切换来回不丢页面自己那份）
        assertTrue(js.contains("if (!m.hasAttribute('${JwWebFit.ATTR_ORIG}'))"))
    }

    /**
     * 自愈（2026-10-08 补）：页面自己的 meta 晚于我们的注入被解析/由 JS 补上时，
     * 覆盖会被盖掉（现象 = 电脑版开着却还是挤压）。所以要盯着 head 反复压回去，
     * 且必须有防自触发的闸（否则我们自己的 setAttribute 会打转）。
     */
    @Test
    fun desktopScriptSelfHealsAgainstLatePageViewportMeta() {
        val js = JwWebFit.DESKTOP_JS
        assertTrue(js.contains("new MutationObserver(apply)"))
        assertTrue(js.contains("document.head"))
        assertTrue(js.contains("__juwViewportApplying"))
        // 自愈不能变成死循环：重入即早退，且每次进来 content 已是目标值就返回
        assertTrue(js.contains("window.__juwViewportApplying = true"))
        assertTrue(js.contains("finally{ window.__juwViewportApplying = false; }"))
    }

    @Test
    fun revertScriptRestoresOriginalAndRemovesSelfCreatedMeta() {
        val js = JwWebFit.DESKTOP_REVERT_JS
        assertTrue(js.contains(JwWebFit.ATTR_ORIG))
        assertTrue(js.contains(JwWebFit.ATTR_CREATED))
        // 自己建的整节点删掉；页面自己那份把 content 还回去
        assertTrue(js.contains("removeChild"))
        assertTrue(js.contains("m.removeAttribute('${JwWebFit.ATTR_ORIG}')"))
        assertTrue(js.contains("void document.documentElement.offsetWidth"))
    }

    // ---- 判据：哪一页注入什么 ----

    @Test
    fun desktopAppliesToJwBusinessPages() {
        val pages = listOf(
            JwUrls.STUDENT_HOME,                       // 教务主页
            JwUrls.SELECTION_CENTER,                   // 选课中心（壳页 + iframe）
            JwUrls.SELECTION_LIST_SHELL,               // 选课日志
            JwUrls.SCORE_FRM,                          // 成绩查询
            JwUrls.SCHEDULE_LIST,                      // 理论课表
            JwUrls.LAB_SCHEDULE,                       // 实验课表
            JwUrls.EXAM_QUERY,                         // 考试安排
            "https://jiaowupj.juwp.edu.cn/jwxsjdp",    // 评教平台（跨主机 SPA）
        )
        pages.forEach { url ->
            val js = JwWebFit.fitScript(url, desktop = true)
            assertNotNull("电脑版下这一页应当注入：$url", js)
            assertTrue("电脑版下这一页应当覆盖 viewport：$url", js!!.contains(JwWebFit.ATTR_ORIG))
        }
    }

    /** 课表页两套脚本并存：桌面覆盖管整页视口，课表适配管表格内部可滚。 */
    @Test
    fun schedulePageKeepsItsOwnFitAlongWithDesktopOverride() {
        val js = JwWebFit.fitScript(JwUrls.SCHEDULE_LIST, desktop = true)!!
        assertTrue(js.contains("juw-schedule-fit"))
        assertTrue(js.contains(JwWebFit.ATTR_ORIG))
    }

    /** 登录 / SSO 页只补表单适配，**不覆盖 viewport**（自动填表与会话链挂在这几页上）。 */
    @Test
    fun loginPagesNeverGetViewportOverride() {
        val logins = listOf(
            "https://eapp2.juwp.edu.cn:9443/cas/login?service=x",
            "http://jiaowu.juwp.edu.cn/Logon.do",
            JwUrls.SSO_WARMUP,
        )
        logins.forEach { url ->
            val js = JwWebFit.fitScript(url, desktop = true)
            assertEquals("登录页只给表单适配：$url", JwWebFit.LOGIN_FIT_JS, js)
            assertFalse(js!!.contains(JwWebFit.ATTR_ORIG))
            assertTrue(JwWebFit.isLoginLikeUrl(url))
        }
    }

    @Test
    fun blankUrlsGetNothing() {
        assertNull(JwWebFit.fitScript(null, desktop = true))
        assertNull(JwWebFit.fitScript("", desktop = true))
        assertNull(JwWebFit.fitScript("about:blank", desktop = true))
    }

    /** 关掉电脑版 = 与引入它之前逐字一致（回归闸）。 */
    @Test
    fun desktopOffIsByteForByteTheOldBehaviour() {
        // 课表页：仍然只注入课表适配
        assertEquals(JwWebFit.SCHEDULE_FIT_JS, JwWebFit.fitScript(JwUrls.SCHEDULE_LIST, desktop = false))
        // 登录页：仍然只注入表单适配（与 desktop 无关）
        assertEquals(JwWebFit.LOGIN_FIT_JS, JwWebFit.fitScript(JwUrls.SSO_WARMUP, desktop = false))
        // 其余页面：什么都不注入（旧行为就是"不碰"）
        assertNull(JwWebFit.fitScript(JwUrls.STUDENT_HOME, desktop = false))
        assertNull(JwWebFit.fitScript(JwUrls.SELECTION_CENTER, desktop = false))
        assertNull(JwWebFit.fitScript("https://jiaowupj.juwp.edu.cn/jwxsjdp", desktop = false))
    }

    /**
     * 弹层宽度收口（2026-10-08 用户报「选课结果弹窗显示不全」）。
     * 教务弹层是硬编码像素 + `left=(视口宽-弹窗宽)/2` 定位（实测 1500px 弹窗在 980 视口下
     * left=-260px），所以要 max-width 压过行内 width、并重新居中；**不动 top**。
     */
    @Test
    fun layerPopupIsClampedToViewportAndRecentered() {
        val css = JwWebFit.LAYER_FIT_CSS
        assertTrue(css.contains(".layui-layer"))
        // 贴着元素的提示气泡（教务用来显示被截断格子的全文）不能被居中，必须排除
        assertTrue(css.contains(":not(.layui-layer-tips)"))
        assertTrue(css.contains("max-width"))
        assertTrue(css.contains("!important"))
        assertTrue(css.contains("left:0"))
        assertTrue(css.contains("right:0"))
        assertTrue(css.contains("margin-left:auto"))
        // 弹层样式由桌面脚本注入、撤销脚本移除（关掉电脑版要逐字还原）
        assertTrue(JwWebFit.DESKTOP_JS.contains(JwWebFit.LAYER_STYLE_ID))
        assertTrue(JwWebFit.DESKTOP_REVERT_JS.contains(JwWebFit.LAYER_STYLE_ID))
    }

    /** 返回键先关弹层：脚本点弹层自己的关闭按钮，返回 1/0 供调用方判断。 */
    @Test
    fun closeTopLayerScriptClicksLayerCloseButton() {
        val js = JwWebFit.CLOSE_TOP_LAYER_JS
        assertTrue(js.contains(".layui-layer"))
        assertTrue(js.contains(".layui-layer-close"))
        assertTrue(js.contains("btn.click()"))
        assertTrue(js.contains("return 0"))
        assertTrue(js.contains("return 1"))
    }
}
