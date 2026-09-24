package edu.jxslu.schedule.ui.life

import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
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
import edu.jxslu.schedule.ui.common.AppCard
import edu.jxslu.schedule.ui.common.AppNoticeVisuals
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.InlineNoticeRow
import edu.jxslu.schedule.ui.common.LoadingHint
import edu.jxslu.schedule.ui.common.NoticeTone
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.Bolt

/**
 * 缴费账单页（DESIGN §3.13「缴费账单页」，2026-09-24）。
 *
 * 原先这一格跳平台 `/bill` 网页（要重新登录、字号与操作都不是本 App 的），
 * 现在在 App 内看：月切换 + 当月汇总 + 近 12 个月柱状 + 该月明细。
 * 数据来自与生活页「最近流水」同一条流水接口（仓库内存缓存 2 分钟，从生活页点进来通常不发请求）。
 *
 * 两个分页（2026-09-24 加入第二个）：**充值账单**（本文件，平台流水）与**用电统计**
 * （`PowerUsagePanel.kt`，本机读数差分）。两者数据源完全不同，所以分页而不是混成一条列表。
 *
 * 页脚留一个「在缴费平台打开」的兜底入口：平台改版或要看别的收费项目时还有一条路。
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
    // 分页选择是页面局部状态：离开页面就回到默认的「充值账单」，不值得持久化
    var showUsage by remember { mutableStateOf(false) }

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

    Scaffold(
        snackbarHost = { AppSnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
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
            // 两个分页：充值账单（平台流水）与用电统计（本机读数差分）——数据源完全不同，
            // 所以分页而不是同一条列表里换口径
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                listOf("充值账单", "用电统计").forEachIndexed { index, label ->
                    SegmentedButton(
                        selected = showUsage == (index == 1),
                        onClick = {
                            haptics.tap()
                            showUsage = index == 1
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
                        )
                        return@LazyColumn
                    }

                    item {
                        MonthCard(
                            state = state,
                            onPrev = {
                                haptics.tap()
                                viewModel.prevMonth()
                            },
                            onNext = {
                                haptics.tap()
                                viewModel.nextMonth()
                            },
                        )
                    }

                    when {
                        state.noCredentials -> item {
                            NoticeBlock(
                                message = "凭证未配置或已清除，请先在「我的 → 校园卡」开启并验证",
                                actionLabel = "去设置",
                                onAction = onOpenSettings,
                            )
                        }

                        state.error != null && state.rows.isEmpty() -> item {
                            NoticeBlock(
                                message = state.error.orEmpty(),
                                actionLabel = "重试",
                                onAction = { viewModel.refresh() },
                            )
                        }

                        else -> {
                            // 有数据时失败提示不挡列表：留着上次取到的账单，配一条提示
                            state.error?.let { message ->
                                item {
                                    InlineNoticeRow(message = message, tone = NoticeTone.Warning)
                                }
                            }
                            if (state.visibleRows.isEmpty()) {
                                item { EmptyMonth(loading = !state.loaded) }
                            } else {
                                items(state.visibleRows, key = { row -> rowKey(row) }) { row ->
                                    BillRow(row)
                                }
                            }
                        }
                    }

                    item {
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
}

/** 列表 key：`turnoverid` 是平台主键，缺失（脏数据）时退到「时间 + 金额」，避免重复键崩列表。 */
private fun rowKey(row: PowerTurnover): String =
    row.turnoverId?.toString() ?: "${row.dateText}|${row.amountFen}|${row.refund}"

/**
 * 页头卡：月切换 + 当月汇总 + 近 12 个月充值柱状。
 *
 * 汇总与柱状都在本地算（`PowerBill`），切月零网络——这一页只有进页那一次取数。
 */
@Composable
private fun MonthCard(
    state: PowerBillUiState,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    AppCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onPrev, enabled = state.canPrev) { Text("‹") }
            Text(
                text = PowerBill.monthLabel(state.monthKey),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            TextButton(onClick = onNext, enabled = state.canNext) { Text("›") }
        }

        val month = state.month
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            AmountItem(
                label = "充值",
                text = PowerBill.amountText(month?.rechargeFen ?: 0L),
                color = MaterialTheme.colorScheme.primary,
            )
            AmountItem(
                label = "退款",
                text = PowerBill.amountText(month?.refundFen ?: 0L),
                color = MaterialTheme.colorScheme.onSurface,
            )
            AmountItem(
                label = "笔数",
                text = "${month?.count ?: 0} 笔",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }

        MonthlyBars(
            monthlyRechargeFen = state.monthlyRechargeFen,
            monthKeys = state.monthKeys,
            currentKey = state.monthKeys.last(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        )
    }
}

@Composable
private fun AmountItem(
    label: String,
    text: String,
    color: Color,
) {
    Column {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
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

/**
 * 近 12 个月充值柱状（自绘 `Canvas`，与消费流水页同款口径，无图表库依赖）。
 *
 * 无记录的月留空位不画柱：柱子的存在本身就表示「那个月交过费」。
 * 当月柱用主题色，其余 35% 透明度；最大值动态缩放，免得某月峰值把其余柱压扁。
 */
@Composable
private fun MonthlyBars(
    monthlyRechargeFen: Map<String, Long>,
    monthKeys: List<String>,
    currentKey: String,
    modifier: Modifier = Modifier,
) {
    val maxFen = monthlyRechargeFen.values.maxOrNull() ?: 0L
    Column(modifier) {
        Text(
            text = "近 12 个月充值",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        )
        Spacer(Modifier.height(8.dp))
        if (maxFen <= 0L) {
            Text(
                text = "还没有充值记录",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            )
            return@Column
        }
        val barColor = MaterialTheme.colorScheme.primary
        val dimColor = barColor.copy(alpha = 0.35f)
        val outline = MaterialTheme.colorScheme.outlineVariant
        // 柱序 = 月切换窗口，同一份口径
        val keys = monthKeys
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            val slot = size.width / keys.size
            val barWidth = slot * 0.55f
            keys.forEachIndexed { index, key ->
                val fen = monthlyRechargeFen[key] ?: 0L
                val h = if (fen > 0) (fen.toFloat() / maxFen) * (size.height - 2f) else 0f
                if (h <= 0f) return@forEachIndexed
                val x = slot * index + slot / 2
                drawLine(
                    color = if (key == currentKey) barColor else dimColor,
                    start = Offset(x, size.height),
                    end = Offset(x, size.height - h),
                    strokeWidth = barWidth,
                    cap = StrokeCap.Round,
                )
            }
            drawLine(outline, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f)
        }
        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "${PowerBill.shortMonthLabel(keys.first())} 月",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "${PowerBill.shortMonthLabel(keys.last())} 月（今）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            )
        }
    }
}

/** 一条缴费记录：时间 · 房间 / 金额（充值 `+` 主色、退款 `−` 常规色）。 */
@Composable
private fun BillRow(row: PowerTurnover) {
    AppCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (row.refund) "电费退款" else "电费充值",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                val secondary = listOfNotNull(
                    // 「2026-08-25 12:20:23」→「08-25 12:20」：月份已在页头，不重复
                    row.dateText.take(16).substringAfter('-', "").takeIf { it.isNotBlank() },
                    PowerModels.roomLabelOf(row.room),
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
    Text(
        text = "该月没有缴费记录",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
    )
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
