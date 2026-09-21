package edu.jxslu.schedule.domain

import kotlin.math.max
import kotlin.math.min

/**
 * LaTeX 子集的解析与排版（DESIGN §4.20）。**纯 JVM**——度量器是注入的 [MathTextMeasurer]，
 * 单测用假度量器钉死几何（`MathTexTest`），Compose 侧只在 `ui/common/MathText.kt` 里提供
 * 真度量器并把 [MathBox] 画出来。
 *
 * 两条纪律：
 * 1. **支持清单之外的一切 → [parseTex] 返回 null**，调用方回退为等宽源码展示；
 *    绝不"渲染一半"（半截公式比看得见的 TeX 源码更误导）。
 * 2. 坐标口径：**基线为 y=0，向上为负**（[MathOp] 的 y 都是"相对基线的偏移"），
 *    [MathBox.width] 从 0 起向右。尺寸一律 em（1em = 调用方传入的字号），
 *    layout 只做几何，不碰像素与字体。
 */

// ---------------------------------------------------------------------------
// 节点
// ---------------------------------------------------------------------------

sealed interface MathNode {
    /** 顺序排列（分组、整体公式都归它）。 */
    data class Row(val items: List<MathNode>) : MathNode

    /** 普通符号：[italic] 变量斜体（字母），正体用于数字/算子/函数名。 */
    data class Sym(val text: String, val italic: Boolean = false) : MathNode

    /** `\text{…}` 正体文字（可含中文）。 */
    data class TextRun(val text: String) : MathNode

    data class Frac(val numerator: MathNode, val denominator: MathNode) : MathNode

    data class Sqrt(val index: MathNode?, val body: MathNode) : MathNode

    /** 上下标（可只有一边，可嵌套）。 */
    data class Script(val base: MathNode, val sub: MathNode? = null, val sup: MathNode? = null) : MathNode

    /**
     * 大字算符。[limitsInDisplay] = true 时 display 模式上下限放正上下（∑/∏/lim 一族），
     * false 恒挂右侧（∫ 一族，数学惯例）。
     */
    data class BigOp(
        val symbol: String,
        val limitsInDisplay: Boolean,
        val sub: MathNode? = null,
        val sup: MathNode? = null,
    ) : MathNode

    /** `\left…\right` 定界符（尺寸随内容长，见 [layoutMath] 的 delimiters）。 */
    data class Delim(val left: String, val body: MathNode, val right: String) : MathNode

    /** 上方的装饰符（\vec \bar \hat \tilde \dot \ddot），[glyph] 是近似字形。 */
    data class Accent(val glyph: String, val body: MathNode) : MathNode

    /** \overline / \underline。 */
    data class Rule(val body: MathNode, val above: Boolean) : MathNode

    /** 显式间距（\, \; \quad 等），单位 em。 */
    data class Spaced(val widthEm: Float) : MathNode
}

// ---------------------------------------------------------------------------
// 解析
// ---------------------------------------------------------------------------

private val GREEK = mapOf(
    "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε",
    "varepsilon" to "ε", "zeta" to "ζ", "eta" to "η", "theta" to "θ", "vartheta" to "ϑ",
    "iota" to "ι", "kappa" to "κ", "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ",
    "pi" to "π", "varpi" to "ϖ", "rho" to "ρ", "sigma" to "σ", "varsigma" to "ς",
    "tau" to "τ", "upsilon" to "υ", "phi" to "φ", "varphi" to "φ", "chi" to "χ",
    "psi" to "ψ", "omega" to "ω",
    "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ", "Xi" to "Ξ",
    "Pi" to "Π", "Sigma" to "Σ", "Upsilon" to "Υ", "Phi" to "Φ", "Psi" to "Ψ",
    "Omega" to "Ω",
)

