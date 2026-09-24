package edu.jxslu.schedule

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import edu.jxslu.schedule.ui.common.LocalBottomBarClearance
import edu.jxslu.schedule.ui.common.LocalBottomBarVisibleRequest
import edu.jxslu.schedule.ui.me.SettingsScreen
import edu.jxslu.schedule.ui.life.LifeScreen
import edu.jxslu.schedule.ui.theme.JuwTheme
import edu.jxslu.schedule.ui.today.TodayScreen
import edu.jxslu.schedule.ui.water.WaterViewModel
import edu.jxslu.schedule.ui.week.WeekScreen
import edu.jxslu.schedule.ui.week.ScheduleBackgroundLayer
import edu.jxslu.schedule.domain.BgScale
import edu.jxslu.schedule.domain.StartPage
import edu.jxslu.schedule.domain.ThemeMode
import me.rerere.hugeicons.stroke.Book01
import me.rerere.hugeicons.stroke.Calendar01
import me.rerere.hugeicons.stroke.Settings01
import me.rerere.hugeicons.stroke.Wallet03
import me.rerere.hugeicons.HugeIcons

class MainActivity : ComponentActivity() {
    /**
     * 小组件网格区点击带来的目标 Tab（DESIGN §3.6）：`Intent` extra [EXTRA_ROUTE]。
     *
     * 用 `mutableStateOf` 承接：实例复用（`SINGLE_TOP`）时在 [onNewIntent] 里更新，
     * 组合读 State 后 `LaunchedEffect` 跳转并把值消费掉（置空），
     * 免得用户手动切走后又被弹回课表。
     *
     * **只在首次创建时读 Intent**：`savedInstanceState != null` 说明是转屏/重建，
     * 此时 Intent 还是上次那份，重读会把用户从当前 Tab 再拽回课表（而 extra 还在，
     * `onNewIntent` 那条消费路径没走过）。重建后的 Tab 由 NavController 自身恢复。
     */
    private val pendingRoute = mutableStateOf<String?>(null)

