package edu.jxslu.schedule.ui.today

import android.app.Activity
import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
// M3 1.3.0 的下拉刷新不在 material3 根包，而在 pulltorefresh 子包
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.client.android.Intents
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.R
import edu.jxslu.schedule.domain.Course
import edu.jxslu.schedule.domain.DormRepairRecord
import edu.jxslu.schedule.domain.DormRepairRules
import edu.jxslu.schedule.domain.EbikeFreeRide
import edu.jxslu.schedule.domain.EbikeUseMode
import edu.jxslu.schedule.domain.QuickScanTarget
import edu.jxslu.schedule.domain.ShortcutSettings
import edu.jxslu.schedule.domain.TodayState
import edu.jxslu.schedule.domain.clockOf
import edu.jxslu.schedule.domain.dayLabel
import edu.jxslu.schedule.domain.quickScanTargetOf
import edu.jxslu.schedule.ui.common.showNotice
import edu.jxslu.schedule.ui.common.ShortcutLauncher
import edu.jxslu.schedule.ui.common.ShortcutPinner
import edu.jxslu.schedule.ui.common.ShortcutSheet
import edu.jxslu.schedule.ui.ebike.EbikeScanActivity
import edu.jxslu.schedule.ui.qzxy.QzxyViewModel
import edu.jxslu.schedule.ui.ujing.UjingEntrySheet
import edu.jxslu.schedule.ui.qzxy.QzxyEntrySheet
import edu.jxslu.schedule.ui.qzxy.QzxyUiState
import edu.jxslu.schedule.ui.qzxy.WateringReconcileEffect
import edu.jxslu.schedule.ui.qzxy.isQzxyWateringExpired
import edu.jxslu.schedule.ui.qzxy.rememberQzxyWateringClock
import edu.jxslu.schedule.ui.common.AppPermissions
import edu.jxslu.schedule.domain.placeTeacherLine
import edu.jxslu.schedule.domain.remarkPreview
import edu.jxslu.schedule.domain.textbookTitlesLine
import edu.jxslu.schedule.domain.sectionRange
import edu.jxslu.schedule.domain.sectionShortLabel
import edu.jxslu.schedule.domain.TimeSlot
import edu.jxslu.schedule.domain.UnlockFlowState
import edu.jxslu.schedule.domain.calculateActualCost
import edu.jxslu.schedule.ui.common.ResumedTickerEffect
import edu.jxslu.schedule.ui.common.AppCardRow
import edu.jxslu.schedule.ui.common.QuickCardMinHeight
import edu.jxslu.schedule.ui.common.QuickEntryCard
import edu.jxslu.schedule.ui.common.AppCardDefaults
import edu.jxslu.schedule.ui.common.AppNoticeVisuals
import edu.jxslu.schedule.ui.jwvw.JwImportOutcomeEffect
import edu.jxslu.schedule.ui.common.AppCard
import edu.jxslu.schedule.ui.common.LocalBottomBarClearance
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.CourseDetailSheet
import edu.jxslu.schedule.ui.common.CourseEditSheet
import edu.jxslu.schedule.ui.common.DeleteConfirmDialog
import edu.jxslu.schedule.ui.common.EmptyHint
import edu.jxslu.schedule.ui.common.LoadingHint
import edu.jxslu.schedule.ui.common.NoticeTone
import edu.jxslu.schedule.ui.common.pinnedStatusBars
import edu.jxslu.schedule.ui.common.SectionHeader
import edu.jxslu.schedule.ui.common.courseColor
import edu.jxslu.schedule.ui.common.postNotice
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import edu.jxslu.schedule.ui.water.WaterEvent
import edu.jxslu.schedule.ui.water.WaterUiState
import edu.jxslu.schedule.ui.water.WaterViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.CalendarOff
import me.rerere.hugeicons.stroke.CheckmarkCircle02
import me.rerere.hugeicons.stroke.ChevronDown
import me.rerere.hugeicons.stroke.ChevronUp
import me.rerere.hugeicons.stroke.Droplet
import me.rerere.hugeicons.stroke.ShowerHead
import me.rerere.hugeicons.stroke.Inbox
import me.rerere.hugeicons.stroke.Menu01
import me.rerere.hugeicons.stroke.Package01
import me.rerere.hugeicons.stroke.RemoteControl
import me.rerere.hugeicons.stroke.WashingMachine
import me.rerere.hugeicons.stroke.QrCodeScan
import me.rerere.hugeicons.stroke.ScooterElectric
import me.rerere.hugeicons.stroke.Toolbox
import me.rerere.hugeicons.stroke.Wallet01
import edu.jxslu.schedule.domain.TodayTip
import edu.jxslu.schedule.domain.PendingHomework
import edu.jxslu.schedule.ui.homework.HomeworkTodayCard

/** U净 弹窗打开时对订单的节流窗口：10 秒内不重复请求（同原生活页口径）。 */
private const val UJING_ORDER_REFRESH_TTL_MILLIS = 10_000L

