package edu.jxslu.schedule.domain

/**
 * Markdown 子集解析（DESIGN §4.20，支持清单以文档为准）。
 *
 * 三条纪律（改动前先读）：
 * 1. **超范围语法不报错也不吞**——表格、HTML、脚注等按普通段落文本渲染，
 *    绝不"识别一半"把用户内容吃掉；
 * 2. **未闭合的强调/代码/公式按字面文本处理**（宁可少渲染，不能丢字符）；
 * 3. **单换行 = 换行**（段落内的 `\n` 原样保留，渲染层逐行断开）——对齐 Obsidian
 *    默认行为，不启用 strict line breaks。
 *
 * 纯 JVM 逻辑，测试见 `MarkdownParserTest`；渲染在 `ui/common/MarkdownView.kt`，
 * 编辑期自动补全在 `MarkdownEdit.kt`。
 */

// ---------------------------------------------------------------------------
// AST
// ---------------------------------------------------------------------------

/** 块级节点。 */
sealed interface MdBlock {
    data class Heading(val level: Int, val content: List<MdInline>) : MdBlock
    data class Paragraph(val content: List<MdInline>) : MdBlock
    /** 无序 / 有序列表（`start` 对无序无意义，恒 1）。 */
    data class ListBlock(val ordered: Boolean, val start: Int, val items: List<MdItem>) : MdBlock
    data class Quote(val blocks: List<MdBlock>) : MdBlock
    data class Code(val language: String, val text: String) : MdBlock
    /** `$$…$$` 公式块。 */
    data class MathBlock(val tex: String) : MdBlock
    data object Divider : MdBlock
}

/** 列表项：[task] 非空表示任务项（`- [ ]`=false / `- [x]`=true），[sub] 是嵌套的块（子列表等）。 */
data class MdItem(
    val content: List<MdInline>,
    val task: Boolean? = null,
    val sub: List<MdBlock> = emptyList(),
)

/** 行内样式（粗/斜/删除线同一族，渲染时叠加）。 */
enum class MdStyle { Bold, Italic, Strike }

/** 行内节点。 */
sealed interface MdInline {
    data class Text(val text: String) : MdInline
    data class Styled(val style: MdStyle, val content: List<MdInline>) : MdInline
    data class Code(val text: String) : MdInline
    data class Link(val content: List<MdInline>, val url: String) : MdInline
    /** [ref] 形如 `img:文件名`（本地附件）或 `http(s)://…`（外链，渲染层给不支持提示）。 */
    data class Image(val alt: String, val ref: String) : MdInline
    /** 行内公式（`$…$`）。 */
    data class Math(val tex: String) : MdInline
}

// ---------------------------------------------------------------------------
// 块级解析
// ---------------------------------------------------------------------------

private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")
private val DIVIDER = Regex("""^\s{0,3}(-{3,}|\*{3,}|_{3,})\s*$""")
private val FENCE = Regex("""^\s{0,3}(`{3,}|~{3,})\s*([^\s`]*)""")
private val BULLET = Regex("""^(\s*)([-*+])\s+(.*)$""")
private val ORDERED = Regex("""^(\s*)(\d{1,9})[.)]\s+(.*)$""")
private val TASK = Regex("""^\[([ xX])\]\s+(.*)$""")

/** 缩进宽度：tab 记 4，空格按 1。 */
private fun indentOf(line: String): Int {
    var width = 0
    for (ch in line) {
        when (ch) {
            ' ' -> width += 1
            '	' -> width += 4
            else -> return width
        }
    }
    return width
}

