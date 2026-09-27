package edu.jxslu.schedule.ui.life

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import edu.jxslu.schedule.data.power.PowerBill
import edu.jxslu.schedule.data.power.PowerModels
import edu.jxslu.schedule.domain.BalanceAlert
import edu.jxslu.schedule.domain.PowerUsage
import edu.jxslu.schedule.domain.PowerUsageBucket
import edu.jxslu.schedule.domain.PowerUsageRange
import edu.jxslu.schedule.domain.PowerUsageSummary
import edu.jxslu.schedule.ui.common.AppBarChart
import edu.jxslu.schedule.ui.common.AppCard
import edu.jxslu.schedule.ui.common.AppCardDivider
import edu.jxslu.schedule.ui.common.BarChartItem
import edu.jxslu.schedule.ui.common.InlineNoticeRow
import edu.jxslu.schedule.ui.common.NoticeTone
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 缴费账单页的「用电统计」分页（DESIGN §3.13）。
 *
 * 数字全部来自 `domain/PowerUsage`（本机读数差分 + 跨天均摊），这里只管画：
 * 一张汇总卡（剩余电量 + 档位 + 窗口合计 + 柱状）＋ 一份逐桶列表 ＋ 口径说明。
 * 没有读数时给的是「怎么才能有数据」的空态，而不是一行「暂无数据」。
 *
 * 2026-09-26 改：柱状换 `AppBarChart`（与另两处统计同款），柱子可点、选中格在标题行显示数值；
 * 逐桶列表收进一张卡（行间细线），选中的那一行带一层浅底——此前一行一张卡，纵向浪费一半。
 */
@OptIn(ExperimentalMaterial3Api::class)
internal fun LazyListScope.powerUsageItems(
    state: PowerUsageUiState,
    onSelectRange: (PowerUsageRange) -> Unit,
    onSelectBucket: (String) -> Unit,
) {
    val summary = state.summary
    item(key = "usage-summary") {
        UsageSummaryCard(
            state = state,
            onSelectRange = onSelectRange,
            onSelectBucket = onSelectBucket,
        )
    }

    if (summary == null || summary.readingCount < 2) {
        item(key = "usage-empty") { UsageEmptyHint(hasReading = summary != null) }
        return
    }

    summary.skippedSegments.takeIf { it > 0 }?.let { skipped ->
        item(key = "usage-skipped") {
            InlineNoticeRow(
                message = "有 $skipped 段读数对不上（充值流水缺失或电表改过数），这几段的用量没计入",
                tone = NoticeTone.Warning,
            )
        }
    }

    val recorded = summary.buckets.filter { it.usedKwh > 0.0 || it.rechargeFen != 0L }
    val selectedKey = state.selectedKey ?: summary.buckets.lastOrNull()?.key
    if (recorded.isEmpty()) {
        item(key = "usage-none") {
            Text(
                text = "这段时间还没有可用量",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
            )
        }
    } else {
        item(key = "usage-header") {
            Text(
                text = "逐${rangeLabel(state.range)}用量",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 8.dp),
            )
        }
        item(key = "usage-rows") {
            AppCard(contentPadding = PaddingValues(0.dp)) {
                recorded.reversed().forEachIndexed { index, bucket ->
                    if (index > 0) AppCardDivider()
                    UsageBucketRow(
                        range = state.range,
                        bucket = bucket,
                        selected = bucket.key == selectedKey,
                    )
                }
            }
        }
    }

    item(key = "usage-footnote") { UsageFootnote(summary) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UsageSummaryCard(
    state: PowerUsageUiState,
    onSelectRange: (PowerUsageRange) -> Unit,
    onSelectBucket: (String) -> Unit,
) {
    val summary = state.summary
    val range = state.range
    val onSurface = MaterialTheme.colorScheme.onSurface
    AppCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        val latest = summary?.latest
        if (latest == null) {
            Text(
                text = "还没有读数",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "${PowerUsage.kwhText(latest.remainKwh)} 度",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "剩余电量 · ${readTimeText(latest.epochMs)} 读数",
                        style = MaterialTheme.typography.bodySmall,
                        color = onSurface.copy(alpha = 0.55f),
                    )
                }
                // 折算口径与生活页电费卡同一处（domain/BalanceAlert.remainingYuan）：
                // 单价缺失就不给金额，不按默认单价编一个数
                BalanceAlert.remainingYuan(latest.remainKwh, latest.priceYuan.takeIf { it > 0 })
                    ?.let { yuan ->
                        Text(
                            text = "≈ ${PowerBill.amountText(PowerModels.fen(yuan))}",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
            }
        }

        Spacer(Modifier.height(12.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            PowerUsageRange.entries.forEachIndexed { index, item ->
                SegmentedButton(
                    selected = item == range,
                    onClick = { onSelectRange(item) },
                    // 不显示选中对勾：选中段自带填充色（与设置页分段控件同口径）
                    icon = {},
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = PowerUsageRange.entries.size),
                    label = { Text(rangeLabel(item), style = MaterialTheme.typography.labelMedium) },
                    modifier = Modifier.height(36.dp),
                )
            }
        }

        if (summary != null && summary.readingCount >= 2) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = windowTotalText(summary, range),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            val recharge = summary.totalRechargeFen
            if (recharge != 0L) {
                Text(
                    text = "期间充值 ${PowerBill.amountText(recharge)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = onSurface.copy(alpha = 0.55f),
                )
            }

            val buckets = summary.buckets
            val selectedKey = state.selectedKey ?: buckets.lastOrNull()?.key
            val selectedBucket = buckets.firstOrNull { it.key == selectedKey }
            val today = LocalDate.now()
            Spacer(Modifier.height(14.dp))
            AppBarChart(
                items = buckets.mapIndexed { index, bucket ->
                    BarChartItem(
                        key = bucket.key,
                        value = bucket.usedKwh.toFloat(),
                        title = PowerUsage.labelOf(range, bucket.key, today),
                        axisLabel = when (index) {
                            0 -> axisText(range, bucket.key)
                            buckets.lastIndex -> "${axisText(range, bucket.key)}（今）"
                            else -> axisText(range, bucket.key)
                        },
                    )
                },
                selectedKey = selectedKey,
                header = "用电量（度）",
                selectedText = selectedBucket?.let { bucket ->
                    val head = "${PowerUsage.labelOf(range, bucket.key, today)} · " +
                        "${PowerUsage.kwhText(bucket.usedKwh)} 度"
                    bucket.usedYuan?.let { yuan -> "$head · ≈ ${PowerBill.amountText(PowerModels.fen(yuan))}" } ?: head
                },
                emptyText = "这段时间还没有可用量",
                onSelect = onSelectBucket,
            )
        }
    }
}

