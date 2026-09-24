package edu.jxslu.schedule.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import edu.jxslu.schedule.domain.MdBlock
import edu.jxslu.schedule.domain.MdInline
import edu.jxslu.schedule.domain.MdItem
import edu.jxslu.schedule.domain.MdParagraphPart
import edu.jxslu.schedule.domain.MdStyle
import edu.jxslu.schedule.domain.parseMarkdown
import edu.jxslu.schedule.domain.splitParagraph

/**
 * Markdown 子集的 Compose 渲染（DESIGN §4.20，支持清单以文档为准）。
 *
 * 分工：解析在 `domain/Markdown.kt`（纯 JVM 可测），这里只做样式映射与交互——
 * 图片点开全屏、链接可点、任务项自绘勾选框、公式走 [MathText]。
 *
 * 样式口径（与 §3.11 对齐）：H1 22 / H2 19 / H3 17 / H4-6 15 加粗，正文 bodyLarge 行高 1.5；
 * 代码块 surfaceVariant 底 + 等宽 + 12dp 圆角；引用左缘 3dp 竖线；列表缩进 20dp/级；
 * 分隔线 outline 30%。**行内图片**（与文字混排）渲染为可点的「[图片]」标签，
 * 真图走块级——段落内的切分判据只有 `domain/Markdown.splitParagraph` 一处
 * （独占一行的图片，本就是编辑器插入的形态；连插多张时它们同属一个段落）。
 */
@Composable
fun MarkdownView(
    markdown: String,
    modifier: Modifier = Modifier,
    onImageClick: ((String) -> Unit)? = null,
    onLinkClick: ((String) -> Unit)? = null,
) {
    val blocks = remember(markdown) { parseMarkdown(markdown) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEach { block ->
            MarkdownBlock(block, level = 0, onImageClick = onImageClick, onLinkClick = onLinkClick)
        }
    }
}

@Composable
private fun ColumnScope.MarkdownBlock(
    block: MdBlock,
    level: Int,
    onImageClick: ((String) -> Unit)?,
    onLinkClick: ((String) -> Unit)?,
) {
    when (block) {
        is MdBlock.Heading -> {
            val size = when (block.level) {
                1 -> 22.sp
                2 -> 19.sp
                3 -> 17.sp
                else -> 15.sp
            }
            InlineText(
                nodes = block.content,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = size,
                    fontWeight = FontWeight.Bold,
                    lineHeight = size * 1.35f,
                ),
                modifier = Modifier.padding(top = if (block.level <= 2) 6.dp else 2.dp),
                onImageClick = onImageClick,
                onLinkClick = onLinkClick,
            )
        }

        is MdBlock.Paragraph -> {
            // 独占一行的图片按块级画真图；其余行合并成行内文本（连插两张图、图紧跟文字后
            // 都落在同一段落里，不切开就会渲染成一串「[图片]」蓝标签，DESIGN §4.20）
            val parts = remember(block.content) { splitParagraph(block.content) }
            parts.forEach { part ->
                when (part) {
                    is MdParagraphPart.BlockImage -> {
                        if (isLocalImage(part.ref)) {
                            val name = localImageName(part.ref)
                            AttachmentImage(fileName = name, onClick = onImageClick?.let { { it(name) } })
                        } else {
                            ExternalImageHint(part.ref)
                        }
                    }

                    is MdParagraphPart.Inline -> InlineText(
                        nodes = part.content,
                        style = bodyStyle(),
                        onImageClick = onImageClick,
                        onLinkClick = onLinkClick,
                    )
                }
            }
        }

        is MdBlock.ListBlock -> Column(
            modifier = Modifier.padding(start = (level * 20).dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            var index = block.start
            block.items.forEach { item ->
                ListItemRow(
                    item = item,
                    ordered = block.ordered,
                    index = if (block.ordered) index++ else null,
                    level = level,
                    onImageClick = onImageClick,
                    onLinkClick = onLinkClick,
                )
            }
        }

        is MdBlock.Quote -> Row(modifier = Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
            )
            Column(
                modifier = Modifier.padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                block.blocks.forEach { inner -> MarkdownBlock(inner, level, onImageClick, onLinkClick) }
            }
        }

        is MdBlock.Code -> CodeBlockView(block)

        is MdBlock.MathBlock -> MathText(
            tex = block.tex,
            baseSize = 17.sp,
            display = true,
            modifier = Modifier.padding(vertical = 4.dp),
        )

        MdBlock.Divider -> HorizontalDivider(
            modifier = Modifier.padding(vertical = 4.dp),
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
        )
    }
}

