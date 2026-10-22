package edu.jxslu.schedule.data.jw

import edu.jxslu.schedule.domain.CourseSelection
import edu.jxslu.schedule.domain.SelectionRound
import edu.jxslu.schedule.domain.SelectionRounds
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 选课抓取（DESIGN §4.35，2026-09-30 实测）。两条数据链**都不带 .do**、都是 layui JSON：
 *
 * 1. 选课结果（选课日志）：壳页 `/jsxsd/xkgl/loadXsxkjgList?lx=xkrz` 的学期下拉给出
 *    实际学期，数据接口 `...?lx=xkrz&type=list&xnxqid=<学期>&pageNum=&pageSize=`；
 *    「退课日志」是同一接口 `lx=tkrz&cxsj=tkjg`（本期不做，字段结构未实测）。
 * 2. 选课轮次：`/jsxsd/xsxk/xklc_list_data`（**列名没有实测样本**，来自页面表格定义：
 *    `xqmc` 学年学期 / `xklc_mc` 选课名称 / `xksj` 选课时间 / `jx0502zbid` 轮次 id /
 *    `yxzt == '1'` 可预览）。非选课期 `count=0` 属正常。
 *
 * **红线**：`/jsxsd/xkgl/Xsxkjg_tk.do`（申请退课，POST）是写操作，本解析器与整个 App
 * 都不触碰；`/jsxsd/xsxk/mzlist.do`（免责声明查询）也是 POST，只由 WebView 里的
 * 教务页面自己调用。
 */
object SelectionParser {

    /** 单页行数；`count` 超过它由调用方翻页（同考试/成绩口径）。 */
    const val PAGE_SIZE = 200

    /**
     * 选课结果数据接口的完整 URL（OkHttp 直抓用）。
     *
     * 学期号先过 [JwUrls.TERM_PATTERN] 白名单：它会被拼进 URL，脏值一律拒绝
     * （返回 null，由调用方报错）——与 `ScoreParser.listUrl` 同一把尺子，只是这里
     * 学期是必填的（教务页面上该下拉 `lay-verify="required"`）。
     */
    fun listUrl(term: String, page: Int): String? {
        if (!JwUrls.TERM_PATTERN.matches(term)) return null
        return "${JwUrls.SELECTION_LIST_API}?lx=xkrz&type=list&xnxqid=$term" +
            "&pageNum=$page&pageSize=$PAGE_SIZE"
    }

    /** 选课日志壳页（学期下拉在这里）：`/jsxsd/xkgl/loadXsxkjgList?lx=xkrz`。 */
    const val SHELL_URL: String = JwUrls.SELECTION_LIST_SHELL

    /** 一页选课结果的解析产物。 */
    data class Page(
        val count: Int,
        val rows: List<CourseSelection>,
    )

    /**
     * 解析一页选课结果 JSON；失败抛 [IllegalStateException]（由 UI 转成可读提示）。
     * 未登录时教务返回的是 HTML 登录页，这里会落到「不是有效 JSON」分支。
     */
    fun parseFetchJson(jsonText: String): Page {
        val root = JwLayuiJson.parseRoot(jsonText, "选课结果")
        return Page(count = root.count, rows = root.rows.mapNotNull { rowToSelection(it) })
    }

    private fun rowToSelection(row: JsonObject): CourseSelection? {
        val name = row.strOrNull("kc_mc") ?: return null
        return CourseSelection(
            term = row.strOrNull("xnxqid").orEmpty(),
            courseNo = row.strOrNull("kch").orEmpty(),
            name = name,
            teacher = row.strOrNull("xm").orEmpty(),
            credit = row.doubleOrNull("xf") ?: 0.0,
            hours = row.doubleOrNull("zxs") ?: 0.0,
            attribute = row.strOrNull("kclb_mc").orEmpty(),
            category = row.strOrNull("kcxz_mc").orEmpty(),
            className = row.strOrNull("ktmc").orEmpty(),
            college = row.strOrNull("yx_mc").orEmpty(),
            timeText = row.multiline("sksj"),
            placeText = row.multiline("skdd"),
            status = row.strOrNull("shzt").orEmpty(),
            remark = row.strOrNull("yy").orEmpty(),
        )
    }