    /**
     * 待打开的二级页队列（DESIGN §3.1）。两条来源：
     * - 通知跳板（作业提醒 / 调课检测）带的定位参数，长度 1；
     * - 从桌面图标重新进前台时按 [SubpageStack] 记录的窗口链放回，可能多层。
     *
     * 与 [pendingRoute] 同形态：`mutableStateOf` + 组合里读 → `LaunchedEffect` 消费后置空，
     * 免得每次重组都把用户又推回那个二级页。
     */
    private val pendingSubpages = mutableStateOf<List<SubpageRequest>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 桌面图标点击（NEW_TASK|RESET_TASK_IF_NEEDED）实测会在栈顶**新压一个
        // MainActivity**，而下面那套窗口（含二级页）原样还在。这个多余实例不是
        // task 的根，说明栈下面就是我们自己：把它关掉、连 setContent 都别走，
        // 系统会把任务栈带到前台，露出用户离开时那一页——二级页不被销毁，
        // 状态、滚动位置、地图视野全都在（这才是「真正的窗口保活」）。
        //
        // 判据必须带 CATEGORY_LAUNCHER + ACTION_MAIN：小组件点击（SINGLE_TOP|CLEAR_TOP）
        // 与通知跳板（我们自己拼的显式 intent）都不带这两个，它们该正常起窗口。
        // 真机四种 launchMode 组合的对比记在 AndroidManifest 那条注释里。
        if (!isTaskRoot() &&
            intent.hasCategory(Intent.CATEGORY_LAUNCHER) &&
            intent.action == Intent.ACTION_MAIN
        ) {
            finish()
            return
        }
        if (savedInstanceState == null) {
            // task 与进程都是新的：窗口链只可能是「用户把 App 从最近任务划掉」留下的残影，
            // 留着会让下一次点图标凭空落进某个二级页。只在真是根实例时清——
            // 上面那条 finish 路径的实例不该动记录
            if (isTaskRoot()) SubpageStack.clear()
            pendingRoute.value = intent?.getStringExtra(EXTRA_ROUTE)
            pendingSubpages.value = SubpageRequest.from(intent)?.let { listOf(it) } ?: emptyList()
        }
        enableEdgeToEdge()
        setContent {
            JuwRoot {
                JuwApp(pendingRoute = pendingRoute, pendingSubpages = pendingSubpages)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 通知跳板：带二级页定位参数
        val subpage = SubpageRequest.from(intent)
        if (subpage != null) {
            pendingRoute.value = null
            pendingSubpages.value = listOf(subpage)
            return
        }
        val route = intent.getStringExtra(EXTRA_ROUTE)
        if (route != null) {
            pendingRoute.value = route
            return
        }
        // 没有业务 extra，且是桌面图标那类 LAUNCHER 入口：singleTask 的 clearTop 已经把
        // 栈上的二级页拆掉，这里按记录把窗口链放回去（DESIGN §3.1）。
        // 判据必须带 action/category：小组件整卡的 intent 同样没有 extra，
        // 但它的语义是「回今日页」，不该被恢复逻辑截走。
        val fromLauncher = intent.action == Intent.ACTION_MAIN &&
            intent.categories?.contains(Intent.CATEGORY_LAUNCHER) == true
        if (fromLauncher) {
            // 只在「记录在、窗口没了」时补：二级页还活着说明它只是被前置，什么都不用做
            // （pendingRestore 内部已经带这道门）。同步启动，不走组合里的 LaunchedEffect：
            // onNewIntent 发生在窗口被带到前台、首帧绘制之前，这里启动的二级页能与主界面同一批上屏
            SubpageStack.pendingRestore().forEach { openSubpage(this, it) }
        }
    }
}

/** 小组件传来的目标 Tab 的 extra 键；只在「网格区点击」时带上（整卡点击不带，落今日页）。 */
internal const val EXTRA_ROUTE = "edu.jxslu.schedule.extra.ROUTE"

/** [EXTRA_ROUTE] 的取值：课表 Tab。 */
internal const val ROUTE_WEEK = "week"

/** [EXTRA_ROUTE] 的取值：生活 Tab（余额提醒通知的落点，DESIGN §3.10 / §3.13）。 */
internal const val ROUTE_LIFE = "life"

/**
 * 主题在根上解析：深浅色由显示偏好里的 [ThemeMode] 决定（默认跟随系统），
 * 强制浅/深时忽略系统设置。放在 setContent 最外层，全 App（含弹层）统一生效。
 * 主界面与教务导入 Activity 共用，保证两个窗口深浅色一致。
 */
@Composable
internal fun JuwRoot(content: @Composable () -> Unit) {
    val prefs by Graph.repository(LocalContext.current).displayPrefs
        .collectAsStateWithLifecycle(initialValue = null)
    val darkTheme = when (prefs?.themeMode) {
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
        ThemeMode.System, null -> isSystemInDarkTheme()
    }
    // 动态取色可关（我的 → 通用）：用户想要固定的品牌蓝绿而不是壁纸色
    JuwTheme(darkTheme = darkTheme, dynamicColor = prefs?.dynamicColor ?: true) {
        content()
    }
}

/**
 * 启动期冻结的底栏形态（悬浮导航栏开关，DESIGN §4.22）。
 *
 * 首帧前同步读 DataStore 一次并缓存为进程常量：悬浮/普通两套底栏的
 * Scaffold 几何完全不同，跟着 Flow 在启动中变会让用户先看到普通底栏
 * 再跳成悬浮（2026-09-24 用户反馈的割裂感）。因此形态**重启生效**：
 * 运行中切开关只写偏好，当前会话不再改 UI，通用设置页会提示重启。
 */
private var floatingNavBarEffective: Boolean? = null

private fun resolveFloatingNavBarBlocking(): Boolean = runBlocking {
    Graph.displayPrefs(Graph.appContext).floatingNavBar.first()
}

/**
 * 解析启动页（通用设置 → 启动页，DESIGN §3.3）。
 *
 * 调用点用 `remember {}` 把它按**窗口**读死一次（不是进程级 var）：`NavHost` 的
 * `startDestination` 跟着 Flow 中途变，Compose Navigation 会重建整张导航图
 * （`remember(route, startDestination, builder)` 的 key 变了），用户会被弹回起点；
 * 而挂 `remember` 的好处是重开窗口就取当时的偏好，进程还活着但窗口被重建的情况
 * （退回桌面再来、从最近任务划掉重进）也能立刻生效——比悬浮导航栏那个进程级
 * [floatingNavBarEffective] 更贴合「启动页」的语义。
 *
 * 生活页关掉时落回今日页：那一项在设置里已经不显示（§3.13），再落在没有入口的
 * Tab 上用户连怎么返回都找不到。判据与设置页选中态共用 [StartPage.effectivePage]。
 */
private fun resolveStartRouteBlocking(): String = runBlocking {
    val store = Graph.displayPrefs(Graph.appContext)
    val stored = store.startPage.first()
    val lifeTabEnabled = store.lifeTabEnabled.first()
    StartPage.effectivePage(stored, lifeTabEnabled).route()
}

/** [StartPage] → 导航路由。路由字符串仍只由 [Routes] 定义，这里不重复字面量。 */
private fun StartPage.route(): String = when (this) {
    StartPage.Today -> Routes.TODAY
    StartPage.Week -> Routes.WEEK
    StartPage.Life -> Routes.LIFE
    StartPage.Me -> Routes.ME
}

private data class BottomTab(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
)

/**
 * 底栏（DESIGN §4.22）。[floating] 决定用哪副形态：
 * - **false（默认）**：整宽不透明底栏，与加这个开关之前完全一致；
 * - **true**：悬浮胶囊，两侧 56dp、离屏幕底 12dp、全圆角 + 阴影 + 半透明底，
 *   身后课表页的背景图从胶囊里透上来。
 */
@Composable
private fun AppBottomBar(
    floating: Boolean,
    tabs: List<BottomTab>,
    currentRoute: String?,
    onSelect: (BottomTab) -> Unit,
) {
    if (floating) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // 先让开手势条，再留 12dp：胶囊离屏幕底的距离 = 手势条 + 12dp
                .navigationBarsPadding()
                .padding(horizontal = FloatingBarSideMargin)
                .padding(bottom = FloatingBarBottomMargin),
        ) {
            FloatingNavBarPill(tabs = tabs, currentRoute = currentRoute, onSelect = onSelect)
        }
        return
    }
    val colorScheme = MaterialTheme.colorScheme
    NavigationBar(
        containerColor = NavigationBarDefaults.containerColor,
        tonalElevation = NavigationBarDefaults.Elevation,
    ) {
        tabs.forEach { tab ->
            NavigationBarItem(
                selected = currentRoute == tab.route,
                onClick = { onSelect(tab) },
                icon = {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = stringResource(tab.labelRes),
                    )
                },
                label = { Text(stringResource(tab.labelRes)) },
            )
        }
    }
}

