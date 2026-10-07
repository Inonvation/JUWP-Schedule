package edu.jxslu.schedule.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * AI 调课的工具调用纯逻辑（DESIGN §4.39，2026-10-06）。
 *
 * 模型通过 `propose_schedule_changes` 返回结构化改动，本类负责：
 * 1. 提供工具 JSON Schema（[PARAMETERS_JSON]，发给 `tools[0].function.parameters`）；
 * 2. 把 `tool_calls[].function.arguments` 解析成 [ScheduleDiff.Change]；
 * 3. **严格校验**——DeepSeek 文档明写模型「不总是生成合法 JSON，可能幻觉 schema 外的参数」，
 *    所以越界序号、缺必填字段、非法类型一律丢弃并在 [Result.rejected] 里留痕，不整批失败。
 *
 * 关键口径：`modify` / `remove` 只认**编号**（`existingIndex`，1 起始），
 * `old` 由本类从 [parse] 的 `current` 里取——**绝不采信模型回抄的旧字段**。
 * 模型重抄会漂移，而 `ScheduleDiff.fingerprint` 靠字段全等匹配本机行，抄错就静默跳过。
 */
object AiScheduleTool {

    /** 工具函数名（`tool_choice` 指定它，App 侧只认这一个）。 */
    const val FUNCTION_NAME = "propose_schedule_changes"

    /** 节次上限：作息表 11 小节（DESIGN §3.5）。 */
    const val MAX_SECTION = 11

    /** 周次上限：一学期最多按 30 周兜底，超出的周次视为模型幻觉。 */
    const val MAX_WEEK = 30

    /** 工具 JSON Schema（`tools[0].function.parameters`）。 */
    val PARAMETERS_JSON: String = """
        {
          "type": "object",
          "properties": {
            "message": {
              "type": "string",
              "description": "给用户看的简体中文说明，一句话，不超过 30 字。直接说结论，不要写「好的」「我来帮你分析」这类开场白，也不要写总结。通知里的课不在课表清单时，在此说明原因并返回空 changes。"
            },
            "readback": {
              "type": "string",
              "description": "仅当用户发来图片时填：把图里读到的通知要点逐条列出（一行一条），供用户核对识别是否准确。没发图片时留空字符串。"
            },
            "changes": {
              "type": "array",
              "description": "对课表的改动列表；没有可执行的改动时返回空数组。",
              "items": {
                "type": "object",
                "properties": {
                  "kind": { "type": "string", "enum": ["add", "modify", "remove"] },
                  "existingIndex": {
                    "type": "integer",
                    "description": "modify / remove 必填：对应课表清单里的 1 起始编号。"
                  },
                  "new": {
                    "type": "object",
                    "description": "add 必填（填全字段）；modify 只填发生变化的字段。",
                    "properties": {
                      "name": { "type": "string" },
                      "teacher": { "type": "string" },
                      "position": { "type": "string" },
                      "day": { "type": "integer", "description": "1=周一 … 7=周日" },
                      "startSection": { "type": "integer", "description": "1..11" },
                      "endSection": { "type": "integer", "description": "1..11" },
                      "weeks": { "type": "array", "items": { "type": "integer" } },
                      "kind": { "type": "string", "enum": ["theory", "lab"] }
                    }
                  },
                  "reason": { "type": "string", "description": "这条改动的简短理由。" }
                },
                "required": ["kind"]
              }
            },
            "overrides": {
              "type": "array",
              "description": "日期覆盖：放假 / 调休补课。这类安排按具体日期发布，用 changes 表达不了，必须走这里。没有则返回空数组。",
              "items": {
                "type": "object",
                "properties": {
                  "kind": { "type": "string", "enum": ["follow", "suspended"] },
                  "fromDate": { "type": "string", "description": "yyyy-MM-dd。suspended 时是区间起日。" },
                  "toDate": { "type": "string", "description": "yyyy-MM-dd；单日时留空或与 fromDate 相同。" },
                  "sourceWeek": {
                    "type": "integer",
                    "description": "follow 必填：照搬哪个周次的课表（通知里「按照第N周…的教学安排进行」的那个 N）。"
                  },
                  "sourceWeekday": {
                    "type": "integer",
                    "description": "follow 必填：照搬那个周次的星期几，1=周一 … 7=周日。"
                  },
                  "reason": { "type": "string" }
                },
                "required": ["kind", "fromDate"]
              }
            }
          },
          "required": ["message", "changes", "overrides"]
        }
    """.trimIndent()

