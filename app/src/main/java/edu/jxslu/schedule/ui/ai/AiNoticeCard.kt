package edu.jxslu.schedule.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import edu.jxslu.schedule.ui.common.AppCard
import edu.jxslu.schedule.ui.theme.semanticColors
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.ClipboardPaste
import me.rerere.hugeicons.stroke.ImageAdd01

/**
 * ① 通知输入卡（DESIGN §3.25 v2）：调课通知的文字与截图都贴在这里。
 *
 * 布局纪律：卡片走 [AppCard]（不再自绘 Surface）；动作按钮走 M3 [TextButton]、
 * 命中区 ≥44dp；文字透明度只用三层（正文 / 0.6 / 0.45）。
 *
 * 状态词是唯一的「发送状态」呈现：未分析不显示；已发送显示「已分析 · H:mm」；
 * 与已发送内容不一致时显示「内容有改动」（对应底栏的「重新分析」）。
 */
@Composable
internal fun AiNoticeCard(
    work: AiWorkState,
    draft: String,
    attachments: List<String>,
    onDraftChange: (String) -> Unit,
    onPickImages: () -> Unit,
    onPasteImage: () -> Unit,
    onRemoveImage: (String) -> Unit,
    onOpenImage: (String) -> Unit,
    onClear: () -> Unit,
) {
    val analyzing = work.phase == AiPhase.ANALYZING
    val dirty = noticeDirty(work, draft, attachments)
    val onSurface = MaterialTheme.colorScheme.onSurface
    val status: Pair<String, Color>? = when {
        analyzing -> "分析中" to onSurface.copy(alpha = 0.45f)
        work.phase == AiPhase.IDLE -> null
        dirty -> "内容有改动" to MaterialTheme.semanticColors.warning
        work.phase == AiPhase.FAILED -> "分析失败" to MaterialTheme.semanticColors.warning
        else -> "已分析 · ${work.analyzedAt}" to onSurface.copy(alpha = 0.45f)
    }
    val hasInput = draft.isNotBlank() || attachments.isNotEmpty()

    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "调课通知",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = onSurface.copy(alpha = 0.75f),
                modifier = Modifier.weight(1f),
            )
            if (status != null) {
                Text(status.first, style = MaterialTheme.typography.labelSmall, color = status.second)
            }
        }
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 68.dp, max = 180.dp),
            placeholder = {
                Text(
                    "粘贴调课通知的文字，或加一张截图",
                    style = MaterialTheme.typography.bodySmall,
                    color = onSurface.copy(alpha = 0.45f),
                )
            },
            textStyle = MaterialTheme.typography.bodyMedium,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
            ),
            maxLines = 8,
        )
        if (attachments.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                attachments.forEach { name ->
                    AttachmentThumb(
                        fileName = name,
                        onOpen = { onOpenImage(name) },
                        onRemove = { onRemoveImage(name) },
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = onPickImages,
                enabled = !analyzing,
                modifier = Modifier.heightIn(min = 44.dp),
                contentPadding = PaddingValues(horizontal = 10.dp),
            ) {
                Icon(HugeIcons.ImageAdd01, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("添加截图", style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
            TextButton(
                onClick = onPasteImage,
                enabled = !analyzing,
                modifier = Modifier.heightIn(min = 44.dp),
                contentPadding = PaddingValues(horizontal = 10.dp),
            ) {
                Icon(HugeIcons.ClipboardPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("粘贴图片", style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = onClear,
                enabled = hasInput && !analyzing,
                modifier = Modifier.heightIn(min = 44.dp),
                contentPadding = PaddingValues(horizontal = 10.dp),
            ) {
                Text("清空", style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
        Text(
            "点「分析」前不发送任何内容 · 教师名默认已脱敏",
            style = MaterialTheme.typography.labelSmall,
            color = onSurface.copy(alpha = 0.45f),
        )
    }
}

/** 一张待发图片：点看大图、右上角移除（✕ 外套 32dp 命中区，别做成 16dp 的死靶）。 */
@Composable
private fun AttachmentThumb(
    fileName: String,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    Box {
        AiThumb(
            fileName = fileName,
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable { onOpen() },
            contentDescription = "通知图片，点按查看",
        )
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .size(30.dp)
                .clickable { onRemove() },
            contentAlignment = Alignment.TopEnd,
        ) {
            Box(
                Modifier
                    .padding(2.dp)
                    .size(17.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    HugeIcons.Cancel01,
                    contentDescription = "移除这张图片",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}
