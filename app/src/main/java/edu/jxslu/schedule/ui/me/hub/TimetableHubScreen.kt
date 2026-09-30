package edu.jxslu.schedule.ui.me.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.domain.ScoreAlertDefaults
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.WheelValueDialog
import edu.jxslu.schedule.ui.reminder.ScoreAlertReminder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import edu.jxslu.schedule.ui.common.SettingItem
import edu.jxslu.schedule.ui.common.SettingsSection
import edu.jxslu.schedule.ui.common.SettingSwitchRow
import edu.jxslu.schedule.ui.common.SettingsTag
import edu.jxslu.schedule.ui.me.MeViewModel
import edu.jxslu.schedule.ui.jwvw.JwImportOutcomeEffect
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.CalendarSync
import me.rerere.hugeicons.stroke.Clock01
import me.rerere.hugeicons.stroke.Database
import me.rerere.hugeicons.stroke.GridView
import me.rerere.hugeicons.stroke.Import

/**
 * 我的 → 课表（DESIGN §3.3，2026-09-28 二次改版）：hero 卡 + 配置 → 使用 → 数据。
 *
 * hero 卡（本轮新增）：当前课表名 + 学期起止 + 周数 + 课程数，视觉语言复用
 * 今日页焦点卡（主色 8% 底 + 左缘 3dp 主色竖条）——进页先确认「我在改哪张表」，
 * 再动手配置。「从教务导入」升为主色按钮 CTA：开学第一周的最高频动作。
 *
 * 上课提醒 2026-09-28 迁往「提醒与桌面」hub（与小组件、日历同属
 * 「课表数据送到哪里」）。成绩查询 2026-09-24 挪进学习页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimetableHubScreen(
    onBack: () -> Unit,
    onOpenJwImport: () -> Unit,
    onOpenTimetableManage: () -> Unit,
    onOpenTimetableSettings: () -> Unit,
    onOpenDataSettings: () -> Unit,
    onOpenCourseTweak: () -> Unit,
    viewModel: MeViewModel = viewModel(
        factory = MeViewModel.Factory(Graph.repository(LocalContext.current)),
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    // 本页有「教务导入」入口，导入窗口 finish 后落回的就是这一页
    JwImportOutcomeEffect(snackbar)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("课表") },
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
            TimetableHero(state = state, onOpenJwImport = onOpenJwImport)

            SettingsSection(title = "配置") {
                SettingItem(
                    title = "课表管理",
                    subtitle = "新建 · 切换 · 删除课表",
                    icon = HugeIcons.GridView,
                    onClick = onOpenTimetableManage,
                )
                SettingItem(
                    title = "课表设置",
                    titleTag = "当前课表",
                    subtitle = "学期起止 · 总周数 · 作息时间",
                    icon = HugeIcons.Clock01,
                    onClick = onOpenTimetableSettings,
                )
            }

            SettingsSection(title = "使用") {
                SettingItem(
                    title = "调课",
                    subtitle = "把某天的课调到另一天 · 仅本机生效",
                    icon = HugeIcons.CalendarSync,
                    onClick = onOpenCourseTweak,
                )
                ExamAlertRows()
            }

            SettingsSection(title = "数据") {
                SettingItem(
                    title = "课表数据",
                    subtitle = "JSON 导出 / 导入 · 清空课程",
                    icon = HugeIcons.Database,
                    onClick = onOpenDataSettings,
                )
            }
        }
    }
}

/**
 * 课表 hub 的 hero 卡：当前课表名 + 学期起止 + 课程数，「从教务导入」主按钮。
 * 视觉 = 今日页焦点卡同语言（主色 8% 底 + 左缘 3dp 竖条 + 14dp 圆角），
 * 提示条消失后仍是「哪张课表在生效」的常驻确认位。
 */