fun parseMarkdown(text: String): List<MdBlock> {
    val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val blocks = mutableListOf<MdBlock>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        if (line.isBlank()) {
            i++
            continue
        }

        // 围栏代码块（未闭合 = 吃到文末，内容照常展示）
        val fence = FENCE.find(line)
        if (fence != null) {
            val marker = fence.groupValues[1]
            val language = fence.groupValues[2]
            i++
            val body = StringBuilder()
            while (i < lines.size && !isFenceClose(lines[i], marker)) {
                if (body.isNotEmpty()) body.append('\n')
                body.append(lines[i])
                i++
            }
            if (i < lines.size) i++
            blocks += MdBlock.Code(language, body.toString())
            continue
        }

        // $$ 公式块：同行闭合（$$x$$）或跨行吃到下一个 $$
        val trimmed = line.trimStart()
        if (trimmed.startsWith("$$")) {
            val inlineClose = trimmed.indexOf("$$", startIndex = 2)
            if (inlineClose >= 0) {
                blocks += MdBlock.MathBlock(trimmed.substring(2, inlineClose).trim())
                i++
            } else {
                val body = StringBuilder(trimmed.removePrefix("$$"))
                i++
                var closed = false
                while (i < lines.size) {
                    val close = lines[i].indexOf("$$")
                    if (close >= 0) {
                        if (body.isNotEmpty()) body.append('\n')
                        body.append(lines[i].take(close))
                        i++
                        closed = true
                        break
                    }
                    if (body.isNotEmpty()) body.append('\n')
                    body.append(lines[i])
                    i++
                }
                @Suppress("UNUSED_EXPRESSION") closed
                blocks += MdBlock.MathBlock(body.toString().trim())
            }
            continue
        }

        val heading = HEADING.find(line)
        if (heading != null) {
            blocks += MdBlock.Heading(
                heading.groupValues[1].length,
                parseInlines(heading.groupValues[2].trim()),
            )
            i++
            continue
        }

        if (DIVIDER.matches(line)) {
            blocks += MdBlock.Divider
            i++
            continue
        }

        // 引用：剥掉 `> ` 后按块递归（可嵌套）
        if (line.trimStart().startsWith(">")) {
            val inner = mutableListOf<String>()
            while (i < lines.size && (lines[i].trimStart().startsWith(">") || lines[i].isBlank())) {
                if (lines[i].isBlank()) {
                    // 引用内的空行：只有在下一行仍是引用时才继续
                    if (i + 1 < lines.size && lines[i + 1].trimStart().startsWith(">")) inner += "" else break
                } else {
                    inner += lines[i].trimStart().removePrefix(">").removePrefix(" ")
                }
                i++
            }
            blocks += MdBlock.Quote(parseMarkdown(inner.joinToString("\n")))
            continue
        }

        if (BULLET.containsMatchIn(line) || ORDERED.containsMatchIn(line)) {
            val (block, next) = parseList(lines, i)
            blocks += block
            i = next
            continue
        }

        // 段落：吃到空行或下一个块起点
        val para = mutableListOf<String>()
        while (i < lines.size && lines[i].isNotBlank() && !startsBlock(lines[i])) {
            para += lines[i]
            i++
        }
        if (para.isEmpty()) {
            // 理论不可达（startsBlock 与上方分支一致）；保底前进避免死循环
            para += lines[i]
            i++
        }
        blocks += MdBlock.Paragraph(parseInlines(para.joinToString("\n")))
    }
    return blocks
}

/** 行首是否是块级语法（段落收集的终止条件）。 */
private fun startsBlock(line: String): Boolean =
    FENCE.containsMatchIn(line) ||
        line.trimStart().startsWith("$$") ||
        HEADING.containsMatchIn(line) ||
        DIVIDER.matches(line) ||
        line.trimStart().startsWith(">") ||
        BULLET.containsMatchIn(line) ||
        ORDERED.containsMatchIn(line)

private fun isFenceClose(line: String, marker: String): Boolean {
    val t = line.trim()
    return t.length >= marker.length &&
        t.all { it == marker[0] } &&
        t.startsWith(marker[0].toString().repeat(marker.length))
}

/**
 * 列表解析（支持按缩进嵌套，缩进步长 ≥ 2）。
 * 返回（块, 下一个待处理行号）；同一父级下有序/无序混排按「首个标记」定块类型，
 * 混排中途换标记视为新列表（简单、可预期，够用）。
 */
