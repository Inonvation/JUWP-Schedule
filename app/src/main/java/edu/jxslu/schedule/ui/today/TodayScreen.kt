package edu.jxslu.schedule.ui.today

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.R
import edu.jxslu.schedule.domain.Course
import edu.jxslu.schedule.domain.ShortcutItem
import edu.jxslu.schedule.domain.ShortcutSettings
import edu.jxslu.schedule.domain.TodayState
import edu.jxslu.schedule.domain.clockOf
import edu.jxslu.schedule.domain.dayLabel
import edu.jxslu.schedule.domain.metaLine
import edu.jxslu.schedule.domain.sectionRange
import edu.jxslu.schedule.domain.TimeSlot
import edu.jxslu.schedule.domain.UnlockFlowState
import edu.jxslu.schedule.domain.calculateActualCost
import edu.jxslu.schedule.ui.common.AppCardRow
import edu.jxslu.schedule.ui.common.AppCardDefaults
import edu.jxslu.schedule.ui.common.AppNoticeVisuals
import edu.jxslu.schedule.ui.common.AppCard
import edu.jxslu.schedule.ui.common.LocalBottomBarClearance
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.CourseDetailSheet
import edu.jxslu.schedule.ui.common.CourseEditSheet
import edu.jxslu.schedule.ui.common.DeleteConfirmDialog
import edu.jxslu.schedule.ui.common.EmptyHint
import edu.jxslu.schedule.ui.common.LoadingHint
import edu.jxslu.schedule.ui.common.NoticeTone
import edu.jxslu.schedule.ui.common.SectionHeader
import edu.jxslu.schedule.ui.common.ShortcutIcon
import edu.jxslu.schedule.ui.common.ShortcutLauncher
import edu.jxslu.schedule.ui.common.ShortcutPinner
import edu.jxslu.schedule.ui.common.courseColor
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import edu.jxslu.schedule.ui.water.WaterEvent
import edu.jxslu.schedule.ui.water.WaterUiState
import edu.jxslu.schedule.ui.water.WaterViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.CalendarOff
import me.rerere.hugeicons.stroke.CheckmarkCircle02
import me.rerere.hugeicons.stroke.ChevronDown
import me.rerere.hugeicons.stroke.ChevronUp
import me.rerere.hugeicons.stroke.Clock01
import me.rerere.hugeicons.stroke.Droplet
import me.rerere.hugeicons.stroke.Edit02
import me.rerere.hugeicons.stroke.Link01
import me.rerere.hugeicons.stroke.ScooterElectric
import edu.jxslu.schedule.domain.MONTH_DAY_FORMAT
import edu.jxslu.schedule.domain.PendingHomework
import edu.jxslu.schedule.ui.homework.HomeworkTodayCard

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
 * 底部固定区（DESIGN §3.3）：快捷方式三列图标网格（§3.8）在上、快趣出行码整行卡居中、
 * 一键开水卡在最底，用 [TodayBottomDock]**钉在滚动区下方**——此前它们是 LazyColumn 的
 * 最后两项，课少时悬在屏幕中段、课多时要滑到底才看得见，同一个「固定区」在空态（贴底）
 * 与有课态（跟滚）之间还是两种表现。三态共用同一个 dock，位置不随状态漂移。
 * 开水卡默认常显（未登录给未登录态，显示设置可关）。
 * 水宝宝一卡通卡已于 2026-09-24 自今日页移除（生活页承接，DESIGN §3.13）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    onOpenJwImport: () -> Unit = {},
    /** 「尚未开学」空态的 CTA：跳课表设置（学期起止） */
    onOpenTimetableSettings: () -> Unit = {},
    onOpenWater: () -> Unit = {},
    /** 共享单车出码页（DESIGN §3.9，SubpageActivity 独立窗口） */
    onOpenEbike: () -> Unit = {},
    /** 附近单车地图页（DESIGN §3.9）：快趣出行码卡右侧入口直达 */
    onOpenEbikeMap: () -> Unit = {},
    /** 快捷方式设置页（长按图标进；null=不定位，非 null=打开后直接编辑该条目，DESIGN §3.8） */
    onOpenShortcuts: (String?) -> Unit = {},
    /** 作业中心（今日页作业卡入口，DESIGN §3.11） */
    onOpenHomeworkTodo: () -> Unit = {},
    /** 某课程的笔记·课件（课程详情弹窗入口，DESIGN §3.11） */
    onOpenCourseNotes: (Course) -> Unit = {},
    /** 某课程的作业（课程详情弹窗入口，DESIGN §3.11） */
    onOpenCourseHomework: (Course) -> Unit = {},
    /** 与开水页共享的 Activity 作用域实例；开水卡的解锁进度与登录态两页一致 */
    waterViewModel: WaterViewModel? = null,
    viewModel: TodayViewModel = viewModel(
        factory = TodayViewModel.Factory(
            Graph.repository(LocalContext.current),
            Graph.homeworkRepository(LocalContext.current),
        ),
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // 开水面板（WaterEntrySheet）的实时状态：弹窗打开时逐帧跟随流程态，
    // 不能用打开瞬间的快照（Working 计时不会动）
    val waterEntryState by waterViewModel?.uiState
        ?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf(WaterUiState()) }
    val homework by viewModel.homeworkPending.collectAsStateWithLifecycle()
    val shortcuts by viewModel.shortcuts.collectAsStateWithLifecycle()
    val waterCardEnabled by viewModel.waterCardEnabled.collectAsStateWithLifecycle()
    val ebikeCardEnabled by viewModel.ebikeCardEnabled.collectAsStateWithLifecycle()
    val dockExpanded by viewModel.todayDockExpanded.collectAsStateWithLifecycle()
    var showWaterEntrySheet by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Course?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var detailCourse by remember { mutableStateOf<Course?>(null) }
    var pendingDelete by remember { mutableStateOf<Course?>(null) }
    val snackbar = remember { SnackbarHostState() }

    // 快捷方式拉起失败的兜底通道（DESIGN §3.8）：Snackbar 带「去设置」动作，
    // 比纯 Toast 多一步「就地修正配置」的出口（菜鸟 Activity 改名这类配置失效场景）；
    // 动作携带失败条目 id，设置页打开后直接展开它的编辑弹层
    val scope = rememberCoroutineScope()
    val showShortcutError: (String, String?) -> Unit = { message, itemId ->
        scope.launch {
            val result = snackbar.showSnackbar(
                message,
                actionLabel = "去设置",
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) onOpenShortcuts(itemId)
        }
    }

    // 「还剩 X 分钟」要跟着时间走
    LaunchedEffect(Unit) {
        while (true) {
            viewModel.refreshTick()
            delay(30_000)
        }
    }

    // 余额面板/开水页发起解锁与查询的结果事件得有人收——此前只有开水页收，
    // 今日页侧的超时/失效提示会全部静默丢弃（Channel 无人消费即无处可去）。
    // 今日页与开水二级页各持一份 WaterViewModel（两个 Activity，登录态/订单走仓库共享），
    // 所以两边各收自己那份的事件，不会重复消费。
    LaunchedEffect(waterViewModel) {
        waterViewModel?.events?.collect { event ->
            when (event) {
                is WaterEvent.Notice -> snackbar.showSnackbar(
                    AppNoticeVisuals(event.text, tone = event.tone),
                )
            }
        }
    }

    // 单条结果提示（钉桌面失败等）：与快捷方式拉起失败共用同一条 Snackbar 队列，
    // 后到的消息自动排队，不会互相顶掉
    val showNotice: (String, NoticeTone) -> Unit = { message, tone ->
        scope.launch { snackbar.showSnackbar(AppNoticeVisuals(message, tone = tone)) }
    }

    // 撤销型反馈（DESIGN §3.3）：删除课程后给「撤销」
    val undoable by viewModel.undoable.collectAsStateWithLifecycle()
    LaunchedEffect(undoable) {
        undoable?.let { m ->
            val result = snackbar.showSnackbar(m.text, actionLabel = "撤销", duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) m.undo()
            viewModel.consumeUndoable()
        }
    }

    // 底部固定区（DESIGN §3.3）三态共用：同一份组合函数喂给加载中/空态/有课态，
    // 「快捷方式在上、开水卡最底」的顺序与贴底位置只有一处定义
    val bottomDock: @Composable () -> Unit = {
        TodayBottomDock(
            dockExpanded = dockExpanded,
            shortcuts = shortcuts,
            onToggleDock = { viewModel.setTodayDockExpanded(dockExpanded != true) },
            onOpenShortcuts = onOpenShortcuts,
            onShortcutError = showShortcutError,
            onNotice = showNotice,
            // 共享单车整行卡（DESIGN §3.9，2026-09-24 起，开关关 = 整卡不占位）：
            // 点卡片其余位置进出码页，右侧「附近单车 ›」直达地图页
            ebikeCard = if (ebikeCardEnabled) {
                { EbikeCard(onOpen = onOpenEbike, onOpenMap = onOpenEbikeMap) }
            } else {
                null
            },
            waterCard = if (waterCardEnabled && waterViewModel != null) {
                { WaterCard(waterViewModel, onOpenWater) { showWaterEntrySheet = true } }
            } else {
                null
            },
        )
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
                windowInsets = WindowInsets.statusBars,
                title = {
                    Column {
                        Text(stringResource(R.string.tab_today))
                        Text(
                            text = buildString {
                                append(state.date.format(MONTH_DAY_FORMAT))
                                append(" 周${dayLabel(state.day)}")
                                if (state.week > 0) append(" · 第 ${state.week} 周")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
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
                    },
                ),
        ) {
            when {
                state.loading -> TodayLoadingContent(bottomDock)

                !state.inTerm -> TodayEmptyContent(
                    "尚未开学或未配置学期",
                    "先在「课表设置」里填好开学日期与周数，也能手动加课。",
                    actionLabel = "去设置学期",
                    onAction = onOpenTimetableSettings,
                    bottomDock = bottomDock,
                )

                state.totalCourseCount == 0 -> TodayEmptyContent(
                    "课表为空",
                    "课表默认为空，请登录教务系统导入「学期理论课表」，也可手动加课。",
                    actionLabel = "从教务导入",
                    onAction = onOpenJwImport,
                    bottomDock = bottomDock,
                )

                else -> TodayContent(
                    state = state,
                    homework = homework,
                    onOpenCourse = { detailCourse = it },
                    onOpenHomeworkTodo = onOpenHomeworkTodo,
                    bottomDock = bottomDock,
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
        CourseDetailSheet(
            course = course,
            slots = state.slots,
            currentWeek = state.week,
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
            onDismiss = { showWaterEntrySheet = false },
        )
    }
}

/**
 * 底部固定区（DESIGN §3.3）：**钉在滚动区下方**，不随课表滚动。
 *
 * 结构（2026-09-24 起）：快捷方式三列图标网格（§3.8）在上 → **快趣出行码整行卡**
 * （§3.9，右侧「附近单车 ›」直达地图）→ 一键开水卡恒在最底。三态（加载中/空态/有课态）
 * 共用本组件，位置不随状态漂移；全部关掉时只剩一个空 Column，高度为 0。
 *
 * **服务格并排的取消**（2026-09-24）：此前快趣出行与水宝宝一卡通并成一行两列；
 * 一卡通能力收进生活页后（DESIGN §3.13），今日页不再有一卡通卡，单卡独占一行
 * 反而是唯一形态——顺带把出行卡也改成与开水卡同款的整行卡（副行放得下流程文案，
 * 右侧放「附近单车 ›」的二级入口）。
 *
 * **高度上限**：屏高 45%。8 条快捷方式（3 行）+ 开水卡在大字体小屏上足以吃掉半屏，
 * 超过上限时 dock 内部可滚——保住课表的可视区，也保证每个入口都还能够到
 * （不设上限的话，超出的部分会被挤出屏幕且无法访问）。
 *
 * **形态**（2026-09-22 二改）：独立描边卡片（`AppCard`，14dp 圆角 + 1dp 描边），
 * 左右外缩进 16dp、与底栏留 12dp。上一版是整宽铺 `surfaceContainerLow` + 顶部 20dp 圆角、
 * 直接坐在屏幕底边上的「工具台」，加了悬浮导航栏之后它读起来像底栏的延长段：
 * 半透明底栏压住卡片下缘，展开/收起的落点也看着落在底栏上。改成卡片后，
 * 自身的折叠动画与底栏形态彼此无关，间距恒定。
 */
@Composable
private fun TodayBottomDock(
    shortcuts: ShortcutSettings,
    /**
     * 底部抽屉展开态（DESIGN §3.3）；持久化，进页面时按上次的来。
     * **null = DataStore 还没读出**，整块不渲染——先按默认值画一帧再纠回来
     * 会看到「抽屉先展开、再收起」的一闪（2026-09-22 用户反馈）。
     */
    dockExpanded: Boolean?,
    onToggleDock: () -> Unit,
    onOpenShortcuts: (String?) -> Unit,
    onShortcutError: (String, String?) -> Unit,
    onNotice: (String, NoticeTone) -> Unit = { _, _ -> },
    /** 快趣出行码整行卡（DESIGN §3.9）；快捷方式之下、开水卡之上 */
    ebikeCard: (@Composable () -> Unit)? = null,
    /** 开水卡（含未登录态，显示设置可关）；恒为 dock 最后一项 */
    waterCard: (@Composable () -> Unit)? = null,
) {
    val expanded = dockExpanded ?: return
    val hasShortcuts = shortcuts.enabled && shortcuts.items.isNotEmpty()
    if (!hasShortcuts && ebikeCard == null && waterCard == null) return

    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.45f).dp
    val bottomClearance = LocalBottomBarClearance.current
    // 卡片外形走 AppCard（§3.2 卡片规格唯一出处）；内部内容自己带 16dp 横向内缩，
    // 所以这里 contentPadding 给 0，不然就是两层内缩叠起来。
    AppCard(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            // 悬浮底栏开着时，底部让位换成它给的净空（含胶囊高度、离底间距与手势条），
            // 卡片正好落在胶囊上缘之上；普通形态仍留 12dp 呼吸距离。
            .padding(bottom = bottomClearance.takeIf { it > 0.dp } ?: 12.dp),
        contentPadding = PaddingValues(0.dp),
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = maxHeight)
                .verticalScroll(rememberScrollState()),
        ) {
            // 把手恒在：整块内容（快捷方式网格 + 服务格 + 开水卡）都在它下面折叠
            DockHandle(expanded = expanded, onToggle = onToggleDock)
            // 只做高度动画，锚点选 **Top**：卡片钉在底部，高度收缩时它的顶边向下走，
            // 内容锚在顶边于是整块跟着下移——读起来就是「抽屉整体下降」。
            // 试过的两个版本都栽在这一点上：锚 Bottom（默认）时内容底边固定、只被削掉顶部；
            // 再叠一层 slide 则是位移叠加（顶边下移 H + 内容自身再移 H = 2H），看上去像瞬间消失。
            // 缓动统一 tween + FastOutSlowIn（缓入缓出），不用默认 spring——整屏宽的面板弹一下很晃。
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(
                    animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
                    expandFrom = Alignment.Top,
                ),
                exit = shrinkVertically(
                    animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
                    shrinkTowards = Alignment.Top,
                ),
            ) {
                // 底部留白挂在**被折叠的内容**里，不是挂在滚动容器上：
                // 挂容器上时收起态也留着这 16dp，卡片比把手高一截，文字看着偏上。
                Column(Modifier.padding(bottom = 16.dp)) {
                    if (hasShortcuts) {
                        ShortcutQuickGrid(
                            shortcuts.items,
                            { onOpenShortcuts(null) },
                            onShortcutError,
                            onNotice,
                        )
                    }
                    // 快趣出行码整行卡（横内缩在 EbikeCard 自带）：上方有区块时给呼吸距离
                    if (ebikeCard != null) {
                        Box(Modifier.padding(top = if (hasShortcuts) 16.dp else 0.dp)) { ebikeCard() }
                    }
                    if (waterCard != null) {
                        // 上方有区块时给一段呼吸距离；单独出现时不再顶一截空白
                        val hasAbove = hasShortcuts || ebikeCard != null
                        Box(Modifier.padding(top = if (hasAbove) 16.dp else 0.dp)) { waterCard() }
                    }
                }
            }
        }
    }
}

