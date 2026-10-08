package edu.jxslu.schedule.ui.jwvw

import edu.jxslu.schedule.data.jw.JwUrls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 教务窗口的返回语义（DESIGN §4.4.4，2026-10-08）。
 *
 * 用户报「有时候按返回没有用」。这里钉住两条最容易踩的：
 * ① 历史里与当前页**同址**的条目（重定向 / 同址重载留下）——退过去页面一模一样；
 * ② **CAS / SSO / 登录页**——退到登录页对用户毫无意义。
 * 两条都该跳过；跳无可跳就返回 null（调用方关窗口）。
 */
class JwWebBackTest {

    private val home = JwUrls.STUDENT_HOME
    private val center = JwUrls.SELECTION_CENTER
    private val round = JwUrls.selectionRoundUrl("ABC123")!!

    @Test
    fun normalHistoryGoesBackOneStep() {
        assertEquals(-1, JwWebBack.backDelta(listOf(home, center), 1))
    }

    /** 同址条目（重定向/同址重载）退过去看不出变化 → 跳过它，一步退到真正不同的那一页。 */
    @Test
    fun duplicateUrlEntriesAreSkipped() {
        assertEquals(-2, JwWebBack.backDelta(listOf(home, center, center), 2))
        // 连着两条同址也要跳过
        assertEquals(-3, JwWebBack.backDelta(listOf(home, center, center, center), 3))
    }

    /** 只差 fragment 视为同一页（同文档锚点跳转）。 */
    @Test
    fun sameUrlIgnoringFragmentIsSkipped() {
        assertEquals(-2, JwWebBack.backDelta(listOf(home, "$center#tab1", "$center#tab2"), 2))
    }

    /** 认证链（CAS 域 / cas/login / Logon.do / sso.jsp）不退：那是登录页，不是"上一页"。 */
    @Test
    fun authChainEntriesAreSkipped() {
        val sso = JwUrls.SSO_WARMUP
        val cas = "${JwUrls.CAS_BASE}/cas/login?service=x"
        val logon = "${JwUrls.XSD_BASE}/Logon.do"
        assertEquals(-3, JwWebBack.backDelta(listOf(home, sso, cas, center), 3))
        assertEquals(-2, JwWebBack.backDelta(listOf(home, logon, center), 2))
        assertTrue(JwWebBack.isAuthChainUrl(sso))
        assertTrue(JwWebBack.isAuthChainUrl(cas))
        assertTrue(JwWebBack.isAuthChainUrl(logon))
        assertTrue(JwWebBack.isAuthChainUrl("https://eapp2.juwp.edu.cn:9443/sso/login"))
        // 业务页不是认证链
        assertTrue(!JwWebBack.isAuthChainUrl(center))
    }

    /** 跳无可跳 = null（关窗口），别把用户留在"按了没反应"的状态里。 */
    @Test
    fun nothingWorthGoingBackToMeansClose() {
        assertNull(JwWebBack.backDelta(listOf(JwUrls.SSO_WARMUP, JwUrls.SSO_WARMUP), 1))
        assertNull(JwWebBack.backDelta(listOf(center), 0))
        assertNull(JwWebBack.backDelta(listOf(center, center), 1))
        // 全是认证链 → 关窗口
        assertNull(JwWebBack.backDelta(listOf(JwUrls.SSO_WARMUP, "${JwUrls.CAS_BASE}/cas/login"), 1))
    }

    /** 空条目 / about:blank 跳过（自动落页与占位导航会留下）。 */
    @Test
    fun blankEntriesAreSkipped() {
        assertEquals(-2, JwWebBack.backDelta(listOf(home, null, center), 2))
        assertEquals(-2, JwWebBack.backDelta(listOf(home, "about:blank", center), 2))
    }

    /** 越界索引不崩，按"没得退"处理。 */
    @Test
    fun outOfRangeIndexIsSafe() {
        assertNull(JwWebBack.backDelta(emptyList(), 0))
        assertNull(JwWebBack.backDelta(listOf(home, center), 5))
        assertNull(JwWebBack.backDelta(listOf(home, center), -1))
    }

    /** 混合场景：登录链 + 同址 + 正常页，应一步退到最近的业务页。 */
    @Test
    fun mixedHistoryLandsOnNearestBusinessPage() {
        val entries = listOf(
            JwUrls.SSO_WARMUP,                       // 0 认证
            "${JwUrls.CAS_BASE}/cas/login",          // 1 认证
            home,                                    // 2 业务（教务主页）
            center,                                  // 3 业务
            center,                                  // 4 同址（重定向/重载留下）
            round,                                   // 5 当前（进入选课）
        )
        assertEquals(-1, JwWebBack.backDelta(entries, 5))   // 当前→center(4)：是另一页，退一步
        assertEquals(-2, JwWebBack.backDelta(entries, 4))   // center(4)→home(2)：跳过同址的 center(3)
        assertEquals(-1, JwWebBack.backDelta(entries, 3))   // center(3)→home(2)
        assertNull(JwWebBack.backDelta(entries, 2))         // 主页之前只有认证链 → 关窗口
    }
}
