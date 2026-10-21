package edu.jxslu.schedule.ui.me

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.jxslu.schedule.BuildConfig
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.R
import edu.jxslu.schedule.ui.common.LoadingHint
import edu.jxslu.schedule.ui.common.LocalBottomBarClearance
import edu.jxslu.schedule.ui.common.SettingItem
import edu.jxslu.schedule.ui.common.SettingsSection
import edu.jxslu.schedule.ui.common.rememberResumeTick
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Agreement01
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.BellRing
import me.rerere.hugeicons.stroke.CalendarSetting01
import me.rerere.hugeicons.stroke.InformationCircle
import me.rerere.hugeicons.stroke.Palette
import me.rerere.hugeicons.stroke.Settings01
import me.rerere.hugeicons.stroke.Shield01

/**
 * 设置页（DESIGN §3.3）：独立窗口里的入口页。
 *
 * 2026-10-21 起按对象重排为四组：应用 → 课表 → 通知 → 关于。学习与校园服务
 * 不再从这里进：笔记 / 作业 / 账号在左侧栏，成绩 / 考试 / 课表导入等教务数据
 * 在教务 Tab。设置页只留仍需要配置或查看的入口。
 *
 * 应用组拆成「外观与交互」「功能」两页；关于组拆成「权限与存储」「关于与更新」
 * 「条款与声明」三页，避免一个页面里混着主题、更新、缓存和条款。
 *
 * 进页仍会做一次「班级为空就补抓」的 ProfileSync（闸门在 `ProfileSyncRules`，
 * 每天最多一次、失败静默）——左侧栏那行身份（姓名 / 班级）靠它填。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenAppearanceSettings: () -> Unit = {},
    onOpenFeatureSettings: () -> Unit = {},
    onOpenTimetableHub: () -> Unit = {},
    onOpenWidgetCalendarHub: () -> Unit = {},
    onOpenPermissionsStorage: () -> Unit = {},
    onOpenAboutUpdate: () -> Unit = {},
    onOpenTerms: () -> Unit = {},
    /** 从侧栏齿轮进来时给返回箭头（独立窗口，恒有）。 */
    onBack: (() -> Unit)? = null,
    viewModel: MeViewModel = viewModel(
        factory = MeViewModel.Factory(Graph.repository(LocalContext.current)),
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets.statusBars,
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(HugeIcons.ArrowLeft01, contentDescription = "返回")
                        }
                    }
                },
                title = { Text(stringResource(R.string.settings_title)) },
            )
        },
    ) { padding ->
        if (state.loading) {
            LoadingHint("正在读取设置", modifier = Modifier.fillMaxSize())
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
                // 悬浮底栏的净空加在滚动内容上（DESIGN §4.22），最后一项顶出胶囊。
                .padding(bottom = LocalBottomBarClearance.current),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val prefsStore = remember { Graph.displayPrefs(context) }
            val resumeTick = rememberResumeTick()
            val profileClass by remember {
                prefsStore.profileClass
            }.collectAsState(initial = "")
            LaunchedEffect(resumeTick) {
                if (profileClass.isBlank()) {
                    runCatching { Graph.profileSync(context).syncOnce() }
                }
            }

            SettingsSection(title = "应用") {
                SettingItem(
                    title = "外观与交互",
                    subtitle = "主题 · 字体 · 导航 · 悬浮球",
                    icon = HugeIcons.Palette,
                    onClick = onOpenAppearanceSettings,
                )
                SettingItem(
                    title = "功能",
                    subtitle = "AI 调课 · 天气 · 快捷方式 · 百宝箱",
                    icon = HugeIcons.Settings01,
                    onClick = onOpenFeatureSettings,
                )
            }

            SettingsSection(title = "课表") {
                val weekSuffix = if (state.currentWeek > 0) " · 第 ${state.currentWeek} 周" else ""
                SettingItem(
                    title = "课表",
                    subtitle = "当前：${state.timetableName.ifBlank { "—" }}$weekSuffix",
                    icon = HugeIcons.CalendarSetting01,
                    onClick = onOpenTimetableHub,
                )
            }

            SettingsSection(title = "通知") {
                SettingItem(
                    title = "提醒与桌面",
                    subtitle = "上课提醒 · 桌面小组件 · 日历同步",
                    icon = HugeIcons.BellRing,
                    onClick = onOpenWidgetCalendarHub,
                )
            }

            SettingsSection(title = "关于") {
                SettingItem(
                    title = "权限与存储",
                    subtitle = "权限 · 缓存 · 诊断日志",
                    icon = HugeIcons.Shield01,
                    onClick = onOpenPermissionsStorage,
                )
                SettingItem(
                    title = "关于与更新",
                    subtitle = "版本 v${BuildConfig.VERSION_NAME} · 检查更新 · 自动检查",
                    icon = HugeIcons.InformationCircle,
                    onClick = onOpenAboutUpdate,
                )
                SettingItem(
                    title = "条款与声明",
                    subtitle = "用户须知 · 免责声明 · 充值风险",
                    icon = HugeIcons.Agreement01,
                    onClick = onOpenTerms,
                )
            }
        }
    }
}
