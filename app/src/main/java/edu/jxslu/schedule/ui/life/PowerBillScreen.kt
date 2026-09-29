package edu.jxslu.schedule.ui.life

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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import edu.jxslu.schedule.data.power.PowerBill
import edu.jxslu.schedule.data.power.PowerModels
import edu.jxslu.schedule.data.power.PowerTurnover
import edu.jxslu.schedule.ui.common.AppBarChart
import edu.jxslu.schedule.ui.common.AppCard
import edu.jxslu.schedule.ui.common.AppCardDivider
import edu.jxslu.schedule.ui.common.AppNoticeVisuals
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.BarChartItem
import edu.jxslu.schedule.ui.common.InlineNoticeRow
import edu.jxslu.schedule.ui.common.LoadingHint
import edu.jxslu.schedule.ui.common.MonthNavRow
import edu.jxslu.schedule.ui.common.MonthPickerDialog
import edu.jxslu.schedule.ui.common.NoticeTone
import edu.jxslu.schedule.ui.common.pinnedStatusBars
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import java.time.YearMonth
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.Bolt

/**
 * 缴费账单页（DESIGN §3.13「缴费账单页」）。
 *
 * 原先「缴费账单」跳平台 `/bill` 网页（要重新登录、字号与操作都不是本 App 的），
 * 现在在 App 内看：月切换 + 当月汇总 + 近 12 个月柱状 + 该月明细。
 * 数据来自与生活页「最近流水」同一条流水接口（仓库内存缓存 2 分钟，从生活页点进来通常不发请求）。
 *
 * 两个分页：**用电统计**（`PowerUsagePanel.kt`，本机读数差分）与**充值账单**（本文件，平台流水）。
 * 数据源完全不同，所以分页而不是混成一条列表。
 *
 * 2026-09-26 排版与交互重构：
 *
 * 1. 汇总从「充值 / 退款 / 笔数」改成**充值 / 净额 / 笔数**——退款是低频事件，与充值并排
 *    占同样位宽没有意义，现在降成充值下方一行小注，净额直接给出。
 * 2. 明细行**进一张卡**（行间细线）并且**可点开详情**：房间、费用所属月、订单号原先在
 *    数据里却没有出口。
 * 3. 月份导航与柱状图换成与消费流水页同一套组件（`MonthNavRow` / `AppBarChart`）。
 * 4. 页签对调：**用电统计**放第一页、进页默认显示（用户拍板），充值账单退到第二页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PowerBillScreen(
    onBack: () -> Unit = {},
    /** 凭证未开启时的「去设置」入口（一卡通设置页）。 */
    onOpenSettings: () -> Unit = {},
    viewModel: PowerBillViewModel = viewModel(
        factory = PowerBillViewModel.Factory(LocalContext.current),
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = rememberAppHaptics()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // 分页选择是页面局部状态：离开页面就回到默认的「用电统计」，不值得持久化
    var showUsage by remember { mutableStateOf(true) }
    var showMonthPicker by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<PowerTurnover?>(null) }

    val showNotice: (String, NoticeTone) -> Unit = { message, tone ->
        scope.launch { snackbar.showSnackbar(AppNoticeVisuals(message, tone = tone)) }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is PowerBillEvent.Notice ->
                    snackbar.showSnackbar(AppNoticeVisuals(event.text, tone = event.tone))
            }
        }
    }

    val months = remember(state.monthKeys) { state.monthKeys.toYearMonths() }

    Scaffold(
        snackbarHost = { AppSnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                windowInsets = pinnedStatusBars(),
                title = { Text("缴费账单") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(HugeIcons.ArrowLeft01, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // 两个分页：用电统计（本机读数差分）与充值账单（平台流水）——数据源完全不同，
            // 所以分页而不是同一条列表里换口径。用电统计在第一页、进页默认显示（2026-09-26）
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                listOf("用电统计", "充值账单").forEachIndexed { index, label ->
                    SegmentedButton(
                        selected = showUsage == (index == 0),
                        onClick = {
                            haptics.tap()
                            showUsage = index == 0
                        },
                        // 不显示选中对勾：选中段自带填充色（与设置页分段控件同口径）
                        icon = {},
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                        modifier = Modifier.height(36.dp),
                    )
                }
            }

            PullToRefreshBox(
                // 首屏加载不用下拉指示器（列表还在「正在读取」态），只有刷新时才转
                isRefreshing = state.loading && state.loaded,
                onRefresh = {
                    haptics.tap()
                    viewModel.refresh()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (showUsage) {
                        powerUsageItems(
                            state = state.usage,
                            onSelectRange = { range -> viewModel.selectUsageRange(range) },
                            onSelectBucket = { key -> viewModel.selectUsageBucket(key) },
                        )
                        return@LazyColumn
                    }

                    item(key = "month") {
                        MonthCard(
                            state = state,
                            months = months,
                            onPrev = {
                                haptics.tap()
                                viewModel.prevMonth()
                            },
                            onNext = {
                                haptics.tap()
                                viewModel.nextMonth()
                            },
                            onPick = { showMonthPicker = true },
                            onSelectMonth = { viewModel.selectMonth(monthKeyOf(it)) },
                        )
                    }

                    when {
                        state.noCredentials -> item(key = "no-credentials") {
                            NoticeBlock(
                                message = "凭证未配置或已清除，请先在「我的 → 校园卡」开启并验证",
                                actionLabel = "去设置",
                                onAction = onOpenSettings,
                            )
                        }

                        state.error != null && state.rows.isEmpty() -> item(key = "error") {
                            NoticeBlock(
                                message = state.error.orEmpty(),
                                actionLabel = "重试",
                                onAction = { viewModel.refresh() },
                            )
                        }

                        else -> {
                            // 有数据时失败提示不挡列表：留着上次取到的账单，配一条提示
                            state.error?.let { message ->
                                item(key = "inline-error") {
                                    InlineNoticeRow(message = message, tone = NoticeTone.Warning)
                                }
                            }
                            if (state.visibleRows.isEmpty()) {
                                item(key = "empty") { EmptyMonth(loading = !state.loaded) }
                            } else {
                                item(key = "rows") {
                                    BillsCard(
                                        rows = state.visibleRows,
                                        onOpenDetail = { detail = it },
                                    )
                                }
                            }
                        }
                    }

                    item(key = "footer") {
                        Footer(
                            onOpenPlatform = {
                                haptics.tap()
                                viewModel.openPlatformPage { url -> openExternal(context, url, showNotice) }
                            },
                        )
                    }
                }
            }
        }
    }

    if (showMonthPicker) {
        MonthPickerDialog(
            months = months,
            selected = runCatching { YearMonth.parse(state.monthKey) }.getOrDefault(YearMonth.now()),
            onDismiss = { showMonthPicker = false },
            onSelect = {
                viewModel.selectMonth(monthKeyOf(it))
                showMonthPicker = false
            },
        )
    }

    detail?.let { row ->
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(onDismissRequest = { detail = null }, sheetState = sheetState) {
            TurnoverDetail(row)
        }
    }
}

