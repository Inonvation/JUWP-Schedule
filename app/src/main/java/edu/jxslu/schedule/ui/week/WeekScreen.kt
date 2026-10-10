package edu.jxslu.schedule.ui.week

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.SubpageActivity
import edu.jxslu.schedule.SubpageScreen
import edu.jxslu.schedule.data.repo.ImportPreview
import edu.jxslu.schedule.data.repo.ImportResult
import edu.jxslu.schedule.domain.Course
import edu.jxslu.schedule.domain.CourseFilter
import edu.jxslu.schedule.domain.LocalTimeLike
import edu.jxslu.schedule.domain.ScheduleCalculator
import edu.jxslu.schedule.domain.ScheduleOverrides
import edu.jxslu.schedule.domain.SemesterConfig
import edu.jxslu.schedule.domain.TimeSlot
import edu.jxslu.schedule.domain.TimetablePrefs
import edu.jxslu.schedule.ui.common.CourseDetailSheet
import edu.jxslu.schedule.ui.common.CourseEditSheet
import edu.jxslu.schedule.ui.common.ResumedTickerEffect
import edu.jxslu.schedule.ui.common.DeleteConfirmDialog
import edu.jxslu.schedule.ui.common.DialogWindowFadeEffect
import edu.jxslu.schedule.ui.common.GhostCourseCard
import edu.jxslu.schedule.ui.common.GridCellStyle
import edu.jxslu.schedule.ui.common.GridCourseCard
import edu.jxslu.schedule.ui.common.ImportTargetDialogHost
import edu.jxslu.schedule.ui.common.LoadingHint
import edu.jxslu.schedule.ui.common.LocalBottomBarClearance
import edu.jxslu.schedule.ui.common.LocalBottomBarVisibleRequest
import edu.jxslu.schedule.ui.common.SingleSectionCard
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import edu.jxslu.schedule.ui.common.readTextFromUri
import edu.jxslu.schedule.ui.common.resolveImportTarget
import edu.jxslu.schedule.ui.common.AppNoticeVisuals
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.AppPermissions
import edu.jxslu.schedule.ui.common.NoticeTone
import edu.jxslu.schedule.ui.common.showNotice
import edu.jxslu.schedule.ui.jwvw.JwImportOutcomeEffect
import edu.jxslu.schedule.ui.me.DisplaySettingsContent
import edu.jxslu.schedule.ui.me.MeViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.Eye
import me.rerere.hugeicons.stroke.Import
import me.rerere.hugeicons.stroke.Share08
import android.Manifest
import android.util.Log
import java.time.LocalDate
import java.time.format.DateTimeFormatter

// dayLabels / monthDayFmt 在 WeekGrid.kt（表头与顶栏共用）；fullDateFmt 仅顶栏用
private val fullDateFmt = DateTimeFormatter.ofPattern("yyyy/M/d")

// 顶栏高度保留本文件定义；时间轴栏宽 / 表头高度已用户可调（显示设置滑块），
// 默认值与滑块范围统一收敛到 TimetablePrefs companion（单一来源），几何唯一来源是
// buildGridLayout（WeekGridLayout.kt）产出的 GridLayout（railWidth / dayHeaderHeight 字段）。
// internal：导出为图片要按「课表页的网格可用高」反推板的行高，顶栏是那笔预算的一段
//（见 WeekGridLayout.shareBoardGridHeightDp）——单一来源，别在两处各写一个 56。
internal val TopBarHeight = 56.dp