/**
 * 今日课表。
 *
 * 页面骨架：**顶部焦点卡（正在上课 / 下一节）+ 一列时间轴课程行 + 贴底固定区**。
 * - 焦点课只出现一次：焦点卡拿走第一门课，[TodayState.listCourses] 已把该课剔除，
 *   旧版「状态卡 + 列表首项」显示同一节课的问题不复存在。
 * - 「下一节」有 60 分钟准入窗口（[TodayState.next]）：更远的课不冒充「下一节」，
 *   页面直接从列表开始 + 一行「今天的课 XX:XX 开始」轻提示。
 * - 焦点卡与时间轴行同为**满宽卡片**（同外边距/内边距/圆角，缘对缘对齐）；
 *   区分靠底色（主色 vs 课程色）与焦点卡状态行/进度条。节次编号只在焦点卡出现。
 * - 已结束的课不显示（保持既有取舍）；今天没有待上课程时才轮到「明天」上桌。
 * - 点课程（焦点卡/课程行）弹**只读详情**（[CourseDetailSheet]，与课表页同口径），
 *   编辑/删除是详情里的二级动作——直跳编辑器易误触。
 *
 * 底部固定区（2026-10-20）：**整块 dock 已收进左侧栏**（取快递 / U净 / 空调遥控 /
 * 快趣出行 / 胖乖 / 趣智 / 问渠 / 笔记 / 作业 / 百宝箱 / 收件箱 / 天气）。
 * 今日页只留课程与提示，课程区拿回全部高度；列表最后一项自己让开悬浮胶囊
 * （[LocalBottomBarClearance]）。旧的 dock 组件与 `todayDockExpanded` 偏好一并删除。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    onOpenJwImport: () -> Unit = {},
    /** 左侧栏入口（2026-10-20）：顶栏汉堡；开合与动画在壳层 */
    onOpenDrawer: () -> Unit = {},
    /** 「尚未开学」空态的 CTA：跳课表设置（学期起止） */
    onOpenTimetableSettings: () -> Unit = {},
    onOpenWater: () -> Unit = {},
    /** 趣智校园开热水页（DESIGN §4.30）：与胖乖生活并排的半行卡，独立窗口 */
    onOpenQzxy: () -> Unit = {},
    /** 趣智校园「诊断与调试」页：开水面板失败态「详情」的出口，带当前设备地址 */
    onOpenQzxyDebug: (String?) -> Unit = {},
    /** 快趣出行 · 骑行页（DESIGN §3.9，SubpageActivity 独立窗口；地图 + 抽屉三态） */
    onOpenEbike: () -> Unit = {},
    /** 顶栏「+ → 扫一扫」扫到车身码：带完整车号进骑行页（DESIGN §3.9 的 `focusItemId`） */
    onOpenRideCar: (String) -> Unit = {},
    /** 顶栏「+ → 扫一扫」扫到洗衣机机身码：原文交给 U净 页识别（DESIGN §3.23） */
    onOpenUjingScan: (String) -> Unit = {},
    /** 江水生活 U净 弹窗的「查看洗衣房空闲情况」：U净 · 洗衣房页 */
    onOpenUjing: () -> Unit = {},
    /** 江水生活「取快递」弹窗：「新增」/ 长按编辑 → 快捷方式设置页（null = 不定位） */
    onOpenShortcuts: (String?) -> Unit = {},
    /** 江水生活「取快递」弹窗里的「菜鸟批量查」：多单号一次导入取件码 */
    onOpenCainiao: () -> Unit = {},
    /** 江水生活「空调遥控」图标：寝室美的空调红外页（DESIGN §3.31） */
    onOpenAcRemote: () -> Unit = {},
    /** 今日页宿舍报修卡：原生进度页（DESIGN §4.49） */
    onOpenDormRepair: () -> Unit = {},
    /** 顶栏「+ → 付款码」：校园卡付款码展示页（DESIGN §3.10） */
    onOpenPayCode: () -> Unit = {},
    /** 顶栏「+ → 收件箱」：应用内重要事实的留痕页（DESIGN §3.24） */
    onOpenInbox: () -> Unit = {},
    /** 顶栏「+ → 百宝箱」：小工具集合（图片工具 + 菜鸟批量查，DESIGN §4.47，2026-10-12） */
    onOpenToolbox: () -> Unit = {},
    /** 顶栏天气提示那一行（DESIGN §3.29）：天气句点它进天气详情页 */
    onOpenWeather: () -> Unit = {},
    /** 顶栏课表提示（南北楼 / 实验课）：课表句点它进课表页（2026-10-20） */
    onOpenWeek: () -> Unit = {},
    /** 作业中心（今日页作业卡入口，DESIGN §3.11） */
    onOpenHomeworkTodo: () -> Unit = {},
    /** 某课程的笔记·课件（课程详情弹窗入口，DESIGN §3.11） */
    onOpenCourseNotes: (Course) -> Unit = {},
    /** 某课程的作业（课程详情弹窗入口，DESIGN §3.11） */
    onOpenCourseHomework: (Course) -> Unit = {},
    /** 与开水页共享的 Activity 作用域实例；开水卡的解锁进度与登录态两页一致 */
    waterViewModel: WaterViewModel? = null,
    /** 与趣智校园页共享的 Activity 作用域实例；今日页半行卡读它的登录态与余额 */
    qzxyViewModel: QzxyViewModel? = null,
    viewModel: TodayViewModel = viewModel(
        factory = TodayViewModel.Factory(
            Graph.repository(LocalContext.current),
            Graph.homeworkRepository(LocalContext.current),
                Graph.weatherRepository(LocalContext.current),
        ),
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
        // 顶栏天气提示（DESIGN §3.29）：功能关着 / 取不到数据时是 null，顶栏不占位
        val weatherLine by viewModel.weatherLine.collectAsStateWithLifecycle()
    // 开水面板（WaterEntrySheet）的实时状态：弹窗打开时逐帧跟随流程态，
    // 不能用打开瞬间的快照（Working 计时不会动）
    val waterEntryState by waterViewModel?.uiState
        ?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf(WaterUiState()) }
    // 趣智校园面板同理：面板里的计时与流程态要逐帧跟随，不能读打开瞬间的快照
    val qzxyEntryState by qzxyViewModel?.uiState
        ?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf(QzxyUiState()) }
    val homework by viewModel.homeworkPending.collectAsStateWithLifecycle()
    // 课程卡的教材名（DESIGN §4.31，2026-10-07）：当前课表学期下「课程名 → 书名列表」，
    // 挂在课名右边（2026-10-08 收口，不再另起一行）；单独订阅不掺课表状态（与 homework
    // 同纪律）；空映射 = 无教材数据，卡片不占位
    val textbookTitles by viewModel.textbookTitles.collectAsStateWithLifecycle()
    val waterCardEnabled by viewModel.waterCardEnabled.collectAsStateWithLifecycle()
    val qzxyCardEnabled by viewModel.qzxyCardEnabled.collectAsStateWithLifecycle()
    val ebikeCardEnabled by viewModel.ebikeCardEnabled.collectAsStateWithLifecycle()
    // 趣智用水对账（DESIGN §3.18）：今日页可见（含开水面板打开）期间把本地记账与
    // 设备核对——自动关停后自动结算 / 记账切「待核对」。页面侧（SubpageActivity）
    // 有自己的同款驱动，repeatOnLifecycle(RESUMED) 保证同一时刻只有前台那个在跑
    qzxyViewModel?.let { vm ->
        WateringReconcileEffect(viewModel = vm, enabled = qzxyEntryState.watering != null)
    }
    // 免费时长计时的两个值直接读偏好仓库：`DisplayPrefs` 那层合并读模型不带它们，
    // 而快趣卡只是展示（与 RideScreen / RideViewModel 读同一个单例）
    val context = LocalContext.current
    // 趣智登录态：卡片与开水面板都要，直接订阅仓库那条流（理由见 QzxyCard 里的注释）
    val qzxyRepo = remember(context) { Graph.qzxy(context) }
    val qzxyLoggedIn by qzxyRepo.loggedIn.collectAsStateWithLifecycle()
    val ebikePrefs = remember(context) { Graph.displayPrefs(context) }
    val ebikeRideStartAt by ebikePrefs.ebikeRideStartAt
        .collectAsStateWithLifecycle(initialValue = 0L)
    val ebikeFreeReminderEnabled by ebikePrefs.ebikeFreeReminderEnabled
        .collectAsStateWithLifecycle(initialValue = false)
    // 使用方式（DESIGN §3.9 / §4.32）：只影响快趣卡副行的常态文案（两档的开车方式不同），
    // 卡本身与入口不变
    val ebikeUseMode by ebikePrefs.ebikeUseMode
        .collectAsStateWithLifecycle(initialValue = EbikeUseMode.Default)
    // 收件箱未读数（DESIGN §3.24）：驱动「+」图标与「+」菜单行两处角标
    val inboxUnread by remember(context) { Graph.inboxRepository(context) }
        .unreadCount
        .collectAsStateWithLifecycle(initialValue = 0)
    // 宿舍报修进度（DESIGN §4.49）：有在途记录优先，没有则显示 3 天内已完成的
    val dormRepairSnapshot by remember(context) { Graph.dormRepairStore(context) }
        .snapshot
        .collectAsStateWithLifecycle()
    val dormRepair = DormRepairRules.cardRecord(
        snapshot = dormRepairSnapshot,
        now = System.currentTimeMillis(),
    )
    // 百宝箱入口开关（DESIGN §4.47，默认开）：关掉时「+」菜单里整行不出现
    val toolboxEnabled by remember(context) { Graph.displayPrefs(context) }
        .toolboxEnabled
        .collectAsStateWithLifecycle(initialValue = true)
    var showWaterEntrySheet by remember { mutableStateOf(false) }
    // 趣智校园的余额面板（DESIGN §3.18）：与开水面板同形态，入口也是卡片右侧的余额
    var showQzxyEntrySheet by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Course?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var detailCourse by remember { mutableStateOf<Course?>(null) }
    var pendingDelete by remember { mutableStateOf<Course?>(null) }
    val snackbar = remember { SnackbarHostState() }

    val scope = rememberCoroutineScope()

    // 江水生活图标入口（2026-10-19 自生活页迁入）：取快递 / U净 各有弹层。
    // U净 在案订单：打开弹窗时对一次服务端（同生活页原口径，节流窗口内不重复请求）
    var showShortcutSheet by remember { mutableStateOf(false) }
    var showUjingSheet by remember { mutableStateOf(false) }
    val shortcutSettings by remember(context) { Graph.repository(context).shortcutSettings }
        .collectAsStateWithLifecycle(initialValue = ShortcutSettings())
    val ujingRepo = remember(context) { Graph.ujing(context) }
    var ujingOrderRefreshedAt by remember { mutableStateOf(0L) }
    val refreshUjingOrderIfStale: () -> Unit = {
        val id = ujingRepo.currentOrder()?.orderId
        val now = System.currentTimeMillis()
        if (id != null && ujingRepo.loggedIn.value &&
            now - ujingOrderRefreshedAt > UJING_ORDER_REFRESH_TTL_MILLIS
        ) {
            ujingOrderRefreshedAt = now
            scope.launch { runCatching { ujingRepo.refreshOrder(id) } }
        }
    }

    // 教务导入成功的气泡（DESIGN §3.3）：今日页空态的「从教务导入」也是入口，落点就是这一页
    JwImportOutcomeEffect(snackbar)

    // 「还剩 X 分钟」要跟着时间走；RESUMED 才 tick——退后台/灭屏不再每 30 秒唤醒重组
    ResumedTickerEffect(intervalMillis = 30_000L) { viewModel.refreshTick() }

    // 余额面板/开水页发起解锁与查询的结果事件得有人收——此前只有开水页收，
    // 今日页侧的超时/失效提示会全部静默丢弃（Channel 无人消费即无处可去）。
    // 今日页与开水二级页各持一份 WaterViewModel（两个 Activity，登录态/订单走仓库共享），
    // 所以两边各收自己那份的事件，不会重复消费。
    LaunchedEffect(waterViewModel) {
        waterViewModel?.events?.collect { event ->
            when (event) {
                // 事件流用 postNotice 单开协程发：收集协程不卡在「等这条提示收场」上，
                // 事件成串来时新的提示才能当场顶掉旧的（见 AppNotice.kt）
                is WaterEvent.Notice -> scope.postNotice(snackbar, event.text, event.tone)
            }
        }
    }

    // 单条结果提示（钉桌面失败等）：与快捷方式拉起失败共用同一条 Snackbar 队列，
    // 后到的消息自动排队，不会互相顶掉
    val showNotice: (String, NoticeTone) -> Unit = { message, tone ->
        scope.launch { snackbar.showNotice(AppNoticeVisuals(message, tone = tone)) }
    }

    // ── 顶栏「+ → 扫一扫 / 付款码」（DESIGN §3.3，2026-10-02）──
    // 扫一扫一枚入口管三种去处：车身码进骑行页、认得准的别家码就地提示、其余给 U净
    // （三档判据都在 domain/QuickScan，页里不复述）。取景窗口走**原文模式**（MODE_RAW）：
    // 车号的解析归本地判据、机身码原文归 U净 服务端，本地只负责「去哪一页」这一件事。
    var quickMenuOpen by remember { mutableStateOf(false) }
    val quickHaptics = rememberAppHaptics()
    val quickScanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val raw = result.data?.getStringExtra(Intents.Scan.RESULT)
        when (val target = quickScanTargetOf(raw)) {
            // 空内容 = 取消（没扫到就退出取景页），静默处理，别把放弃扫码报成失败
            null -> Unit
            is QuickScanTarget.Ebike -> {
                quickHaptics.tap()
                onOpenRideCar(target.carNum)
            }
            is QuickScanTarget.Washer -> {
                quickHaptics.tap()
                onOpenUjingScan(target.raw)
            }
            // 认得准的别家码：就地说明，不跳页（送到 U净 也只会回「未识别到设备信息」）
            is QuickScanTarget.Foreign -> showNotice(
                "这是${target.label}的码，不是快趣车身码或洗衣机机身码",
                NoticeTone.Warning,
            )
        }
    }
    val startQuickScan: () -> Unit = {
        quickHaptics.tap()
        quickScanLauncher.launch(
            Intent(context, EbikeScanActivity::class.java).apply {
                putExtra(Intents.Scan.PROMPT_MESSAGE, "对准车身或洗衣机机身上的二维码")
                putExtra(EbikeScanActivity.EXTRA_RESULT_MODE, EbikeScanActivity.MODE_RAW)
                // 车身码与机身码都是二维码：限定格式，省得商品一维码也当码送出去。
                // 键走 Intents.Scan.FORMATS 的**逗号分隔字符串**（DecodeFormatManager 只读
                // StringExtra 这一种形态，别改传 ArrayList，那样等于没设）
                putExtra(Intents.Scan.FORMATS, BarcodeFormat.QR_CODE.name)
            },
        )
    }

    // 江水生活 U净 弹窗的扫码按钮：与上方「+ → 扫一扫」同一套判据，
    // 洗衣机机身码原文交给 U净 页识别；车身码 / 别家码就地提示，不跳页。
    val ujingScanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val raw = result.data?.getStringExtra(Intents.Scan.RESULT)
        when (val target = quickScanTargetOf(raw)) {
            null -> Unit
            is QuickScanTarget.Washer -> {
                showUjingSheet = false
                quickHaptics.tap()
                onOpenUjingScan(target.raw)
            }
            is QuickScanTarget.Ebike -> showNotice("这是快趣车身码，不是洗衣机机身码", NoticeTone.Warning)
            is QuickScanTarget.Foreign -> showNotice(
                "这是${target.label}的码，不是洗衣机机身码",
                NoticeTone.Warning,
            )
        }
    }
    val startUjingScan: () -> Unit = {
        quickHaptics.tap()
        ujingScanLauncher.launch(
            Intent(context, EbikeScanActivity::class.java).apply {
                putExtra(Intents.Scan.PROMPT_MESSAGE, "对准洗衣机机身上的二维码")
                putExtra(EbikeScanActivity.EXTRA_RESULT_MODE, EbikeScanActivity.MODE_RAW)
                // 机身码是二维码：限定格式，别让商品一维码也当码送出去（同上方扫一扫）
                putExtra(Intents.Scan.FORMATS, BarcodeFormat.QR_CODE.name)
            },
        )
    }

    // 趣智校园开阀要连蓝牙，权限在今日页也要能就地补——缺权限时面板里点开始用水
    // 直接拉起系统授权，授完接着开阀，不把用户赶回趣智校园页
    val qzxyPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.all { granted -> granted }) {
            qzxyViewModel?.openValveFromCard()
        } else {
            showNotice("缺少「附近的设备」权限，连不上热水器", NoticeTone.Warning)
        }
    }
    val startQzxyValve: () -> Unit = {
        val missing = AppPermissions.missingBluetoothScan(context)
        if (missing.isEmpty()) {
            qzxyViewModel?.openValveFromCard()
        } else {
            qzxyPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    // 撤销型反馈（DESIGN §3.3）：删除课程后给「撤销」
    val undoable by viewModel.undoable.collectAsStateWithLifecycle()
    LaunchedEffect(undoable) {
        undoable?.let { m ->
            val result = snackbar.showNotice(m.text, actionLabel = "撤销", duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) m.undo()
            viewModel.consumeUndoable()
        }
    }

    Scaffold(
        // 顶部 inset 由外层消费（防顶栏双倍空白）；底部导航栏 inset 已由外层底栏
        // 高度提供，内层 contentWindowInsets 归零防底部双倍空白。
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                // 顶部 inset 自取（DESIGN §4.22）：外层 JuwApp Scaffold 的 contentWindowInsets 已归零，
                // 不再垫状态栏高度——课表页要把背景图铺到状态栏，顶部就只能由各页自己让位。
                // 这里取 statusBars 后顶栏总高与改动前一致，不会出现双倍空白。
                // 用钉住版：今日页的快捷方式会拉起外部应用，过渡期间系统栏瞬时归零的
                // insets 不落地（2026-09-26 修「顶栏跳动」，见 pinnedStatusBars 的 KDoc）。
                windowInsets = pinnedStatusBars(),
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(
                            imageVector = HugeIcons.Menu01,
                            contentDescription = "打开生活侧栏",
                        )
                    }
                },
                title = {
                    Column {
                        Text(stringResource(R.string.tab_today))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = buildString {
                                    // 日期用 M/d 短式（2026-10-20）：同一行还要放提示句，
                                    // 「10月20日」在 360dp 上会先被挤掉周次
                                    append("${state.date.monthValue}/${state.date.dayOfMonth}")
                                    append(" 周${dayLabel(state.day)}")
                                    if (state.week > 0) append(" · 第 ${state.week} 周")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                // 日期**恒为内容宽、不参与让位**（2026-10-20 反转了 2026-10-12 的口径）：
                                // 早先提示行先拿满整行、日期只分剩余宽度，结果是日期被压成「10月…」，
                                // 而 24 字的提示自己仍然截断——两头都没救。日期是定位信息，
                                // 提示是锦上添花；现在提示拿剩余宽度、超了省略，日期始终可读。
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            // 天气 / 课表提示（DESIGN §3.29）：**接在次标题同一行的后面**，
                            // emoji + 一句话（18:00 前讲今天、之后讲明天）。
                            // 天气句点它进天气详情页，课表句进课表页（2026-10-20 按 kind 分流）。
                            //
                            // 为什么不做成顶栏里的第三行：M3 的 TopAppBar 高度是固定的 64dp，
                            // 标题区塞三行、用户又关掉「固定字体大小」时会被裁掉底边
                            // （现有标题两行合计 44sp，加上第三行 16sp 已到 60sp，没有余量）。
                            // 同一行渲染则任何字号下都只是省略号收尾，不会裁字。
                            //
                            // 2026-10-12 加动画：轮换时**交叉淡入淡出**（不硬切）；首次出现
                            // （打开软件 / 数据刚到）额外加一点上浮，避免突然蹦出来。
                            // 空态（功能关着 / 取不到数据）渲染零尺寸占位，不占位也不闪。
                            AnimatedContent(
                                targetState = weatherLine,
                                transitionSpec = {
                                    if (initialState == null) {
                                        // 入场：淡入 + 轻微上浮
                                        (fadeIn(tween(400)) +
                                            slideInVertically(tween(400)) { it / 3 }) togetherWith
                                            fadeOut(tween(150))
                                    } else {
                                        // 轮换：交叉淡入淡出
                                        fadeIn(tween(400)) togetherWith fadeOut(tween(400))
                                    }
                                },
                                contentAlignment = Alignment.CenterStart,
                                // 只拿日期之外剩下的宽度（日期先量），超了省略
                                modifier = Modifier.weight(1f),
                                label = "todayWeatherTip",
                            ) { line ->
                                if (line == null) {
                                    // 首帧 / 无数据：零尺寸占位
                                    Box(Modifier)
                                } else {
                                    Text(
                                        text = line.text,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable {
                                                quickHaptics.tap()
                                                when (line.kind) {
                                                    TodayTip.Kind.Weather -> onOpenWeather()
                                                    TodayTip.Kind.Schedule -> onOpenWeek()
                                                }
                                            }
                                            // 竖向 4dp 是把命中区从一行字（约 14dp）撑到约 22dp，
                                            // 标题区三行合计 62dp 仍在 TopAppBar 的 64dp 之内；
                                            // 再加大就会顶出顶栏被裁，别动这个数
                                            .padding(start = 6.dp, top = 4.dp, bottom = 4.dp),
                                    )
                                }
                            }
                        }
                    }
                },
                // 「+」快捷入口（2026-10-02，2026-10-06 加收件箱）：扫一扫（车码 / 机身码
                // 自动分流）+ 付款码 + 收件箱。与微信首页右上角同一形态：一枚图标 + 下拉，
                // 不做页内常驻按钮——低频动作不跟底部固定区的卡片抢位置。
                // 有未读时「+」图标右上叠一枚红点（应用内 UI 角标，不接桌面 launcher 角标）。
                actions = {
                    Box {
                        IconButton(
                            onClick = {
                                quickHaptics.tap()
                                quickMenuOpen = true
                            },
                        ) {
                            BadgedBox(badge = { if (inboxUnread > 0) Badge() }) {
                                Icon(HugeIcons.Add01, contentDescription = "快捷入口")
                            }
                        }
                        DropdownMenu(
                            expanded = quickMenuOpen,
                            onDismissRequest = { quickMenuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("扫一扫") },
                                leadingIcon = {
                                    Icon(HugeIcons.QrCodeScan, contentDescription = null)
                                },
                                onClick = {
                                    quickMenuOpen = false
                                    startQuickScan()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("付款码") },
                                leadingIcon = { Icon(HugeIcons.Wallet01, contentDescription = null) },
                                onClick = {
                                    quickMenuOpen = false
                                    quickHaptics.tap()
                                    onOpenPayCode()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("收件箱") },
                                leadingIcon = { Icon(HugeIcons.Inbox, contentDescription = null) },
                                trailingIcon = {
                                    if (inboxUnread > 0) {
                                        Badge { Text(inboxUnread.coerceAtMost(99).toString()) }
                                    }
                                },
                                onClick = {
                                    quickMenuOpen = false
                                    quickHaptics.tap()
                                    onOpenInbox()
                                },
                            )
                            // 百宝箱（DESIGN §4.47，2026-10-12）：小工具集合
                            // （水印 / 拼接 / 菜鸟批量查）；总开关在 通用设置 → 功能，
                            // 关掉时整行不出现
                            // （「志愿服务」一行 2026-10-13 删除：问渠已进生活页「校园服务」）
                            if (toolboxEnabled) {
                                DropdownMenuItem(
                                    text = { Text("百宝箱") },
                                    leadingIcon = {
                                        Icon(HugeIcons.Toolbox, contentDescription = null)
                                    },
                                    onClick = {
                                        quickMenuOpen = false
                                        quickHaptics.tap()
                                        onOpenToolbox()
                                    },
                                )
                            }
                        }
                    }
                },
                // 一键开水入口不挂顶栏：底部固定区已有开水卡，顶栏图标重复
            )
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        // 下拉刷新（2026-09-23）：并行刷新胖乖余额/设备 + 今日时间状态。
        // 包住三态内容：空态（假期）也要能刷胖乖余额。
        // （2026-09-24：一卡通余额自今日页移除，不再参与刷新聚合。）
        //
        // **指示器驻留**（2026-09-23 修「拉不动」观感）：M3 的 onRefresh 在松手且拉过
        // 阈值后才回调，而 water 的 loading 标志要等 VM 协程跑起来才置 true——
        // 直接聚合的话 isRefreshing 在回调瞬间还是 false，指示器被立刻收回，
        // 体感就是「拉下去又弹回去、啥都没发生」。手动 manualRefreshing 兜住回调瞬间：
        // 触发即置 true，等 loading 归零**且**最短驻留 650ms 后才收——
        // 没得刷（未登录）时也驻留一个完整周期，给「刷新完成」的确定反馈。
        val waterRefreshing = waterEntryState.loadingBalance || waterEntryState.loadingDevices
        var manualRefreshing by remember { mutableStateOf(false) }
        LaunchedEffect(manualRefreshing, waterRefreshing) {
            if (!manualRefreshing) return@LaunchedEffect
            if (waterRefreshing) return@LaunchedEffect
            delay(650)
            if (!waterRefreshing) manualRefreshing = false
        }
        val refreshing = manualRefreshing || waterRefreshing
        val pullRefreshState = rememberPullToRefreshState()
        // 触感由 rememberAppHaptics 统一查开关（DisplayPrefs.hapticsEnabled），关掉时短路，
        // 调用点不用自己判断。onRefresh 只在拉过阈值松手时回调——它响就等于「下拉成功」，
        // 与消费流水页同一个语义（那里也是 tap）。
        val haptics = rememberAppHaptics()
        // 与消费流水页同口径：padding 收在刷新容器上，不再由三态内容各自让位。
        // Scaffold 的 content 从 (0,0) 铺满整屏、TopAppBar 压在它上面，容器不带 padding 时
        // 指示器整条滑入轨迹都在顶栏后面（`pullToRefreshIndicator` 里
        // translationY = fraction × threshold − 自身高度，拉满也只到阈值处，比顶栏矮），
        // 要拖过阈值一大截才露出半圈，读起来就是「拉了半天没反应」——2026-09-23 定位。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .pullToRefresh(
                    isRefreshing = refreshing,
                    state = pullRefreshState,
                    threshold = TodayPullRefreshThreshold,
                    onRefresh = {
                        haptics.tap()
                        manualRefreshing = true
                        viewModel.refreshTick()
                        waterViewModel?.refreshBalance()
                        waterViewModel?.refreshDevices()
                        // 宿舍报修进度（DESIGN §4.49）：下拉时顺带刷新，不阻塞课表刷新
                        scope.launch {
                            runCatching { Graph.dormRepairSync(context).refresh() }
                        }
                    },
                ),
        ) {
            when {
                state.loading -> TodayLoadingContent()

                !state.inTerm -> TodayEmptyContent(
                    "尚未开学或未配置学期",
                    "先在「课表设置」里填好开学日期与周数，也能手动加课。",
                    actionLabel = "去设置学期",
                    onAction = onOpenTimetableSettings,
                    dormRepair = dormRepair,
                    onOpenDormRepair = onOpenDormRepair,
                )

                state.totalCourseCount == 0 -> TodayEmptyContent(
                    "课表为空",
                    "课表默认为空，请登录教务系统导入「学期理论课表」，也可手动加课。",
                    actionLabel = "从教务导入",
                    onAction = onOpenJwImport,
                    dormRepair = dormRepair,
                    onOpenDormRepair = onOpenDormRepair,
                )

                else -> TodayContent(
                    state = state,
                    homework = homework,
                    textbookTitles = textbookTitles,
                    onOpenCourse = { detailCourse = it },
                    onOpenHomeworkTodo = onOpenHomeworkTodo,
                    dormRepair = dormRepair,
                    onOpenDormRepair = onOpenDormRepair,
                )
            }
            PullToRefreshDefaults.Indicator(
                state = pullRefreshState,
                isRefreshing = refreshing,
                modifier = Modifier.align(Alignment.TopCenter),
                threshold = TodayPullRefreshThreshold,
            )
        }
    }

    // 只读详情（与课表页同口径）：编辑/删除是详情里的二级动作；
    // 2026-09-21 起弹窗内还有「笔记·课件 / 作业」两个入口（DESIGN §3.11）
    detailCourse?.let { course ->
        // 教材（DESIGN §4.31）：按当前课表学期 + 课程名查；无学期号的课表给空，不显示
        val detailRepo = remember(context) { Graph.repository(context) }
        val detailTextbooks by remember(course.name) {
            detailRepo.textbooksFor(course.name)
        }.collectAsStateWithLifecycle(initialValue = emptyList())
        CourseDetailSheet(
            course = course,
            slots = state.slots,
            currentWeek = state.week,
            textbooks = detailTextbooks,
            onEdit = {
                detailCourse = null
                editing = course
                editorOpen = true
            },
            onDelete = {
                detailCourse = null
                pendingDelete = course
            },
            onOpenNotes = {
                detailCourse = null
                onOpenCourseNotes(course)
            },
            onOpenHomework = {
                detailCourse = null
                onOpenCourseHomework(course)
            },
            onDismiss = { detailCourse = null },
        )
    }

    if (editorOpen) {
        CourseEditSheet(
            course = editing,
            onDismiss = { editorOpen = false },
            onSave = { c ->
                viewModel.upsert(c)
                editorOpen = false
            },
            onDelete = editing?.let { c ->
                {
                    pendingDelete = c
                    editorOpen = false
                }
            },
        )
    }

    pendingDelete?.let { c ->
        DeleteConfirmDialog(
            courseName = c.name,
            onConfirm = {
                viewModel.deleteCourse(c)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }

    // 开水卡：点余额的开水操作面板（2026-09-23，DESIGN §3.3）——
    // 余额/积分/开水按钮/流程态/结算明细，与开水页共享同一 WaterViewModel
    if (showWaterEntrySheet && waterViewModel != null) {
        edu.jxslu.schedule.ui.water.WaterEntrySheet(
            state = waterEntryState,
            onUnlock = { waterViewModel.unlock() },
            onDismissFlow = { waterViewModel.dismissFlow() },
            onToggleUsePoints = { waterViewModel.toggleUsePoints() },
            onEndSession = { waterViewModel.endSession() },
            onDismiss = { showWaterEntrySheet = false },
        )
    }

    // 趣智校园：点余额的开水面板（DESIGN §3.18）——余额/设备/用水状态/结算，
    // 与趣智校园页共享同一个 QzxyViewModel，面板里开的阀在页面上状态一致
    if (showQzxyEntrySheet && qzxyViewModel != null) {
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
            onOpenDiagnostics = onOpenQzxyDebug,
            onOpenPage = onOpenQzxy,
            onDismiss = { showQzxyEntrySheet = false },
        )
    }

    // 江水生活「取快递」弹窗（2026-10-19 自生活页迁入）：预设归「取快递」段、
    // 自定义归「其他」段；条目动作由弹窗自己播完退场再交给这里执行
    if (showShortcutSheet) {
        ShortcutSheet(
            settings = shortcutSettings,
            onDismiss = { showShortcutSheet = false },
            onLaunchItem = { item ->
                ShortcutLauncher.launch(context, item)?.let { message ->
                    showNotice(message, NoticeTone.Warning)
                }
            },
            onPinItem = { item ->
                ShortcutPinner.pin(context, item)?.let { message ->
                    showNotice(message, NoticeTone.Warning)
                }
            },
            onEdit = { itemId -> onOpenShortcuts(itemId) },
            onAdd = { onOpenShortcuts(null) },
            onOpenCainiao = onOpenCainiao,
        )
    }

    // 江水生活 U净 弹窗（2026-10-19 自生活页迁入）：在案订单状态 + 扫码 + 进页副行
    if (showUjingSheet) {
        UjingEntrySheet(
            onDismiss = { showUjingSheet = false },
            onScan = startUjingScan,
            onOpenPage = onOpenUjing,
        )
    }
}
/**
 * 今日页居中态（加载/空态）共用外壳：`weight(1f)` 居中区 + 贴底固定区。
 * 抽出统一壳是为了防再犯「Box 少 fillMaxWidth 贴左」的错——居中容器只有一处定义。
 */