@Composable
private fun ListItemRow(
    item: MdItem,
    ordered: Boolean,
    index: Int?,
    level: Int,
    onImageClick: ((String) -> Unit)?,
    onLinkClick: ((String) -> Unit)?,
) {
    Column {
        Row(verticalAlignment = Alignment.Top) {
            if (item.task == null) {
                Text(
                    text = if (ordered) "${index ?: 1}. " else "• ",
                    style = bodyStyle(),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            } else {
                TaskCheckbox(checked = item.task)
                Spacer(Modifier.width(8.dp))
            }
            InlineText(
                nodes = item.content,
                style = bodyStyle().copy(
                    color = if (item.task == true) {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    textDecoration = if (item.task == true) TextDecoration.LineThrough else null,
                ),
                modifier = Modifier.weight(1f),
                onImageClick = onImageClick,
                onLinkClick = onLinkClick,
            )
        }
        if (item.sub.isNotEmpty()) {
            Column(
                modifier = Modifier.padding(start = 14.dp, top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item.sub.forEach { sub -> MarkdownBlock(sub, level + 1, onImageClick, onLinkClick) }
            }
        }
    }
}

/** 任务项勾选框：自绘 16dp 方框（不引图标依赖、不受图标库版本影响）。 */
@Composable
private fun TaskCheckbox(checked: Boolean) {
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline
    Canvas(
        modifier = Modifier
            .padding(top = 3.dp)
            .size(16.dp),
    ) {
        val stroke = 1.6.dp.toPx()
        val corner = CornerRadius(4.dp.toPx())
        if (!checked) {
            drawRoundRect(
                color = outline.copy(alpha = 0.75f),
                cornerRadius = corner,
                style = Stroke(width = stroke),
            )
        } else {
            drawRoundRect(color = primary, cornerRadius = corner)
            val path = Path().apply {
                moveTo(size.width * 0.24f, size.height * 0.52f)
                lineTo(size.width * 0.44f, size.height * 0.72f)
                lineTo(size.width * 0.78f, size.height * 0.30f)
            }
            drawPath(path = path, color = Color.White, style = Stroke(width = stroke, cap = StrokeCap.Round))
        }
    }
}

@Composable
private fun CodeBlockView(block: MdBlock.Code) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(12.dp)
            .horizontalScroll(rememberScrollState()),
    ) {
        Text(
            text = block.text,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                lineHeight = 20.sp,
            ),
        )
    }
}

/** 外链图片：不支持联网取图（本地优先），给一行诚实提示而不是空白。 */
@Composable
private fun ExternalImageHint(ref: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = "外链图片不在本机显示 · $ref",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}

internal fun isLocalImage(ref: String): Boolean = ref.startsWith("img:")

internal fun localImageName(ref: String): String = ref.removePrefix("img:")

@Composable
private fun bodyStyle(): TextStyle = MaterialTheme.typography.bodyLarge.copy(
    lineHeight = MaterialTheme.typography.bodyLarge.fontSize * 1.5f,
)

// ---------------------------------------------------------------------------
// 行内：AnnotatedString + 内联公式 + 点击命中
// ---------------------------------------------------------------------------

private const val TAG_LINK = "md_link"
private const val TAG_IMAGE = "md_image"

/** 行内渲染结果：文本 + 内联内容（公式占位盒子）。 */
private class InlineRender(
    val text: AnnotatedString,
    val inlineContent: Map<String, InlineTextContent>,
)

/**
 * 行内文本：公式节点先按顺序排版（`rememberMathRender` 只能在组合里调用），
 * 再在纯计算里拼 [AnnotatedString]；点击命中走 `TextLayoutResult` 的字符串注解查找。
 */