private val OPERATORS = mapOf(
    "times" to "×", "div" to "÷", "pm" to "±", "mp" to "∓", "cdot" to "⋅", "ast" to "∗",
    "star" to "⋆", "circ" to "∘", "bullet" to "∙", "oplus" to "⊕", "ominus" to "⊖",
    "otimes" to "⊗", "oslash" to "⊘", "odot" to "⊙", "leq" to "≤", "le" to "≤",
    "geq" to "≥", "ge" to "≥", "neq" to "≠", "ne" to "≠", "equiv" to "≡", "approx" to "≈",
    "sim" to "∼", "simeq" to "≃", "cong" to "≅", "propto" to "∝", "ll" to "≪", "gg" to "≫",
    "perp" to "⊥", "parallel" to "∥", "mid" to "∣", "subset" to "⊂", "subseteq" to "⊆",
    "supset" to "⊃", "supseteq" to "⊇", "in" to "∈", "notin" to "∉", "ni" to "∋",
    "cup" to "∪", "cap" to "∩", "setminus" to "∖", "emptyset" to "∅", "varnothing" to "∅",
    "forall" to "∀", "exists" to "∃", "nexists" to "∄", "neg" to "¬", "lnot" to "¬",
    "land" to "∧", "wedge" to "∧", "lor" to "∨", "vee" to "∨",
    "to" to "→", "rightarrow" to "→", "leftarrow" to "←", "leftrightarrow" to "↔",
    "Rightarrow" to "⇒", "Leftarrow" to "⇐", "Leftrightarrow" to "⇔", "mapsto" to "↦",
    "uparrow" to "↑", "downarrow" to "↓", "implies" to "⟹", "iff" to "⟺",
    "infty" to "∞", "partial" to "∂", "nabla" to "∇", "angle" to "∠", "degree" to "°",
    "prime" to "′", "ldots" to "…", "dots" to "…", "cdots" to "⋯", "vdots" to "⋮",
    "ddots" to "⋱", "square" to "□", "blacksquare" to "■", "triangle" to "△",
    "checkmark" to "✓", "dagger" to "†", "ell" to "ℓ", "hbar" to "ℏ", "Re" to "ℜ", "Im" to "ℑ",
)

/** 函数名（正体 + 后面点一个细空格）。 */
private val FUNCTIONS = setOf(
    "sin", "cos", "tan", "cot", "sec", "csc", "arcsin", "arccos", "arctan",
    "sinh", "cosh", "tanh", "coth", "ln", "log", "lg", "exp", "det", "dim",
    "gcd", "deg", "arg", "ker", "hom", "min", "max", "sup", "inf", "lim",
    "limsup", "liminf", "bmod",
)

/** 大字算符：[limitsInDisplay] 决定 display 模式下上下限位置。 */
private val BIG_OPS = mapOf(
    "sum" to Pair("∑", true), "prod" to Pair("∏", true), "coprod" to Pair("∐", true),
    "bigcup" to Pair("⋃", true), "bigcap" to Pair("⋂", true), "bigoplus" to Pair("⨁", true),
    "bigotimes" to Pair("⨂", true), "bigvee" to Pair("⋁", true), "bigwedge" to Pair("⋀", true),
    "int" to Pair("∫", false), "iint" to Pair("∬", false), "iiint" to Pair("∭", false),
    "oint" to Pair("∮", false),
)

private val ACCENTS = mapOf(
    "vec" to "→", "bar" to "¯", "hat" to "ˆ", "widehat" to "ˆ", "tilde" to "˜",
    "widetilde" to "˜", "dot" to "·", "ddot" to "··", "check" to "ˇ", "breve" to "˘",
)

private val SPACES = mapOf(
    "," to 0.167f, ":" to 0.222f, ";" to 0.278f, "!" to -0.167f,
    "quad" to 1f, "qquad" to 2f, " " to 0.25f,
)

private val BLACKBOARD = mapOf(
    'R' to "ℝ", 'N' to "ℕ", 'Z' to "ℤ", 'Q' to "ℚ", 'C' to "ℂ", 'P' to "ℙ", 'E' to "𝔼",
)

private class TexParser(private val src: String) {
    private var pos = 0
    /** 出现任何不认识的结构就置位，最终返回 null（调用方回退源码展示）。 */
    var failed = false
        private set

    fun parse(): MathNode? {
        val node = parseSequence(stopAtBrace = false)
        if (failed || pos < src.length) return null
        return node
    }

