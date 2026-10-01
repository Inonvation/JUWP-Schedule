package edu.jxslu.schedule.ui.selection

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.JwImportActivity
import edu.jxslu.schedule.SubpageActivity
import edu.jxslu.schedule.SubpageScreen
import edu.jxslu.schedule.data.repo.SelectionGrabStore
import edu.jxslu.schedule.data.repo.SelectionSync
import edu.jxslu.schedule.domain.CourseSelection
import edu.jxslu.schedule.domain.ScoreAlertDefaults
import edu.jxslu.schedule.domain.SelectionRound
import edu.jxslu.schedule.domain.SelectionRoundPhase
import edu.jxslu.schedule.domain.SelectionRounds
import edu.jxslu.schedule.ui.common.AppCard
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.SettingItem
import edu.jxslu.schedule.ui.common.SettingSwitchRow
import edu.jxslu.schedule.ui.common.SettingsSection
import edu.jxslu.schedule.ui.common.WheelValueDialog
import edu.jxslu.schedule.ui.jwvw.JwImportMode
import edu.jxslu.schedule.ui.reminder.ScoreAlertReminder
import edu.jxslu.schedule.ui.reminder.SelectionAlertReminder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AddToList
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.CheckList
import me.rerere.hugeicons.stroke.Globe
import me.rerere.hugeicons.stroke.Rocket
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 选课（DESIGN §3.19 / §4.35，我的 → 学习 → 选课）。2026-10-01 排版收口：
 * 主页只放「要做的事」，记录与细节全部收进二级页。
 *
 * 三块内容：
 * 1. **轮次卡**：进行中/即将开始的轮次置顶（状态标签 + 确定性时间文案），唯一主按钮
 *    「进入选课」开应用内选课中心（§3.22，轮次 id 随行直达）；其余轮次收成紧凑行。
 *    **教务 WebView 全页只留一个入口**——下方「教务网页」行（备用）；轮次行不再直达教务页。
 * 2. **功能入口**：已选课程（`SELECTION_RECORDS`，记录列表整体挪入）/ 预选清单 /
 *    自动抢课 / 教务网页（备用）。
 * 3. **提醒**：轮次开始前/截止前的定时通知（[SelectionAlertReminder]），默认关。
 *
 * 数据闸门：进页自动同步一次、30 分钟内重复进页直接用缓存（`SelectionSync.MIN_REFRESH_MS`）；
 * 顶栏「更新」= 强制同步。失败不弹窗：轮次卡空态处说明，记录的展示在已选课程页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { Graph.selectionRepository(context) }
    val sync = remember { Graph.selectionSync(context) }
    val prefs = remember { Graph.displayPrefs(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // 已选课程入口行的副标题口径：null = Room 流首帧未到
    val allState by repo.observeAll().collectAsStateWithLifecycle(initialValue = null)

    var rounds by remember { mutableStateOf(sync.cachedRounds()) }
    var lastSyncAt by remember { mutableLongStateOf(sync.cachedFetchedAt()) }
    var refreshing by remember { mutableStateOf(false) }
    var lastError by remember { mutableStateOf<String?>(null) }
    var showIntervalPicker by remember { mutableStateOf(false) }

    val alertEnabled by prefs.selectionAlertEnabled.collectAsStateWithLifecycle(initialValue = false)
    val intervalHours by prefs.alertIntervalHours
        .collectAsStateWithLifecycle(initialValue = ScoreAlertDefaults.INTERVAL_DEFAULT)
    val wishes by prefs.selectionWishes.collectAsStateWithLifecycle(initialValue = emptyList())
    val grab by SelectionGrabStore.state.collectAsStateWithLifecycle()
    val client = remember { Graph.selectionCenterClient(context) }

    // 页面可能被「等开抢」的用户长时间停着：每分钟重算一次相位，
    // 「即将开始 → 进行中」「剩余时间」不至于停在进页那一刻
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = System.currentTimeMillis()
        }
    }
    val target = remember(rounds, now) { SelectionRounds.pickTarget(rounds, now) }
    val others = remember(rounds, target) { rounds.filter { it.id != target?.id } }

    fun refresh(force: Boolean) {
        if (refreshing) return
        refreshing = true
        scope.launch {
            val outcome = sync.refresh(force = force)
            rounds = sync.cachedRounds()
            lastSyncAt = sync.cachedFetchedAt()
            refreshing = false
            lastError = outcome.failedReason
            // 轮次快照刚变过：重排提醒闹钟（开关关着时是撤销闹钟的空跑）
            SelectionAlertReminder.scheduleNext(context)
            // 进页那次自动刷新**失败不进气泡**（轮次卡空态处说明）；手动「更新」才回报结果
            if (force) {
                val results = outcome.results
                snackbar.showSnackbar(
                    when {
                        outcome.failedReason != null -> "同步失败：${outcome.failedReason}"
                        results is SelectionSync.Result.Updated -> "已更新：${results.rowCount} 条选课记录"
                        outcome.rounds is SelectionSync.RoundsResult.Updated -> "选课轮次已更新"
                        else -> "已是最新"
                    },
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        refresh(force = false)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("选课", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { refresh(force = true) }, enabled = !refreshing) {
                        Icon(Icons.Filled.Refresh, contentDescription = "更新")
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
                RoundsCard(
                    target = target,
                    others = others,
                    now = now,
                    syncing = refreshing,
                    lastSyncAt = lastSyncAt,
                    lastError = lastError,
                    onOpenCenter = { round ->
                        // 轮次 id 走二级页 focusItemId 通道直达（§3.22）：中心页先认它，
                        // 认不出（快照已过期）再退回 pickTarget
                        SubpageActivity.start(
                            context,
                            SubpageScreen.SELECTION_CENTER,
                            focusItemId = round?.id,
                        )
                    },
                )
            }
            item {
                SettingsSection(title = "功能") {
                    SettingItem(
                        title = "已选课程",
                        subtitle = recordsSubtitle(allState),
                        icon = HugeIcons.CheckList,
                        onClick = {
                            SubpageActivity.start(context, SubpageScreen.SELECTION_RECORDS)
                        },
                    )
                    SettingItem(
                        title = "预选清单",
                        subtitle = if (wishes.isEmpty()) {
                            "录入想选的课程与教师 · 选课开放后按清单抢课"
                        } else {
                            "已有 ${wishes.size} 条 · 点击编辑"
                        },
                        icon = HugeIcons.AddToList,
                        onClick = {
                            SubpageActivity.start(context, SubpageScreen.SELECTION_WISHES)
                        },
                    )
                    SettingItem(
                        title = "自动抢课",
                        subtitle = grabSubtitle(grab, client.isConfigured),
                        icon = HugeIcons.Rocket,
                        onClick = {
                            SubpageActivity.start(context, SubpageScreen.SELECTION_GRAB)
                        },
                    )
                    SettingItem(
                        title = "教务网页",
                        subtitle = "选课/退课尽量在应用内完成 · 此为备用入口",
                        icon = HugeIcons.Globe,
                        onClick = {
                            // 全页唯一的教务 WebView 入口：开「学生选课中心」列表，
                            // 不带轮次（轮次直达已在轮次行上收进应用内中心）
                            JwImportActivity.start(context, JwImportMode.Selection)
                        },
                    )
                }
            }
            item {
                ReminderSection(
                    enabled = alertEnabled,
                    intervalHours = intervalHours,
                    onToggle = { value ->
                        scope.launch {
                            prefs.setSelectionAlertEnabled(value)
                            if (value) {
                                // 立刻评估一次：清掉检查时刻，让核对真正去抓一轮轮次再排闹钟
                                prefs.setSelectionRoundsCheckMillis(0)
                            }
                            // 周期任务的存废 + 即时核对（共用成绩/考试那条链）
                            ScoreAlertReminder.onSettingsChanged(context)
                            SelectionAlertReminder.scheduleNext(context)
                            snackbar.showSnackbar(if (value) "已开启选课提醒" else "已关闭选课提醒")
                        }
                    },
                    onPickInterval = { showIntervalPicker = true },
                )
            }
        }
    }

    if (showIntervalPicker) {
        WheelValueDialog(
            title = "自动检查间隔",
            values = ScoreAlertDefaults.INTERVAL_CHOICES.map { ScoreAlertDefaults.intervalLabel(it) },
            initialIndex = ScoreAlertDefaults.intervalChoiceIndex(intervalHours),
            onConfirm = { index ->
                showIntervalPicker = false
                scope.launch {
                    prefs.setAlertIntervalHours(ScoreAlertDefaults.INTERVAL_CHOICES[index])
                    ScoreAlertReminder.onSettingsChanged(context)
                }
            },
            onDismiss = { showIntervalPicker = false },
        )
    }
}

/**
 * 轮次卡（紧凑）：目标轮次（进行中 > 最近即将开始，[SelectionRounds.pickTarget]）置顶 +
 * 其余轮次紧凑行 + 唯一主按钮。
 *
 * 按钮总在：没有轮次时按钮开应用内中心（页内会说明状态），文案换成「打开选课中心」。
 * 轮次行只有「可操作」（进行中/即将开始）的能点，直达应用内中心对应轮次；
 * 已结束的行是纯信息。同步失败时在空态下补一句原因（不弹窗）。
 */
@Composable
private fun RoundsCard(
    target: SelectionRound?,
    others: List<SelectionRound>,
    now: Long,
    syncing: Boolean,
    lastSyncAt: Long,
    lastError: String?,
    onOpenCenter: (SelectionRound?) -> Unit,
) {
    AppCard {
        if (target == null) {
            Text(
                "选课轮次",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "当前没有开放的选课轮次（非选课期属正常）。选课开始后这里会显示轮次与时间。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                modifier = Modifier.padding(top = 4.dp),
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    target.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                PhaseTag(SelectionRounds.phase(target, now))
            }
            val detail = roundTimeLine(target, now)
                ?: listOf(target.term, target.timeText).filter { it.isNotBlank() }.joinToString(" · ")
            if (detail.isNotBlank()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        others.forEach { round ->
            val phase = SelectionRounds.phase(round, now)
            RoundRow(
                round = round,
                now = now,
                // 只有可操作的轮次能点（直达应用内中心）；已结束/时间未知的是纯信息
                onOpen = if (phase == SelectionRoundPhase.Active || phase == SelectionRoundPhase.Upcoming) {
                    { onOpenCenter(round) }
                } else {
                    null
                },
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        // 同步状态对轮次非空时也要可见：快照旧了 / 刚才那趟同步挂了，不能只靠空态说
        if (lastError != null) {
            Text(
                "同步失败：$lastError",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Text(
            if (lastSyncAt > 0) "上次同步 ${syncTimeLabel(lastSyncAt)}" else "尚未同步过",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            modifier = Modifier.padding(top = 6.dp),
        )
        Button(
            onClick = { onOpenCenter(target) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                // min 而不是固定高：系统大字体档位下按钮要能撑高，别把字裁了
                .heightIn(min = 40.dp),
        ) {
            Text(
                when {
                    syncing -> "同步中…"
                    target != null -> "进入选课"
                    else -> "打开选课中心"
                },
            )
        }
    }
}

@Composable
private fun RoundRow(
    round: SelectionRound,
    now: Long,
    onOpen: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val phase = SelectionRounds.phase(round, now)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(round.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val detail = roundTimeLine(round, now)
                ?: listOf(round.term, round.timeText).filter { it.isNotBlank() }.joinToString(" · ")
            if (detail.isNotBlank()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        PhaseTag(phase)
        if (onOpen != null) {
            Icon(
                HugeIcons.ArrowRight01,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(18.dp),
            )
        }
    }
}

/** 轮次状态小标签；时间解析不出来（[SelectionRoundPhase.Unknown]）不画标签，不编状态。 */
@Composable
private fun PhaseTag(phase: SelectionRoundPhase) {
    val label = when (phase) {
        SelectionRoundPhase.Active -> "进行中"
        SelectionRoundPhase.Upcoming -> "即将开始"
        SelectionRoundPhase.Ended -> "已结束"
        SelectionRoundPhase.Unknown -> return
    }
    val color: Color = when (phase) {
        SelectionRoundPhase.Active -> MaterialTheme.colorScheme.primary
        SelectionRoundPhase.Upcoming -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
    }
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = Modifier.padding(start = 8.dp),
    )
}

/** 同步脚注的时间：今天给「HH:mm」，更早给「MM-dd HH:mm」。 */
private fun syncTimeLabel(ms: Long): String {
    val zone = ZoneId.systemDefault()
    val at = Instant.ofEpochMilli(ms).atZone(zone)
    return if (at.toLocalDate() == LocalDate.now(zone)) {
        DateTimeFormatter.ofPattern("HH:mm").format(at)
    } else {
        DateTimeFormatter.ofPattern("MM-dd HH:mm").format(at)
    }
}

/**
 * 轮次的确定性时间文案：能解析出起止时刻就写「MM-dd HH:mm 开始/截止/已结束」，
 * 解析不出回退 null（调用方退回教务原文），不猜。
 */
private fun roundTimeLine(round: SelectionRound, now: Long): String? {
    val fmt = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    fun at(ms: Long): String = fmt.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
    return when (SelectionRounds.phase(round, now)) {
        SelectionRoundPhase.Upcoming -> round.startAt?.let { "${at(it)} 开始" }
        SelectionRoundPhase.Active -> round.endAt?.let { "${at(it)} 截止" }
        SelectionRoundPhase.Ended -> round.endAt?.let { "${at(it)} 已结束" }
        SelectionRoundPhase.Unknown -> null
    }
}

/** 已选课程入口行的副标题：学期数 + 门数；没同步过就说明来源。 */
private fun recordsSubtitle(rows: List<CourseSelection>?): String = when {
    rows == null -> "从教务「选课日志」同步后展示"
    rows.isEmpty() -> "暂无记录 · 点开自动同步"
    else -> "${rows.map { it.term }.distinct().size} 个学期 · 共 ${rows.size} 门"
}

@Composable
private fun ReminderSection(
    enabled: Boolean,
    intervalHours: Int,
    onToggle: (Boolean) -> Unit,
    onPickInterval: () -> Unit,
) {
    SettingsSection(
        title = "选课提醒",
        subtitle = "轮次开始前 30 分钟、截止前 6 小时各通知一次",
    ) {
        SettingSwitchRow(
            title = "开启提醒",
            subtitle = "按教务发布的轮次时间排定时提醒",
            checked = enabled,
            onCheckedChange = onToggle,
        )
        SettingItem(
            title = "自动检查间隔",
            subtitle = "与「成绩与考试」提醒共用",
            value = ScoreAlertDefaults.intervalLabel(intervalHours),
            enabled = enabled,
            onClick = onPickInterval,
        )
        Text(
            "提醒需要「我的 → 教务账户」已保存统一认证账号；轮次时间解析不出来的轮次不会提醒。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
        )
    }
}

/** 抢课行的副标题：进行中 > 待接入 > 可开始 > 还差什么。 */
private fun grabSubtitle(state: SelectionGrabStore.State, configured: Boolean): String = when {
    state.running -> "进行中：已抢到 ${state.grabbedCount}/${state.targetCount}"
    !configured -> "接口待接入（选课窗口期联调后启用）"
    state.lastStop != null -> "上次：${state.lastStop}"
    else -> "按预选清单自动加课 · 只加课不自动退课"
}
