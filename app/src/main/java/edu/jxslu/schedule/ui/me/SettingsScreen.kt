package edu.jxslu.schedule.ui.me

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
import edu.jxslu.schedule.domain.AccountMask
import edu.jxslu.schedule.data.session.LoginState
import edu.jxslu.schedule.data.session.LoginStateRules
import edu.jxslu.schedule.data.session.LoginTarget
import edu.jxslu.schedule.data.session.SessionStatus
import edu.jxslu.schedule.data.session.WebViewCookieBridge
import edu.jxslu.schedule.ui.common.AppCard
import edu.jxslu.schedule.ui.common.LoadingHint
import edu.jxslu.schedule.ui.common.LocalBottomBarClearance
import edu.jxslu.schedule.ui.common.SettingItem
import edu.jxslu.schedule.ui.common.SettingsSection
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import edu.jxslu.schedule.ui.common.stateWord
import edu.jxslu.schedule.ui.common.tint
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.CalendarSetting01
import me.rerere.hugeicons.stroke.CreditCard
import me.rerere.hugeicons.stroke.Droplet
import me.rerere.hugeicons.stroke.GraduationCap
import me.rerere.hugeicons.stroke.Book02
import me.rerere.hugeicons.stroke.InformationCircle
import me.rerere.hugeicons.stroke.Settings01
import me.rerere.hugeicons.stroke.GridView
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff

/**
 * 「我的」= 账号卡 + 六入口（DESIGN §3.3，2026-09-23 改版）。
 *
 * 每个分区收进独立汇总二级页（`ui/me/hub/`），根页面只留实时副标题——
 * 课表行锚定当前课表名，学习行带笔记/作业计数，其余行给内容概览。
 * 账号卡数据源 = 水宝宝一卡通凭证（`YktCredentialStore`），遮罩口径在
 * `domain/AccountMask`；眼睛只在内存里切换完整学号，不写存储不进剪贴板。
 * 卡内三个服务格（教务 / 一卡通 / 胖乖生活）是登录入口，见 [ServiceCell]。
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
    /** 账户卡三格的落点（DESIGN §3.16）：教务账户页 / 校园卡设置 / 胖乖生活页。 */
    onOpenJwLogin: () -> Unit = {},
    onOpenCampusCard: () -> Unit = {},
    onOpenWater: () -> Unit = {},
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
            // 从引导页 / 校园卡设置页回来（ON_RESUME）会重建主窗口组合，这里随之重读。
            //
            // 账户卡**常显**（DESIGN §3.16）：显示条件不再依赖「有没有一卡通凭证」——
            // 没配一卡通的人同样需要身份区与登录入口。
            val vault = remember { Graph.credentialVault(context) }
            val casUsername = remember { vault.readCas()?.username }
            val yktUsername = remember { vault.readYkt()?.username }
            val qiekjLoggedIn = remember { Graph.qiekj(context).localToken() != null }
            // 升级用户没存凭证，但 WebView 里可能还有有效会话。只读 CookieManager、不联网，
            // 不认这一点就会出现「卡上说未登录、点进导入却能用」的自相矛盾。
            val webSession = remember { WebViewCookieBridge.hasAnyCookie() }
            val suspendedTargets by SessionStatus.suspended.collectAsStateWithLifecycle()
            val profileName by remember {
                Graph.displayPrefs(context).profileName
            }.collectAsState(initial = "")
            val profileClass by remember {
                Graph.displayPrefs(context).profileClass
            }.collectAsState(initial = "")
            // 班级为空就补抓一次（DESIGN §3.3）：闸门在 ProfileSync 内部（班级空 + 今天没
            // 试过），正常情况下一进页最多一次请求，抓到之后不再请求。失败静默——
            // 「我的」页不该因为一个锦上添花的字段变成错误态。
            LaunchedEffect(Unit) {
                if (profileClass.isBlank()) {
                    runCatching { Graph.profileSync(context).syncOnce() }
                }
            }
            val jwState = LoginStateRules.derive(
                credentialExists = casUsername != null,
                webSessionExists = webSession,
                suspended = LoginTarget.Jw in suspendedTargets,
            )
            val yktState = LoginStateRules.derive(
                credentialExists = yktUsername != null,
                webSessionExists = false,
                suspended = LoginTarget.Ykt in suspendedTargets,
            )
            val qiekjState = LoginStateRules.derive(
                credentialExists = qiekjLoggedIn,
                webSessionExists = false,
                suspended = LoginTarget.Qiekj in suspendedTargets,
            )
            AccountBar(
                username = casUsername ?: yktUsername.orEmpty(),
                name = profileName,
                className = profileClass,
                jwState = jwState,
                yktState = yktState,
                qiekjState = qiekjState,
                // 统一进教务账户页：状态、学业信息、更新密码、导入入口都在那一页。
                // 此前按状态分流（已登录直接跳 WebView），与「一卡通」点进去是原生页不一致。
                onOpenJw = onOpenJwLogin,
                onOpenYkt = onOpenCampusCard,
                onOpenQiekj = onOpenWater,
            )

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
        subtitle = "宿舍报修 · 快捷方式 · 出行码 · 一卡通 · 胖乖生活",
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
 * 账号卡（DESIGN §3.3）：头像圆标 +「姓名 学号」+ 班级副行 + 三个服务格。
 * 完整学号只存在 [username] 参数（内存）里，切眼睛不触发任何持久化；
 * 卡片本体不可点（AppCard 不传 onClick，无涟漪），交互面只有眼睛按钮与三个服务格。
 *
 * [name] / [className] 来自教务学籍卡（成绩导入顺带落 DataStore）；缺失时
 * 标题退回遮罩学号（此时学号不再重复跟在旁边），班级缺失则整行副行不显示。
 */
