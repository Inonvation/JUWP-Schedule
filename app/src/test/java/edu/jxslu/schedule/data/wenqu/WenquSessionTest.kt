package edu.jxslu.schedule.data.wenqu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 问渠会话完整性（2026-10-20）：**密码不是必要条件**。
 *
 * 「记住登录」默认关时只落 token；`isComplete` 仍为 true（JWT 有效期内照常可用），
 * 但 `canRefresh` 为 false——到期后必须重新登录，不能拿空密码去撞平台。
 */
class WenquSessionTest {

    private fun session(password: String = "", token: String = "t") =
        WenquSession(
            phone = "13800000000",
            password = password,
            token = token,
            expiresAt = 0L,
        )

    @Test
    fun tokenOnlySession_isComplete_butCannotRefresh() {
        val s = session(password = "")
        assertTrue(s.isComplete)
        assertFalse(s.canRefresh)
    }

    @Test
    fun savedPassword_canRefresh() {
        val s = session(password = "p")
        assertTrue(s.isComplete)
        assertTrue(s.canRefresh)
    }

    @Test
    fun missingToken_isNotComplete() {
        assertFalse(session(token = "").isComplete)
    }
}
