package edu.jxslu.schedule.data.jw

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 教学日历解析（DESIGN §4.42）：fixture 结构照教务真实页（数据行单引号属性、
 * 工作日裸日号 + 周末 `MM月DD日`、页脚带学期），数据虚构。
 */
class TeachingCalendarParserTest {

    // ------------------------------------------------------------------
    // fixture
    // ------------------------------------------------------------------

    private fun weekRow(label: String, days: List<String>): String {
        val dayCells = days.joinToString("") { d ->
            "<td class='qz-weeklyTable-td qz-weeklyTable-normalDay '>" +
                "<div class='td-cell qz-flex-col'><div class='qz-weeklyTable-normalLable'>" +
                "<font color='black' size='2' face='宋体'>$d</font></div></div></td>"
        }
        return "<tr class='qz-weeklyTable-tr'>" +
            "<td class='qz-weeklyTable-td qz-weeklyTable-label' width='84px'>" +
            "<div class='td-cell qz-ellipse'>$label</div></td>$dayCells" +
            "<td class='qz-weeklyTable-td qz-weeklyTable-normalDay'><div class='td-cell'>" +
            "<div class='qz-weeklyTable-textarea'>" +
            "<textarea name='bz' placeholder='' class='layui-textarea' readonly></textarea>" +
            "</div></div></td></tr>"
    }

    private val allTerms = listOf(
        "2054-2055-1",
        "2099-2100-1",
        "2099-2100-2",
        "2099-2100-3",
        "2100-2101-1",
        "2100-2101-2",
    )

    private fun page(
        selected: String? = "2099-2100-1",
        footer: String? = "2099-2100-1",
        week1: List<String> = autumnWeek1,
    ): String {
        val select = if (selected == null) {
            ""
        } else {
            val options = allTerms.joinToString("\n") { t ->
                if (t == selected) {
                    """<option value="$t"   selected="selected">$t</option>"""
                } else {
                    """<option value="$t"  >$t</option>"""
                }
            }
            """
            <select name="xnxq01id" id="xnxq01id" lay-filter="xnxq" lay-verify="">
                $options
            </select>
            """.trimIndent()
        }
        val footerRow = if (footer == null) {
            ""
        } else {
            "<tr class='qz-weeklyTable-tr'>" +
                "<td class='qz-weeklyTable-td qz-weeklyTable-label'>" +
                "<div class='td-cell qz-ellipse'>周历编制</div></td>" +
                "<td class='qz-weeklyTable-td qz-weeklyTable-detailtext' colspan='8'>" +
                "<div class='td-cell'>$footer</div></td></tr>"
        }
        return """
            <html><head><title>教学周历查看</title></head><body>
            $select
            <table class="qz-weeklyTable">
            <tr class="qz-weeklyTable-tr">
                <th class="qz-weeklyTable-th">周次</th>
                <th class="qz-weeklyTable-th">星期一</th>
                <th class="qz-weeklyTable-th">星期二</th>
                <th class="qz-weeklyTable-th">星期三</th>
                <th class="qz-weeklyTable-th">星期四</th>
                <th class="qz-weeklyTable-th">星期五</th>
                <th class="qz-weeklyTable-th">星期六</th>
                <th class="qz-weeklyTable-th">星期日</th>
                <th class="qz-weeklyTable-th">备注</th>
            </tr>
            ${weekRow("第1周", week1)}
            ${weekRow("第2周", listOf("14", "15", "16", "17", "18", "09月19日", "09月20日"))}
            $footerRow
            </table></body></html>
        """.trimIndent()
    }

    // ------------------------------------------------------------------
    // cases
    // ------------------------------------------------------------------

    @Test
    fun `秋学期解析出第 1 周周一`() {
        val cal = TeachingCalendarParser.parse(page(), "2099-2100-1")
        assertEquals(LocalDate.of(2099, 9, 7), cal?.startDate)
        assertEquals("2099-2100-1", cal?.term)
    }