private fun parseList(lines: List<String>, start: Int): Pair<MdBlock.ListBlock, Int> {
    val firstBullet = BULLET.find(lines[start])
    val firstOrdered = ORDERED.find(lines[start])
    val ordered = firstBullet == null || (firstOrdered != null && firstOrdered.range.first < firstBullet.range.first)
    val baseIndent = indentOf(lines[start])
    val startNumber = if (ordered) ORDERED.find(lines[start])?.groupValues?.get(2)?.toIntOrNull() ?: 1 else 1

    val items = mutableListOf<MdItem>()
    var i = start
    while (i < lines.size) {
        val line = lines[i]
        if (line.isBlank()) {
            // 空行后还有同缩进的列表项 → 属于同一列表（松散列表的本子集）
            val nextIdx = nextNonBlank(lines, i)
            if (nextIdx == null || indentOf(lines[nextIdx]) < baseIndent ||
                !(BULLET.containsMatchIn(lines[nextIdx]) || ORDERED.containsMatchIn(lines[nextIdx]))
            ) {
                break
            }
            i = nextIdx
            continue
        }
        val bullet = BULLET.find(line)
        val numbered = ORDERED.find(line)
        val isItem = (bullet != null || numbered != null) && indentOf(line) == baseIndent
        if (!isItem) {
            // 续行（更深的缩进、非列表）：并入上一项内容
            if (items.isNotEmpty() && indentOf(line) > baseIndent) {
                val last = items.removeAt(items.size - 1)
                val extra = line.trim()
                items += last.copy(content = last.content + MdInline.Text("\n" + extra))
                i++
                continue
            }
            break
        }

        val raw = (bullet?.groupValues?.get(3) ?: numbered!!.groupValues[3])
        var task: Boolean? = null
        var contentText = raw
        TASK.find(raw)?.let {
            task = it.groupValues[1].equals("x", ignoreCase = true)
            contentText = it.groupValues[2]
        }

        // 子块（缩进 ≥ baseIndent + 2 且是列表/引用/围栏的后续行）
        i++
        val subLines = mutableListOf<String>()
        while (i < lines.size && lines[i].isNotBlank() && indentOf(lines[i]) >= baseIndent + 2) {
            val trimmedLine = lines[i].trim()
            if (BULLET.containsMatchIn(trimmedLine) || ORDERED.containsMatchIn(trimmedLine) ||
                trimmedLine.startsWith(">") || FENCE.containsMatchIn(trimmedLine) ||
                trimmedLine.startsWith("$$")
            ) {
                // 收进子块时统一压平一级缩进，交给递归解析
                subLines += lines[i].removePrefix(" ".repeat(baseIndent + 2))
                i++
            } else {
                break
            }
        }
        val sub = if (subLines.isEmpty()) emptyList() else parseMarkdown(subLines.joinToString("\n"))
        items += MdItem(parseInlines(contentText), task, sub)
    }
    return MdBlock.ListBlock(ordered, startNumber, items) to i
}

private fun nextNonBlank(lines: List<String>, from: Int): Int? {
    var i = from
    while (i < lines.size && lines[i].isBlank()) i++
    return if (i < lines.size) i else null
}

// ---------------------------------------------------------------------------
// 行内解析
// ---------------------------------------------------------------------------

private fun isWordBoundary(text: String, index: Int): Boolean =
    index == 0 || text[index - 1].isWhitespace() || text[index - 1] in "([{>*-_"

/** 转义字符集：ASCII 标点（CommonMark 口径的实用子集）。 */
private fun isEscapable(c: Char): Boolean = c in "\\`*_{}[]()#+-.!>~$|"