/**
 * 悬浮胶囊的左右外缩进：比内容边距大得多，胶囊才读得出「浮在页面上」。
 * 56dp 是照参考形态取的比例（约屏宽 14%），窄了像整宽底栏、宽了三格挤在一起。
 */
private val FloatingBarSideMargin = 56.dp

/** 悬浮胶囊与手势条的间距。 */
private val FloatingBarBottomMargin = 12.dp

/** 底栏图标基准尺寸；选中态在 [FloatingNavBarPill] 里乘 1.18 倍（约 26dp），不改布局。 */
private val TabIconBaseSize = 22.dp

/**
 * 悬浮胶囊的高度（解析值）：行内上下 6dp×2 + 列内上下 6dp×2 + 图标 22dp + 间距 2dp +
 * 文字 labelMedium 行高 16dp。
 * 只在算页面底部净空时用（见 JuwApp），**不去测量组件**：测量要等首帧之后才拿得到，
 * 页面会先按「无净空」摆一次再往上跳。系统字体放大时文字高几 dp，净空少几 dp 无妨。
 */
private val FloatingPillHeight = 64.dp

/**
 * 悬浮胶囊底栏（DESIGN §4.22，2026-09-22 按用户给的参考形态改）。
 *
 * 全圆角 + 阴影 + 半透明底；选中态三重信号：淡主色底块 + 图标微放大 +
 * 图标/文字颜色加深（用户 2026-09-22 追加：原先只靠颜色，切页辨识度不够）。
 *
 * 底色用 `surfaceContainer` 而不是 `surface`：浅色主题下 `surface` 与页面底色几乎同色，
 * 胶囊会读成「一条普通白底栏」，浮不起来。
 *
 * 不用 M3 的 `NavigationBar`：它是按整宽 80dp 设计的，胶囊要的是另一套几何
 * （高度 64dp、外侧留白、圆角 50%）。
 */
