package edu.jxslu.schedule.data.jw

import edu.jxslu.schedule.domain.CreditCell
import edu.jxslu.schedule.domain.ScholarCourse
import edu.jxslu.schedule.domain.ScholarCourseStatus
import edu.jxslu.schedule.domain.ScholarDimension
import edu.jxslu.schedule.domain.ScholarGroup
import edu.jxslu.schedule.domain.ScholarProgress
import edu.jxslu.schedule.domain.ScholarProgressRules

/**
 * 学业完成情况页面解析（DESIGN §4.29）。
 *
 * 与成绩 / 考试那两处**不同**：这里的接口（`/jsxsd/xxwcqk/xxwcqkOn*.do`）返回的是
 * 渲染好的 HTML 片段，不是 JSON，所以没有注入 fetch、也没有 JSON 解析，只有正则抠节点。
 *
 * 页面结构（2026-09-27 实测）：
 * ```
 * div.mod-total-area            顶层汇总表（培养方案名 + 分组学分总账）
 *   h5.header-item-text          培养方案名
 *   div.total-list > div.list-tr 汇总行（kctx 5 列，其余维度 3 列）
 * div.mod-item-detail           分组块，一个维度下 N 个
 *   span.header-title-text / b.header-title-blod   分组名
 *   span.result-tag-text         本轮结论（通过 / 未通过）
 *   label.study-info-label       学分要求
 *   div.layui-progress-bar[lay-percent]            进度
 *   b.study-number               已修 / 还需
 *   div.sub-table > div.list-tr  课程明细（11 列）
 * ```
 *
 * 三处不能想当然的地方：
 * 1. 表格不是 `<table>`，全是 `div.list-tr` / `div.list-td` / `span.list-td-cell`；
 * 2. 页面有**两段并列的 `<html>`**（双 doctype 后代），任何按「一个根节点」写的 XML 式
 *    解析都会丢后半段——这也是项目里一致拒绝 lxml 类库的原因；
 * 3. 不同维度的列数不同（明细 11 列 vs 公选课 9 列），所以**按列下标取值前先看列数**，
 *    不能拿 kctx 的下标去套 kcxz。
 */
object ScholarProgressParser {

    /**
     * 解析一个维度的页面。解析不出来返回 null（调用方据此判定"页面结构变了"）。
     *
     * [dimension] 是该页面所属维度；[html] 是 `xxwcqkOn*.do?isdb=0` 的响应体。
     */
    fun parse(dimension: ScholarDimension, html: String): ScholarProgress? {
        if (html.isBlank()) return null
        // 未登录时教务会回一个 860 字节左右的登录提示页
        if (html.contains("用户没有登录")) return null

        val parts = html.split(MOD_ITEM_SPLIT)
        val head = parts.first()
        val blocks = parts.drop(1)

        val planName = PLAN_NAME_RE.find(head)?.let { clean(it.groupValues[1]) }.orEmpty()

        val groups = mutableListOf<ScholarGroup>()
        val courses = mutableListOf<ScholarCourse>()

        blocks.forEachIndexed { index, block ->
            parseGroup(dimension, block, index)?.let { (group, rows) ->
                groups += group
                courses += rows
            }
        }

        if (groups.isEmpty() && courses.isEmpty()) return null
        return ScholarProgress(planName = planName, groups = groups, courses = courses)
    }

    // ---- 分组块 ----

    private data class GroupBlock(val group: ScholarGroup, val courses: List<ScholarCourse>)

