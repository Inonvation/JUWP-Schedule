package edu.jxslu.schedule.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 理论课表页学期抽取（DESIGN §4.17 重立）：学期是调课检查与基线对齐的唯一钥匙，
 * 抽错学期的数据会被当成「课表大变动」，所以这里钉住三种真实形态与两个陷阱：
 * - 页面可能不止一个下拉，必须认 `id="xnxq01id"` 那个（整页搜 selected 会串）；
 * - value 非学期格式时退回文本，文本也散了才返回 null（调用方报失败，不猜）。
 */
class ScheduleSyncTermFromHtmlTest {

    @Test
    fun `value 属性形态`() {
        val html = """<select id="xnxq01id"><option value="2025-2026-2">2025-2026-2</option>
            <option value="2026-2027-1" selected>2026-2027-1</option></select>"""
        assertEquals("2026-2027-1", ScheduleSync.termFromHtml(html))
    }

    @Test
    fun `selected 写在文本前的 value 里且页面另有别的下拉`() {
        // 别的 select 先出现、也带 selected：抽取必须限定在 xnxq01id 那块内
        val html = """<select id="xqlb"><option selected>全部</option></select>
            <select name="xnxq01id" id="xnxq01id"><option value="2026-2027-1" selected="selected">2026-2027-1</option></select>"""
        assertEquals("2026-2027-1", ScheduleSync.termFromHtml(html))
    }

    @Test
    fun `无 value 属性时退回 option 文本`() {
        val html = """<select id="xnxq01id"><option selected>2026-2027-1</option></select>"""
        assertEquals("2026-2027-1", ScheduleSync.termFromHtml(html))
    }

    @Test
    fun `没有学期下拉或选中项不是学期格式时返回 null`() {
        assertNull(ScheduleSync.termFromHtml("<html><body>登录页</body></html>"))
        assertNull("""<select id="xnxq01id"><option selected>请选择</option></select>""".let { ScheduleSync.termFromHtml(it) })
    }
}