@Composable
private fun FloatingNavBarPill(
    tabs: List<BottomTab>,
    currentRoute: String?,
    onSelect: (BottomTab) -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val haptics = rememberAppHaptics()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(percent = 50),
        color = colorScheme.surfaceContainer.copy(alpha = 0.82f),
        tonalElevation = 0.dp,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { tab ->
                val selected = currentRoute == tab.route
                // 选中 = 淡主色底块 + 加深。颜色渐变过渡，切页时不会「啪」地跳一下
                val contentColor by animateColorAsState(
                    targetValue = if (selected) colorScheme.onSurface else colorScheme.onSurfaceVariant,
                    animationSpec = tween(TabFadeMillis),
                    label = "navItemColor",
                )
                val itemBackground by animateColorAsState(
                    targetValue = if (selected) colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent,
                    animationSpec = tween(TabFadeMillis),
                    label = "navItemBackground",
                )
                val iconScale by animateFloatAsState(
                    targetValue = if (selected) 1.18f else 1f,
                    animationSpec = tween(TabFadeMillis),
                    label = "navItemIconScale",
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(itemBackground)
                        // 用 selectable 而不是 clickable：TalkBack 要能读出「已选中的标签页」
                        .selectable(
                            selected = selected,
                            role = Role.Tab,
                            // 触感**不在这里给**：外层 onSelect 已经调了一次，两处都调就是双击震动
                            onClick = { onSelect(tab) },
                        )
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier
                            .size(TabIconBaseSize)
                            .graphicsLayer {
                                scaleX = iconScale
                                scaleY = iconScale
                            },
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(tab.labelRes),
                        style = MaterialTheme.typography.labelMedium,
                        color = contentColor,
                    )
                }
            }
        }
    }
}

// ---- 转场动画 ----
// NavHost 现在只承载三个平级 tab：短促交叉淡入淡出，横向滑动感会误导层级。
// 设置类二级页已全部改为 SubpageActivity 独立窗口（底栏不可达），
// 它们的进出场动画是 Activity 级 overridePendingTransition（右侧推入/退出），不在这里。

/** Tab 交叉淡入淡出的时长（毫秒）。课表页背景层的淡入淡出也用它，两处必须同频。 */
private const val TabFadeMillis = 180

private fun tabEnter(): EnterTransition = fadeIn(tween(TabFadeMillis))

private fun tabExit(): ExitTransition = fadeOut(tween(TabFadeMillis))

