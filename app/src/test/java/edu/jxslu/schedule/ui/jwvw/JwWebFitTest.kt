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
 * 这里钉的是**注入什么**，不是"注入后长什么样"（那要靠真机点验）。四条最重要的：
 * ① 覆盖串必须整串替换（留着 `initial-scale` 就缩不小、留着 `user-scalable=no` 就放不大），
 *    且**必须带上自己算的 `initial-scale`**（不带就"切了看不出变化"，见对应用例）；
 * ② 登录 / SSO 页**绝不覆盖**（自动填表与会话链挂在它们身上，而它们本来就没有 meta）；
 * ③ 关掉电脑版时与引入之前**逐字一致**（回归闸）；
 * ④ 脚本"长什么样"与它跑起来"做了什么"分开：`scale` 这类只有引擎能给的量，实测口径记在
 *    [JwWebFit.DESKTOP_JS] 的注释里（Chromium 手机版 1080×2400 / dpr 2.625）。
 */
class JwWebFitTest {

    // ---- 桌面覆盖脚本本身 ----

    @Test
    fun desktopScriptReplacesWholeContentNotJustWidth() {
        val js = JwWebFit.DESKTOP_JS
        // 整串赋值：content 由 width=980（+ initial-scale，见下一个用例）现拼
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
     * 电脑版必须**带显式 `initial-scale`**（2026-10-08 用户报「手机版切电脑版界面不变，
     * 要手动缩小才看得出差异」）。
     *
     * 根因：Blink 只在 meta 里显式写了 `initial-scale` 时才重新套用缩放——只改 `width=980`
     * 的话它只重新布局（`iw` 412→980），缩放停在 1.0，屏幕里只看得见 980 宽布局的左边 412px，
     * 于是"看不出变化"。实测：`width=980` → `scale=1.000`；`width=980, initial-scale=0.4198`
     * → `scale=0.420`（当场生效）。倍数 = 屏幕 CSS 宽 / 980。
     */
    @Test
    fun desktopScriptCarriesExplicitInitialScaleSoSwitchTakesEffectAtOnce() {
        val js = JwWebFit.DESKTOP_JS
        // 拼串形态：宽度 + ', initial-scale=' + 现算倍数
        assertTrue(js.contains("width=${JwWebFit.DESKTOP_WIDTH_PX}, initial-scale=' +"))
        assertTrue(js.contains("css / ${JwWebFit.DESKTOP_WIDTH_PX}"))
        assertTrue(js.contains(".toFixed(4)"))
        // 屏幕宽取 visualViewport.width × scale（乘 scale 后与当前缩放无关，双指缩放不打架）
        assertTrue(js.contains("vv.width * vv.scale"))
        // 拿不到屏幕宽（Compose 首测 0×0）时退回裸宽度，不能拼出 initial-scale=0
        assertTrue(js.contains("if (css <= 0) return 'width=${JwWebFit.DESKTOP_WIDTH_PX}';"))
        // 幂等判据仍然只看 content（现拼的目标值），不是标记
        assertTrue(js.contains("var target = desktopContent();"))
        assertTrue(js.contains("if (m.getAttribute('content') !== target) {"))
    }

    /**
     * 旋转屏幕 / 首测 0×0 之后要重算（`resize`）：980 不再等于屏宽时，靠这条把倍数补对。
     * 双指缩放不触发重设（乘积不变 → content 逐字相同 → 幂等早退）。
     */
    @Test
    fun desktopScriptRecomputesOnResize() {
        val js = JwWebFit.DESKTOP_JS
        assertTrue(js.contains("window.addEventListener('resize', window.__juwViewportApply)"))
    }

    /**
     * 幂等判据必须是「content 已是我们的值」而不是「标记已在」：`onPageCommitVisible` 与
     * `onPageFinished` 两轮都会注入，第一轮可能跑在页面自己的 meta 之前，靠标记早退就再也补不上。
     */
    @Test
    fun desktopScriptIsIdempotentByContentSoSecondPassCanStillOverride() {
        val js = JwWebFit.DESKTOP_JS
        // 只在 content 不是目标值时改写（已是我们的值就什么都不做，反复注入无副作用）
        assertTrue(js.contains("if (m.getAttribute('content') !== target) {"))
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

    /**
     * 撤销脚本必须解除自愈观察者与那四个重算监听（2026-10-08 复查轮 P2 + 真机 WebView 实测）：
     * [JwWebFit.DESKTOP_JS] 装的东西都是常驻的——撤销只还原 meta 的话，
     * ① 页面下一次 head 变异（layui 弹 tips / 页面自己的 script）会把 980 压回去；
     * ② **还原视口这一步本身就派发 `resize`**，`resize` 监听不摘就当场被打回 980
     *    （用户看到「切回手机版没反应」）。②是 2026-10-08 用 CDP 在真机 WebView 里跑这两个脚本
     *    时踩到的，单测与 Chrome 侧替身脚本都看不出来。
     */
    @Test
    fun revertScriptDisconnectsSelfHealObserverAndResetsGuard() {
        val desktop = JwWebFit.DESKTOP_JS
        val revert = JwWebFit.DESKTOP_REVERT_JS
        // 装的时候存到 window 变量上（撤销才拿得到）
        assertTrue(desktop.contains("window.__juwViewportObserver = new MutationObserver(apply)"))
        assertTrue(desktop.contains("window.__juwViewportApply = apply"))
        // 撤销时断开观察者、摘掉四个监听、复位守卫闸
        assertTrue(revert.contains("window.__juwViewportObserver.disconnect()"))
        assertTrue(revert.contains("window.__juwViewportObserver = null"))
        listOf("DOMContentLoaded", "load", "pageshow", "resize").forEach { ev ->
            assertTrue("撤销要摘掉 $ev 监听", revert.contains("window.removeEventListener('$ev', f)"))
        }
        assertTrue(revert.contains("window.__juwViewportApply = null"))
        assertTrue(revert.contains("window.__juwViewportGuard = false"))
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
