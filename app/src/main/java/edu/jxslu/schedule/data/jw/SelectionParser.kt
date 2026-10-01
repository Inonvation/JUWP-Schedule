package edu.jxslu.schedule.data.jw

import edu.jxslu.schedule.domain.CourseSelection
import edu.jxslu.schedule.domain.SelectionRound
import edu.jxslu.schedule.domain.SelectionRounds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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

    private val json = Json { ignoreUnknownKeys = true }

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
        val root = parseLayuiRoot(jsonText, "选课结果")
        val count = root["count"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        val rows = root["data"]?.jsonArray?.mapNotNull { it as? JsonObject } ?: emptyList()
        return Page(count = count, rows = rows.mapNotNull { rowToSelection(it) })
    }

    private fun rowToSelection(row: JsonObject): CourseSelection? {
        val name = row.str("kc_mc") ?: return null
        return CourseSelection(
            term = row.str("xnxqid").orEmpty(),
            courseNo = row.str("kch").orEmpty(),
            name = name,
            teacher = row.str("xm").orEmpty(),
            credit = row.dbl("xf") ?: 0.0,
            hours = row.dbl("zxs") ?: 0.0,
            attribute = row.str("kclb_mc").orEmpty(),
            category = row.str("kcxz_mc").orEmpty(),
            className = row.str("ktmc").orEmpty(),
            college = row.str("yx_mc").orEmpty(),
            timeText = row.multiline("sksj"),
            placeText = row.multiline("skdd"),
            status = row.str("shzt").orEmpty(),
            remark = row.str("yy").orEmpty(),
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
     * 解析轮次 JSON。字段名来自页面表格定义（非选课期无样本可对照）：
     * 缺 `jx0502zbid`（进选课的键）或名称的行直接丢弃——没有它这行不可操作。
     */
    fun parseRounds(jsonText: String): RoundsPage {
        val root = parseLayuiRoot(jsonText, "选课轮次")
        val count = root["count"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        val rows = root["data"]?.jsonArray?.mapNotNull { it as? JsonObject } ?: emptyList()
        return RoundsPage(count = count, rounds = rows.mapNotNull { rowToRound(it) })
    }

    private fun rowToRound(row: JsonObject): SelectionRound? {
        val id = row.str("jx0502zbid") ?: return null
        val name = row.str("xklc_mc") ?: return null
        val timeText = row.str("xksj").orEmpty()
        val (startAt, endAt) = SelectionRounds.parseTimeRange(timeText)
        return SelectionRound(
            id = id,
            term = row.str("xqmc").orEmpty(),
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
    fun shellTerm(html: String): String? {
        val selectBody = SELECT_RE.find(html)?.groupValues?.getOrNull(1) ?: return null
        for (option in OPTION_RE.findAll(selectBody)) {
            val attrs = option.groupValues[1]
            if (!attrs.contains("selected", ignoreCase = true)) continue
            // value 属性优先；教务下拉的 value 与文本相同，文本兜底
            valueOf(attrs)?.let { return it }
            option.groupValues[2].trim().takeIf { it.isNotEmpty() }?.let { return it }
        }
        return null
    }

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

    /**
     * layui JSON 的三道检查（照 `ScoreParser` 的口径）：
     * 注入/OkHttp 失败前缀、no-open 页、非 JSON（未登录时是 HTML 登录页）、code != 0。
     */
    private fun parseLayuiRoot(jsonText: String, what: String): JsonObject {
        if (jsonText.startsWith("ERR:")) {
            throw IllegalStateException("请求失败：${jsonText.removePrefix("ERR:")}")
        }
        if ("系统功能暂未开放" in jsonText) {
            throw IllegalStateException("教务${what}功能暂未开放（no-open 页）")
        }
        if ("用户没有登录" in jsonText) {
            throw IllegalStateException("教务会话已失效，请重新登录")
        }
        val root = try {
            json.parseToJsonElement(jsonText).jsonObject
        } catch (e: Exception) {
            throw IllegalStateException("返回的不是有效 JSON（可能未登录教务）：${e.message}")
        }
        val code = root["code"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1
        if (code != 0) {
            throw IllegalStateException(
                "教务返回 code=$code，msg=${root["msg"]?.jsonPrimitive?.content ?: ""}",
            )
        }
        return root
    }

    private fun JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.dbl(key: String): Double? = try {
        (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull()
    } catch (_: Exception) {
        null
    }

    /** 教务多行字段用 `<br>` 分隔（如「星期四 0708节<br>星期五 0708节」）：转 `\n` 并清掉残余标签。 */
    private fun JsonObject.multiline(key: String): String {
        val raw = this[key]?.jsonPrimitive?.content ?: return ""
        return raw
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
