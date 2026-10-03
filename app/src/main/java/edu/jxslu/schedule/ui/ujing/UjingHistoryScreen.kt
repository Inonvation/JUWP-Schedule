package edu.jxslu.schedule.ui.ujing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.ujing.UjingHistoryEntry
import edu.jxslu.schedule.domain.UjingState
import edu.jxslu.schedule.ui.common.AppCard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * U净 历史账单（DESIGN §3.23 / §4.37，2026-10-02）：本 App 发起的每一笔订单
 * （下单即入账、状态随刷新更新、取消也算一笔），按最近落定排序，最多 50 笔
 * （[UjingHistoryStore.CAP]）。数据源是本机账单仓——服务端账单接口没有协议样本，
 * 拿到样本前不猜端点。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UjingHistoryScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val store = remember { Graph.ujingHistory(context) }
    val entries by store.state.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("历史账单") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (entries.isNotEmpty()) {
                        TextButton(onClick = { confirmClear = true }) { Text("清空") }
                    }
                },
            )
        },
    ) { padding ->
        if (entries.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "还没有账单",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "扫码下单后，每一笔都会记在这里。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(
                    horizontal = 16.dp,
                    vertical = 12.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(entries, key = { it.orderId }) { entry ->
                    HistoryRow(entry)
                }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空账单") },
            text = { Text("将删除本机保存的全部 ${entries.size} 笔账单记录，不影响订单本身。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    store.clear()
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun HistoryRow(entry: UjingHistoryEntry) {
    val timeText = remember(entry.displayAt) {
        SimpleDateFormat("M月d日 HH:mm", Locale.CHINESE).format(Date(entry.displayAt))
    }
    // 已完成（50）的账单状态行给主色，其余（运行中 / 已取消 / 超时）用中性色
    val done = entry.paid && entry.status == "50"
    AppCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    buildString {
                        append(entry.deviceName.ifBlank { "洗衣机" })
                        if (entry.modelName.isNotBlank()) {
                            append(" · ").append(entry.modelName)
                        }
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    buildString {
                        append(timeText)
                        append(" · ").append(UjingState.statusText(entry.status))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (done) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    },
                )
            }
            if (entry.priceFen > 0) {
                Text(
                    "¥${UjingState.fen2yuan(entry.priceFen)}",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}
