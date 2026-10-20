package edu.jxslu.schedule.data.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 学籍卡补抓闸门（DESIGN §3.3）：抓不到时不能每次进页都重试。 */
class ProfileSyncRulesTest {

    @Test
    fun profileIncompleteAndNeverTried_attempts() {
        assertTrue(
            ProfileSyncRules.shouldAttempt(
                profileIncomplete = true,
                lastAttemptDate = null,
                today = "2026-09-24",
            ),
        )
    }

    @Test
    fun profileComplete_neverAttempts() {
        assertFalse(
            ProfileSyncRules.shouldAttempt(
                profileIncomplete = false,
                lastAttemptDate = null,
                today = "2026-09-24",
            ),
        )
        assertFalse(
            ProfileSyncRules.shouldAttempt(
                profileIncomplete = false,
                lastAttemptDate = "2026-09-23",
                today = "2026-09-24",
            ),
        )
    }

    /** 今天已经试过（哪怕没抓到）就不再试——这是「别白打教务」的全部依据。 */
    @Test
    fun triedToday_doesNotRetry() {
        assertFalse(
            ProfileSyncRules.shouldAttempt(
                profileIncomplete = true,
                lastAttemptDate = "2026-09-24",
                today = "2026-09-24",
            ),
        )
    }

    @Test
    fun triedYesterday_retries() {
        assertTrue(
            ProfileSyncRules.shouldAttempt(
                profileIncomplete = true,
                lastAttemptDate = "2026-09-23",
                today = "2026-09-24",
            ),
        )
    }
}
