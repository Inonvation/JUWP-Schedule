package edu.jxslu.schedule.ui.ebike

import org.junit.Assert.assertEquals
import org.junit.Test

/** 「地图缓存」卡的数据侧（DESIGN §3.9）：体积文案口径。 */
class EbikeMapCacheTest {

    @Test
    fun `体积文案与成绩单同一口径`() {
        assertEquals("0 B", EbikeMapCache.sizeLabel(0))
        assertEquals("512 B", EbikeMapCache.sizeLabel(512))
        assertEquals("1 KB", EbikeMapCache.sizeLabel(1024))
        assertEquals("12 KB", EbikeMapCache.sizeLabel(12 * 1024L))
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