    /** 一条被拒的提议（无法解析 / 越界 / 缺字段），原文与原因都留给 UI 展示。 */
    data class Rejection(val raw: String, val reason: String)

    /**
     * 解析结果。[changes] 与 [reasons] 同序同长（[reasons] 允许空串），
     * [overrides] 与 [overrideReasons] 同理；[rejected] 是没被采纳的条目，
     * UI 逐条提示，不影响其余改动。
     *
     * [readback] 只在用户发了图片时有值（模型复述它读到的通知），UI 拿它做核对块。
     */
    data class Result(
        val message: String = "",
        val readback: String = "",
        val changes: List<ScheduleDiff.Change> = emptyList(),
        val reasons: List<String> = emptyList(),
        val overrides: List<ScheduleOverride> = emptyList(),
        val overrideReasons: List<String> = emptyList(),
        val rejected: List<Rejection> = emptyList(),
    )

    private val format = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 解析 tool_call 的 `arguments`（JSON 字符串）。
     * [current] 必须是**非考试**条目，且与发给模型的编号清单同源同序。
     * [existingOverrides] 是当前已生效的日期安排：与之**等价**的提议不再列为待确认
     * （确定性兜底——prompt 里已让模型别重报，模型不听话时这里兜住），进 [Result.rejected]
     * 并注明原因，不静默吞。
     */
    fun parse(
        argumentsJson: String,
        current: List<Course>,
        semester: SemesterConfig? = null,
        maskedTeacherNames: Set<String> = emptySet(),
        existingOverrides: List<ScheduleOverride> = emptyList(),
    ): Result {
        val root = runCatching { format.parseToJsonElement(argumentsJson) }.getOrNull() as? JsonObject
            ?: return Result(
                rejected = listOf(Rejection(argumentsJson.take(200), "工具参数不是合法 JSON 对象")),
            )
        return assemble(
            message = root.str("message").orEmpty(),
            readback = root.str("readback").orEmpty().trim(),
            changeItems = (root["changes"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject },
            overrideItems = (root["overrides"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject },
            current = current,
            semester = semester,
            maskedTeacherNames = maskedTeacherNames,
            existingOverrides = existingOverrides,
        )
    }

    /**
     * **流式增量解析**（2026-10-06）：工具参数是分片到达的，整体 JSON 在收完之前都是半截的。
     * 本方法从半截文本里**只捞已经闭合完整的条目**，让 UI 能随吐字逐张长出卡片——
     * 不然「流式」只体现在网络层，用户看到的还是「转圈 → 一次性出结果」。
     *
     * 半截 JSON 里 `message` 可能还没写完，一律不取（宁可暂时不显示，也不显示半句话）；
     * 未闭合的条目直接跳过，不会进 [Result.rejected]（那不是错误，只是还没到）。
     * `readback` 同理——半句话的核对块比没有更糟，等收完再显示。
     */
    fun parsePartial(
        argumentsJson: String,
        current: List<Course>,
        semester: SemesterConfig? = null,
        maskedTeacherNames: Set<String> = emptySet(),
        existingOverrides: List<ScheduleOverride> = emptyList(),
    ): Result = assemble(
        message = "",
        changeItems = extractCompleteItems(argumentsJson, "changes"),
        overrideItems = extractCompleteItems(argumentsJson, "overrides"),
        current = current,
        semester = semester,
        maskedTeacherNames = maskedTeacherNames,
        existingOverrides = existingOverrides,
    )

    /**
     * 从（可能不完整的）JSON 文本里抽出 `key` 数组下**已经闭合**的对象。
     * 括号配对要考虑字符串里的 `{` `}` 与转义，所以自己扫而不是正则。
     */
    private fun extractCompleteItems(text: String, key: String): List<JsonObject> {
        val keyIndex = text.indexOf("\"$key\"")
        if (keyIndex < 0) return emptyList()
        // 键与 '[' 之间**只允许**冒号和空白：`"changes":null` 后面还跟着 overrides 数组时，
        // 直接 indexOf('[') 会锁到 overrides 的 '['，把覆盖条目当改动解析
        var cursor = keyIndex + key.length + 2
        while (cursor < text.length && text[cursor].isWhitespace()) cursor++
        if (cursor >= text.length || text[cursor] != ':') return emptyList()
        cursor++
        while (cursor < text.length && text[cursor].isWhitespace()) cursor++
        if (cursor >= text.length || text[cursor] != '[') return emptyList()
        val bracket = cursor
        val out = mutableListOf<JsonObject>()
        var index = bracket + 1
        while (index < text.length) {
            when (text[index]) {
                '{' -> {
                    val end = matchBrace(text, index)
                    if (end < 0) return out
                    runCatching { format.parseToJsonElement(text.substring(index, end + 1)) }
                        .getOrNull()
                        ?.let { it as? JsonObject }
                        ?.let { out += it }
                    index = end + 1
                }

                ']' -> return out

                else -> index++
            }
        }
        return out
    }

    /** 从 `start` 处的 `{` 配对到对应 `}` 的下标；未闭合返回 -1。字符串与转义不参与配对。 */
    private fun matchBrace(text: String, start: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    /** 逐条校验并组装（[parse] 与 [parsePartial] 共用同一套口径）。 */
    private fun assemble(
        message: String,
        changeItems: List<JsonObject>,
        overrideItems: List<JsonObject>,
        current: List<Course>,
        semester: SemesterConfig?,
        readback: String = "",
        maskedTeacherNames: Set<String> = emptySet(),
        existingOverrides: List<ScheduleOverride> = emptyList(),
    ): Result {
        val changes = mutableListOf<ScheduleDiff.Change>()
        val reasons = mutableListOf<String>()
        val rejected = mutableListOf<Rejection>()

        changeItems.forEach { obj ->
            val element = obj
            val kind = obj.str("kind").orEmpty().lowercase()
            val reason = obj.str("reason").orEmpty()
            when (kind) {
                "modify" -> {
                    val old = resolveIndex(obj, current, rejected, element) ?: return@forEach
                    val fields = parseFields(obj["new"], maskedTeacherNames)
                    if (fields == null || fields.isEmpty) {
                        rejected += Rejection(element.toString().take(200), "modify 没有给出要修改的字段")
                        return@forEach
                    }
                    val updated = fields.applyTo(old)
                    if (updated == null) {
                        rejected += Rejection(element.toString().take(200), "修改后的时间/周次不合法")
                        return@forEach
                    }
                    if (ScheduleDiff.fingerprint(updated) == ScheduleDiff.fingerprint(old)) {
                        rejected += Rejection(element.toString().take(200), "修改前后完全一致，无实际变化")
                        return@forEach
                    }
                    changes += ScheduleDiff.Change(ScheduleDiff.ChangeKind.MODIFIED, old = old, new = updated)
                    reasons += reason
                }

                "remove" -> {
                    val old = resolveIndex(obj, current, rejected, element) ?: return@forEach
                    changes += ScheduleDiff.Change(ScheduleDiff.ChangeKind.REMOVED, old = old)
                    reasons += reason
                }

                "add" -> {
                    val fields = parseFields(obj["new"], maskedTeacherNames)
                    val course = fields?.toNewCourse()
                    if (course == null) {
                        rejected += Rejection(
                            element.toString().take(200),
                            "add 缺少必填字段（name / day / startSection / endSection / weeks）或取值不合法",
                        )
                        return@forEach
                    }
                    changes += ScheduleDiff.Change(ScheduleDiff.ChangeKind.ADDED, new = course)
                    reasons += reason
                }

                else -> rejected += Rejection(
                    element.toString().take(200),
                    "未知的改动类型「${obj.str("kind").orEmpty()}」",
                )
            }
        }

        // 日期覆盖（调休 / 停课，§4.40）：按具体日期发布的安排走这一路
        val overrides = mutableListOf<ScheduleOverride>()
        val overrideReasons = mutableListOf<String>()
        overrideItems.forEach { obj ->
            val parsed = parseOverride(obj, semester)
            when {
                parsed == null -> rejected += Rejection(
                    obj.toString().take(200),
                    "日期覆盖不合法（日期不是 yyyy-MM-dd，或 follow 缺 sourceWeek / sourceWeekday）",
                )

                // 与已生效的安排等价 = 重复提议（模型重发了同一通知）——不静默吞，
                // 进 rejected 说明白；prompt 侧已交代过「别再给出」，这里是确定性兜底
                existingOverrides.any { ScheduleOverrides.sameEffect(it, parsed) } -> rejected += Rejection(
                    obj.toString().take(200),
                    "该日期安排已生效，无需重复（" + ScheduleOverrides.describe(parsed, semester) + "）",
                )

                else -> {
                    overrides += parsed
                    overrideReasons += obj.str("reason").orEmpty()
                }
            }
        }
        return Result(
            message = message,
            readback = readback,
            changes = changes,
            reasons = reasons,
            overrides = overrides,
            overrideReasons = overrideReasons,
            rejected = rejected,
        )
    }

    /** 解析一条日期覆盖；不合法返回 null（由调用方记进 rejected）。 */
    private fun parseOverride(obj: JsonObject, semester: SemesterConfig?): ScheduleOverride? {
        val from = obj.str("fromDate")?.takeIf { it.isNotBlank() } ?: return null
        if (parseDateOrNull(from) == null) return null
        val to = obj.str("toDate")?.takeIf { it.isNotBlank() } ?: from
        if (parseDateOrNull(to) == null) return null
        return when (obj.str("kind").orEmpty().lowercase()) {
            "suspended" -> ScheduleOverride(
                fromDate = from,
                toDate = to,
                kind = OverrideKind.SUSPENDED,
                note = obj.str("reason").orEmpty(),
            )

            "follow" -> {
                val week = obj.int("sourceWeek") ?: return null
                val weekday = obj.int("sourceWeekday") ?: return null
                if (weekday !in 1..7) return null
                if (semester != null && week !in 1..semester.totalWeeks) return null
                // follow 是单日的：区间形态只取起日，不猜「区间里每天都照搬」
                ScheduleOverride(
                    fromDate = from,
                    toDate = from,
                    kind = OverrideKind.FOLLOW,
                    sourceWeek = week,
                    sourceWeekday = weekday,
                    note = obj.str("reason").orEmpty(),
                )
            }

            else -> null
        }
    }

    private fun parseDateOrNull(value: String): java.time.LocalDate? =
        runCatching { ScheduleCalculator.parseDate(value) }.getOrNull()

    /**
     * 模型没走 tool_call 时的兜底：从正文里抽第一段**配对完整**的 JSON 对象。
     * 会剥掉 ```json 代码围栏；抽不到返回 null。
     */
    fun extractJson(text: String): String? {
        val stripped = text.replace("```json", "```").let { t ->
            val fence = t.indexOf("```")
            if (fence < 0) t else t.removeRange(fence, fence + 3)
        }
        // 逐个候选试：说明文字里的花括号（「用 { } 表示」）会顶掉真正的 JSON，
        // 所以要**取第一个能解析成 JsonObject 的片段**，不是第一个配对成功的片段
        var searchFrom = 0
        while (true) {
            val start = stripped.indexOf('{', searchFrom)
            if (start < 0) return null
            val end = matchBrace(stripped, start)
            if (end < 0) return null
            val candidate = stripped.substring(start, end + 1)
            if (runCatching { format.parseToJsonElement(candidate) }.getOrNull() is JsonObject) {
                return candidate
            }
            searchFrom = start + 1
        }
    }

    private fun resolveIndex(
        obj: JsonObject,
        current: List<Course>,
        rejected: MutableList<Rejection>,
        element: JsonElement,
    ): Course? {
        val index = (obj["existingIndex"] as? JsonPrimitive)?.intOrNull
        if (index == null || index !in 1..current.size) {
            rejected += Rejection(
                element.toString().take(200),
                "课表编号 ${index ?: "缺失"} 越界（当前课表共 ${current.size} 条）",
            )
            return null
        }
        return current[index - 1]
    }

    /** 模型给出的新字段，全部可空——只填变化的字段是合法的。 */
    private data class Fields(
        val name: String? = null,
        val teacher: String? = null,
        val position: String? = null,
        val day: Int? = null,
        val startSection: Int? = null,
        val endSection: Int? = null,
        val weeks: List<Int>? = null,
        val kind: CourseKind? = null,
    ) {
        val isEmpty: Boolean
            get() = name == null && teacher == null && position == null && day == null &&
                startSection == null && endSection == null && weeks == null && kind == null

        /**
         * 在 [old] 之上叠加本次字段；时间/周次不合法返回 null。
         *
         * [masked] 是发给模型的**脱敏占位名**集合（「教师1」…）。模型只见过占位名，
         * 追问轮又要求它「没提到的条目原样重发」，所以它回抄的 teacher 很可能就是占位名——
         * **必须当「未提供」处理**，否则本机真实姓名被静默覆盖且无从还原。
         */
        fun applyTo(old: Course, masked: Set<String> = emptySet()): Course? {
            val newDay = day ?: old.day
            val start = startSection ?: old.startSection
            val end = endSection ?: old.endSection
            if (!validSpan(newDay, start, end)) return null
            // 上限只约束**模型给的**周次；本机旧值原样保留——导入允许到 40 周，
            // 一门 31 周以后的课被 AI 改个教室时不该把那些周次静默丢掉
            val newWeeks = if (weeks == null) old.weeks else weeks.filter { it in 1..MAX_WEEK }.toSet()
            if (newWeeks.isEmpty()) return null
            return old.copy(
                name = name?.takeIf { it.isNotBlank() } ?: old.name,
                // 空串与脱敏占位名都当「未提供」：模型回 "" 不该把本机姓名清掉，
                // 回抄「教师1」更不该把它覆盖成占位名
                teacher = teacher?.takeIf { it.isNotBlank() && it !in masked } ?: old.teacher,
                // 同一口径：回 "" 不该把地点静默清掉（position 进指纹，清了会以「调整」名义落库）
                position = position?.takeIf { it.isNotBlank() } ?: old.position,
                day = newDay,
                startSection = start,
                endSection = end,
                weeks = newWeeks,
                kind = kind ?: old.kind,
            )
        }

        /** 拼一门新课（add）；缺必填字段或取值不合法返回 null。 */
        fun toNewCourse(): Course? {
            val courseName = name?.takeIf { it.isNotBlank() } ?: return null
            val newDay = day ?: return null
            val start = startSection ?: return null
            val end = endSection ?: return null
            if (!validSpan(newDay, start, end)) return null
            val newWeeks = (weeks ?: return null).filter { it in 1..MAX_WEEK }.toSet()
            if (newWeeks.isEmpty()) return null
            return Course(
                id = 0,
                name = courseName,
                teacher = teacher.orEmpty(),
                position = position.orEmpty(),
                day = newDay,
                startSection = start,
                endSection = end,
                weeks = newWeeks,
                kind = kind ?: CourseKind.Theory,
            )
        }

        private fun validSpan(day: Int, start: Int, end: Int): Boolean =
            day in 1..7 && start in 1..MAX_SECTION && end in 1..MAX_SECTION && start <= end
    }

    private fun parseFields(element: JsonElement?, maskedTeacherNames: Set<String> = emptySet()): Fields? {
        val obj = element as? JsonObject ?: return null
        return Fields(
            name = obj.str("name"),
            teacher = obj.str("teacher")?.takeIf { it !in maskedTeacherNames },
            position = obj.str("position"),
            day = obj.int("day"),
            startSection = obj.int("startSection"),
            endSection = obj.int("endSection"),
            weeks = (obj["weeks"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.intOrNull },
            kind = obj.str("kind")?.let { raw ->
                when (raw.lowercase()) {
                    "theory" -> CourseKind.Theory
                    "lab" -> CourseKind.Lab
                    else -> null
                }
            },
        )
    }

    private fun JsonObject.str(key: String): String? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        return primitive.contentOrNull
    }

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
}