private fun List<String>.toYearMonths(): List<YearMonth> =
    mapNotNull { runCatching { YearMonth.parse(it) }.getOrNull() }

private fun monthKeyOf(month: YearMonth): String = "%04d-%02d".format(month.year, month.monthValue)

/**
 * 页头卡：月切换 + 当月汇总 + 近 12 个月充值柱状（`AppBarChart`，与消费流水页同一套）。
 *
 * 汇总与柱状都在本地算（`PowerBill`），切月零网络——这一页只有进页那一次取数。
 */
@Composable
private fun MonthCard(
    state: PowerBillUiState,
    months: List<YearMonth>,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onPick: () -> Unit,
    onSelectMonth: (YearMonth) -> Unit,
) {
    val month = state.month
    val rechargeFen = month?.rechargeFen ?: 0L
    val refundFen = month?.refundFen ?: 0L
    val onSurface = MaterialTheme.colorScheme.onSurface
    val currentKey = state.monthKeys.last()

    AppCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        MonthNavRow(
            label = PowerBill.monthLabel(state.monthKey),
            onPrev = onPrev,
            onNext = onNext,
            onPick = onPick,
            prevEnabled = state.canPrev,
            nextEnabled = state.canNext,
            showThisMonth = state.monthKey != currentKey,
            onThisMonth = { onSelectMonth(months.last()) },
        )

        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            AmountItem("充值", PowerBill.amountText(rechargeFen), MaterialTheme.colorScheme.primary)
            AmountItem("净额", PowerBill.amountText(rechargeFen - refundFen), onSurface)
            AmountItem("笔数", "${month?.count ?: 0} 笔", onSurface.copy(alpha = 0.7f))
        }
        if (refundFen > 0L) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "其中退款 ${PowerBill.amountText(refundFen)}，净额已扣除",
                style = MaterialTheme.typography.bodySmall,
                color = onSurface.copy(alpha = 0.5f),
            )
        }

        val items = months.mapIndexed { index, item ->
            val key = monthKeyOf(item)
            BarChartItem(
                key = key,
                value = (state.monthlyRechargeFen[key] ?: 0L).toFloat(),
                title = PowerBill.monthLabel(key),
                axisLabel = when (index) {
                    0 -> "%d.%d".format(item.year, item.monthValue)
                    months.lastIndex -> "%d.%d（今）".format(item.year, item.monthValue)
                    else -> "%d".format(item.monthValue)
                },
            )
        }
        Spacer(Modifier.height(16.dp))
        AppBarChart(
            items = items,
            selectedKey = state.monthKey,
            header = "近 12 个月充值",
            selectedText = if (rechargeFen > 0L) {
                "${PowerBill.monthLabel(state.monthKey)} · ${PowerBill.amountText(rechargeFen)}"
            } else {
                null
            },
            emptyText = "还没有充值记录",
            onSelect = { key ->
                months.firstOrNull { monthKeyOf(it) == key }?.let(onSelectMonth)
            },
        )
    }
}

