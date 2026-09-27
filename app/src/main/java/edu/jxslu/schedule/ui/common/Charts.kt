package edu.jxslu.schedule.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 统计页共用的两个图形件（2026-09-26）。
 *
 * 此前「近 12 个月支出」「近 12 个月充值」「逐日用电」三处各写一份自绘 `Canvas`
 * （`StatementScreen.MonthlyBars` / `PowerBillScreen.MonthlyBars` /
 * `PowerUsagePanel.UsageBars`），柱宽、透明度、轴标签各不相同，改一处漏两处。
 * 现在只留 [AppBarChart] 一份实现，三个页面共用。
 *
 * **数值标签放在标题行右侧，不在柱顶**：柱状图一年 12 根、一屏宽 360dp，
 * 每格只有约 24dp，柱顶浮标会被 Compose 的文本宽度裁成「¥2…」。标题行右侧
 * 有整行宽度可放「2026 年 9 月 · ¥201.11」，读起来也更明确。柱高本身仍随
 * 选中态高亮，看不出选中哪根时点一下即可。
 */

/** 柱状图的一条数据。[value] 单位由调用方决定（分 / 度），只用于比高矮。 */
data class BarChartItem(
    /** 稳定标识（月份键 / 桶键），选中态按它匹配。 */
    val key: String,
    val value: Float,
    /** 无障碍与长按提示（如「2026 年 9 月」）。 */
    val title: String,
    /** 轴下端的短标签；只有首尾两根会被渲染。 */
    val axisLabel: String,
)

/**
 * 主色柱状图（[AppBarChart]）。
 *
 * [selectedKey] 命中哪根，哪根用实色主色，其余 35% 透明；[onSelect] 非 null 时每根可点，
 * 点击回传 key（消费流水 / 缴费账单用它切月，用电统计用它选桶）。
 *
 * [emptyText] 用于「整段窗口都没有数据」：柱高全为 0 时显示它，而不是排一列看不见的柱子。
 */
@Composable
fun AppBarChart(
    items: List<BarChartItem>,
    selectedKey: String?,
    modifier: Modifier = Modifier,
    header: String? = null,
    selectedText: String? = null,
    emptyText: String? = null,
    barAreaHeight: Dp = 72.dp,
    onSelect: ((String) -> Unit)? = null,
) {
    if (items.isEmpty()) return
    val maxValue = items.maxOf { it.value }
    // 整段窗口都没有数据时不显示选中值：今天 · 0.00 度 配一行「还没有可用量」自相矛盾
    val selectedLabel = if (maxValue <= 0f && emptyText != null) null else selectedText
    val haptics = rememberAppHaptics()
    val onSurface = MaterialTheme.colorScheme.onSurface
    val barColor = MaterialTheme.colorScheme.primary
    Column(modifier) {
        if (header != null || selectedLabel != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = header.orEmpty(),
                    style = MaterialTheme.typography.labelLarge,
                    color = onSurface.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (selectedLabel != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = selectedLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = barColor,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        if (maxValue <= 0f && emptyText != null) {
            Text(
                text = emptyText,
                style = MaterialTheme.typography.bodySmall,
                color = onSurface.copy(alpha = 0.45f),
            )
            return@Column
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(barAreaHeight),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            items.forEach { item ->
                val selected = item.key == selectedKey
                val ratio = if (maxValue > 0f) (item.value / maxValue).coerceIn(0f, 1f) else 0f
                // 高度按比例给，有量但很小时留 3dp 的可见高度（0 = 真的没有，不画）
                val height = if (item.value <= 0f) 0.dp else (barAreaHeight * ratio).coerceAtLeast(3.dp)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .then(
                            if (onSelect == null) {
                                Modifier
                            } else {
                                // 触摸区 = 整格（柱很细时也好点），点击回传 key
                                Modifier.clickable(onClickLabel = item.title) {
                                    haptics.tap()
                                    onSelect(item.key)
                                }
                            },
                        ),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.62f)
                            .height(height)
                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 2.dp, bottomEnd = 2.dp))
                            .background(if (selected) barColor else barColor.copy(alpha = 0.35f)),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = items.first().axisLabel,
                style = MaterialTheme.typography.labelSmall,
                color = onSurface.copy(alpha = 0.4f),
            )
            Text(
                text = items.last().axisLabel,
                style = MaterialTheme.typography.labelSmall,
                color = onSurface.copy(alpha = 0.4f),
            )
        }
    }
}

/**
 * 占比条（消费流水页「支出构成」用）。
 *
 * [fraction] 是相对**本组最大值**的比例（不是相对总额）：一排条里最高那条总是占满，
 * 其余按比例缩短，读的是「哪一类占大头」。金额与百分比写在 [valueText] 里，由调用方拼。
 */
@Composable
fun AppRatioBar(
    label: String,
    valueText: String,
    fraction: Float,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
) {
    val barColor = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    Column(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.bodySmall,
                color = onSurface.copy(alpha = 0.7f),
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(5.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(onSurface.copy(alpha = 0.08f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (highlight) barColor else barColor.copy(alpha = 0.35f)),
            )
        }
    }
}