    @Test
    fun `春学期年份取次年年`() {
        // 2100-02-22（周一）那一周：周六 02月27日
        val week1 = listOf("22", "23", "24", "25", "26", "02月27日", "02月28日")
        val cal = TeachingCalendarParser.parse(
            page(selected = "2099-2100-2", footer = "2099-2100-2", week1 = week1),
            "2099-2100-2",
        )
        assertEquals(LocalDate.of(2100, 2, 22), cal?.startDate)
    }

    @Test
    fun `跨月形态的裸日号（31→01）也解析正确`() {
        // 2054-09-05 是周六：第 1 周周一 = 2054-08-31（周一格写 31、周二格写 01，跨月不带月份）
        val week1 = listOf("31", "01", "02", "03", "04", "09月05日", "09月06日")
        val cal = TeachingCalendarParser.parse(
            page(selected = "2054-2055-1", footer = "2054-2055-1", week1 = week1),
            "2054-2055-1",
        )
        assertEquals(LocalDate.of(2054, 8, 31), cal?.startDate)
    }

    @Test
    fun `-3 学期号拒收`() {
        val html = page(selected = "2099-2100-3", footer = "2099-2100-3")
        assertNull(TeachingCalendarParser.parse(html, "2099-2100-3"))
    }

    @Test
    fun `周六日不存在的日期拒收（非闰年 02月29日）`() {
        val week1 = listOf("22", "23", "24", "25", "26", "02月29日", "03月01日")
        val html = page(selected = "2100-2101-2", footer = "2100-2101-2", week1 = week1)
        assertNull(TeachingCalendarParser.parse(html, "2100-2101-2"))
    }

    @Test
    fun `登录页返回 null`() {
        val html = "<html><head><title>登录</title></head><body>请先登录系统</body></html>"
        assertNull(TeachingCalendarParser.parse(html, "2099-2100-1"))
    }

    @Test
    fun `响应学期与请求不符返回 null`() {
        val html = page(selected = "2100-2101-1", footer = "2100-2101-1")
        assertNull(TeachingCalendarParser.parse(html, "2099-2100-1"))
    }

    @Test
    fun `选择器缺失时页脚兜底可用`() {
        val cal = TeachingCalendarParser.parse(
            page(selected = null, footer = "2099-2100-1"),
            "2099-2100-1",
        )
        assertEquals(LocalDate.of(2099, 9, 7), cal?.startDate)
    }

    @Test
    fun `周末格缺少日期返回 null`() {
        val week1 = listOf("07", "08", "09", "10", "11", "", "")
        assertNull(TeachingCalendarParser.parse(page(week1 = week1), "2099-2100-1"))
    }

    @Test
    fun `周一日号对不上返回 null`() {
        // 周六说 09月12日（周一应为 07），但周一格写的是 08
        val week1 = listOf("08", "09", "09", "10", "11", "09月12日", "09月13日")
        assertNull(TeachingCalendarParser.parse(page(week1 = week1), "2099-2100-1"))
    }

    @Test
    fun `周六格不是周六日期返回 null`() {
        // 2099-09-13 实际是周日——年份推导出的日期对不上星期，拒收
        val week1 = listOf("08", "09", "10", "11", "12", "09月13日", "09月14日")
        assertNull(TeachingCalendarParser.parse(page(week1 = week1), "2099-2100-1"))
    }

    @Test
    fun `非法学期号（年份不连续）返回 null`() {
        val html = page(selected = "2099-2101-1", footer = "2099-2101-1")
        assertNull(TeachingCalendarParser.parse(html, "2099-2101-1"))
    }

    @Test
    fun `缺少第 1 周行返回 null`() {
        val html = page().replace("第1周", "第9周")
        assertNull(TeachingCalendarParser.parse(html, "2099-2100-1"))
    }

    private companion object {
        /** 2099-09-07（周一）那一周：周六 09月12日、周日 09月13日。 */
        val autumnWeek1 = listOf("07", "08", "09", "10", "11", "09月12日", "09月13日")
    }
}
