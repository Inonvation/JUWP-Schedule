package edu.jxslu.schedule.ui.me

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import edu.jxslu.schedule.startActivityOutsideApp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.repo.ScheduleRepository
import edu.jxslu.schedule.domain.ReminderDefaults
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.SettingItem
import edu.jxslu.schedule.ui.common.SettingsNote
import edu.jxslu.schedule.ui.common.SettingSwitchRow
import edu.jxslu.schedule.ui.common.SettingsSection
import edu.jxslu.schedule.ui.common.WheelValueDialog
import edu.jxslu.schedule.ui.common.showNotice
import edu.jxslu.schedule.ui.reminder.ClassReminder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AlarmClock
import me.rerere.hugeicons.stroke.BellRing
import me.rerere.hugeicons.stroke.Notification01
import me.rerere.hugeicons.stroke.Task01

/**
 * 提醒子页（DESIGN §3.7 上课提醒 + §3.11 作业截止提醒）：开关 + 提前量 + 通知权限状态 + 说明。
 *
 * 默认关：通知是打扰型能力。开启那一刻才请求 `POST_NOTIFICATIONS`（API 33+），
 * 拒绝不阻塞开关——功能在系统设置里授权后自动生效。
 * 调度重排放在本页对 (开关, 提前量) 的观察上；课表数据变化与冷启动由
 * JuwApplication 的数据收集器兜住，这里不重复。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderSettingsScreen(
    onBack: () -> Unit,
    viewModel: ReminderSettingsViewModel = viewModel(
        factory = ReminderSettingsViewModel.Factory(Graph.repository(LocalContext.current)),
    ),
) {
    val context = LocalContext.current
    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
    val lead by viewModel.leadMinutes.collectAsStateWithLifecycle()
    val homeworkEnabled by viewModel.homeworkEnabled.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showLeadPicker by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showNotice(it)
            viewModel.consumeMessage()
        }
    }

    // 开关/提前量变化 → 立即重排（scheduleNext 内部已兜错；关闭时撤销已排闹钟）
    LaunchedEffect(enabled, lead) {
        ClassReminder.scheduleNext(context)
    }

    // 作业提醒开关变化 → 立即核对一次（enqueueCheck 先补发窗口内的、再重排闹钟；
    // 关到位后作业侧不再排闹钟）。首次进入也会跑一次——与 15 分钟周期核对等价、很廉价。
    LaunchedEffect(homeworkEnabled) {
        ClassReminder.enqueueCheck(context)
    }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) viewModel.onPermissionDenied()
    }

    // 两个提醒开关共用：开启那一刻请求 POST_NOTIFICATIONS（API 33+），拒绝不阻塞开关本身
    val requestNotifPermission = {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // 通知权限状态：进页与从系统设置返回（ON_RESUME）时各读一次
    val notifEnabled = remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notifEnabled.value = NotificationManagerCompat.from(context).areNotificationsEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("上课提醒") },
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
                .padding(16.dp),
            // 与其余设置子页同口径：分区卡之间留 12dp（漏了会三张卡紧贴）
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 通知权限并入「上课提醒」节（2026-10-07 收口）：单行节卡没必要单独立卡，
            // 语义上它是提醒能不能显示的前置条件
            SettingsSection(
                title = "上课提醒",
                subtitle = "按当前课表计算，换课表后自动跟随",
            ) {
                Spacer(Modifier.height(8.dp))
                SettingSwitchRow(
                    title = "开启上课提醒",
                    subtitle = "上课前提醒 + 上课时刻提醒，点击通知直达 App",
                    checked = enabled,
                    onCheckedChange = { value ->
                        viewModel.setEnabled(value)
                        if (value) requestNotifPermission()
                    },
                    icon = HugeIcons.BellRing,
                )
                SettingItem(
                    title = "提前量",
                    subtitle = "提前多少分钟提醒",
                    value = ReminderDefaults.leadLabel(lead),
                    onClick = { showLeadPicker = true },
                    icon = HugeIcons.AlarmClock,
                )
                SettingItem(
                    title = "系统通知权限",
                    subtitle = if (notifEnabled.value) "已开启" else "未开启 · 未授权时提醒不会显示",
                    onClick = { openNotificationSettings(context) },
                    icon = HugeIcons.Notification01,
                )
            }

            // 作业截止提醒（DESIGN §3.11）：独立于上课提醒的开关，调度共用同一闹钟
            SettingsSection(
                title = "作业截止提醒",
                subtitle = "只提醒未完成、未过期的作业",
            ) {
                SettingSwitchRow(
                    title = "开启作业截止提醒",
                    subtitle = "截止前一天 20:00 与当天 20:00 提醒未完成的作业",
                    checked = homeworkEnabled,
                    onCheckedChange = { value ->
                        viewModel.setHomeworkEnabled(value)
                        if (value) requestNotifPermission()
                    },
                    icon = HugeIcons.Task01,
                )
            }

            // 说明卡：只留运行行为里用户测得到、且别处没说的两条——省电推迟（对应
            // 权限设置的改善出口）与重启自动恢复
            SettingsSection(title = "说明") {
                SettingsNote("提醒可能被系统省电推迟；在「权限设置」允许忽略电池优化可改善")
                SettingsNote("手机重启后自动恢复，无需打开 App")
            }
        }
    }

    if (showLeadPicker) {
        WheelValueDialog(
            title = "提前量",
            values = ReminderDefaults.LEAD_CHOICES.map { ReminderDefaults.leadLabel(it) },
            initialIndex = ReminderDefaults.LEAD_CHOICES.indexOf(lead).coerceAtLeast(0),
            onConfirm = { index ->
                showLeadPicker = false
                viewModel.setLeadMinutes(ReminderDefaults.LEAD_CHOICES[index])
            },
            onDismiss = { showLeadPicker = false },
        )
    }
}

/** 跳系统应用通知设置；不支持时退回应用详情页（与小组件页的「尽力而为」同一策略）。 */
private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    try {
        // 二级页窗口拉系统设置也走 startActivityOutsideApp：抑制右推入过渡，
        // 不然从设置回来时本页会凭空重放一次入场动画（同 AboutScreen 的理由）
        context.startActivityOutsideApp(intent)
    } catch (_: Exception) {
        runCatching {
            context.startActivityOutsideApp(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.fromParts("package", context.packageName, null)),
            )
        }
    }
}

