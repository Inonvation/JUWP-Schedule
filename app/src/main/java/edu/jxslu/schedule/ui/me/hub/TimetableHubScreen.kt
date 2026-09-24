package edu.jxslu.schedule.ui.me.hub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.ui.common.SettingItem
import edu.jxslu.schedule.ui.common.SettingsSection
import edu.jxslu.schedule.ui.me.MeViewModel
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.BellRing
import me.rerere.hugeicons.stroke.CalendarSync
import me.rerere.hugeicons.stroke.Clock01
import me.rerere.hugeicons.stroke.Database
import me.rerere.hugeicons.stroke.GridView
import me.rerere.hugeicons.stroke.Import


/** 我的 → 课表（DESIGN §3.3）：配置 → 使用 → 数据。成绩查询 2026-09-24 挪进通用设置。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimetableHubScreen(
    onBack: () -> Unit,
    onOpenJwImport: () -> Unit,
    onOpenTimetableManage: () -> Unit,
    onOpenTimetableSettings: () -> Unit,
    onOpenDataSettings: () -> Unit,
    onOpenCourseTweak: () -> Unit,
    onOpenReminderSettings: () -> Unit,
    viewModel: MeViewModel = viewModel(
        factory = MeViewModel.Factory(Graph.repository(LocalContext.current)),
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

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
                title = "配置",
                subtitle = "当前：${state.timetableName.ifBlank { "—" }}",
            ) {
                SettingItem(
                    title = "课表管理",
                    subtitle = "新建 · 切换 · 删除",
                    icon = HugeIcons.GridView,
                    onClick = onOpenTimetableManage,
                )
                SettingItem(
                    title = "课表设置",
                    subtitle = "学期起止 · 作息时间",
                    icon = HugeIcons.Clock01,
                    onClick = onOpenTimetableSettings,
                )
                SettingItem(
                    title = "教务导入",
                    subtitle = "从学校教务拉取课表",
                    icon = HugeIcons.Import,
                    onClick = onOpenJwImport,
                )
            }

            SettingsSection(title = "使用") {
                SettingItem(
                    title = "上课提醒",
                    subtitle = "上课前提前通知，点开直达",
                    icon = HugeIcons.BellRing,
                    onClick = onOpenReminderSettings,
                )
                SettingItem(
                    title = "调课",
                    subtitle = "把某天的课调到另一天",
                    icon = HugeIcons.CalendarSync,
                    onClick = onOpenCourseTweak,
                )
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