@Composable
private fun AmountItem(label: String, text: String, color: Color) {
    Column {
        Text(
            text = text,
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

/** 该月明细：一张卡装全部行，行间细线；整行可点开详情。 */
@Composable
private fun BillsCard(rows: List<PowerTurnover>, onOpenDetail: (PowerTurnover) -> Unit) {
    AppCard(contentPadding = PaddingValues(0.dp)) {
        rows.forEachIndexed { index, row ->
            if (index > 0) AppCardDivider()
            BillRow(row = row, onClick = { onOpenDetail(row) })
        }
    }
}

/** 一条缴费记录：时间 · 房间 / 金额（充值 `+` 主色、退款 `−` 常规色）。 */
@Composable
private fun BillRow(row: PowerTurnover, onClick: () -> Unit) {
    val haptics = rememberAppHaptics()
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
            imageVector = HugeIcons.Bolt,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.tertiary,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = if (row.refund) "电费退款" else "电费充值",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            // 顺序 = 时刻 → 房间 → 支付方式：房间是「充到哪」的主信息，支付方式补位——
            // 平台偶尔不填 `abstracts`（2026-09-28 实测 13 条里 2 条为空，都是电子账户那条
            // 路径），那几行至少还能看出「这笔是怎么付的」，不至于整行没有目标信息。
            val secondary = listOfNotNull(
                // 「2026-08-25 12:20:23」→「08-25 12:20」：月份已在页头，不重复
                row.dateText.take(16).substringAfter('-', "").takeIf { it.isNotBlank() },
                PowerModels.roomLabelOf(row.room),
                PowerModels.payChannelLabelOf(row.payId),
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
            text = (if (row.refund) "−" else "+") + PowerBill.amountText(row.amountFen),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (row.refund) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.primary
            },
        )
    }
}

/** 详情弹层：完整时间 / 金额 / 房间 / 费用所属月 / 订单号。 */
@Composable
private fun TurnoverDetail(row: PowerTurnover) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = if (row.refund) "电费退款" else "电费充值",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        DetailRow("交易时间", row.dateText)
        DetailRow("金额", (if (row.refund) "−" else "+") + PowerBill.amountText(row.amountFen))
        PowerModels.roomLabelOf(row.room)?.let { DetailRow("房间", it) }
        PowerModels.payChannelLabelOf(row.payId)?.let { DetailRow("支付方式", it) }
        feeRangeLabel(row.month)?.let { DetailRow("费用所属月", it) }
        row.turnoverId?.let { DetailRow("订单号", it.toString()) }
    }
}

/**
 * `feerange` 的 `202608` 形态 → 「2026 年 8 月」；认不出就原样给（有值才显示）。
 *
 * 这里是展示口径：与 `PowerBill.monthKeyOf` 的**归属**口径无关（那条是「账单算到哪个月」，
 * 取缴费日期），详情里这一行就是平台登记的「费用所属月」原文。
 */
private fun feeRangeLabel(raw: String?): String? {
    val text = raw.orEmpty().trim()
    if (text.isEmpty()) return null
    if (text.length == 6 && text.all { it.isDigit() }) {
        val month = text.substring(4).trimStart('0').ifEmpty { "0" }
        return "${text.take(4)} 年 $month 月"
    }
    return text
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

/** 空态：该月没有记录 / 还在读第一次。 */
@Composable
private fun EmptyMonth(loading: Boolean) {
    if (loading) {
        LoadingHint(
            title = "正在读取缴费记录",
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp),
        )
        return
    }
    AppCard {
        Text(
            text = "该月没有缴费记录",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
        )
    }
}

/** 提示块（凭证缺失 / 取数失败）：一句话 + 一个出口。 */
@Composable
private fun NoticeBlock(message: String, actionLabel: String, onAction: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        InlineNoticeRow(message = message, tone = NoticeTone.Warning)
        Button(onClick = onAction, modifier = Modifier.fillMaxWidth()) {
            Text(actionLabel)
        }
    }
}

/** 页脚：兜底入口 + 免责。 */
@Composable
private fun Footer(onOpenPlatform: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TextButton(onClick = onOpenPlatform) {
            Icon(HugeIcons.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("在缴费平台打开")
        }
        Text(
            text = "账单数据来自学校缴费平台，仅供参考，以平台网页为准。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            textAlign = TextAlign.Center,
        )
    }
}