@Composable
private fun AccountBar(
    username: String,
    name: String,
    className: String,
    jwState: LoginState,
    yktState: LoginState,
    qiekjState: LoginState,
    onOpenJw: () -> Unit,
    onOpenYkt: () -> Unit,
    onOpenQiekj: () -> Unit,
) {
    var revealed by rememberSaveable { mutableStateOf(false) }
    val masked = AccountMask.maskStudentId(username).orEmpty()
    val hasName = name.isNotBlank()
    // 姓名与学号都没有 = 一份凭证都没配过：标题写「未登录」，而不是留一片空白
    val title = name.ifBlank { masked.ifBlank { "未登录" } }
    // 学号跟在姓名右侧；姓名缺失时它已经当标题用了，不再重复一遍
    val idText = if (revealed) username else masked
    AppCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 头像底座：校徽（江西水利电力大学 2025-06 更名后的新版校徽，DESIGN §3.3）
            Image(
                painter = painterResource(R.drawable.ic_school_emblem),
                contentDescription = null,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // fill = false：姓名短就贴着自己的宽度，学号紧跟着；姓名长才让位给省略号
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .alignByBaseline(),
                    )
                    if (hasName && idText.isNotBlank()) {
                        Text(
                            text = idText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                            maxLines = 1,
                            modifier = Modifier
                                .padding(start = 6.dp)
                                .alignByBaseline(),
                        )
                    }
                }
                if (className.isNotBlank()) {
                    Text(
                        text = className,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = { revealed = !revealed }) {
                Icon(
                    imageVector = if (revealed) HugeIcons.ViewOff else HugeIcons.View,
                    contentDescription = if (revealed) "隐藏学号" else "显示学号",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 10.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ServiceCell("教务", HugeIcons.GraduationCap, jwState, onOpenJw, Modifier.weight(1f))
            ServiceCell("一卡通", HugeIcons.CreditCard, yktState, onOpenYkt, Modifier.weight(1f))
            ServiceCell("胖乖生活", HugeIcons.Droplet, qiekjState, onOpenQiekj, Modifier.weight(1f))
        }
    }
}

/**
 * 服务格（DESIGN §3.16）：图标 + 名称 + 登录状态点，整格可点，落点由调用方给
 * （教务账户页 / 校园卡设置 / 胖乖生活页）。
 *
 * 状态只给名词，动作提示交给「整格可点」和状态色。**不做后台探测**：没请求过就是
 * 「未登录」，只有真撞上凭证错才转「已失效」。
 *
 * 格宽约 94dp（360dp 屏），「胖乖生活」在 12sp 下约 50dp，字体放大到 1.3 倍仍放得下。
 */
@Composable
private fun ServiceCell(
    label: String,
    icon: ImageVector,
    state: LoginState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberAppHaptics()
    val stateTint = state.tint()
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClickLabel = "$label：${state.stateWord()}") {
                haptics.tap()
                onClick()
            }
            // 48dp 是 M3 的最小触控目标；这里给到 60dp，三格与卡片同宽、互不挤
            .heightIn(min = 60.dp)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 状态点：色觉障碍下「已登录」与「未登录」只靠色相分不开，补一层形状
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(stateTint),
            )
            Text(
                text = state.stateWord(),
                style = MaterialTheme.typography.labelSmall,
                color = stateTint,
                maxLines = 1,
            )
        }
    }
}