@Composable
private fun TodayCenteredShell(
    /** 顶部常驻内容（今日页的宿舍报修卡）：空态/未开学时也要在，不能只在有课时出现。 */
    topContent: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    // 顶部/底部的避让由外层刷新容器给（2026-09-23 起），这里不再吃 Scaffold 的 padding
    val clearance = LocalBottomBarClearance.current
    Column(modifier = Modifier.fillMaxSize()) {
        topContent?.invoke()
        // fillMaxWidth 必须带：Box 默认 wrap 内容、crossAxis 左对齐，
        // 少了它整个居中态贴左（旧版加载态左偏的根因）。
        // 底部让开悬浮胶囊：dock 收进侧栏后，居中内容直接贴到窗口底（2026-10-20）
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(bottom = clearance),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

/** 加载态：水滴呼吸居中（DESIGN §3.2），底部固定区照常在位（DESIGN §3.3「加载中不留白屏」）。 */
@Composable
private fun TodayLoadingContent() {
    TodayCenteredShell {
        LoadingHint("正在读取本机课表")
    }
}

/**
 * 空态（未开学 / 课表为空）：居中提示 + 底部固定区。
 * 快捷方式网格与开水卡在空态也上桌（DESIGN §3.3/§3.8）——假期恰是取件码高频时段，
 * 课表为空不等于入口该消失。
 */
@Composable
private fun TodayEmptyContent(
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    dormRepair: DormRepairRecord? = null,
    onOpenDormRepair: () -> Unit = {},
) {
    val top: (@Composable () -> Unit)? = dormRepair?.let { record ->
        @Composable { DormRepairCard(record = record, onClick = onOpenDormRepair) }
    }
    TodayCenteredShell(topContent = top) {
        EmptyHint(title, body, actionLabel, onAction)
    }
}

@Composable
private fun TodayContent(
    state: TodayState,
    homework: PendingHomework,
    textbookTitles: Map<String, List<String>>,
    onOpenCourse: (Course) -> Unit,
    onOpenHomeworkTodo: () -> Unit,
    dormRepair: DormRepairRecord? = null,
    onOpenDormRepair: () -> Unit = {},
) {
    val sectionColumnWidth = rememberSectionColumnWidth(state.timeline)
    // dock 收进侧栏后，列表最后一项要自己让开悬浮胶囊（2026-10-20）
    val clearance = LocalBottomBarClearance.current
    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 16.dp + clearance),
        ) {
            // 宿舍报修进度（DESIGN §4.49）：有在途记录（或 3 天内完成）时置顶，
            // 没有就整卡不出现。报修是短时事件，值得占课程区上方这一格。
            if (dormRepair != null) {
                item(key = "dorm-repair") {
                    DormRepairCard(record = dormRepair, onClick = onOpenDormRepair)
                }
            }
            // 今日课程：一列到底。正在上的课**留在原位**、就地套焦点卡形态；最近一节已上完的
            // 课置灰保留（更早的不出现）——三态切换的动画在 TodayCourseCard 里。
            // 今天没课 / 不在学期 / 全部上完：一行状态卡（DoneBlock）。
            if (state.timeline.isNotEmpty() && !state.todayAllDone) {
                item(key = "courses-header") {
                    // 计数 = 列表里的卡片数（不是当天总节数）：已上完的会收起、正在上的会在位，
                    // 标题数字必须和下面看到的卡数对上（2026-10-13 用户反馈）
                    SectionHeader("今日课程", trailing = "${state.timeline.size} 节课")
                }
                items(state.timeline, key = { it.id }) { course ->
                    val isOngoing = state.ongoing?.id == course.id
                    TodayCourseCard(
                        course = course,
                        slots = state.slots,
                        textbookTitle = textbookTitles[course.name],
                        sectionColumnWidth = sectionColumnWidth,
                        ongoing = isOngoing,
                        isNext = state.ongoing == null && state.next?.id == course.id,
                        done = state.endedRecent?.id == course.id,
                        countdown = if (isOngoing) {
                            state.ongoingCountdown
                                ?: state.minutesToOngoingEnd?.let { "还有 $it 分钟下课" }
                        } else {
                            state.minutesToNext?.let { "还有 $it 分钟上课" }
                        },
                        progress = if (isOngoing) state.ongoingProgress else null,
                        onClick = { onOpenCourse(course) },
                        // 删除/新增课程时列表项平滑进出场，不再整列硬跳
                        modifier = Modifier.animateItem(),
                    )
                }
            } else {
                item(key = "done") { DoneBlock(state) }
            }

            // 明天只在今天没有待上课程（上完 / 没课）时上桌，今天的信息优先
            if (state.tomorrowVisible) {
                item(key = "tomorrow") { TomorrowBlock(state, textbookTitles, onOpenCourse) }
            }

            // 作业卡（DESIGN §3.3/§3.11）：有未完成作业才占位，位置 = **滚动区最后一项**
            // （「今天还有 N 节」与「明天」块之后）、贴底固定区（快捷方式网格）之上——
            // 2026-09-21 用户拍板两次：放焦点卡之下会把当天课程整段下推；定在明天课表之下，
            // 作业是"顺带看一眼"的信息，不抢课表的位置。
            if (!homework.isEmpty) {
                item(key = "homework") {
                    HomeworkTodayCard(
                        pending = homework,
                        today = state.date,
                        onClick = onOpenHomeworkTodo,
                    )
                }
            }
        }

        // 底部固定区（DESIGN §3.3）：恒贴底，不随上面的课表滚动
    }
}

