package edu.jxslu.schedule.ui.me.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.domain.StartPage
import edu.jxslu.schedule.domain.ThemeMode
import edu.jxslu.schedule.domain.ThemePalette
import edu.jxslu.schedule.ui.common.AppNoticeVisuals
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.ImeAwareModalBottomSheet
import edu.jxslu.schedule.ui.common.NoticeTone
import edu.jxslu.schedule.ui.common.SettingChoiceRow
import edu.jxslu.schedule.ui.common.SettingItem
import edu.jxslu.schedule.ui.common.SettingSwitchRow
import edu.jxslu.schedule.ui.common.SettingsSection
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import edu.jxslu.schedule.ui.common.showNotice
import edu.jxslu.schedule.ui.me.MeViewModel
import edu.jxslu.schedule.ui.theme.paletteSwatch
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Blur
import me.rerere.hugeicons.stroke.ColorPicker
import me.rerere.hugeicons.stroke.Home01
import me.rerere.hugeicons.stroke.Palette
import me.rerere.hugeicons.stroke.SwatchBook
import me.rerere.hugeicons.stroke.TextFont
import me.rerere.hugeicons.stroke.Vibrate
import me.rerere.hugeicons.stroke.Wallet01

/**
 * 设置 → 应用 → 外观与交互（DESIGN §3.3）。
 *
 * 原「通用设置」的观感与布局、页面与导航两节；功能开关拆到
 * [FeatureSettingsScreen]。主题读写仍走 [MeViewModel]，改完立即生效。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettingsScreen(
    onBack: () -> Unit,
    viewModel: MeViewModel = viewModel(
        factory = MeViewModel.Factory(Graph.repository(LocalContext.current)),
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val floatingBallEnabled by viewModel.floatingBallEnabled.collectAsStateWithLifecycle()
    val haptics = rememberAppHaptics()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showPaletteSheet by remember { mutableStateOf(false) }
    val showNotice: (String) -> Unit = { message ->
        scope.launch {
            snackbar.showNotice(AppNoticeVisuals(message, tone = NoticeTone.Info))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("外观与交互") },
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
            SettingsSection(title = "主题与字体") {
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
                SettingItem(
                    title = "主题配色",
                    subtitle = if (state.displayPrefs.dynamicColor) {
                        "关闭动态取色后生效"
                    } else {
                        "内置配色，选中即实时生效"
                    },
                    icon = HugeIcons.SwatchBook,
                    value = state.displayPrefs.themePalette.label,
                    enabled = !state.displayPrefs.dynamicColor,
                    onClick = { showPaletteSheet = true },
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
                SettingSwitchRow(
                    title = "固定字体大小",
                    subtitle = "不随系统字体缩放；关闭后跟随系统",
                    checked = state.displayPrefs.fontScaleLocked,
                    onCheckedChange = viewModel::setFontScaleLocked,
                    icon = HugeIcons.TextFont,
                )
            }

            SettingsSection(title = "导航与悬浮") {
                SettingSwitchRow(
                    title = "悬浮导航栏",
                    titleTag = "重启生效",
                    subtitle = "底栏半透明，背景图透到屏幕底部",
                    checked = state.displayPrefs.floatingNavBar,
                    onCheckedChange = { value ->
                        viewModel.setFloatingNavBar(value)
                        showNotice("已保存，重启应用后生效")
                    },
                    icon = HugeIcons.Blur,
                )
                val startPages = StartPage.visiblePages()
                val effectiveStart = StartPage.effectivePage(state.displayPrefs.startPage)
                SettingChoiceRow(
                    title = "启动页",
                    titleTag = "重启生效",
                    subtitle = "打开应用时先显示这一页",
                    icon = HugeIcons.Home01,
                    options = startPages.map { it.label },
                    selectedIndex = startPages.indexOf(effectiveStart),
                    onSelect = { index ->
                        haptics.toggle()
                        viewModel.setStartPage(startPages[index])
                        showNotice("已保存，重启应用后生效")
                    },
                )
                SettingSwitchRow(
                    title = "悬浮球",
                    subtitle = "常驻快捷入口：付款码 · 扫一扫，可拖动",
                    checked = floatingBallEnabled,
                    onCheckedChange = viewModel::setFloatingBallEnabled,
                    icon = HugeIcons.Wallet01,
                )
            }
        }
    }

    if (showPaletteSheet) {
        ImeAwareModalBottomSheet(onDismiss = { showPaletteSheet = false }) {
            PalettePickerSheet(
                selected = state.displayPrefs.themePalette,
                onSelect = { palette ->
                    haptics.toggle()
                    viewModel.setThemePalette(palette)
                },
            )
        }
    }
}

/** 主题配色色卡弹层：选中只写偏好不关弹层，方便连续试色。 */
@Composable
private fun PalettePickerSheet(
    selected: ThemePalette,
    onSelect: (ThemePalette) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
    ) {
        Text(
            "主题配色",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        ThemePalette.entries.forEach { palette ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onSelect(palette) }
                    .padding(vertical = 12.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    paletteSwatch(palette).forEach { color ->
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(color)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    palette.label,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                if (palette == selected) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = "已选中",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}
