package edu.jxslu.schedule.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.YearMonth
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ChevronDown
import me.rerere.hugeicons.stroke.ChevronLeft
import me.rerere.hugeicons.stroke.ChevronRight

/**
 * 月份导航与月份选择（消费流水页 / 缴费账单页共用，2026-09-26）。
 *
 * 此前两页都是 `TextButton("‹")` / `TextButton("›")` 逐月点：默认 48dp 触摸高度把标题行
 * 撑高，翻到十个月前要点十次，且没有回到当月的快捷。现在箭头换 40dp 图标按钮，
 * 标题可点开 [MonthPickerDialog] 直接跳月，另给一枚「本月」（已在当月时不出现）。
 */

/**
 * 月份导航行：`‹  2026 年 9 月 ⌄  ›` + 右侧「本月」。
 *
 * [showThisMonth] 为 false 时右侧按钮整块不渲染——调用方在「已经在当月」时传 false，
 * 免得留一个点了没反应的死按钮。
 */
@Composable
fun MonthNavRow(
    label: String,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
    prevEnabled: Boolean = true,
    nextEnabled: Boolean = true,
    showThisMonth: Boolean = false,
    onThisMonth: () -> Unit = {},
) {
    val haptics = rememberAppHaptics()
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { haptics.tap(); onPrev() }, enabled = prevEnabled) {
            Icon(HugeIcons.ChevronLeft, contentDescription = "上一个月")
        }
        TextButton(
            onClick = { haptics.tap(); onPick() },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                HugeIcons.ChevronDown,
                contentDescription = "选择月份",
                modifier = Modifier.width(16.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }
        IconButton(onClick = { haptics.tap(); onNext() }, enabled = nextEnabled) {
            Icon(HugeIcons.ChevronRight, contentDescription = "下一个月")
        }
        Spacer(Modifier.weight(1f))
        if (showThisMonth) {
            TextButton(onClick = { haptics.tap(); onThisMonth() }) { Text("本月") }
        }
    }
}

/**
 * 月份选择弹窗：按年份分组，每年一行起、三个月一行。
 *
 * 分组而不是「2025 年 10 月」整串塞进格子：格宽只有约 100dp，整串在字体放大档位下会换行；
 * 年份抬头 + 「10 月」的格子在任何字号下都读得下。
 */
@Composable
fun MonthPickerDialog(
    months: List<YearMonth>,
    selected: YearMonth,
    onDismiss: () -> Unit,
    onSelect: (YearMonth) -> Unit,
    title: String = "选择月份",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                months.groupBy { it.year }.forEach { (year, listOfYear) ->
                    Text(
                        text = "$year 年",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    listOfYear.chunked(3).forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            row.forEach { month ->
                                MonthCell(
                                    label = "${month.monthValue} 月",
                                    selected = month == selected,
                                    onClick = { onSelect(month) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            // 当年最后一行不足 3 个：补空位，格子宽度与上一行对齐
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

@Composable
private fun MonthCell(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberAppHaptics()
    val shape = RoundedCornerShape(11.dp)
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .height(40.dp)
            .clip(shape)
            .background(if (selected) primary.copy(alpha = 0.12f) else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (selected) Color.Transparent else MaterialTheme.colorScheme.outlineVariant,
                shape = shape,
            )
            .clickable(onClickLabel = label) { haptics.tap(); onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