/**
 * 今日页居中态（加载/空态）共用外壳：`weight(1f)` 居中区 + 贴底固定区。
 * 抽出统一壳是为了防再犯「Box 少 fillMaxWidth 贴左」的错——居中容器只有一处定义。
 */
@Composable
private fun TodayCenteredShell(
    bottomDock: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    // 顶部/底部的避让由外层刷新容器给（2026-09-23 起），这里不再吃 Scaffold 的 padding
    Column(modifier = Modifier.fillMaxSize()) {
        // fillMaxWidth 必须带：Box 默认 wrap 内容、crossAxis 左对齐，
        // 少了它整个居中态贴左（旧版加载态左偏的根因）
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
        bottomDock()
    }
}

/** 加载态：水滴呼吸居中（DESIGN §3.2），底部固定区照常在位（DESIGN §3.3「加载中不留白屏」）。 */
@Composable
private fun TodayLoadingContent(
    bottomDock: @Composable () -> Unit,
) {
    TodayCenteredShell(bottomDock) {
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
    bottomDock: @Composable () -> Unit,
) {
    TodayCenteredShell(bottomDock) {
        EmptyHint(title, body, actionLabel, onAction)
    }
}

@Composable
private fun TodayContent(
    state: TodayState,
    homework: PendingHomework,
    onOpenCourse: (Course) -> Unit,
    onOpenHomeworkTodo: () -> Unit,
    bottomDock: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            val focus = state.ongoing ?: state.next
            // 焦点卡 ↔「上完/没课」的切换给淡入淡出：这是今日页最常发生的状态跳变
            //（下课瞬间），硬切显得突兀
            item(key = "focus") {
                AnimatedContent(
                    targetState = focus,
                    contentKey = { it?.id },
                    transitionSpec = {
                        fadeIn(tween(220)) togetherWith fadeOut(tween(220))
                    },
                    label = "todayFocus",
                ) { f ->
                    when {
                        f != null -> FocusCard(state, f, onOpenCourse)
                        // 远课态：下一节课在 60 分钟窗口外，不冒充「下一节」；
                        // 给一行轻量提示交代落点（DESIGN §3.3），不弹焦点卡
                        state.remaining.isNotEmpty() -> NextStartHint(state)
                        else -> DoneBlock(state)
                    }
                }
            }

            // 「今天还有」只列焦点之外的课；正在上的课已在焦点卡里，不重复出现。
            // 焦点课若是当天唯一剩余（focus 取走它后列表为空），整段标题也不出现——
            // 「今天还有 1 节」下面空着比不显示更费解。
            // 计数 = 列表里的课数（不含焦点卡那节）：旧版数 remaining，
            // 「今天还有 2 节」下面只列 1 节，数字对不上页面课块数
            if (state.listCourses.isNotEmpty()) {
                item(key = "remaining-header") {
                    SectionHeader("今天还有 ${state.listCourses.size} 节")
                }
                items(state.listCourses, key = { it.id }) { course ->
                    CourseTimelineRow(
                        course = course,
                        slots = state.slots,
                        onClick = { onOpenCourse(course) },
                        // 删除/新增课程时列表项平滑进出场，不再整列硬跳
                        modifier = Modifier.animateItem(),
                    )
                }
            }

            // 明天只在今天没有待上课程（上完 / 没课）时上桌，今天的信息优先
            if (state.tomorrowVisible) {
                item(key = "tomorrow") { TomorrowBlock(state, onOpenCourse) }
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
        bottomDock()
    }
}

/**
 * 顶部焦点卡：正在上的课，或 60 分钟窗口内的下一节（DESIGN §3.3）。
 *
 * 满宽卡片样式（2026-09-21 定稿，用户拍板）：主色 8% 底 + 左缘 3dp 主色竖条，
 * 内部「状态行（正在上课 / 下一节 · 第N-M节 + 倒计时）→ 课名 → meta →
 * （正在上课时）进度条」。与时间轴行（[CourseTimelineRow]）**同一卡片形态**：
 * 同为满宽、同圆角、同内边距体系，两处卡片左右缘天然对齐；区分靠底色
 * （焦点卡主色 / 时间轴行课程色）与状态行。焦点课从列表里拿走（不两处重复）；
 * 点卡片弹只读详情（编辑/删除是详情里的二级动作，与课表页同口径）。
 */
@Composable
private fun FocusCard(
    state: TodayState,
    focus: Course,
    onOpenCourse: (Course) -> Unit,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val primary = MaterialTheme.colorScheme.primary
    val ongoing = state.ongoing != null
    val haptics = rememberAppHaptics()

    val countdown: String? = if (ongoing) {
        state.ongoingCountdown
            ?: state.minutesToOngoingEnd?.let { "还有 $it 分钟下课" }
    } else {
        // 「下一节」进焦点卡的前提就是 ≤60 分钟（TodayState 的准入窗口），直接说分钟数
        state.minutesToNext?.let { "还有 $it 分钟上课" }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(AppCardDefaults.Shape)
            .background(primary.copy(alpha = 0.08f))
            .clickable(onClickLabel = "查看课程") {
                haptics.tap()
                onOpenCourse(focus)
            },
    ) {
        // 左缘主色竖条：焦点卡的视觉锚
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(primary),
        )
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 11.dp, vertical = 13.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = (if (ongoing) "正在上课" else "下一节") + " · " + sectionRange(focus),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = primary,
                )
                Spacer(Modifier.weight(1f))
                if (countdown != null) {
                    Text(
                        text = countdown,
                        style = MaterialTheme.typography.labelMedium,
                        color = primary,
                        textAlign = TextAlign.End,
                        maxLines = 2,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = focus.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = metaLine(state.slots, focus),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = onSurface.copy(alpha = 0.62f),
            )
            if (ongoing) {
                val progress = state.ongoingProgress
                if (progress != null) {
                    Spacer(Modifier.height(8.dp))
                    ProgressBar(progress)
                }
            }
        }
    }
}