    /** 顺序解析到 `}` / `\right` / 文末。 */
    private fun parseSequence(stopAtBrace: Boolean): MathNode {
        val items = mutableListOf<MathNode>()
        while (pos < src.length) {
            val c = src[pos]
            if (c == '}') {
                if (stopAtBrace) break
                fail(); break
            }
            if (stopAtBrace.not() && startsWith("\\right")) break
            if (c == '&') {
                fail(); break
            }
            val atom = parseAtom() ?: break
            items += attachScripts(atom)
        }
        return if (items.size == 1) items[0] else MathNode.Row(items)
    }

    /** 解析一个原子，随后由 [attachScripts] 吃 `^` / `_`。 */
    private fun parseAtom(): MathNode? {
        if (pos >= src.length) return null
        val c = src[pos]
        return when {
            c == '{' -> {
                pos++
                val inner = parseSequence(stopAtBrace = true)
                expect('}')
                inner
            }
            c == '\\' -> parseCommand()
            c.isDigit() -> {
                val start = pos
                while (pos < src.length && (src[pos].isDigit() || src[pos] == '.')) pos++
                MathNode.Sym(src.substring(start, pos))
            }
            c.isLetter() -> {
                val start = pos
                while (pos < src.length && src[pos].isLetter()) pos++
                val word = src.substring(start, pos)
                // 多字母按单个变量渲染（x y → 斜体 xy 连排），与手写笔记观感一致
                MathNode.Sym(word, italic = true)
            }
            else -> {
                pos++
                MathNode.Sym(c.toString())
            }
        }
    }

    private fun parseCommand(): MathNode? {
        pos++ // 吃掉 '\'
        if (pos >= src.length) {
            fail(); return null
        }
        val c = src[pos]
        // 转义字符：\{ \} \% \& \# \_ \$ \,（空格类单独处理）
        if (!c.isLetter()) {
            pos++
            return when (c) {
                ',', ':', ';', '!' -> MathNode.Spaced(SPACES[c.toString()] ?: 0f)
                ' ' -> MathNode.Spaced(SPACES[" "] ?: 0.25f)
                else -> MathNode.Sym(c.toString())
            }
        }
        val start = pos
        while (pos < src.length && src[pos].isLetter()) pos++
        val name = src.substring(start, pos)
        return when {
            name == "frac" || name == "dfrac" || name == "tfrac" ->
                MathNode.Frac(parseRequiredGroup() ?: return null, parseRequiredGroup() ?: return null)

            name == "sqrt" -> {
                val index = if (peek() == '[') parseOptionalBracket() else null
                MathNode.Sqrt(index, parseRequiredGroup() ?: return null)
            }

            name == "left" -> parseDelimited()

            name == "right" -> {
                fail(); null
            }

            name in GREEK -> MathNode.Sym(GREEK.getValue(name), italic = name.first().isLowerCase())

            name in OPERATORS -> MathNode.Sym(OPERATORS.getValue(name))

            name in FUNCTIONS -> MathNode.Row(
                listOf(MathNode.Sym(name), MathNode.Spaced(0.17f)),
            )

            name in BIG_OPS -> {
                val (glyph, limits) = BIG_OPS.getValue(name)
                MathNode.BigOp(glyph, limitsInDisplay = limits)
            }

            name in ACCENTS -> MathNode.Accent(ACCENTS.getValue(name), parseRequiredGroup() ?: return null)

            name == "overline" -> MathNode.Rule(parseRequiredGroup() ?: return null, above = true)
            name == "underline" -> MathNode.Rule(parseRequiredGroup() ?: return null, above = false)

            name == "text" || name == "mathrm" || name == "operatorname" ->
                MathNode.TextRun(parseRawGroup() ?: return null)

            name == "mathbb" -> parseBlackboard() ?: return null

            name in SPACES -> MathNode.Spaced(SPACES.getValue(name))

            else -> {
                fail(); null
            }
        }
    }

