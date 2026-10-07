package edu.jxslu.schedule.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 实习基地快照缓存规则（DESIGN §4.43）：新鲜期边界逐条钉住。 */
class InternshipCacheRulesTest {

    @Test
    fun `新鲜期内为真且含边界`() {
        val t = 1_000_000L
        assertTrue(InternshipCacheRules.isFresh(t, t))
        assertTrue(InternshipCacheRules.isFresh(t, t + InternshipCacheRules.TTL_MS))
        assertFalse(InternshipCacheRules.isFresh(t, t + InternshipCacheRules.TTL_MS + 1))
    }

    @Test
    fun `未来时间戳视为不新鲜`() {
        assertFalse(InternshipCacheRules.isFresh(2_000_000L, 1_000_000L))
    }

    @Test
    fun `缓存判定矩阵`() {
        val t = 1_000_000L
        // 非强刷 + 抓过 + 新鲜 → 用缓存
        assertTrue(InternshipCacheRules.shouldUseCache(force = false, fetchedAt = t, now = t + 1))
        // 强刷 → 永远不走缓存
        assertFalse(InternshipCacheRules.shouldUseCache(force = true, fetchedAt = t, now = t + 1))
        // 从没抓过（fetchedAt = 0）→ 不走缓存
        assertFalse(InternshipCacheRules.shouldUseCache(force = false, fetchedAt = 0L, now = t))
        // 过期 → 不走缓存
        assertFalse(
            InternshipCacheRules.shouldUseCache(
                force = false,
                fetchedAt = t,
                now = t + InternshipCacheRules.TTL_MS + 1,
            ),
        )
        // 抓过但 0 条（权威空）也走缓存：页面要如实显示空态
        assertTrue(InternshipCacheRules.shouldUseCache(force = false, fetchedAt = t, now = t))
    }
}
