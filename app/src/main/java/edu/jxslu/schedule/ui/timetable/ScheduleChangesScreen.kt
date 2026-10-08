package edu.jxslu.schedule.ui.timetable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.repo.ScheduleSync
import edu.jxslu.schedule.domain.Course
import edu.jxslu.schedule.domain.InboxKind
import edu.jxslu.schedule.domain.ScheduleAlertDefaults
import edu.jxslu.schedule.domain.ScheduleDiff
import edu.jxslu.schedule.domain.SchedulePendingChanges
import edu.jxslu.schedule.ui.common.AppCard
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.DialogWindowFadeEffect
import edu.jxslu.schedule.ui.common.ScheduleChangeCard
import edu.jxslu.schedule.ui.common.WheelValueDialog
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import edu.jxslu.schedule.ui.common.showNotice
import edu.jxslu.schedule.ui.reminder.ScoreAlertReminder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 调课处置页（DESIGN §4.17，2026-10-04 重立）：教务课表与基线比对出的差异在这里
 * 逐条处置——勾选合并入当前课表，或忽略本轮。
 *
 * 数据源是检查链落的「待处置缓存」（`SchedulePendingChanges`）：进页不重新抓教务，
 * 看到的就是通知里那份差异；顶栏「重新检查」才 force 再查一次（结果更新缓存）。
 *
 * 状态机：
 * - 合并所选：`ScheduleRepository.applyScheduleChanges` 事务内逐条应用（用户改过的
 *   条目指纹对不上就跳过，不硬套）→ 基线推进为应用后的实际课表 → 本次完整差异记
 *   「已阅」→ 清缓存。没勾的差异下次检查仍会如实报出。
 * - 忽略：写已阅指纹 → 清缓存。基线不动，同一次差异不再重复提醒；教务再出新差异
 *   指纹才会变。
 * - 冲突条目（`ScheduleDiff.isConflicted`：与你修改过的课相关）默认不勾，采纳与否由
 *   用户逐条决定——应用它会覆盖你自己的修改。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleChangesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Graph.displayPrefs(context) }
    val repo = remember { Graph.repository(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val haptics = rememberAppHaptics()

    // null = 缓存首帧未到；非 null = 待处置差异（可能为空的 changes 无意义，缓存只在
    // 有差异时写入，读到空 changes 视同没有待处置）
    val pending by prefs.schedulePendingChanges.collectAsStateWithLifecycle(initialValue = null)

    // 冲突判定的两个数据源：基线（一次性读）与当前课表（流，合并后自动刷新）
    var baseline by remember { mutableStateOf<List<Course>>(emptyList()) }
    LaunchedEffect(Unit) {
        baseline = repo.loadScheduleBaseline().courses.map { it.toCourse() }
    }
    val currentCourses by repo.courses.collectAsStateWithLifecycle(initialValue = emptyList())
    val alertOn by prefs.scheduleAlertEnabled.collectAsStateWithLifecycle(initialValue = false)
    // 缓存归属校验：差异是按课表存的，活跃课表被切走后这里的合并会落到错误的表
    val currentTimetableId by repo.currentTimetableId.collectAsStateWithLifecycle(initialValue = 0L)
    val belongsToCurrent = pending == null || pending?.timetableId == currentTimetableId
    // 调课检查间隔独立设置（默认 24h：每次核对抓两张整页，低频起步）
    val scheduleIntervalHours by prefs.scheduleAlertIntervalHours
        .collectAsStateWithLifecycle(initialValue = ScheduleAlertDefaults.INTERVAL_DEFAULT)
    var showIntervalPicker by remember { mutableStateOf(false) }

    val changes = remember(pending) { pending?.toChanges().orEmpty() }
    val conflicts = remember(changes, currentCourses) {
        changes.map { ScheduleDiff.isConflicted(it, currentCourses) }
    }
    // 默认勾选 = 全部非冲突条目；用户手动改动后以手动为准（pending 变了重置）
    var selected by remember(pending) { mutableStateOf<Set<Int>?>(null) }
    val effectiveSelection: Set<Int> = selected
        ?: changes.indices.filterNot { conflicts[it] }.toSet()

    var busy by remember { mutableStateOf(false) }
    var confirmingIgnore by remember { mutableStateOf(false) }

    fun refreshNow() {
        if (busy) return
        busy = true
        scope.launch {
            val result = runCatching { Graph.scheduleSync(context).check(force = true) }
            busy = false
            val message = when (val r = result.getOrNull()) {
                is ScheduleSync.CheckResult.Ok ->
                    if (r.changes.isEmpty()) "已检查：暂无新的调课差异" else "发现 ${r.changes.size} 处差异"
                is ScheduleSync.CheckResult.Failed -> r.reason
                else -> "检查被跳过（提醒开关未开启或间隔未到）"
            }
            snackbar.showNotice(message)
        }
    }

    fun mergeSelected() {
        if (busy || pending == null || !belongsToCurrent) return
        val snapshot = pending ?: return
        busy = true
        scope.launch {
            val accepted = effectiveSelection
            val report = runCatching {
                repo.applyScheduleChanges(changes, accepted)
            }.getOrNull()
            if (report == null) {
                busy = false
                snackbar.showNotice("合并失败，请稍后重试")
                return@launch
            }
            // 基线已在 apply 内推进；这里把「本次展示过的完整差异」记为已阅并清缓存——
            // 没勾的条目客观上仍未与教务对齐，下次检查会以剩余差异的形态重新报出
            prefs.setScheduleIgnoredFingerprint(snapshot.fingerprint)
            prefs.setSchedulePendingChanges(null)
            // 收件箱（DESIGN §4.38）：本轮差异已处置，那条「课程安排有变动」标已读
            Graph.inboxRepository(context).markKindRead(InboxKind.SCHEDULE_CHANGE)
            busy = false
            haptics.success()
            val detail = if (report.skipped > 0) "，${report.skipped} 条与你的修改冲突被跳过" else ""
            snackbar.showNotice("已合并 ${report.applied} 处变动$detail")
        }
    }

    fun ignoreAll() {
        val snapshot = pending ?: return
        scope.launch {
            prefs.setScheduleIgnoredFingerprint(snapshot.fingerprint)
            prefs.setSchedulePendingChanges(null)
            Graph.inboxRepository(context).markKindRead(InboxKind.SCHEDULE_CHANGE)
            confirmingIgnore = false
            snackbar.showNotice("已忽略本轮差异；教务再变化时会重新提醒")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("调课检测", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = ::refreshNow, enabled = !busy) { Text("重新检查") }
                },
            )
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // 开关与间隔两行**空态也常显**：第一次进页要配置的就是这两行，
            // 收在有差异的分支里等于永远配不上
            AlertSwitchRow(alertOn, prefs, scope, context)
            AppCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 8.dp),
                onClick = { showIntervalPicker = true },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("自动检查间隔", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "独立于成绩/考试提醒，互不影响",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                    Text(
                        ScheduleAlertDefaults.intervalLabel(scheduleIntervalHours),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            when {
                // 缓存只在有差异时写入；读到空 changes（防御）视同没有待处置
                pending == null || changes.isEmpty() -> Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "当前没有待处理的调课差异",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                        Spacer(Modifier.padding(top = 6.dp))
                        Text(
                            "开启调课提醒后，每次核对会与教务最新课表比对，发现差异会在这里列出",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        )
                        Spacer(Modifier.padding(top = 14.dp))
                        TextButton(onClick = ::refreshNow, enabled = !busy) { Text("立即检查一次") }
                    }
                }

                else -> {
                    Text(
                    buildString {
                        append("教务课表与你的课表存在 ${changes.size} 处差异")
                        conflicts.count { it }.takeIf { it > 0 }?.let {
                            append("，其中 ${it} 处与你修改过的课相关")
                        }
                        append("。勾选要合并进当前课表的变动：")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp, vertical = 10.dp),
                )
                if (!belongsToCurrent) {
                    Text(
                        "这批差异属于另一张课表（当前活跃课表已切换）。请先「重新检查」刷新，" +
                            "或回到原课表再合并。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 22.dp),
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    changes.forEachIndexed { index, change ->
                        ScheduleChangeCard(
                            change = change,
                            conflicted = conflicts[index],
                            checked = index in effectiveSelection,
                            onCheckedChange = { checked ->
                                selected = effectiveSelection.toMutableSet().apply {
                                    if (checked) add(index) else remove(index)
                                }
                            },
                            // 整卡可点切换勾选（与勾选框同一状态，勾选框自身不冒泡）
                            onRowToggle = {
                                selected = effectiveSelection.toMutableSet().apply {
                                    if (!add(index)) remove(index)
                                }
                            },
                        )
                    }
                    Spacer(Modifier.padding(bottom = 8.dp))
                }
                // 固定动作区：heightIn 防大字体档位下按钮被裁字（选课页同款纪律）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = ::mergeSelected,
                        enabled = !busy && belongsToCurrent && effectiveSelection.isNotEmpty(),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp),
                    ) {
                        Text("合并所选（${effectiveSelection.size}）", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(
                        onClick = { confirmingIgnore = true },
                        enabled = !busy,
                        modifier = Modifier.heightIn(min = 44.dp),
                    ) {
                        Text("忽略全部")
                    }
                }
                }
            }
        }
        if (busy) Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator() }
    }

    if (showIntervalPicker) {
        WheelValueDialog(
            title = "自动检查间隔",
            values = ScheduleAlertDefaults.INTERVAL_CHOICES.map { ScheduleAlertDefaults.intervalLabel(it) },
            initialIndex = ScheduleAlertDefaults.intervalChoiceIndex(scheduleIntervalHours),
            onConfirm = { index ->
                showIntervalPicker = false
                scope.launch {
                    prefs.setScheduleAlertIntervalHours(ScheduleAlertDefaults.INTERVAL_CHOICES[index])
                    // 清检查时刻 + 周期任务重排（取四链最小档）+ 即时核对，收口在一个入口
                    ScoreAlertReminder.onSettingsChanged(context)
                }
            },
            onDismiss = { showIntervalPicker = false },
        )
    }

    if (confirmingIgnore) {
        DialogWindowFadeEffect()
        AlertDialog(
            onDismissRequest = { confirmingIgnore = false },
            title = { Text("忽略这些差异？") },
            text = {
                Text(
                    "忽略后本轮不再提醒；基线保持不变，教务课表与你课表的差异仍然存在，" +
                        "教务再出新变化时会连同这些一起重新提醒。",
                )
            },
            confirmButton = {
                TextButton(onClick = ::ignoreAll) { Text("忽略") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingIgnore = false }) { Text("取消") }
            },
        )
    }
}

