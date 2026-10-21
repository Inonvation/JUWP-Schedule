package edu.jxslu.schedule

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.first
import edu.jxslu.schedule.data.prefs.DisplayPrefs
import edu.jxslu.schedule.data.xg.XgUrls
import edu.jxslu.schedule.data.prefs.OnboardingSyncOutcome
import edu.jxslu.schedule.domain.FloatingBall
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.DialogWindowFadeEffect
import edu.jxslu.schedule.ui.common.FloatingBallOverlay
import edu.jxslu.schedule.ui.common.NoticeTone
import edu.jxslu.schedule.ui.common.postNotice
import edu.jxslu.schedule.ui.common.rememberQuickScan
import edu.jxslu.schedule.ui.campus.CampusPayResultDialogHost
import edu.jxslu.schedule.ui.jw.JwHubScreen
import edu.jxslu.schedule.ui.jwvw.JwImportMode
import edu.jxslu.schedule.ui.me.hub.UpdateAvailableDialogHost
import edu.jxslu.schedule.ui.reminder.DormRepairReminder
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import edu.jxslu.schedule.ui.common.LocalBottomBarClearance
import edu.jxslu.schedule.ui.common.LocalBottomBarVisibleRequest
import edu.jxslu.schedule.ui.common.StackedDrawerState
import edu.jxslu.schedule.ui.life.LifeScreen
import edu.jxslu.schedule.ui.life.RechargeKind
import edu.jxslu.schedule.domain.QuickScanTarget
import edu.jxslu.schedule.domain.quickScanTargetOf
import edu.jxslu.schedule.ui.common.AppPermissions
import edu.jxslu.schedule.ui.ebike.EbikeScanActivity
import edu.jxslu.schedule.ui.life.SideDrawerContent
import edu.jxslu.schedule.ui.qzxy.QzxyEntrySheet
import edu.jxslu.schedule.ui.ujing.UjingEntrySheet
import edu.jxslu.schedule.ui.water.WaterEntrySheet
import com.google.zxing.BarcodeFormat
import com.google.zxing.client.android.Intents
import edu.jxslu.schedule.ui.theme.JuwTheme
import edu.jxslu.schedule.ui.theme.semanticColors
import edu.jxslu.schedule.ui.today.TodayScreen
import edu.jxslu.schedule.ui.water.WaterViewModel
import edu.jxslu.schedule.data.qzxy.QzxyBluetoothScanner
import edu.jxslu.schedule.ui.qzxy.QzxyViewModel
import edu.jxslu.schedule.ui.week.WeekScreen
import edu.jxslu.schedule.ui.week.ScheduleBackgroundLayer
import edu.jxslu.schedule.domain.BgScale
import edu.jxslu.schedule.domain.FontScalePolicy
import edu.jxslu.schedule.domain.StartPage
import edu.jxslu.schedule.domain.ThemeMode
import edu.jxslu.schedule.domain.ThemePalette
import edu.jxslu.schedule.domain.WenquRules
import me.rerere.hugeicons.stroke.Book01
import me.rerere.hugeicons.stroke.Calendar01
import me.rerere.hugeicons.stroke.School01
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
        // 引导窗口还活着时，小组件 / 通知跳板 / 桌面快捷方式再把 MainActivity 叠上来，
        // 会在底下那层引导没退场的情况下浮出一个新的今日页；引导完成后一按返回就
        // 露出僵尸引导（2026-09-28 真机踩过）。此时直接退位，让底下的引导继续——
        // 引导期间小组件的跳转路由本来就无处可去（课表还没导入）。
        if (savedInstanceState == null && OnboardingActivity.liveInstances > 0) {
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
        // 首次配置引导（DESIGN §3.16）：只服务新安装——老用户没有 onboarding_seen 键，
        // 不弹引导，只在「我的」页显示登录状态卡。阻塞读一次，与 resolveStartRouteBlocking
        // 同一模式（读一次就定，不让它跟 Flow 变）。引导是不透明全屏窗口，主界面照常
        // setContent 也看不到底下，用户感知就是「打开就是引导」。
        if (savedInstanceState == null && shouldShowOnboarding()) {
            OnboardingActivity.start(this)
        }
        setContent {
            JuwRoot {
                JuwApp(pendingRoute = pendingRoute, pendingSubpages = pendingSubpages)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 宿舍报修（DESIGN §4.49）：只在回到前台时核对（不做后台轮询）。
        // 30 分钟内不重复打接口，闸门在 DormRepairSync 里；用 applicationContext 不持有 Activity。
        Graph.appScope.launch {
            DormRepairReminder.checkOnAppOpen(applicationContext)
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

/** [EXTRA_ROUTE] 的取值：我的 Tab（登录失效通知的落点，DESIGN §3.16）。 */
internal const val ROUTE_ME = "me"

/** [EXTRA_ROUTE] 的取值：生活小组件整卡 → 付款码页（DESIGN §3.6 二条目改版，2026-09-27）。 */
internal const val ROUTE_PAY_CODE = "pay_code"

/** [EXTRA_ROUTE] 的取值：生活小组件的电费副行 → 缴费账单·用电统计页。 */
internal const val ROUTE_POWER_BILL = "power_bill"

/** [EXTRA_ROUTE] 的取值：校园卡小组件未开凭证 → 校园卡设置页。 */
internal const val ROUTE_CAMPUS_CARD = "campus_card"

/** [EXTRA_ROUTE] 的取值：胖乖开水小组件整卡 → 开水页（DESIGN §3.6「开水两卡」，2026-09-28）。 */
internal const val ROUTE_WATER = "water"

/** [EXTRA_ROUTE] 的取值：趣智开水小组件整卡 → 趣智校园页（同上）。 */
internal const val ROUTE_QZXY = "qzxy"

/**
 * [EXTRA_ROUTE] 的取值：胖乖开水卡胶囊「去开水」→ 开水页并**自动开水**
 * （2026-09-28 用户拍板：只有胶囊直达，整卡其余位置只进页面）。
 * 与 [ROUTE_WATER] 的差别仅在武装 [WaterAutoStart] 令牌。
 */
internal const val ROUTE_WATER_START = "water_start"

/** [EXTRA_ROUTE] 的取值：趣智开水卡胶囊「去开水」→ 趣智页并自动开阀（同上）。 */
internal const val ROUTE_QZXY_START = "qzxy_start"

/**
 * 主题首帧快照（进程级，2026-09-28）。
 *
 * DataStore 第一份数据必然晚于任何窗口的首帧，而「外观与交互」这类二级页每次打开都是
 * 新建的独立窗口、都要过一次空窗期。没有快照时首帧只能按默认值渲染——用户会看到
 * 「先闪一下默认主题、再跳成自己选的配色」（2026-09-28 用户反馈）。动态取色开关与
 * 深浅模式同理，一并快照。做法与 [floatingNavBarEffective] 同源：首次同步读一份真值，
 * 此后每个窗口真值到达时回写刷新，后续窗口直接吃缓存、不再阻塞主线程。
 *
 * 与 [floatingNavBarEffective] 的区别：主题**不冻结**——它本就实时生效，快照只在
 * 首帧与真值之间垫一脚；用户改完配色，下一个窗口的首帧就用新值。
 *
 * 2026-10-09 起「固定字体大小」（字体缩放锁定）也吃这份快照：它与主题同属
 * 「首帧就得按真值渲染」的即时项，关掉开关的用户不该先看到一帧固定字号再跳。
 */
private data class ThemeSnapshot(
    val themeMode: ThemeMode,
    val dynamicColor: Boolean,
    val palette: ThemePalette,
    /** 字体缩放锁定（「固定字体大小」）：首帧也要用真值，否则关掉开关的窗口会先渲染一帧固定字号。 */
    val fontScaleLocked: Boolean,
)

private var themeSnapshot: ThemeSnapshot? = null

/**
 * 启动期一次性预载的显示偏好（2026-10-02，体检第三批；终审修正）。
 *
 * 主线程上同步读 DataStore 的点位原本有三个（主题快照 / 悬浮栏 / 启动路由），各自
 * `runBlocking { ... .first() }`——**首次**读盘是真磁盘 IO，三份就是主线程上的三次串行。
 * 合并成「第一次需要时读一整份 [DisplayPrefs]」后，**只有悬浮栏**继续吃这份缓存
 * （它本来就是「重启生效」的冻结语义）；**主题**由 [themeSnapshot] 的每窗口回写垫首帧；
 * **启动页**改回每次真读（[resolveStartRouteBlocking]）——它的 KDoc 承诺「重开窗口就
 * 取当时的偏好」，进程级缓存会把这语义退化成重启生效，收益差异只剩一次内存读。
 */
private var startupPrefs: DisplayPrefs? = null

private fun startupPrefsBlocking(): DisplayPrefs = startupPrefs ?: runCatching {
    runBlocking { Graph.repository(Graph.appContext).displayPrefs.first() }
}.getOrDefault(DisplayPrefs()).also { startupPrefs = it }

private fun readThemeSnapshotBlocking(): ThemeSnapshot = startupPrefsBlocking().let {
    ThemeSnapshot(it.themeMode, it.dynamicColor, it.themePalette, it.fontScaleLocked)
}

/**
 * 主题在根上解析：深浅色由显示偏好里的 [ThemeMode] 决定（默认跟随系统），
 * 强制浅/深时忽略系统设置。放在 setContent 最外层，全 App（含弹层）统一生效。
 * 主界面与教务导入 Activity 共用，保证两个窗口深浅色一致。
 */
@Composable
internal fun JuwRoot(content: @Composable () -> Unit) {
    val prefs by Graph.repository(LocalContext.current).displayPrefs
        .collectAsStateWithLifecycle(initialValue = null)
    // 真值到达前用进程快照兜首帧（不是默认值，见 ThemeSnapshot 的 KDoc）
    val snapshot = themeSnapshot ?: readThemeSnapshotBlocking().also { themeSnapshot = it }
    val themeMode = prefs?.themeMode ?: snapshot.themeMode
    val dynamicColor = prefs?.dynamicColor ?: snapshot.dynamicColor
    val palette = prefs?.themePalette ?: snapshot.palette
    val fontScaleLocked = prefs?.fontScaleLocked ?: snapshot.fontScaleLocked
    // 真值到达（或用户改了）就回写快照，供下一个窗口的首帧直接用
    if (prefs != null) {
        themeSnapshot = ThemeSnapshot(themeMode, dynamicColor, palette, fontScaleLocked)
    }
    val darkTheme = when (themeMode) {
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
        ThemeMode.System -> isSystemInDarkTheme()
    }
    // 动态取色默认关；关着时用内置配色里选中的那套（默认晴空蓝），开着才跟壁纸
    JuwTheme(
        darkTheme = darkTheme,
        dynamicColor = dynamicColor,
        palette = palette,
    ) {
        // 字体缩放锁定（「固定字体大小」，默认开）：全 App 唯一的字形缩放覆盖点。
        // 立即生效：开关一动，displayPrefs 发射，这里跟着重组
        ProvideFixedFontScale(locked = fontScaleLocked) {
            content()
        }
    }
}

/**
 * 字体缩放锁定（「固定字体大小」）：把 Compose 树的 `fontScale` 钉在
 * [FontScalePolicy.effectiveFontScale] 的结果上（固定 0.9×）。
 *
 * 只覆盖 `fontScale`——密度（dp/px）原样透传，图标、间距、命中区、自适应行高
 * 等一切按 dp 的几何都不受影响；`sp` 的换算从此与系统「字体大小」设置脱钩。
 * 弹层 / 底部弹层 / 下拉菜单是同一合成树的子组合，继承这层 `LocalDensity`，无需各自处理。
 *
 * 未锁定时与系统值恒等，不额外包一层 Provider（避免无谓的 Density 实例）。
 */
@Composable
private fun ProvideFixedFontScale(locked: Boolean, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val scale = FontScalePolicy.effectiveFontScale(locked, density.fontScale)
    if (scale == density.fontScale) {
        content()
    } else {
        CompositionLocalProvider(
            LocalDensity provides Density(density.density, scale),
        ) {
            content()
        }
    }
}

/**
 * 启动期冻结的底栏形态（悬浮导航栏开关，DESIGN §4.22）。
 *
 * 首帧前同步读 DataStore 一次并缓存为进程常量：悬浮/普通两套底栏的
 * Scaffold 几何完全不同，跟着 Flow 在启动中变会让用户先看到普通底栏
 * 再跳成悬浮（2026-09-24 用户反馈的割裂感）。因此形态**重启生效**：
 * 运行中切开关只写偏好，当前会话不再改 UI，「外观与交互」页会提示重启。
 */
private var floatingNavBarEffective: Boolean? = null

private fun resolveFloatingNavBarBlocking(): Boolean = startupPrefsBlocking().floatingNavBar

/**
 * 解析启动页（设置 → 应用 → 外观与交互 → 启动页，DESIGN §3.3）。
 *
 * 调用点用 `remember {}` 把它按**窗口**读死一次（不是进程级 var）：`NavHost` 的
 * `startDestination` 跟着 Flow 中途变，Compose Navigation 会重建整张导航图
 * （`remember(route, startDestination, builder)` 的 key 变了），用户会被弹回起点；
 * 而挂 `remember` 的好处是重开窗口就取当时的偏好，进程还活着但窗口被重建的情况
 * （退回桌面再来、从最近任务划掉重进）也能立刻生效——比悬浮导航栏那个进程级
 * [floatingNavBarEffective] 更贴合「启动页」的语义。
 *
 * 终审修正（2026-10-02）：保持**每次真读**、不走 [startupPrefsBlocking] 进程缓存——
 * 缓存会让「重开窗口生效」退化成「重启生效」。DataStore 首读后全走内存缓存，
 * 这里的成本只剩内存读。
 *
 * 生活页关掉时落回今日页：那一项在设置里已经不显示（§3.13），再落在没有入口的
 * Tab 上用户连怎么返回都找不到。判据与设置页选中态共用 [StartPage.effectivePage]。
 */
private fun resolveStartRouteBlocking(): String = runBlocking {
    val prefs = Graph.repository(Graph.appContext).displayPrefs.first()
    StartPage.effectivePage(prefs.startPage).route()
}

/**
 * 首启引导是否已经走过（DESIGN §3.16）。
 *
 * 只认 `onboarding_seen` 这一个键，不认「有没有课表」——导入过课表又清空过的用户
 * 不该被重新引导一遍。
 *
 * `onboarding_seen` 不在 [DisplayPrefs] 数据类里（它是 store 的独立键），单独走一次
 * DataStore 读——此时主题快照已把 DataStore 热进内存缓存，这次读只碰内存，无磁盘 IO。
 */
private fun onboardingSeenBlocking(): Boolean = runBlocking {
    Graph.displayPrefs(Graph.appContext).onboardingSeen.first()
}

/**
 * 这次启动要不要弹首启引导（DESIGN §3.16）。
 *
 * 两个条件：`onboarding_seen` 还没写过（没走完过），且**不是覆盖升级**。
 *
 * **debug 包只看前一个条件**：开发与验收时反复覆盖安装，`isFreshInstall` 永远是 false，
 * 引导就再也弹不出来了，只能靠卸载重装——那是给自己找麻烦。release 才需要「老用户
 * 不打扰」这条保护，所以只在那里判安装时间。
 */
private fun shouldShowOnboarding(): Boolean {
    if (onboardingSeenBlocking()) return false
    if (BuildConfig.DEBUG) return true
    return isFreshInstall(Graph.appContext)
}

/**
 * 是不是「新安装」，而不是「覆盖升级」（DESIGN §3.16）。
 *
 * **只看 `onboarding_seen` 键不够**：老用户设备上这个键同样不存在（它是新加的），
 * 会被当成首次启动而弹引导——设计要求的是「老用户不打扰」。判据用安装时间：
 * 新装时 `lastUpdateTime` 与 `firstInstallTime` 几乎相同，覆盖安装则后者明显更晚。
 *
 * 用「有没有课表」判不行：导入过课表又清空的用户会被误判成新安装。读不到安装信息时
 * 保守返回 false——宁可不弹，也不要在老用户机器上凭空冒出一段引导。
 */
@Suppress("DEPRECATION")
private fun isFreshInstall(context: android.content.Context): Boolean = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    info.lastUpdateTime - info.firstInstallTime < FRESH_INSTALL_WINDOW_MS
}.getOrDefault(false)

/** 安装与更新的时间差小于这个值就算同一次安装（真机实测覆盖安装会差好几天，取一分钟足够）。 */
private const val FRESH_INSTALL_WINDOW_MS = 60_000L

/** [StartPage] → 导航路由。路由字符串仍只由 [Routes] 定义，这里不重复字面量。 */
private fun StartPage.route(): String = when (this) {
    StartPage.Today -> Routes.TODAY
    StartPage.Week -> Routes.WEEK
    StartPage.Jw -> Routes.JW
    // 生活 / 我的不再占 Tab：旧选择统一落回今日
    StartPage.Life, StartPage.Me -> Routes.TODAY
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
 * 侧栏展开时主页面的缩放比例（2026-10-20 用户参考的层级效果）。
 * 0.06 = 缩小到 94%：上下各露出约 3% 的后层，够读出"卡片浮在侧栏之上"，又不至于让字明显变小。
 */
private const val CardShrink = 0.06f

/** 侧栏展开时余额静默刷新的节流窗口：用户反复开关侧栏不会连发两轮请求。 */
private const val SIDE_BALANCE_REFRESH_TTL_MILLIS = 60_000L

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
 * 普通（非悬浮）底栏的解析高度：M3 `NavigationBar` 的默认容器高 80dp。
 * 只用于算悬浮球的可拖动区域（让开底栏），不参与布局测量。
 */
private val PlainBottomBarHeight = 80.dp

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

/**
 * 首启引导后台同步的结果弹窗（DESIGN §4.17 / §4.29，2026-10-04）：登录后课表、成绩、
 * 学业三项在后台静默同步，引导走完回到主界面时在这里一次性汇报。
 *
 * 每行 = 一个数据项：成功给「查看」入口（成绩 → 成绩页，学业 → 学业页，课表本身就是
 * 主界面不另给入口）；失败显示教务返回的原因 + 下一步出路。**「查看」只跳页不关闭**——
 * 弹窗保持挂起，用户看完回来可以接着看另一项；只有「知道了」（或点弹窗外部）才消费。
 */
@Composable
private fun OnboardingSyncResultDialog(
    outcome: OnboardingSyncOutcome,
    onConsume: () -> Unit,
    onOpenSubpage: (SubpageScreen) -> Unit,
) {
    DialogWindowFadeEffect()
    AlertDialog(
        onDismissRequest = onConsume,
        title = { Text("同步完成") },
        text = {
            Column {
                OnboardingSyncLine(
                    ok = outcome.scheduleOk,
                    shown = outcome.scheduleTried,
                    okText = "课表已导入" + (if (outcome.scheduleCount > 0) "（${outcome.scheduleCount} 门）" else ""),
                    failText = "课表未能自动导入",
                    failReason = outcome.scheduleFailReason,
                    failHint = "在课表页点「导入课表」手动导一遍即可",
                )
                OnboardingSyncLine(
                    ok = outcome.scoreOk,
                    shown = outcome.scoreTried,
                    okText = "成绩已导入" + (if (outcome.scoreCount > 0) "（${outcome.scoreCount} 条）" else ""),
                    failText = "成绩未能自动导入",
                    failReason = outcome.scoreFailReason,
                    failHint = "到「我的 → 学习 → 成绩查询」手动导入即可",
                    onView = { onOpenSubpage(SubpageScreen.SCORES) },
                )
                OnboardingSyncLine(
                    ok = outcome.scholarOk,
                    shown = outcome.scholarTried,
                    okText = "学业完成情况已更新" +
                        (if (outcome.scholarCount > 0) "（${outcome.scholarCount} 条课程）" else ""),
                    failText = "学业完成情况暂未同步",
                    failReason = outcome.scholarFailReason,
                    failHint = "下次进「学业完成情况」页会自动补抓",
                    onView = { onOpenSubpage(SubpageScreen.SCHOLAR) },
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "完成于 " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(outcome.finishedAt)) +
                        " · 成功的项目后续也会随提醒自动更新",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConsume) { Text("知道了") }
        },
    )
}

/** 结果弹窗的一行：状态点 + 主文案（+ 失败原因与出路），成功时行尾可带「查看」。 */
@Composable
private fun OnboardingSyncLine(
    ok: Boolean,
    shown: Boolean,
    okText: String,
    failText: String,
    failReason: String?,
    failHint: String,
    onView: (() -> Unit)? = null,
) {
    if (!shown) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(
                    if (ok) MaterialTheme.semanticColors.success
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                ),
        )
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = if (ok) okText else failText,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!ok) {
                Text(
                    text = listOfNotNull(
                        failReason?.takeIf { it.isNotBlank() },
                        failHint,
                    ).joinToString("。"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }
        if (ok && onView != null) {
            TextButton(onClick = onView) { Text("查看") }
        }
    }
}

@Composable
internal fun JuwApp(
    pendingRoute: MutableState<String?>? = null,
    pendingSubpages: MutableState<List<SubpageRequest>>? = null,
) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val haptics = rememberAppHaptics()

    // 层叠抽屉（2026-10-20）：主页面在前、侧栏在后。300dp 是页面完全打开时的位移，
    // 也是侧栏内容的宽度；拖拽 / 吸附 / 返回关闭都在 StackedDrawerState 里。
    val drawerScope = rememberCoroutineScope()
    val drawerDensity = LocalDensity.current
    val drawerMaxOffsetPx = with(drawerDensity) { 300.dp.toPx() }
    val drawerThresholdPx = with(drawerDensity) { 32.dp.toPx() }
    val drawerLockPx = with(drawerDensity) { 20.dp.toPx() }
    val drawer = remember {
        StackedDrawerState(
            scope = drawerScope,
            maxOffsetPx = drawerMaxOffsetPx,
            dragThresholdPx = drawerThresholdPx,
            directionLockPx = drawerLockPx,
            onSettled = { haptics.tap() },
        )
    }
    DrawerBackHandler(drawer)
    // 从侧栏钱包发起充值：先落一个待办，再进隐藏的 Life 页让原有充值流程接管
    var pendingLifeRecharge by remember { mutableStateOf<RechargeKind?>(null) }

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
        } else if (route == ROUTE_ME) {
            // 「我的」不再是 Tab（2026-10-20 收进侧栏）：登录失效通知原本落这一页，
            // 现在直接落「账号与登录」——它才是那类通知真正要人处理的地方。
            pendingRoute.value = null
            SubpageActivity.start(context, SubpageScreen.ACCOUNTS)
        } else if (
            route == ROUTE_PAY_CODE || route == ROUTE_POWER_BILL || route == ROUTE_CAMPUS_CARD ||
            route == ROUTE_WATER || route == ROUTE_QZXY ||
            route == ROUTE_WATER_START || route == ROUTE_QZXY_START
        ) {
            // 小组件点击（DESIGN §3.6 二条目改版 + 开水两卡）：落点不是底栏 Tab 而是二级页
            // （付款码 / 用电统计 / 校园卡设置 / 开水页 / 趣智页）。仍走「先消费再启动」，
            // 与上面同一套防重组回弹的口径；主界面在底下保持今日页原样。
            // 「去开水」胶囊（_start）在进页前武装一次性令牌，页面首帧消费后自动开水；
            // 整卡其余位置不带令牌，只进页面
            pendingRoute.value = null
            val screen = when (route) {
                ROUTE_PAY_CODE -> SubpageScreen.PAY_CODE
                ROUTE_POWER_BILL -> SubpageScreen.POWER_BILL
                ROUTE_WATER, ROUTE_WATER_START -> SubpageScreen.WATER
                ROUTE_QZXY, ROUTE_QZXY_START -> SubpageScreen.QZXY
                else -> SubpageScreen.CAMPUS_CARD_SETTINGS
            }
            if (route == ROUTE_WATER_START || route == ROUTE_QZXY_START) {
                WaterAutoStart.arm(screen)
            }
            openSubpage(context, SubpageRequest(screen = screen))
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

    // 首启引导的后台同步结果（DESIGN §4.17 / §4.29）：引导结束后在主界面弹一次结果弹窗，
    // 成功的项带「查看」入口，失败的给手动导入的出路；看过即消费，绝不弹第二次。
    // 写入端（OnboardingScreen.startBgSync）在三项全部 settle 后一次性落盘，
    // 这里读到非空即终态；onboardingSeen 同时为 true 才弹——防「结果先落、引导还在走」。
    val outcomePrefs = remember { Graph.displayPrefs(context) }
    val syncOutcome by outcomePrefs.onboardingSyncOutcome.collectAsStateWithLifecycle(initialValue = null)
    val onboardingSeen by outcomePrefs.onboardingSeen.collectAsStateWithLifecycle(initialValue = false)
    val outcomeScope = rememberCoroutineScope()
    // 局部量接一次再判空：委托属性不吃智能转换
    val outcome = syncOutcome
    val showSyncOutcome = outcome != null && !outcome.consumed && onboardingSeen
    // 「查看」只跳页不消费（用户看完回来还要看别的）；连点用时间戳挡，防重复 startActivity
    var lastViewClickAt by remember { mutableStateOf(0L) }
    if (showSyncOutcome && outcome != null) {
        OnboardingSyncResultDialog(
            outcome = outcome,
            onConsume = {
                outcomeScope.launch { outcomePrefs.setOnboardingSyncOutcome(outcome.copy(consumed = true)) }
            },
            onOpenSubpage = { screen ->
                val now = System.currentTimeMillis()
                if (now - lastViewClickAt > 800) {
                    lastViewClickAt = now
                    openSubpage(context, SubpageRequest(screen = screen))
                }
            },
        )
    }

    // 「支付成功」弹窗（DESIGN §3.10）：付款码页检测到扣款会自行退出，结果经
    // PayCodeResultBus 落到这里（取值即消费）。挂在根层、四个 Tab 之上——
    // 今日页「+ → 付款码」与生活页付款码条两条出码路径共用这一个宿主
    // （此前只有生活页自己弹，从今日页出码付款后没人消费结果，弹窗会一直挂着、
    // 直到下次打开生活页才迟到弹出；2026-10-07 补齐）。校园卡设置页是独立窗口，
    // 那份宿主在那边的页面里。
    CampusPayResultDialogHost()

    // 「发现新版本」弹窗（DESIGN §3.3，2026-10-08）：冷启动自动检查的结果经
    // UpdateAvailableBus 落在这里。「同步完成」结果弹窗还没消费时先不弹（防叠窗），
    // 消费后自动接着弹；用户关掉弹窗（稍后 / 忽略 / 下载）才消费——转屏重建时弹窗还在。
    // **不挂 onboardingSeen 闸**：引导在顶层时本窗口天然被盖住（独立窗口、不透明）；
    // 而老用户设备上没有那个键（2026-09-30 才加、只服务新安装），挂上会让他们的
    // 自动提醒永远不弹。
    UpdateAvailableDialogHost(enabled = !showSyncOutcome)

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

    // 趣智校园 ViewModel 挂 Activity 作用域（DESIGN §4.30）：侧栏趣智入口与趣智校园页
    // 各自持有实例，这份供侧栏入口显示登录态与余额。
    val qzxyViewModel: QzxyViewModel = viewModel(
        viewModelStoreOwner = context as ComponentActivity,
        factory = QzxyViewModel.Factory(
            Graph.qzxy(context),
            QzxyBluetoothScanner(context),
            Graph.qzxyLink(context),
            Graph.qzxyFlowLock,
            Graph.qzxyDevices(context),
            Graph.qzxyDebug(context),
            Graph.qzxyClear(context),
            Graph.qzxyWatering(context),
        ),
    )

    // ── 侧栏里的三个入口弹窗（2026-10-20）──
    // 用户口径：U净 / 胖乖生活 / 趣智校园点开的是**原来的弹窗**，不是整页。
    // 弹窗与趣智校园页共用同一份 ViewModel，面板里开的阀在页面上状态一致。
    val waterEntryState by waterViewModel.uiState.collectAsStateWithLifecycle()
    val qzxyEntryState by qzxyViewModel.uiState.collectAsStateWithLifecycle()
    val qzxyLoggedIn by remember { Graph.qzxy(context).loggedIn }.collectAsStateWithLifecycle()
    var showWaterSheet by remember { mutableStateOf(false) }
    var showQzxySheet by remember { mutableStateOf(false) }
    var showUjingSheet by remember { mutableStateOf(false) }

    // U净 弹窗的扫码：洗衣机机身码原文交给 U净 页识别（同今日页口径），别家码不跳页
    val ujingScanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) {
            return@rememberLauncherForActivityResult
        }
        val target = quickScanTargetOf(result.data?.getStringExtra(Intents.Scan.RESULT))
        if (target is QuickScanTarget.Washer) {
            UjingScanHandoff.arm(target.raw)
            drawer.close()
            SubpageActivity.start(context, SubpageScreen.UJING)
        }
    }

    // 趣智开阀要连蓝牙：缺权限时就地拉起系统授权，授完接着开（同今日页口径）
    val qzxyPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.all { granted -> granted }) {
            qzxyViewModel.openValveFromCard()
        }
    }
    val startQzxyValve: () -> Unit = {
        val missing = AppPermissions.missingBluetoothScan(context)
        if (missing.isEmpty()) qzxyViewModel.openValveFromCard()
        else qzxyPermissionLauncher.launch(missing.toTypedArray())
    }

    // 侧栏行右侧的余额：未登录 / 还没拉到就留空，不占位。
    // 侧栏展开时静默刷一轮（节流 60 秒）：行现在直接进页，余额平时只在这看一眼，
    // 进程存续期只靠初始化那一次会越放越旧。节流值放外侧：LaunchedEffect 重启
    // （drawer 翻转）时读的是上一轮的时刻。
    // 问渠工时（2026-10-21）：侧栏问渠行右侧的展示数。工时影子在会话里（登录 / 续期 /
    // 问渠页、「我的」页刷新都会写回），这里只订阅流；侧栏展开时的静默补刷走同一道闸
    // （TTL 30 分钟由仓库 hoursStale 判，过期才发请求）。
    val wenquRepo = remember { Graph.wenqu(context) }
    val wenquSession by wenquRepo.session.collectAsStateWithLifecycle()
    val wenquHoursText = wenquSession?.takeIf { it.hours > 0.0 }
        ?.let { "${WenquRules.formatHours(it.hours)} 工时" }
    var sideBalanceRefreshedAt by remember { mutableStateOf(0L) }
    LaunchedEffect(drawer.isVisible) {
        if (!drawer.isVisible) return@LaunchedEffect
        val now = System.currentTimeMillis()
        if (now - sideBalanceRefreshedAt < SIDE_BALANCE_REFRESH_TTL_MILLIS) return@LaunchedEffect
        sideBalanceRefreshedAt = now
        if (waterEntryState.loggedIn) waterViewModel.refreshBalance()
        if (qzxyLoggedIn) qzxyViewModel.refreshAccount()
        wenquRepo.refreshHoursIfStale()
    }
    val waterBalanceText = waterEntryState.balance?.takeIf { waterEntryState.loggedIn }
        ?.let { "¥${it.ticketText}" }
    val qzxyBalanceText = qzxyEntryState.balance?.takeIf { qzxyLoggedIn }
        ?.let { "¥${it.text}" }

    // 悬浮导航栏（DESIGN §4.22）：底栏形态是全局项，这里单独订阅一次。
    // 不复用 JuwRoot 那份：它是为了定主题色，作用域在 setContent 最外层，拿不到这里。
    // 壳层只收 [AppShellPrefs] 切片（底栏开关 + 背景图五参数）：NavHost 挂在壳里，
    // 收整份 DisplayPrefs 的话，拖一根字号滑杆都会重组整个壳。
    val shellPrefs by remember { Graph.repository(context).appShellPrefs }
        .collectAsStateWithLifecycle(initialValue = null)

    // 悬浮导航栏：只用启动期冻结的那份（见 floatingNavBarEffective 的 KDoc）。
    // shellPrefs 流仍订阅着背景图/生活页开关等运行时可变项，别顺手把这里改回它。
    val floatingNavBar = floatingNavBarEffective ?: resolveFloatingNavBarBlocking()
        .also { floatingNavBarEffective = it }

    // 启动页（DESIGN §3.3）：按窗口读死一次（见 resolveStartRouteBlocking 的 KDoc）——
    // NavHost 的 startDestination 中途变会把用户弹回起点，改完由设置页提示重启生效。
    val startRoute = remember { resolveStartRouteBlocking() }

    // 底栏三项：今日 · 课表 · 教务（生活/我的已收进侧栏，2026-10-20）
    val tabs = buildList {
        add(BottomTab(Routes.TODAY, R.string.tab_today, HugeIcons.Calendar01))
        add(BottomTab(Routes.WEEK, R.string.tab_week, HugeIcons.Book01))
        add(BottomTab(Routes.JW, R.string.tab_jw, HugeIcons.School01))
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

    // 应用内悬浮球（DESIGN §3.1）：开关 + 位置，独立单流各收一次（combine 已满员）。
    // 首帧按「开 + 右下角」渲染，真值到达后归位——与底栏同纪律：宁可先显示再收。
    val repo = remember(context) { Graph.repository(context) }
    val floatingBallEnabled by remember { repo.floatingBallEnabled }
        .collectAsStateWithLifecycle(initialValue = true)
    val floatingBallPosition by remember { repo.floatingBallPosition }
        .collectAsStateWithLifecycle(initialValue = FloatingBall.DefaultPosition)
    // 外壳级提示通道：悬浮球「别家码」这类播报落在主窗口——页面各自的 Snackbar 在 NavHost
    // 里，够不到壳层的球（球的动作是壳级、不属于任何一页）
    val shellSnackbar = remember { SnackbarHostState() }
    val shellScope = rememberCoroutineScope()
    // 悬浮球的双击动作：扫一扫，复用与今日页「+ → 扫一扫」同一套三档分流（车码 / 机身码 / 别家码）
    val startBallScan = rememberQuickScan(
        onEbike = { carNum ->
            openSubpage(context, SubpageRequest(screen = SubpageScreen.RIDE, focusItemId = carNum))
        },
        onWasher = { raw ->
            UjingScanHandoff.arm(raw)
            openSubpage(context, SubpageRequest(screen = SubpageScreen.UJING))
        },
        onForeign = { label ->
            shellScope.postNotice(
                shellSnackbar,
                "这是${label}的码，不是快趣车身码或洗衣机机身码",
                NoticeTone.Warning,
            )
        },
    )
    // 悬浮球让开状态栏与底栏：可拖动区域 = 窗口 − 状态栏 −（手势条 + 底栏高）
    val statusBarTopDp = with(LocalDensity.current) {
        WindowInsets.statusBars.getTop(this).toDp()
    }
    val bottomReserveDp = navBarInsetDp +
        if (floatingNavBar) FloatingPillHeight + FloatingBarBottomMargin else PlainBottomBarHeight
    // 悬浮球只在主窗口四个 Tab 显示（二级页是独立 Activity 窗口，本就看不到它）
    val floatingBallOnTab = currentRoute == Routes.TODAY || currentRoute == Routes.WEEK ||
        currentRoute == Routes.LIFE

    // 侧栏里点普通入口**不收侧栏**（2026-10-21 用户口径，与设置同一个姿势）：
    // 二级页是独立窗口，直接盖在侧栏上；返回时侧栏还开着，用户接着点下一项。
    // 此前先 close() 再起窗口，收起动画与窗口推入叠在一起，观感很别扭。
    // 例外只有两类「动作」而不是「入口」：扫一扫、充值（后者要导航到隐藏的 Life 页）。
    val openFromDrawer: (SubpageScreen, String?) -> Unit = { screen, focus ->
        openSubpage(context, SubpageRequest(screen = screen, focusItemId = focus))
    }

    // ── 层叠抽屉（2026-10-20 第二版）──
    // 层级：侧栏是**静态后层**，主页面是前层卡片——展开时卡片右移并微微缩小，
    // 上下露出后层。draggable + 20dp 方向锁 + 32dp 落定阈值 + 临界阻尼弹簧。
    Box(
        Modifier
            .fillMaxSize()
            // 后层底色 = 侧栏底色：卡片缩小后露出的上下留白与侧栏是同一层。
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        val cardDragState = rememberDraggableState { delta -> drawer.drag(delta) }
        val panelDragState = rememberDraggableState { delta -> drawer.drag(delta) }
        // 底层：侧栏**不动**，只是被上层卡片让开。整块底色与侧栏同色，
        // 卡片缩小后露出的上下留白读起来是「同一层」，而不是两套颜色。
        Surface(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .width(300.dp)
                .then(
                    if (drawer.isVisible) {
                        Modifier.draggable(
                            orientation = Orientation.Horizontal,
                            state = panelDragState,
                            onDragStarted = { drawer.beginDrag() },
                            onDragStopped = { v -> drawer.settle(v) },
                        )
                    } else {
                        Modifier
                    },
                ),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            SideDrawerContent(
                onOpenAccounts = { openFromDrawer(SubpageScreen.ACCOUNTS, null) },
                onOpenStudentInfo = { openFromDrawer(SubpageScreen.STUDENT_INFO, null) },
                onOpenPayCode = { openFromDrawer(SubpageScreen.PAY_CODE, null) },
                onOpenShortcuts = { itemId -> openFromDrawer(SubpageScreen.SHORTCUTS, itemId) },
                onOpenCainiao = { openFromDrawer(SubpageScreen.TOOLBOX_CAINIAO, null) },
                // 三张卡（2026-10-21 用户口径）：行本体直接进二级页；右侧状态 / 余额才开弹窗。
                // 弹窗挂在外壳层（侧栏 300dp 宽会被裁），所以进页与开弹窗是两条回调
                onOpenUjing = { openFromDrawer(SubpageScreen.UJING, null) },
                onOpenUjingSheet = { showUjingSheet = true },
                onOpenAcRemote = { openFromDrawer(SubpageScreen.AC_REMOTE, null) },
                onOpenRide = { openFromDrawer(SubpageScreen.RIDE, null) },
                onOpenWater = { openFromDrawer(SubpageScreen.WATER, null) },
                onOpenWaterSheet = { showWaterSheet = true },
                onOpenQzxy = { openFromDrawer(SubpageScreen.QZXY, null) },
                onOpenQzxySheet = { showQzxySheet = true },
                waterBalance = waterBalanceText,
                qzxyBalance = qzxyBalanceText,
                wenquHours = wenquHoursText,
                // U净 空闲摘要的拉取闸：侧栏可见才发请求（与余额刷新同一口径）
                drawerVisible = drawer.isVisible,
                onOpenWenqu = { openFromDrawer(SubpageScreen.WENQU, null) },
                onOpenNotes = { openFromDrawer(SubpageScreen.NOTES, null) },
                onOpenHomework = { openFromDrawer(SubpageScreen.HOMEWORK, null) },
                onOpenToolbox = { openFromDrawer(SubpageScreen.TOOLBOX, null) },
                onOpenWeather = { openFromDrawer(SubpageScreen.WEATHER, null) },
                onOpenStatement = { openFromDrawer(SubpageScreen.CAMPUS_STATEMENT, null) },
                onOpenPowerBill = { openFromDrawer(SubpageScreen.POWER_BILL, null) },
                onOpenCampusRecharge = {
                    drawer.close()
                    pendingLifeRecharge = RechargeKind.Campus
                    navController.navigate(Routes.LIFE) { launchSingleTop = true }
                },
                onOpenPowerRecharge = {
                    drawer.close()
                    pendingLifeRecharge = RechargeKind.Power
                    navController.navigate(Routes.LIFE) { launchSingleTop = true }
                },
                onOpenSettings = {
                    SettingsActivity.start(context)
                },
                onScan = {
                    drawer.close()
                    startBallScan()
                },
            )
        }
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = drawer.progress
                    val eased = FastOutSlowInEasing.transform(abs(p))
                    // 用户参考的层级：主页面**微微缩小**并右移，上下露出后层；
                    // 左缘始终贴在侧栏宽度上（缩放绕中心，补回一半的收缩量）。
                    val scale = 1f - CardShrink * eased
                    scaleX = scale
                    scaleY = scale
                    translationX = drawerMaxOffsetPx * p - (1f - scale) * size.width / 2f
                    shape = RoundedCornerShape(24.dp * eased)
                    shadowElevation = (12.dp * eased).toPx()
                    clip = true
                }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = cardDragState,
                    onDragStarted = { drawer.beginDrag() },
                    onDragStopped = { v -> drawer.settle(v) },
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    if (drawer.isVisible) drawer.close()
                },
            color = MaterialTheme.colorScheme.background,
        ) {
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
                    fileName = shellPrefs?.bgImageName,
                    opacity = shellPrefs?.bgImageOpacity ?: 1f,
                    dim = shellPrefs?.bgImageDim ?: 0f,
                    blur = shellPrefs?.bgImageBlur ?: 0f,
                    scale = shellPrefs?.bgImageScale ?: BgScale.Fill,
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
                            // 胖乖生活卡常显（未登录给未登录态，显示设置可关，DESIGN §3.3）；
                            // 登录态由 WaterViewModel 自带，外层不再按登录与否隐藏整卡
                            onOpenWater = { SubpageActivity.start(context, SubpageScreen.WATER) },
                            // 快趣出行（DESIGN §3.9）：今日页已不展示卡片；入口在左侧栏。
                            // 2026-09-29 结构重构后两档共用同一个页面（地图 + 抽屉三态），
                            // 不再按使用方式路由到两个页面，也不再需要「选车回传」那条通道
                            onOpenEbike = { SubpageActivity.start(context, SubpageScreen.RIDE) },
                            // 顶栏「+ → 扫一扫」扫到车身码：带完整车号进骑行页（进页即定位，
                            // DESIGN §3.9 的 focusItemId 复用）
                            onOpenRideCar = { carNum ->
                                SubpageActivity.start(
                                    context,
                                    SubpageScreen.RIDE,
                                    focusItemId = carNum,
                                )
                            },
                            // 扫到洗衣机机身码：原文走一次性令牌（UjingScanHandoff）交给
                            // U净 页自己识别——「扫码 → 开草稿 → 跳下单页」只有那一条实现
                            onOpenUjingScan = { raw ->
                                UjingScanHandoff.arm(raw)
                                SubpageActivity.start(context, SubpageScreen.UJING)
                            },
                            // 江水生活图标入口（2026-10-19 自生活页迁入）：
                            // U净 弹窗副行进洗衣房页；取快递弹窗的新增/编辑进快捷方式设置
                            onOpenUjing = {
                                SubpageActivity.start(context, SubpageScreen.UJING)
                            },
                            onOpenShortcuts = { focusItemId ->
                                SubpageActivity.start(context, SubpageScreen.SHORTCUTS, focusItemId)
                            },
                            onOpenCainiao = {
                                SubpageActivity.start(context, SubpageScreen.TOOLBOX_CAINIAO)
                            },
                            onOpenAcRemote = {
                                SubpageActivity.start(context, SubpageScreen.AC_REMOTE)
                            },
                            // 宿舍报修进度（DESIGN §4.49）：今日页卡片进原生只读详情页
                            onOpenDormRepair = {
                                SubpageActivity.start(context, SubpageScreen.DORM_REPAIR)
                            },
                            // 付款码（DESIGN §3.10）：校园卡付款码展示页，独立窗口
                            onOpenPayCode = {
                                SubpageActivity.start(context, SubpageScreen.PAY_CODE)
                            },
                            // 收件箱（DESIGN §3.24，2026-10-21 收口）：今日页收件箱卡
                            // 「查看更多」的落点，独立窗口
                            onOpenInbox = {
                                SubpageActivity.start(context, SubpageScreen.INBOX)
                            },
                            // 百宝箱（DESIGN §4.47，2026-10-12）：小工具集合，
                            // 「+」菜单里那一行的总开关在「设置 → 功能」（默认开）
                            onOpenToolbox = {
                                SubpageActivity.start(context, SubpageScreen.TOOLBOX)
                            },
                            // 天气详情（DESIGN §3.29，2026-10-11）：顶栏天气句的落点
                            onOpenWeather = {
                                SubpageActivity.start(context, SubpageScreen.WEATHER)
                            },
                            // 课表句（南北楼 / 实验课）的落点：切到课表 Tab，
                            // 与底栏同一套导航参数（2026-10-20）
                            onOpenWeek = {
                                navController.navigate(Routes.WEEK) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
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
                            // 趣智校园卡（DESIGN §4.30）：与胖乖生活并排，默认开、可关
                            onOpenQzxy = { SubpageActivity.start(context, SubpageScreen.QZXY) },
                            // 开水面板失败态「详情」的出口（DESIGN §3.18）：
                            // 带当前设备地址过去，诊断页据此选中同一台
                            onOpenQzxyDebug = { address ->
                                SubpageActivity.start(
                                    context,
                                    SubpageScreen.QZXY_DEBUG,
                                    focusItemId = address,
                                )
                            },
                            qzxyViewModel = qzxyViewModel,
                            // 左侧栏：汉堡在今日页顶栏，切换开关（2026-10-20）
                            onOpenDrawer = {
                                // 用 isVisible 而不是 isOpen：展开动画途中再点一次要能收回，
                                // isOpen 要等动画播完才翻。
                                if (drawer.isVisible) drawer.close() else drawer.open()
                            },
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
                    // 教务 Tab（2026-10-20）：入口汇总；每个入口落到原生二级页。
                    // 网页核对按钮已经收进各业务页顶栏，首页不再重复挂「网页」入口。
                    composable(Routes.JW) {
                        JwHubScreen(
                            onOpenTheorySchedule = {
                                openSubpage(context, SubpageRequest(screen = SubpageScreen.THEORY_SCHEDULE))
                            },
                            onOpenLabSchedule = {
                                openSubpage(context, SubpageRequest(screen = SubpageScreen.LAB_SCHEDULE))
                            },
                            onOpenScores = { openSubpage(context, SubpageRequest(screen = SubpageScreen.SCORES)) },
                            onOpenExams = { openSubpage(context, SubpageRequest(screen = SubpageScreen.EXAMS)) },
                            onOpenTextbooks = { openSubpage(context, SubpageRequest(screen = SubpageScreen.TEXTBOOKS)) },
                            onOpenScholar = { openSubpage(context, SubpageRequest(screen = SubpageScreen.SCHOLAR)) },
                            onOpenCurriculum = { openSubpage(context, SubpageRequest(screen = SubpageScreen.CURRICULUM)) },
                            onOpenSelection = { openSubpage(context, SubpageRequest(screen = SubpageScreen.SELECTIONS)) },
                            onOpenInternship = {
                                openSubpage(context, SubpageRequest(screen = SubpageScreen.INTERNSHIP_BASES))
                            },
                            onOpenTeachingCalendar = {
                                openSubpage(context, SubpageRequest(screen = SubpageScreen.TEACHING_CALENDAR))
                            },
                            onOpenTeachingEval = { JwImportActivity.start(context, JwImportMode.TeachingEval) },
                            onOpenLeave = { XgFormActivity.start(context, XgUrls.LEAVE) },
                            onOpenRepair = { openSubpage(context, SubpageRequest(screen = SubpageScreen.DORM_REPAIR)) },
                            onOpenCalendar = {
                                openSubpage(context, SubpageRequest(screen = SubpageScreen.CAMPUS_CALENDAR))
                            },
                            onOpenImport = { JwImportActivity.start(context) },
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
                            // 校园服务（2026-10-19）：考试安排 / 成绩查询两格直达；
                            // 教务服务 / 学业数据两格各自弹入口弹窗
                            onOpenWenqu = {
                                SubpageActivity.start(context, SubpageScreen.WENQU)
                            },
                            onOpenScores = {
                                SubpageActivity.start(context, SubpageScreen.SCORES)
                            },
                            onOpenExam = {
                                SubpageActivity.start(context, SubpageScreen.EXAMS)
                            },
                            // 教务服务弹窗：学生评教 / 宿舍报修 / 请假 / 校历
                            onOpenTeachingEval = {
                                JwImportActivity.start(context, JwImportMode.TeachingEval)
                            },
                            onOpenDormRepair = {
                                XgFormActivity.start(context, XgUrls.REPAIR)
                            },
                            onOpenLeave = {
                                XgFormActivity.start(context, XgUrls.LEAVE)
                            },
                            onOpenCalendar = {
                                SubpageActivity.start(context, SubpageScreen.CAMPUS_CALENDAR)
                            },
                            // 学业数据弹窗：我的 → 学习「学业数据」全部入口 + 实习基地查看
                            onOpenScholar = {
                                SubpageActivity.start(context, SubpageScreen.SCHOLAR)
                            },
                            onOpenCurriculum = {
                                SubpageActivity.start(context, SubpageScreen.CURRICULUM)
                            },
                            onOpenSelection = {
                                SubpageActivity.start(context, SubpageScreen.SELECTIONS)
                            },
                            onOpenInternshipBases = {
                                SubpageActivity.start(context, SubpageScreen.INTERNSHIP_BASES)
                            },
                            initialRecharge = pendingLifeRecharge,
                            onInitialRechargeConsumed = { pendingLifeRecharge = null },
                        )
                    }
                }
            }
            // 侧栏三个入口弹窗（2026-10-20）：与对应页面共用同一份 ViewModel。
            // 放在外壳层而不是侧栏里——侧栏是 300dp 宽的 Surface，弹层挂在它下面会跟着被裁。
            if (showWaterSheet) {
                WaterEntrySheet(
                    state = waterEntryState,
                    onUnlock = { waterViewModel.unlock() },
                    onDismissFlow = { waterViewModel.dismissFlow() },
                    onToggleUsePoints = { waterViewModel.toggleUsePoints() },
                    onEndSession = { waterViewModel.endSession() },
                    onDismiss = { showWaterSheet = false },
                )
            }
            if (showQzxySheet) {
                QzxyEntrySheet(
                    state = qzxyEntryState,
                    loggedIn = qzxyLoggedIn,
                    onStart = startQzxyValve,
                    onResend = { qzxyViewModel.resendOpenData() },
                    onStop = { qzxyViewModel.stopWater() },
                    onAbandon = { qzxyViewModel.abandonWatering() },
                    onClearDeviceRecord = { qzxyViewModel.clearDeviceRecord() },
                    onClearAndOpen = { qzxyViewModel.clearAndOpen() },
                    onDismissSettlement = { qzxyViewModel.dismissSettlement() },
                    onOpenDiagnostics = { address ->
                        drawer.close()
                        SubpageActivity.start(
                            context,
                            SubpageScreen.QZXY_DEBUG,
                            focusItemId = address,
                        )
                    },
                    onOpenPage = {
                        drawer.close()
                        SubpageActivity.start(context, SubpageScreen.QZXY)
                    },
                    onDismiss = { showQzxySheet = false },
                )
            }
            if (showUjingSheet) {
                UjingEntrySheet(
                    onDismiss = { showUjingSheet = false },
                    onScan = {
                        showUjingSheet = false
                        ujingScanLauncher.launch(
                            Intent(context, EbikeScanActivity::class.java).apply {
                                putExtra(Intents.Scan.PROMPT_MESSAGE, "对准洗衣机机身上的二维码")
                                putExtra(EbikeScanActivity.EXTRA_RESULT_MODE, EbikeScanActivity.MODE_RAW)
                                putExtra(Intents.Scan.FORMATS, BarcodeFormat.QR_CODE.name)
                            },
                        )
                    },
                    onOpenPage = {
                        showUjingSheet = false
                        drawer.close()
                        SubpageActivity.start(context, SubpageScreen.UJING)
                    },
                )
            }
            // 外壳级提示通道（悬浮球「别家码」等播报）：贴底、让开底栏。画在页面之上、
            // 底栏之下——球被 inset 顶在底栏之上，这条提示与它同一带，互不遮挡。
            AppSnackbarHost(
                shellSnackbar,
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = bottomReserveDp),
            )
            // 应用内悬浮球（DESIGN §3.1）：主窗口四个 Tab 常驻。显示设置面板打开时
            // （bottomBarVisible 为 false）一并收起，免得压在面板上。
            if (floatingBallEnabled && floatingBallOnTab && bottomBarVisible) {
                FloatingBallOverlay(
                    position = floatingBallPosition,
                    // 单击 → 付款码页（独立窗口）
                    onTap = { openSubpage(context, SubpageRequest(screen = SubpageScreen.PAY_CODE)) },
                    // 双击 → 扫一扫（车码 / 机身码 / 别家码三档分流，见 startBallScan）
                    onDoubleTap = startBallScan,
                    onPositionChange = { pos ->
                        shellScope.launch { repo.setFloatingBallPosition(pos) }
                    },
                    topInset = statusBarTopDp,
                    bottomInset = bottomReserveDp,
                )
            }
        }
    }
        }
    }
}

@Composable
private fun DrawerBackHandler(drawer: StackedDrawerState) {
    // 只读布尔量：progress 是 Animatable 的逐帧状态，放进 enabled 会让这里每帧重组。
    BackHandler(enabled = drawer.isVisible) { drawer.close() }
}

object Routes {
    const val TODAY = "today"
    const val WEEK = "week"
    const val JW = "jw"
    const val LIFE = "life"
}
