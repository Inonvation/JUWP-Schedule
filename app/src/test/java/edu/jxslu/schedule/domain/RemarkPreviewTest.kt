package edu.jxslu.schedule.domain

import edu.jxslu.schedule.ui.reminder.ReminderNotifications
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

    @Test
    fun `truncation never splits surrogate pair`() {
        // 19 个 BMP 字符 + 一个 emoji（代理对，2 个 code unit）：截断点落在代理对中间
        val remark = "字".repeat(19) + "\uD83D\uDE00"
        val out = remarkPreview(remark)!!
        // 末位不是孤立高代理（孤立半对渲染成替换符）
        org.junit.Assert.assertFalse(Character.isHighSurrogate(out[out.length - 2]))
        assertEquals("字".repeat(19) + "…", out)
    }

    /** 通知正文契约（直调 [ReminderNotifications.notificationLine]）：meta 在前、备注换行在后、无备注只有 meta。 */
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
        val noRemark = withRemark.copy(remark = "  ")

        assertEquals(
            "${metaLine(slots, withRemark)}\n带课本",
            ReminderNotifications.notificationLine(slots, withRemark),
        )
        // 无备注（含纯空白）= 原文 meta，一个字都不多
        assertEquals(metaLine(slots, noRemark), ReminderNotifications.notificationLine(slots, noRemark))
    }

    /** 通知里的备注同样受 20 字截断：长备注带省略号进通知。 */
    @Test
    fun `notification line truncates long remark`() {
        val slots = listOf(TimeSlot(1, "08:30", "09:10"))
        val course = Course(
            id = 0, name = "高等数学", teacher = "", position = "南B302", day = 1,
            startSection = 1, endSection = 1, weeks = (1..16).toSet(),
            remark = "字".repeat(REMARK_PREVIEW_LIMIT + 5),
        )
        assertEquals(
            "${metaLine(slots, course)}\n${"字".repeat(REMARK_PREVIEW_LIMIT)}…",
            ReminderNotifications.notificationLine(slots, course),
        )
    }
}
