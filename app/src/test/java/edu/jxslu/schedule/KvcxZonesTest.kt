package edu.jxslu.schedule

import edu.jxslu.schedule.domain.KvcxZones
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 还车点 / 禁停区图层解析（DESIGN §3.9）。
 *
 * fixture 逐字段对齐解包产物里的消费代码（`service/area/index.js` 的
 * `loadNearbyAreaPolygons`）：`givecarList[]` 带 `lat/lng + scopeArray`，
 * `nogoZoneList[].scopeArray`，另有我们不画的 `servicesiteZoneList`。
 */
class KvcxZonesTest {

    @Test
    fun `解析还车点与禁停区`() {
        val json = """
            {"resultCode":1,"errorCode":0,"result":{
              "servicesiteZoneList":[{"servicesiteId":7,"scopeArray":[{"lat":28.6,"lng":115.8}]}],
              "givecarList":[
                {"lat":28.681,"lng":115.851,"scopeArray":[{"lat":28.681,"lng":115.851},{"lat":28.682,"lng":115.852},{"lat":28.680,"lng":115.853}],"device":{"name":"东门"}},
                {"lat":"28.690","lng":"115.860","scopeArray":[]}
              ],
              "nogoZoneList":[{"scopeArray":[{"lat":28.7,"lng":115.9},{"lat":28.71,"lng":115.91},{"lat":28.70,"lng":115.92}]}]
            }}
        """.trimIndent()

        val zones = KvcxZones.parse(json)

        assertEquals(2, zones.parkSpots.size)
        assertEquals(28.681, zones.parkSpots[0].lat, 0.0001)
        assertEquals(3, zones.parkSpots[0].outline.size)
        // 数值给字符串也认（快趣字段类型不稳定，与 KqcxAuth 同一教训）
        assertEquals(28.690, zones.parkSpots[1].lat, 0.0001)
        assertTrue(zones.parkSpots[1].outline.isEmpty())
        assertEquals(1, zones.nogoZones.size)
        assertEquals(3, zones.nogoZones[0].size)
        // 服务区那层我们不画（已有手绘校园围栏），解析层直接忽略它，别当成还车点
        assertEquals(false, zones.isEmpty)
    }

    @Test
    fun `坏点与脏数据被丢掉而不是整层失败`() {
        val json = """
            {"resultCode":1,"errorCode":0,"result":{
              "givecarList":[
                {"lng":115.851},
                {"lat":28.681,"lng":"abc"},
                {"lat":28.682,"lng":115.852}
              ],
              "nogoZoneList":[
                {"scopeArray":[{"lat":1.0,"lng":2.0}]},
                {"scopeArray":[{"lat":1.0,"lng":2.0},{"lat":1.1,"lng":2.1},{"lat":1.0,"lng":2.2}]}
              ]
            }}
        """.trimIndent()

        val zones = KvcxZones.parse(json)

        // 只剩第三个车号；缺 lat / lng 非数的一律丢
        assertEquals(1, zones.parkSpots.size)
        // 不足三点的"禁停区"不是多边形（画不出来），丢掉
        assertEquals(1, zones.nogoZones.size)
    }

    @Test
    fun `只看本校只筛还车点不筛禁停区`() {
        val json = """
            {"resultCode":1,"errorCode":0,"result":{
              "givecarList":[
                {"lat":28.68,"lng":115.85},
                {"lat":28.75,"lng":115.95}
              ],
              "nogoZoneList":[{"scopeArray":[{"lat":28.75,"lng":115.95},{"lat":28.76,"lng":115.96},{"lat":28.75,"lng":115.97}]}]
            }}
        """.trimIndent()
        val zones = KvcxZones.parse(json)

        // 围栏只认第一个点
        val filtered = zones.campusOnly { lat, _ -> lat < 28.7 }

        assertEquals(1, filtered.parkSpots.size)
        assertEquals(28.68, filtered.parkSpots[0].lat, 0.0001)
        // 禁停区是安全提示，不跟着筛掉
        assertEquals(1, filtered.nogoZones.size)
        // 原对象不被改动（纯函数）
        assertEquals(2, zones.parkSpots.size)
    }

    @Test
    fun `空结果与坏 JSON 都回空图层`() {
        assertTrue(KvcxZones.parse("""{"resultCode":1,"errorCode":0,"result":{"givecarList":[],"nogoZoneList":[]}}""").isEmpty)
        assertTrue(KvcxZones.parse("""{"resultCode":0,"errorCode":500,"resultMsg":"x"}""").isEmpty)
        assertTrue(KvcxZones.parse("not json").isEmpty)
        assertTrue(KvcxZones.EMPTY.isEmpty)
    }
}
