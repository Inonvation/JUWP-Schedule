package edu.jxslu.schedule

import edu.jxslu.schedule.domain.BikeNearby
import edu.jxslu.schedule.domain.Gcj02
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WGS84 → GCJ-02（DESIGN §4.23）。
 *
 * 这里**没有硬编码的"标准答案"坐标对**：手头没有权威的官方对照表，随手抄一组网上的
 * 数字写进断言，测试就变成橡皮图章了。改为锁住几件与数据源无关、错了就一定会露出来的性质：
 * 境外不偏移、境内偏移量在合理量级、确定性、邻近两点转换后相对距离不变。
 * 最后一条正是本功能的用法（把地图中心落到用户所在的那一片）。
 */
class Gcj02Test {

    @Test
    fun `境外坐标原样返回`() {
        // 东京、纽约、悉尼，以及原点（经度 0 必然在判定矩形之外）
        val samples = listOf(
            35.68 to 139.76,
            40.71 to -74.00,
            -33.87 to 151.21,
            0.0 to 0.0,
        )
        samples.forEach { (lat, lng) ->
            val point = Gcj02.toGcj02(lat, lng)
            assertEquals("境外不该偏移（lat=$lat lng=$lng）", lat, point.lat, 1e-12)
            assertEquals("境外不该偏移（lat=$lat lng=$lng）", lng, point.lng, 1e-12)
        }
    }

    @Test
    fun `境内偏移量在几百米量级`() {
        // 天安门、南昌、广州三个纬度跨度较大的点
        val samples = listOf(
            39.9087 to 116.3975,
            28.6883 to 116.0285,
            23.1291 to 113.2644,
        )
        samples.forEach { (lat, lng) ->
            val point = Gcj02.toGcj02(lat, lng)
            val shift = BikeNearby.distanceMeters(lat, lng, point.lat, point.lng)
            assertTrue(
                "偏移量应落在 100~1000 米，实际 $shift 米（lat=$lat lng=$lng）",
                shift in 100.0..1000.0,
            )
        }
    }

    @Test
    fun `邻近两点转换后相对距离不变`() {
        // 相隔约 1 公里的两点：偏移算法在几公里尺度上近似刚性平移，
        // 所以转换前后彼此的间距应该几乎一样。差得多就说明算法被改坏了
        val a = 28.6883 to 116.0285
        val b = 28.6973 to 116.0285
        val before = BikeNearby.distanceMeters(a.first, a.second, b.first, b.second)

        val ga = Gcj02.toGcj02(a.first, a.second)
        val gb = Gcj02.toGcj02(b.first, b.second)
        val after = BikeNearby.distanceMeters(ga.lat, ga.lng, gb.lat, gb.lng)

        assertEquals("转换前后相对距离应几乎一致", before, after, 10.0)
    }

    @Test
    fun `同一输入结果稳定`() {
        val first = Gcj02.toGcj02(28.6883, 116.0285)
        val second = Gcj02.toGcj02(28.6883, 116.0285)
        assertEquals(first.lat, second.lat, 0.0)
        assertEquals(first.lng, second.lng, 0.0)
    }

    @Test
    fun `非法坐标原样返回不产生 NaN 偏移`() {
        listOf(
            Double.NaN to 116.0,
            28.0 to Double.NaN,
            Double.POSITIVE_INFINITY to 116.0,
            28.0 to Double.NEGATIVE_INFINITY,
        ).forEach { (lat, lng) ->
            val point = Gcj02.toGcj02(lat, lng)
            assertEquals(lat, point.lat, 0.0)
            assertEquals(lng, point.lng, 0.0)
        }
    }

    @Test
    fun `国境判定矩形的边界`() {
        assertTrue(Gcj02.isOutsideChina(56.0, 116.0))
        assertTrue(Gcj02.isOutsideChina(28.0, 71.9))
        assertTrue(Gcj02.isOutsideChina(28.0, 138.0))
        assertTrue(Gcj02.isOutsideChina(0.5, 116.0))
        assertFalse(Gcj02.isOutsideChina(28.6883, 116.0285))
        assertFalse(Gcj02.isOutsideChina(50.0, 100.0))
    }

    @Test
    fun `极值坐标不崩`() {
        listOf(
            90.0 to 180.0,
            -90.0 to -180.0,
            55.8271 to 137.8347,
            0.8293 to 72.004,
        ).forEach { (lat, lng) ->
            val point = Gcj02.toGcj02(lat, lng)
            assertTrue("结果必须有限（lat=$lat lng=$lng）", point.lat.isFinite() && point.lng.isFinite())
        }
    }
}