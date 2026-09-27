package edu.jxslu.schedule.ui.campus

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.jxslu.schedule.data.local.TypeAmountRow
import edu.jxslu.schedule.data.local.YktTurnoverEntity
import edu.jxslu.schedule.ui.common.AppBarChart
import edu.jxslu.schedule.ui.common.AppCard
import edu.jxslu.schedule.ui.common.AppCardDivider
import edu.jxslu.schedule.ui.common.AppNoticeVisuals
import edu.jxslu.schedule.ui.common.AppRatioBar
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.BarChartItem
import edu.jxslu.schedule.ui.common.InlineNoticeRow
import edu.jxslu.schedule.ui.common.MonthNavRow
import edu.jxslu.schedule.ui.common.MonthPickerDialog
import edu.jxslu.schedule.ui.common.NoticeTone
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.CreditCard
import me.rerere.hugeicons.stroke.Receipt

/**
 * 消费流水页（DESIGN §4.19 L3/L4）：本地库优先（秒开、离线可查）+ 下拉/进页增量同步。
 *
 * 2026-09-26 排版与交互重构，三处与旧版不同：
 *
 * 1. **整页一个 `LazyColumn`**：此前是 `Column` 包「汇总卡 + 列表」，汇总卡钉在屏幕顶部不滚，
 *    纵向吃掉约 300dp、列表只剩半屏。现在汇总卡是列表的第一个 item，跟着滚走。
 * 2. **汇总卡换 `AppCard`**：此前手写 `clip + border` 画卡片，与缴费账单页的观感不一致。
 * 3. **月份可跳**：`‹ ›` 文字按钮换图标按钮，标题点开月份网格，另加「本月」快捷；
 *    月度柱状图可点，点哪根切到哪个月。
 *
 * 明细列表按自然日分组、每组一张卡；筛选是本地过滤（当前月已全部在内存里，
 * 不发请求、不动 DAO）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatementScreen(
    onBack: () -> Unit = {},
    /** 详情弹层内「凭证/开关」引导跳设置页（凭证缺失时）。 */
    onOpenSettings: () -> Unit = {},
    viewModel: StatementViewModel = viewModel(factory = StatementViewModel.Factory(LocalContext.current)),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val haptics = rememberAppHaptics()

    // 枚举名存 String：rememberSaveable 不认自定义枚举，存名字最省事
    var filterName by rememberSaveable { mutableStateOf(StatementFilter.All.name) }
    val filter = runCatching { StatementFilter.valueOf(filterName) }.getOrDefault(StatementFilter.All)

    var showMonthPicker by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<YktTurnoverEntity?>(null) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is StatementEvent.Notice ->
                    snackbar.showSnackbar(AppNoticeVisuals(event.text, tone = event.tone))
            }
        }
    }

    // 按自然日分组：records 已按月倒序，groupBy 保序，分组头与行都不用再排
    val groups = remember(state.records, filter) { state.records.toDayGroups(filter) }
    val windowMonths = viewModel.windowMonths()

    Scaffold(
        snackbarHost = { AppSnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("消费流水") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(HugeIcons.ArrowLeft01, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.syncing,
            onRefresh = {
                haptics.tap()
                viewModel.refresh()
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "summary") {
                    SummaryCard(
                        state = state,
                        windowMonths = windowMonths,
                        monthLabel = viewModel.monthLabel(state.month),
                        onPrev = { viewModel.loadMonth(state.month.minusMonths(1)) },
                        onNext = { viewModel.loadMonth(state.month.plusMonths(1)) },
                        onPickMonth = { viewModel.loadMonth(it) },
                        onOpenPicker = { showMonthPicker = true },
                    )
                }

                item(key = "filter") {
                    FilterRow(
                        filter = filter,
                        onSelect = { filterName = it.name },
                    )
                }

                when {
                    state.noCredentials -> item(key = "no-credentials") {
                        InlineNoticeRow(
                            message = "凭证未配置或已清除，请先在「我的 → 校园卡」开启并验证。已保存的历史账单仍可离线查看。",
                            tone = NoticeTone.Warning,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                            Text("去设置")
                        }
                    }

                    state.error != null && state.records.isEmpty() -> item(key = "error") {
                        InlineNoticeRow(message = state.error.orEmpty(), tone = NoticeTone.Error)
                        if (state.canRetry) {
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { viewModel.retry() }, modifier = Modifier.fillMaxWidth()) {
                                Text("重试")
                            }
                        }
                    }

                    groups.isEmpty() -> item(key = "empty") {
                        EmptyState(
                            syncing = state.syncing,
                            hasRecords = state.records.isNotEmpty(),
                        )
                    }

                    else -> items(groups, key = { it.key }) { group ->
                        DayGroupCard(group = group, onOpenDetail = { detail = it })
                    }
                }
            }
        }
    }

    if (showMonthPicker) {
        MonthPickerDialog(
            months = windowMonths,
            selected = state.month,
            onDismiss = { showMonthPicker = false },
            onSelect = {
                viewModel.loadMonth(it)
                showMonthPicker = false
            },
        )
    }

    detail?.let { record ->
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(onDismissRequest = { detail = null }, sheetState = sheetState) {
            TurnoverDetail(record)
        }
    }
}

