package edu.jxslu.schedule.ui.score

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.domain.ScoreAlertDefaults
import edu.jxslu.schedule.domain.ScoreSortMode
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.DialogWindowFadeEffect
import edu.jxslu.schedule.ui.common.SettingItem
import edu.jxslu.schedule.ui.common.SettingsNote
import edu.jxslu.schedule.ui.common.SettingsSection
import edu.jxslu.schedule.ui.common.WheelValueDialog
import edu.jxslu.schedule.ui.common.showNotice
import edu.jxslu.schedule.ui.reminder.ScoreAlertReminder
import kotlinx.coroutines.launch

/**
 * 成绩设置（DESIGN §4.33）：成绩查询页的设置单独成页，2026-09-30 自成绩页收编。
 *
 * 三段：**展示**（按学期/学年分组 · 排序——原成绩页顶栏菜单迁入）、**统计口径**
 * （任选课计入两项平均，原汇总卡内开关迁入）、**成绩变动提醒**（开关 + 自动检查间隔）。
 * 成绩页顶栏收一个齿轮入口进来；导入仍留在成绩页，清空学期已迁到本页「数据」段
 * （选择学期后二次确认）。
 *
 * 考试变动提醒**不在这里**：2026-09-30 起在独立考试页（`ui/exam/ExamScreen.kt`，
 * `SubpageScreen.EXAMS`，入口「我的 → 学习 → 成绩与考试 → 考试安排」），
 * 两个提醒共用检查间隔 [ScoreAlertDefaults]（同一份 DataStore 值，这里改了那边也变）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScoreSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { Graph.displayPrefs(context) }
    val scoreRepo = remember { Graph.scoreRepository(context) }
    val snackbar = remember { SnackbarHostState() }

    val groupByYear by prefs.scoreGroupByYear.collectAsState(initial = false)
    val includeFreeElectives by prefs.scoreIncludeFreeElectives.collectAsState(initial = false)
    val sortMode by prefs.scoreSortMode.collectAsState(initial = ScoreSortMode.Default)
    val scoreAlertEnabled by prefs.scoreAlertEnabled.collectAsState(initial = true)
    val intervalHours by prefs.scoreAlertIntervalHours.collectAsState(initial = ScoreAlertDefaults.INTERVAL_DEFAULT)
    val terms by scoreRepo.observeTerms().collectAsState(initial = emptyList())
    var intervalPickerOpen by remember { mutableStateOf(false) }
    var clearDialogOpen by remember { mutableStateOf(false) }
    var clearTerm by remember { mutableStateOf<String?>(null) }

    // 开启提醒那一刻请求 POST_NOTIFICATIONS（API 33+），拒绝不阻塞开关本身
    // （口径同 CampusCardSettingsScreen：功能在系统设置里授权后自动生效）
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            scope.launch {
                snackbar.showNotice("未授予通知权限，提醒不会显示；可在系统设置里重新开启")
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("成绩设置", style = MaterialTheme.typography.titleMedium) },
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsSection(title = "展示") {
                SettingItem(
                    title = "分组方式",
                    subtitle = "成绩列表按学期或按学年归组",
                    value = if (groupByYear) "按学年" else "按学期",
                    onClick = { scope.launch { prefs.setScoreGroupByYear(!groupByYear) } },
                )
                SettingItem(
                    title = "排序",
                    subtitle = "学期视图内课程卡的顺序",
                    value = when (sortMode) {
                        ScoreSortMode.Default -> "默认顺序"
                        ScoreSortMode.ByScore -> "成绩从高到低"
                        ScoreSortMode.ByGradePoint -> "绩点从高到低"
                    },
                    onClick = {
                        val next = when (sortMode) {
                            ScoreSortMode.Default -> ScoreSortMode.ByScore
                            ScoreSortMode.ByScore -> ScoreSortMode.ByGradePoint
                            ScoreSortMode.ByGradePoint -> ScoreSortMode.Default
                        }
                        scope.launch { prefs.setScoreSortMode(next) }
                    },
                )
            }

            SettingsSection(title = "统计口径") {
                SettingItem(
                    title = "任选课计入统计",
                    subtitle = "默认排除：本校综测也不计；影响加权平均分与平均绩点",
                    trailing = {
                        Switch(
                            checked = includeFreeElectives,
                            onCheckedChange = { scope.launch { prefs.setScoreIncludeFreeElectives(it) } },
                        )
                    },
                )
            }
            SettingsNote(
                "加权平均分 = Σ(课程成绩 × 学分) ÷ Σ学分；" +
                    "平均绩点 = Σ(课程绩点 × 学分) ÷ Σ学分。",
            )

            SettingsSection(title = "成绩变动提醒") {
                SettingItem(
                    title = "出分提醒",
                    subtitle = "新出成绩或复查改分时通知；打开应用也会检查一次",
                    trailing = {
                        Switch(
                            checked = scoreAlertEnabled,
                            onCheckedChange = { want ->
                                scope.launch { prefs.setScoreAlertEnabled(want) }
                                if (want) {
                                    maybeRequestNotifPermission(context, notifPermissionLauncher)
                                    scope.launch { ScoreAlertReminder.onSettingsChanged(context) }
                                }
                            },
                        )
                    },
                )
                SettingItem(
                    title = "自动检查间隔",
                    subtitle = "多久在后台自动核对一次（与考试变动提醒共用）",
                    value = ScoreAlertDefaults.intervalLabel(intervalHours),
                    enabled = scoreAlertEnabled,
                    onClick = { intervalPickerOpen = true },
                )
                SettingsNote("首次开启只记基线，之后有新出分才通知；需已保存教务账号")
            }

            SettingsSection(title = "数据") {
                SettingItem(
                    title = "清空学期成绩",
                    subtitle = if (terms.isEmpty()) {
                        "本机还没有可清空的成绩"
                    } else {
                        "删除某一学期已导入的成绩，可重新从教务导入"
                    },
                    value = "选择学期",
                    enabled = terms.isNotEmpty(),
                    onClick = {
                        clearTerm = terms.firstOrNull()
                        clearDialogOpen = true
                    },
                )
            }
            SettingsNote("清空只删本机成绩，不影响教务数据；重新导入即可恢复。")
        }
    }

    if (intervalPickerOpen) {
        WheelValueDialog(
            title = "自动检查间隔",
            values = ScoreAlertDefaults.INTERVAL_CHOICES.map { ScoreAlertDefaults.intervalLabel(it) },
            initialIndex = ScoreAlertDefaults.intervalChoiceIndex(intervalHours),
            onConfirm = { index ->
                intervalPickerOpen = false
                val value = ScoreAlertDefaults.INTERVAL_CHOICES[index]
                scope.launch {
                    prefs.setScoreAlertIntervalHours(value)
                    ScoreAlertReminder.onSettingsChanged(context)
                }
            },
            onDismiss = { intervalPickerOpen = false },
        )
    }

    if (clearDialogOpen && clearTerm != null) {
        AlertDialog(
            onDismissRequest = { clearDialogOpen = false },
            title = { Text("清空学期成绩？") },
            text = {
                DialogWindowFadeEffect()
                Column {
                    Text(
                        "选择要清空的学期：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        terms.forEach { term ->
                            FilterChip(
                                selected = term == clearTerm,
                                onClick = { clearTerm = term },
                                label = { Text(term) },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "将删除「${clearTerm.orEmpty()}」的全部成绩记录；需要时可重新从教务导入。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val term = clearTerm
                        clearDialogOpen = false
                        if (term != null) {
                            scope.launch {
                                val result = runCatching { scoreRepo.deleteTerm(term) }
                                snackbar.showNotice(
                                    if (result.isSuccess) "已清空「$term」的成绩"
                                    else "清空失败，请稍后重试",
                                )
                            }
                        }
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { clearDialogOpen = false }) { Text("取消") }
            },
        )
    }
}

/**
 * 通知权限申请：API 33+ 且未授予才弹（模式同 CampusCardSettingsScreen）。
 * internal 自 2026-09-30：考试页的开提醒也要同一份口径，别再复制一处。
 */
internal fun maybeRequestNotifPermission(
    context: android.content.Context,
    launcher: ActivityResultLauncher<String>,
) {
    if (Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS,
        ) != PackageManager.PERMISSION_GRANTED
    ) {
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
