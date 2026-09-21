package edu.jxslu.schedule.domain

/**
 * 编辑器纯函数（DESIGN §3.11/§4.20）：回车续行、格式动作、`$` 自动配对。
 *
 * **口径只有这一份**——UI 只负责把 [EditResult] 应用回 `TextFieldValue`，
 * 不许在 Composable 里另写续行/包裹判断（否则工具条与键盘行为迟早分叉）。
 * 全部纯 JVM，测试见 `MarkdownEditTest`。
 */

/** 编辑结果：新文本 + 新选区（默认折叠为一个光标位）。 */
data class EditResult(
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int = selectionStart,
)

/** 工具条动作（与 `ui/common/MarkdownEditor` 的按钮一一对应）。 */
enum class MdAction {
    /** 二级标题（行首 `## `，开关式） */
    Heading,

    /** 粗体（行内包裹） */
    Bold,

    /** 斜体（行内包裹） */
    Italic,

    /** 删除线（行内包裹） */
    Strike,

    /** 行内代码（反引号包裹） */
    Code,

    /** 行内公式（`$…$`，空选区时补 `$$`） */
    Math,

    /** 无序列表（行首 `- `，开关式） */
    Bullet,

    /** 有序列表（行首 `1. `，开关式；多行时递增编号） */
    Ordered,

    /** 任务项（行首 `- [ ] `，开关式） */
    Task,

    /** 引用（行首 `> `，开关式） */
    Quote,
}

// ---------------------------------------------------------------------------
// 回车续行
// ---------------------------------------------------------------------------

private val LINE_BULLET = Regex("""^(\s*)([-*+])(\s\[[ xX]\])?\s(.*)$""")
private val LINE_ORDERED = Regex("""^(\s*)(\d{1,9})([.)])\s(.*)$""")
private val LINE_QUOTE = Regex("""^(\s*)>\s?(.*)$""")

/**
 * 回车续行（Obsidian 式）。返回 null 表示**不干预**，交给输入框做普通换行：
 * - 光标不在行尾（本行还有非空白内容）→ 不干预（普通换行即可）
 * - 围栏代码块内 → 不干预
 * - 非列表/引用行 → 不干预
 *
 * 干预时分三种：
 * 1. 列表项有内容 → 插入换行 + 同款标记（有序递增、任务补 `[ ] `、缩进保留）；
 * 2. 列表项为空 → 删掉该行标记（结束列表），光标落在标记原位；
 * 3. 引用行 → 续 `> `。
 */
fun continueOnEnter(text: String, cursor: Int): EditResult? {
    if (cursor !in 0..text.length) return null
    val lineStart = text.lastIndexOf('\n', (cursor - 1).coerceAtLeast(0)).let {
        if (it < 0) 0 else it + 1
    }
    val line = text.substring(lineStart, cursor)
    if (line.isBlank()) return null
    // 光标右侧还有内容（同一行未到尾）：交给输入框做普通换行，不续行
    val lineEnd = text.indexOf('\n', cursor)
    val rest = if (lineEnd < 0) text.substring(cursor) else text.substring(cursor, lineEnd)
    if (rest.isNotBlank()) return null
    if (insideFence(text, lineStart)) return null

    LINE_BULLET.find(line)?.let { m ->
        val indent = m.groupValues[1]
        val marker = m.groupValues[2]
        val taskBox = m.groupValues[3]
        val content = m.groupValues[4]
        return if (content.isBlank()) {
            // 空项回车：结束列表（连同缩进一起清掉）
            EditResult(
                text = text.removeRange(lineStart, cursor),
                selectionStart = lineStart,
            )
        } else {
            val nextMarker = when {
                taskBox.isNotEmpty() -> "$marker [ ] "
                else -> "$marker "
            }
            val insert = "\n" + indent + nextMarker
            EditResult(text.substring(0, cursor) + insert + text.substring(cursor), cursor + insert.length)
        }
    }

    LINE_ORDERED.find(line)?.let { m ->
        val indent = m.groupValues[1]
        val number = m.groupValues[2].toIntOrNull() ?: return null
        val dot = m.groupValues[3]
        val content = m.groupValues[4]
        return if (content.isBlank()) {
            EditResult(text.removeRange(lineStart, cursor), lineStart)
        } else {
            val insert = "\n$indent${number + 1}$dot "
            EditResult(text.substring(0, cursor) + insert + text.substring(cursor), cursor + insert.length)
        }
    }

    LINE_QUOTE.find(line)?.let { m ->
        val indent = m.groupValues[1]
        val content = m.groupValues[2]
        return if (content.isBlank()) {
            EditResult(text.removeRange(lineStart, cursor), lineStart)
        } else {
            // 引用续行保持 `> ` 简洁形态（不保留 `>` 后缩进）
            val insert = "\n$indent> "
            EditResult(text.substring(0, cursor) + insert + text.substring(cursor), cursor + insert.length)
        }
    }

    return null
}

