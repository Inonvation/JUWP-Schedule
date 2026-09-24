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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import edu.jxslu.schedule.ui.common.SettingItem
import edu.jxslu.schedule.ui.common.SettingsSection
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.CalendarSetting01
import me.rerere.hugeicons.stroke.Layout2Row

/** 我的 → 小组件与日历（DESIGN §3.3）：小组件与日历同步都是全局项。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetCalendarHubScreen(
    onBack: () -> Unit,
    onOpenWidgetSettings: () -> Unit,
    onOpenCalendarSettings: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("小组件与日历") },
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
            SettingsSection(title = "桌面") {
                SettingItem(
                    title = "桌面小组件",
                    subtitle = "三种尺寸 · 一键添加",
                    icon = HugeIcons.Layout2Row,
                    onClick = onOpenWidgetSettings,
                )
            }
            SettingsSection(title = "日历") {
                SettingItem(
                    title = "日历同步",
                    subtitle = "写入系统日历 · 提前提醒",
                    icon = HugeIcons.CalendarSetting01,
                    onClick = onOpenCalendarSettings,
                )
            }
        }
    }
}