fun parseInlines(text: String): List<MdInline> {
    val out = mutableListOf<MdInline>()
    val plain = StringBuilder()
    var i = 0

    fun flush() {
        if (plain.isNotEmpty()) {
            out += MdInline.Text(plain.toString())
            plain.setLength(0)
        }
    }

    while (i < text.length) {
        val c = text[i]
        when {
            c == '\\' && i + 1 < text.length && isEscapable(text[i + 1]) -> {
                plain.append(text[i + 1]); i += 2
            }

            c == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > i + 1) {
                    flush(); out += MdInline.Code(text.substring(i + 1, end)); i = end + 1
                } else {
                    plain.append(c); i++
                }
            }

            c == '$' -> {
                // 行内公式：同一行内闭合、内容非空、紧跟非空白（`$100` 这种金额不当公式）
                val end = text.indexOf('$', i + 1)
                val inner = if (end > i) text.substring(i + 1, end) else ""
                if (end > i + 1 && !inner.first().isWhitespace() && !inner.contains('\n') && inner.isNotBlank()) {
                    flush(); out += MdInline.Math(inner); i = end + 1
                } else {
                    plain.append(c); i++
                }
            }

            text.startsWith("![", i) -> {
                val parsed = parseBracket(text, i + 1)
                if (parsed != null) {
                    flush()
                    out += MdInline.Image(parsed.first, parsed.second)
                    i = parsed.third
                } else {
                    plain.append(c); i++
                }
            }

            c == '[' -> {
                val parsed = parseBracket(text, i)
                if (parsed != null) {
                    flush()
                    out += MdInline.Link(parseInlines(parsed.first), parsed.second)
                    i = parsed.third
                } else {
                    plain.append(c); i++
                }
            }

            text.startsWith("**", i) || text.startsWith("__", i) -> {
                val marker = text.substring(i, i + 2)
                // 下划线强调要求在词边界（`snake_case` 不是斜体）
                val boundaryOk = marker == "**" || isWordBoundary(text, i)
                val end = if (boundaryOk) findClose(text, i + 2, marker) else -1
                if (end > 0) {
                    flush()
                    out += MdInline.Styled(MdStyle.Bold, parseInlines(text.substring(i + 2, end)))
                    i = end + 2
                } else {
                    plain.append(marker); i += 2
                }
            }

            text.startsWith("~~", i) -> {
                val end = findClose(text, i + 2, "~~")
                if (end > 0) {
                    flush()
                    out += MdInline.Styled(MdStyle.Strike, parseInlines(text.substring(i + 2, end)))
                    i = end + 2
                } else {
                    plain.append("~~"); i += 2
                }
            }

            c == '*' || c == '_' -> {
                val boundaryOk = c == '*' || isWordBoundary(text, i)
                val end = if (boundaryOk) findClose(text, i + 1, c.toString()) else -1
                if (end > i + 1) {
                    flush()
                    out += MdInline.Styled(MdStyle.Italic, parseInlines(text.substring(i + 1, end)))
                    i = end + 1
                } else {
                    plain.append(c); i++
                }
            }

            else -> {
                plain.append(c); i++
            }
        }
    }
    flush()
    return out
}

/**
 * 找闭合标记：要求内容非空、闭合符前一字符非空白（`* a *` 不成强调）。
 * 返回闭合标记的起始下标；找不到返回 -1。
 */
private fun findClose(text: String, from: Int, marker: String): Int {
    var idx = text.indexOf(marker, from)
    while (idx > 0) {
        if (idx > from && !text[idx - 1].isWhitespace()) return idx
        idx = text.indexOf(marker, idx + marker.length)
    }
    return -1
}

/**
 * 解析 `[文字](目标)` / `![alt](目标)`。
 * [open] 指向 `[` 或 `!` 后紧邻的 `[`；返回（括号内文字, 目标, 下一个下标）；结构不合法返回 null。
 */
private fun parseBracket(text: String, open: Int): Triple<String, String, Int>? {
    if (open >= text.length || text[open] != '[') return null
    val closeBracket = text.indexOf(']', open + 1)
    if (closeBracket < 0 || closeBracket + 1 >= text.length || text[closeBracket + 1] != '(') return null
    val closeParen = text.indexOf(')', closeBracket + 2)
    if (closeParen < 0) return null
    val label = text.substring(open + 1, closeBracket)
    val target = text.substring(closeBracket + 2, closeParen).trim()
    if (target.isEmpty()) return null
    return Triple(label, target, closeParen + 1)
}

// ---------------------------------------------------------------------------
// 摘要与纯文本
// ---------------------------------------------------------------------------

/** 列表项 / 段落的纯文本（摘要与「正文首行」用）：去掉标记、公式、图片，压平空白。 */
fun plainTextOf(inlines: List<MdInline>): String =
    inlines.joinToString("") { inline ->
        when (inline) {
            is MdInline.Text -> inline.text
            is MdInline.Code -> inline.text
            is MdInline.Styled -> plainTextOf(inline.content)
            is MdInline.Link -> plainTextOf(inline.content)
            is MdInline.Math -> ""     // 公式不进摘要
            is MdInline.Image -> ""    // 图片不进摘要（列表用角标表达）
        }
    }

/**
 * 列表摘要（笔记列表项的第二行）：取正文第一段可用文字，压平空白后截到 [max] 字符。
 * 代码块/公式块不进摘要；一个字的正文也没有时返回空串（UI 侧不显示这一行）。
 */