/** 窗口合计文案：`近 14 天用电 3.21 度 · ≈ ¥1.99`（单价未知时只报度数）。 */
private fun windowTotalText(summary: PowerUsageSummary, range: PowerUsageRange): String {
    val head = "${rangeWindowLabel(range)}用电 ${PowerUsage.kwhText(summary.totalKwh)} 度"
    val yuan = summary.totalYuan ?: return head
    return "$head · ≈ ${PowerBill.amountText(PowerModels.fen(yuan))}"
}

/**
 * 一个桶一行：标签 · 度数 / 金额（充值另起一行小注）。
 *
 * [selected] 是柱状图选中的那一格：加一层浅底，柱与行互相对得上。
 */
@Composable
private fun UsageBucketRow(range: PowerUsageRange, bucket: PowerUsageBucket, selected: Boolean) {
    val today = LocalDate.now()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                } else {
                    androidx.compose.ui.graphics.Color.Transparent
                },
            )
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = PowerUsage.labelOf(range, bucket.key, today),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            if (bucket.rechargeFen != 0L) {
                Text(
                    text = "充值 ${PowerBill.amountText(bucket.rechargeFen)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "${PowerUsage.kwhText(bucket.usedKwh)} 度",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            bucket.usedYuan?.let { yuan ->
                Text(
                    text = "≈ ${PowerBill.amountText(PowerModels.fen(yuan))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }
        }
    }
}

/** 空态：一句「为什么现在没数字」＋ 一句「怎么才有」。 */
@Composable
private fun UsageEmptyHint(hasReading: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = if (hasReading) "再记一条读数就能算出用量" else "还没有电表读数",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "用电量由本机记录的读数差分推算：打开生活页、点电费卡刷新、" +
                "下拉刷新本页都会记一条。攒够两条才会出现曲线。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            textAlign = TextAlign.Center,
        )
    }
}

/** 页脚口径说明：把「这是推算值」讲清楚，别让用户拿它当电费单据。 */
@Composable
private fun UsageFootnote(summary: PowerUsageSummary) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        InlineNoticeRow(
            message = "本机共 ${summary.readingCount} 条读数，最早一条距今 ${summary.spanDays} 天。",
            tone = NoticeTone.Info,
        )
        Text(
            text = "平台只提供当前剩余电量，没有用电量接口；这里按「上次度数 + 期间充值度数 − " +
                "这次度数」推算，两次读数之间按天数均摊。读数疏时按天看到的是均摊值，" +
                "充值登记或电表改数会让某几段对不上（已跳过）。仅供参考，以缴费平台为准。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
        )
    }
}

private fun rangeLabel(range: PowerUsageRange): String = when (range) {
    PowerUsageRange.Day -> "日"
    PowerUsageRange.Week -> "周"
    PowerUsageRange.Month -> "月"
}

private fun rangeWindowLabel(range: PowerUsageRange): String = when (range) {
    PowerUsageRange.Day -> "近 ${PowerUsage.windowOf(range)} 天"
    PowerUsageRange.Week -> "近 ${PowerUsage.windowOf(range)} 周"
    PowerUsageRange.Month -> "近 ${PowerUsage.windowOf(range)} 个月"
}

private fun axisText(range: PowerUsageRange, key: String): String {
    val label = PowerUsage.axisLabel(range, key)
    return when (range) {
        PowerUsageRange.Day -> "$label 日"
        PowerUsageRange.Month -> "$label 月"
        PowerUsageRange.Week -> label
    }
}

private fun readTimeText(epochMs: Long): String = runCatching {
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
        .let { "%02d-%02d %02d:%02d".format(it.monthValue, it.dayOfMonth, it.hour, it.minute) }
}.getOrDefault("—")
