package edu.jxslu.schedule.ui.me.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.ui.common.SettingItem
import edu.jxslu.schedule.ui.common.SettingsSection
import edu.jxslu.schedule.ui.me.MeViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.BellRing
import me.rerere.hugeicons.stroke.CalendarSetting01
import me.rerere.hugeicons.stroke.GridView
import me.rerere.hugeicons.stroke.Shield01

/**
 * 我的 → 提醒与桌面（DESIGN §3.3，2026-09-28 自「小组件与日历」改名扩容）。
 *
 * 三行的共同抽象 = **课表数据送到哪里**：通知栏（上课提醒，自课表 hub 迁入）、
 * 桌面（小组件）、日历（日历同步）。原来「上课提醒」在课表 hub、日历的提前提醒
 * 在日历页，「提醒」这件事被拆在两处；这里并成一条线。
 *
 * 入口行右侧带实时状态（提醒开关开着给「已开启」，与根页学习行计数同口径）；
 * 尾部说明卡指路「关于 → 权限设置」——「提醒不响」是这类应用最高频的问题，
 * 三条渠道各对应什么权限一句话说清。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetCalendarHubScreen(
    onBack: () -> Unit,
    onOpenWidgetSettings: () -> Unit,
    onOpenCalendarSettings: () -> Unit,
    onOpenReminderSettings: () -> Unit,
    onOpenPermissionSettings: () -> Unit,
    viewModel: MeViewModel = viewModel(
        factory = MeViewModel.Factory(Graph.repository(LocalContext.current)),
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // 提醒行右侧状态：开关实时值（repo 流），首帧阻塞读一次避免先按默认渲染再跳
    val context = LocalContext.current
    val reminderEnabled by remember {
        Graph.repository(context).reminderEnabled
    }.collectAsStateWithLifecycle(
        initialValue = remember { runBlocking { Graph.repository(context).reminderEnabled.first() } },
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("提醒与桌面") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsSection(title = "提醒", subtitle = "把课表和作业送到通知栏") {
                SettingItem(
                    title = "上课提醒",
                    subtitle = "上课 / 作业截止通知 · 提前量可调 · 点通知直达",
                    icon = HugeIcons.BellRing,
                    value = if (reminderEnabled) "已开启" else null,
                    onClick = onOpenReminderSettings,
                )
            }
            SettingsSection(title = "桌面") {
                SettingItem(
                    title = "桌面小组件",
                    subtitle = "课表 · 校园卡电费 · 开水卡 · 一键添加",
                    icon = HugeIcons.GridView,
                    onClick = onOpenWidgetSettings,
                )
            }
            SettingsSection(title = "日历") {
                SettingItem(
                    title = "日历同步",
                    subtitle = "课表写入系统日历 · 提前提醒随同步写入",
                    icon = HugeIcons.CalendarSetting01,
                    onClick = onOpenCalendarSettings,
                )
            }

            // 渠道 → 权限对照说明卡：「提醒不响」先来这里查授权
            ChannelPermissionHint(onOpenPermissionSettings = onOpenPermissionSettings)
        }
    }
}

/** 尾部说明卡：三条渠道与各自权限，整卡可点直达权限设置页。 */
@Composable
private fun ChannelPermissionHint(onOpenPermissionSettings: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .clickable(onClickLabel = "打开权限设置") { onOpenPermissionSettings() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "三条渠道互不依赖：提醒走通知权限，日历走日历读写，小组件随系统刷新。" +
                "遇到「没提醒 / 日历是空的」，先去查对应授权。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            HugeIcons.Shield01,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            modifier = Modifier.size(18.dp),
        )
    }
}