fun plainExcerpt(body: String, max: Int = 80): String {
    val blocks = parseMarkdown(body)
    val builder = StringBuilder()
    // 标题只作兜底：笔记另有 title 字段，正文首个标题通常与它重复，不该占掉摘要
    var headingFallback = ""
    for (block in blocks) {
        if (block is MdBlock.Heading) {
            if (headingFallback.isEmpty()) headingFallback = plainTextOf(block.content)
            continue
        }
        val piece = when (block) {
            is MdBlock.Paragraph -> plainTextOf(block.content)
            is MdBlock.ListBlock -> block.items.joinToString(" ") { plainTextOf(it.content) }
            is MdBlock.Quote -> block.blocks.joinToString(" ") { b ->
                when (b) {
                    is MdBlock.Paragraph -> plainTextOf(b.content)
                    is MdBlock.Heading -> plainTextOf(b.content)
                    else -> ""
                }
            }
            else -> ""
        }
        if (piece.isNotBlank()) {
            if (builder.isNotEmpty()) builder.append(' ')
            builder.append(piece)
        }
        if (builder.length >= max) break
    }
    val source = if (builder.isBlank()) headingFallback else builder.toString()
    val flat = source.replace(Regex("\\s+"), " ").trim()
    return if (flat.length <= max) flat else flat.take(max - 1).trimEnd() + "…"
}

// ---------------------------------------------------------------------------
// 段落切分：独占一行的图片按块级渲染
// ---------------------------------------------------------------------------

/** [splitParagraph] 的切分结果（渲染层按类型分别处理）。 */
sealed interface MdParagraphPart {
    /** 独占一行的图片（[ref] 与 [MdInline.Image.ref] 同义：`img:文件名` 或外链）。 */
    data class BlockImage(val ref: String) : MdParagraphPart

    /** 行内段：与文字混排的图片/链接/公式都留在这里。 */
    data class Inline(val content: List<MdInline>) : MdParagraphPart
}

/**
 * 把段落内容切成「块级图片」与「行内段」（DESIGN §4.20「渲染子集」）。
 *
 * 判据 = 图片是不是**所在行的唯一实际内容**（同行其余节点都是空白文本）。
 * 为什么不靠解析器分段：单换行不构成新段落（对齐 Obsidian），
 * 于是「一次插两张图」或「图片紧跟文字后面」在 AST 里是同一个 Paragraph——
 * 渲染层不切开就会退化成主色下划线的「[图片]」文字标签。
 */
fun splitParagraph(content: List<MdInline>): List<MdParagraphPart> {
    val parts = mutableListOf<MdParagraphPart>()
    var pending = mutableListOf<MdInline>()

    fun flush() {
        while (pending.isNotEmpty() && pending.first().isBlankText()) pending.removeAt(0)
        while (pending.isNotEmpty() && pending.last().isBlankText()) pending.removeAt(pending.size - 1)
        if (pending.isNotEmpty()) parts += MdParagraphPart.Inline(pending.toList())
        pending = mutableListOf()
    }

    paragraphLines(content).forEach { line ->
        val image = line.singleImageOrNull()
        if (image != null) {
            flush()
            parts += MdParagraphPart.BlockImage(image.ref)
        } else {
            if (pending.isNotEmpty()) pending += MdInline.Text("\n")
            pending += line
        }
    }
    flush()
    return parts
}

/** 段落内的文本节点按 `\n` 拆行，其余节点落在当前行。 */
private fun paragraphLines(content: List<MdInline>): List<List<MdInline>> {
    val lines = mutableListOf<MutableList<MdInline>>()
    var current = mutableListOf<MdInline>().also { lines += it }
    content.forEach { node ->
        if (node is MdInline.Text && node.text.contains('\n')) {
            node.text.split('\n').forEachIndexed { index, segment ->
                if (index > 0) {
                    current = mutableListOf<MdInline>().also { lines += it }
                }
                if (segment.isNotEmpty()) current += MdInline.Text(segment)
            }
        } else {
            current += node
        }
    }
    return lines
}

/** 行内只有一张图片（其余全是空白文本）→ 返回它；否则 null。 */
private fun List<MdInline>.singleImageOrNull(): MdInline.Image? {
    var image: MdInline.Image? = null
    for (node in this) {
        when {
            node is MdInline.Image && image == null -> image = node
            node.isBlankText() -> Unit
            else -> return null
        }
    }
    return image
}

private fun MdInline.isBlankText(): Boolean = this is MdInline.Text && text.isBlank()
