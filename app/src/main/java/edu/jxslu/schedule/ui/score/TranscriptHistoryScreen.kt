package edu.jxslu.schedule.ui.score

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.domain.TranscriptEntry
import edu.jxslu.schedule.domain.TranscriptHistory
import edu.jxslu.schedule.ui.common.AppCardRow
import edu.jxslu.schedule.ui.common.AppNoticeVisuals
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.EmptyHint
import edu.jxslu.schedule.ui.common.LoadingHint
import edu.jxslu.schedule.ui.common.NoticeTone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 最近导出（DESIGN §3.14 / §4.25）：`filesDir/transcripts/` 里的成绩单，
 * 每条可 打开 / 分享 / 删除，顶部可清空。
 *
 * 列表就是目录本身（文件名里带学期标签与导出时刻），没有第二套索引：
 * 不建 Room 表、不写 JSON 清单，就不会出现「记录还在、文件没了」的分叉。
 *
 * 定位是**最近导出**而不是档案库：最多 [TranscriptHistory.DEFAULT_KEEP] 份，超出的在下次导出时
 * 自动删掉。要长期留存请用「存到下载」或分享出去，页面底部把这句话写给了用户。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriptHistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { Graph.transcriptStore(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // null = 还在读盘（不区分「读取中」和「空」的话，首帧会先闪一下空态）
    var entries by remember { mutableStateOf<List<TranscriptEntry>?>(null) }
    var pendingDelete by remember { mutableStateOf<TranscriptEntry?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    fun notify(message: String, tone: NoticeTone) {
        scope.launch { snackbar.showSnackbar(AppNoticeVisuals(message, tone = tone)) }
    }

    fun refresh() {
        scope.launch { entries = withContext(Dispatchers.IO) { store.recent() } }
    }

    LaunchedEffect(Unit) { refresh() }

    /** 打开/分享前复核文件还在：列表与动作之间有「新导出触发保留策略」的时间差。 */
    fun useFile(entry: TranscriptEntry, action: (Uri) -> Unit) {
        val file = store.existingFile(entry.name)
        if (file == null) {
            notify("「${TranscriptHistory.labelOf(entry.name)}」已经不在了", NoticeTone.Warning)
            refresh()
            return
        }
        action(store.uriOf(file))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("最近导出", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    val list = entries
                    if (list != null && list.isNotEmpty()) {
                        TextButton(onClick = { confirmClear = true }) { Text("清空") }
                    }
                },
            )
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        val list = entries
        when {
            list == null -> LoadingHint(
                title = "正在读取导出记录",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )

            list.isEmpty() -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center,
            ) {
                EmptyHint(
                    title = "还没有导出记录",
                    body = "导出成绩单后会在这里留下最近 ${TranscriptHistory.DEFAULT_KEEP} 份，方便再次分享或另存。\n" +
                        "这里不是档案库，成绩单随时可以从教务处重新导出。",
                    actionLabel = "返回导出",
                    onAction = onBack,
                )
            }

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(list, key = { it.name }) { entry ->
                    HistoryRow(
                        entry = entry,
                        onOpen = { useFile(entry) { uri -> openPdf(context, uri, ::notify) } },
                        onShare = { useFile(entry) { uri -> sharePdf(context, uri, ::notify) } },
                        onDelete = { pendingDelete = entry },
                    )
                }
                item {
                    Column {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "只保留最近 ${TranscriptHistory.DEFAULT_KEEP} 份，超出的会在下次导出时自动清理。" +
                                "要长期留存请「存到下载」或分享出去。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这份成绩单？") },
            text = {
                Text(
                    "「${TranscriptHistory.labelOf(entry.name)}」会从本机删除，删掉就要重新导出。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = entry.name
                        pendingDelete = null
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { store.delete(name) }
                            notify(if (ok) "已删除" else "删除失败", if (ok) NoticeTone.Success else NoticeTone.Error)
                            refresh()
                        }
                    },
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空全部导出记录？") },
            text = { Text("本机保存的 ${entries?.size ?: 0} 份成绩单都会被删除，需要时重新导出即可。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        scope.launch {
                            val removed = withContext(Dispatchers.IO) { store.deleteAll() }
                            notify("已清空 $removed 份", NoticeTone.Success)
                            refresh()
                        }
                    },
                ) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            },
        )
    }
}

/**
 * 一条记录：标签 + 时间与体积；行本身点开（最常用），右侧两个动作按钮。
 *
 * 「打开」不再单独给按钮：行点击已经是打开，再放一个图标就是同一个动作两处入口。
 */
@Composable
private fun HistoryRow(
    entry: TranscriptEntry,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    AppCardRow(onClick = onOpen, onClickLabel = "打开 ${TranscriptHistory.labelOf(entry.name)}") {
        Column(Modifier.weight(1f)) {
            Text(
                TranscriptHistory.labelOf(entry.name),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${TranscriptHistory.timeLabel(entry.modifiedAtMillis)} · " +
                    TranscriptHistory.sizeLabel(entry.sizeBytes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        // 两个动作按钮各自吃掉自己的点击：外面的行点击是「打开」，不能互相穿透
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onShare) {
                Icon(Icons.Filled.Share, contentDescription = "分享", tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}
