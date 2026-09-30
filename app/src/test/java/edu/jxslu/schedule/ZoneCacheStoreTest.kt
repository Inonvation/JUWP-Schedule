package edu.jxslu.schedule

import edu.jxslu.schedule.data.kqcx.ZoneCacheStore
import edu.jxslu.schedule.domain.GcjPoint
import edu.jxslu.schedule.domain.KvcxParkSpot
import edu.jxslu.schedule.domain.KvcxZones
import edu.jxslu.schedule.domain.ZoneCacheEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 停车点缓存文件的往返（DESIGN §3.9）：存进去什么、读出来还得是什么。
 *
 * 缓存文件格式是隐式契约——字段改名/漏默认值造成的"读旧文件读空"是静默回归（页面只是
 * 少一层图层，没人会报错），所以用临时文件往返一遍钉住。影子行类型与本体字段一一对应。
 */
class ZoneCacheStoreTest {

    private fun tempFile(): File =
        File.createTempFile("zones", ".json").also { it.delete() }

    @Test
    fun `条目往返保留坐标与多边形`() = runBlocking {
        val file = tempFile()
        try {
            val store = ZoneCacheStore(file)
            val entries = listOf(
                ZoneCacheEntry(
                    lat = 28.688,
                    lng = 116.028,
                    fetchedAt = 1_700_000_000_000L,
                    zones = KvcxZones(
                        parkSpots = listOf(
                            KvcxParkSpot(
                                lat = 28.6881,
                                lng = 116.0281,
                                outline = listOf(
                                    GcjPoint(28.6881, 116.0281),
                                    GcjPoint(28.6882, 116.0282),
                                    GcjPoint(28.6880, 116.0283),
                                ),
                                name = "教学北大楼左侧",
                            ),
                        ),
                        nogoZones = listOf(
                            listOf(GcjPoint(28.69, 116.03), GcjPoint(28.70, 116.04), GcjPoint(28.71, 116.05)),
                        ),
                    ),
                ),
            )

            store.save(entries)
            val back = store.load()

            assertEquals(1, back.size)
            assertEquals(28.688, back[0].lat, 1e-9)
            assertEquals(1_700_000_000_000L, back[0].fetchedAt)
            assertEquals(1, back[0].zones.parkSpots.size)
            assertEquals(3, back[0].zones.parkSpots[0].outline.size)
            assertEquals(28.6882, back[0].zones.parkSpots[0].outline[1].lat, 1e-9)
            // 点位名（2026-09-30 加的列）必须一起过一遍文件，丢了列表就只剩「还车点」
            assertEquals("教学北大楼左侧", back[0].zones.parkSpots[0].displayName)
            assertEquals(1, back[0].zones.nogoZones.size)
            assertEquals(3, back[0].zones.nogoZones[0].size)
            // 往返后文件确实在，且占用可统计（「地图缓存」卡要用）
            assertTrue(store.bytes() > 0)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `坏文件与不存在的文件都回空表`() = runBlocking {
        val file = tempFile()
        try {
            val store = ZoneCacheStore(file)
            // 不存在
            assertEquals(0, store.load().size)
            // 是坏 JSON
            file.writeText("{不是数组")
            assertEquals(0, store.load().size)
            // 结构对不上（少了新字段的旧文件由默认值兜住）
            file.writeText("""[{"lat":1.0,"lng":2.0,"fetchedAt":3}]""")
            val back = store.load()
            assertEquals(1, back.size)
            assertTrue(back[0].zones.isEmpty)
            // 老版本写下的还车点没有 `name` 列：读出来是空串，而不是整份文件解码失败
            file.writeText(
                """[{"lat":1.0,"lng":2.0,"fetchedAt":3,"spots":[{"lat":1.1,"lng":2.1,"outline":[]}]}]""",
            )
            val legacy = store.load()
            assertEquals(1, legacy.size)
            assertEquals(1, legacy[0].zones.parkSpots.size)
            assertEquals("还车点", legacy[0].zones.parkSpots[0].displayName)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `删除后读回空表`() = runBlocking {
        val file = tempFile()
        try {
            val store = ZoneCacheStore(file)
            store.save(listOf(ZoneCacheEntry(1.0, 2.0, 3L, KvcxZones.EMPTY)))
            assertTrue(store.bytes() > 0)
            store.delete()
            assertEquals(0, store.load().size)
        } finally {
            file.delete()
        }
    }
}
