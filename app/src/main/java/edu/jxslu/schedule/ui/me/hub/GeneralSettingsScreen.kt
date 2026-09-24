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
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.domain.StartPage
import edu.jxslu.schedule.domain.ThemeMode
import edu.jxslu.schedule.ui.common.AppNoticeVisuals
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.NoticeTone
import edu.jxslu.schedule.ui.common.SettingChoiceRow
import edu.jxslu.schedule.ui.common.SettingSwitchRow
import edu.jxslu.schedule.ui.common.SettingsSection
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import edu.jxslu.schedule.ui.me.MeViewModel
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Blur
import me.rerere.hugeicons.stroke.ColorPicker
import me.rerere.hugeicons.stroke.Home01
import me.rerere.hugeicons.stroke.Palette
import me.rerere.hugeicons.stroke.Vibrate
import me.rerere.hugeicons.stroke.Wallet03

/**
 * 我的 → 通用设置（DESIGN §3.3，2026-09-23 汇总页）。
 *
 * 全局观感项。主题等读写全部走 [MeViewModel]（与旧「我的」共用
 * 一个 VM，改主题立即生效，返回主界面无需刷新）。
 *
 * 悬浮导航栏开关**重启生效**（MainActivity 在首帧前把形态读死，DESIGN §4.22），
 * 切完当场提示，避免用户以为没生效。成绩查询 2026-09-24 挪入「学习」页（DESIGN §3.11）。
 *
 * 启动页（DESIGN §3.3）同口径重启生效；选项集跟随底栏 Tab——生活页关掉时
 * 「生活」那一项不列（`StartPage.visiblePages`），选中态显示的是**实际生效页**
 * （`StartPage.effectivePage`），与启动落点同一个口径。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneralSettingsScreen(
    onBack: () -> Unit,
    viewModel: MeViewModel = viewModel(
        factory = MeViewModel.Factory(Graph.repository(LocalContext.current)),
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val haptics = rememberAppHaptics()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val showNotice: (String) -> Unit = { message ->
        scope.launch {
            snackbar.showSnackbar(AppNoticeVisuals(message, tone = NoticeTone.Info))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("通用设置") },
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
            SettingsSection(title = "观感") {
                SettingChoiceRow(
                    title = "外观主题",
                    icon = HugeIcons.Palette,
                    options = ThemeMode.entries.map { it.label },
                    selectedIndex = ThemeMode.entries.indexOf(state.themeMode),
                    onSelect = { index ->
                        haptics.toggle()
                        viewModel.setThemeMode(ThemeMode.entries[index])
                    },
                )
                SettingSwitchRow(
                    title = "触感反馈",
                    checked = state.displayPrefs.hapticsEnabled,
                    onCheckedChange = viewModel::setHapticsEnabled,
                    icon = HugeIcons.Vibrate,
                )
                SettingSwitchRow(
                    title = "动态取色",
                    subtitle = "跟随壁纸配色（Material You）",
                    checked = state.displayPrefs.dynamicColor,
                    onCheckedChange = viewModel::setDynamicColor,
                    icon = HugeIcons.ColorPicker,
                )
            }

            SettingsSection(title = "布局与页面") {
                SettingSwitchRow(
                    title = "悬浮导航栏",
                    subtitle = "底栏半透明，背景图透到屏幕底部（重启生效）",
                    checked = state.displayPrefs.floatingNavBar,
                    onCheckedChange = { value ->
                        viewModel.setFloatingNavBar(value)
                        showNotice("已保存，重启应用后生效")
                    },
                    icon = HugeIcons.Blur,
                )
                SettingSwitchRow(
                    title = "生活页",
                    subtitle = "底栏「生活」：一卡通 · 付款码 · 寝室电费",
                    checked = state.displayPrefs.lifeTabEnabled,
                    onCheckedChange = viewModel::setLifeTabEnabled,
                    icon = HugeIcons.Wallet03,
                )
                // 启动页：选项集 = 底栏 Tab 集，生活页关掉时同步少一项（DESIGN §3.3）
                val startPages = StartPage.visiblePages(state.displayPrefs.lifeTabEnabled)
                val effectiveStart = StartPage.effectivePage(
                    state.displayPrefs.startPage,
                    state.displayPrefs.lifeTabEnabled,
                )
                SettingChoiceRow(
                    title = "启动页",
                    subtitle = "打开应用时先显示这一页（重启生效）",
                    icon = HugeIcons.Home01,
                    options = startPages.map { it.label },
                    // 显示实际生效页而不是存储值：生活页关着时选中态落在今日页，
                    // 与下次启动的落点一致，不会出现「这里写着生活、打开却在今日」
                    selectedIndex = startPages.indexOf(effectiveStart),
                    onSelect = { index ->
                        haptics.toggle()
                        viewModel.setStartPage(startPages[index])
                        showNotice("已保存，重启应用后生效")
                    },
                )
            }

        }
    }
}