class ReminderSettingsViewModel(
    private val repo: ScheduleRepository,
) : ViewModel() {

    // 初值阻塞读真值：stateIn 初值若写 false/默认分钟，开过提醒的用户进页会先看到
    // 关、真值到达后再跳开（MeViewModel 2026-09-28 同款反馈）。DataStore 读过一次后
    // 常驻内存，这里只是再收一次缓存。
    val enabled: StateFlow<Boolean> = repo.reminderEnabled
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            runBlocking { repo.reminderEnabled.first() },
        )

    val leadMinutes: StateFlow<Int> = repo.reminderLeadMinutes
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            runBlocking { repo.reminderLeadMinutes.first() },
        )

    /** 作业截止提醒开关（DESIGN §3.11）。与上课提醒开关互相独立，默认关。 */
    val homeworkEnabled: StateFlow<Boolean> = repo.homeworkReminderEnabled
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            runBlocking { repo.homeworkReminderEnabled.first() },
        )

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun consumeMessage() {
        _message.value = null
    }

    fun setEnabled(value: Boolean) {
        viewModelScope.launch { repo.setReminderEnabled(value) }
    }

    fun setHomeworkEnabled(value: Boolean) {
        viewModelScope.launch { repo.setHomeworkReminderEnabled(value) }
    }

    fun setLeadMinutes(minutes: Int) {
        viewModelScope.launch { repo.setReminderLeadMinutes(minutes) }
    }

    fun onPermissionDenied() {
        _message.value = "未授予通知权限，提醒不会显示；可在系统设置里重新开启"
    }

    class Factory(private val repo: ScheduleRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ReminderSettingsViewModel(repo) as T
    }
}
