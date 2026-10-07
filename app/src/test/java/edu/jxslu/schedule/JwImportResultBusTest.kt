package edu.jxslu.schedule

import edu.jxslu.schedule.ui.jwvw.JwImportOutcome
import edu.jxslu.schedule.ui.jwvw.OutcomeQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // ---- 排队语义（2026-10-06：教材提示不该把导入气泡顶掉）----

    @Test
    fun `空闲时新消息直接上屏`() {
        val q = OutcomeQueue<String>()
        assertEquals("A", q.push("A"))
    }

    @Test
    fun `前一条没消费时后一条排队不覆盖`() {
        val q = OutcomeQueue<String>()
        q.push("A")
        assertEquals("在屏上的仍应是 A", "A", q.push("B"))
        assertEquals("A 下场后 B 顶上", "B", q.consume("A"))
        assertNull(q.consume("B"))
    }

    @Test
    fun `多条排队按先来后到`() {
        val q = OutcomeQueue<String>()
        q.push("A")
        q.push("B")
        q.push("C")
        assertEquals("B", q.consume("A"))
        assertEquals("C", q.consume("B"))
        assertNull(q.consume("C"))
    }

    @Test
    fun `两个消费者不会把没上屏的那条顶掉`() {
        // 分屏双窗口 / 转场期间两个落点页同时在组合里：第二个消费者拿着**旧值**来消费，
        // 不该推进队列（推进就把 B 吞了——丢消息，而不是重复消费）
        val q = OutcomeQueue<String>()
        q.push("A")
        q.push("B")
        assertEquals("B", q.consume("A"))
        assertEquals("拿着已下场的旧值再消费，仍是 B", "B", q.consume("A"))
        assertNull(q.consume("B"))
    }

    @Test
    fun `首屏没有东西时消费不推进`() {
        val q = OutcomeQueue<String>()
        q.push("A")
        q.push("B")
        assertEquals("首次调用传 null：原样返回在屏那条，不推进", "A", q.consume(null))
        assertEquals("B", q.consume("A"))
    }

    @Test
    fun `消费完再入队直接上屏`() {
        val q = OutcomeQueue<String>()
        q.push("A")
        assertNull(q.consume("A"))
        assertEquals("B", q.push("B"))
    }
}
