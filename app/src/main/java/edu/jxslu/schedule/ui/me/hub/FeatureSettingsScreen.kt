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
import edu.jxslu.schedule.ui.common.SettingSwitchRow
import edu.jxslu.schedule.ui.common.SettingsSection
import edu.jxslu.schedule.ui.me.MeViewModel
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiBrain01
import me.rerere.hugeicons.stroke.Flash
import me.rerere.hugeicons.stroke.SunCloud01
import me.rerere.hugeicons.stroke.Toolbox

/**
 * 设置 → 应用 → 功能（DESIGN §3.3）。
 *
 * 这里只留仍在使用中的功能入口与显隐开关。今日页快趣 / 胖乖 / 趣智三张卡
 * 已经不再渲染，对应开关已删除；生活服务统一从左侧栏进入。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeatureSettingsScreen(
    onBack: () -> Unit,
    onOpenShortcuts: () -> Unit,
    onOpenAiSettings: () -> Unit,
    onOpenWeatherSettings: () -> Unit,
    viewModel: MeViewModel = viewModel(
        factory = MeViewModel.Factory(Graph.repository(LocalContext.current)),
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("功能") },
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
                title = "服务与工具",
                subtitle = "关闭后，该功能的全部入口与卡片一起隐藏",
            ) {
                SettingItem(
                    title = "AI 调课服务",
                    subtitle = "服务地址 · 模型 · API Key · 教师名脱敏",
                    icon = HugeIcons.AiBrain01,
                    onClick = onOpenAiSettings,
                )
                SettingItem(
                    title = "天气",
                    subtitle = "今日页顶栏天气提示 · 数据来自 Open-Meteo",
                    icon = HugeIcons.SunCloud01,
                    onClick = onOpenWeatherSettings,
                )
                SettingItem(
                    title = "快捷方式",
                    subtitle = "编辑今日页快捷入口 · 预设与自定义",
                    icon = HugeIcons.Flash,
                    onClick = onOpenShortcuts,
                )
                SettingSwitchRow(
                    title = "百宝箱",
                    subtitle = "今日页「+」里的工具：水印 · 拼接 · 菜鸟批量查",
                    checked = state.displayPrefs.toolboxEnabled,
                    onCheckedChange = viewModel::setToolboxEnabled,
                    icon = HugeIcons.Toolbox,
                )
            }
        }
    }
}
