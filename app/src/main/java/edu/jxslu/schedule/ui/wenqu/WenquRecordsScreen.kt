package edu.jxslu.schedule.ui.wenqu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.ui.common.AppCard
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.FooterDisclaimer
import edu.jxslu.schedule.ui.common.LoadingHint
import edu.jxslu.schedule.ui.common.showNotice
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Refresh01

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
 * 只读：报名、签到、提交等写操作请到微信小程序内完成。
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
                actions = {
                    if (state.loading || state.refreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 14.dp).size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        IconButton(onClick = { vm.load(force = true) }) {
                            Icon(HugeIcons.Refresh01, contentDescription = "刷新")
                        }
                    }
                },
            )
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        if (state.loading && state.records.isEmpty()) {
            LoadingHint("正在读取志愿记录", modifier = Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "count") {
                Text(
                    text = "共 ${state.records.size} 条记录 · 点任一条看详情",
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
                items(state.records, key = { "rec-${it.id}" }) { record ->
                    RecordCard(record = record, onClick = { vm.openDetail(record) })
                }
            }

            item(key = "disclaimer") {
                FooterDisclaimer(
                    "记录与工时来自「问渠」实践教育平台（第三方系统，非学校官方功能）。" +
                        "只读展示；如需申诉或修改，请到微信小程序内联系活动单位。",
                    modifier = Modifier.padding(top = 4.dp),
                )
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
        )
    }
}