/** 左列节次标签右侧的呼吸位（在实测最宽标签之外再加这一点，避免文字贴住竖分隔）。 */
private val SectionColumnTrailingGap = 8.dp

/**
 * 左列（节次 + 时间）宽度：按这一批课里**最宽的节次标签**实测取齐，再留一点呼吸位。
 *
 * 为什么不写死一个值：写死时短标签（如「3-4 节」）右侧会空出一大截，看着像排版坏了
 * （2026-10-13 真机反馈「节数右侧留有好大的空白」）。取齐最宽标签既保证各卡竖分隔对齐，
 * 又不留多余空白。实测走 `TextMeasurer`，跟随系统字体缩放。
 */
@Composable
private fun rememberSectionColumnWidth(courses: List<Course>): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
    return remember(courses, style, density) {
        val maxPx = courses.maxOfOrNull {
            measurer.measure(sectionShortLabel(it), style).size.width
        } ?: 0
        with(density) { maxPx.toDp() } + SectionColumnTrailingGap
    }
}

/**
 * 今日页统一课程卡（2026-10-13）：一列到底，三种形态**就地**切换。
 *
 * - **未上**：课程色 16% 底 + 左列节次/时间 + 竖分隔 + 课名（+ 教材）/ 地点·教师（+ 备注）
 * - **正在上 / 下一节**：现版焦点卡形态——主色 8% 底 + 左缘主色竖条 + 状态行（含倒计时）
 *   + 进度条（仅正在上）；左列节次/时间收起（宽度与透明度过渡）
 * - **已上完**：整卡置灰 + 右端「已下课」
 *
 * 形态切换全部走 animate*（底色 / 左缘竖条 / 左列宽度 / 展开区高度），焦点在课程间
 * 转移时不硬跳。点卡片弹只读详情（编辑/删除是详情里的二级动作，与课表页同口径）。
 */
