package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 「最近导出」的文件名解析与保留策略（DESIGN §4.25）。
 *
 * 这一层是纯逻辑，落盘 I/O 在 `TranscriptStore`；把「认哪些文件、留哪几份」抽出来，
 * 是为了让保留策略有测试，而不是靠读代码确认。
 */
class TranscriptHistoryTest {

    @Test
    fun treatsPdfAsEntryAndPartialAsJunk() {
        assertTrue(TranscriptHistory.isEntryName("成绩单-2025-2026-2-20260924-1928.pdf"))
        assertTrue(TranscriptHistory.isEntryName("x.PDF"))
        assertFalse(TranscriptHistory.isEntryName("x.txt"))
        assertFalse(TranscriptHistory.isEntryName(""))

        // 写盘中间态同时带 .pdf 与 .part：必须先判 .part，否则半成品会被当记录列出来
        assertTrue(TranscriptHistory.isPartialName("a.pdf.part"))
        assertFalse(TranscriptHistory.isEntryName("a.pdf.part"))
    }

    @Test
    fun derivesLabelFromFileName() {
        assertEquals(
            "2025-2026-2",
            TranscriptHistory.labelOf("成绩单-2025-2026-2-20260924-1928.pdf"),
        )
        assertEquals(
            "2025-2026-2等4个学期",
            TranscriptHistory.labelOf("成绩单-2025-2026-2等4个学期-20260924-1928.pdf"),
        )
        assertEquals("全部学期", TranscriptHistory.labelOf("成绩单-全部学期-20260924-1928.pdf"))
        // 前缀变了（老版本文件）也不许让标题变空
        assertEquals("custom", TranscriptHistory.labelOf("custom-20260924-1928.pdf"))
        // 没有时间戳时只去掉前缀与后缀，标签仍可读
        assertEquals("x", TranscriptHistory.labelOf("成绩单-x.pdf"))
        // 空串不抛异常
        assertEquals("", TranscriptHistory.labelOf(""))
    }

    @Test
    fun sortsNewestFirstAndBreaksTiesByName() {
        val entries = listOf(
            entry("a.pdf", at = 100),
            entry("c.pdf", at = 300),
            entry("b.pdf", at = 300),
            entry("d.pdf", at = 200),
        )
        assertEquals(
            // 同秒写入的两份（连点两次导出）按名字倒排，顺序稳定
            listOf("c.pdf", "b.pdf", "d.pdf", "a.pdf"),
            TranscriptHistory.sortNewestFirst(entries).map { it.name },
        )
    }

    @Test
    fun prunesEverythingBeyondKeep() {
        val entries = (1..12).map { entry("f$it.pdf", at = it * 1000L) }
        // 最新在前 → 丢掉最旧的两份
        assertEquals(listOf("f2.pdf", "f1.pdf"), TranscriptHistory.filesToPrune(entries, keep = 10))
        // 不足 keep 时一份都不删
        assertTrue(TranscriptHistory.filesToPrune(entries.take(3), keep = 10).isEmpty())
        // keep 为 0 视为不留
        assertEquals(12, TranscriptHistory.filesToPrune(entries, keep = 0).size)
        assertTrue(TranscriptHistory.filesToPrune(emptyList(), keep = 10).isEmpty())
    }

    @Test
    fun rejectsNamesThatCouldEscapeTheDirectory() {
        assertTrue(TranscriptHistory.isSafeName("成绩单-2025-2026-2-20260924-1928.pdf"))
        assertFalse(TranscriptHistory.isSafeName(""))
        assertFalse(TranscriptHistory.isSafeName("   "))
        assertFalse(TranscriptHistory.isSafeName("../notes_img/secret.jpg"))
        assertFalse(TranscriptHistory.isSafeName("a\\b.pdf"))
    }

    @Test
    fun formatsTimeAndSize() {
        val zone = ZoneId.of("Asia/Shanghai")
        val millis = ZonedDateTime.of(2026, 9, 24, 19, 28, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("2026-09-24 19:28", TranscriptHistory.timeLabel(millis, zone))

        assertEquals("371 KB", TranscriptHistory.sizeLabel(380_479))
        assertEquals("1 KB", TranscriptHistory.sizeLabel(1024))
        assertEquals("1023 KB", TranscriptHistory.sizeLabel(1_048_575))
        assertEquals("1.5 MB", TranscriptHistory.sizeLabel(1_572_864))
        assertEquals("512 B", TranscriptHistory.sizeLabel(512))
    }

    private fun entry(name: String, at: Long) =
        TranscriptEntry(name = name, sizeBytes = 1000, modifiedAtMillis = at)
}