@Composable
private fun TimetableHero(state: edu.jxslu.schedule.ui.me.MeUiState, onOpenJwImport: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val semester = state.configSemester
    val week = state.configWeek
    // 左缘 3dp 主色竖条 + 内容：外层 Row 装 [竖条, 内容列]，同今日页焦点卡的语言。
    // 竖条上下各缩进 12dp（焦点卡口径），IntrinsicSize.Min 让它跟内容列等高
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(primary.copy(alpha = 0.08f))
            .height(IntrinsicSize.Min),
    ) {
        Box(
            modifier = Modifier
                .padding(vertical = 12.dp)
                .width(3.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(primary),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp, end = 14.dp, top = 13.dp, bottom = 13.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = state.timetableName.ifBlank { "默认课表" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Spacer(Modifier.width(8.dp))
                SettingsTag("当前课表")
            }
            Text(
                text = buildString {
                    if (semester != null) {
                        append(semester.startDate)
                        append(" 起 · 共 ${semester.totalWeeks} 周")
                    } else {
                        append("未配置学期 · 去课表设置填开学日期")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                modifier = Modifier.padding(top = 3.dp),
            )
            Text(
                text = buildString {
                    append("${state.configCourseCount} 门课程")
                    if (week > 0) append(" · 第 $week 周")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                modifier = Modifier.padding(top = 2.dp),
            )
            Row(
                modifier = Modifier
                    .padding(top = 11.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(primary)
                    .clickable(onClickLabel = "从教务导入课表") { onOpenJwImport() }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Icon(
                    HugeIcons.Import,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    "从教务导入",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

/**
 * 考试变动提醒（DESIGN §4.33，2026-09-30 挪入课表 hub）：考试跟着课表走，
 * 开关与自动检查间隔就放在「使用」区里，不再单独开页。检测到教务发布新考试
 * 或时间/考场调整时发通知，点通知进教务导入页确认后才会写进课表。
 *
 * 自动检查间隔与「成绩变动提醒」共用一份 DataStore 值（[ScoreAlertDefaults]）——
 * 两条提醒链共用同一个周期任务（`ScoreAlertReminder`），间隔本来就是全局的。
 */
@Composable
private fun ExamAlertRows() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { Graph.displayPrefs(context) }
    val enabled by prefs.examAlertEnabled.collectAsState(initial = false)
    val intervalHours by prefs.alertIntervalHours.collectAsState(initial = ScoreAlertDefaults.INTERVAL_DEFAULT)
    var intervalPickerOpen by remember { mutableStateOf(false) }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            scope.launch {
                android.widget.Toast.makeText(
                    context,
                    "未授予通知权限，提醒不会显示；可在系统设置里重新开启",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    SettingSwitchRow(
        title = "考试变动提醒",
        subtitle = "教务发布新考试或时间/考场调整时通知；点通知进教务导入页确认后才会写进课表",
        checked = enabled,
        onCheckedChange = { want ->
            scope.launch { prefs.setExamAlertEnabled(want) }
            if (want) {
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(
                        context, Manifest.permission.POST_NOTIFICATIONS,
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                scope.launch { ScoreAlertReminder.onSettingsChanged(context) }
            }
        },
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { intervalPickerOpen = true }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "自动检查间隔",
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            },
        )
        Spacer(Modifier.weight(1f))
        Text(
            ScoreAlertDefaults.intervalLabel(intervalHours),
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            },
        )
    }

    if (intervalPickerOpen) {
        WheelValueDialog(
            title = "自动检查间隔",
            values = ScoreAlertDefaults.INTERVAL_CHOICES.map { ScoreAlertDefaults.intervalLabel(it) },
            initialIndex = ScoreAlertDefaults.intervalChoiceIndex(intervalHours),
            onConfirm = { index ->
                intervalPickerOpen = false
                scope.launch {
                    prefs.setAlertIntervalHours(ScoreAlertDefaults.INTERVAL_CHOICES[index])
                    ScoreAlertReminder.onSettingsChanged(context)
                }
            },
            onDismiss = { intervalPickerOpen = false },
        )
    }
}
