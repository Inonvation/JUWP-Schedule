package edu.jxslu.schedule.data.jw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 教务 URL 判定的契约测试。
 *
 * 重点钉住成绩页不被当成课表页：成绩导入的自动触发（`autoImportOnScoreReady`）拿
 * [JwUrls.isScoreQueryUrl] 当闸门，若把它并进 [JwUrls.schedulePageKind]，课表页那套
 * 会跟着套到成绩页上——注入课表适配样式、并把成绩页误判成「理论课表就绪」。
 */
class JwUrlsTest {

    @Test
    fun isScoreQueryUrl_matchesScoreFormOnly() {
        assertTrue(JwUrls.isScoreQueryUrl(JwUrls.SCORE_FRM))
        // 页面可能带查询串（如从菜单跳转带上参数），仍要认出
        assertTrue(JwUrls.isScoreQueryUrl("${JwUrls.SCORE_FRM}?kksj=2025-2026-2"))

        assertFalse(JwUrls.isScoreQueryUrl(JwUrls.SCORE_LIST_API))
        assertFalse(JwUrls.isScoreQueryUrl(JwUrls.SCHEDULE_LIST))
        assertFalse(JwUrls.isScoreQueryUrl(JwUrls.LAB_SCHEDULE))
        assertFalse(JwUrls.isScoreQueryUrl(JwUrls.EXAM_QUERY))
        assertFalse(JwUrls.isScoreQueryUrl(JwUrls.STUDENT_HOME))
        assertFalse(JwUrls.isScoreQueryUrl(JwUrls.ENTRY))
        assertFalse(JwUrls.isScoreQueryUrl(null))
        assertFalse(JwUrls.isScoreQueryUrl(""))
    }

    @Test
    fun scorePageIsNotASchedulePage() {
        // 成绩页必须落在 None 档：课表页分支会注入课表适配样式并触发课表自动导入
        assertEquals(JwSchedulePage.None, JwUrls.schedulePageKind(JwUrls.SCORE_FRM))
        // 课表页判定不受影响
        assertEquals(JwSchedulePage.Theory, JwUrls.schedulePageKind(JwUrls.SCHEDULE_LIST))
        assertEquals(JwSchedulePage.Lab, JwUrls.schedulePageKind(JwUrls.LAB_SCHEDULE))
        assertEquals(JwSchedulePage.Exam, JwUrls.schedulePageKind(JwUrls.EXAM_QUERY))
    }
}
