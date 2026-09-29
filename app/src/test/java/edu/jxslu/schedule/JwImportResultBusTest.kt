package edu.jxslu.schedule

import edu.jxslu.schedule.ui.jwvw.JwImportOutcome
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 教务导入结果通道的新鲜期（DESIGN §3.3，2026-09-29）。
 *
 * 闸门的意义：导入窗口 finish 后，用户可能落在二级页（「我的 → 学校统一认证」那条路），
 * 课表页要等切 Tab 才组合。没有新鲜期，十分钟前导完的消息会在用户随手切回课表页时冒出来。
 * 阈值本身是拍脑袋的常数，但**边界必须钉住**——写错一个符号就变成「永远新鲜」或「永不显示」。
 */
class JwImportResultBusTest {

    @Test
    fun `刚发布的消息算新鲜`() {
        val outcome = JwImportOutcome(text = "已导入 12 门", publishedAtMs = 1_000L)
        assertTrue(outcome.isFresh(nowMs = 1_000L))
        assertTrue(outcome.isFresh(nowMs = 1_000L + JwImportOutcome.FRESH_WINDOW_MS))
    }

    @Test
    fun `超过新鲜期就不再展示`() {
        val outcome = JwImportOutcome(text = "已导入 12 门", publishedAtMs = 1_000L)
        assertFalse(outcome.isFresh(nowMs = 1_000L + JwImportOutcome.FRESH_WINDOW_MS + 1))
    }

    @Test
    fun `新鲜期至少够读完完成弹窗`() {
        // 用户要看弹窗上的门数与覆盖/合并口径再点「完成」，一秒两秒不够
        assertTrue(JwImportOutcome.FRESH_WINDOW_MS >= 30_000L)
    }
}