/** 光标所在位置之前是否有未闭合的围栏（奇数个 ``` 即视为在代码块内）。 */
private fun insideFence(text: String, upto: Int): Boolean {
    var count = 0
    var idx = text.indexOf("```", 0)
    while (idx in 0 until upto) {
        count++
        idx = text.indexOf("```", idx + 3)
    }
    return count % 2 == 1
}

// ---------------------------------------------------------------------------
// 工具条动作
// ---------------------------------------------------------------------------

/**
 * 应用一个格式动作。行内动作（粗/斜/删/码/公式）包裹选区或插入成对标记；
 * 行首动作（标题/列表/任务/引用）作用于选区覆盖的每一行，**同款前缀再点一次 = 取消**（开关式）。
 */
fun applyMarkdownAction(
    text: String,
    selectionStart: Int,
    selectionEnd: Int,
    action: MdAction,
): EditResult {
    val start = selectionStart.coerceIn(0, text.length)
    val end = selectionEnd.coerceIn(start, text.length)
    return when (action) {
        MdAction.Bold -> wrapInline(text, start, end, "**", "**")
        MdAction.Italic -> wrapInline(text, start, end, "*", "*")
        MdAction.Strike -> wrapInline(text, start, end, "~~", "~~")
        MdAction.Code -> wrapInline(text, start, end, "`", "`")
        MdAction.Math -> wrapInline(text, start, end, "$", "$")
        MdAction.Heading -> toggleHeading(text, start, end)
        MdAction.Bullet -> toggleLinePrefix(text, start, end, "- ")
        MdAction.Task -> toggleLinePrefix(text, start, end, "- [ ] ")
        MdAction.Quote -> toggleLinePrefix(text, start, end, "> ")
        MdAction.Ordered -> toggleOrdered(text, start, end)
    }
}

private fun wrapInline(text: String, start: Int, end: Int, open: String, close: String): EditResult {
    return if (end > start) {
        val selected = text.substring(start, end)
        // 选中内容已被同款标记包着 → 再点一次取消包裹
        if (selected.length >= open.length + close.length &&
            selected.startsWith(open) && selected.endsWith(close)
        ) {
            val inner = selected.substring(open.length, selected.length - close.length)
            EditResult(text.substring(0, start) + inner + text.substring(end), start, start + inner.length)
        } else {
            val newText = text.substring(0, start) + open + selected + close + text.substring(end)
            EditResult(newText, start + open.length, start + open.length + selected.length)
        }
    } else {
        val newText = text.substring(0, start) + open + close + text.substring(start)
        EditResult(newText, start + open.length)
    }
}

