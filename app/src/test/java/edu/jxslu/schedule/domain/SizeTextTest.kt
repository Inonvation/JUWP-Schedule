package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class SizeTextTest {

    @Test
    fun formatsBytesKilobytesAndMegabytes() {
        assertEquals("0 B", SizeText.format(0))
        assertEquals("512 B", SizeText.format(512))
        assertEquals("1023 B", SizeText.format(1023))
        assertEquals("1 KB", SizeText.format(1024))
        assertEquals("371 KB", SizeText.format(380_479))
        assertEquals("1023 KB", SizeText.format(1_048_575))
        assertEquals("1.0 MB", SizeText.format(1L shl 20))
        assertEquals("1.5 MB", SizeText.format(1_572_864))
    }
}
