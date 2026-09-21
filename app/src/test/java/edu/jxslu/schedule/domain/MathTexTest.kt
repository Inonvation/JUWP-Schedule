package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max

/**
 * LaTeX 子集的解析与排版（DESIGN §4.20）。
 *
 * 两种失败都要防：**该渲的不渲**（支持清单里的语法解析失败 → 用户看到源码）与
 * **不该渲的硬渲**（未知命令被猜着画出来 → 误导）。度量器用假实现，几何按 em 断言。
 */
class MathTexTest {

    /** 假度量器：等宽 0.5em（可预测的几何，便于断言）。 */
    private val measure = MathTextMeasurer { text, sizeEm -> text.length * 0.5f * sizeEm }

    private fun layout(src: String, display: Boolean = false): MathBox {
        val node = parseTex(src)
        assertNotNull("解析失败：$src", node)
        return layoutMath(node!!, sizeEm = 1f, display = display, measure = measure)
    }

    // ---- 解析：支持清单 ----

    @Test
    fun parse_greekAndOperators() {
        val cases = listOf(
            "\\alpha + \\beta \\leq \\Omega",
            "\\sum_{i=1}^{n} x_i",
            "\\int_0^1 f(x)\\,dx",
            "\\frac{\\partial f}{\\partial x}",
            "\\sqrt[n]{a^2 + b^2}",
            "\\lim_{x \\to 0} \\frac{\\sin x}{x}",
            "\\left( \\frac{a}{b} \\right)",
            "\\vec{v} = \\frac{d\\vec{r}}{dt}",
            "\\text{当且仅当} x \\geq 0",
            "\\mathbb{R}^n",
            "\\overline{AB} \\perp \\underline{CD}",
        )
        val failed = cases.filter { parseTex(it) == null }
        assertEquals("以下样例解析失败", emptyList<String>(), failed)
    }
    @Test
    fun parse_failureReturnsNull() {
        assertNull("未知命令必须回退", parseTex("\\begin{matrix} a & b \\end{matrix}"))
        assertNull("括号不配必须回退", parseTex("\\frac{a}{b"))
        assertNull(parseTex("\\unknowncmd{x}"))
        assertNull(parseTex("a & b"))
        assertNull(parseTex(""))
    }

    @Test
    fun parse_nestedScripts() {
        val node = parseTex("x^{a^{b}}")!!
        val script = node as MathNode.Script
        assertEquals("上标里还能有上标", true, script.sup is MathNode.Script)
    }

    // ---- 排版几何 ----

    @Test
    fun layout_symbolSizeAndBaseline() {
        val box = layout("x")
        assertEquals(0.5f, box.width, 1e-3f)
        assertTrue("有基线以上高度", box.ascent > 0f)
        assertTrue("有基线以下高度", box.descent > 0f)
    }

    @Test
    fun layout_fracIsStackedAndNarrowerThanBody() {
        val box = layout("\\frac{a}{b}")
        // 分子分母各 0.5em * 0.85，加两侧 pad：宽度应大于单个符号
        assertTrue(box.width > 0.5f)
        // 分数整体比单行高
        val single = layout("a")
        assertTrue("分数应比单符号高", box.height > single.height)
        // 有一条分数线
        assertTrue(box.ops.any { it is MathOp.Line })
    }

    @Test
    fun layout_sqrtDrawsRuleAndOverline() {
        val box = layout("\\sqrt{x}")
        val lines = box.ops.filterIsInstance<MathOp.Line>()
        assertTrue("根号至少 4 段线", lines.size >= 4)
    }

    @Test
    fun layout_scriptRaisesSupAndDropsSub() {
        val box = layout("x^2")
        val texts = box.ops.filterIsInstance<MathOp.Text>()
        val sup = texts.first { it.text == "2" }
        assertEquals("上标字号 0.7", 0.7f, sup.sizeEm, 1e-3f)
        assertTrue("上标在基线以上", sup.baselineY < 0f)

        val sub = layout("x_1").ops.filterIsInstance<MathOp.Text>().first { it.text == "1" }
        assertTrue("下标在基线以下", sub.baselineY > 0f)
    }

    @Test
    fun layout_bigOpLimitsStackOnlyInDisplay() {
        val display = layout("\\sum_{i=1}^{n}", display = true)
        val inline = layout("\\sum_{i=1}^{n}", display = false)
        val displayLimits = display.ops.filterIsInstance<MathOp.Text>().filter { it.text == "n" || it.text == "i" }
        // display：上下限居中叠放 → x 位置接近算符中心（宽度一半附近）
        val symbol = display.ops.filterIsInstance<MathOp.Text>().first { it.text == "∑" }
        assertTrue(displayLimits.all { it.x < symbol.x + symbol.sizeEm })
        // inline：上下限挂在右侧
        val inlineLimits = inline.ops.filterIsInstance<MathOp.Text>().filter { it.text == "n" || it.text == "i" }
        val inlineSymbol = inline.ops.filterIsInstance<MathOp.Text>().first { it.text == "∑" }
        assertTrue(inlineLimits.all { it.x > inlineSymbol.x })
        assertTrue("display 模式更高", display.height > inline.height)
    }

    @Test
    fun layout_integralLimitsStayOnTheSide() {
        val box = layout("\\int_0^1", display = true)
        val symbol = box.ops.filterIsInstance<MathOp.Text>().first { it.text == "∫" }
        val limit = box.ops.filterIsInstance<MathOp.Text>().first { it.text == "1" }
        assertTrue("积分上下限恒挂右侧", limit.x > symbol.x)
    }

    @Test
    fun layout_delimiterGrowsWithBody() {
        val small = layout("\\left(a\\right)")
        val tall = layout("\\left(\\frac{a}{b}\\right)")
        val smallParen = small.ops.filterIsInstance<MathOp.Text>().first { it.text == "(" }
        val tallParen = tall.ops.filterIsInstance<MathOp.Text>().first { it.text == "(" }
        assertTrue("内容高 → 定界符放大", tallParen.sizeEm > smallParen.sizeEm)
    }

    @Test
    fun layout_textRunIsUpright() {
        val box = layout("\\text{中文}")
        val run = box.ops.filterIsInstance<MathOp.Text>().single()
        assertEquals("中文", run.text)
        assertEquals("正体", false, run.italic)
    }

    @Test
    fun layout_neverProducesNegativeSizes() {
        listOf("\\frac{\\frac{a}{b}}{c}", "x_{i_j}", "\\sqrt{\\sqrt{x}}").forEach { src ->
            val box = layout(src)
            assertTrue("宽必须为正：$src", box.width > 0f)
            assertTrue("高必须为正：$src", box.height > 0f)
            box.ops.filterIsInstance<MathOp.Text>().forEach {
                assertTrue("字号必须为正：$src", it.sizeEm > 0f)
                assertTrue("宽度必须有限：$src", max(it.x, it.baselineY) < 100f)
            }
        }
    }
}
