package edu.jxslu.schedule.data.jw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QiangzhiScheduleParserTest {

    @Test
    fun parseWeeksRangeAndList() {
        assertEquals(setOf(1, 2, 3, 4, 5), QiangzhiScheduleParser.parseWeeks("1-5"))
        assertEquals(setOf(2, 4, 6, 8), QiangzhiScheduleParser.parseWeeks("2,4,6,8"))
        assertEquals(setOf(6, 7, 8, 9, 10, 14, 15), QiangzhiScheduleParser.parseWeeks("6-10,14-15"))
    }

    @Test
    fun parseDetail() {
        val d = QiangzhiScheduleParser.parseDetail(
            "老师:唐刚;时间:11周[1-2节];地点:教学北大楼(北B102)",
        )
        assertEquals("唐刚", d.teacher)
        assertEquals("11周", d.weeksRaw)
        assertEquals(1 to 2, d.sections)
        assertTrue(d.position.contains("北B102"))
    }

    @Test
    fun toCourse() {
        val c = QiangzhiScheduleParser.toCourse(
            QiangzhiScheduleParser.RawItem(
                name = "液压与气压传动A",
                detail = "老师:唐刚;时间:7-11,14-15周[3-4节];地点:教学南大楼(南B206)",
                day = 1,
            ),
        )
        assertNotNull(c)
        assertEquals(1, c!!.day)
        assertEquals(3, c.startSection)
        assertEquals(4, c.endSection)
        assertEquals(setOf(7, 8, 9, 10, 11, 14, 15), c.weeks)
        assertEquals("唐刚", c.teacher)
    }

    @Test
    fun decodeExtractJson() {
        val json = """
            {"ok":true,"title":"个人课表信息","items":[
              {"name":"高数","detail":"老师:张;时间:1-8周[1-2节];地点:A101","day":3}
            ]}
        """.trimIndent()
        val items = QiangzhiScheduleParser.decodeExtractJson(json)
        assertEquals(1, items.size)
        assertEquals("高数", items[0].name)
        assertEquals(3, items[0].day)
    }

    @Test
    fun toCourseUsesDayFromColumnNotClass() {
        val c = QiangzhiScheduleParser.toCourse(
            QiangzhiScheduleParser.RawItem(
                name = "传感器与测试技术",
                detail = "老师:廖钱生;时间:1-16周[3-4节];地点:教学南大楼(南B206)",
                day = 3,
            ),
        )
        assertNotNull(c)
        assertEquals(3, c!!.day)
    }

    /**
     * 列序解析：第 0 列是节次标签，第 1–7 列是周一至周日。
     * 真实页面的 `li` 上挂着 `qz-hasCourse-1`（恒为 1），不能当星期来源。
     */
    @Test
    fun parseFromHtmlUsesColumnOrder() {
        val html = buildTable(
            row0 = listOf(
                cell("液压与气压传动A", "老师:唐刚;时间:11周[1-2节];地点:教学北大楼(北B102)", rowspan = 2),
                cell("传感器与测试技术", "老师:廖钱生;时间:6-8周[1-2节];地点:教学南大楼(南B206)"),
                emptyCell(), emptyCell(), emptyCell(), emptyCell(), emptyCell(),
            ),
            // 周一被上一行 rowspan 占掉，这一行第一个数据格应该是周二
            row1 = listOf(
                cell("机电传动控制B", "老师:陈磊;时间:1-10周[3-4节];地点:教学南大楼(南B106)"),
                emptyCell(), emptyCell(), emptyCell(), emptyCell(), emptyCell(),
            ),
        )
        val result = QiangzhiScheduleParser.parseFromHtml(html)
        assertTrue("解析应成功：$result", result is ImportParseResult.Success)
        val courses = (result as ImportParseResult.Success).courses
        assertEquals(1, courses.first { it.name == "液压与气压传动A" }.day)
        assertEquals(2, courses.first { it.name == "传感器与测试技术" }.day)
        assertEquals(
            "rowspan 合并后周二不应被算成周一",
            2,
            courses.first { it.name == "机电传动控制B" }.day,
        )
    }

    @Test
    fun parseFromHtmlWithoutRowspanKeepsColumnOrder() {
        val html = buildTable(
            row0 = listOf(
                cell("周一课", "老师:张;时间:1-4周[1-2节];地点:A101"),
                cell("周二课", "老师:李;时间:1-4周[1-2节];地点:A102"),
                cell("周三课", "老师:王;时间:1-4周[1-2节];地点:A103"),
                cell("周四课", "老师:赵;时间:1-4周[1-2节];地点:A104"),
                cell("周五课", "老师:钱;时间:1-4周[1-2节];地点:A105"),
                cell("周六课", "老师:孙;时间:1-4周[1-2节];地点:A106"),
                cell("周日课", "老师:周;时间:1-4周[1-2节];地点:A107"),
            ),
            row1 = listOf(
                emptyCell(), emptyCell(), emptyCell(), emptyCell(), emptyCell(), emptyCell(), emptyCell(),
            ),
        )
        val courses = (QiangzhiScheduleParser.parseFromHtml(html) as ImportParseResult.Success).courses
        assertEquals((1..7).toList(), courses.map { it.day }.sorted())
    }

    private fun buildTable(row0: List<String>, row1: List<String>): String {
        fun row(section: String, cells: List<String>) = buildString {
            append("<tr class=\"qz-weeklyTable-tr\">")
            append("<td class=\"qz-weeklyTable-td qz-weeklyTable-label\" name=\"timeTd\">")
            append(section)
            append("</td>")
            cells.forEach { append(it) }
            append("</tr>")
        }
        return buildString {
            append("<table class=\"qz-weeklyTable\"><tbody class=\"qz-weeklyTable-thbody\">")
            append(row("第一二节", row0))
            append(row("第三四节", row1))
            append("</tbody></table>")
        }
    }

    private fun cell(name: String, detail: String, rowspan: Int = 1): String =
        "<td rowspan=\"$rowspan\" name=\"kbDataTd\" class=\"qz-weeklyTable-td qz-hasCourse\">" +
            "<ul class=\"courselists\"><li class=\"courselists-item qz-hasCourse-1\">" +
            "<div class=\"qz-hasCourse-title qz-ellipse\">$name</div>" +
            "<p><span class=\"qz-hasCourse-abbrinfo\">$detail</span></p>" +
            "</li></ul></td>"

    private fun emptyCell(): String = "<td name=\"kbDataTd\" class=\"qz-weeklyTable-td\"></td>"

    private fun <T> assertNotNull(value: T?) {
        org.junit.Assert.assertNotNull(value)
    }
}