@Composable
internal fun JuwApp(
    pendingRoute: MutableState<String?>? = null,
    pendingSubpages: MutableState<List<SubpageRequest>>? = null,
) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val haptics = rememberAppHaptics()

    // 小组件网格区点击 → 切到课表 Tab；余额提醒通知 → 切到生活 Tab（DESIGN §3.6 / §3.10）。
    // 跳转后消费掉 extra，否则每次重组/返回都会把用户弹回那个 Tab。
    //
    // 判据写成「route 等于某个非空常量」而不是先算出目标再判空：Kotlin 的智能转换
    // 只有在这种形态下才认得出 `pendingRoute` 非空（`pendingRoute?.value` 非空 ⇒ 它非空），
    // 换成 `val target = when(route){…}` 再判 target 就会编译不过（2026-09-24 实测）。
    val route = pendingRoute?.value
    LaunchedEffect(route) {
        if (route == ROUTE_WEEK || route == ROUTE_LIFE) {
            val target = if (route == ROUTE_WEEK) Routes.WEEK else Routes.LIFE
            navController.navigate(target) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
            pendingRoute.value = null
        }
    }

    // 待打开的二级页（通知跳板 / 从桌面图标回来恢复窗口链，DESIGN §3.1）。
    // 先置空再依次启动：置空会重启这个 effect，新一轮读到空队列直接返回。
    val subpageQueue = pendingSubpages?.value
    LaunchedEffect(subpageQueue) {
        if (subpageQueue.isNullOrEmpty()) return@LaunchedEffect
        pendingSubpages.value = emptyList()
        // 顺序即层次：先启动的在下面，最后一个在最上层
        subpageQueue.forEach { openSubpage(context, it) }
    }

    // 「我的 → 显示设置」跨 Tab 触发已于 2026-09-20 删除（DESIGN §3.1）：
    // 显示设置唯一入口 = 课表页顶栏眼睛图标，「我的」侧属重复入口，
    // 对应的 Channel 链路（displaySettingsRequests → WeekScreen）一并移除。

    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    // 胖乖 ViewModel 挂 Activity 作用域：今日页快捷入口与开水页（独立窗口）各自持有，
    // 这里这份供今日页直接触发 unlock 时使用
    val waterViewModel: WaterViewModel = viewModel(
        viewModelStoreOwner = context as ComponentActivity,
        factory = WaterViewModel.Factory(Graph.qiekj(context)),
    )

    // 悬浮导航栏（DESIGN §4.22）：底栏形态是全局项，这里单独订阅一次。
    // 不复用 JuwRoot 那份：它是为了定主题色，作用域在 setContent 最外层，拿不到这里。
    // 同一个 DataStore 流多一个订阅者只是多一次 map，不产生额外磁盘读。
    val displayPrefs by remember { Graph.repository(context).displayPrefs }
        .collectAsStateWithLifecycle(initialValue = null)

    // 悬浮导航栏：只用启动期冻结的那份（见 floatingNavBarEffective 的 KDoc）。
    // displayPrefs 流仍订阅着背景图/生活页开关等运行时可变项，别顺手把这里改回它。
    val floatingNavBar = floatingNavBarEffective ?: resolveFloatingNavBarBlocking()
        .also { floatingNavBarEffective = it }

    // 启动页（DESIGN §3.3）：按窗口读死一次（见 resolveStartRouteBlocking 的 KDoc）——
    // NavHost 的 startDestination 中途变会把用户弹回起点，改完由设置页提示重启生效。
    val startRoute = remember { resolveStartRouteBlocking() }

    // 生活页开关（DESIGN §3.13）：默认开，关掉后底栏回到 3 项。
    // 未读出（首帧 null）按开处理——宁可先显示再收起，也别让底栏先少一项再补上。
    val lifeTabEnabled = displayPrefs?.lifeTabEnabled ?: true

    // 今日页 → 附近单车地图（2026-09-24）：要拿「选中的车号」回传——地图选车后先收起，
    // 这里接住车号再开出码页（车号走 SubpageRequest.focusItemId，进页即出码）。
    // 普通 startActivity 收不到结果，必须走 launcher（openSubpageForResult 只补转场）。
    val ebikeMapLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val carNum = result.data?.getStringExtra(SubpageActivity.EXTRA_PICKED_CAR_NUM)
        if (!carNum.isNullOrBlank()) {
            SubpageActivity.start(context, SubpageScreen.EBIKE, focusItemId = carNum)
        }
    }

    // 底栏四项：今日 · 课表 · 生活 · 我的（生活页在课表右侧）
    val tabs = buildList {
        add(BottomTab(Routes.TODAY, R.string.tab_today, HugeIcons.Calendar01))
        add(BottomTab(Routes.WEEK, R.string.tab_week, HugeIcons.Book01))
        if (lifeTabEnabled) add(BottomTab(Routes.LIFE, R.string.tab_life, HugeIcons.Wallet03))
        add(BottomTab(Routes.ME, R.string.tab_me, HugeIcons.Settings01))
    }

    // 在生活页里把开关关掉（设置页是独立窗口，回来时这里才感知到）：退回今日页，
    // 别停在已经被隐藏的 Tab 上——它连入口都没有了。
    LaunchedEffect(lifeTabEnabled, currentRoute) {
        if (!lifeTabEnabled && currentRoute == Routes.LIFE) {
            navController.navigate(Routes.TODAY) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    // 悬浮形态的底部净空 = 胶囊高 + 离底间距 + 手势条。**解析式算，不去测量**：
    // 测量要在首帧之后才拿得到高度，页面会先按「无净空」摆一次再往上跳。
    val navBarInsetDp = with(LocalDensity.current) {
        WindowInsets.navigationBars.getBottom(this).toDp()
    }
    val bottomBarClearance = if (floatingNavBar) {
        FloatingPillHeight + FloatingBarBottomMargin + navBarInsetDp
    } else {
        0.dp
    }

    // 页面浮层（课表页的显示设置面板）可以请求收起底栏，见 LocalBottomBarVisibleRequest。
    val bottomBarVisibleRequest = remember { mutableStateOf(true) }
    val bottomBarVisible by bottomBarVisibleRequest

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        // 顶部 inset 归零（DESIGN §4.22）：垫了状态栏高度，课表页的背景图就铺不到状态栏。
        // 三个 Tab 的顶栏各自取 WindowInsets.statusBars；底部让位仍由 bottomBar 高度提供
        // （Scaffold 的内边距与 contentWindowInsets 无关），所以底栏避让不受影响。
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            // 悬浮胶囊浮在页面内容之上，显示设置面板（画在 NavHost 里）盖不住它：
            // 面板打开时把胶囊整个收起来，面板才能铺到屏幕底、那一带的点击才归面板。
            // 只对悬浮形态生效——非悬浮形态是整宽不透明底栏，面板本来就在它上方，
            // 收起来反而会让 NavHost 的 padding 变化、网格重排。
            AnimatedVisibility(
                visible = !floatingNavBar || bottomBarVisible,
                enter = fadeIn(tween(TabFadeMillis)),
                exit = fadeOut(tween(TabFadeMillis)),
            ) {
                AppBottomBar(
                    floating = floatingNavBar,
                    tabs = tabs,
                    currentRoute = currentRoute,
                    onSelect = { tab ->
                        haptics.tap()
                        navController.navigate(tab.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            // 背景层（DESIGN §4.21/§4.22）：铺满整个窗口（状态栏 → 屏幕底），
            // 放在 NavHost 之前，因此它在所有页面内容之下、也在底栏之下。
            //
            // 放在这里而不是课表页内部的根因：Scaffold 的 Surface 会裁掉超出自身边界的绘制，
            // 图想「溢出」到底栏后面就会被切掉；外层 Scaffold 的内容区本身就是整个窗口，
            // 不需要任何溢出技巧。
            //
            // 只画课表 Tab：其余两页是不透明底，图本来也露不出来，但底栏在它们那儿是半透明的，
            // 不挡住就会从底栏透出来，与「背景只铺课表页」的约定冲突。
            //
            // 显隐必须与 NavHost 的转场**同频**（都用 TabFadeMillis）：写成
            // `if (currentRoute == Routes.WEEK)` 时，导航一发出目的地就变了，而课表页还要淡出
            // 180ms——用户看到的就是「图先没、页再走」。这里让两者一起淡出/淡入。
            AnimatedVisibility(
                visible = currentRoute == Routes.WEEK,
                enter = fadeIn(tween(TabFadeMillis)),
                exit = fadeOut(tween(TabFadeMillis)),
                modifier = Modifier.matchParentSize(),
            ) {
                ScheduleBackgroundLayer(
                    fileName = displayPrefs?.bgImageName,
                    opacity = displayPrefs?.bgImageOpacity ?: 1f,
                    dim = displayPrefs?.bgImageDim ?: 0f,
                    blur = displayPrefs?.bgImageBlur ?: 0f,
                    scale = displayPrefs?.bgImageScale ?: BgScale.Fill,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            CompositionLocalProvider(
                LocalBottomBarClearance provides bottomBarClearance,
                LocalBottomBarVisibleRequest provides bottomBarVisibleRequest,
            ) {
                NavHost(
                    navController = navController,
                    // 启动页（DESIGN §3.3）：默认今日，可在「我的 → 通用 → 启动页」改，
                    // 生活页关掉时那项不可选、落点由 resolveStartRouteBlocking 兜回今日。
                    startDestination = startRoute,
                    // 悬浮形态：内容铺到窗口底，被胶囊压住一部分——留出底栏槽位的话，
                    // 内容会在胶囊上方被截断，看着仍是「一条白色底栏 + 一个胶囊」。
                    // 普通形态照旧吃 Scaffold 的 padding（不透明底栏必须让位）。
                    modifier = if (floatingNavBar) Modifier.fillMaxSize() else Modifier.padding(padding),
                    enterTransition = { tabEnter() },
                    exitTransition = { tabExit() },
                    popEnterTransition = { tabEnter() },
                    popExitTransition = { tabExit() },
                ) {
                    composable(Routes.TODAY) {
                        TodayScreen(
                            // 教务导入是独立 Activity：新窗口覆盖，底层课表布局不动
                            onOpenJwImport = {
                                JwImportActivity.start(context)
                            },
                            // 「尚未开学」空态 CTA：跳课表设置子页（独立窗口）
                            onOpenTimetableSettings = {
                                SubpageActivity.start(context, SubpageScreen.TIMETABLE_SETTINGS)
                            },
                            // 一键开水卡常显（未登录给未登录态，显示设置可关，DESIGN §3.3）；
                            // 登录态由 WaterViewModel 自带，外层不再按登录与否隐藏整卡
                            onOpenWater = { SubpageActivity.start(context, SubpageScreen.WATER) },
                            // 共享单车出码页（DESIGN §3.9）：今日页卡片直达，独立窗口；
                            // 卡片右侧「附近单车 ›」进地图（带返回值：选车后开出码页自动出码）
                            onOpenEbike = { SubpageActivity.start(context, SubpageScreen.EBIKE) },
                            onOpenEbikeMap = {
                                openSubpageForResult(
                                    context,
                                    ebikeMapLauncher::launch,
                                    SubpageRequest(SubpageScreen.EBIKE_MAP),
                                )
                            },
                            // 快捷方式网格：长按图标进设置页（null）；Snackbar「去设置」带失败条目
                            // id 直达该条目的编辑弹层（DESIGN §3.8 的就地修正闭环）
                            onOpenShortcuts = { focusItemId ->
                                SubpageActivity.start(context, SubpageScreen.SHORTCUTS, focusItemId)
                            },
                            // 作业卡 → 作业中心；课程详情弹窗 → 该课程的笔记·课件 / 作业（DESIGN §3.11）
                            onOpenHomeworkTodo = {
                                SubpageActivity.start(context, SubpageScreen.HOMEWORK_TODO)
                            },
                            onOpenCourseNotes = { course ->
                                SubpageActivity.start(context, SubpageScreen.NOTES_COURSE, courseName = course.name)
                            },
                            onOpenCourseHomework = { course ->
                                SubpageActivity.start(context, SubpageScreen.HOMEWORK_COURSE, courseName = course.name)
                            },
                            waterViewModel = waterViewModel,
                        )
                    }
                composable(Routes.WEEK) {
                    WeekScreen(
                            onOpenJwImport = {
                                JwImportActivity.start(context)
                            },
                            // 切换弹层「管理课表」→ 独立窗口；
                            // 眼睛是页内覆盖弹层（不跳页，课表保持可见，见 WeekScreen）
                            onOpenTimetableManage = {
                                SubpageActivity.start(context, SubpageScreen.TIMETABLE_MANAGE)
                            },
                            // 课程详情弹窗的「笔记·课件 / 作业」（DESIGN §3.11）
                            onOpenCourseNotes = { course ->
                                SubpageActivity.start(context, SubpageScreen.NOTES_COURSE, courseName = course.name)
                            },
                            onOpenCourseHomework = { course ->
                                SubpageActivity.start(context, SubpageScreen.HOMEWORK_COURSE, courseName = course.name)
                            },
                        )
                    }
                    // 生活页（DESIGN §3.13）：一卡通余额 / 付款码 / 寝室电费 / 充值入口 / 最近流水。
                    // 三个出口都是二级页（独立窗口，返回语义清晰）：消费流水、全屏付款码、一卡通设置
                    composable(Routes.LIFE) {
                        LifeScreen(
                            onOpenStatement = {
                                SubpageActivity.start(context, SubpageScreen.CAMPUS_STATEMENT)
                            },
                            onOpenPowerBill = {
                                SubpageActivity.start(context, SubpageScreen.POWER_BILL)
                            },
                            onOpenPayCode = {
                                SubpageActivity.start(context, SubpageScreen.PAY_CODE)
                            },
                            onOpenCampusSettings = {
                                SubpageActivity.start(context, SubpageScreen.CAMPUS_CARD_SETTINGS)
                            },
                        )
                    }
                composable(Routes.ME) {
                        SettingsScreen(
                            onOpenGeneralSettings = {
                                SubpageActivity.start(context, SubpageScreen.GENERAL_SETTINGS)
                            },
                            onOpenTimetableHub = {
                                SubpageActivity.start(context, SubpageScreen.TIMETABLE_HUB)
                            },
                            onOpenLearningHub = {
                                SubpageActivity.start(context, SubpageScreen.LEARNING_HUB)
                            },
                            onOpenWidgetCalendarHub = {
                                SubpageActivity.start(context, SubpageScreen.WIDGET_CALENDAR_HUB)
                            },
                            onOpenExtensionServices = {
                                SubpageActivity.start(context, SubpageScreen.EXT_SERVICES_HUB)
                            },
                            onOpenAbout = {
                                SubpageActivity.start(context, SubpageScreen.ABOUT)
                            },
                        )
                    }
                }
            }
        }
    }
}

object Routes {
    const val TODAY = "today"
    const val WEEK = "week"
    const val LIFE = "life"
    const val ME = "me"
}