@Composable
private fun TodayCourseCard(
    course: Course,
    slots: List<TimeSlot>,
    textbookTitle: List<String>?,
    /** 左列（节次 + 时间）宽度：由 [rememberSectionColumnWidth] 按当天最宽的节次标签实测取齐 */
    sectionColumnWidth: Dp,
    ongoing: Boolean,
    isNext: Boolean,
    done: Boolean,
    countdown: String?,
    progress: Float?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val primary = MaterialTheme.colorScheme.primary
    val accent = courseColor(course.colorIndex)
    val focused = ongoing || isNext
    val haptics = rememberAppHaptics()

    val background by animateColorAsState(
        targetValue = when {
            focused -> primary.copy(alpha = 0.08f)
            done -> onSurface.copy(alpha = 0.04f)
            else -> accent.copy(alpha = 0.16f)
        },
        animationSpec = tween(340),
        label = "todayCourseBg",
    )
    // 已上完：整卡内容淡出（底色另算）
    val faded by animateFloatAsState(
        targetValue = if (done) 0.55f else 1f,
        animationSpec = tween(340),
        label = "todayCourseFaded",
    )
    // 左缘主色竖条：仅焦点态显示，走透明度过渡（避免切换时竖条突然出现 / 消失）
    val barAlpha by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(340),
        label = "todayCourseBar",
    )
    // 左列（节次 + 时间）：焦点态宽度收成 0、内容淡出。宽度按实测的最宽节次标签取齐，
    // 不写死——写死时「3-4 节」右侧会空出一大截（2026-10-13 真机反馈）
    val leftWidth by animateDpAsState(
        targetValue = if (focused) 0.dp else sectionColumnWidth,
        animationSpec = tween(340),
        label = "todayCourseLeftWidth",
    )
    val leftAlpha by animateFloatAsState(
        targetValue = if (focused) 0f else 1f,
        animationSpec = tween(220),
        label = "todayCourseLeftAlpha",
    )
    val textbook = textbookTitlesLine(textbookTitle.orEmpty())
    val meta = placeTeacherLine(course)
    val remark = remarkPreview(course.remark)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(AppCardDefaults.Shape)
            .background(background)
            // 左缘主色竖条用绘制而不是子节点：无界高度下 fillMaxHeight 是 no-op，
            // 而 IntrinsicSize.Min 与 AnimatedVisibility 的固有尺寸测量不兼容，故画出来。
            .drawBehind {
                if (barAlpha > 0f) {
                    val inset = 11.dp.toPx()
                    drawRect(
                        color = primary.copy(alpha = barAlpha),
                        topLeft = Offset(0f, inset),
                        size = Size(3.dp.toPx(), (size.height - inset * 2).coerceAtLeast(0f)),
                    )
                }
            }
            .clickable(onClickLabel = "查看课程") {
                haptics.tap()
                onClick()
            }
            // 卡内统一内缩（与 AppCardDefaults.Padding 同档）：左列节次/时间与课名都从这里起，
            // 不再让节次贴住卡片左缘（2026-10-13 真机反馈「节数和课程都偏左」）
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左列：节次 + 起始时刻。焦点态宽度收成 0（外框裁剪，内容不压缩）
        Box(
            modifier = Modifier
                .width(leftWidth)
                .clipToBounds(),
        ) {
            Column(
                modifier = Modifier
                    .width(sectionColumnWidth)
                    .alpha(leftAlpha),
            ) {
                Text(
                    text = sectionShortLabel(course),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = onSurface,
                    maxLines = 1,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = clockOf(slots, course),
                    style = MaterialTheme.typography.labelSmall,
                    color = onSurface.copy(alpha = 0.5f),
                    maxLines = 1,
                )
            }
        }
        // 竖分隔：焦点态淡出并让位
        if (leftWidth > 0.dp) {
            Box(
                Modifier
                    .width(1.dp)
                    .height(38.dp)
                    .alpha(leftAlpha)
                    .background(onSurface.copy(alpha = 0.12f)),
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(
            Modifier
                .weight(1f)
                .alpha(faded),
        ) {
            // 状态行（仅焦点态）：正在上课 / 下一节 + 倒计时
            AnimatedVisibility(
                visible = focused,
                enter = expandVertically(tween(300)) + fadeIn(tween(300)),
                exit = shrinkVertically(tween(260)) + fadeOut(tween(160)),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = (if (ongoing) "正在上课" else "下一节") + " · " + sectionRange(course),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = primary,
                    )
                    if (countdown != null) {
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = countdown,
                            style = MaterialTheme.typography.labelMedium,
                            color = primary,
                            textAlign = TextAlign.End,
                            maxLines = 1,
                        )
                    }
                }
            }
            // 课名 + 教材名（DESIGN §4.31）：教材名挂在课名**右边**同一行，不再另起一行抬卡高。
            // 课名先量（优先保完整、最长两行），教材名吃余量、不够省略号
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = course.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = onSurface,
                )
                if (textbook != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = textbook,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = onSurface.copy(alpha = 0.55f),
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
            // meta 行（地点·教师）+ 备注预览（贴卡片右下角）；左列已单列时刻，这里不再重复时间段
            if (meta.isNotBlank() || remark != null) {
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (meta.isNotBlank()) {
                        Text(
                            text = meta,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = onSurface.copy(alpha = 0.68f),
                        )
                    }
                    if (remark != null) {
                        Text(
                            text = remark,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = onSurface.copy(alpha = 0.45f),
                            textAlign = TextAlign.End,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 8.dp),
                        )
                    }
                }
            }
            // 进度条（仅正在上）
            AnimatedVisibility(
                visible = ongoing && progress != null,
                enter = expandVertically(tween(300)) + fadeIn(tween(300)),
                exit = shrinkVertically(tween(260)) + fadeOut(tween(160)),
            ) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    ProgressBar(progress ?: 0f)
                }
            }
        }
        // 「已下课」标签（仅已上完）
        if (done) {
            Text(
                text = "已下课",
                style = MaterialTheme.typography.labelSmall,
                color = onSurface.copy(alpha = 0.4f),
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .padding(start = 8.dp),
            )
        }
    }
}

