package edu.jxslu.schedule.data.jw

import edu.jxslu.schedule.domain.Curriculum
import edu.jxslu.schedule.domain.CurriculumCourse

/**
 * 培养方案「课程设置总表」页面解析（DESIGN §4.41）。
 *
 * 与学业完成情况同款：返回的是**渲染好的 HTML**，没有 JSON 接口，只有正则抠节点。
 * 页面结构（2026-10-07 实测线上快照，与 09-27 样本逐字段 0 差异）：
 * ```
 * 页首「一、培养目标」区  p.item-area-content-inner       培养方案名
 * 「三、课程设置总表」起：
 *   div.body-table-tr > div.tixi-cell-text                体系块（通识限选课 / 通识必修课 / …）
 *   div.son-table-tr > div.table-td-cell.cols-N           课程行
 *     cols-3 选课组（本专业为空）/ cols-4 编号 / cols-5 名称 / cols-6 性质 /
 *     cols-7 属性 / cols-8 学分 / cols-9-2-1×5 学时 / cols-10 开设学期
 * ```
 *
 * 三处不能想当然（与 scripts 侧 `fetch_curriculum.py` 是同一条口径）：
 * 1. **方案名在「三、课程设置总表」切片之外**（页首段落里），必须全页搜；
 * 2. 学时的 5 个子列在 DOM 里 class 全同（`table-td-cell cols-9-2-1`），只能按
 *    **行内出现顺序**区分（理论 / 实践 / 讲座 / 实验 / 总），按 class 名找必然错位；
 * 3. 体系块与课程行是两串节点，必须按**文档位置归并**（各自 findAll 会丢归属）；
 *    没有课程名的行（汇总 / 空行）丢弃。
 */
object CurriculumParser {

    /**
     * 解析整页 HTML。解析不出来返回 null（调用方据此判定「页面结构变了」）。
     *
     * 登录失效与结构变化都返回 null：调用方的 [edu.jxslu.schedule.data.repo.CurriculumSync]
     * 对两种情况本就回同一个「本次未更新」，不必在这里区分（会话失效在
     * `CasSession.fetchHtml` 层已经抛 SessionInvalid 并自动重登一次了）。
     */
    fun parse(html: String): Curriculum? {
        if (html.isBlank()) return null
        // 防御：若会话失效的页面一路漏到这里（860B 提示页 / 80KB 新版登录页），别当数据解析
        if (html.contains("用户没有登录") || html.contains("请先登录系统")) return null

        val planName = PLAN_NAME_RE.find(html)?.let { clean(it.groupValues[1]) }
            ?.takeIf { it.isNotBlank() }
            ?: HEADER_TITLE_RE.find(html)?.let { clean(it.groupValues[1]) }.orEmpty()

        val start = html.indexOf(COURSE_TABLE_MARK)
        if (start < 0) return null
        val body = html.substring(start)

        // 体系块与课程行按文档位置归并
        val events = mutableListOf<Event>()
        TIXI_RE.findAll(body).forEach {
            events += Event(it.range.first, tixi = clean(it.groupValues[1]), row = null)
        }
        ROW_RE.findAll(body).forEach {
            events += Event(it.range.first, tixi = null, row = it.groupValues[1])
        }
        events.sortBy { it.pos }

        val courses = mutableListOf<CurriculumCourse>()
        var currentTixi = ""
        for (event in events) {
            val tixi = event.tixi
            if (tixi != null) {
                currentTixi = tixi
                continue
            }
            val row = event.row ?: continue
            toCourse(row, currentTixi, courses.size)?.let { courses += it }
        }

        if (courses.isEmpty()) return null
        return Curriculum(planName = planName, courses = courses)
    }

    // ---- 行解析 ----

    private data class Event(val pos: Int, val tixi: String?, val row: String?)

    private data class Cell(val classes: List<String>, val text: String)

    private fun toCourse(row: String, tixi: String, index: Int): CurriculumCourse? {
        val cells = CELL_RE.findAll(row).map { m ->
            Cell(classes = m.groupValues[1].split(' '), text = clean(m.groupValues[2]))
        }.toList()
        fun cell(cls: String): String =
            cells.firstOrNull { cls in it.classes }?.text.orEmpty()

        val name = cell("cols-5")
        if (name.isBlank()) return null  // 汇总 / 空行

        // 学时五列 class 全同，按行内出现顺序对应（理论 / 实践 / 讲座 / 实验 / 总）
        val hours = cells.filter { HOUR_CLASS in it.classes }.map { it.text }

        return CurriculumCourse(
            term = cell("cols-10").toIntOrNull() ?: 0,
            sortOrder = index,
            courseNo = cell("cols-4"),
            name = name,
            kind = cell("cols-6"),
            attr = cell("cols-7"),
            credit = cell("cols-8").toDoubleOrNull(),
            hoursTheory = hours.getOrElse(0) { "" },
            hoursPractice = hours.getOrElse(1) { "" },
            hoursLecture = hours.getOrElse(2) { "" },
            hoursExperiment = hours.getOrElse(3) { "" },
            hoursTotal = hours.getOrElse(4) { "" },
            tixi = tixi,
        )
    }

    // ---- 底层工具（clean 口径与 ScholarProgressParser 一致）----

    private fun clean(raw: String): String = raw
        .replace(TAG_RE, " ")
        .replace(NUMERIC_ENTITY_RE, " ")
        .replace("&nbsp;", " ")
        .replace("\u00a0", " ")
        .replace(WHITESPACE_RE, " ")
        .trim()

    /** 正文切片起点（「三、课程设置总表」标题）。 */
    private const val COURSE_TABLE_MARK = "三、课程设置总表"

    /** 学时子列的 class（五个子列在 DOM 里全同，只能按出现顺序认）。 */
    private const val HOUR_CLASS = "cols-9-2-1"

    /** 方案名的第一出处（页首「一、培养目标」段落）。 */
    private val PLAN_NAME_RE =
        Regex("""class="item-area-content-inner"[^>]*>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)

    /** 方案名的兜底出处（页头 span，带「培养方案及教学计划」模板后缀）。 */
    private val HEADER_TITLE_RE =
        Regex("""class="header-item-title"[^>]*>(.*?)</span>""", RegexOption.DOT_MATCHES_ALL)

    private val TIXI_RE =
        Regex("""class="tixi-cell-text"[^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)

    /**
     * 课程行。终止前瞻覆盖三种边界：下一行、下一个容器、页脚/正文结尾——
     * 缺了任何一种，最后一行会把页脚汇总数字一起吞进来。
     */
    private val ROW_RE = Regex(
        """<div class="son-table-tr">(.*?)(?=<div class="son-table-tr">|<div class="body-table-|<footer|</html|$)""",
        RegexOption.DOT_MATCHES_ALL,
    )

    private val CELL_RE =
        Regex("""<div class="table-td-cell ([^"]+)"[^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)

    private val TAG_RE = Regex("""<[^>]+>""")
    private val NUMERIC_ENTITY_RE = Regex("""&#x?[0-9a-fA-F]+;""")
    private val WHITESPACE_RE = Regex("""\s+""")
}
