package edu.jxslu.schedule.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 考试安排壳页学期解析（DESIGN §4.33）：缺省学期以教务为准，抽不到不猜。
 */
class ExamSyncTermFromShellTest {

    @Test
    fun `从 value 属性抽出选中项`() {
        val html = """
            <select id="xnxqid">
              <option value="2024-2025-2">2024-2025-2</option>
              <option value="2026-2027-1" selected="selected">2026-2027-1</option>
            </select>
        """.trimIndent()
        assertEquals("2026-2027-1", ExamSync.termFromShell(html))
    }

    @Test
    fun `没有 value 属性时取闭合标签前的文本`() {
        val html = """
            <select id="xnxqid">
              <option>2025-2026-1</option>
              <option selected>2026-2027-1</option>
            </select>
        """.trimIndent()
        assertEquals("2026-2027-1", ExamSync.termFromShell(html))
    }

    @Test
    fun `没有选中项时返回 null`() {
        val html = """
            <select id="xnxqid">
              <option value="2026-2027-1">2026-2027-1</option>
            </select>
        """.trimIndent()
        assertNull(ExamSync.termFromShell(html))
    }

    @Test
    fun `页面没有下拉时返回 null`() {
        assertNull(ExamSync.termFromShell("<html><body>系统功能暂未开放</body></html>"))
        assertNull(ExamSync.termFromShell(""))
    }

    @Test
    fun `data-selected 变体也能抽到`() {
        // 强智有的壳页把选中态写在 data-selected 上
        val html = """<option value="2026-2027-1" data-selected="true">2026-2027-1</option>"""
        assertEquals("2026-2027-1", ExamSync.termFromShell(html))
    }
}
