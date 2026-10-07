package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 今日页课程卡教材名行：只取书名；单本直显、多本「书名1 等 N 本」（2026-10-08）；
 * 空名跳过、全空给 null。
 */
class TextbookTitlesLineTest {

    @Test
    fun `single title shows itself`() {
        assertEquals("高等数学（第七版）", textbookTitlesLine(listOf("高等数学（第七版）")))
    }

    @Test
    fun `multiple titles show first plus count`() {
        assertEquals("高等数学 等 2 本", textbookTitlesLine(listOf("高等数学", "线性代数")))
        assertEquals("高等数学 等 3 本", textbookTitlesLine(listOf("高等数学", "线性代数", "概率论")))
    }

    @Test
    fun `blank titles are skipped before counting`() {
        assertEquals("高等数学 等 2 本", textbookTitlesLine(listOf("高等数学", "  ", "", "  线性代数 ")))
    }

    @Test
    fun `all blank gives null`() {
        assertNull(textbookTitlesLine(listOf("", "  ")))
        assertNull(textbookTitlesLine(emptyList()))
    }
}