/** 自绘进度条：不引 M3 的 LinearProgressIndicator，免去两端圆角/端点圆点的版本差异。 */
@Composable
private fun ProgressBar(progress: Float) {
    val primary = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(primary.copy(alpha = 0.18f)),
    ) {
        val fraction = progress.coerceIn(0f, 1f)
        if (fraction > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(primary),
            )
        }
    }
}

/**
 * 今天结束后的落点：上完课 / 本来就没课。
 *
 * 2026-09-22 由「居中两行裸文字」改卡片：居中块与下方课程卡的左缘互不相干，
 * 页面顶部看着像一句留言而不是一屏内容。现在与课程行同宽同左缘，图标交代语义。
 * 2026-10-20 收成单行：标题 + 右侧「共 N 节」，不再占两行高度。
 */
@Composable
private fun DoneBlock(state: TodayState) {
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    AppCardRow(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Icon(
            imageVector = if (state.todayAllDone) {
                HugeIcons.CheckmarkCircle02
            } else {
                HugeIcons.CalendarOff
            },
            contentDescription = null,
            tint = primary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = if (state.todayAllDone) "今天的课都上完了" else "今天没有课",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (state.todayAllDone) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = "共 ${state.todayTotal} 节",
                style = MaterialTheme.typography.labelMedium,
                color = onSurface.copy(alpha = 0.55f),
                maxLines = 1,
            )
        }
    }
}

