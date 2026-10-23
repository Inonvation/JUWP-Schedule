package edu.jxslu.schedule.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QiekjAutoSignInRulesTest {

    private val today = "2026-10-24"

    @Test
    fun enabled_loggedIn_newDay_runs() {
        assertTrue(QiekjAutoSignInRules.shouldRun(true, true, "2026-10-23", today))
        assertTrue(QiekjAutoSignInRules.shouldRun(true, true, null, today))
        assertTrue(QiekjAutoSignInRules.shouldRun(true, true, "", today))
    }

    @Test
    fun sameDay_skips() {
        assertFalse(QiekjAutoSignInRules.shouldRun(true, true, today, today))
    }

    @Test
    fun disabled_skips() {
        assertFalse(QiekjAutoSignInRules.shouldRun(false, true, null, today))
    }

    @Test
    fun notLoggedIn_skips() {
        assertFalse(QiekjAutoSignInRules.shouldRun(true, false, null, today))
    }
}
