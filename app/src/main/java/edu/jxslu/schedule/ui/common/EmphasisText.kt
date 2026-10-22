package edu.jxslu.schedule.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * 声明文案里的 `**重点**` 渲染成加粗片段。
 *
 * 只支持成对标记，未闭合的 `**` 原样保留——这不是 Markdown 解析器，只是给
 * `domain/` 里的声明正文标重点用。文案仍以普通 String 存放，纯 JVM 单测可直接比对。
 *
 * [boldColor] 为空时只加粗、不改色；页脚这类整体半透明的正文传入更实的颜色，
 * 避免重点字段跟着正文一起变淡。
 */
private val EmphasisPattern = Regex("\\*\\*(.+?)\\*\\*", RegexOption.DOT_MATCHES_ALL)

fun emphasizedText(
    text: String,
    boldColor: Color? = null,
): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    EmphasisPattern.findAll(text).forEach { match ->
        append(text.substring(cursor, match.range.first))
        val style = if (boldColor == null) {
            SpanStyle(fontWeight = FontWeight.Bold)
        } else {
            SpanStyle(fontWeight = FontWeight.Bold, color = boldColor)
        }
        withStyle(style) {
            append(match.groupValues[1])
        }
        cursor = match.range.last + 1
    }
    append(text.substring(cursor))
}
