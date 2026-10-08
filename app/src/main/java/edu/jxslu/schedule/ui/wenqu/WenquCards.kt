package edu.jxslu.schedule.ui.wenqu

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import edu.jxslu.schedule.data.wenqu.WenquActivity
import edu.jxslu.schedule.data.wenqu.WenquVolunteerRecord
import edu.jxslu.schedule.domain.WenquRules
import edu.jxslu.schedule.ui.common.AppCard

/**
 * 问渠的条目卡（DESIGN §3.28「统一卡片」）——活动列表、记录列表共用一件。
 *
 * 首行 = 状态徽章（左）+ 右侧数字（名额 / 工时）；然后标题（最多两行）；
 * 最后一行是 `单位 · 地点 · 时间`（最多两行，长单位名不挤掉时间）。
 * 徽章观感与 [edu.jxslu.schedule.ui.common.SettingsTag] 同族：低透明底色 + 同色文字。
 */
@Composable
internal fun WenquEntryCard(
    badge: String,
    badgeColor: Color,
    title: String,
    meta: String,
    trailing: String? = null,
    trailingColor: Color? = null,
    /** 第二枚徽章（活动卡的「已报名」）——跟在状态徽章后面，不挤掉右侧数字。 */
    extraBadge: String? = null,
    extraBadgeColor: Color? = null,
    onClickLabel: String? = null,
    onClick: (() -> Unit)? = null,
) {
    AppCard(onClick = onClick, onClickLabel = onClickLabel) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (badge.isNotBlank()) {
                WenquBadge(text = badge, color = badgeColor)
            }
            if (!extraBadge.isNullOrBlank()) {
                Spacer(Modifier.width(6.dp))
                WenquBadge(text = extraBadge, color = extraBadgeColor ?: badgeColor)
            }
            Spacer(Modifier.weight(1f))
            if (trailing != null) {
                Text(
                    text = trailing,
                    style = MaterialTheme.typography.labelSmall,
                    color = trailingColor ?: MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (meta.isNotBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(
                text = meta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 小徽章：低透明底色 + 同色文字（与 `SettingsTag` 同族）。 */
@Composable
private fun WenquBadge(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(5.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** 活动状态 → 徽章色：招募中 = 主色，进行中 = 第三色，其余（已结束 / 已取消）压成灰。 */
@Composable
internal fun wenquStatusColor(status: Int): Color = when (status) {
    1 -> MaterialTheme.colorScheme.primary
    2 -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
}

/**
 * 活动卡：状态徽章 + （已报名时）「已报名」+ 活动名 + 单位/地点/时间 + 名额。
 *
 * `isApply` 平台一直在回，早先解析了却没展示——报过名的活动和没报名的长得一模一样，
 * 只有点开详情才看到「我的状态」（2026-10-08 用户要的）。
 */
@Composable
internal fun ActivityCard(activity: WenquActivity, onClick: () -> Unit) {
    val full = WenquRules.isFull(activity.status, activity.peopleNum, activity.balanceNum)
    val quota = if (full) "已满" else WenquRules.quotaText(activity.joinNum, activity.peopleNum)
    WenquEntryCard(
        badge = WenquRules.statusLabel(activity.status),
        badgeColor = wenquStatusColor(activity.status),
        extraBadge = if (activity.applied) "已报名" else null,
        extraBadgeColor = MaterialTheme.colorScheme.primary,
        title = activity.title.ifBlank { activity.projectName.ifBlank { "志愿服务活动" } },
        meta = listOfNotNull(
            activity.unit.takeIf { it.isNotBlank() },
            activity.address.takeIf { it.isNotBlank() },
            WenquRules.timeRange(activity.startTime, activity.endTime).takeIf { it.isNotBlank() },
        ).joinToString(" · "),
        trailing = quota.takeIf { it.isNotBlank() },
        trailingColor = if (full) MaterialTheme.colorScheme.error else null,
        onClickLabel = "查看活动详情",
        onClick = onClick,
    )
}

/**
 * 记录卡：与活动卡同一件，只是数据来自「我的记录」。
 *
 * 徽章用**我的报名状态**（`apply_status_text`，如「已完成」）而不是活动状态——
 * 记录列表里关心的是「我这条怎么样了」。
 */
@Composable
internal fun RecordCard(
    record: WenquVolunteerRecord,
    onClick: (() -> Unit)? = null,
) {
    val score = WenquRules.hoursOf(record.score)
    WenquEntryCard(
        badge = record.statusText,
        badgeColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        title = record.title.ifBlank { "志愿服务活动" },
        meta = listOfNotNull(
            record.unit.takeIf { it.isNotBlank() },
            record.address.takeIf { it.isNotBlank() },
            WenquRules.timeRange(record.startTime, record.endTime).takeIf { it.isNotBlank() },
        ).joinToString(" · "),
        trailing = if (score > 0.0) "${WenquRules.formatHours(score)} h" else null,
        trailingColor = MaterialTheme.colorScheme.primary,
        onClickLabel = if (onClick != null) "查看这条记录的详情" else null,
        onClick = onClick,
    )
}
