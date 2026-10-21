package edu.jxslu.schedule.ui.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import edu.jxslu.schedule.domain.Note
import edu.jxslu.schedule.domain.imageRefs
import edu.jxslu.schedule.domain.plainExcerpt
import edu.jxslu.schedule.ui.common.AttachmentThumbnailRow
import edu.jxslu.schedule.ui.common.epochMonthDay
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.PencilEdit02

/**
 * 笔记列表的**展开详情块**（2026-10-21，DESIGN §3.11）：
 * 标题（空标题用正文首行兜底）+ 更新时间 + 2 行摘要 + 图片缩略图。
 *
 * 笔记通常很长，列表不做全文展开——全文仍在详情页；这里只把「标题之外还有什么」
 * 和「有没有图」交代清楚。缩略图与作业列表同一口径（240px 解码、44dp 方图）。
 *
 * 本组件只画内容，不画卡片：组卡展开区直接平铺，课程页由调用方套 `AppCard`。
 */
@Composable
internal fun NoteItemBlock(
    note: Note,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onImageClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** true = 组卡展开区（左缘 32dp 与课程名对齐）；false = 课程页顶层卡片（左缘 14dp）。 */
    embedded: Boolean = true,
) {
    val haptics = rememberAppHaptics()
    val title = remember(note.title, note.body) {
        note.title.ifBlank {
            plainExcerpt(note.body, max = 30).ifBlank { "未命名笔记" }
        }
    }
    val excerpt = remember(note.body) { plainExcerpt(note.body) }
    val images = remember(note.body) { imageRefs(note.body).toList() }
    val startPadding = if (embedded) 32.dp else 14.dp

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable {
                haptics.tap()
                onClick()
            }
            // 左缩进 32dp = 组卡头行的课程名左缘（14 内边距 + 8 色点 + 10 间距）
            .padding(start = startPadding, top = 11.dp, end = 14.dp, bottom = 11.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "更新 ${epochMonthDay(note.updatedAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(
                onClick = {
                    haptics.tap()
                    onEdit()
                },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = HugeIcons.PencilEdit02,
                    contentDescription = "编辑笔记",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (excerpt.isNotBlank()) {
            Text(
                text = excerpt,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (images.isNotEmpty()) {
            AttachmentThumbnailRow(
                fileNames = images,
                size = 44.dp,
                onOpen = onImageClick,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