/**
 * 远课态的轻量提示（DESIGN §3.3）：下一节课在 60 分钟窗口外时不显示「下一节」焦点卡，
 * 给一行轻提示交代落点（「今天的课 14:00 开始」），随焦点卡淡入淡出同一动画通道。
 */
@Composable
private fun NextStartHint(state: TodayState) {
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val first = state.remaining.firstOrNull() ?: return
    AppCardRow(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
    ) {
        Icon(
            imageVector = HugeIcons.Clock01,
            contentDescription = null,
            tint = primary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "今天的课 ${clockOf(state.slots, first)} 开始",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = sectionRange(first),
                style = MaterialTheme.typography.bodySmall,
                color = onSurface.copy(alpha = 0.55f),
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
 */
@Composable
private fun DoneBlock(state: TodayState) {
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    AppCardRow(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
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
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = if (state.todayAllDone) "今天的课都上完了" else "今天没有课",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (state.todayAllDone) {
                Text(
                    text = "今天共 ${state.todayTotal} 节",
                    style = MaterialTheme.typography.bodySmall,
                    color = onSurface.copy(alpha = 0.55f),
                )
            }
        }
    }
}

/**
 * 时间轴课程行：**满宽课程色卡**（2026-09-21 改版，用户拍板）。
 *
 * 与焦点卡（[FocusCard]）**同一卡片形态**：同为满宽、同圆角 12dp、同内边距体系
 * （horizontal 16dp + 卡内 11/13dp），两处卡片左右缘天然对齐；区分靠底色
 * （焦点卡主色 8% / 时间轴行课程色 16%）与焦点卡的状态行/进度条。
 * 卡内三行：课名 → 时刻范围+@地点 · 教师（metaLine 已含 `10:15–11:40` 起止，
 * 旧版行首时刻列删除后时刻信息仍完整）；整行可点弹只读详情。
 */
@Composable
private fun CourseTimelineRow(
    course: Course,
    slots: List<TimeSlot>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val accent = courseColor(course.colorIndex)
    val meta = metaLine(slots, course)
    val haptics = rememberAppHaptics()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(AppCardDefaults.Shape)
            .background(accent.copy(alpha = 0.16f))
            .clickable(onClickLabel = "查看课程") {
                haptics.tap()
                onClick()
            },
    ) {
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 11.dp, vertical = 13.dp),
        ) {
            Text(
                text = course.name,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = onSurface,
            )
            if (meta.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = onSurface.copy(alpha = 0.68f),
                )
            }
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
    onOpenCourse: (Course) -> Unit,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface

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
        // 课程行自带 vertical 4dp 外间距，这里不再叠一层
        state.tomorrowCourses.forEach { course ->
            CourseTimelineRow(
                course = course,
                slots = state.slots,
                onClick = { onOpenCourse(course) },
            )
        }
    }
}

