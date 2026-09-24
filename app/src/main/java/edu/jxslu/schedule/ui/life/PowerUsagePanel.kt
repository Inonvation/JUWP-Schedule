package edu.jxslu.schedule.ui.life

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
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
import edu.jxslu.schedule.ui.common.AppCard
import edu.jxslu.schedule.ui.common.InlineNoticeRow
import edu.jxslu.schedule.ui.common.NoticeTone
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 缴费账单页的「用电统计」分页（DESIGN §3.13，2026-09-24）。
 *
 * 数字全部来自 `domain/PowerUsage`（本机读数差分 + 跨天均摊），这里只管画：
 * 一张汇总卡（剩余电量 + 窗口合计 + 档位 + 柱状）＋ 一份逐桶列表 ＋ 口径说明。
 * 没有读数时给的是「怎么才能有数据」的空态，而不是一行「暂无数据」。
 */
@OptIn(ExperimentalMaterial3Api::class)
internal fun LazyListScope.powerUsageItems(
    state: PowerUsageUiState,
    onSelectRange: (PowerUsageRange) -> Unit,
) {
    val summary = state.summary
    item { UsageSummaryCard(state = state, onSelectRange = onSelectRange) }

    if (summary == null || summary.readingCount < 2) {
        item { UsageEmptyHint(hasReading = summary != null) }
        return
    }

    summary.skippedSegments.takeIf { it > 0 }?.let { skipped ->
        item {
            InlineNoticeRow(
                message = "有 $skipped 段读数对不上（充值流水缺失或电表改过数），这几段的用量没计入",
                tone = NoticeTone.Warning,
            )
        }
    }

    val recorded = summary.buckets.filter { it.usedKwh > 0.0 || it.rechargeFen != 0L }
    if (recorded.isEmpty()) {
        item {
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
        items(recorded.reversed(), key = { "usage-${it.key}" }) { bucket -> UsageBucketRow(state.range, bucket) }
    }

    item { UsageFootnote(summary) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UsageSummaryCard(
    state: PowerUsageUiState,
    onSelectRange: (PowerUsageRange) -> Unit,
) {
    val summary = state.summary
    val range = state.range
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
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "剩余电量 · ${readTimeText(latest.epochMs)} 读数",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
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
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }
            Spacer(Modifier.height(10.dp))
            UsageBars(
                buckets = summary.buckets,
                range = range,
                modifier = Modifier.fillMaxWidth(),
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
 * 逐桶柱状（自绘 `Canvas`，与账单页的月度充值柱、消费流水页同款口径，无图表库依赖）。
 *
 * 没有用量的桶留空位不画柱：柱子的存在本身表示「那个桶里有用量」。
 * 当前桶用主题色，其余 35% 透明度；最大值动态缩放，免得某个峰值把其余柱压扁。
 */
@Composable
private fun UsageBars(
    buckets: List<PowerUsageBucket>,
    range: PowerUsageRange,
    modifier: Modifier = Modifier,
) {
    val maxKwh = buckets.maxOfOrNull { it.usedKwh } ?: 0.0
    if (maxKwh <= 0.0) return
    val barColor = MaterialTheme.colorScheme.primary
    val dimColor = barColor.copy(alpha = 0.35f)
    val outline = MaterialTheme.colorScheme.outlineVariant
    val currentKey = buckets.lastOrNull()?.key
    Column(modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            val slot = size.width / buckets.size
            buckets.forEachIndexed { index, bucket ->
                if (bucket.usedKwh <= 0.0) return@forEachIndexed
                val h = (bucket.usedKwh / maxKwh).toFloat() * (size.height - 2f)
                val x = slot * index + slot / 2
                drawLine(
                    color = if (bucket.key == currentKey) barColor else dimColor,
                    start = Offset(x, size.height),
                    end = Offset(x, size.height - h),
                    strokeWidth = slot * 0.55f,
                    cap = StrokeCap.Round,
                )
            }
            drawLine(outline, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f)
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = axisText(range, buckets.first().key),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "${axisText(range, buckets.last().key)}（今）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            )
        }
    }
}

/** 一个桶一行：标签 · 度数 / 金额（充值另起一行小注）。 */
@Composable
private fun UsageBucketRow(range: PowerUsageRange, bucket: PowerUsageBucket) {
    val today = LocalDate.now()
    AppCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
