package edu.jxslu.schedule.ui.homework

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import edu.jxslu.schedule.domain.Homework
import edu.jxslu.schedule.domain.dueLabel
import edu.jxslu.schedule.domain.homeworkDisplay
import edu.jxslu.schedule.ui.common.MarkdownView
import edu.jxslu.schedule.ui.common.MdImageStyle
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.PencilEdit02
import java.time.LocalDate

/**
 * 作业列表的**展开详情块**（2026-10-21，DESIGN §3.11）：
 * 头行（勾选 + 标题 + 截止 chip + 编辑）+ 完整 Markdown 正文 + 缩略图。
 *
 * 与旧的单行摘要行（`HomeworkRow`，已删）的差别：不再只给正文首行摘要。标题取
 * [homeworkDisplay] 的 title，正文取它的 body（已摘掉标题行，避免「标题 + 正文第一行」重复）；
 * 图片走 [MdImageStyle.Thumbnail]（72dp、240px 解码），点开仍由调用方给全屏查看器。
 *
 * 本组件只画内容，不画卡片：组卡展开区直接平铺（父级用 `AppCardDivider` 分隔），
 * 课程页由调用方套 `AppCard`。删除仍在详情页，防误触。
 */
@Composable
internal fun HomeworkItemBlock(
    homework: Homework,
    today: LocalDate,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onImageClick: (String) -> Unit,
    onLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberAppHaptics()
    val display = remember(homework.detail) { homeworkDisplay(homework.detail) }
    val label = dueLabel(homework.dueDate, today)
    val overdue = homework.dueDate?.isBefore(today) == true && !homework.done
    val onSurface = MaterialTheme.colorScheme.onSurface

    Column(
        modifier = modifier
            .fillMaxWidth()
            // 勾选框自带 M3 的 48dp 触达区，行内间距向它让位（左右不对称是有意的）
            .padding(start = 4.dp, end = 14.dp, top = 12.dp, bottom = 13.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = homework.done,
                onCheckedChange = { checked ->
                    haptics.toggle()
                    onToggle(checked)
                },
            )
            Spacer(Modifier.width(4.dp))
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = display.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (homework.done) onSurface.copy(alpha = 0.5f) else onSurface,
                    textDecoration = if (homework.done) TextDecoration.LineThrough else null,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            haptics.tap()
                            onClick()
                        },
                )
                if (label != null) {
                    Spacer(Modifier.width(8.dp))
                    DueChip(label = label, overdue = overdue, dimmed = homework.done)
                }
            }
            IconButton(
                onClick = {
                    haptics.tap()
                    onEdit()
                },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = HugeIcons.PencilEdit02,
                    contentDescription = "编辑作业",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }

        if (display.body.isNotBlank()) {
            MarkdownView(
                markdown = display.body,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 52.dp, top = 6.dp)
                    .alpha(if (homework.done) 0.55f else 1f)
                    .clickable {
                        haptics.tap()
                        onClick()
                    },
                imageStyle = MdImageStyle.Thumbnail,
                onImageClick = onImageClick,
                onLinkClick = onLinkClick,
            )
        }
    }
}

/** 已完成分组的标题行（作业库 / 课程作业页共用）。 */
@Composable
internal fun HomeworkDoneHeader(count: Int) {
    Text(
        text = "已完成 · $count",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 2.dp),
    )
}

/** 截止 chip：逾期红、今天/明天主色、远期中性色；完成项整体压暗。 */
@Composable
private fun DueChip(label: String, overdue: Boolean, dimmed: Boolean) {
    val (bg, fg) = when {
        overdue -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        label == "今天" || label == "明天" ->
            MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = fg,
        maxLines = 1,
        modifier = Modifier
            .alpha(if (dimmed) 0.6f else 1f)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 7.dp, vertical = 4.dp),
    )
}
