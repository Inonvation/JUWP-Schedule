package edu.jxslu.schedule.data.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 登录闸门（DESIGN §4.27）：窗口、计数口径、停用、会话可信期。
 *
 * 这几条是防「自动续登把账号试到锁」的全部闸门，改动前先看这里的用例。
 */
class LoginGateRulesTest {

    private val t0 = 1_700_000_000_000L

    @Test
    fun freshState_allowsAttempt() {
        assertTrue(LoginGateRules.canAttempt(LoginGateState(), t0))
        assertFalse(LoginGateRules.sessionTrusted(LoginGateState(), t0))
    }

    /**
     * 成功留下的时间戳不该算作「刚失败过」。
     *
     * 真机上踩到的是**旧版本写下的数据**：那时 `afterSuccess` 也会写 `last_attempt`，
     * 于是升级后 5 分钟内的冷启动会被窗口挡住重登，用户看到「明明登录了还要再登」。
     */
    @Test
    fun canAttempt_ignoresAttemptThatSucceeded() {
        val legacy = LoginGateState(lastSuccessMs = t0, lastAttemptMs = t0)
        assertTrue(LoginGateRules.canAttempt(legacy, t0 + 1))
        assertTrue(LoginGateRules.canAttempt(legacy, t0 + LoginGateRules.REPEAT_WINDOW_MS - 1))

        // 真正失败留下的时间戳仍然要被窗口挡住
        val failed = LoginGateRules.afterCredentialFailure(LoginGateState(), t0)
        assertFalse(LoginGateRules.canAttempt(failed, t0 + 1))
        assertTrue(LoginGateRules.canAttempt(failed, t0 + LoginGateRules.REPEAT_WINDOW_MS))
    }

    /**
     * 成功之后：10 分钟内信会话，但**不留重复窗口**——窗口只由失败驱动。
     *
     * 曾经把 `last_attempt` 也推到成功时刻，结果进程重启后 cookie 丢了、时间戳还在，
     * 需要立刻重登时被那个窗口挡住（`Failed("刚刚登录失败过")`），用户看到的就是
     * 「明明登录了还要再登一次」。这条钉的就是它。
     */
    @Test
    fun afterSuccess_trustsSessionWithoutBlockingNextAttempt() {
        val after = LoginGateRules.afterSuccess(t0)
        assertTrue(LoginGateRules.canAttempt(after, t0))
        assertTrue(LoginGateRules.canAttempt(after, t0 + 60_000))
        assertTrue(LoginGateRules.sessionTrusted(after, t0 + LoginGateRules.SESSION_TRUST_MS))
        assertFalse(LoginGateRules.sessionTrusted(after, t0 + LoginGateRules.SESSION_TRUST_MS + 1))
    }

    /** 连续两次凭证错即停用——中途一次网络错不该把它推向停用。 */
    @Test
    fun credentialFailures_suspendOnlyAfterThreshold() {
        var s = LoginGateRules.afterCredentialFailure(LoginGateState(), t0)
        assertFalse(s.suspended)
        assertEquals(1, s.consecutiveCredentialFailures)

        s = LoginGateRules.afterTransientFailure(s, t0 + 1)
        assertEquals(1, s.consecutiveCredentialFailures)
        assertFalse(s.suspended)

        s = LoginGateRules.afterCredentialFailure(s, t0 + 2)
        assertTrue(s.suspended)
        assertFalse(LoginGateRules.canAttempt(s, t0 + LoginGateRules.REPEAT_WINDOW_MS * 10))
    }

    /** 验证码 / 网络 / 5xx 都不计数：它们的成因不在密码上。 */
    @Test
    fun transientFailures_neverSuspend() {
        var s = LoginGateState()
        repeat(10) { s = LoginGateRules.afterTransientFailure(s, t0 + it) }
        assertEquals(0, s.consecutiveCredentialFailures)
        assertFalse(s.suspended)
    }

    /** 成功一次把计数清零，但不解除停用（停用只能靠更新密码 reset）。 */
    @Test
    fun success_clearsCounters() {
        val suspended = LoginGateRules.afterCredentialFailure(
            LoginGateRules.afterCredentialFailure(LoginGateState(), t0),
            t0,
        )
        assertTrue(suspended.suspended)
        val after = LoginGateRules.afterSuccess(t0 + 1)
        assertEquals(0, after.consecutiveCredentialFailures)
        assertFalse(after.suspended)
        assertTrue(LoginGateRules.sessionTrusted(after, t0 + 2))
    }

    @Test
    fun reset_clearsEverything() {
        val dirty = LoginGateRules.afterCredentialFailure(LoginGateState(), t0, maxFailures = 1)
        assertTrue(dirty.suspended)
        val clean = LoginGateRules.reset()
        assertFalse(clean.suspended)
        assertEquals(0, clean.consecutiveCredentialFailures)
        assertEquals(0L, clean.lastSuccessMs)
    }

    /**
     * 自动填表登录的跨窗口节流。
     *
     * 页面级的「只自动一次」挡不住重开窗口：密码改过时每开一次页面就撞一次 CAS。
     */
    @Test
    fun autoLoginThrottle_blocksRepeatWithinWindow() {
        assertTrue(LoginGateRules.shouldAutoLogin(lastAutoLoginMs = 0L, nowMs = t0))
        assertFalse(LoginGateRules.shouldAutoLogin(lastAutoLoginMs = t0, nowMs = t0 + 1))
        assertFalse(
            LoginGateRules.shouldAutoLogin(
                lastAutoLoginMs = t0,
                nowMs = t0 + LoginGateRules.AUTO_LOGIN_WINDOW_MS - 1,
            ),
        )
        assertTrue(
            LoginGateRules.shouldAutoLogin(
                lastAutoLoginMs = t0,
                nowMs = t0 + LoginGateRules.AUTO_LOGIN_WINDOW_MS,
            ),
        )
    }

    /**
     * 凭证已被判错到停用时，自动填表一律不放行——那时密码就是错的，再填一次只是多撞
     * 一遍 CAS 的失败计数，正好是账号锁定最敏感的形状。
     */
    @Test
    fun autoLoginThrottle_blockedWhenSuspended() {
        assertFalse(
            LoginGateRules.shouldAutoLogin(
                lastAutoLoginMs = 0L,
                nowMs = t0,
                suspended = true,
            ),
        )
        // 窗口早已过去也一样
        assertFalse(
            LoginGateRules.shouldAutoLogin(
                lastAutoLoginMs = t0,
                nowMs = t0 + LoginGateRules.AUTO_LOGIN_WINDOW_MS * 10,
                suspended = true,
            ),
        )
    }
}
