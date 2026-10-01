package edu.jxslu.schedule.ui.selection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.repo.SelectionGrabStore
import edu.jxslu.schedule.domain.SelectionGrabPolicy
import edu.jxslu.schedule.domain.SelectionRound
import edu.jxslu.schedule.domain.SelectionRoundPhase
import edu.jxslu.schedule.domain.SelectionRounds
import edu.jxslu.schedule.ui.common.AppCard
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.SettingItem
import edu.jxslu.schedule.ui.common.SettingsSection
import edu.jxslu.schedule.ui.common.WheelValueDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 抢课面板（DESIGN §4.36，我的 → 学习 → 选课 → 抢课）。
 *
 * 三块：**状态**（轮次 / 目标 / 进度 / 开始-停止）、**设置**（检查间隔 · 抢课须知）、
 * **日志**（[SelectionGrabStore] 的最近事件，服务写、这里读）。
 *
 * 「开始」的条件是**全部满足**：接口已接入（窗口期联调后）+ 有进行中的轮次 + 清单非空 +
 * 会话未在跑。任何一个不满足都给出**具体原因**（而不是灰着按钮不解释）。
 * 首次进面板自动弹一次「抢课须知」（写操作确认，落 DataStore）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionGrabScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Graph.displayPrefs(context) }
    val client = remember { Graph.selectionCenterClient(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val grab by SelectionGrabStore.state.collectAsStateWithLifecycle()
    val wishes by prefs.selectionWishes.collectAsStateWithLifecycle(initialValue = emptyList())
    val intervalMs by prefs.selectionGrabIntervalFlow.collectAsStateWithLifecycle(
        initialValue = SelectionGrabPolicy.INTERVAL_DEFAULT_MS,
    )
    // null = 首帧未到（不弹须知）；0 = 没确认过
    val disclaimerAt by prefs.selectionGrabDisclaimerFlow.collectAsStateWithLifecycle(initialValue = null)

    var rounds by remember { mutableStateOf<List<SelectionRound>>(emptyList()) }
    LaunchedEffect(Unit) {
        rounds = withContext(Dispatchers.IO) { Graph.selectionSync(context).cachedRounds() }
    }
    val activeRound = remember(rounds) {
        val now = System.currentTimeMillis()
        rounds.firstOrNull { SelectionRounds.phase(it, now) == SelectionRoundPhase.Active }
    }

    var showDisclaimer by remember { mutableStateOf(false) }
    var showIntervalPicker by remember { mutableStateOf(false) }
    LaunchedEffect(disclaimerAt) {
        if (disclaimerAt == 0L) showDisclaimer = true
    }

    val missingReason = when {
        !client.isConfigured -> "接口待接入：课程列表与提交要等选课窗口期实测（下个学期）"
        activeRound == null -> "当前没有进行中的选课轮次"
        wishes.isEmpty() -> "预选清单还是空的，先去录几条"
        else -> null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("抢课", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                AppCard {
                    Text(
                        if (grab.running) "抢课进行中" else "抢课",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val roundLine = activeRound?.let { round ->
                        "${round.name}（进行中）" + round.timeText.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                    } ?: "当前没有进行中的轮次"
                    Text(
                        "轮次：$roundLine",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        "目标：预选清单 ${wishes.size} 条" +
                            if (grab.targetCount > 0) " · 已抢到 ${grab.grabbedCount}/${grab.targetCount}" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    grab.lastStop?.takeIf { !grab.running }?.let { last ->
                        Text(
                            "上次结果：$last",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    missingReason?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (grab.running) {
                            OutlinedButton(
                                onClick = { SelectionGrabService.stop(context) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(42.dp),
                            ) { Text("停止") }
                        } else {
                            Button(
                                onClick = {
                                    val round = activeRound
                                    if (round == null || missingReason != null) return@Button
                                    if (disclaimerAt == 0L) {
                                        showDisclaimer = true
                                    } else {
                                        SelectionGrabService.start(
                                            context,
                                            roundId = round.id,
                                            roundName = round.name,
                                            roundEndAt = round.endAt,
                                        )
                                    }
                                },
                                enabled = missingReason == null,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(42.dp),
                            ) { Text("开始抢课") }
                        }
                    }
                }
            }

            item {
                SettingsSection(title = "抢课设置") {
                    SettingItem(
                        title = "检查间隔",
                        subtitle = "每轮拉一次课程列表；连续失败会自动退避并最终停止",
                        value = SelectionGrabPolicy.intervalLabel(intervalMs),
                        enabled = !grab.running,
                        onClick = { showIntervalPicker = true },
                    )
                    SettingItem(
                        title = "抢课须知",
                        subtitle = "写操作与风险声明（首次使用必须确认）",
                        onClick = { showDisclaimer = true },
                    )
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "日志",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { SelectionGrabStore.clearLog() }) { Text("清空") }
                }
            }
            if (grab.log.isEmpty()) {
                item {
                    Text(
                        "开始抢课后，这里显示每一轮检查与提交结果。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            } else {
                // 日志按条渲染，不用 key：同一秒可能出现完全相同的两行（如连续两次被拒），
                // 以内容当 key 会撞车让 LazyColumn 直接抛异常
                itemsIndexed(grab.log) { _, line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }
    }

    if (showIntervalPicker) {
        WheelValueDialog(
            title = "检查间隔",
            values = SelectionGrabPolicy.INTERVAL_CHOICES_MS.map { SelectionGrabPolicy.intervalLabel(it) },
            initialIndex = SelectionGrabPolicy.intervalChoiceIndex(intervalMs),
            onConfirm = { index ->
                showIntervalPicker = false
                val picked = SelectionGrabPolicy.INTERVAL_CHOICES_MS[index]
                scope.launch { prefs.setSelectionGrabIntervalMs(picked) }
            },
            onDismiss = { showIntervalPicker = false },
        )
    }

    if (showDisclaimer) {
        GrabDisclaimerDialog(
            onDismiss = { showDisclaimer = false },
            onConfirm = {
                showDisclaimer = false
                scope.launch {
                    prefs.setSelectionGrabDisclaimerAt(System.currentTimeMillis())
                }
            },
        )
    }
}

/**
 * 抢课须知的四条（写操作风险，DESIGN §4.36）：首次必须显式确认，确认后不再弹
 * （面板里保留「抢课须知」入口随时可看）。确认按钮有 3 秒阅读锁。
 */
@Composable
private fun GrabDisclaimerDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(3_000)
        ready = true
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("抢课须知") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "1. 抢课会用你保存的统一认证账号向教务提交选课请求——这是本 App 首次对教务做写操作。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "2. 自动化选课是否被学校允许请自行确认；被教务风控的后果由你承担。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "3. App 只提交预选清单匹配到的课程，只加课、不自动退课；" +
                        "不并发、按固定间隔轮询，连续失败会自动停止。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "4. 抢课不保证成功（名额与并发由教务决定）；遇到验证码或异常页面会立即停止并通知你。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = ready) {
                Text(if (ready) "我已知晓" else "请阅读…")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
