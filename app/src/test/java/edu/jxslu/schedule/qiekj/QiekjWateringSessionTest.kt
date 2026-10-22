package edu.jxslu.schedule.qiekj

import edu.jxslu.schedule.data.qiekj.QiekjWateringSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 胖乖「出水中」本地会话的过期判定（2026-10-21）：服务端 165 秒超时关阀，
 * 本地留到 5 分钟——超过即视为流程已不在（进程被杀 / 页面被关），读时清掉。
 */
class QiekjWateringSessionTest {

    private val start = 1_000_000L

    @Test
    fun freshSessionIsActive() {
        val session = QiekjWateringSession(start, "2号开水机", "goods-1")
        assertFalse(session.isStale(start))
        assertFalse(session.isStale(start + 60_000L))
        assertFalse(session.isStale(start + QiekjWateringSession.STALE_MILLIS - 1))
    }

    @Test
    fun sessionGoesStaleAtThreshold() {
        val session = QiekjWateringSession(start, "2号开水机", null)
        assertTrue(session.isStale(start + QiekjWateringSession.STALE_MILLIS))
        assertTrue(session.isStale(start + QiekjWateringSession.STALE_MILLIS + 1))
    }

    @Test
    fun staleWindowCoversServerTimeout() {
        // 服务端 165 秒自动关阀：窗口必须比它长（结算/轮询还要余量），
        // 否则正常流程还没收尾，卡片就先消失了
        assertTrue(QiekjWateringSession.STALE_MILLIS > 165_000L)
    }

    @Test
    fun progressFractionTracksAutoClose() {
        val session = QiekjWateringSession(start, "2号开水机", null)
        assertEquals(0f, session.progressFraction(start))
        assertEquals(
            0.5f,
            session.progressFraction(start + QiekjWateringSession.AUTO_CLOSE_SECONDS * 500L),
            0.001f,
        )
        assertEquals(
            1f,
            session.progressFraction(start + QiekjWateringSession.AUTO_CLOSE_SECONDS * 1_000L),
        )
        // 服务端默认已经关阀之后仍不出界（等 5 分钟过期窗口收行）
        assertEquals(1f, session.progressFraction(start + 10 * 60_000L))
    }
}
