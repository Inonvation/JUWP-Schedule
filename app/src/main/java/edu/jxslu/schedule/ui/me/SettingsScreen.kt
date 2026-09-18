package edu.jxslu.schedule.ui.me

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.jxslu.schedule.BuildConfig
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.R
import edu.jxslu.schedule.domain.ThemeMode
import edu.jxslu.schedule.ui.common.EmptyHint
import edu.jxslu.schedule.ui.common.SettingItem
import edu.jxslu.schedule.ui.common.SettingSwitchRow
import edu.jxslu.schedule.ui.common.SettingsIconBadge
import edu.jxslu.schedule.ui.common.SettingsSection
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Clock01
import me.rerere.hugeicons.stroke.Database
import me.rerere.hugeicons.stroke.Eye
import me.rerere.hugeicons.stroke.GlassWater
import me.rerere.hugeicons.stroke.GridView
import me.rerere.hugeicons.stroke.Import
import me.rerere.hugeicons.stroke.InformationCircle
import me.rerere.hugeicons.stroke.Palette
import me.rerere.hugeicons.stroke.Vibrate

/** 开水按钮点击模式（设置页 chips）。 */
private enum class WaterClickMode(val label: String) {
    Single("单击"),
    Double("双击"),
}

/**
 * 「我的」= 设置枢纽（DESIGN §3.3）。
 *
 * 形态对齐拾光：**通用项内嵌 + 高级功能跳子页**。
 * - 通用（全局）：外观主题、触感反馈
 * - 课表（当前课表级）：课表管理 / 课表设置（学期·作息）/ 显示设置 / 教务导入
 * - 数据 / 胖乖生活 / 关于
 *
 * 重构说明：此前主题三选一是裸 chips、各分组之间夹分割线、页尾再挂说明文字，
 * 层级靠猜。现在统一为「图标底座 + 标题 + 说明 + 尾部值」的行式布局，
 * 每个分区一张卡，说明并入分区副标题，整页只有一种视觉节奏。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenJwImport: () -> Unit = {},
    onOpenTimetableManage: () -> Unit = {},
    onOpenTimetableSettings: () -> Unit = {},
    onOpenDisplaySettings: () -> Unit = {},
    onOpenDataSettings: () -> Unit = {},
    onOpenWater: () -> Unit = {},
    /** 胖乖登录态（由外层传入，仅决定卡片文案）；登录/退出在开水页内完成 */
    waterLoggedIn: Boolean = false,
    viewModel: MeViewModel = viewModel(
        factory = MeViewModel.Factory(Graph.repository(LocalContext.current)),
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val haptics = rememberAppHaptics()

    Scaffold(
        // 根因：外层 JuwApp Scaffold 无 topBar，contentWindowInsets（systemBars）已垫了一个
        // 状态栏高度；TopAppBar 默认 windowInsets 再消费一次 → 顶栏上方双倍空白。
        // 顶部 inset 统一只由外层消费，这里归零。
        // 内容 inset 同理归零：底部导航栏 inset 已由外层（底栏高度）提供，
        // 内层再消费一次就是底部双倍空白。
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = { Text(stringResource(R.string.tab_me)) },
            )
        },
    ) { padding ->
        if (state.loading) {
            EmptyHint("加载中…", "读取设置")
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- 通用（全局） ----
            SettingsSection(
                title = "通用",
                subtitle = "全局设置，对所有课表生效",
            ) {
                // 主题：标题行内嵌三段选择。放同一行是因为它只有三个短选项，
                // 单独占一整行反而把「通用」卡撑高，且与触感开关行高不齐。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SettingsIconBadge(icon = HugeIcons.Palette)
                    Text("外观主题", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ThemeMode.entries.forEach { mode ->
                            FilterChip(
                                selected = state.themeMode == mode,
                                onClick = {
                                    haptics.toggle()
                                    viewModel.setThemeMode(mode)
                                },
                                label = { Text(mode.label, style = MaterialTheme.typography.labelMedium) },
                            )
                        }
                    }
                }
                SettingSwitchRow(
                    title = "触感反馈",
                    subtitle = "点击按钮与开关时轻微振动",
                    checked = state.displayPrefs.hapticsEnabled,
                    onCheckedChange = viewModel::setHapticsEnabled,
                    icon = HugeIcons.Vibrate,
                )
            }

            // ---- 课表（当前课表级） ----
            SettingsSection(
                title = "课表",
                subtitle = "当前：${state.timetableName.ifBlank { "—" }} · 下列项随切换课表变化",
            ) {
                SettingItem(
                    title = "课表管理",
                    subtitle = "新建、切换、复制、删除",
                    icon = HugeIcons.GridView,
                    onClick = onOpenTimetableManage,
                )
                SettingItem(
                    title = "课表设置",
                    subtitle = "学期起止 · 作息表",
                    icon = HugeIcons.Clock01,
                    onClick = onOpenTimetableSettings,
                )
                SettingItem(
                    title = "显示设置",
                    subtitle = "字号 · 格子样式 · 显示开关",
                    icon = HugeIcons.Eye,
                    onClick = onOpenDisplaySettings,
                )
                SettingItem(
                    title = "教务导入",
                    subtitle = "登录学校教务，解析理论 / 实验课表",
                    icon = HugeIcons.Import,
                    onClick = onOpenJwImport,
                )
            }

            // ---- 数据 ----
            SettingsSection(
                title = "数据",
                subtitle = "JSON 与拾光互通，可互导备份",
            ) {
                SettingItem(
                    title = "课表数据",
                    subtitle = "导出 / 导入 JSON · 清空当前课表课程",
                    icon = HugeIcons.Database,
                    onClick = onOpenDataSettings,
                )
            }

            // ---- 胖乖生活（全局：登录态不随课表变化）----
            SettingsSection(
                title = "胖乖生活",
                subtitle = "一键开水 · 余额 · 订单快照（非学校官方功能）",
            ) {
                SettingItem(
                    title = "开水",
                    subtitle = if (waterLoggedIn) "已登录 · 开水 / 余额 / 订单" else "未登录 · 点击登录胖乖生活",
                    icon = HugeIcons.GlassWater,
                    onClick = onOpenWater,
                )
                // 双击确认防误触：默认双击，嫌烦可改单击（与今日快捷卡、开水页共用）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SettingsIconBadge(icon = HugeIcons.GlassWater)
                    Column(Modifier.weight(1f)) {
                        Text("开水点击方式", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "双击确认可防单击误触出水",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        WaterClickMode.entries.forEach { mode ->
                            val selected = when (mode) {
                                WaterClickMode.Single -> !state.displayPrefs.waterRequireDoubleClick
                                WaterClickMode.Double -> state.displayPrefs.waterRequireDoubleClick
                            }
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    haptics.toggle()
                                    viewModel.setWaterRequireDoubleClick(mode == WaterClickMode.Double)
                                },
                                label = { Text(mode.label, style = MaterialTheme.typography.labelMedium) },
                            )
                        }
                    }
                }
            }

            // ---- 关于 ----
            SettingsSection(title = "关于") {
                SettingItem(
                    title = "关于 水贝贝",
                    subtitle = "学生自用课表，非学校官方应用。" +
                        "教务/第三方接口风险自负。v${BuildConfig.VERSION_NAME}",
                    icon = HugeIcons.InformationCircle,
                    showArrow = false,
                )
            }
        }
    }
}