    /** `\left(x…\right)` 定界符族。 */
    private fun parseDelimited(): MathNode? {
        val left = parseDelimiterChar() ?: return null
        val body = parseSequence(stopAtBrace = false)
        if (!consume("\\right")) {
            fail(); return null
        }
        val right = parseDelimiterChar() ?: return null
        return MathNode.Delim(left, body, right)
    }

    private fun parseDelimiterChar(): String? {
        skipSpaces()
        if (pos >= src.length) return null
        return if (src[pos] == '\\') {
            pos++
            val start = pos
            while (pos < src.length && src[pos].isLetter()) pos++
            val name = src.substring(start, pos)
            when (name) {
                "{" -> "{"
                "}" -> "}"
                "|" -> "‖"
                "langle" -> "⟨"
                "rangle" -> "⟩"
                "lvert" -> "|"
                "rvert" -> "|"
                "lfloor" -> "⌊"
                "rfloor" -> "⌋"
                "lceil" -> "⌈"
                "rceil" -> "⌉"
                else -> null
            }
        } else {
            val ch = src[pos]
            if (ch in "()[]|/." || ch == '<' || ch == '>') {
                pos++
                when (ch) {
                    '<' -> "⟨"
                    '>' -> "⟩"
                    else -> ch.toString()
                }
            } else {
                null
            }
        }
    }

    /** `{…}` 必需组；缺失即失败。 */
    private fun parseRequiredGroup(): MathNode? {
        skipSpaces()
        if (peek() != '{') {
            // 允许 `\frac12` 形态：单字符即一组
            return parseAtom()?.let { attachScripts(it) }
        }
        pos++
        val inner = parseSequence(stopAtBrace = true)
        expect('}')
        return inner
    }

    /** `[…]` 可选组（如 `\sqrt[n]{}` 的次数）：先按括号配对切子串再递归解析。 */
    private fun parseOptionalBracket(): MathNode? {
        expect('[')
        val start = pos
        var depth = 0
        while (pos < src.length) {
            when (src[pos]) {
                '{' -> depth++
                '}' -> depth--
                ']' -> if (depth == 0) break
            }
            pos++
        }
        if (pos >= src.length) {
            fail()
            return null
        }
        val raw = src.substring(start, pos)
        pos++ // 跳过 ']'
        val inner = parseTex(raw)
        if (inner == null) fail()
        return inner
    }

    /** `\text{…}` 的原始内容（不解析内部语法，中英文原样）。 */
    private fun parseRawGroup(): String? {
        skipSpaces()
        if (peek() != '{') return null
        pos++
        val start = pos
        var depth = 1
        while (pos < src.length && depth > 0) {
            when (src[pos]) {
                '{' -> depth++
                '}' -> depth--
            }
            if (depth > 0) pos++
        }
        if (depth != 0) {
            fail(); return null
        }
        val raw = src.substring(start, pos)
        pos++
        return raw
    }

    private fun parseBlackboard(): MathNode? {
        val raw = parseRawGroup() ?: return null
        val mapped = raw.map { BLACKBOARD[it] ?: it.toString() }.joinToString("")
        return MathNode.Sym(mapped)
    }

    /** 把 `^` / `_` 挂到刚解析出的原子上（可两者都有，任意顺序）。 */
    private fun attachScripts(base: MathNode): MathNode {
        var current = base
        var sub: MathNode? = null
        var sup: MathNode? = null
        while (true) {
            skipSpaces()
            val marker = peek()
            if (marker != '^' && marker != '_') break
            pos++
            val arg = parseRequiredGroup() ?: break
            if (marker == '^') sup = arg else sub = arg
        }
        current = when {
            current is MathNode.BigOp && (sub != null || sup != null) ->
                current.copy(sub = sub, sup = sup)
            sub != null || sup != null -> MathNode.Script(current, sub, sup)
            else -> current
        }
        return current
    }

    private fun peek(): Char? = if (pos < src.length) src[pos] else null

    private fun skipSpaces() {
        while (pos < src.length && src[pos] == ' ') pos++
    }

    private fun expect(ch: Char) {
        if (peek() == ch) {
            pos++
        } else {
            fail()
        }
    }

