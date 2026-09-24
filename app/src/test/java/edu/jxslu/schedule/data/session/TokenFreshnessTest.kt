package edu.jxslu.schedule.data.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 落盘 token 的新鲜度（DESIGN §4.27）：判宽了会拿废 token 白跑，判严了白登一次。 */
class TokenFreshnessTest {

    private val t0 = 1_700_000_000_000L

    @Test
    fun neverSaved_isStale() {
        assertFalse(TokenFreshness.isFresh(0L, t0))
    }

    @Test
    fun justSaved_isFresh() {
        assertTrue(TokenFreshness.isFresh(t0, t0))
    }

    @Test
    fun withinMaxAge_isFresh() {
        assertTrue(TokenFreshness.isFresh(t0, t0 + TokenFreshness.MAX_AGE_MS))
    }

    @Test
    fun beyondMaxAge_isStale() {
        assertFalse(TokenFreshness.isFresh(t0, t0 + TokenFreshness.MAX_AGE_MS + 1))
    }

    /** 设备时钟被往回拨：宁可当它不新鲜，也不要拿一个「来自未来」的 token 去用。 */
    @Test
    fun clockMovedBackwards_isStale() {
        assertFalse(TokenFreshness.isFresh(t0, t0 - 1))
    }
}
