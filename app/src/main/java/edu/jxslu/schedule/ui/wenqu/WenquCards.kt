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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import edu.jxslu.schedule.data.wenqu.WenquActivity
import edu.jxslu.schedule.data.wenqu.WenquVolunteerRecord
import edu.jxslu.schedule.domain.WenquRules
import edu.jxslu.schedule.ui.common.AppCard

/**
 * 问渠的条目卡（DESIGN §3.28「统一卡片」）——活动列表、记录列表共用一件。
 *
 * **2026-10-11 重排**（用户报「排版布局」+ 提案页定稿）：
 * 1. 首行 = 状态徽章（+ 可选的「已报名」）· **名额** · **时间（右对齐）**——
 *    时间不再被长单位名挤掉（原先 `单位 · 地点 · 时间` 挤一行、两行截断，长单位名先吃掉时间）；
 * 2. 标题（最多两行，搜索时**命中高亮**）；
 * 3. `单位 · 地点`（最多一行）。
 *
 * 名额走 [WenquRules.cardQuotaText]（只对招募中给「还剩 N」/「已满」），
 * 已结束 / 进行中的卡不显示名额——它们不在可报名的语义里，挂个数字只占位。
 *
 * 徽章观感与 [edu.jxslu.schedule.ui.common.SettingsTag] 同族：低透明底色 + 同色文字。
 */
@Composable
internal fun WenquEntryCard(
    badge: String,
    badgeColor: Color,
    title: String,
    meta: String,
    /** 紧跟徽章的名额（活动卡用；记录卡不给）。 */
    quota: String? = null,
    quotaColor: Color? = null,
    /** 首行右端：活动卡给时间，记录卡给本条工时。 */
    trailing: String? = null,
    trailingColor: Color? = null,
    /** 第二枚徽章（活动卡的「已报名」）——跟在状态徽章后面，不挤掉右侧的时间。 */
    extraBadge: String? = null,
    extraBadgeColor: Color? = null,
    /** 搜索关键词：非空时在标题与 [meta] 里高亮命中段（用户一眼看到为什么这条被搜出来）。 */
    keyword: String = "",
    /** 已结束 / 已取消的卡整体压暗，让「还能报名的」一眼分辨。 */
    dimmed: Boolean = false,
    onClickLabel: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val highlightColor = MaterialTheme.colorScheme.primary
    AppCard(
        modifier = if (dimmed) Modifier.alpha(0.62f) else Modifier,
        onClick = onClick,
        onClickLabel = onClickLabel,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (badge.isNotBlank()) {
                WenquBadge(text = badge, color = badgeColor)
            }
            if (!extraBadge.isNullOrBlank()) {
                Spacer(Modifier.width(6.dp))
                WenquBadge(text = extraBadge, color = extraBadgeColor ?: badgeColor)
            }
            if (!quota.isNullOrBlank()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = quota,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = quotaColor ?: MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.weight(1f))
            if (!trailing.isNullOrBlank()) {
                Text(
                    text = trailing,
                    style = MaterialTheme.typography.labelSmall,
                    color = trailingColor ?: MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = highlighted(text = title, keyword = keyword, color = highlightColor),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (meta.isNotBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(
                text = highlighted(text = meta, keyword = keyword, color = highlightColor),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 命中高亮：把 [keyword] 在 [text] 里命中的段标成主色加粗。
 *
 * 命中位置由 [WenquRules.keywordRanges] 算（与 `matchesKeyword` 同一份归一化）——
 * 那边是纯逻辑、JVM 可测，这里只负责把样式贴上去。
 */
@Composable
private fun highlighted(text: String, keyword: String, color: Color): AnnotatedString =
    remember(text, keyword, color) {
        val ranges = WenquRules.keywordRanges(text, keyword)
        if (ranges.isEmpty()) {
            AnnotatedString(text)
        } else {
            buildAnnotatedString {
                var cursor = 0
                for (range in ranges) {
                    if (range.first > cursor) append(text.substring(cursor, range.first))
                    withStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold)) {
                        append(text.substring(range.first, range.last + 1))
                    }
                    cursor = range.last + 1
                }
                if (cursor < text.length) append(text.substring(cursor))
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
 * 活动卡：状态徽章 +（已报名时）「已报名」+ 名额 + 时间 + 活动名 + `单位 · 地点`。
 *
 * `isApply` 平台一直在回，早先解析了却没展示——报过名的活动和没报名的长得一模一样，
 * 只有点开详情才看到「我的状态」（2026-10-08 用户要的）。
 */
@Composable
internal fun ActivityCard(
    activity: WenquActivity,
    keyword: String = "",
    onClick: () -> Unit,
) {
    val full = WenquRules.isFull(activity.status, activity.peopleNum, activity.balanceNum)
    val quota = WenquRules.cardQuotaText(
        status = activity.status,
        joinNum = activity.joinNum,
        peopleNum = activity.peopleNum,
        balanceNum = activity.balanceNum,
    )
    WenquEntryCard(
        badge = WenquRules.statusLabel(activity.status),
        badgeColor = wenquStatusColor(activity.status),
        extraBadge = if (activity.applied) "已报名" else null,
        extraBadgeColor = MaterialTheme.colorScheme.primary,
        quota = quota.takeIf { it.isNotBlank() },
        quotaColor = if (full) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        title = activity.title.ifBlank { activity.projectName.ifBlank { "志愿服务活动" } },
        meta = listOfNotNull(
            activity.unit.takeIf { it.isNotBlank() },
            activity.address.takeIf { it.isNotBlank() },
        ).joinToString(" · "),
        // 今天 / 明天直接写出来（抢活动的常态），其余给 `MM-dd HH:mm` 那套
        trailing = WenquRules.cardTimeLabel(
            start = activity.startTime,
            end = activity.endTime,
            now = System.currentTimeMillis(),
        ).takeIf { it.isNotBlank() },
        keyword = keyword,
        // 已结束 / 已取消压暗；招募中与进行中保持原样（「还能报名的」一眼分辨）
        dimmed = activity.status != WenquRules.STATUS_RECRUITING && activity.status != 2,
        onClickLabel = "查看活动详情",
        onClick = onClick,
    )
}

/**
 * 记录卡：与活动卡同一件，只是数据来自「我的记录」。
 *
 * 徽章用**我的报名状态**（`apply_status_text`，如「已完成」）而不是活动状态——
 * 记录列表里关心的是「我这条怎么样了」；右侧数字是**本条工时**。
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
        ).joinToString(" · "),
        trailing = if (score > 0.0) "${WenquRules.formatHours(score)} 小时" else null,
        trailingColor = MaterialTheme.colorScheme.primary,
        onClickLabel = if (onClick != null) "查看这条记录的详情" else null,
        onClick = onClick,
    )
}