@Composable
private fun InlineText(
    nodes: List<MdInline>,
    style: TextStyle,
    modifier: Modifier = Modifier,
    onImageClick: ((String) -> Unit)? = null,
    onLinkClick: ((String) -> Unit)? = null,
) {
    val built = rememberInline(nodes, style.fontSize)
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val interactive = onImageClick != null || onLinkClick != null
    Text(
        text = built.text,
        inlineContent = built.inlineContent,
        style = style,
        modifier = if (interactive) {
            modifier.pointerInput(built) {
                detectTapGestures { offset ->
                    val result = layout ?: return@detectTapGestures
                    val pos = result.getOffsetForPosition(offset)
                    val image = built.text.getStringAnnotations(TAG_IMAGE, pos, pos).firstOrNull()
                    if (image != null) {
                        onImageClick?.invoke(image.item)
                        return@detectTapGestures
                    }
                    built.text.getStringAnnotations(TAG_LINK, pos, pos).firstOrNull()?.let { link ->
                        onLinkClick?.invoke(link.item)
                    }
                }
            }
        } else {
            modifier
        },
        onTextLayout = { layout = it },
    )
}

@Composable
private fun rememberInline(nodes: List<MdInline>, fontSize: TextUnit): InlineRender {
    val measurer = rememberTextMeasurer(cacheSize = 192)
    val density = LocalDensity.current
    val primary = MaterialTheme.colorScheme.primary
    val codeBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)

    val mathNodes = remember(nodes) { collectMath(nodes) }
    val mathRenders = mathNodes.map { rememberMathRender(it, fontSize, display = false, measurer = measurer) }

    return remember(nodes, mathRenders) {
        var mathIndex = 0
        val inlineContent = mutableMapOf<String, InlineTextContent>()
        val text = buildAnnotatedString {
            fun emit(list: List<MdInline>, style: SpanStyle) {
                list.forEach { node ->
                    when (node) {
                        is MdInline.Text -> withStyle(style) { append(node.text) }

                        is MdInline.Styled -> emit(
                            node.content,
                            when (node.style) {
                                MdStyle.Bold -> style.merge(SpanStyle(fontWeight = FontWeight.Bold))
                                MdStyle.Italic -> style.merge(SpanStyle(fontStyle = FontStyle.Italic))
                                MdStyle.Strike -> style.merge(SpanStyle(textDecoration = TextDecoration.LineThrough))
                            },
                        )

                        is MdInline.Code -> withStyle(
                            style.merge(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)),
                        ) { append(node.text) }

                        is MdInline.Link -> {
                            val start = length
                            emit(
                                node.content,
                                style.merge(
                                    SpanStyle(color = primary, textDecoration = TextDecoration.Underline),
                                ),
                            )
                            addStringAnnotation(TAG_LINK, node.url, start, length)
                        }

                        is MdInline.Image -> {
                            val start = length
                            val local = isLocalImage(node.ref)
                            withStyle(
                                style.merge(
                                    SpanStyle(color = primary, textDecoration = TextDecoration.Underline),
                                ),
                            ) { append(if (local) "[图片]" else "[外链图片]") }
                            if (local) addStringAnnotation(TAG_IMAGE, localImageName(node.ref), start, length)
                        }

                        is MdInline.Math -> {
                            val idx = mathIndex++
                            val render = mathRenders.getOrNull(idx)
                            if (render == null) {
                                // 解析失败：等宽源码（不吞内容、不做半截渲染）
                                withStyle(style.merge(SpanStyle(fontFamily = FontFamily.Monospace))) {
                                    append(" ${node.tex} ")
                                }
                            } else {
                                val id = "math-$idx"
                                inlineContent[id] = InlineTextContent(
                                    placeholder = Placeholder(
                                        width = with(density) { (render.box.width * render.basePx).toSp() },
                                        height = with(density) { (render.box.height * render.basePx).toSp() },
                                        placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
                                    ),
                                    children = { MathText(tex = node.tex, baseSize = fontSize, display = false) },
                                )
                                appendInlineContent(id, node.tex)
                            }
                        }
                    }
                }
            }
            emit(nodes, SpanStyle())
        }
        InlineRender(text, inlineContent)
    }
}

/** 顺序收集行内公式源码（与 [rememberInline] 的排版顺序严格一致）。 */
private fun collectMath(nodes: List<MdInline>): List<String> {
    val out = mutableListOf<String>()
    fun walk(list: List<MdInline>) {
        list.forEach { node ->
            when (node) {
                is MdInline.Math -> out += node.tex
                is MdInline.Styled -> walk(node.content)
                is MdInline.Link -> walk(node.content)
                else -> Unit
            }
        }
    }
    walk(nodes)
    return out
}
