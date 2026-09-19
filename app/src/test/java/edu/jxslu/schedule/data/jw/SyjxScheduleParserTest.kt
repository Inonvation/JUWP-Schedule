package edu.jxslu.schedule.data.jw

import edu.jxslu.schedule.domain.CourseKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 实验课表解析。
 *
 * fixture 按真实页面结构拼：每个周次 6 行，首行 9 个 td（多一列周次标签），
 * 其余行 8 个 td；课块里同时放上 `qz-tooltipContent-*` 的假节次，用来验证不会误抓。
 */
class SyjxScheduleParserTest {

    @Test
    fun parseSectionsAcceptsRangeAndSingle() {
        assertEquals(3 to 4, SyjxScheduleParser.parseSections("3-4"))
        assertEquals(11 to 11, SyjxScheduleParser.parseSections("11"))
        assertEquals(1 to 2, SyjxScheduleParser.parseSections(" 1 - 2 "))
        assertNull(SyjxScheduleParser.parseSections("第一二节"))
    }

    /** 同一门课在第 1、3 周各出一块 → 必须聚合成一条，weeks 取并集。 */
    @Test
    fun mergesBlocksOfSameCourseAcrossWeeks() {
        val html = table(
            weekBlock(
                1,
                "1-2" to cells(),
                "3-4" to cells(null, null, null, null, null, "机电传动控制B|工程训练中心207", null),
                "5-6" to cells(),
                "7-8" to cells(),
                "9-10" to cells(),
                "11" to cells(),
            ),
            weekBlock(
                3,
                "1-2" to cells(),
                "3-4" to cells(null, null, null, null, null, "机电传动控制B|工程训练中心207", null),
                "5-6" to cells(),
                "7-8" to cells(),
                "9-10" to cells(),
                "11" to cells(),
            ),
        )
        val courses = coursesOf(html)
        assertEquals("跨周的同门课应合并为一条", 1, courses.size)
        assertEquals(setOf(1, 3), courses[0].weeks)
        assertEquals(6, courses[0].day)
        assertEquals(3, courses[0].startSection)
        assertEquals(4, courses[0].endSection)
        assertEquals("工程训练中心207", courses[0].position)
        assertEquals(CourseKind.Lab, courses[0].kind)
        assertEquals("实验课表页不提供教师，应为空", "", courses[0].teacher)
    }

    /**
     * 同名课在不同实训室上课 → 不能合并成一条，否则地点会被吞掉。
     * 实测「机械制造基础A」就分布在 212 / 105 / 403 三个房间。
     */
    @Test
    fun keepsSameNameCoursesWithDifferentPositionsApart() {
        val html = table(
            weekBlock(
                4,
                "1-2" to cells(), "3-4" to cells(), "5-6" to cells(),
                "7-8" to cells(null, null, null, null, null, "机械制造基础A|工程训练中心212", null),
                "9-10" to cells(), "11" to cells(),
            ),
            weekBlock(
                7,
                "1-2" to cells(), "3-4" to cells(), "5-6" to cells(),
                "7-8" to cells(null, null, null, null, null, "机械制造基础A|工程训练中心105", null),
                "9-10" to cells(), "11" to cells(),
            ),
        )
        val courses = coursesOf(html)
        assertEquals("同名但不同实训室的课不能合并成一条", 2, courses.size)
        // 用地点索引而不是下标：解析结果按位置排序，断言不该依赖排序细节
        val byPosition = courses.associateBy { it.position }
        assertEquals(setOf(4), byPosition.getValue("工程训练中心212").weeks)
        assertEquals(setOf(7), byPosition.getValue("工程训练中心105").weeks)
    }

    /** 8-td 行里最后一格是星期日（day=7），不要少算一列。 */
    @Test
    fun lastColumnIsSunday() {
        val html = table(
            weekBlock(
                2,
                "1-2" to cells(null, null, null, null, null, null, "机械装备结构与设计|工程训练中心107"),
                "3-4" to cells(), "5-6" to cells(), "7-8" to cells(), "9-10" to cells(), "11" to cells(),
            ),
        )
        val courses = coursesOf(html)
        assertEquals(1, courses.size)
        assertEquals(7, courses[0].day)
    }

    /** 周次首行（9 个 td）同时承载节次 1-2，列序要按行形态右对齐。 */
    @Test
    fun firstRowOfWeekUsesShiftedColumnIndex() {
        val html = table(
            weekBlock(
                6,
                "1-2" to cells("人机交互技术|工程训练中心210", null, null, null, null, null, null),
                "3-4" to cells(), "5-6" to cells(), "7-8" to cells(), "9-10" to cells(), "11" to cells(),
            ),
        )
        val courses = coursesOf(html)
        assertEquals(1, courses.size)
        assertEquals("首个数据格是星期一", 1, courses[0].day)
        assertEquals(1, courses[0].startSection)
        assertEquals(2, courses[0].endSection)
        assertEquals(setOf(6), courses[0].weeks)
    }

    /** 单值节次标签 `11` 应解析为第 11 节的单小节课程。 */
    @Test
    fun singleSectionLabel() {
        val html = table(
            weekBlock(
                5,
                "1-2" to cells(), "3-4" to cells(), "5-6" to cells(), "7-8" to cells(), "9-10" to cells(),
                "11" to cells(null, "PLC原理及应用B|工程训练中心208", null, null, null, null, null),
            ),
        )
        val courses = coursesOf(html)
        assertEquals(1, courses.size)
        assertEquals(11, courses[0].startSection)
        assertEquals(11, courses[0].endSection)
        assertEquals(2, courses[0].day)
    }