    private fun parseGroup(
        dimension: ScholarDimension,
        block: String,
        index: Int,
    ): GroupBlock? {
        val headerText = HEADER_TITLE_TEXT_RE.find(block)
            ?.let { clean(it.groupValues[1]) }
            .orEmpty()
        val boldName = HEADER_BOLD_RE.find(block)?.let { clean(it.groupValues[1]) }
        val name = boldName?.takeIf { it.isNotBlank() }
            ?: headerText.trim().trimEnd(':', '：').trim()
        if (name.isBlank()) return null

        val resultText = RESULT_TEXT_RE.find(block)?.let { clean(it.groupValues[1]) }.orEmpty()
        val passed = when {
            resultText.isBlank() -> null
            resultText.contains("未通过") -> false
            resultText.contains("通过") -> true
            else -> null
        }
        val required = STUDY_INFO_RE.find(block)
            ?.let { numOrNull(it.groupValues[1]) }
        val percent = PERCENT_ATTR_RE.find(block)?.groupValues?.get(1)?.trim().orEmpty()

        val numbers = mutableMapOf<String, Double>()
        STUDY_NUMBER_RE.findAll(block).forEach { m ->
            val label = clean(m.groupValues[1])
            val value = m.groupValues[2].toDoubleOrNull() ?: return@forEach
            if (label.isNotBlank()) numbers[label] = value
        }

        val group = ScholarGroup(
            dimension = dimension.id,
            name = name,
            sortOrder = index,
            requiredCredit = required,
            earnedCredit = numbers["已修"],
            ongoingCredit = numbers["在修"] ?: numbers["正修读"],
            remainingCredit = numbers["还需"],
            passed = passed,
            percent = percent,
        )
        return GroupBlock(group, parseCourseRows(dimension, name, block))
    }

    private fun parseCourseRows(
        dimension: ScholarDimension,
        groupName: String,
        block: String,
    ): List<ScholarCourse> {
        val tableStart = block.indexOf("sub-table")
        if (tableStart < 0) return emptyList()
        val table = block.substring(tableStart)
        if (table.isBlank()) return emptyList()
        val columns = parseColumns(table)
        if (columns.isEmpty()) return emptyList()
        val rows = splitRows(table)
        val out = mutableListOf<ScholarCourse>()
        rows.forEachIndexed { index, row ->
            val cells = cells(row)
            if (cells.isEmpty()) return@forEachIndexed
            val course = toCourse(dimension, groupName, index, cells, columns)
                ?: return@forEachIndexed
            out += course
        }
        return out
    }

    /**
     * 明细表的列名 → 列下标。
     *
     * **必须按表头名对齐，不能按列下标硬取**：四个维度的列集合各不相同，而且差异不在尾部
     * ——课程性质维度（9 列）连「修读学期」都没有，公选课类别（10 列）没有「课程属性」，
     * 课程体系 / 课程属性（11 列）才是全的。按下标取会让公选课那批把「课程性质」读成
     * 「课程属性」、「修读情况」读成「课程性质」，成绩列还会被当成状态解析成一个奇怪的枚举值。
     * 表头是教务给的稳定契约，拿它当唯一依据，顺带还能容忍教务以后加减列。
     */
    private fun parseColumns(table: String): Map<String, Int> {
        val headerHtml = table.substringBefore(LIST_TR_TAG)
        return HEADER_CELL_RE.findAll(headerHtml)
            .mapIndexed { index, m -> clean(m.groupValues[1]) to index }
            .filter { it.first.isNotBlank() }
            .toMap()
    }

    /** 一行 → [ScholarCourse]。[columns] 是表头名到列下标的映射。 */
    private fun toCourse(
        dimension: ScholarDimension,
        groupName: String,
        index: Int,
        cells: List<Cell>,
        columns: Map<String, Int>,
    ): ScholarCourse? {
        fun cell(vararg names: String): String {
            val at = names.firstNotNullOfOrNull { columns[it] } ?: return ""
            return cells.getOrNull(at)?.text.orEmpty()
        }

        val name = cell("课程名称")
        if (name.isBlank()) return null
        val credit: CreditCell? = ScholarProgressRules.parseCredit(cell("学分"))
        return ScholarCourse(
            dimension = dimension.id,
            groupName = groupName,
            sortOrder = index,
            term = cell("修读学期"),
            courseNo = cell("课程编号"),
            name = name,
            credit = credit?.value ?: 0.0,
            planned = credit?.planned,
            category = cell("课程类别", "公选课类别"),
            attribute = cell("课程属性"),
            nature = cell("课程性质"),
            status = ScholarCourseStatus.fromRaw(cell("修读情况")).label,
            // 课程体系 / 课程属性维度叫「课程成绩」，课程性质维度叫「总成绩」，同一个位置
            scoreText = cell("课程成绩", "总成绩"),
            remark = cell("备注"),
            degreeCourse = ScholarProgressRules.parseDegreeCourse(cell("是否学位课")),
        )
    }