/** 提醒开关行：hub 的入口行、通知、开关三处一个归属，收在本页不分散。 */
@Composable
private fun AlertSwitchRow(
    checked: Boolean,
    prefs: edu.jxslu.schedule.data.prefs.DisplayPrefsStore,
    scope: kotlinx.coroutines.CoroutineScope,
    context: android.content.Context,
) {
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("调课提醒", style = MaterialTheme.typography.titleSmall)
                Text(
                    "开启后按成绩提醒的间隔自动与教务比对，发现变动发通知",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            Switch(checked = checked, onCheckedChange = { value ->
                scope.launch {
                    prefs.setScheduleAlertEnabled(value)
                    // 两个方向都重排周期任务：开 → 本开关立即生效（清检查时刻后当场评估
                    // 一次）；关 → 若其余三路提醒也全关，ensurePeriodicWork 会撤销周期任务。
                    // 重排不会丢已有相位（KEEP 策略，档位没变就保留）
                    if (value) prefs.setScheduleCheckMillis(0)
                    ScoreAlertReminder.ensurePeriodicWork(context)
                    if (value) ScoreAlertReminder.enqueueCheck(context)
                }
            })
        }
    }
}

/** 一条差异的卡片已抽到 [edu.jxslu.schedule.ui.common.ScheduleChangeCard] 共用（2026-10-06）。 */