    /** 直接喂 WebView 注入脚本的输出。 */
    @Test
    fun decodesInjectedJson() {
        val payload = """
            {"ok":true,"title":"实验课表","url":"http://jiaowu.juwp.edu.cn:8080/jsxsd/syjx/toXskb.do",
             "items":[
               {"name":"机电传动控制B","position":"工程训练中心207","day":6,"week":1,"sections":"3-4"},
               {"name":"机电传动控制B","position":"工程训练中心207","day":6,"week":3,"sections":"3-4"},
               {"name":"液压与气压传动A","position":"工程训练中心201","day":5,"week":14,"sections":"3-4"},
               {"name":"液压与气压传动A","position":"工程训练中心201","day":5,"week":15,"sections":"3-4"}
             ]}
        """.trimIndent()
        val result = SyjxScheduleParser.parseExtractJson(payload)
        assertTrue("应解析成功：$result", result is ImportParseResult.Success)
        val courses = (result as ImportParseResult.Success).courses
        assertEquals(2, courses.size)
        assertEquals(setOf(1, 3), courses.first { it.day == 6 }.weeks)
        assertEquals("同一地点跨周应合并", setOf(14, 15), courses.first { it.day == 5 }.weeks)
    }

    /** 注入 JSON 带页面学期时，透传到 Success.term（导入确认弹窗展示的口径来源）。 */
    @Test
    fun decodesInjectedJsonCarriesTerm() {
        val payload = """
            {"ok":true,"term":"2025-2026-2","title":"实验课表","url":"http://jiaowu.juwp.edu.cn:8080/jsxsd/syjx/toXskb.do",
             "items":[
               {"name":"机电传动控制B","position":"工程训练中心207","day":6,"week":1,"sections":"3-4"}
             ]}
        """.trimIndent()
        val result = SyjxScheduleParser.parseExtractJson(payload)
        assertTrue("应解析成功：$result", result is ImportParseResult.Success)
        assertEquals("2025-2026-2", (result as ImportParseResult.Success).term)
    }

    /** 页面不对 / 还没查询时，给可读的失败提示而不是空列表。 */
    @Test
    fun emptyPageFails() {
        val result = SyjxScheduleParser.parseFromHtml("<html><body><p>无课表</p></body></html>")
        assertTrue(result is ImportParseResult.Failure)
    }

    /** tooltip 里的 `节次：60304` 是页面内部编码，不能被当成真实节次。 */
    @Test
    fun ignoresTooltipPseudoSection() {
        val html = table(
            weekBlock(
                9,
                "1-2" to cells(), "3-4" to cells(), "5-6" to cells(),
                "7-8" to cells(null, null, null, null, null, "人机交互技术|工程训练中心210", null),
                "9-10" to cells(), "11" to cells(),
            ),
        )
        val courses = coursesOf(html)
        assertEquals(1, courses.size)
        assertEquals("节次只能来自行标签", 7, courses[0].startSection)
        assertEquals(8, courses[0].endSection)
    }

    // ---------- fixture ----------

    private fun coursesOf(html: String) =
        (SyjxScheduleParser.parseFromHtml(html) as ImportParseResult.Success).courses

    private fun table(vararg blocks: String): String =
        "<table class=\"qz-weeklyTable\"><tbody class=\"qz-weeklyTable-thbody\">" +
            blocks.joinToString("") + "</tbody></table>"

    /** 一个周次 = 6 行；首行带 rowspan 的周次标签，因此比其余行多一个 td。 */
    private fun weekBlock(week: Int, vararg rows: Pair<String, List<String?>>): String = buildString {
        rows.forEachIndexed { index, (sectionLabel, cells) ->
            append("<tr class=\"qz-weeklyTable-tr\">")
            if (index == 0) {
                append(
                    "<td class=\"qz-weeklyTable-td qz-weeklyTable-label\" rowspan=\"${rows.size}\">" +
                        "$week</td>",
                )
            }
            append("<td class=\"qz-weeklyTable-td qz-weeklyTable-label\">$sectionLabel</td>")
            cells.forEach { append(if (it == null) emptyCell() else courseCell(it)) }
            append("</tr>")
        }
    }

    private fun cells(vararg specs: String?): List<String?> =
        List(7) { index -> specs.getOrNull(index) }

    private fun emptyCell() = "<td class=\"qz-weeklyTable-td\"></td>"

    /** 课块 + tooltip（tooltip 里故意放会干扰解析的字段）。 */
    private fun courseCell(spec: String): String {
        val name = spec.substringBefore('|')
        val position = spec.substringAfter('|', "")
        return "<td class=\"qz-weeklyTable-td qz-hasCourse qz-mixrow\" rowspan=\"1\">" +
            "<div class=\"td-cell qz-flex-col\"><ul class=\"courselists hasMoreCourse qz-flex-col\">" +
            "<li class=\"courselists-item qz-hasCourse-1\">" +
            "<div class=\"qz-hasCourse-title qz-ellipse\">$name</div>" +
            "<div class=\"qz-hasCourse-detaillists qz-hasCourse-abbrinfo\">" +
            "<div class=\"qz-hasCourse-detailitem\"> $position </div></div>" +
            "</li></ul>" +
            "<div class=\"qz-tooltip\"><div class=\"qz-tooltipContent\"><ul class=\"qz-toolitiplists\">" +
            "<li class=\"qz-toolitiplists\">" +
            "<div class=\"qz-tooltipContent-title qz-ellipse\">$name</div>" +
            "<div class=\"qz-tooltipContent-detaillists\">" +
            "<div class=\"qz-tooltipContent-detailitem\"> 课程编号：080803005 </div>" +
            "<div class=\"qz-tooltipContent-detailitem\"> 节次：60304 </div>" +
            "</div></li></ul></div></div>" +
            "</div></td>"
    }
}