/** 流水类型筛选（本地过滤，不发请求）。 */
private enum class StatementFilter(val label: String) {
    All("全部"),
    Expense("消费"),
    Income("充值"),
}

/** 一个自然日的流水分组。 */
private data class DayGroup(
    val key: String,
    val label: String,
    val records: List<YktTurnoverEntity>,
)

private val DAY_KEY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd")

/**
 * 按自然日切成组。
 *
 * 组键取时间原文的日期部分（`jndatetimeStr` 前 10 位）而不是 `jndatetime` 毫秒：
 * 时间原文解析失败时 `jndatetime` 为 0（会全挤进 1970 年一组），原文至少还能按字符串分组。
 */
private fun List<YktTurnoverEntity>.toDayGroups(filter: StatementFilter): List<DayGroup> {
    val out = ArrayList<DayGroup>()
    var currentKey: String? = null
    var bucket = ArrayList<YktTurnoverEntity>()

    fun flush() {
        val key = currentKey ?: return
        val date = runCatching { LocalDate.parse(key, DAY_KEY_FORMAT) }.getOrNull()
        val today = LocalDate.now()
        val label = when (date) {
            null -> key
            today -> "今天"
            today.minusDays(1) -> "昨天"
            else -> "${date.monthValue} 月 ${date.dayOfMonth} 日"
        }
        out.add(DayGroup(key = key, label = label, records = bucket))
    }

    // 显式 `for (record in this)`：写在 buildList/forEach 的 receiver 里，
    // 隐式 receiver 会变成正在构建的那个 list，迭代对象就不是本源流水了
    for (record in this) {
        if (!record.matches(filter)) continue
        val key = record.jndatetimeStr.split(" ").firstOrNull().orEmpty()
        if (key != currentKey) {
            flush()
            currentKey = key
            bucket = ArrayList()
        }
        bucket.add(record)
    }
    flush()
    return out
}

private fun YktTurnoverEntity.matches(filter: StatementFilter): Boolean = when (filter) {
    StatementFilter.All -> true
    StatementFilter.Expense -> !income
    StatementFilter.Income -> income
}

/**
 * 汇总卡：月份导航 + 当月支出/收入 + 支出构成 + 近 12 个月支出柱状。
 *
 * 月切换全在本地（Room 响应式查询换键），零网络；柱状图点哪根切到哪个月。
 */
@Composable
private fun SummaryCard(
    state: StatementUiState,
    windowMonths: List<YearMonth>,
    monthLabel: String,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onPickMonth: (YearMonth) -> Unit,
    onOpenPicker: () -> Unit,
) {
    val month = state.month
    val currentMonth = windowMonths.last()
    val onSurface = MaterialTheme.colorScheme.onSurface
    AppCard {
        MonthNavRow(
            label = monthLabel,
            onPrev = onPrev,
            onNext = onNext,
            onPick = onOpenPicker,
            prevEnabled = month.isAfter(windowMonths.first()),
            nextEnabled = month.isBefore(currentMonth),
            showThisMonth = month != currentMonth,
            onThisMonth = { onPickMonth(currentMonth) },
        )

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            AmountBlock("支出", state.expensesFen, onSurface)
            AmountBlock("收入", state.incomeFen, MaterialTheme.colorScheme.primary)
        }

        // 支出构成 Top3（DESIGN §4.19 L4）：只取正数（支出）项，按金额降序，条长相对本组最大值
        val top = state.byType.filter { it.amountFen > 0 }.take(3)
        if (top.isNotEmpty()) {
            val total = state.byType.filter { it.amountFen > 0 }.sumOf { it.amountFen }.coerceAtLeast(1L)
            val max = top.first().amountFen.coerceAtLeast(1L)
            Spacer(Modifier.height(16.dp))
            Text(
                text = "支出构成",
                style = MaterialTheme.typography.labelLarge,
                color = onSurface.copy(alpha = 0.55f),
            )
            Spacer(Modifier.height(10.dp))
            top.forEachIndexed { index, row ->
                if (index > 0) Spacer(Modifier.height(10.dp))
                AppRatioBar(
                    label = row.type.ifBlank { "其他" },
                    valueText = "¥%.2f · %d%%".format(row.amountFen / 100.0, row.amountFen * 100 / total),
                    fraction = row.amountFen.toFloat() / max.toFloat(),
                    highlight = index == 0,
                )
            }
        }

        // 当月的数走 Room 的实时汇总（expensesFen），其余月走同步后一次性查的年视图：
        // 两者混用会让「选中的那根柱」和标题行的金额对不上
        val currentKey = monthKeyOf(currentMonth)
        val items = windowMonths.mapIndexed { index, item ->
            val key = monthKeyOf(item)
            BarChartItem(
                key = key,
                value = if (key == currentKey) {
                    state.expensesFen.toFloat()
                } else {
                    (state.monthlyExpenses[key] ?: 0L).toFloat()
                },
                title = "${item.year} 年 ${item.monthValue} 月",
                axisLabel = when (index) {
                    0 -> "%d.%d".format(item.year, item.monthValue)
                    windowMonths.lastIndex -> "%d.%d（今）".format(item.year, item.monthValue)
                    else -> "%d".format(item.monthValue)
                },
            )
        }
        val monthFen = if (month == currentMonth) {
            state.expensesFen
        } else {
            state.monthlyExpenses[monthKeyOf(month)] ?: 0L
        }
        Spacer(Modifier.height(16.dp))
        AppBarChart(
            items = items,
            selectedKey = monthKeyOf(month),
            header = "近 12 个月支出",
            selectedText = if (monthFen > 0) "%s · ¥%.2f".format(monthLabel, monthFen / 100.0) else null,
            emptyText = "还没有可统计的支出",
            onSelect = { key ->
                windowMonths.firstOrNull { monthKeyOf(it) == key }?.let(onPickMonth)
            },
        )

        if (state.localCount > 0) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = "本地已存 ${state.localCount} 条（下拉刷新同步最新）",
                style = MaterialTheme.typography.bodySmall,
                color = onSurface.copy(alpha = 0.4f),
            )
        }
    }
}

