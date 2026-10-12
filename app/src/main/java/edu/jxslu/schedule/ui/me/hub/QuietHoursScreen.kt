package edu.jxslu.schedule.ui.me.hub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import edu.jxslu.schedule.domain.QuietWindow
import edu.jxslu.schedule.domain.QuietWindows
import edu.jxslu.schedule.ui.common.AppCard
import edu.jxslu.schedule.ui.common.SettingsSection
import edu.jxslu.schedule.ui.common.SettingItem
import edu.jxslu.schedule.ui.common.SettingsNote
import edu.jxslu.schedule.ui.common.SettingSwitchRow
import edu.jxslu.schedule.ui.common.TimeWheelDialog
import kotlinx.coroutines.launch

/**
 * 免扰时段管理页（DESIGN §3.7，2026-10-07）：预置夜间休息 / 午休两个时段，
 * 可编辑、可停用、可删除、可再加。时段内的系统通知不立即发出，推迟到时段
 * 结束后补发（队列与补发见 `ui/reminder/QuietHoursGate`）。
 *
 * 配置存 DataStore（`quiet_hours_json` / `quiet_hours_enabled`，缺键回预置两时段）；
 * 停用的窗口保留在列表里，用户不用每次重建。上课等「时刻型」提醒若补发时事件
 * 已过期（>10 分钟）会静默丢弃，页面说明里写清。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuietHoursScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { Graph.repository(context) }
    val scope = rememberCoroutineScope()

    // null = DataStore 首帧未到（先渲染占位，避免「预置时段闪一下又消失」）
    val enabled by repo.quietHoursEnabled.collectAsStateWithLifecycle(initialValue = true)
    val windowsState by repo.quietHoursWindows.collectAsStateWithLifecycle(initialValue = null)

    var editing by remember { mutableStateOf<QuietWindow?>(null) }
    var deleting by remember { mutableStateOf<QuietWindow?>(null) }

    fun persist(next: List<QuietWindow>) {
        scope.launch { repo.setQuietHoursWindows(next) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("免扰时段", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 列表未载入时禁用：载入前保存会以空表为底，把 DataStore 里
                    // 全部既有时段静默覆盖成一条新时段
                    IconButton(
                        onClick = {
                            editing = QuietWindow(id = "", label = "", startMinute = 9 * 60, endMinute = 12 * 60)
                        },
                        enabled = windowsState != null,
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "添加时段")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SettingsSection(title = "总开关", subtitle = "关闭后所有通知回到立即发送，时段配置保留") {
                    SettingSwitchRow(
                        title = "免扰时段",
                        subtitle = "时段内的提醒推迟到时段结束后发出",
                        checked = enabled,
                        onCheckedChange = { value -> scope.launch { repo.setQuietHoursEnabled(value) } },
                    )
                }
            }
            item {
                SettingsSection(
                    title = "时段",
                    subtitle = "预置两个可改；可添加更多（支持跨零点，如 22:00 到次日 07:00）",
                ) {
                    val windows = windowsState
                    if (windows == null) {
                        Text(
                            "正在读取…",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    } else {
                        windows.forEach { window ->
                            WindowRow(
                                window = window,
                                onToggle = { value ->
                                    persist(windows.map {
                                        if (it.id == window.id) it.copy(enabled = value) else it
                                    })
                                },
                                onClick = { editing = window },
                                onDelete = if (windows.size > 1) {
                                    { deleting = window }
                                } else null,
                            )
                        }
                    }
                }
            }
            item {
                SettingsNote("时段内通知推迟到结束后发出；已过点的提醒不补发，收件箱不受影响")
            }
        }
    }

    editing?.let { current ->
        WindowEditDialog(
            initial = current,
            isNew = current.id.isEmpty(),
            onSave = { saved ->
                editing = null
                val next = if (saved.id.isEmpty()) {
                    windowsState.orEmpty() + saved.copy(id = QuietWindows.newId())
                } else {
                    windowsState.orEmpty().map { if (it.id == saved.id) saved else it }
                }
                persist(next)
            },
            onDismiss = { editing = null },
        )
    }

    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除这个时段？") },
            text = { Text("「${target.label}」将从列表里移除；预置时段删除后也可重新添加。") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    persist(windowsState.orEmpty().filterNot { it.id == target.id })
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("取消") }
            },
        )
    }
}

/** 一个时段行：名称 + 时间范围 + 行内启停开关；点行进编辑，可删除（保底留一个）。 */
@Composable
private fun WindowRow(
    window: QuietWindow,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    AppCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    window.label.ifBlank { "未命名时段" },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    windowText(window),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
            androidx.compose.material3.Switch(
                checked = window.enabled,
                onCheckedChange = onToggle,
            )
            if (onDelete != null) {
                TextButton(onClick = onDelete) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/** 「22:00 – 次日 07:00」样式的时间范围（跨零点自动带「次日」）。 */
private fun windowText(window: QuietWindow): String {
    val start = QuietWindows.clockText(window.startMinute)
    val end = QuietWindows.clockText(window.endMinute)
    val endText = if (window.endMinute <= window.startMinute) "次日 $end" else end
    return "$start – $endText" + if (window.enabled) "" else " · 已停用"
}

/** 添加 / 编辑弹窗：名称 + 起止两个滚轮时间选择（「时:分」双滚轮，滚着点确定取当下值）。 */
@Composable
private fun WindowEditDialog(
    initial: QuietWindow,
    isNew: Boolean,
    onSave: (QuietWindow) -> Unit,
    onDismiss: () -> Unit,
) {
    var label by remember { mutableStateOf(initial.label) }
    var startMinute by remember { mutableStateOf(initial.startMinute) }
    var endMinute by remember { mutableStateOf(initial.endMinute) }
    // null = 未在选时间；"start" / "end" = 当前在编辑哪一端
    var picking by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "添加时段" else "编辑时段") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("名称（如 夜间休息）") },
                    singleLine = true,
                )
                TimeRow(
                    label = "开始",
                    text = QuietWindows.clockText(startMinute),
                    onClick = { picking = "start" },
                )
                TimeRow(
                    label = "结束",
                    text = QuietWindows.clockText(endMinute),
                    onClick = { picking = "end" },
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (!QuietWindows.validate(startMinute, endMinute)) {
                        error = "开始与结束不能相同"
                        return@TextButton
                    }
                    onSave(
                        initial.copy(
                            id = if (isNew) "" else initial.id,
                            label = label.trim().ifBlank { "自定义时段" },
                            startMinute = startMinute,
                            endMinute = endMinute,
                        ),
                    )
                },
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )

    picking?.let { which ->
        TimeWheelDialog(
            title = if (which == "start") "开始时间" else "结束时间",
            initial = QuietWindows.clockText(if (which == "start") startMinute else endMinute),
            onConfirm = { value ->
                val minute = QuietWindows.minutesFromClockText(value)
                if (which == "start") startMinute = minute else endMinute = minute
                error = null
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
}

@Composable
private fun TimeRow(label: String, text: String, onClick: () -> Unit) {
    SettingItem(
        title = label,
        subtitle = "点按选择时间",
        onClick = onClick,
        showArrow = false,
        value = text,
    )
}
