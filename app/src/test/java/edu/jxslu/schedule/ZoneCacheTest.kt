package edu.jxslu.schedule

import edu.jxslu.schedule.domain.KvcxParkSpot
import edu.jxslu.schedule.domain.KvcxZones
import edu.jxslu.schedule.domain.ZoneCache
import edu.jxslu.schedule.domain.ZoneCacheEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 停车点 / 禁停区图层的缓存策略（DESIGN §3.9，2026-09-28；2026-09-29 收小覆盖半径）。
 *
 * 校园尺度取点：默认中心 28.688 / 116.028；0.0005 度纬度约 55 米，0.06 度约 6.7 公里。
 * **覆盖半径只有 50 米**：接口回的是"离查询点最近的 15 个"，实测挪 100 米就换掉 5 个、
 * 挪 400 米换掉 13 个，所以缓存只能服务"几乎同一视野"（进页补种与微调）。
 */
class ZoneCacheTest {

    /** 用还车点的 lat 当这条缓存的"身份标记"，方便断言拿到的是哪一条。 */
    private fun entry(lat: Double, lng: Double, at: Long, tag: Double = lat) = ZoneCacheEntry(
        lat = lat,
        lng = lng,
        fetchedAt = at,
        zones = KvcxZones(
            parkSpots = listOf(KvcxParkSpot(lat = tag, lng = lng, outline = emptyList())),
            nogoZones = emptyList(),
        ),
    )

    private fun tagOf(entry: ZoneCacheEntry?): Double =
        entry?.zones?.parkSpots?.first()?.lat ?: -1.0

    @Test
    fun `覆盖半径内取最新的一条`() {
        val old = entry(28.6880, 116.0285, at = 1_000L, tag = 1.0)
        val newest = entry(28.6882, 116.0283, at = 2_000L, tag = 2.0) // 约 25 米，覆盖
        val far = entry(28.75, 116.10, at = 3_000L, tag = 3.0) // 超出半径

        val best = ZoneCache.bestCovering(listOf(old, newest, far), 28.688, 116.028)

        // 取最新而不是最近：两条都覆盖时，新拉到的那条才是服务端当前的说法
        assertEquals(2.0, tagOf(best), 0.0001)
    }

    @Test
    fun `超出覆盖半径不命中`() {
        val far = entry(28.75, 116.10, at = 1_000L)
        assertNull(ZoneCache.bestCovering(listOf(far), 28.688, 116.028))
        assertNull(ZoneCache.bestCovering(emptyList(), 28.688, 116.028))
    }

    /**
     * 回归（2026-09-29 用户报「移到有停车区的地方却刷不出来」）：覆盖半径**不能放大**。
     *
     * 接口回的是"离查询点最近的 15 个"，实测挪 400 米真实 15 个里 13 个不在旧集合里——
     * 半径一旦到几百米，就等于拿旧视野的点冒充新视野的图层。这条钉死"挪出去必须重拉"。
     */
    @Test
    fun `挪出去几百米必须重新拉`() {
        val atCenter = entry(28.6883, 116.0285, at = 1_000L)
        // 东 400 米（0.0042 度经度 ≈ 410 米）
        assertNull(ZoneCache.bestCovering(listOf(atCenter), 28.6883, 116.0327))
        // 北 100 米（0.0009 度纬度 ≈ 100 米）：实测这个距离就会换掉 5/15 个点，同样不吃缓存
        assertNull(ZoneCache.bestCovering(listOf(atCenter), 28.6892, 116.0285))
    }

    @Test
    fun `新鲜期按 TTL 判定边界`() {
        val e = entry(28.688, 116.028, at = 1_000_000L)
        assertTrue(ZoneCache.isFresh(e, 1_000_000L))
        assertTrue(ZoneCache.isFresh(e, 1_000_000L + ZoneCache.TTL_MS - 1))
        // 恰好到点就不算新鲜：过期即重拉（仍会先摆旧图层，见 fetchZones）
        assertFalse(ZoneCache.isFresh(e, 1_000_000L + ZoneCache.TTL_MS))
    }

    @Test
    fun `写入替换同片区旧条目而不是越攒越多`() {
        val old = entry(28.688, 116.028, at = 1_000L)
        val sameArea = entry(28.6881, 116.0281, at = 2_000L) // 约 14 米，算同一片

        val result = ZoneCache.put(listOf(old), sameArea)

        assertEquals(1, result.size)
        assertEquals(2_000L, result[0].fetchedAt)
    }

    @Test
    fun `写入超出替换半径则新增一条`() {
        val a = entry(28.688, 116.028, at = 1_000L)
        val b = entry(28.70, 116.028, at = 2_000L) // 约 1.3 公里

        assertEquals(2, ZoneCache.put(listOf(a), b).size)
    }

    @Test
    fun `写入超过上限只留最新的若干条`() {
        var entries = emptyList<ZoneCacheEntry>()
        // 各条间隔 0.02 度（约 2.2 公里）：既不在替换半径内，也互不覆盖
        repeat(ZoneCache.MAX_ENTRIES + 3) { index ->
            entries = ZoneCache.put(
                entries,
                entry(lat = 28.60 + index * 0.02, lng = 116.00, at = 1_000L + index),
            )
        }

        assertEquals(ZoneCache.MAX_ENTRIES, entries.size)
        // 最旧的三条已被丢掉；最新的那条还在
        assertTrue(entries.none { it.fetchedAt <= 1_000L + 2 })
        assertTrue(entries.any { it.fetchedAt == 1_000L + ZoneCache.MAX_ENTRIES + 2 })
    }
}
