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
import edu.jxslu.schedule.ui.me.MeViewModel
import edu.jxslu.schedule.ui.theme.paletteSwatch
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Blur
import me.rerere.hugeicons.stroke.ColorPicker
import me.rerere.hugeicons.stroke.Droplet
import me.rerere.hugeicons.stroke.Flash
import me.rerere.hugeicons.stroke.Home01
import me.rerere.hugeicons.stroke.Palette
import me.rerere.hugeicons.stroke.ScooterElectric
import me.rerere.hugeicons.stroke.ShowerHead
import me.rerere.hugeicons.stroke.SwatchBook
import me.rerere.hugeicons.stroke.Vibrate
import me.rerere.hugeicons.stroke.Wallet03

/**
 * 我的 → 通用设置（DESIGN §3.3，2026-09-28 二次改版）。
 *
 * 全局观感项 + **功能开关**。主题等读写全部走 [MeViewModel]（与旧「我的」共用
 * 一个 VM，改主题立即生效，返回主界面无需刷新）。
 *
 * 「功能开关」（本轮新增）：把散在各处的「功能在 App 内是否出现」收成一处——
 * 快捷方式入口 + 快趣出行码 / 胖乖生活 / 趣智校园三个卡片开关。开关语义 =
 * 关闭后该功能的**全部入口与卡片一起隐藏**（今日页卡片、「我的」账号卡对应格、
 * 校园服务页入口；DataStore 同一键，与原各处开关零迁移）。胖乖 / 趣智
 * 页尾部的同名开关随之移除（一个偏好只留一处管理入口）。
 *
 * 「主题配色」（DESIGN §3.3）：内置六套配色的色卡弹层，动态取色开着（默认）时
 * 置灰——Material You 优先，选中的配色只有在关掉动态取色后才生效；选中即写偏好，
 * 弹层开着也能看到身后的页面实时换色。
 *
 * 悬浮导航栏与启动页**重启生效**（MainActivity 在首帧前把形态读死，DESIGN §4.22），
 * 标题旁常挂 [SettingsTag]（本轮改版：说明文字容易被扫视漏掉），切完当场提示。
 * 成绩查询 2026-09-24 挪入「学习」页（DESIGN §3.11）。
 *
 * 启动页（DESIGN §3.3）同口径重启生效；选项集跟随底栏 Tab——生活页关掉时
 * 「生活」那一项不列（`StartPage.visiblePages`），选中态显示的是**实际生效页**
 * （`StartPage.effectivePage`），与启动落点同一个口径。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneralSettingsScreen(
    onBack: () -> Unit,
    /** 功能开关节 → 快捷方式编辑页（原扩展服务入口迁入）。 */
    onOpenShortcuts: () -> Unit,
    viewModel: MeViewModel = viewModel(
        factory = MeViewModel.Factory(Graph.repository(LocalContext.current)),
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val haptics = rememberAppHaptics()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showPaletteSheet by remember { mutableStateOf(false) }
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
                // 主题配色：动态取色开着时被 Material You 盖住，置灰并说明原因
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
            }

            SettingsSection(title = "布局") {
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
            }

            SettingsSection(title = "页面与导航") {
                // 启动页：选项集 = 底栏 Tab 集，生活页关掉时同步少一项（DESIGN §3.3）
                val startPages = StartPage.visiblePages(state.displayPrefs.lifeTabEnabled)
                val effectiveStart = StartPage.effectivePage(
                    state.displayPrefs.startPage,
                    state.displayPrefs.lifeTabEnabled,
                )
                SettingChoiceRow(
                    title = "启动页",
                    titleTag = "重启生效",
                    subtitle = "打开应用时先显示这一页",
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
                SettingSwitchRow(
                    title = "生活页",
                    subtitle = "底栏「生活」：一卡通 · 付款码 · 寝室电费",
                    checked = state.displayPrefs.lifeTabEnabled,
                    onCheckedChange = viewModel::setLifeTabEnabled,
                    icon = HugeIcons.Wallet03,
                )
            }

            // 功能开关（2026-09-28 收拢）：原「扩展服务 → 今日页」节 + 服务页尾部开关
            SettingsSection(
                title = "功能开关",
                subtitle = "关闭后该功能的全部入口与卡片一起隐藏",
            ) {
                // 快捷方式不是单个开关：条目编辑页里有「在今日页显示」总开关，
                // 这里给入口行而不是开关行，编辑与显隐管理都在那一页
                SettingItem(
                    title = "快捷方式",
                    subtitle = "编辑今日页快捷入口 · 预设与自定义",
                    icon = HugeIcons.Flash,
                    onClick = onOpenShortcuts,
                )
                SettingSwitchRow(
                    title = "快趣出行码",
                    subtitle = "今日页骑行二维码卡 · 非学校官方功能",
                    checked = state.displayPrefs.ebikeCardEnabled,
                    onCheckedChange = viewModel::setEbikeCardEnabled,
                    icon = HugeIcons.ScooterElectric,
                )
                SettingSwitchRow(
                    title = "胖乖生活",
                    subtitle = "今日页开水卡 · 我的页账号卡入口",
                    checked = state.displayPrefs.waterCardEnabled,
                    onCheckedChange = viewModel::setWaterCardEnabled,
                    icon = HugeIcons.Droplet,
                )
                SettingSwitchRow(
                    title = "趣智校园",
                    subtitle = "今日页趣智卡 · 我的页账号卡入口",
                    checked = state.displayPrefs.qzxyCardEnabled,
                    onCheckedChange = viewModel::setQzxyCardEnabled,
                    icon = HugeIcons.ShowerHead,
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

/**
 * 主题配色色卡弹层（DESIGN §3.3）：每个配色一行——四个色点（主色 / 容器 / 次要 / 底色，
 * 取浅色系）+ 名称 + 选中勾。选中只写偏好不关弹层，方便连续试色；
 * 身后页面实时换色，弹层自身也跟着换（同一主题根）。
 */
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