// monthDayFmt 已收拢为 domain/TodayFormat.kt 的 MONTH_DAY_FORMAT（与调课页共用）

/**
 * 底部固定区整行卡的最小高度（快趣出行码卡 + 开水未登录态/已登录态）：
 * = 两行文本（bodyMedium 20dp + bodySmall 16dp）+ 上下 padding 22dp。
 * 统一 min 后，出行卡与开水卡高度恒定；系统大字体时自然高度超过 min 也不受影响
 * （min 只是下限）。
 */
private val QuickCardMinHeight = 58.dp


/**
 * 快趣出行码整行卡（DESIGN §3.9，2026-09-24 由两列服务格改整行，与开水卡同形态）：
 * 标题「快趣出行码」+ 副行「微信扫一扫开车」，**右侧「附近单车 ›」是二级入口**
 * （[CardSideActionText]，直达附近单车地图 `EBIKE_MAP`）；点卡片其余位置进出码页。
 */
@Composable
private fun EbikeCard(onOpen: () -> Unit, onOpenMap: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    AppCardRow(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .heightIn(min = QuickCardMinHeight),
        onClick = onOpen,
        onClickLabel = "打开共享单车出码",
        contentPadding = PaddingValues(horizontal = 13.dp, vertical = 11.dp),
    ) {
        Icon(
            HugeIcons.ScooterElectric,
            contentDescription = null,
            tint = primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = "快趣出行码",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "微信扫一扫开车",
                style = MaterialTheme.typography.bodySmall,
                color = onSurface.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(4.dp))
        CardSideActionText(
            text = "附近单车 ›",
            style = MaterialTheme.typography.bodyMedium,
            color = primary,
            fontWeight = FontWeight.SemiBold,
            onClickLabel = "打开附近单车地图",
            onClick = onOpenMap,
        )
    }
}


