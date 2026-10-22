package edu.jxslu.schedule.ui.ebike

import org.junit.Assert.assertEquals
import org.junit.Test

/** 「地图缓存」卡的数据侧（DESIGN §3.9）。 */
class EbikeMapCacheTest {

    @Test
    fun `体积文案委托 SizeText`() {
        // 格式化细节在 SizeTextTest
        assertEquals("1.5 MB", EbikeMapCache.sizeLabel(1024L * 1024 + 512 * 1024))
    }

    @Test
    fun `两块都为 0 才算空`() {
        assertEquals(true, EbikeMapCache.Usage(0, 0).isEmpty)
        assertEquals(false, EbikeMapCache.Usage(1, 0).isEmpty)
        assertEquals(false, EbikeMapCache.Usage(0, 1).isEmpty)
        assertEquals(4096L, EbikeMapCache.Usage(1024, 3072).totalBytes)
    }
}
