package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 课程卡右下角备注预览 / 上课提醒通知的备注段（2026-10-08）：
 * 空白给 null、超 [REMARK_PREVIEW_LIMIT] 字截断加省略号，两处共用同一口径。
 */
class RemarkPreviewTest {

    @Test
    fun `short remark shows as is`() {
        assertEquals("带课本", remarkPreview("带课本"))
    }

    @Test
    fun `blank remark gives null`() {
        assertNull(remarkPreview(""))
        assertNull(remarkPreview("   "))
        assertNull(remarkPreview(" \n "))
    }

    @Test
    fun `trims surrounding whitespace`() {
        assertEquals("带课本", remarkPreview("  带课本 \n"))
    }

    @Test
    fun `long remark truncated with ellipsis`() {
        val long = "这是一条很长的备注内容需要被截断处理才行别让它把卡片撑爆了"
        assertEquals(long.take(REMARK_PREVIEW_LIMIT) + "…", remarkPreview(long))
        assertEquals(REMARK_PREVIEW_LIMIT + 1, remarkPreview(long)!!.length)
    }

    @Test
    fun `exactly limit not truncated`() {
        val exact = "字".repeat(REMARK_PREVIEW_LIMIT)
        assertEquals(exact, remarkPreview(exact))
    }

    /** 通知正文契约：metaLine 在前、备注换行在后、无备注只有 meta（与 ClassReminder 同构）。 */
    @Test
    fun `notification line appends remark after meta`() {
        val slots = listOf(
            TimeSlot(1, "08:30", "09:10"),
            TimeSlot(2, "09:15", "09:55"),
        )
        val withRemark = Course(
            id = 0, name = "高等数学", teacher = "", position = "南B302", day = 1,
            startSection = 1, endSection = 2, weeks = (1..16).toSet(), remark = "带课本",
        )
        val noRemark = withRemark.copy(remark = "")

        assertEquals(
            metaLine(slots, withRemark) + "\n带课本",
            metaLine(slots, withRemark).let { m -> remarkPreview(withRemark.remark)?.let { "$m\n$it" } ?: m },
        )
        assertEquals(
            metaLine(slots, noRemark),
            metaLine(slots, noRemark).let { m -> remarkPreview(noRemark.remark)?.let { "$m\n$it" } ?: m },
        )
    }
}
