package edu.jxslu.schedule.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import edu.jxslu.schedule.domain.MdAction
import edu.jxslu.schedule.domain.applyMarkdownAction
import edu.jxslu.schedule.domain.autoPairDollar
import edu.jxslu.schedule.domain.continueOnEnter
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.CheckList
import me.rerere.hugeicons.stroke.Code
import me.rerere.hugeicons.stroke.Heading02
import me.rerere.hugeicons.stroke.ImageAdd01
import me.rerere.hugeicons.stroke.LeftToRightListBullet
import me.rerere.hugeicons.stroke.LeftToRightListNumber
import me.rerere.hugeicons.stroke.QuoteUp
import me.rerere.hugeicons.stroke.Sigma
import me.rerere.hugeicons.stroke.TextBold
import me.rerere.hugeicons.stroke.TextItalic
import me.rerere.hugeicons.stroke.TextStrikethrough

/**
 * Markdown 编辑器（DESIGN §3.11）：源码输入 + 格式工具条 + 自动补全接线。
 *
 * 自动补全的判断**全部在 `domain/MarkdownEdit.kt`**（纯 JVM 可测），这里只是把
 * `TextFieldValue` 的差分喂进去、把结果写回——不要在 Composable 里另写续行/配对逻辑。
 */
@Composable
fun MarkdownEditor(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "写点什么…支持 Markdown 与 $ 公式",
    minHeight: Dp = 220.dp,
    onPickImages: (() -> Unit)? = null,
) {
    Column(modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .clip(AppCardDefaults.Shape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f))
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = AppCardDefaults.Shape,
                )
                .padding(12.dp),
        ) {
            if (value.text.isEmpty()) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                )
            }
            BasicTextField(
                value = value,
                onValueChange = { next -> onValueChange(handleMarkdownInput(value, next) ?: next) },
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = MaterialTheme.typography.bodyLarge.fontSize * 1.5f,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        EditorToolbar(
            value = value,
            onValueChange = onValueChange,
            onPickImages = onPickImages,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/**
 * 无边框标题输入（笔记 / 作业详情共用，2026-09-22 新增）。
 *
 * 此前用 M3 的 `OutlinedTextField`：4dp 直角描边、聚焦时边框加粗、标签浮到边框上，
 * 与全 App 的圆角卡片不是一套语言；而且查看态的标题是无边框大字，
 * 两态切换时字号与左缘都跳一下。现在查看/编辑同字号（headlineSmall）同左缘，
 * 编辑态只多一个占位提示与光标。
 *
 * 光标是唯一的位置线索，所以占位用 35% 透明而不是空行——不然新建笔记时光标容易看不见。
 */
@Composable
fun TitleTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxWidth()) {
        if (value.text.isEmpty()) {
            Text(
                text = placeholder,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = MaterialTheme.typography.headlineSmall.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            // 单行：标题里换行会让它变成两行大字把表单顶下去，旧的 OutlinedTextField 也是
            // singleLine——这条口径不能因为换控件而丢
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 工具条按钮（图标名均取自 HugeIcons 本地 JAR，勿猜）。 */
private class ToolbarAction(
    val icon: ImageVector,
    val label: String,
    val action: MdAction? = null,
)

private val TOOLBAR: List<ToolbarAction> = listOf(
    ToolbarAction(HugeIcons.Heading02, "标题", MdAction.Heading),
    ToolbarAction(HugeIcons.TextBold, "加粗", MdAction.Bold),
    ToolbarAction(HugeIcons.TextItalic, "斜体", MdAction.Italic),
    ToolbarAction(HugeIcons.TextStrikethrough, "删除线", MdAction.Strike),
    ToolbarAction(HugeIcons.LeftToRightListBullet, "无序列表", MdAction.Bullet),
    ToolbarAction(HugeIcons.LeftToRightListNumber, "有序列表", MdAction.Ordered),
    ToolbarAction(HugeIcons.CheckList, "任务项", MdAction.Task),
    ToolbarAction(HugeIcons.QuoteUp, "引用", MdAction.Quote),
    ToolbarAction(HugeIcons.Code, "代码", MdAction.Code),
    ToolbarAction(HugeIcons.Sigma, "公式", MdAction.Math),
)

@Composable
private fun EditorToolbar(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onPickImages: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberAppHaptics()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(AppCardDefaults.Shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        TOOLBAR.forEach { item ->
            IconButton(
                onClick = {
                    haptics.tap()
                    val action = item.action ?: return@IconButton
                    val result = applyMarkdownAction(
                        text = value.text,
                        selectionStart = value.selection.start,
                        selectionEnd = value.selection.end,
                        action = action,
                    )
                    onValueChange(
                        TextFieldValue(result.text, TextRange(result.selectionStart, result.selectionEnd)),
                    )
                },
                modifier = Modifier.size(38.dp),
            ) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = item.label,
                    modifier = Modifier.size(19.dp),
                )
            }
        }
        if (onPickImages != null) {
            IconButton(
                onClick = {
                    haptics.tap()
                    onPickImages()
                },
                modifier = Modifier.size(38.dp),
            ) {
                Icon(
                    imageVector = HugeIcons.ImageAdd01,
                    contentDescription = "插入图片",
                    modifier = Modifier.size(19.dp),
                )
            }
        }
    }
}

/**
 * 输入差分处理：回车续行 与 `$` 自动配对（纯函数在 domain）。
 * 返回 null = 不干预（保留输入框的原始结果）。
 */
internal fun handleMarkdownInput(old: TextFieldValue, new: TextFieldValue): TextFieldValue? {
    val oldText = old.text
    val newText = new.text
    if (newText.length != oldText.length + 1) return null
    val insertedAt = new.selection.start - 1
    if (insertedAt !in 0 until newText.length) return null
    return when (newText[insertedAt]) {
        '\n' -> continueOnEnter(oldText, insertedAt)?.toTextFieldValue()
        '$' -> autoPairDollar(oldText, newText, new.selection.start)?.toTextFieldValue()
        else -> null
    }
}

private fun edu.jxslu.schedule.domain.EditResult.toTextFieldValue(): TextFieldValue =
    TextFieldValue(
        text = text,
        selection = TextRange(selectionStart, selectionEnd),
    )
