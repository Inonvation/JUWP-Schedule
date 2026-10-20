package edu.jxslu.schedule.ui.exam

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.JwImportActivity
import edu.jxslu.schedule.domain.ScoreAlertDefaults
import edu.jxslu.schedule.ui.common.AppCardRow
import edu.jxslu.schedule.ui.common.PageScaffoldColumn
import edu.jxslu.schedule.ui.common.SettingItem
import edu.jxslu.schedule.ui.common.SettingSwitchRow
import edu.jxslu.schedule.ui.common.SettingsNote
import edu.jxslu.schedule.ui.common.SettingsSection
import edu.jxslu.schedule.ui.common.WheelValueDialog
import edu.jxslu.schedule.ui.common.showNotice
import edu.jxslu.schedule.ui.jwvw.JwImportOutcomeEffect
import edu.jxslu.schedule.ui.reminder.ScoreAlertReminder
import edu.jxslu.schedule.ui.score.maybeRequestNotifPermission
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Import

/**
 * 考试设置（2026-10-20）：考试页的提醒设置与「导入到课表」卡片统一收在这里。
 *
 * 考试列表页只保留列表、刷新与网页核对；设置页负责提醒开关、自动检查间隔和导入入口。
 * 间隔仍与成绩提醒各一份 DataStore 值，互不牵动。
 */
@Composable
fun ExamSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { Graph.displayPrefs(context) }
    val snackbar = remember { SnackbarHostState() }

    val enabled by prefs.examAlertEnabled.collectAsState(initial = true)
    val intervalHours by prefs.examAlertIntervalHours
        .collectAsState(initial = ScoreAlertDefaults.INTERVAL_DEFAULT)
    var intervalPickerOpen by remember { mutableStateOf(false) }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            scope.launch {
                snackbar.showNotice("未授予通知权限，提醒不会显示；可在系统设置里重新开启")
            }
        }
    }

    // 导入窗口从本页打开，finish 后回到这里；结果气泡落在本页。
    JwImportOutcomeEffect(snackbar)

    PageScaffoldColumn(
        title = "考试设置",
        onBack = onBack,
        snackbar = snackbar,
    ) {
        SettingsSection(
            title = "考试变动提醒",
            subtitle = "教务发布新考试、或时间/考场调整时通知",
        ) {
            SettingSwitchRow(
                title = "开启提醒",
                subtitle = "首次只记基线，之后有变动才通知",
                checked = enabled,
                onCheckedChange = { want ->
                    scope.launch { prefs.setExamAlertEnabled(want) }
                    if (want) {
                        maybeRequestNotifPermission(context, notifPermissionLauncher)
                        scope.launch { ScoreAlertReminder.onSettingsChanged(context) }
                    }
                },
            )
            SettingItem(
                title = "自动检查间隔",
                subtitle = "与成绩提醒分开设置，互不影响",
                value = ScoreAlertDefaults.intervalLabel(intervalHours),
                enabled = enabled,
                onClick = { intervalPickerOpen = true },
            )
            SettingsNote("提醒需要已保存统一认证账号；点通知回到考试安排页。")
        }

        AppCardRow(onClick = { JwImportActivity.start(context, startAtExam = true) }) {
            Column(Modifier.weight(1f)) {
                Text(
                    "导入考试安排到课表",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "打开导入页自动识别考试；确认后才写进课表",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            Icon(
                HugeIcons.Import,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }

        SettingsNote("考试只提醒、不自动写课表；导入动作始终需要你在确认弹窗里点一下。")
    }

    if (intervalPickerOpen) {
        WheelValueDialog(
            title = "自动检查间隔",
            values = ScoreAlertDefaults.INTERVAL_CHOICES.map { ScoreAlertDefaults.intervalLabel(it) },
            initialIndex = ScoreAlertDefaults.intervalChoiceIndex(intervalHours),
            onConfirm = { index ->
                intervalPickerOpen = false
                scope.launch {
                    prefs.setExamAlertIntervalHours(ScoreAlertDefaults.INTERVAL_CHOICES[index])
                    ScoreAlertReminder.onSettingsChanged(context)
                }
            },
            onDismiss = { intervalPickerOpen = false },
        )
    }
}
