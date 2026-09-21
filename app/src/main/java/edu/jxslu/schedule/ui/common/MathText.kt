package edu.jxslu.schedule.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import edu.jxslu.schedule.domain.MathBox
import edu.jxslu.schedule.domain.MathOp
import edu.jxslu.schedule.domain.MathTextMeasurer
import edu.jxslu.schedule.domain.layoutMath
import edu.jxslu.schedule.domain.parseTex

/**
 * LaTeX 子集的 Compose 渲染（DESIGN §4.20）。
 *
 * 分工：几何全在 `domain/MathTex.kt`（纯 JVM 可测），这里只做两件 UI 事——
 * 用 [TextMeasurer] 提供真宽度（em 单位换算）与把 [MathBox] 画到 Canvas 上。
 *
 * **解析失败 = 等宽源码**（[MathFallback]）：宁可露出 TeX 让用户知道"这条没渲染"，
 * 也不做半截渲染误导。
 */
@Composable
fun MathText(
    tex: String,
    modifier: Modifier = Modifier,
    baseSize: TextUnit = 16.sp,
    display: Boolean = false,
    color: Color = LocalContentColor.current,
    /** display 模式下居中展示（块级公式）；inline 时调用方自己摆位置。 */
    centered: Boolean = display,
) {
    val measurer = rememberTextMeasurer(cacheSize = 192)
    val render = rememberMathRender(tex, baseSize, display, measurer)
    if (render == null) {
        MathFallback(tex, modifier, baseSize)
        return
    }
    val density = LocalDensity.current
    val width = with(density) { (render.box.width * render.basePx).toDp() }
    val height = with(density) { (render.box.height * render.basePx).toDp() }
    val canvas = Modifier.size(width, height)
    val content: @Composable () -> Unit = {
        Canvas(canvas) {
            drawMathBox(render, baseSize, color, measurer)
        }
    }
    if (centered) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { content() }
    } else {
        Box(modifier) { content() }
    }
}

/** 解析失败的兜底：等宽源码（不做半截渲染）。 */
@Composable
fun MathFallback(tex: String, modifier: Modifier = Modifier, baseSize: TextUnit = 16.sp) {
    Text(
        text = tex,
        modifier = modifier,
        fontFamily = FontFamily.Monospace,
        fontSize = (baseSize.value * 0.85f).sp,
        color = LocalContentColor.current.copy(alpha = 0.7f),
    )
}

/** 排版结果 + 基准像素（1em = basePx px）。 */
internal class MathRender(val box: MathBox, val basePx: Float)

/** 解析 + 排版；失败（含超范围语法）返回 null。 */
@Composable
internal fun rememberMathRender(
    tex: String,
    baseSize: TextUnit,
    display: Boolean,
    measurer: TextMeasurer,
): MathRender? {
    val node = remember(tex) { parseTex(tex) } ?: return null
    val density = LocalDensity.current
    val basePx = with(density) { baseSize.toPx() }
    return remember(node, basePx, display) {
        val measure = MathTextMeasurer { text, sizeEm ->
            val style = mathTextStyle(sizeEm * baseSize.value, italic = false)
            measurer.measure(AnnotatedString(text), style).size.width / basePx
        }
        MathRender(layoutMath(node, sizeEm = 1f, display = display, measure = measure), basePx)
    }
}

private fun mathTextStyle(sizeSp: Float, italic: Boolean): TextStyle = TextStyle(
    fontSize = sizeSp.sp,
    fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
    fontWeight = FontWeight.Normal,
)

private fun DrawScope.drawMathBox(
    render: MathRender,
    baseSize: TextUnit,
    color: Color,
    measurer: TextMeasurer,
) {
    val basePx = render.basePx
    val baselineY = render.box.ascent * basePx
    render.box.ops.forEach { op ->
        when (op) {
            is MathOp.Text -> {
                val style = mathTextStyle(op.sizeEm * baseSize.value, op.italic).copy(color = color)
                val result = measurer.measure(AnnotatedString(op.text), style)
                drawText(
                    textLayoutResult = result,
                    topLeft = Offset(
                        op.x * basePx,
                        baselineY + op.baselineY * basePx - result.firstBaseline,
                    ),
                )
            }

            is MathOp.Line -> {
                drawLine(
                    color = color,
                    start = Offset(op.x1 * basePx, baselineY + op.y1 * basePx),
                    end = Offset(op.x2 * basePx, baselineY + op.y2 * basePx),
                    strokeWidth = (op.thickness * basePx).coerceAtLeast(0.6f),
                )
            }
        }
    }
}
