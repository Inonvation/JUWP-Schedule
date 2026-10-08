package edu.jxslu.schedule.data.jw

import edu.jxslu.schedule.domain.SelectionCourse
import edu.jxslu.schedule.domain.SelectionQuery
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 应用内选课中心「本学期计划选课」的解析（DESIGN §4.36 C 阶段，**2026-10-08 窗口期实测**）。
 *
 * 页面链路（壳页 iframe 里都是它）：
 * ```
 * 进入选课   GET  newXsxkzx?jx0502zbid=<轮次id>&isallsc=     ← 轮次时间窗未开回「不在选课时间范围」提示页
 * 列表壳页   GET  xsxkkc/getBxqjxk                          ← 验证码开关 input#sfyzmxk 在这里
 * 列表数据   POST xsxkkc/xsxkBxqjhxk?kch=…&…               ← body 必须旧版 DataTables 参数（少了回 404 错误页）
 * 提交选课   GET  xsxkkc/bxqjhxkOper?kcid=..&cfbs=..&…      ← 写操作，只由用户逐次确认触发
 * 退课      GET  xsxkjg/xstkOper?jx0404id=..                ← 写操作，同上；只由用户逐次确认
 * ```
 * （URL 常量全在 [JwUrls]。响应是 DataTables 形态 `{aaData, iTotalRecords, …}`，
 * 不是 layui 的 `{code,count,data}`——两套别混。）
 *
 * **「先进轮次」是会话前提**（2026-10-08 实测）：xsxkkc 模块的请求（壳页 + 列表）要求
 * 当前会话先 GET 过 [JwUrls.selectionRoundUrl]（newXsxkzx）——没进过时壳页只回 534 字节空
 * 存根、列表一律回教务自家 404 页（**不是参数问题**：同参数进轮次后 200）。客户端把
 * 「进轮次 + 壳页校验」放在 `SelectionCenterHttpClient.prepare`，且对 404 页有重进自愈。
 *
 * 红线（沿用）：`/jsxsd/xkgl/Xsxkjg_tk.do`（申请退课，老入口）与 `/jsxsd/xsxk/mzlist.do`
 * （免责声明）任何路径都不碰；本解析器只做纯文本映射，不发请求。
 */
object SelectionCenterParser {

    private val json = Json { ignoreUnknownKeys = true }

    /** 轮次时间窗未开时 `newXsxkzx` 的提示页原文（2026-10-01 / 10-08 两次实抓同款）。 */
    const val NOT_IN_WINDOW_TEXT = "当前不在选课时间范围内，具体请查看学校选课通知！"

    /** 一页拿多少行（教务接受 200；「最后 10 条」按 `iTotalRecords` 兜底翻页）。 */
    const val PAGE_SIZE = 100

    /**
     * 浏览全量用的关键词（2026-10-08 实测）。教务列表**要求 关键词/教师/星期 至少一个
     * 非空**（全空回 0 行、通配 `%` 与空格也回 0 行——服务端按字面匹配，不认 LIKE 通配）；
     * 但课程编号是 9 位数字串、**全部以 0 开头**，`kch=0` 命中每一门课——数字扫描
     * kch=0..9 的并集（数学上必含全部课程）与 kch=0 单查询**同为 48 行、0 差集**（实测）。
     * 与其它条件按 AND 叠加（kch=0+类别=31 行、+星期=7 行、+过滤已满=46 行，均实测）。
     *
     * 口径边界：这是对「编号都含 0」这一数据事实的利用（不是绕过任何校验——就是一个普通
     * 搜索串）；真要哪天全校出现了不含 0 的编号，浏览会漏那几门，届时改用「按星期铺开」
     * 的多查询兜底（那条路已验证可用、但会漏掉没有排课时间的课程设计类），注释与测试一起改。
     */
    const val BROWSE_KEYWORD = "0"

    /**
     * 「本学期计划选课」列表的 URL 查询参数（照页面 `queryKxkcList` 的 param 拼法，
     * 2026-10-08 v2 起支持全量条件面；字段含义见 [SelectionQuery]）。
     *
     * 空条件的落法：关键词与教师都空时 `kch` 换成 [BROWSE_KEYWORD]（浏览全量）——
     * 只空教师之外的组合照旧；教师单独在时为条件（其自身就满足教务的「至少一个非空」）。
     */
    fun listQuery(query: SelectionQuery): List<Pair<String, String>> {
        val keyword = when {
            query.keyword.isNotBlank() -> query.keyword
            query.teacher.isBlank() -> BROWSE_KEYWORD
            else -> ""
        }
        return listOf(
            "kch" to keyword,
            "jg0101id" to query.teacher,
            "skxq" to (query.weekday?.toString() ?: ""),
            "skjc" to (query.startSection?.toString() ?: ""),
            "endJc" to (query.endSection?.toString() ?: ""),
            "sfym" to query.hideFull.toString(),
            "sfct" to query.hideConflict.toString(),
            "sfxx" to query.hideRestricted.toString(),
            "skfs" to "",
            "kkdw" to "",
            "kctype" to query.courseType,
        )
    }

