package edu.jxslu.schedule

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import edu.jxslu.schedule.ui.common.LocalBottomBarClearance
import edu.jxslu.schedule.ui.common.LocalBottomBarVisibleRequest
import edu.jxslu.schedule.ui.me.SettingsScreen
import edu.jxslu.schedule.ui.theme.JuwTheme
import edu.jxslu.schedule.ui.today.TodayScreen
import edu.jxslu.schedule.ui.water.WaterViewModel
import edu.jxslu.schedule.ui.week.WeekScreen
import edu.jxslu.schedule.ui.week.ScheduleBackgroundLayer
import edu.jxslu.schedule.domain.BgScale
import edu.jxslu.schedule.domain.ThemeMode
import me.rerere.hugeicons.stroke.Book01
import me.rerere.hugeicons.stroke.Calendar01
import me.rerere.hugeicons.stroke.Settings01
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            pendingRoute.value = intent?.getStringExtra(EXTRA_ROUTE)
        }
        enableEdgeToEdge()
        setContent {
            JuwRoot {
                JuwApp(pendingRoute = pendingRoute)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingRoute.value = intent.getStringExtra(EXTRA_ROUTE)
    }
}

/** 小组件传来的目标 Tab 的 extra 键；只在「网格区点击」时带上（整卡点击不带，落今日页）。 */
internal const val EXTRA_ROUTE = "edu.jxslu.schedule.extra.ROUTE"

/** [EXTRA_ROUTE] 的取值：课表 Tab。 */
internal const val ROUTE_WEEK = "week"

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
 * 全圆角 + 阴影 + 半透明底；**选中态只体现在图标与文字自身的颜色上**，不垫任何底色块
 * （用户 2026-09-22 指定：切到哪一页，那一页的图标就加深变黑）。
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
                // 选中 = 加深，不换底色块。颜色渐变过渡，切页时不会「啪」地跳一下
                val contentColor by animateColorAsState(
                    targetValue = if (selected) colorScheme.onSurface else colorScheme.onSurfaceVariant,
                    animationSpec = tween(TabFadeMillis),
                    label = "navItemColor",
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(percent = 50))
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
                        modifier = Modifier.size(22.dp),
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
fun JuwApp(pendingRoute: MutableState<String?>? = null) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val haptics = rememberAppHaptics()

    // 小组件网格区点击 → 切到课表 Tab（DESIGN §3.6）。跳转后消费掉 extra，
    // 否则每次重组/返回都会把用户弹回课表。
    val route = pendingRoute?.value
    LaunchedEffect(route) {
        if (route == ROUTE_WEEK) {
            navController.navigate(Routes.WEEK) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
            pendingRoute.value = null
        }
    }

    // 「我的 → 显示设置」跨 Tab 触发已于 2026-09-20 删除（DESIGN §3.1）：
    // 显示设置唯一入口 = 课表页顶栏眼睛图标，「我的」侧属重复入口，
    // 对应的 Channel 链路（displaySettingsRequests → WeekScreen）一并移除。

    val tabs = listOf(
        BottomTab(Routes.TODAY, R.string.tab_today, HugeIcons.Calendar01),
        BottomTab(Routes.WEEK, R.string.tab_week, HugeIcons.Book01),
        BottomTab(Routes.ME, R.string.tab_me, HugeIcons.Settings01),
    )
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    // 胖乖登录态没有 Flow：key 到 currentRoute，从开水页返回（或切 Tab）时重读 token。
    // 根因：无 key 的 remember 在登录成功返回后仍是旧值，入口卡片一直显示「未登录」。
    // 二级页改为独立窗口后，返回主窗口触发重组，这里随 currentRoute 重算。
    val waterLoggedIn = remember(currentRoute) { Graph.qiekj(context).localToken() != null }

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
    val floatingNavBar = displayPrefs?.floatingNavBar == true

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
                    startDestination = Routes.TODAY,
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
                            // 共享单车出码页（DESIGN §3.9）：今日页卡片直达，独立窗口
                            onOpenEbike = { SubpageActivity.start(context, SubpageScreen.EBIKE) },
                            // 校园卡付款码页（DESIGN §3.10）：开关开时今日页卡片直达
                            onOpenPayCode = { SubpageActivity.start(context, SubpageScreen.PAY_CODE) },
                            // 校园卡消费流水页（DESIGN §4.19）：今日页余额弹窗入口
                            onOpenStatement = { SubpageActivity.start(context, SubpageScreen.CAMPUS_STATEMENT) },
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
                            // 导入图标有调课提醒气泡时（DESIGN §4.17），点击直达「更新课表」
                            onOpenScheduleUpdate = {
                                SubpageActivity.start(context, SubpageScreen.SCHEDULE_UPDATE)
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
                composable(Routes.ME) {
                        SettingsScreen(
                            onOpenJwImport = {
                                JwImportActivity.start(context)
                            },
                            onOpenScores = {
                                SubpageActivity.start(context, SubpageScreen.SCORES)
                            },
                            // 二级页统一独立窗口：底栏不可达，返回栈语义清晰（根因见 SubpageActivity）
                            onOpenTimetableManage = {
                                SubpageActivity.start(context, SubpageScreen.TIMETABLE_MANAGE)
                            },
                            onOpenTimetableSettings = {
                                SubpageActivity.start(context, SubpageScreen.TIMETABLE_SETTINGS)
                            },
                            onOpenDataSettings = {
                                SubpageActivity.start(context, SubpageScreen.DATA_SETTINGS)
                            },
                            onOpenCourseTweak = {
                                SubpageActivity.start(context, SubpageScreen.COURSE_TWEAK)
                            },
                            onOpenTweakDetect = {
                                SubpageActivity.start(context, SubpageScreen.TWEAK_DETECT)
                            },
                            onOpenCampusCard = {
                                SubpageActivity.start(context, SubpageScreen.CAMPUS_CARD_SETTINGS)
                            },
                            onOpenWidgetSettings = {
                                SubpageActivity.start(context, SubpageScreen.WIDGET_SETTINGS)
                            },
                            onOpenCalendarSettings = {
                                SubpageActivity.start(context, SubpageScreen.CALENDAR_SETTINGS)
                            },
                            onOpenReminderSettings = {
                                SubpageActivity.start(context, SubpageScreen.REMINDER_SETTINGS)
                            },
                            onOpenShortcuts = {
                                SubpageActivity.start(context, SubpageScreen.SHORTCUTS)
                            },
                            onOpenWater = {
                                SubpageActivity.start(context, SubpageScreen.WATER)
                            },
                            // 学习分区（DESIGN §3.11）：笔记·课件库 / 作业库
                            onOpenNotes = {
                                SubpageActivity.start(context, SubpageScreen.NOTES)
                            },
                            onOpenHomework = {
                                SubpageActivity.start(context, SubpageScreen.HOMEWORK)
                            },
                            waterLoggedIn = waterLoggedIn,
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
    const val ME = "me"
}
