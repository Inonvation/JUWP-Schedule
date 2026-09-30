package edu.jxslu.schedule.ui.score

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import edu.jxslu.schedule.ui.common.SettingItem
import edu.jxslu.schedule.ui.common.SettingsSection
import edu.jxslu.schedule.ui.common.WheelValueDialog
import edu.jxslu.schedule.ui.reminder.ScoreAlertReminder
import kotlinx.coroutines.launch

/**
 * 成绩设置（DESIGN §4.33）：成绩查询页的设置单独成页，2026-09-30 自成绩页收编。
 *
 * 三段：**展示**（按学期/学年分组 · 排序——原成绩页顶栏菜单迁入）、**统计口径**
 * （任选课计入两项平均，原汇总卡内开关迁入）、**成绩变动提醒**（开关 + 自动检查间隔）。
 * 成绩页顶栏收一个齿轮入口进来；数据操作（导入 / 清空学期）仍留在成绩页——它们是
 * 页内动作不是设置。
 *
 * 考试变动提醒**不在这里**：考试跟着课表走（入口在 我的 → 课表，2026-09-30 挪入），
 * 两个提醒共用检查间隔 [ScoreAlertDefaults]（同一份 DataStore 值，这里改了那边也变）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScoreSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { Graph.displayPrefs(context) }
    val snackbar = remember { SnackbarHostState() }

    val groupByYear by prefs.scoreGroupByYear.collectAsState(initial = false)
    val includeFreeElectives by prefs.scoreIncludeFreeElectives.collectAsState(initial = false)
    val sortMode by prefs.scoreSortMode.collectAsState(initial = ScoreSortMode.Default)
    val scoreAlertEnabled by prefs.scoreAlertEnabled.collectAsState(initial = false)
    val intervalHours by prefs.alertIntervalHours.collectAsState(initial = ScoreAlertDefaults.INTERVAL_DEFAULT)
    var intervalPickerOpen by remember { mutableStateOf(false) }

    // 开启提醒那一刻请求 POST_NOTIFICATIONS（API 33+），拒绝不阻塞开关本身
    // （口径同 CampusCardSettingsScreen：功能在系统设置里授权后自动生效）
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            scope.launch {
                snackbar.showSnackbar("未授予通知权限，提醒不会显示；可在系统设置里重新开启")
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
                    subtitle = "默认排除：本校综测同样不计任选课；影响加权平均分与平均绩点",
                    trailing = {
                        Switch(
                            checked = includeFreeElectives,
                            onCheckedChange = { scope.launch { prefs.setScoreIncludeFreeElectives(it) } },
                        )
                    },
                )
            }

            SettingsSection(title = "成绩变动提醒") {
                SettingItem(
                    title = "出分提醒",
                    subtitle = "新出成绩或复查改分时通知；打开应用时也会自动在后台检查一次",
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
                Text(
                    "首次开启只记录当前成绩作为基线，之后有新出分才通知。" +
                        "提醒需要「我的」页已保存教务账号。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                )
            }
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
                    prefs.setAlertIntervalHours(value)
                    ScoreAlertReminder.onSettingsChanged(context)
                }
            },
            onDismiss = { intervalPickerOpen = false },
        )
    }
}

/** 通知权限申请：API 33+ 且未授予才弹（模式同 CampusCardSettingsScreen）。 */
private fun maybeRequestNotifPermission(
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
