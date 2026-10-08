package edu.jxslu.schedule.data.wenqu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 问渠进程内 TTL 缓存（DESIGN §4.45）。
 *
 * 这块是「每次进页都重新获取」的正面修复点，所以单测钉住三件事：
 * 新鲜期内命中、过期即失效、超量按写入序淘汰。
 */
class WenquTtlCacheTest {

    private val ttl = 30 * 60 * 1000L

    @Test
    fun `新鲜期内命中_不用再取数`() {
        val cache = WenquTtlCache<String, String>(ttl)
        cache.put("", "第一页", 1_000L)
        assertEquals("第一页", cache.get("", 1_000L))
        assertEquals("第一页", cache.get("", 1_000L + ttl - 1))
    }

    @Test
    fun `到点即失效_并顺手删掉`() {
        val cache = WenquTtlCache<String, String>(ttl)
        cache.put("", "第一页", 1_000L)
        assertNull(cache.get("", 1_000L + ttl))
        // 已经删了：再查一次还是 null，不会「过期值复活」
        assertNull(cache.get("", 1_000L + ttl))
    }

    @Test
    fun `不同关键词各存一份`() {
        val cache = WenquTtlCache<String, String>(ttl)
        cache.put("", "全量", 1_000L)
        cache.put("支教", "搜索结果", 1_000L)
        assertEquals("全量", cache.get("", 1_000L))
        assertEquals("搜索结果", cache.get("支教", 1_000L))
        assertNull(cache.get("志愿", 1_000L))
    }

    @Test
    fun `超量按写入序淘汰最旧的一条`() {
        val cache = WenquTtlCache<String, String>(ttl, maxEntries = 2)
        cache.put("a", "1", 1_000L)
        cache.put("b", "2", 2_000L)
        cache.put("c", "3", 3_000L)
        assertNull(cache.get("a", 3_000L))
        assertEquals("2", cache.get("b", 3_000L))
        assertEquals("3", cache.get("c", 3_000L))
    }

    @Test
    fun `重写同一条会排到最新_不会被先淘汰`() {
        val cache = WenquTtlCache<String, String>(ttl, maxEntries = 2)
        cache.put("a", "1", 1_000L)
        cache.put("b", "2", 2_000L)
        // 翻页会重写同一条（累计结果变长）：它该变成最新，淘汰的是 b 而不是 a
        cache.put("a", "1+2", 3_000L)
        cache.put("c", "3", 4_000L)
        assertEquals("1+2", cache.get("a", 4_000L))
        assertNull(cache.get("b", 4_000L))
    }

    @Test
    fun `clear 之后一条都不留`() {
        val cache = WenquTtlCache<String, String>(ttl)
        cache.put("", "第一页", 1_000L)
        cache.clear()
        assertNull(cache.get("", 1_000L))
    }
}