    private fun consume(token: String): Boolean {
        skipSpaces()
        return if (src.startsWith(token, pos)) {
            pos += token.length
            true
        } else {
            false
        }
    }

    private fun startsWith(token: String): Boolean = src.startsWith(token, pos)

    private fun fail() {
        failed = true
    }
}

/**
 * 解析 TeX 子集；**失败返回 null**（调用方展示等宽源码）。
 */
fun parseTex(src: String): MathNode? {
    val trimmed = src.trim()
    if (trimmed.isEmpty()) return null
    return TexParser(trimmed).parse()
}

// ---------------------------------------------------------------------------
// 排版
// ---------------------------------------------------------------------------

/** 文本宽度度量（em 单位）：Compose 侧用 TextMeasurer 实现，单测用假实现。 */
fun interface MathTextMeasurer {
    fun width(text: String, sizeEm: Float): Float
}

/** 绘制指令（相对基线：y=0 是基线，向上为负）。 */
sealed interface MathOp {
    data class Text(
        val text: String,
        val x: Float,
        val baselineY: Float,
        val sizeEm: Float,
        val italic: Boolean,
    ) : MathOp

    data class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val thickness: Float) : MathOp
}

/** 平移复制（父容器摆放子盒子时用）。 */
internal fun MathOp.shifted(dx: Float, dy: Float): MathOp = when (this) {
    is MathOp.Text -> copy(x = x + dx, baselineY = baselineY + dy)
    is MathOp.Line -> copy(x1 = x1 + dx, x2 = x2 + dx, y1 = y1 + dy, y2 = y2 + dy)
}

/** 排版结果：宽 + 基线上下高度 + 绘制指令（em 单位，相对自身基线）。 */
data class MathBox(
    val width: Float,
    val ascent: Float,
    val descent: Float,
    val ops: List<MathOp>,
) {
    val height: Float get() = ascent + descent
}

private const val TEXT_ASCENT = 0.74f
private const val TEXT_DESCENT = 0.22f
private const val SCRIPT_SCALE = 0.7f
private const val SCRIPT_MIN_SCALE = 0.55f
private const val FRAC_SCALE = 0.85f
private const val AXIS = 0.26f
private const val RULE_THICKNESS = 0.06f
private const val FRAC_GAP = 0.14f
private const val SUP_RAISE = 0.42f
private const val SUB_DROP = 0.16f

/** 累积指令并跟踪包围盒（相对基线：向上为负，故 minY 记最小 y）。 */
private class BoxBuilder {
    private val ops = mutableListOf<MathOp>()
    private var minY = 0f
    private var maxY = 0f
    private var width = 0f

    fun add(box: MathBox, dx: Float = 0f, dy: Float = 0f): BoxBuilder {
        box.ops.forEach { ops += it.shifted(dx, dy) }
        if (box.width > 0f || box.ops.isNotEmpty()) {
            minY = min(minY, dy - box.ascent)
            maxY = max(maxY, dy + box.descent)
            width = max(width, dx + box.width)
        }
        return this
    }

    fun addRaw(op: MathOp, top: Float, bottom: Float, right: Float): BoxBuilder {
        ops += op
        minY = min(minY, top)
        maxY = max(maxY, bottom)
        width = max(width, right)
        return this
    }

    fun advanceWidth(value: Float): BoxBuilder {
        width = max(width, value)
        return this
    }

    fun text(text: String, x: Float, baselineY: Float, sizeEm: Float, italic: Boolean, measure: MathTextMeasurer) =
        addRaw(
            op = MathOp.Text(text, x, baselineY, sizeEm, italic),
            top = baselineY - TEXT_ASCENT * sizeEm,
            bottom = baselineY + TEXT_DESCENT * sizeEm,
            right = x + measure.width(text, sizeEm),
        )

    fun segment(x1: Float, y1: Float, x2: Float, y2: Float, thickness: Float) = addRaw(
        op = MathOp.Line(x1, y1, x2, y2, thickness),
        top = minOf(y1, y2) - thickness / 2,
        bottom = maxOf(y1, y2) + thickness / 2,
        right = maxOf(x1, x2),
    )