    /** 一页轮次的解析产物。 */
    data class RoundsPage(
        val count: Int,
        val rounds: List<SelectionRound>,
    )

    /** 选课轮次数据接口（学生选课中心列表）。 */
    const val ROUNDS_URL: String = JwUrls.SELECTION_ROUNDS_API

    /**
     * 解析轮次 JSON。字段名曾来自页面表格定义、无样本；2026-10-08 选课窗口期已实抓（见
     * `SelectionParserTest.parsesRealRoundRowFromLiveWindow`）——`xksj` 是
     * 「`日期 时刻~日期 时刻`」（`~` 无空格）形态。
     * 缺 `jx0502zbid`（进选课的键）或名称的行直接丢弃——没有它这行不可操作。
     */
    fun parseRounds(jsonText: String): RoundsPage {
        val root = JwLayuiJson.parseRoot(jsonText, "选课轮次")
        return RoundsPage(count = root.count, rounds = root.rows.mapNotNull { rowToRound(it) })
    }

    private fun rowToRound(row: JsonObject): SelectionRound? {
        val id = row.strOrNull("jx0502zbid") ?: return null
        val name = row.strOrNull("xklc_mc") ?: return null
        val timeText = row.strOrNull("xksj").orEmpty()
        val (startAt, endAt) = SelectionRounds.parseTimeRange(timeText)
        return SelectionRound(
            id = id,
            term = row.strOrNull("xqmc").orEmpty(),
            name = name,
            timeText = timeText,
            startAt = startAt,
            endAt = endAt,
            canPreview = row["yxzt"]?.jsonPrimitive?.content == "1",
        )
    }

    /**
     * 从选课日志壳页抽学期下拉的当前选中项（`<option value="2026-2027-1" selected>`）。
     *
     * 与 `ExamSync.termFromShell` 同一条纪律：正则而不是 DOM、抽不到返回 null、
     * **不猜学期**（猜错会把别的学期的记录当成本学期的）。这里额外先定位 `select#xnxqid`：
     * 该页有两个 select（搜索表单里还有别的），按 id 收窄比全文扫 `<option selected>` 稳。
     */
    fun shellTerm(html: String): String? =
        JwHtml.selectedTerm(html, selectId = "xnxqid")

    /**
     * 壳页学期下拉的全量选项（value 优先、文本兜底，保序去重）。
     * 「更多学期」列表用它，避免为了列个清单再打一次教务。
     */
    fun shellTerms(html: String): List<String> {
        val selectBody = SELECT_RE.find(html)?.groupValues?.getOrNull(1) ?: return emptyList()
        val out = LinkedHashSet<String>()
        for (option in OPTION_RE.findAll(selectBody)) {
            val value = valueOf(option.groupValues[1])
                ?: option.groupValues[2].trim().takeIf { it.isNotEmpty() }
            if (value != null && JwUrls.TERM_PATTERN.matches(value)) out += value
        }
        return out.toList()
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 教务多行字段用 `<br>` 分隔（如「星期四 0708节<br>星期五 0708节」）：转 `\n` 并清掉残余标签。 */
    private fun JsonObject.multiline(key: String): String {
        val raw = this[key]
        if (raw is JsonNull) return ""
        val text = (raw as? JsonPrimitive)?.contentOrNull ?: return ""
        return text
            .replace(BR_RE, "\n")
            .replace(TAG_RE, "")
            .trim()
    }

    private fun valueOf(attrs: String): String? =
        VALUE_RE.find(attrs)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }

    private val BR_RE = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val TAG_RE = Regex("""<[^>]+>""")
    private val SELECT_RE = Regex(
        """(?is)<select[^>]*id\s*=\s*["']xnxqid["'][^>]*>(.*?)</select>""",
    )
    private val OPTION_RE = Regex("""(?is)<option\b([^>]*)>(.*?)</option>""")
    private val VALUE_RE = Regex("""value\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
}