/**
 * 一键开水卡（DESIGN §3.3 底部固定区）：**默认常显**，按登录态分两形态——
 * 已登录 = 余额卡形态（[WaterQuickEntry]，点余额弹开水操作面板，点卡片其余位置进开水页）；
 * 未登录 = 未登录态（[WaterLoggedOutCard]，点卡片跳开水页，登录表单就在该页）。
 */
@Composable
private fun WaterCard(vm: WaterViewModel, onOpen: () -> Unit, onOpenEntrySheet: () -> Unit) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    if (state.loggedIn) {
        WaterQuickEntry(vm, onOpen, onOpenEntrySheet)
    } else {
        WaterLoggedOutCard(onOpen)
    }
}

/**
 * 底部抽屉的把手（DESIGN §3.3，2026-09-22 加）。快捷方式网格、快趣出行码、
 * 胖乖生活开水**整块**收在它下面，展开态持久化（`DisplayPrefs.todayDockExpanded`，默认展开）。
 *
 * 收起后 dock 只剩这一行（约 40dp）——大字体小屏上几张卡 + 三行图标最容易吃掉半屏，
 * 而这一整块并非每屏都要看。把手恒在：收起态它是「下面还有东西」的提示，也是唯一的
 * 展开入口，不能跟着一起藏。文案「江水生活」固定，右侧「展开 / 收起 + 方向箭头」表达状态。
 */
