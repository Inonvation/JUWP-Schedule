package edu.jxslu.schedule.ui.wenqu

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.domain.WenquRules
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.FooterDisclaimer
import edu.jxslu.schedule.ui.common.SettingItem
import edu.jxslu.schedule.ui.common.SettingSwitchRow
import edu.jxslu.schedule.ui.common.SettingsSection
import edu.jxslu.schedule.ui.common.WheelValueDialog
import edu.jxslu.schedule.ui.common.showNotice
import edu.jxslu.schedule.ui.reminder.WenquActivityAlertReminder
import edu.jxslu.schedule.ui.score.maybeRequestNotifPermission
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Logout03
import me.rerere.hugeicons.stroke.Note01
import me.rerere.hugeicons.stroke.Notification03

/**
 * 问渠设置页（DESIGN §3.28）——问渠页顶栏那枚齿轮进来的二级页。
 *
 * 2026-10-08 用户口径：**主页只管展示工时数**，记录入口、活动提醒、退出登录都收在这里。
 *
 * 三块：
 * 1. **账号**——登录的手机号（脱敏）+「我的志愿服务记录」入口（记录页 `WENQU_RECORDS`）；
 * 2. **提醒**——新增志愿活动提醒开关 + 检测间隔（默认 24 小时，档位与成绩/考试那几链同一套）；
 * 3. **登录**——退出登录（二次确认，清本机凭证）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WenquSettingsScreen(onBack: () -> Unit, onOpenRecords: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Graph.displayPrefs(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val session by remember { Graph.wenqu(context).session }.collectAsStateWithLifecycle()

    val alertEnabled by prefs.wenquActivityAlertEnabled.collectAsState(initial = false)
    val intervalHours by prefs.wenquActivityIntervalHours
        .collectAsState(initial = WenquRules.ACTIVITY_ALERT_INTERVAL_DEFAULT)
    var intervalPickerOpen by remember { mutableStateOf(false) }
    var confirmingLogout by remember { mutableStateOf(false) }
    /** 记录条数：进页读一次仓库缓存（冷缓存时它自己会抓一次），失败就不显示数字。 */
    var recordsCount by remember { mutableStateOf<Int?>(null) }
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    LaunchedEffect(Unit) {
        recordsCount = runCatching { Graph.wenqu(context).volunteer().list.size }.getOrNull()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("问渠设置", style = MaterialTheme.typography.titleMedium) },
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
            SettingsSection(
                title = "账号",
                subtitle = "问渠实践教育平台（第三方系统）· 账号是手机号",
            ) {
                SettingItem(
                    title = "已登录手机号",
                    subtitle = "本机保存，退出登录即清除",
                    value = session?.phone?.takeIf { it.isNotBlank() }?.let(WenquRules::maskPhone),
                )
                SettingItem(
                    title = "我的志愿服务记录",
                    subtitle = "我参加过的活动 · 每条可点开看详情",
                    icon = HugeIcons.Note01,
                    value = recordsCount?.let { "共 $it 条" },
                    onClick = onOpenRecords,
                )
            }

            SettingsSection(
                title = "提醒",
                subtitle = "问渠平台上出现新的可报名志愿活动时通知",
            ) {
                SettingSwitchRow(
                    title = "新增志愿活动提醒",
                    subtitle = "检测到新的「招募中」活动就发一条通知",
                    icon = HugeIcons.Notification03,
                    checked = alertEnabled,
                    onCheckedChange = { want ->
                        // 开关变了要重排周期任务（这一路可能是五条链里最勤的那条）+ 立刻评估一次
                        scope.launch {
                            prefs.setWenquActivityAlertEnabled(want)
                            WenquActivityAlertReminder.onSettingsChanged(context)
                        }
                        if (want) maybeRequestNotifPermission(context, notifPermissionLauncher)
                    },
                )
                SettingItem(
                    title = "检测间隔",
                    subtitle = "多久在后台扫一次新活动（与其它提醒共用周期任务）",
                    value = WenquRules.activityIntervalLabel(intervalHours),
                    enabled = alertEnabled,
                    onClick = { intervalPickerOpen = true },
                )
                Text(
                    text = "首次开启只把当前活动记成基线、不会立刻通知；之后出现新的「招募中」活动才提醒。" +
                        "问渠是第三方系统，间隔别调太短——一天一次（24 小时）就够用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                )
            }

            SettingsSection(title = "登录", subtitle = "凭证只存本机（加密存储，不进云备份）") {
                SettingItem(
                    title = "退出登录",
                    subtitle = "清除本机保存的问渠手机号与密码",
                    icon = HugeIcons.Logout03,
                    onClick = { confirmingLogout = true },
                )
            }

            FooterDisclaimer(
                "问渠是第三方系统，非学校官方功能；工时与活动数据以平台为准。" +
                    "本应用只做只读展示，报名、签到、提交等操作请到微信小程序内完成。",
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }

    if (intervalPickerOpen) {
        WheelValueDialog(
            title = "检测间隔",
            values = WenquRules.ACTIVITY_ALERT_INTERVAL_CHOICES.map(WenquRules::activityIntervalLabel),
            initialIndex = WenquRules.activityIntervalChoiceIndex(intervalHours),
            onConfirm = { index ->
                intervalPickerOpen = false
                val value = WenquRules.ACTIVITY_ALERT_INTERVAL_CHOICES[index]
                scope.launch {
                    prefs.setWenquActivityIntervalHours(value)
                    WenquActivityAlertReminder.onSettingsChanged(context)
                    snackbar.showNotice("检测间隔已设为${WenquRules.activityIntervalLabel(value)}")
                }
            },
            onDismiss = { intervalPickerOpen = false },
        )
    }

    if (confirmingLogout) {
        AlertDialog(
            onDismissRequest = { confirmingLogout = false },
            title = { Text("退出登录") },
            text = { Text("退出后将清除本机保存的问渠凭证，工时卡片与活动列表都会隐藏。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingLogout = false
                    Graph.wenqu(context).logout()
                    // 退完直接回问渠页——那一页订阅着登录态，此刻已经是登录表单了；
                    // 留在设置页只会看到一张空的「已登录手机号」
                    onBack()
                }) { Text("退出") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingLogout = false }) { Text("取消") }
            },
        )
    }
}