/** 行首动作：选区覆盖的每一行加上前缀；若都已带该前缀则整体去掉（开关式）。 */
private fun toggleLinePrefix(text: String, start: Int, end: Int, prefix: String): EditResult {
    val lineStart = text.lastIndexOf('\n', (start - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
    val lineEnd = text.indexOf('\n', end).let { if (it < 0) text.length else it }
    val before = text.substring(0, lineStart)
    val body = text.substring(lineStart, lineEnd)
    val after = text.substring(lineEnd)

    val lines = body.split("\n")
    val allPrefixed = lines.all { it.startsWith(prefix) }
    val newLines = if (allPrefixed) {
        lines.map { it.removePrefix(prefix) }
    } else {
        lines.map { if (it.startsWith(prefix)) it else prefix + it }
    }
    val newBody = newLines.joinToString("\n")
    val newText = before + newBody + after
    // 光标尽量贴住原位置（行首插入会让后续整体后移，简单按整体位移算）
    val delta = newBody.length - body.length
    return EditResult(newText, start + delta, end + delta)
}

/**
 * 标题（工具条只有一个 H2 按钮，行为可预期）：
 * 已是 `## ` → 去掉；是别的级别 → 换成 `## `；没有标题 → 加 `## `。
 */
private fun toggleHeading(text: String, start: Int, end: Int): EditResult {
    val lineStart = text.lastIndexOf('\n', (start - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
    val lineEnd = text.indexOf('\n', end).let { if (it < 0) text.length else it }
    val before = text.substring(0, lineStart)
    val body = text.substring(lineStart, lineEnd)
    val after = text.substring(lineEnd)

    val existing = Regex("""^(#{1,6})\s""")
    val lines = body.split("\n")
    val allH2 = lines.all { it.startsWith("## ") }
    val newLines = when {
        allH2 -> lines.map { it.removePrefix("## ") }
        else -> lines.map { line ->
            if (existing.containsMatchIn(line)) existing.replace(line) { "## " } else "## " + line
        }
    }
    val newBody = newLines.joinToString("\n")
    val delta = newBody.length - body.length
    return EditResult(before + newBody + after, start + delta, end + delta)
}

/** 有序列表：选中多行时按 1. 2. 3. 递增编号；全部已编号则整体去掉（开关式）。 */
private fun toggleOrdered(text: String, start: Int, end: Int): EditResult {
    val lineStart = text.lastIndexOf('\n', (start - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
    val lineEnd = text.indexOf('\n', end).let { if (it < 0) text.length else it }
    val before = text.substring(0, lineStart)
    val body = text.substring(lineStart, lineEnd)
    val after = text.substring(lineEnd)

    val lines = body.split("\n")
    val pattern = Regex("""^(\d{1,9})[.)]\s""")
    val allNumbered = lines.all { pattern.containsMatchIn(it) }
    val newLines = if (allNumbered) {
        lines.map { it.replaceFirst(pattern, "") }
    } else {
        lines.mapIndexed { index, line ->
            if (pattern.containsMatchIn(line)) line else "${index + 1}. $line"
        }
    }
    val newBody = newLines.joinToString("\n")
    val delta = newBody.length - body.length
    return EditResult(before + newBody + after, start + delta, end + delta)
}

// ---------------------------------------------------------------------------
// 输入期自动配对
// ---------------------------------------------------------------------------

/**
 * 输入 `$` 时自动补成 `$$`（光标居中），对齐 Obsidian 的公式配对。
 * 返回 null = 不干预。仅在「这次输入刚好插入了一个 `$`」且右邻不是 `$` 时生效，
 * 用户自己敲第二个 `$` 不会被插队。
 */
fun autoPairDollar(before: String, after: String, cursor: Int): EditResult? {
    if (cursor !in 1..after.length) return null
    if (after.length != before.length + 1) return null
    if (after[cursor - 1] != '$') return null
    if (after.substring(0, cursor - 1) != before.substring(0, cursor - 1) ||
        after.substring(cursor) != before.substring(cursor - 1)
    ) {
        return null
    }
    if (cursor < after.length && after[cursor] == '$') return null
    val newText = after.substring(0, cursor) + "$" + after.substring(cursor)
    return EditResult(newText, cursor)
}

// ---------------------------------------------------------------------------
// 图片插入
// ---------------------------------------------------------------------------

/**
 * 在光标处插入一条图片引用（独占一行；前后补空行防止粘进上一段）。
 * [cursor] 与 [selectionEnd] 用 [EditResult] 的语义返回，光标落在插入行之后。
 */
fun insertImageRef(text: String, cursor: Int, fileName: String): EditResult {
    val pos = cursor.coerceIn(0, text.length)
    val token = imageRefToken(fileName)
    val needsLeadingBreak = pos > 0 && text[pos - 1] != '\n'
    val needsTrailingBreak = pos < text.length && text[pos] != '\n'
    val insert = buildString {
        if (text.isNotEmpty() && needsLeadingBreak) append("\n\n") else if (text.isEmpty()) Unit
        append(token)
        if (needsTrailingBreak) append("\n\n") else append("\n")
    }
    val newText = text.substring(0, pos) + insert + text.substring(pos)
    return EditResult(newText, pos + insert.length)
}
