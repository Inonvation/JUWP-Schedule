package edu.jxslu.schedule.ui.wenqu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.wenqu.WenquVolunteerRecord
import edu.jxslu.schedule.domain.WenquRules
import edu.jxslu.schedule.ui.common.AppCard
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.FooterDisclaimer
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import edu.jxslu.schedule.ui.common.showNotice
import kotlinx.coroutines.launch

/**
 * 我的志愿服务记录页（DESIGN §3.28）——问渠主页工时卡那行「共 N 条记录」进来的二级页。
 *
 * **是独立页而不是弹层**（2026-10-08 用户口径）：记录详情本身也要弹层，
 * 弹层里再开弹层会互相打架。所以这里是正常的顶栏 + 列表页，详情才走弹层。
 *
 * 数据来自仓库的 30 分钟缓存（[WenquRecordsViewModel.load]）——从主页点进来通常一个请求都不发。
 * 记录详情复用活动详情弹层（记录的 `id` 就是活动 id，见 `WenquVolunteerRecord.asActivity`），
 * 只是徽章换成「我的报名状态」。
 *
 * 写操作：平台说这条能取消（`canCancel`）时，详情弹层给「取消报名」入口（过二次确认）；
 * 签到、提交材料等其它写操作请到微信小程序内完成。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WenquRecordsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: WenquRecordsViewModel =
        viewModel(factory = WenquRecordsViewModel.Factory(Graph.wenqu(context)))
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val haptics = rememberAppHaptics()

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbar.showNotice(message)
        vm.consumeMessage()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("志愿服务记录", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                // 刷新走**下拉**（2026-10-11，同主页与流水 / 账单 / 生活那几页的
                // `PullToRefreshBox` 口径）：顶栏不再放刷新图标。

            )
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        if (state.loading && state.records.isEmpty()) {
            // 首屏给**与记录卡同几何**的骨架（不是一句转圈提示），数据回来时不跳
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                WenquRecordsSkeleton()
            }
            return@Scaffold
        }

        PullToRefreshBox(
            // 首屏取数不走下拉指示器（上面已有「正在读取志愿记录」），只有刷新时才转
            isRefreshing = state.refreshing,
            onRefresh = {
                haptics.tap()
                vm.load(force = true)
            },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                // 页首一行：条数 + 累计工时——回答「我一共做了多少」，此前要回主页看工时卡
                val totalHours = state.records.sumOf { WenquRules.hoursOf(it.score) }
                item(key = "count") {
                    Text(
                        text = buildString {
                            append("共 ${state.records.size} 条")
                            if (totalHours > 0.0) {
                                append(" · 累计 ${WenquRules.formatHours(totalHours)} 小时")
                            }
                            append(" · 点任一条看详情")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }

                if (state.records.isEmpty()) {
                    item(key = "empty") {
                        AppCard {
                            Text(
                                text = "还没有志愿服务记录。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            )
                        }
                    }
                } else {
                    // 按年份分组（2026-10-11）：记录一多就是一条长流水，加一层年份小标题，
                    // 「上学期做了什么」一眼能找到。年份认不出（平台没给时间）的归到最后。
                    groupedByYear(state.records).forEach { (year, records) ->
                        item(key = "year-${year ?: 0}") {
                            Text(
                                text = year?.let { "$it 年" } ?: "未标注时间",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                        items(records, key = { "rec-${it.id}" }) { record ->
                            RecordCard(record = record, onClick = { vm.openDetail(record) })
                        }
                    }
                }

                item(key = "disclaimer") {
                    FooterDisclaimer(
                        WENQU_FOOTER,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }

    val activity = state.detailActivity()
    if (activity != null) {
        WenquActivitySheet(
            activity = activity,
            detailLoading = state.detailLoading,
            onDismiss = vm::closeDetail,
            // 记录视角：徽章用「我的报名状态」（如「已完成」）而不是活动状态
            recordBadge = state.opened?.statusText,
            detailUnavailable = state.detailUnavailable,
            onNotice = { msg -> scope.launch { snackbar.showNotice(msg) } },
            // 记录视角的取消入口以平台的 `canCancel` 为准（活动列表 / 详情都没这个字段）
            canCancel = state.opened?.canCancel == true,
            busy = state.applying,
            actionError = state.detailActionError,
            onCancel = vm::cancelApply,
        )
    }
}

/**
 * 记录按年份分组（2026-10-11）：年份降序，「未标注时间」（平台没给开始时间 / 解析不了）
 * 永远排最后。组内保持平台原序（记录本来就是时间流，不动它）。
 */
private fun groupedByYear(
    records: List<WenquVolunteerRecord>,
): List<Pair<Int?, List<WenquVolunteerRecord>>> {
    val buckets = LinkedHashMap<Int?, MutableList<WenquVolunteerRecord>>()
    for (record in records) {
        val year = WenquRules.stampYear(record.startTime)
        buckets.getOrPut(year) { mutableListOf() } += record
    }
    return buckets.entries
        .sortedByDescending { it.key ?: Int.MIN_VALUE }
        .map { it.key to it.value.toList() }
}
