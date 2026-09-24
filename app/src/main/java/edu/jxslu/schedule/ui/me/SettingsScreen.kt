package edu.jxslu.schedule.ui.me

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import edu.jxslu.schedule.R
import edu.jxslu.schedule.domain.AccountMask
import edu.jxslu.schedule.ui.common.AppCard
import edu.jxslu.schedule.ui.common.LoadingHint
import edu.jxslu.schedule.ui.common.LocalBottomBarClearance
import edu.jxslu.schedule.ui.common.SettingItem
import edu.jxslu.schedule.ui.common.SettingsSection
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.CalendarSetting01
import me.rerere.hugeicons.stroke.CreditCard
import me.rerere.hugeicons.stroke.Book02
import me.rerere.hugeicons.stroke.InformationCircle
import me.rerere.hugeicons.stroke.Settings01
import me.rerere.hugeicons.stroke.GridView
import me.rerere.hugeicons.stroke.UserCircle
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff

/**
 * 「我的」= 账号条 + 六入口（DESIGN §3.3，2026-09-23 改版）。
 *
 * 每个分区收进独立汇总二级页（`ui/me/hub/`），根页面只留实时副标题——
 * 课表行锚定当前课表名，学习行带笔记/作业计数，其余行给内容概览。
 * 账号条数据源 = 水宝宝一卡通凭证（`YktCredentialStore`），遮罩口径在
 * `domain/AccountMask`；眼睛只在内存里切换完整学号，不写存储不进剪贴板。
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
                title = { Text(stringResource(R.string.tab_me)) },
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
            // 进页读一次加密凭证（EncryptedSharedPreferences 读取不便宜，别在重组里重复读）；
            // 从一卡通设置页回来（ON_RESUME）会重建主窗口组合，这里随之重读，无需刷新机制。
            val yktUsername = remember { Graph.yktCredentialStore(context).read()?.username }
            val masked = yktUsername?.let { AccountMask.maskStudentId(it) }
            if (masked != null) {
                val profileName by remember {
                    Graph.displayPrefs(context).profileName
                }.collectAsState(initial = "")
                val profileClass by remember {
                    Graph.displayPrefs(context).profileClass
                }.collectAsState(initial = "")
                AccountBar(
                    username = yktUsername.orEmpty(),
                    name = profileName,
                    className = profileClass,
                )
            }

            SettingsSection(title = "设置") {
                SettingItem(
                    title = "通用设置",
                    subtitle = "主题 · 触感 · 悬浮导航栏 · 生活页",
                    icon = HugeIcons.Settings01,
                    onClick = onOpenGeneralSettings,
                )
                SettingItem(
                    title = "课表",
                    subtitle = "当前：${state.timetableName.ifBlank { "—" }}",
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
                    title = "小组件与日历",
                    subtitle = "桌面小组件 · 课程进系统日历",
                    icon = HugeIcons.GridView,
                    onClick = onOpenWidgetCalendarHub,
                )
                SettingItem(
                    title = "扩展服务",
                    subtitle = "宿舍报修 · 快捷方式 · 出行码 · 一卡通 · 开水",
                    icon = HugeIcons.CreditCard,
                    onClick = onOpenExtensionServices,
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

/**
 * 账号条（DESIGN §3.3，2026-09-23 升级为账户卡）：头像圆标 + 姓名 +「班级 · 学号」。
 * 完整学号只存在 [username] 参数（内存）里，切眼睛不触发任何持久化；
 * 卡片本体不可点（AppCard 不传 onClick，无涟漪），交互面只有眼睛按钮。
 *
 * [name] / [className] 来自教务学籍卡（成绩导入顺带落 DataStore）；缺失时
 * 标题退回遮罩学号、副行只剩学号，显示永远不空。
 */
@Composable
private fun AccountBar(username: String, name: String, className: String) {
    var revealed by rememberSaveable { mutableStateOf(false) }
    val masked = AccountMask.maskStudentId(username).orEmpty()
    val title = name.ifBlank { masked }
    val subtitle = buildString {
        if (className.isNotBlank()) {
            append(className)
            append(" · ")
        }
        append(if (revealed) username else masked)
    }
    AppCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 头像底座：向量图标即可（DESIGN §3.3），不落任何图片文件
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = HugeIcons.UserCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { revealed = !revealed }) {
                Icon(
                    imageVector = if (revealed) HugeIcons.ViewOff else HugeIcons.View,
                    contentDescription = if (revealed) "隐藏学号" else "显示学号",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }
        }
    }
}
