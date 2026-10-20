package edu.jxslu.schedule.ui.me

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.jxslu.schedule.BuildConfig
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.OnboardingActivity
import edu.jxslu.schedule.R
import edu.jxslu.schedule.ui.common.AppCard
import edu.jxslu.schedule.ui.common.LoadingHint
import edu.jxslu.schedule.ui.common.LocalBottomBarClearance
import edu.jxslu.schedule.ui.common.SettingItem
import edu.jxslu.schedule.ui.common.SettingsSection
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import edu.jxslu.schedule.ui.common.rememberResumeTick
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.BellRing
import me.rerere.hugeicons.stroke.CalendarSetting01
import me.rerere.hugeicons.stroke.CreditCard
import me.rerere.hugeicons.stroke.Book02
import me.rerere.hugeicons.stroke.InformationCircle
import me.rerere.hugeicons.stroke.Settings01
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff

/**
 * 设置页（DESIGN §3.3）：**两张节卡、六入口**，没有账号卡。
 *
 * 2026-10-20 起它不再是主窗口的隐藏 Tab，而是独立窗口 `SettingsActivity`：
 * 身份与登录状态统一在左侧栏显示，设置页只留「去哪里配」。
 *
 * - 「功能」：课表 / 学习 / 校园服务 / 提醒与桌面——「去用」；
 * - 「设置」：通用设置 / 关于——「去配」。
 *
 * 每个分区收进独立汇总二级页（`ui/me/hub/`），根页面只留实时副标题——
 * 课表行锚定当前课表名与周次，学习行带笔记/作业计数，其余行给职责描述。
 *
 * 进页仍会做一次「班级为空就补抓」的 ProfileSync（闸门在 `ProfileSyncRules`，
 * 每天最多一次、失败静默）——左侧栏那行身份（姓名 / 班级）靠它填。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenGeneralSettings: () -> Unit = {},
    onOpenTimetableHub: () -> Unit = {},
    onOpenLearningHub: () -> Unit = {},
    onOpenWidgetCalendarHub: () -> Unit = {},
    onOpenExtensionServices: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    /** 从侧栏齿轮进来时给返回箭头（2026-10-20 起独立窗口，恒有）。 */
    onBack: (() -> Unit)? = null,
    viewModel: MeViewModel = viewModel(
        factory = MeViewModel.Factory(Graph.repository(LocalContext.current)),
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // 笔记/作业计数：仓库直订冷 Flow（与旧版同范式，不新开 ViewModel）
    val noteGroups by remember { Graph.noteRepository(context) }.observeGroups()
        .collectAsStateWithLifecycle(initialValue = null)
    val homeworkGroups by remember { Graph.homeworkRepository(context) }.observeGroups()
        .collectAsStateWithLifecycle(initialValue = null)

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
            // 账号卡已移除（2026-10-20）：身份与登录状态统一在左侧栏显示，设置页只留
            // 「去哪里配」。姓名/班级的后台补抓保留一次——侧栏那行身份要靠它填。
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

            // 入口顺序（2026-10-07 重排）：功能入口在前（课表 / 学习 / 校园服务 /
            // 提醒与桌面），配置类下沉（通用设置 / 关于）——两张卡对应「去用 / 去配」
            SettingsSection(title = "功能") {
                val weekSuffix = if (state.currentWeek > 0) " · 第 ${state.currentWeek} 周" else ""
                SettingItem(
                    title = "课表",
                    subtitle = "当前：${state.timetableName.ifBlank { "—" }}$weekSuffix",
                    icon = HugeIcons.CalendarSetting01,
                    onClick = onOpenTimetableHub,
                )
                val noteCount = noteGroups?.sumOf { it.count } ?: 0
                val pendingCount = homeworkGroups?.sumOf { it.pending } ?: 0
                SettingItem(
                    title = "学习",
                    subtitle = when {
                        noteCount == 0 && pendingCount == 0 -> "笔记 · 作业 · 暂无内容"
                        pendingCount > 0 -> "笔记 $noteCount 篇 · $pendingCount 项作业未完成"
                        else -> "笔记 $noteCount 篇 · 作业"
                    },
                    icon = HugeIcons.Book02,
                    onClick = onOpenLearningHub,
                )
                SettingItem(
                    title = "校园服务",
                    subtitle = "学工 · 校历",
                    icon = HugeIcons.CreditCard,
                    onClick = onOpenExtensionServices,
                )
                SettingItem(
                    title = "提醒与桌面",
                    subtitle = "上课提醒 · 桌面小组件 · 日历同步",
                    icon = HugeIcons.BellRing,
                    onClick = onOpenWidgetCalendarHub,
                )
            }
            SettingsSection(title = "设置") {
                SettingItem(
                    title = "通用设置",
                    subtitle = "外观 · 布局 · 页面 · 功能",
                    icon = HugeIcons.Settings01,
                    onClick = onOpenGeneralSettings,
                )
                SettingItem(
                    title = "关于",
                    subtitle = "版本 v${BuildConfig.VERSION_NAME} · 免责声明 · 权限",
                    icon = HugeIcons.InformationCircle,
                    onClick = onOpenAbout,
                )
            }
        }
    }
}