    /**
     * 列表请求体：**旧版 DataTables 参数**，键名照页面 `aoColumns` 一个都不能少——
     * 少了 `sEcho` / `mDataProp_i` 教务直接回它自家的 404 错误页（实测，不是 400）。
     * [start] 是行偏移（翻页），[pageSize] 行数。
     */
    fun listBody(start: Int = 0, pageSize: Int = PAGE_SIZE): List<Pair<String, String>> {
        val body = mutableListOf(
            "sEcho" to "1",
            "iColumns" to COLUMNS.size.toString(),
            "sColumns" to COLUMNS.joinToString(",") { "" },
            "iDisplayStart" to start.toString(),
            "iDisplayLength" to pageSize.toString(),
            "sSearch" to "",
            "bRegex" to "false",
        )
        COLUMNS.forEachIndexed { index, name -> body += "mDataProp_$index" to name }
        body += "iSortCol_0" to "0"
        body += "sSortDir_0" to "asc"
        body += "iSortingCols" to "1"
        return body
    }

    /** 一页课程列表的解析产物。 */
    data class CourseListPage(val total: Int, val rows: List<SelectionCourse>)

    /**
     * 解析一页列表 JSON。页面形态（未登录 / 无权限 / 参数不齐 / 窗口关闭）先给可读错误
     * （抛 [IllegalStateException]，由调用方映射）；行缺 `jx0404id` 或课名直接丢弃。
     */
    fun parseList(jsonText: String): CourseListPage {
        if (notInWindow(jsonText)) throw IllegalStateException(NOT_IN_WINDOW_TEXT)
        if (noOpen(jsonText)) throw IllegalStateException("教务选课功能暂未开放（no-open 页）")
        if (qzNotFound(jsonText)) throw IllegalStateException("教务回了 404 错误页（请求形态与教务不匹配）")
        if (jsonText.startsWith("ERR:")) {
            throw IllegalStateException("请求失败：${jsonText.removePrefix("ERR:")}")
        }
        if ("用户没有登录" in jsonText) throw IllegalStateException("教务会话已失效，请重新登录")
        val root = try {
            json.parseToJsonElement(jsonText).jsonObject
        } catch (e: Exception) {
            throw IllegalStateException("返回的不是有效 JSON（可能未登录教务）：${e.message}")
        }
        val array = root["aaData"] as? JsonArray ?: emptyList()
        val rows = array.mapNotNull { (it as? JsonObject)?.let(::rowToCourse) }
        val total = root.int("iTotalRecords") ?: rows.size
        return CourseListPage(total = total, rows = rows)
    }

    /** 提交 / 退课应答的解析产物（三态由 client 映射到 `SelectionSubmitResult`）。 */
    data class ActionOutcome(
        val success: Boolean,
        val message: String,
        /**
         * 教务受理了但**冲突未决**（`success=true` 且 message 含「还有」——页面会接着弹
         * `xsxkBxqjhxkCfbs` 冲突处置框）。对 App 来说这行还没落定，按「未成功」报，
         * **绝不自动走冲突处置**（那等于替用户做取舍）。
         */
        val conflictPending: Boolean = false,
    )

    /** 解析提交 / 退课应答（`{success, message, …}`）。不可识别的形态抛 [IllegalStateException]。 */
    fun parseAction(jsonText: String): ActionOutcome {
        if (notInWindow(jsonText)) throw IllegalStateException(NOT_IN_WINDOW_TEXT)
        if (noOpen(jsonText)) throw IllegalStateException("教务选课功能暂未开放（no-open 页）")
        if (qzNotFound(jsonText)) throw IllegalStateException("教务回了 404 错误页（请求形态与教务不匹配）")
        if (jsonText.startsWith("ERR:")) {
            throw IllegalStateException("请求失败：${jsonText.removePrefix("ERR:")}")
        }
        if ("用户没有登录" in jsonText) throw IllegalStateException("教务会话已失效，请重新登录")
        val root = try {
            json.parseToJsonElement(jsonText).jsonObject
        } catch (e: Exception) {
            throw IllegalStateException("返回的不是有效 JSON（可能未登录教务）：${e.message}")
        }
        val success = root["success"]?.jsonPrimitive?.content == "true"
        val message = root.str("message").orEmpty()
        val conflictPending = success && message.contains("还有")
        return ActionOutcome(
            success = success,
            message = message.ifEmpty { if (success) "教务已受理" else "教务未给出原因" },
            conflictPending = conflictPending,
        )
    }

    /**
     * 该消息是否代表「换时间再试也没用、要人工介入」：验证码 / 会话 / 窗口状态类。
     * 命中即让调用方收场（界面按 Fatal 提示重新登录或去教务页面）。
     */
    fun isFatalMessage(message: String): Boolean =
        FATAL_KEYWORDS.any { it in message }