/**
 * 明日预告。只在 [TodayState.tomorrowVisible]（今天已无待上课程）时渲染，
 * 用与今天相同的行组件，保证「明天也是课表」而不是另一套排版。
 */
@Composable
private fun TomorrowBlock(
    state: TodayState,
    textbookTitles: Map<String, List<String>>,
    onOpenCourse: (Course) -> Unit,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val sectionColumnWidth = rememberSectionColumnWidth(state.tomorrowCourses)

    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(
            title = "明天 · 周${dayLabel(state.tomorrowDay)}",
            trailing = if (state.tomorrowCourses.isEmpty()) {
                "没有课"
            } else {
                "${state.tomorrowCourses.size} 节"
            },
        )
        if (state.tomorrowCourses.isEmpty()) {
            // 标题行已交代「没有课」，这里只补一句收尾，不再复述一遍
            Text(
                text = "可以放松一下",
                style = MaterialTheme.typography.bodySmall,
                color = onSurface.copy(alpha = 0.55f),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            return@Column
        }
        // 课程行自带 vertical 4dp 外间距，这里不再叠一层。明天用与今天同一张卡
        // （非焦点、非已上完形态）：左列节次/时间 + 课名 + 地点·教师
        state.tomorrowCourses.forEach { course ->
            TodayCourseCard(
                course = course,
                slots = state.slots,
                textbookTitle = textbookTitles[course.name],
                sectionColumnWidth = sectionColumnWidth,
                ongoing = false,
                isNext = false,
                done = false,
                countdown = null,
                progress = null,
                onClick = { onOpenCourse(course) },
            )
        }
    }
}

// monthDayFmt 已收拢为 domain/TodayFormat.kt 的 MONTH_DAY_FORMAT（与调课页共用）
/**
 * 今日页下拉刷新阈值（2026-09-23）。M3 默认 `PullToRefreshDefaults.PositionalThreshold` 是 80dp，
 * 而 `PullToRefreshModifierNode` 里计入拖动的只有实际位移的一半
 * （`adjustedDistancePulled = distancePulled * DragMultiplier`，`DragMultiplier = 0.5f`），
 * 也就是真拖 160dp 才触发。今日页顶部可拖空间比消费流水页的列表短，收到 56dp（真拖 112dp）。
 * modifier 的 threshold 与 `PullToRefreshDefaults.Indicator` 的 threshold **必须同值**：
 * 前者决定松手触发点，后者决定图标滑出的落位，写岔了会出现「图标到位了却没刷新」。
 */
private val TodayPullRefreshThreshold = 56.dp
