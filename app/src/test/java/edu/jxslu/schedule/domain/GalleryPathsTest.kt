package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GalleryPathsTest {

    @Test
    fun `旧相册文件路径改写到新目录`() {
        assertEquals(
            "/storage/emulated/0/Pictures/JUWPHub/ebike-100000669.jpg",
            GalleryPaths.rewriteLegacyFilePath(
                "/storage/emulated/0/Pictures/水贝贝/ebike-100000669.jpg",
            ),
        )
    }

    @Test
    fun `非旧目录路径不改写`() {
        assertNull(GalleryPaths.rewriteLegacyFilePath("/storage/emulated/0/Pictures/Other/x.jpg"))
        assertNull(GalleryPaths.rewriteLegacyFilePath("content://media/external/images/media/1"))
        assertNull(GalleryPaths.rewriteLegacyFilePath("/storage/emulated/0/Pictures/水贝贝2/x.jpg"))
    }
}