    // ---- 底层工具 ----

    private data class Cell(val classes: String, val text: String)

    /** 按 `div.list-tr` 切行。第一段是表头（`sub-table-header-tr`），调用方自己丢。 */
    private fun splitRows(section: String): List<String> =
        section.split(LIST_TR_SPLIT).drop(1)

    private fun cells(rowHtml: String): List<Cell> =
        CELL_RE.findAll(rowHtml).map { m ->
            Cell(classes = m.groupValues[1], text = clean(m.groupValues[2]))
        }.toList()

    private fun numOrNull(raw: String): Double? =
        Regex("""(-?\d+(?:\.\d+)?)""").find(raw)?.groupValues?.get(1)?.toDoubleOrNull()

    /**
     * 去标签、去实体、压空白。
     *
     * 实体这一步不是可选的：分组名的 `<b>` 里套着一个 iconfont 图标
     * （`<i class="layui-icon">&#xe62f;</i>`），只去标签会把 `&#xe62f;` 留在分组名里，
     * 界面上显示成一串方块。课程名里不会出现私有区实体，去掉是安全的。
     */
    private fun clean(raw: String): String = raw
        .replace(TAG_RE, " ")
        .replace(NUMERIC_ENTITY_RE, " ")
        .replace("&nbsp;", " ")
        .replace("\u00a0", " ")
        .replace(WHITESPACE_RE, " ")
        .trim()

    private val MOD_ITEM_SPLIT = Regex("""<div class="mod-item-detail""")
    private val PLAN_NAME_RE = Regex("""(?s)header-item-text[^>]*>(.*?)</h5>""")
    private val LIST_TR_SPLIT = Regex("""<div class="list-tr""")
    private val HEADER_TITLE_TEXT_RE = Regex("""(?s)header-title-text[^>]*>(.*?)</span>""")
    /**
     * 只认 `<b>` 上的 header-title-blod。
     *
     * 不能用「`header-title-blod` 后跟 `</b>`」的宽松写法：有些分组压根没有 `<b>`，
     * 而它的图标 `<i class="… header-title-blod">` 也带这个类名，宽松正则会从那个 `<i>`
     * 一路吞到**后面某处的 `</b>`**，把一整段 HTML 当成分组名（实测「计划外非通选课」
     * 就是这么变成一串标签的）。
     */
    private val HEADER_BOLD_RE = Regex("""(?s)<b[^>]*header-title-blod[^>]*>(.*?)</b>""")
    private val RESULT_TEXT_RE = Regex("""(?s)result-tag-text[^>]*>(.*?)</span>""")
    private val HEADER_CELL_RE = Regex("""(?s)header-th-cell[^>]*>(.*?)</span>""")
    private val STUDY_INFO_RE = Regex("""(?s)study-info-label[^>]*>[^<]*?[：:]\s*([\d.]+)""")
    private val PERCENT_ATTR_RE = Regex("""lay-percent="([^"]*)"""")
    private val STUDY_NUMBER_RE =
        Regex("""(?s)study-number-item[^>]*>(.*?)<b[^>]*study-number[^>]*>\s*([\d.]+)\s*</b>""")
    private val CELL_RE =
        Regex("""(?s)<div class="(list-td[^"]*)">\s*<span class="list-td-cell[^"]*">\s*(.*?)\s*</span>\s*</div>""")
    private val TAG_RE = Regex("""<[^>]+>""")
    private val NUMERIC_ENTITY_RE = Regex("""&#x?[0-9a-fA-F]+;""")
    private val WHITESPACE_RE = Regex("""\s+""")

    /** 明细行起始标签（表头与数据行的分界，也是 [parseColumns] 的截断点）。 */
    private const val LIST_TR_TAG = "<div class=\"list-tr"
}
