package edu.jxslu.schedule.ui.toolbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.domain.CainiaoQuery
import edu.jxslu.schedule.domain.ShortcutItem
import edu.jxslu.schedule.ui.common.AppCard
import edu.jxslu.schedule.ui.common.AppCardRow
import edu.jxslu.schedule.ui.common.AppNoticeVisuals
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.NoticeTone
import edu.jxslu.schedule.ui.common.ShortcutLauncher
import edu.jxslu.schedule.ui.common.pinnedStatusBars
import edu.jxslu.schedule.ui.common.showNotice
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01

/**
 * 菜鸟批量查（DESIGN §4.47）：贴入多个运单号 → 拉起菜鸟的「三方路由」批量查链接
 * （模板与真机实测口径见 [CainiaoQuery]）。到菜鸟后的动线：搜索页已预填 →
 * 「去批量查询」→ 添加包裹页（全部查完）→「添加到包裹列表」保存 → 取件码页看所有码。
 *
 * 本页不做网络请求、不碰菜鸟账号，只把单号递给菜鸟自己去查。输入清洗口径在
 * [CainiaoQuery.parseInput]（全角转半角 · 只留字母数字连字符 · 长度与含数字门槛 ·
 * 大小写不敏感去重 · 上限 20），被丢掉的「重复 / 杂项 / 超上限」在计数行里报数，
 * 不静默少发。最近提交存本机 DataStore（见
 * [edu.jxslu.schedule.data.prefs.DisplayPrefsStore.updateCainiaoQueryHistory]），点一条回填输入框。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CainiaoQueryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val prefs = remember(context) { Graph.displayPrefs(context) }
    val historyFlow = remember(context) { prefs.cainiaoQueryHistory }
    val history by historyFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    var input by remember { mutableStateOf("") }
    val parsed = remember(input) { CainiaoQuery.parseInput(input) }
    val scroll = rememberScrollState()

    fun send() {
        val items = CainiaoQuery.parseInput(input).numbers
        if (items.isEmpty()) return
        if (context.packageManager.getLaunchIntentForPackage(CainiaoQuery.PKG) == null) {
            scope.launch {
                snackbar.showNotice(AppNoticeVisuals("未安装菜鸟 App", tone = NoticeTone.Error))
            }
            return
        }
        val error = ShortcutLauncher.launch(
            context,
            ShortcutItem(
                id = "cainiao_batch_query",
                name = "菜鸟批量查",
                uri = CainiaoQuery.buildLink(items),
                pkg = CainiaoQuery.PKG,
            ),
        )
        if (error != null) {
            scope.launch {
                snackbar.showNotice(AppNoticeVisuals(error, tone = NoticeTone.Error))
            }
            return
        }
        // 拉起成功才入史：失败重试不该在历史里堆同一串
        scope.launch {
            prefs.updateCainiaoQueryHistory { old ->
                CainiaoQuery.pushHistory(old, CainiaoQuery.entryOf(items))
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                // 钉住版 insets：本页会拉起菜鸟（外部应用），口径同其余工具页
                windowInsets = pinnedStatusBars(),
                title = { Text("菜鸟批量查") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scroll)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    maxLines = 8,
                    placeholder = { Text("粘贴运单号：逗号、换行、空格分隔都行") },
                    trailingIcon = {
                        if (input.isNotEmpty()) {
                            IconButton(onClick = { input = "" }) {
                                Icon(HugeIcons.Cancel01, contentDescription = "清空输入")
                            }
                        }
                    },
                )
                Spacer(Modifier.height(10.dp))
                val junkOnly = input.isNotBlank() && parsed.numbers.isEmpty()
                Text(
                    text = countHint(parsed, input.isBlank()),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (junkOnly) {
                        MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    },
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { send() },
                    enabled = parsed.numbers.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("发送到菜鸟")
                }
            }
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Text("到菜鸟后怎么走", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    "搜索页已按你贴的单号预填，点「去批量查询」→ 核对结果 →「添加到包裹列表」保存；" +
                        "保存后到菜鸟的取件码页，就能看到所有到站包裹的取件码。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
            if (history.isNotEmpty()) {
                Text(
                    text = "最近提交（点一条回填）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 4.dp),
                )
                history.forEach { entry ->
                    AppCardRow(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            input = entry
                            // 输入卡在页顶，回填后滚回去让用户直接看到结果
                            scope.launch { scroll.animateScrollTo(0) }
                        },
                    ) {
                        Text(
                            text = entry,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "${CainiaoQuery.entryCount(entry)} 个",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        )
                        IconButton(
                            onClick = {
                                scope.launch {
                                    prefs.updateCainiaoQueryHistory { old ->
                                        old.filter { it != entry }
                                    }
                                }
                            },
                        ) {
                            Icon(HugeIcons.Cancel01, contentDescription = "删除这条历史")
                        }
                    }
                }
            }
        }
    }
}

/**
 * 计数行文案：识别到几个单号 + 被丢掉的三个计数（有才加）。
 * 一条都没认出来时直接说判据，别让用户对着灰按钮猜。
 */
private fun countHint(parsed: CainiaoQuery.ParseResult, blankInput: Boolean): String {
    if (blankInput) return "一次最多 ${CainiaoQuery.MAX_COUNT} 个"
    val total = parsed.numbers.size + parsed.overflow
    if (total == 0) {
        return "没认出单号——单号一般是至少 ${CainiaoQuery.MIN_LEN} 位的字母数字组合"
    }
    val extras = buildList {
        if (parsed.overflow > 0) add("超上限只发前 ${CainiaoQuery.MAX_COUNT} 个")
        if (parsed.duplicates > 0) add("已去重 ${parsed.duplicates} 个")
        if (parsed.ignored > 0) add("忽略 ${parsed.ignored} 段杂项")
    }
    return "识别到 $total 个单号" + if (extras.isEmpty()) "" else "（${extras.joinToString("，")}）"
}