/**
 * 周课表：小节 × 星期网格。
 * 默认周一至周日，可在「显示设置」里关掉周末；整页 HorizontalPager 横滑切周。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeekScreen(
    onOpenJwImport: () -> Unit = {},
    onOpenTimetableManage: () -> Unit = {},
    /** 某课程的笔记·课件（课程详情弹窗入口，DESIGN §3.11） */
    onOpenCourseNotes: (Course) -> Unit = {},
    /** 某课程的作业（课程详情弹窗入口，DESIGN §3.11） */
    onOpenCourseHomework: (Course) -> Unit = {},
    viewModel: WeekViewModel = viewModel(
        factory = WeekViewModel.Factory(Graph.repository(LocalContext.current)),
    ),
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Course?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var detailCourse by remember { mutableStateOf<Course?>(null) }
    // 入口与弹层：周次区（顶栏日期块）→ 选周；课表名 → 切换课表；
    // 眼睛 → 页内**覆盖弹层**（DisplaySettingsContent）。
    // 根因：显示设置先前跳独立子页，课表显示被整页替换/挤压；现在面板只盖住下半屏，
    // 上方课表保持原样，样式改动在真实网格上即时生效（拾光的交互形态）。
    var pickerOpen by remember { mutableStateOf(false) }
    var importOpen by remember { mutableStateOf(false) }
    var switchOpen by remember { mutableStateOf(false) }
    var displaySheetOpen by remember { mutableStateOf(false) }
    var shareOpen by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Course?>(null) }
    // 点「放假 / 补课」表头弹的覆盖详情（DESIGN §4.40）；(week, day)
    var overrideInfo by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    // JSON 文件导入（弹层入口）：读文件 → preview → 目标课表选择弹窗 → importJson，
    // 结果走 Snackbar。目标选择与教务导入共用 ImportTargetDialogHost，语义两处一致。
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val repo = remember { Graph.repository(context) }
    var pendingJsonText by remember { mutableStateOf<String?>(null) }
    var jsonPreview by remember { mutableStateOf<ImportPreview.Ok?>(null) }

    val jsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = readTextFromUri(context, uri)
            if (text.isNullOrBlank()) {
                snackbar.showNotice(
                    AppNoticeVisuals("读取文件失败", tone = NoticeTone.Error),
                )
                return@launch
            }
            when (val p = repo.previewImport(text)) {
                is ImportPreview.Error -> snackbar.showNotice(
                    AppNoticeVisuals(p.message, tone = NoticeTone.Error),
                )
                is ImportPreview.Ok -> {
                    pendingJsonText = text
                    jsonPreview = p
                }
            }
        }
    }

    suspend fun runJsonImport(text: String, merge: Boolean, targetId: Long) {
        when (val r = repo.importJson(text, merge, targetId)) {
            is ImportResult.Success -> snackbar.showNotice(
                AppNoticeVisuals(
                    if (merge) "合并完成：新增 ${r.added} / 文件共 ${r.total}" else "已覆盖导入 ${r.total} 门课",
                    tone = NoticeTone.Success,
                ),
            )
            is ImportResult.Failure -> snackbar.showNotice(
                AppNoticeVisuals(r.message, tone = NoticeTone.Error),
            )
        }
    }

    // ---- 分享（DESIGN §4.12）----
    // CSV/JSON 走 SAF 另存为（与「我的 → 导出 JSON」同形态）；日历同步先运行时申请
    // READ/WRITE_CALENDAR，授予后续跑（授予回调里执行同步），拒绝则 Snackbar 提示。
    val fileDateTag = remember { LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) }
    val csvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri -> if (uri != null) viewModel.exportCsv(context, uri) }
    val jsonExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> if (uri != null) viewModel.exportJson(context, uri) }
    var pendingCalendarSync by remember { mutableStateOf(false) }

    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.all { it }) {
            pendingCalendarSync = true
        } else {
            // 拒绝也要有反馈：弹层已在发起申请时关闭（见 startCalendarSync），
            // 这里的提示才看得见——此前拒绝分支不关弹层，Snackbar 被分享弹层整个盖住
            scope.launch {
                snackbar.showNotice(
                    AppNoticeVisuals("未授予日历权限，无法同步", tone = NoticeTone.Warning),
                )
            }
            pendingCalendarSync = false
        }
    }

    // 权限授予后的续跑放 LaunchedEffect，避免在 launcher 回调里直接调 VM 引发重组竞态
    LaunchedEffect(pendingCalendarSync) {
        if (pendingCalendarSync) {
            pendingCalendarSync = false
            viewModel.syncToCalendar(context)
        }
    }

    fun startCalendarSync() {
        val needed = AppPermissions.missing(context, AppPermissions.calendar)
        // 先关分享弹层再进任何一条分支：弹层是独立窗口，盖在它下面的提醒一律不可见
        //（已授权分支此前就在这里关，拒绝分支漏了 → 提示被盖住）
        shareOpen = false
        if (needed.isEmpty()) {
            viewModel.syncToCalendar(context)
        } else {
            calendarPermissionLauncher.launch(needed.toTypedArray())
        }
    }

    // VM 分享结果 → 提示卡（一次性，消费后置空）
    val shareMessage by viewModel.shareMessage.collectAsStateWithLifecycle()
    LaunchedEffect(shareMessage) {
        shareMessage?.let {
            snackbar.showNotice(AppNoticeVisuals(it.text, tone = it.tone))
            viewModel.consumeShareMessage()
        }
    }

    // 撤销型反馈（DESIGN §3.3）：删除课程等动作执行后给「撤销」，点了就回滚
    val undoableMessage by viewModel.undoableMessage.collectAsStateWithLifecycle()
    LaunchedEffect(undoableMessage) {
        undoableMessage?.let { m ->
            val result = snackbar.showNotice(m.text, actionLabel = "撤销", duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) m.undo()
            viewModel.consumeUndoableMessage()
        }
    }

    // 教务导入成功的气泡（DESIGN §3.3）：导入窗口与这一页分属两个 Activity，结果经进程内
    // 单例转交过来（2026-09-29 用户口径：提醒走下方那条气泡，不弹窗）
    JwImportOutcomeEffect(snackbar)

    val today = LocalDate.now()
    val todayDay = today.dayOfWeek.value
    val totalWeeks = maxOf(state.semester?.totalWeeks ?: 20, 1)
    val sectionCount = maxOf(11, state.slots.maxOfOrNull { it.number } ?: 11)
    val visibleDays = state.visibleDays
    val days = visibleDays.size
    val weeksWithCourses = remember(state.allCourses) {
        state.allCourses.flatMap { it.weeks }.toSet()
    }

    // Pager 初始页必须一次到位。
    // 根因：initialPage 只在首次创建时生效；若在 loading 期间以第 1 周创建 Pager，
    // 数据就绪后 scrollToPage 到本周，肉眼可见「先第一周、再跳本周」的闪跳。
    // 方案：VM 初始化（含初始周次解析）完成前不创建 Pager，创建时 initialPage
    // 直接取解析好的本周周次；加载期间顶栏与网格都不渲染，首帧即终态。
    val pagerState = if (!state.loading) {
        rememberPagerState(
            initialPage = (state.week - 1).coerceIn(0, (totalWeeks - 1).coerceAtLeast(0)),
        ) { totalWeeks }
    } else {
        null
    }

    // 顶栏周次必须**跟手**，不能等松手后 settledPage 才更新。
    // 根因：此前顶栏读 VM 的 `state.week`，而 VM 只被 `snapshotFlow { settledPage }` 驱动——
    // 滑动过程中 settledPage 恒为旧值，松手（含 fling 衰减小 1s）后才变，于是「第 N 周」滞后。
    // 方案：横滑进行中由 currentPage + currentPageOffsetFraction 就地推算出「离得最近的那一周」，
    // 手指划过一半就翻牌；停稳后再由 settledPage 回写 VM，两者取值一致时不会抖动。
    val pagerWeek = pagerState?.let { ps ->
        val nearest = ps.currentPage + if (ps.currentPageOffsetFraction > 0.5f) 1 else 0
        nearest.coerceIn(0, (totalWeeks - 1).coerceAtLeast(0)) + 1
    }
    val displayWeek = pagerWeek ?: state.week

    // VM → Pager（回到本周 / 外部改周）
    LaunchedEffect(pagerState, totalWeeks, state.week) {
        val ps = pagerState ?: return@LaunchedEffect
        val target = state.week.coerceIn(1, totalWeeks) - 1
        if (ps.settledPage != target) {
            // 相邻周动画滚回（回本周最常见就是从 ±1 页滑走）；远距瞬移，
            // 避免动画一路掠过中间几周造成无意义闪烁
            if (kotlin.math.abs(target - ps.settledPage) == 1) {
                ps.animateScrollToPage(target)
            } else {
                ps.scrollToPage(target)
            }
        }
    }

    // Pager → VM（整页横滑切周）。用 settledPage 而非 currentPage：
    // 回写只在停稳后发生，避免手指划过中间页时把 VM 周次刷成中间值。
    LaunchedEffect(pagerState) {
        val ps = pagerState ?: return@LaunchedEffect
        snapshotFlow { ps.settledPage }
            .collectLatest { page ->
                val week = page + 1
                if (week != state.week) viewModel.setWeek(week)
            }
    }

    // 当前时刻线要跟着时间走；RESUMED 才 tick——退后台/灭屏不再每 30 秒重建整页 UiState
    ResumedTickerEffect(intervalMillis = 30_000L) { viewModel.refreshNow() }

    // 背景图文件不在磁盘上（清过数据、恢复备份不完整、目录被系统清过）：
    // 偏好里还挂着名字的话，渲染每次都会解一遍空气。校验一次就清掉名字，降级成无背景。
    LaunchedEffect(state.bgImageName) {
        val name = state.bgImageName ?: return@LaunchedEffect
        if (!Graph.scheduleBackground(context).exists(name)) {
            Log.w("WeekScreen", "background file missing, clearing preference")
            viewModel.clearBackgroundImage()
        }
    }

    // ---- 网格字号换算（不依赖可用约束，提到 Scaffold 外）----
    // 换算口径收敛在 rememberGridTypeSpec（与「导出为图片」共用同一份，避免两处漂移）；
    // 上提到函数级是因为「表头高度」的动态下限 headerMinDp 要同时喂给两处：
    // 布局（渲染兜底防日期文字截断）与显示设置弹层（滑块 valueRange 从有效下限起步）。
    val typeSpec = rememberGridTypeSpec(state, days)
    val gridScale = typeSpec.gridScale
    val roomFontSp = typeSpec.roomFontSp
    val teacherFontSp = typeSpec.teacherFontSp
    val railFontSp = typeSpec.railFontSp
    val dateFontSp = typeSpec.dateFontSp
    val headerMinDp = typeSpec.headerMinDp

    Scaffold(
        // 底色透明（DESIGN §4.22）：背景图由 JuwApp 铺在外层 Scaffold 之下（要盖到状态栏与底栏），
        // 本页的 Surface 若不透明就会把它整块盖住。不透明底只由外层 Scaffold 提供，不会露出窗口底。
        containerColor = Color.Transparent,
        // 底部导航栏 inset 已由外层底栏高度提供，内层不再消费（防底部双倍空白）；
        // 顶栏为自绘 56dp Row，本就不消费状态栏 inset，顶部由外层 padding 避让。
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            // 加载中不渲染顶栏：否则会先显示默认的「第 1 周」，就绪后再跳成本周（同 Pager 的闪跳根因）
            if (!state.loading) {
                WeekTopBar(
                    week = displayWeek,
                    today = today,
                    timetableName = state.timetableName,
                    onOpenPicker = { pickerOpen = true },
                    onOpenDisplay = { displaySheetOpen = true },
                    onOpenTimetables = { switchOpen = true },
                    onOpenImport = { importOpen = true },
                    onOpenShare = { shareOpen = true },
                )
            }
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(padding)
                // 悬浮底栏的净空：网格按可用高度自适应，不扣掉这段最后几行会被胶囊压住。
                // 背景图不受影响——它在窗口层，照旧铺到屏幕底（§4.22）。
                .padding(bottom = LocalBottomBarClearance.current),
        ) {
            val layout = buildGridLayout(
                maxHeight = maxHeight,
                maxWidth = maxWidth,
                sectionCount = sectionCount,
                dayCount = days,
                rowHeightScale = state.rowHeightScale,
                railWidth = state.railWidthDp.dp,
                // 表头高度用户值与「装得下两行日期文字」的下限取大：用户把表头拖小、
                // 日期字号拉大时兜底防截断。只抬高渲染值，不改写存储值（滑块位置不动）。
                // 下限同时是显示设置弹层里滑块的动态起点（见 headerMinDp），两端一致
                dayHeaderHeight = maxOf(state.dayHeaderHeightDp.dp, headerMinDp.dp),
            )
            val fitsOneScreen = layout.gridHeight + layout.dayHeaderHeight <= maxHeight

            val body: @Composable () -> Unit = {
                val ps = pagerState
                if (ps == null) {
                    // 初始化未完成：网格不渲染，避免 Pager 以第 1 周先出一帧（根因见 pagerState 注释）。
                    // 但也不能纯白屏——给水滴呼吸加载态（此前先是空白，后改裸 spinner）
                    LoadingHint("正在准备课表", modifier = Modifier.fillMaxSize())
                } else {
                    Row(Modifier.fillMaxSize()) {
                        TimeRail(
                            layout = layout,
                            slots = state.slots,
                            monthLabel = weekDate(state.semester, displayWeek, 1)
                                ?.let { "${it.monthValue}月" },
                            railFontSp = railFontSp,
                            dateFontSp = dateFontSp,
                        )
                        HorizontalPager(
                            state = ps,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            verticalAlignment = Alignment.Top,
                        ) { pageIndex ->
                        val week = pageIndex + 1
                        WeekPage(
                            week = week,
                            layout = layout,
                            visibleDays = visibleDays,
                            allCourses = state.allCourses,
                            slots = state.slots,
                            semester = state.semester,
                            overrides = state.overrides,
                            isTodayWeek = week == state.todayWeek,
                            todayDay = todayDay,
                            now = state.now,
                            showNonCurrentWeek = state.showNonCurrentWeek,
                            cellStyle = GridCellStyle(
                                cornerRadiusDp = state.cellRadiusDp,
                                opacity = state.cellOpacity,
                                centerHorizontal = state.cellCenterH,
                                centerVertical = state.cellCenterV,
                                showTeacher = state.showTeacher,
                                showBorder = state.showCellBorder,
                                roomFontSp = roomFontSp,
                                teacherFontSp = teacherFontSp,
                                showAtSign = state.showAtSign,
                            ),
                            showNowLine = state.showNowLine,
                            showGridLines = state.showGridLines,
                            dateFontSp = dateFontSp,
                            tapBlankToAdd = state.tapBlankToAdd,
                            onOpenOverrideInfo = { w, d -> overrideInfo = w to d },
                            onAddEmpty = { day, section ->
                                editing = Course(
                                    id = 0,
                                    name = "",
                                    teacher = "",
                                    position = "",
                                    day = day,
                                    startSection = section,
                                    endSection = section,
                                    weeks = setOf(week),
                                )
                                editorOpen = true
                            },
                            onOpenCourse = { detailCourse = it },
                        )
                        }
                    }
                }
            }

            if (fitsOneScreen) {
                GridTypography(gridScale) { body() }
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    Box(Modifier.height(layout.dayHeaderHeight + layout.gridHeight)) {
                        GridTypography(gridScale) { body() }
                    }
                    if (state.totalCourseCount == 0) {
                        EmptyScheduleHint(
                            filter = state.courseFilter,
                            onOpenJwImport = onOpenJwImport,
                            tapBlankToAdd = state.tapBlankToAdd,
                        )
                    }
                    Spacer(Modifier.height(72.dp))
                }
            }

            if (fitsOneScreen && state.totalCourseCount == 0 && !state.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    EmptyScheduleHint(
                        filter = state.courseFilter,
                        onOpenJwImport = onOpenJwImport,
                        tapBlankToAdd = state.tapBlankToAdd,
                        modifier = Modifier.padding(bottom = 24.dp),
                    )
                }
            }

            // 「回到本周」悬浮按钮：非本周才出现，贴课表页右下角（与空态提示同层，故后者下移避让）。
            // 根因：顶栏右侧图标区已排满（眼睛 + 导入），且「回到本周」是低频的**纠偏**动作、
            // 不是常驻导航——放顶栏会与高频入口抢位置，也让顶栏在切周时左右跳动。
            // 放右下角后：位置固定不参与顶栏布局，拇指可达，底部再抬 24dp 避让外层底栏。
            val showBackToWeek = !state.loading && displayWeek != state.todayWeek
            if (showBackToWeek) {
                BackToCurrentWeekButton(
                    onClick = { viewModel.goToToday() },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 24.dp),
                )
            }
        }
    }

    // 显示设置覆盖面板：不跳页、不挤压课表——真实网格在上层保持不变，
    // 面板盖住下半屏，改动即时生效。
    //
    // 根因（本需求的 bug）：此前用的是 ModalBottomSheet + confirmValueChange = { false }。
    // ModalBottomSheet 内部注册了 ConsumeSwipeWithinBottomSheetBoundsNestedScrollConnection，
    // 面板内容（DisplaySettingsContent）自己又是 verticalScroll——于是「在面板里上下滑」这件事
    // 会同时喂给两个滚动消费者：内层列表滚到顶/底之后剩余位移继续向上冒泡，
    // sheet 的 anchoredDraggable 认为用户在拖面板并触发 settleToDismiss，
    // 动画先行、confirmValueChange 的否决来不及拦住（它只否决状态落点，不否决动画），
    // 结果「手动划一下窗口就被自动关闭」。
    //
    // 方案：不再用 ModalBottomSheet，改成**自绘的固定锚定面板**——
    // 结构上不存在 sheet 的拖拽手势与 nestedScroll 连接，面板只有三种退出口：
    // 「完成」按钮 / 点遮罩 / 系统返回。滚动冲突从根上消失，而不是靠参数对冲。
    //
    // 面板打开期间还要把底栏收起来（LocalBottomBarVisibleRequest）：底栏画在页面内容之后，
    // 不收的话悬浮胶囊浮在面板上、盖住面板最下面一条，那一带的点击也归胶囊。
    // onDispose 复原，免得面板还没关就切走 Tab 时底栏一直藏着。
    val bottomBarVisibleRequest = LocalBottomBarVisibleRequest.current
    DisposableEffect(displaySheetOpen) {
        bottomBarVisibleRequest.value = !displaySheetOpen
        onDispose { bottomBarVisibleRequest.value = true }
    }
    if (displaySheetOpen) {
        DisplaySettingsOverlay(
            onDismiss = { displaySheetOpen = false },
            viewModel = viewModel(factory = MeViewModel.Factory(Graph.repository(context))),
            // 表头滑块的动态下限与布局兜底同源（dateFontSp 同一换算），两端不会各说各话
            headerMinDp = headerMinDp,
        )
    }

    if (pickerOpen) {
        WeekPickerSheet(
            currentWeek = state.week,
            todayWeek = state.todayWeek,
            totalWeeks = totalWeeks,
            weeksWithCourses = weeksWithCourses,
            onPickWeek = { week ->
                pickerOpen = false
                viewModel.setWeek(week)
            },
            onBackToCurrentWeek = {
                pickerOpen = false
                viewModel.goToToday()
            },
            onDismiss = { pickerOpen = false },
        )
    }

    if (importOpen) {
        ImportEntrySheet(
            onManualAdd = {
                importOpen = false
                editing = null
                editorOpen = true
            },
            onJsonImport = {
                importOpen = false
                jsonLauncher.launch(
                    arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"),
                )
            },
            onJwImport = {
                importOpen = false
                onOpenJwImport()
            },
            onDismiss = {
                importOpen = false
            },
        )
    }

    if (shareOpen) {
        ShareEntrySheet(
            onSyncCalendar = { startCalendarSync() },
            onExportCsv = {
                shareOpen = false
                csvLauncher.launch("水贝贝课表_$fileDateTag.csv")
            },
            onExportJson = {
                shareOpen = false
                jsonExportLauncher.launch("水贝贝课表_$fileDateTag.json")
            },
            onShareImage = {
                // 先关弹层再开二级页：弹层是独立窗口，盖在二级页之上会挡住它
                shareOpen = false
                SubpageActivity.start(
                    context,
                    SubpageScreen.SCHEDULE_SHARE,
                    // focusItemId 复用为「进页时预览哪一周」（与 SHORTCUTS / RIDE 同纪律）
                    focusItemId = state.week.toString(),
                )
            },
            onDismiss = { shareOpen = false },
        )
    }

    jsonPreview?.let { preview ->
        ImportTargetDialogHost(
            courses = preview.courses,
            title = "导入 JSON 课表",
            term = preview.term,
            defaultMerge = false,
            repo = repo,
            onConfirm = { target, merge ->
                val text = pendingJsonText
                jsonPreview = null
                pendingJsonText = null
                if (text != null) {
                    scope.launch {
                        val targetId = resolveImportTarget(repo, target)
                        runJsonImport(text, merge, targetId)
                        // 导入到非当前课表后切过去，让用户立刻看到结果
                        repo.setCurrentTimetable(targetId)
                    }
                }
            },
            onDismiss = {
                jsonPreview = null
                pendingJsonText = null
            },
        )
    }

    if (switchOpen) {
        TimetableSwitchSheet(
            timetables = state.timetables,
            currentTimetableId = state.currentTimetableId,
            onSelect = { id ->
                switchOpen = false
                viewModel.selectTimetable(id)
            },
            onOpenManage = {
                switchOpen = false
                onOpenTimetableManage()
            },
            onDismiss = { switchOpen = false },
        )
    }

    detailCourse?.let { course ->
        // 教材（DESIGN §4.31）：按当前课表学期 + 课程名查；无学期号的课表给空，不显示
        val detailTextbooks by remember(course.name) {
            repo.textbooksFor(course.name)
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
            onDelete = editing?.takeIf { it.id > 0 }?.let { c ->
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
                viewModel.delete(c)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }

    // 点「放假 / 补课」表头 → 该日覆盖详情（原有安排 / 借了谁的课 / 日期 / 说明）
    overrideInfo?.let { (week, day) ->
        val detail = ScheduleOverrides.detail(
            week = week,
            day = day,
            overrides = state.overrides,
            courses = state.allCourses,
            semester = state.semester,
        )
        if (detail == null) {
            overrideInfo = null
        } else {
            OverrideInfoDialog(detail = detail, onDismiss = { overrideInfo = null })
        }
    }
}

/** 覆盖详情弹窗：日期 + 类型 + 原有安排 + 实际安排 + 说明（DESIGN §4.40）。 */
@Composable
private fun OverrideInfoDialog(detail: ScheduleOverrides.Detail, onDismiss: () -> Unit) {
    DialogWindowFadeEffect()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                buildString {
                    val date = detail.date
                    if (date != null) {
                        append(date.monthValue).append("月").append(date.dayOfMonth).append("日 ")
                    }
                    append("周").append(DAY_NAMES.getOrNull(detail.day - 1) ?: detail.day.toString())
                    append(" · 第 ").append(detail.week).append(" 周")
                },
            )
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    if (detail.suspended) "放假 · 教学活动暂停" else "补课 · 按别天的课表上课",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (detail.suspended) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
                detail.source?.let { source ->
                    Text(
                        "照搬 第 ${source.week} 周" +
                            (DAY_NAMES.getOrNull(source.day - 1)?.let { "周$it" } ?: "") +
                            " 的教学安排",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                OverrideCourseGroup(
                    label = "原有安排",
                    courses = detail.originalCourses,
                    emptyText = "这天本来没有课",
                )
                OverrideCourseGroup(
                    label = "实际安排",
                    courses = detail.effectiveCourses,
                    emptyText = "这天不上课",
                )
                if (detail.note.isNotBlank()) {
                    Text(
                        detail.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("知道了") }
        },
    )
}

@Composable
private fun OverrideCourseGroup(label: String, courses: List<Course>, emptyText: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        if (courses.isEmpty()) {
            Text(
                emptyText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            )
        } else {
            courses.forEach { course ->
                Text(
                    buildString {
                        append("· ").append(course.name)
                        append("　第").append(course.startSection).append('-')
                        append(course.endSection).append("节")
                        if (course.position.isNotBlank()) append("　").append(course.position)
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private val DAY_NAMES = listOf("一", "二", "三", "四", "五", "六", "日")

/**
 * 把网格区域的字号倍率收进可排版区间。
 * 只包住网格本身——顶栏、弹层、空态提示按全局的字体缩放渲染（「固定字体大小」
 * 锁定默认开，恒为 0.9×；关掉锁定则跟随系统，见 `domain/FontScalePolicy`）。
 *
 * `internal`（原 private）：导出为图片的预览板（`ScheduleShareDialog`）要用同一份，
 * 两处网格排版必须同源。
 */
@Composable
internal fun GridTypography(fontScale: Float, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, fontScale),
    ) {
        content()
    }
}

/**
 * 顶栏对齐 WakeUp：左侧大号日期 + 「第 N 周 周X」小字，右侧动作图标。
 * 左侧日期块 → 周次选择；眼睛图标 → 显示设置覆盖面板。
 *
 * 本轮调整（按需求）：
 * - 去掉右侧「+」：它与「导入」弹层里的「手动添加课程」是同一件事（都开编辑器），
 *   两个入口等于把同一动作的两种叫法摆在顶栏，去掉图标后仍可从导入弹层进入。
 * - 「回到本周」移出顶栏：它不是常驻导航，会随周次反复出现/消失把右侧图标挤来挤去；
 *   改为课表页右下角悬浮按钮（见 [BackToCurrentWeekButton]），顶栏布局因此恒定。
 */
@Composable
private fun WeekTopBar(
    week: Int,
    today: LocalDate,
    timetableName: String,
    onOpenPicker: () -> Unit,
    onOpenDisplay: () -> Unit,
    onOpenTimetables: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenShare: () -> Unit,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val haptics = rememberAppHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 顶部 inset 自取（DESIGN §4.22）：外层 Scaffold 归零后不再垫状态栏高度，
            // 自绘顶栏自己让位；垫在 height 之外，总高 = 状态栏 + 56dp，与改动前一致。
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(TopBarHeight)
            .padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable(
                    onClickLabel = "选择周次",
                    role = Role.Button,
                    onClick = onOpenPicker,
                )
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(
                text = today.format(fullDateFmt),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = onSurface,
            )
            // 小字行放两个独立可点区：周次信息 → 周次选择器；课表名 ▾ → 切换课表。
            // 放同一行是空间取舍：顶栏横向放不下第四个常驻入口，而课表名与「第 N 周」
            // 同属"当前看的是哪张课表的哪一周"这一语义层。
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "第 $week 周 ${dayLabels[today.dayOfWeek.value - 1]}",
                    style = MaterialTheme.typography.labelMedium,
                    color = onSurface.copy(alpha = 0.55f),
                )
                if (timetableName.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "$timetableName ▾",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(
                                onClickLabel = "切换课表",
                                role = Role.Button,
                                onClick = onOpenTimetables,
                            )
                            .padding(horizontal = 2.dp),
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = { haptics.tap(); onOpenShare() }) {
            Icon(
                HugeIcons.Share08,
                contentDescription = "分享课表",
                tint = onSurface.copy(alpha = 0.75f),
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(onClick = { haptics.tap(); onOpenDisplay() }) {
            Icon(
                HugeIcons.Eye,
                contentDescription = "显示设置",
                tint = onSurface.copy(alpha = 0.75f),
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(onClick = { haptics.tap(); onOpenImport() }) {
            Icon(
                HugeIcons.Import,
                contentDescription = "导入课表",
                tint = onSurface.copy(alpha = 0.75f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
/**
 * 课表页右下角的「回到本周」悬浮按钮（非本周才显示）。
 *
 * 根因：这是纠偏动作、不是常驻入口——放顶栏会在切周时反复出现/消失，
 * 把右侧图标左右推挤（用户明确反馈过位置跳动）。移到右下角后：
 * - 不参与顶栏布局，顶栏恒定；
 * - 拇指可达（右手握持主区）；
 * - 用底色调 + 阴影与课表卡区分，压住空白格但不遮课程内容（避开最后一节与底栏）。
 */
@Composable
private fun BackToCurrentWeekButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberAppHaptics()
    Surface(
        modifier = modifier
            .shadow(4.dp, RoundedCornerShape(50))
            .clip(RoundedCornerShape(50))
            .clickable(
                onClickLabel = "回到本周",
                role = Role.Button,
            ) {
                haptics.tap()
                onClick()
            },
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                HugeIcons.ArrowDown01,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Text("回到本周", style = MaterialTheme.typography.labelLarge)
        }
    }
}
