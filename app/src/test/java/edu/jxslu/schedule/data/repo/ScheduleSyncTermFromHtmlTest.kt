package edu.jxslu.schedule.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 理论课表页学期抽取的**调用点**：学期是调课检查与基线对齐的唯一钥匙，
 * 抽错学期的数据会被当成「课表大变动」。通用形态（value / 文本 / 格式校验）在
 * `JwHtmlTest`，这里只钉 `id="xnxq01id"` 收窄与抽不到时返回 null（调用方报失败，不猜）。
 */
class ScheduleSyncTermFromHtmlTest {

    @Test
    fun `selected 写在文本前的 value 里且页面另有别的下拉`() {
        // 别的 select 先出现、也带 selected：抽取必须限定在 xnxq01id 那块内
        val html = """<select id="xqlb"><option selected>全部</option></select>
            <select name="xnxq01id" id="xnxq01id"><option value="2026-2027-1" selected="selected">2026-2027-1</option></select>"""
        assertEquals("2026-2027-1", ScheduleSync.termFromHtml(html))
    }

    @Test
    fun `没有学期下拉或选中项不是学期格式时返回 null`() {
        assertNull(ScheduleSync.termFromHtml("<html><body>登录页</body></html>"))
        assertNull("""<select id="xnxq01id"><option selected>请选择</option></select>""".let { ScheduleSync.termFromHtml(it) })
    }
}