@Composable
private fun DockHandle(
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val haptics = rememberAppHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 左右 10dp + 内层 4dp = 文字离卡边 14dp，与 AppCardDefaults.Padding 同档，
            // 收起态这行文字才和上方课程卡、作业卡的文字在同一条竖线上
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClickLabel = if (expanded) "收起江水生活" else "展开江水生活") {
                haptics.tap()
                onToggle()
            }
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "江水生活",
            style = MaterialTheme.typography.labelLarge,
            color = onSurface.copy(alpha = 0.75f),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = if (expanded) "收起" else "展开",
            style = MaterialTheme.typography.bodySmall,
            color = onSurface.copy(alpha = 0.5f),
        )
        Spacer(Modifier.width(2.dp))
        Icon(
            imageVector = if (expanded) HugeIcons.ChevronUp else HugeIcons.ChevronDown,
            contentDescription = null,
            tint = onSurface.copy(alpha = 0.5f),
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * 未登录态开水卡：与已登录卡同款 1dp 描边形态，只交代「未登录 + 点这里去登录」，
 * 不显示设备/解锁按钮——登录表单在开水页（SubpageActivity.WATER），点卡片直达。
 */
@Composable
private fun WaterLoggedOutCard(onOpen: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    AppCardRow(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .heightIn(min = QuickCardMinHeight),
        onClick = onOpen,
        onClickLabel = "去登录胖乖生活",
        contentPadding = PaddingValues(horizontal = 13.dp, vertical = 11.dp),
    ) {
        Icon(
            HugeIcons.Droplet,
            contentDescription = null,
            tint = primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = "胖乖生活 · 未登录",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "点击去登录，登录后可一键开水、查余额与订单",
                style = MaterialTheme.typography.bodySmall,
                color = onSurface.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 已登录的开水卡（2026-09-23 重构）：不再卡内放「开水」按钮——
 * 标题「胖乖生活」+ 副行设备名（同旧卡口径），**小票余额放卡片右侧**，点余额弹出
 * 开水操作面板（[WaterEntrySheet]：余额/积分/开水按钮/流程态/结算明细，
 * 与开水页共享同一 ViewModel）。整卡点击进开水页（选设备、看订单详情）。
 * 流程不在卡内发起，但若弹窗/开水页留下进行中的流程，副行给流程简报——
 * 关闭弹窗不等于丢弃状态，右侧余额仍可点回面板看完整进度。
 */
@Composable
private fun WaterQuickEntry(vm: WaterViewModel, onOpen: () -> Unit, onOpenEntrySheet: () -> Unit) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val flow = state.flow
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val haptics = rememberAppHaptics()
    AppCardRow(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .heightIn(min = QuickCardMinHeight),
        onClick = onOpen,
        onClickLabel = "打开胖乖生活开水页",
        contentPadding = PaddingValues(horizontal = 13.dp, vertical = 11.dp),
    ) {
        Icon(
            HugeIcons.Droplet,
            contentDescription = null,
            tint = primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            WaterCardTitle("胖乖生活")
            // 副行：Idle = 设备名（异步取的，占位口径见 [waterDeviceLabel]）；
            // 流程态 = 流程简报（计时/结算文案每秒都在变，不做 Crossfade，会一直闪）。
            // 简报纯展示不可点——进面板走右侧余额，副行不再嵌套 clickable
            Text(
                text = when (flow) {
                    is UnlockFlowState.Idle -> waterDeviceLabel(state)
                    is UnlockFlowState.PreChecking -> flow.step
                    is UnlockFlowState.Working -> "正在出水 ${waterClock(flow.elapsedSeconds)}"
                    is UnlockFlowState.Success -> "开水成功 · 花费 ¥${calculateActualCost(flow.result)}"
                    is UnlockFlowState.Failed -> "开水失败 · ${flow.message}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = onSurface.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(4.dp))
        // 右侧小票余额（进入面板的入口，见 [CardSideActionText]）。
        // 余额没拉过给不可点占位（与生活页一卡通余额卡「读取中…」同口径）
        when {
            // balance 是委托属性，smart cast 不可用，取本地快照
            state.balance != null -> {
                val balance = state.balance
                CardSideActionText(
                    text = "小票 ¥${balance?.ticketText} ›",
                    style = MaterialTheme.typography.bodyMedium,
                    color = primary,
                    fontWeight = FontWeight.SemiBold,
                    onClickLabel = "查看小票与开水",
                    onClick = onOpenEntrySheet,
                )
            }
            state.balanceLoaded -> CardSideActionText(
                text = "余额暂不可用 ›",
                style = MaterialTheme.typography.bodySmall,
                color = onSurface.copy(alpha = 0.55f),
                onClickLabel = "查看小票与开水",
                onClick = onOpenEntrySheet,
            )
            else -> Text(
                text = "余额读取中…",
                style = MaterialTheme.typography.bodySmall,
                color = onSurface.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 整行卡右侧的二级动作文本（2026-09-24 由开水卡专用泛化，开水卡/快趣出行码卡共用）：
 * 开水卡 = 点它弹开水操作面板，出行卡 = 点它直达附近单车地图。
 *
 * 热区扩大靠 `clip → clickable → padding` 的顺序——padding 在 clickable **之内**，
 * 触达块 = 文字 + 四周内缩（垂直 8dp×2 ≈ 32dp 高满足 M3 最小触达，左侧再扩 12dp），
 * 文字本体位置不变（垂直居中抵消、无 end padding 所以右缘不动）；涟漪被 8dp 圆角收口。
 * 该 clickable 嵌在整卡 clickable 之内，不冒泡触发整卡跳转。
 */
@Composable
private fun CardSideActionText(
    text: String,
    style: TextStyle,
    color: Color,
    fontWeight: FontWeight? = null,
    onClickLabel: String,
    onClick: () -> Unit,
) {
    val haptics = rememberAppHaptics()
    Text(
        text = text,
        style = style,
        color = color,
        fontWeight = fontWeight,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClickLabel = onClickLabel) {
                haptics.tap()
                onClick()
            }
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
    )
}

private fun waterClock(totalSeconds: Int): String {
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return "%02d:%02d".format(m, s)
}

/**
 * 开水卡 Idle 态副行的设备名（2026-09-22 引入，2026-09-23 余额移至右侧后回归副行）：
 * 设备列表是异步取的，没拉过先说「读取设备…」，拉过但没有才说「未选择设备」——
 * 首帧的占位文案不该用一个可能被立刻顶掉的结论。
 *
 * `devices.isEmpty()` 也算未就绪：[WaterUiState] 的 `loadingDevices` 初值是 false，
 * 而 `refreshDevices` 要等协程跑起来才置 true，中间那一帧会落到「未选择设备」。
 * 这张卡只在已登录时出现（未登录走 `WaterLoggedOutCard`），已登录却没有设备列表，
 * 只能是还没拉到。
 */
private fun waterDeviceLabel(state: WaterUiState): String =
    state.selectedDevice?.goodsName?.ifBlank { "未命名设备" }
        ?: if (state.loadingDevices || state.devices.isEmpty()) "读取设备…" else "未选择设备"

/** 开水卡主行文本（与流程简报共用同一套字重与截断口径）。 */
@Composable
private fun WaterCardTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * 快捷方式三列图标网格（DESIGN §3.8，2026-09-19 自横滑 chips 改，用户拍板）：
 * 应用图标样式——方形图标块 + 块下单行名称，三项一行、超出换行；新增条目按列表顺序
 * 落最后的新行，区块向上生长（开水卡恒在底部固定区最后一项）。
 * 点击立即拉起（执行层与错误口径见 [ShortcutLauncher]，失败走 [onShortcutError] 的
 * Snackbar 兜底，不做预检确认）；长按弹菜单：添加到桌面 / 快捷方式设置。
 */
@Composable
private fun ShortcutQuickGrid(
    items: List<ShortcutItem>,
    onOpenSettings: () -> Unit,
    onShortcutError: (String, String?) -> Unit,
    onNotice: (String, NoticeTone) -> Unit = { _, _ -> },
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items.chunked(3).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                rowItems.forEach { item ->
                    ShortcutGridCell(
                        item = item,
                        onOpenSettings = onOpenSettings,
                        onShortcutError = onShortcutError,
                        onNotice = onNotice,
                        modifier = Modifier.weight(1f),
                    )
                }
                // 末行不满 3 个时补空位，保持三列对齐
                repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** 三列网格单元：方形图标块（52dp，圆角 12dp）+ 块下单行名称。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShortcutGridCell(
    item: ShortcutItem,
    onOpenSettings: () -> Unit,
    onShortcutError: (String, String?) -> Unit,
    onNotice: (String, NoticeTone) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val haptics = rememberAppHaptics()
    val tileShape = RoundedCornerShape(12.dp)
    var menuOpen by remember { mutableStateOf(false) }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(tileShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    .combinedClickable(
                        onClick = {
                            haptics.tap()
                            ShortcutLauncher.launch(context, item)?.let { message ->
                                onShortcutError(message, item.id)
                            }
                        },
                        onLongClick = {
                            haptics.tap()
                            menuOpen = true
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                ShortcutIcon(item, Modifier.size(28.dp))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("添加到桌面") },
                    leadingIcon = { Icon(HugeIcons.Link01, null, modifier = Modifier.size(18.dp)) },
                    onClick = {
                        menuOpen = false
                        haptics.tap()
                        // 钉桌面会弹系统确认框，成功无需再提示；失败（桌面不支持等）报结果。
                        // 不走 onShortcutError：那条通道带「去设置」动作，而桌面不支持、
                        // 目标未安装都不是设置页能修的，给出口反而误导
                        ShortcutPinner.pin(context, item)?.let { message ->
                            onNotice(message, NoticeTone.Warning)
                        }
                    },
                )
                DropdownMenuItem(
                    text = { Text("快捷方式设置") },
                    leadingIcon = { Icon(HugeIcons.Edit02, null, modifier = Modifier.size(18.dp)) },
                    onClick = {
                        menuOpen = false
                        onOpenSettings()
                    },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = item.name,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 今日页下拉刷新阈值（2026-09-23）。M3 默认 `PullToRefreshDefaults.PositionalThreshold` 是 80dp，
 * 而 `PullToRefreshModifierNode` 里计入拖动的只有实际位移的一半
 * （`adjustedDistancePulled = distancePulled * DragMultiplier`，`DragMultiplier = 0.5f`），
 * 也就是真拖 160dp 才触发。今日页顶部可拖空间比消费流水页的列表短，收到 56dp（真拖 112dp）。
 * modifier 的 threshold 与 `PullToRefreshDefaults.Indicator` 的 threshold **必须同值**：
 * 前者决定松手触发点，后者决定图标滑出的落位，写岔了会出现「图标到位了却没刷新」。
 */
private val TodayPullRefreshThreshold = 56.dp