    /**
     * 列表壳页里的验证码开关（`input#sfyzmxk`，`"1"` = 本轮启用验证码选课）。
     * 抽不到按 false（页面默认 0）——真开了验证码，提交会被教务挡下并按 message 收场。
     */
    fun captchaEnabled(html: String): Boolean {
        val tag = Regex("""<input[^>]*\b(?:id|name)\s*=\s*["']?sfyzmxk["']?[^>]*>""", RegexOption.IGNORE_CASE)
            .find(html)?.value ?: return false
        return Regex("""\bvalue\s*=\s*["']?1["']?""", RegexOption.IGNORE_CASE).containsMatchIn(tag)
    }

    /** 轮次时间窗未开的提示页（`newXsxkzx` 在窗外的形态）。 */
    fun notInWindow(html: String): Boolean = "当前不在选课时间范围内" in html

    /** 校方把功能关掉时的 no-open 页。 */
    fun noOpen(html: String): Boolean = "系统功能暂未开放" in html

    /** 强智自家的 404 错误页（请求形态不匹配时会出现——它把参数缺失渲染成 404）。 */
    fun qzNotFound(html: String): Boolean = "您访问的页面不存在" in html

    // ------------------------------------------------------------------
    // 课程主键：`jx0404id|jx02id|cfbs`（提交三个参数都在；id 对上层仍是不透明串）
    // ------------------------------------------------------------------

    /** 拼课程主键。三个分段都不会含 `|`（id 是数字/hex，cfbs 是短标记）。 */
    fun courseKey(jx0404id: String, jx02id: String, cfbs: String): String =
        "$jx0404id|$jx02id|$cfbs"

    /** 拆课程主键；形态不对（缺段 / 缺 jx0404id）回 null——调用方报可读错误，不硬拼 URL。 */
    fun splitCourseKey(key: String): Triple<String, String, String>? {
        val parts = key.split('|')
        if (parts.size != 3 || parts[0].isEmpty()) return null
        return Triple(parts[0], parts[1], parts[2])
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private fun rowToCourse(row: JsonObject): SelectionCourse? {
        val jx0404id = row.str("jx0404id") ?: return null
        val name = row.str("kcmc") ?: return null
        return SelectionCourse(
            id = courseKey(jx0404id, row.str("jx02id").orEmpty(), row.str("cfbs").orEmpty()),
            name = name,
            teacher = row.str("skls").orEmpty(),
            timeText = row.multiline("sksj"),
            placeText = row.multiline("skdd"),
            credit = row.dbl("xf"),
            attribute = row.str("kcsxmc").orEmpty(),
            category = row.str("kcxzmc").orEmpty(),
            capacity = row.int("pkrs"),
            enrolled = row.int("xkrs"),
            selected = row.str("xkzt") == "9",
            courseNo = row.str("kch").orEmpty(),
            remainingSeats = row.int("syrs"),
            conflictNote = row.str("ctsm").orEmpty(),
            courseType = row.str("kctype").orEmpty(),
            groupName = row.str("fzmc").orEmpty(),
            campus = row.str("xqmc").orEmpty(),
            teachMode = row.str("skfsmc").orEmpty(),
        )
    }

    private fun JsonObject.str(key: String): String? {
        // JsonNull 要挡掉：它的 `content` 是字符串 "null"（实测 cfbs 等列常为 null）
        val value = this[key] ?: return null
        if (value is JsonNull) return null
        return value.jsonPrimitive.content.trim().takeIf { it.isNotEmpty() }
    }

    private fun JsonObject.int(key: String): Int? =
        this[key]?.jsonPrimitive?.content?.trim()?.toIntOrNull()

    private fun JsonObject.dbl(key: String): Double? =
        this[key]?.jsonPrimitive?.content?.trim()?.toDoubleOrNull()

    /** 教务多行字段用 `<br>` 分隔：转 `\n` 并清掉残余标签；逐行 trim（实测数据行尾带空格）。 */
    private fun JsonObject.multiline(key: String): String {
        val value = this[key] ?: return ""
        if (value is JsonNull) return ""
        return value.jsonPrimitive.content
            .replace(BR_RE, "\n")
            .replace(TAG_RE, "")
            .split("\n")
            .joinToString("\n") { it.trim() }
    }

    /** 页面 `aoColumns` 的 mDataProp 顺序（缺键会吃 404，见 [listBody]）。 */
    private val COLUMNS = listOf(
        "kch", "kcmc", "kctype", "fzmc", "xf", "skls", "sksj", "skdd",
        "xqmc", "xkrs", "syrs", "skfsmc", "ctsm", "czOper",
    )

    private val FATAL_KEYWORDS = listOf(
        "验证码", "未登录", "重新登录", "不在选课时间", "选课已结束", "选课时间已过",
    )

    private val BR_RE = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val TAG_RE = Regex("""<[^>]+>""")
}