    fun build(): MathBox = MathBox(width, -minY, maxY, ops)
}

/**
 * 排版：[sizeEm] 是基准字号（em），[display] 为 true 时大字算符用正上下限、分数线更舒展。
 */
fun layoutMath(
    node: MathNode,
    sizeEm: Float,
    display: Boolean,
    measure: MathTextMeasurer,
): MathBox = layout(node, sizeEm.coerceAtLeast(0.1f), display, measure)

private fun layout(
    node: MathNode,
    scale: Float,
    display: Boolean,
    measure: MathTextMeasurer,
): MathBox = when (node) {
    is MathNode.Row -> {
        val builder = BoxBuilder()
        var x = 0f
        node.items.forEach { child ->
            val box = layout(child, scale, display, measure)
            builder.add(box, dx = x)
            x += box.width
        }
        builder.build()
    }

    is MathNode.Sym -> {
        val builder = BoxBuilder()
        builder.text(node.text, 0f, 0f, scale, node.italic, measure)
        builder.build()
    }

    is MathNode.TextRun -> {
        val builder = BoxBuilder()
        builder.text(node.text, 0f, 0f, scale, italic = false, measure)
        builder.build()
    }

    is MathNode.Spaced -> MathBox(node.widthEm * scale, 0f, 0f, emptyList())

    is MathNode.Frac -> {
        val inner = scale * FRAC_SCALE
        val num = layout(node.numerator, inner, display, measure)
        val den = layout(node.denominator, inner, display, measure)
        val pad = 0.12f * scale
        val width = max(num.width, den.width) + 2 * pad
        val builder = BoxBuilder()
        // 分数线落在数学轴高度上
        builder.segment(pad, -AXIS * scale, width - pad, -AXIS * scale, RULE_THICKNESS * scale)
        builder.add(num, dx = (width - num.width) / 2, dy = -AXIS * scale - FRAC_GAP * scale - num.descent)
        builder.add(den, dx = (width - den.width) / 2, dy = -AXIS * scale + FRAC_GAP * scale + den.ascent)
        builder.build()
    }

    is MathNode.Sqrt -> {
        val body = layout(node.body, scale, display, measure)
        val index = node.index?.let { layout(it, scale * 0.6f, display, measure) }
        val radicalWidth = 0.45f * scale
        val indexWidth = index?.width ?: 0f
        val overhang = 0.08f * scale
        val thickness = 0.05f * scale
        // 根号：起笔的短横 → 斜下到尖 → 斜上到顶 → 顶部横线盖住被开方式
        val x0 = indexWidth * 0.7f
        val topY = -body.ascent - 0.14f * scale
        val bottomY = 0.06f * scale
        val builder = BoxBuilder()
        builder.segment(x0, topY + 0.22f * scale, x0 + radicalWidth * 0.3f, topY + 0.32f * scale, thickness)
        builder.segment(x0 + radicalWidth * 0.3f, topY + 0.32f * scale, x0 + radicalWidth * 0.6f, bottomY, thickness)
        builder.segment(x0 + radicalWidth * 0.6f, bottomY, x0 + radicalWidth, topY, thickness)
        val bodyX = x0 + radicalWidth + overhang
        builder.add(body, dx = bodyX)
        builder.segment(x0 + radicalWidth, topY, bodyX + body.width, topY, thickness)
        if (index != null) {
            builder.add(index, dx = 0f, dy = topY - index.descent * 0.4f + 0.02f * scale)
        }
        builder.build()
    }

    is MathNode.Script -> {
        val base = layout(node.base, scale, display, measure)
        val scriptScale = (scale * SCRIPT_SCALE).coerceAtLeast(SCRIPT_MIN_SCALE)
        val sub = node.sub?.let { layout(it, scriptScale, display, measure) }
        val sup = node.sup?.let { layout(it, scriptScale, display, measure) }
        val builder = BoxBuilder()
        builder.add(base)
        val x = base.width + 0.03f * scale
        sup?.let { builder.add(it, dx = x, dy = -SUP_RAISE * scale - it.height * 0.35f) }
        sub?.let { builder.add(it, dx = x, dy = SUB_DROP * scale + it.ascent * 0.7f) }
        builder.build()
    }

    is MathNode.BigOp -> {
        val symbolScale = if (display) scale * 1.5f else scale * 1.1f
        val symbol = buildSymbolWidth(node.symbol, symbolScale, measure)
        val builder = BoxBuilder()
        val stacked = display && node.limitsInDisplay && (node.sub != null || node.sup != null)
        if (stacked) {
            val limitScale = (scale * 0.55f).coerceAtLeast(SCRIPT_MIN_SCALE)
            val sup = node.sup?.let { layout(it, limitScale, display, measure) }
            val sub = node.sub?.let { layout(it, limitScale, display, measure) }
            val width = maxOf(symbol, sup?.width ?: 0f, sub?.width ?: 0f)
            sup?.let {
                // 上限坐在算符顶上
                val dy = -(TEXT_ASCENT * symbolScale + 0.10f * scale) - it.descent
                builder.add(it, dx = (width - it.width) / 2, dy = dy)
            }
            builder.text(node.symbol, (width - symbol) / 2, 0f, symbolScale, italic = false, measure)
            sub?.let {
                // 下限挂在算符底下
                val dy = TEXT_DESCENT * symbolScale + 0.14f * scale + it.ascent
                builder.add(it, dx = (width - it.width) / 2, dy = dy)
            }
            builder.advanceWidth(width)
            builder.build()
        } else {
            builder.text(node.symbol, 0f, 0f, symbolScale, italic = false, measure)
            val x = symbol + 0.02f * scale
            val scriptScale = (scale * SCRIPT_SCALE).coerceAtLeast(SCRIPT_MIN_SCALE)
            node.sup?.let {
                val box = layout(it, scriptScale, display, measure)
                builder.add(box, dx = x, dy = -SUP_RAISE * scale - box.height * 0.35f)
            }
            node.sub?.let {
                val box = layout(it, scriptScale, display, measure)
                builder.add(box, dx = x, dy = SUB_DROP * scale + box.ascent * 0.7f)
            }
            builder.build()
        }
    }

    is MathNode.Delim -> {
        val body = layout(node.body, scale, display, measure)
        val bodyHeight = body.height
        val delimiterScale = (scale * max(1f, bodyHeight / (TEXT_ASCENT * scale * 1.4f))).coerceAtMost(scale * 1.8f)
        val builder = BoxBuilder()
        val leftWidth = buildSymbolWidth(node.left, delimiterScale, measure)
        builder.text(node.left, 0f, -body.ascent * 0.35f, delimiterScale, italic = false, measure)
        builder.add(body, dx = leftWidth + 0.06f * scale)
        val rightX = leftWidth + 0.06f * scale + body.width + 0.06f * scale
        builder.text(node.right, rightX, -body.ascent * 0.35f, delimiterScale, italic = false, measure)
        builder.advanceWidth(rightX + buildSymbolWidth(node.right, delimiterScale, measure))
        builder.build()
    }

    is MathNode.Accent -> {
        val body = layout(node.body, scale, display, measure)
        val builder = BoxBuilder()
        builder.add(body)
        val glyphScale = scale * 0.55f
        val glyphWidth = buildSymbolWidth(node.glyph, glyphScale, measure)
        val top = -body.ascent - 0.12f * scale
        builder.text(node.glyph, (body.width - glyphWidth) / 2, top, glyphScale, italic = false, measure)
        builder.build()
    }

    is MathNode.Rule -> {
        val body = layout(node.body, scale, display, measure)
        val builder = BoxBuilder()
        builder.add(body)
        val y = if (node.above) -body.ascent - 0.1f * scale else body.descent + 0.1f * scale
        builder.segment(0f, y, body.width, y, RULE_THICKNESS * scale)
        builder.build()
    }
}

/** 符号宽度（大字算符/定界符在放大字号下测量）。 */
private fun buildSymbolWidth(text: String, sizeEm: Float, measure: MathTextMeasurer): Float =
    measure.width(text, sizeEm)