@Composable
private fun AmountBlock(label: String, fen: Long, color: Color) {
    Column {
        Text(
            text = "¥%.2f".format(fen / 100.0),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        )
    }
}

@Composable
private fun FilterRow(filter: StatementFilter, onSelect: (StatementFilter) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatementFilter.entries.forEach { item ->
            FilterChip(
                selected = item == filter,
                onClick = { onSelect(item) },
                label = { Text(item.label) },
            )
        }
    }
}

/** 一天的流水：分组标题 + 一张装了当天全部行的卡（行间细线，比一行一张卡省纵向空间）。 */
@Composable
private fun DayGroupCard(group: DayGroup, onOpenDetail: (YktTurnoverEntity) -> Unit) {
    Column {
        Text(
            text = group.label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 8.dp),
        )
        AppCard(contentPadding = PaddingValues(0.dp)) {
            group.records.forEachIndexed { index, record ->
                if (index > 0) AppCardDivider()
                TurnoverRow(record = record, onClick = { onOpenDetail(record) })
            }
        }
    }
}

/**
 * 单条流水行：类型 + 「商户 · 时刻」（一行副标题）+ 金额。
 *
 * 时刻并进副标题而不是单占一行（2026-09-26）：一屏从两条变三条，日期已经在分组头上了。
 */
@Composable
private fun TurnoverRow(record: YktTurnoverEntity, onClick: () -> Unit) {
    val haptics = rememberAppHaptics()
    val income = record.income
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "查看详情") {
                haptics.tap()
                onClick()
            }
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (income) HugeIcons.CreditCard else HugeIcons.Receipt,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = record.turnoverType.ifBlank { "交易" },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 顺序 = 时刻 → 商户 → 摘要：摘要里常见「二维码=[40…]」这种超长串，
            // 排在最后被省略号吃掉，前面的时刻与商户还读得到（反过来会把商户挤没）
            val time = record.jndatetimeStr.split(" ").getOrNull(1)?.take(5).orEmpty()
            val secondary = listOfNotNull(
                time.takeIf { it.isNotBlank() },
                record.locationName?.takeIf { it.isNotBlank() },
                record.remark?.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            if (secondary.isNotEmpty()) {
                Text(
                    text = secondary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = (if (income) "+" else "−") + "¥%.2f".format(record.tranamtFen / 100.0),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (income) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 详情弹层内容：完整时间/类型/商户/订单号/余额快照。 */
@Composable
private fun TurnoverDetail(record: YktTurnoverEntity) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = record.turnoverType.ifBlank { "交易" },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        DetailRow("交易时间", record.jndatetimeStr)
        DetailRow("金额", (if (record.income) "+" else "−") + "¥%.2f".format(record.tranamtFen / 100.0))
        record.balanceAfterFen?.let { DetailRow("交易后余额", "¥%.2f".format(it / 100.0)) }
        record.locationName?.let { DetailRow("商户/终端", it) }
        record.remark?.let { DetailRow("摘要", it) }
        record.resume?.takeIf { it != record.remark }?.let { DetailRow("说明", it) }
        DetailRow("订单号", record.orderId)
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            modifier = Modifier.width(92.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun EmptyState(syncing: Boolean, hasRecords: Boolean) {
    AppCard {
        Text(
            text = when {
                syncing -> "正在同步该月流水…"
                hasRecords -> "该类型下没有流水，换「全部」看看"
                else -> "该月没有流水记录"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
        )
    }
}

private fun monthKeyOf(month: YearMonth): String = "%04d-%02d".format(month.year, month.monthValue)
