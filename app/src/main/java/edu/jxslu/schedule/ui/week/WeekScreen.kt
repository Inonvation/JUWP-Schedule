package edu.jxslu.schedule.ui.week

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import edu.jxslu.schedule.data.repo.ImportPreview
import edu.jxslu.schedule.data.repo.ImportResult
import edu.jxslu.schedule.domain.Course
import edu.jxslu.schedule.domain.CourseFilter
import edu.jxslu.schedule.domain.GridFont
import edu.jxslu.schedule.domain.LocalTimeLike
import edu.jxslu.schedule.domain.ScheduleCalculator
import edu.jxslu.schedule.domain.SemesterConfig
import edu.jxslu.schedule.domain.TimeSlot
import edu.jxslu.schedule.ui.common.CourseEditSheet
import edu.jxslu.schedule.ui.common.DeleteConfirmDialog
import edu.jxslu.schedule.ui.common.GhostCourseCard
import edu.jxslu.schedule.ui.common.GridCellStyle
import edu.jxslu.schedule.ui.common.GridCourseCard
import edu.jxslu.schedule.ui.common.ImportTargetDialogHost
import edu.jxslu.schedule.ui.common.SingleSectionCard
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import edu.jxslu.schedule.ui.common.resolveImportTarget
import edu.jxslu.schedule.ui.me.DisplaySettingsContent
import edu.jxslu.schedule.ui.me.MeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.Eye
import me.rerere.hugeicons.stroke.Import
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val dayLabels = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
private val monthDayFmt = DateTimeFormatter.ofPattern("M/d")
private val fullDateFmt = DateTimeFormatter.ofPattern("yyyy/M/d")

private val TopBarHeight = 56.dp
private val RailWidth = 48.dp
private val DayHeaderHeight = 44.dp
/** 色块在格子里的内缩。只留 1dp：WakeUp 风格的紧凑感来自「块几乎填满格子」，此前 3dp×2 加上行距显得松散。 */
private val CellGap = 1.dp

/**
 * 大节之间的空隙。根因：此前 12dp（加上课块两侧各 1dp 内缩，同日相邻课块实际空 14dp），
 * 真机反馈同一天两节课之间的空白偏大——课块本身已内缩 1dp，12:3 的「20 分钟:5 分钟」
 * 设计比例在视觉上只贡献了松散感。收成 6dp：保留「大节间 > 大节内」的层级即可，
 * 不再追求与作息分钟数成比例。
 */
private val IntraGap = 3.dp
private val InterGap = 6.dp

/** 再挤不能低于这个高度，否则课程名放不下；不足时整表竖滑。 */
private val MinRowHeight = 40.dp

/**
 * 网格几何：行号 = 小节号（1–11）。
 *
 * 根因：旧实现只有 5 行（大节），却拿小节号当行号用，
 * 于是 7-8 节的课落到第 7 行、9-10 节落到第 9 行，直接被画到网格外。
 * 现在行数与 `TimeSlot.number` 一一对应，不会再错位。
 */
internal data class GridLayout(
    val rowHeight: Dp,
    /** 下标 0 是第 1 节的顶部 */
    val rowTops: List<Dp>,
    val rowBottoms: List<Dp>,
    val dayWidth: Dp,
) {
    val gridHeight: Dp get() = rowBottoms.last()
    val sections: Int get() = rowTops.size

    fun topOf(section: Int): Dp = rowTops[(section - 1).coerceIn(0, rowTops.lastIndex)]
    fun bottomOf(section: Int): Dp = rowBottoms[(section - 1).coerceIn(0, rowBottoms.lastIndex)]
    fun heightOf(startSection: Int, endSection: Int): Dp = bottomOf(endSection) - topOf(startSection)
}

internal fun buildGridLayout(
    maxHeight: Dp,
    maxWidth: Dp,
    sectionCount: Int,
    dayCount: Int,
    rowHeightScale: Float = 1f,
): GridLayout {
    val groups = ScheduleCalculator.BIG_SECTIONS
    val intraCount = groups.sumOf { it.last - it.first }
    val interCount = (groups.size - 1).coerceAtLeast(0)

    val available = maxHeight - DayHeaderHeight
    val rawRow = (available - IntraGap * intraCount - InterGap * interCount) / sectionCount
    // 倍率乘在自适应值上（不是乘在 MinRowHeight 上）：>1 时超出屏幕走竖滑，<1 时仍受下限保护
    val rowHeight = maxOf(rawRow * rowHeightScale, MinRowHeight)

    val tops = ArrayList<Dp>(sectionCount)
    val bottoms = ArrayList<Dp>(sectionCount)
    var y = 0.dp
    for (section in 1..sectionCount) {
        tops += y
        y += rowHeight
        bottoms += y
        if (section < sectionCount) {
            y += if (ScheduleCalculator.isBigSectionEnd(section)) InterGap else IntraGap
        }
    }
    return GridLayout(
        rowHeight = rowHeight,
        rowTops = tops,
        rowBottoms = bottoms,
        dayWidth = (maxWidth - RailWidth) / dayCount.coerceAtLeast(1),
    )
}

/**
 * 当前时刻在网格里的位置。
 *
 * [inBreak]=false 表示正处在一节课内，位置按 40 分钟线性插值；
 * [inBreak]=true 表示落在课间/午休/晚休，此时**贴到离得近的那一端**
 * （下课的下一行顶部，或下一节的行顶），而不是在整段间隔里线性走。
 *
 * 根因：网格的纵向比例是设计比例而非时间比例——17:10→19:00 这 110 分钟的晚饭
 * 只占 12dp，而 40 分钟一节课占约 50dp。若在间隔里线性插值，17:36 的时刻线
 * 只会落在 17:10 下方 2.8dp，看起来像「卡住/偏了」；而它其实是准确的，
 * 是「把 110 分钟压进 12dp」这件事让它没法同时表示准确与直观。
 * 改成吸附后：下课边界与上课边界二选一，位置稳定且语义清楚（已上完 / 即将开始）。
 */
internal data class NowMarker(val offsetY: Dp, val inBreak: Boolean)

internal fun nowMarker(
    slots: List<TimeSlot>,
    layout: GridLayout,
    now: LocalTimeLike,
    dayEndMinutes: Int?,
): NowMarker? {
    val minutes = now.toMinutes()
    // 终点按当日课程收口：上完最后一节就消失，而不是挂到作息表 21:10；
    // dayEndMinutes == null = 今天没课（或结束时间无法判定），整天不画线。
    // 起点仍按作息表：次日到第一节开始（默认 08:30）线自动回来。
    if (dayEndMinutes == null || minutes >= dayEndMinutes) return null
    val first = slots.minByOrNull { it.number } ?: return null
    // 还没到第一节：不画线，免得凌晨时刻线贴在网格顶部像个 bug
    if (minutes < ScheduleCalculator.toMinutes(first.startTime)) return null

    for (section in 1..layout.sections) {
        val slot = slots.firstOrNull { it.number == section } ?: continue
        val start = ScheduleCalculator.toMinutes(slot.startTime)
        val end = ScheduleCalculator.toMinutes(slot.endTime)
        if (end <= start) continue

        if (minutes in start until end) {
            val frac = (minutes - start).toFloat() / (end - start)
            val top = layout.topOf(section)
            return NowMarker(top + (layout.bottomOf(section) - top) * frac, inBreak = false)
        }
        if (minutes < start) {
            if (section == 1) return null
            val prev = slots.firstOrNull { it.number == section - 1 } ?: return null
            val prevEnd = ScheduleCalculator.toMinutes(prev.endTime)
            if (minutes < prevEnd) continue
            // 距离哪一端近就贴哪一端：刚下课贴行底，快上课贴下一节行顶
            val offset = if (minutes - prevEnd <= start - minutes) {
                layout.bottomOf(section - 1)
            } else {
                layout.topOf(section)
            }
            return NowMarker(offset, inBreak = true)
        }
    }
    return null
}

/**
 * 周课表：小节 × 星期网格。
 * 默认周一至周日，可在「显示设置」里关掉周末；整页 HorizontalPager 横滑切周。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeekScreen(
    onOpenJwImport: () -> Unit = {},
    onOpenTimetableManage: () -> Unit = {},
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
    var pendingDelete by remember { mutableStateOf<Course?>(null) }

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
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    }
                }.getOrNull()
            }
            if (text.isNullOrBlank()) {
                snackbar.showSnackbar("读取文件失败")
                return@launch
            }
            when (val p = repo.previewImport(text)) {
                is ImportPreview.Error -> snackbar.showSnackbar(p.message)
                is ImportPreview.Ok -> {
                    pendingJsonText = text
                    jsonPreview = p
                }
            }
        }
    }

    suspend fun runJsonImport(text: String, merge: Boolean, targetId: Long) {
        when (val r = repo.importJson(text, merge, targetId)) {
            is ImportResult.Success -> snackbar.showSnackbar(
                if (merge) "合并完成：新增 ${r.added} / 文件共 ${r.total}" else "已覆盖导入 ${r.total} 门课",
            )
            is ImportResult.Failure -> snackbar.showSnackbar(r.message)
        }
    }

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
            ps.scrollToPage(target)
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

    // 当前时刻线要跟着时间走
    LaunchedEffect(Unit) {
        while (true) {
            viewModel.refreshNow()
            delay(30_000)
        }
    }

    Scaffold(
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
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val layout = buildGridLayout(
                maxHeight = maxHeight,
                maxWidth = maxWidth,
                sectionCount = sectionCount,
                dayCount = days,
                rowHeightScale = state.rowHeightScale,
            )
            val fitsOneScreen = layout.gridHeight + DayHeaderHeight <= maxHeight
            val systemFontScale = LocalDensity.current.fontScale
            // 网格字号：课名目标字号 dp（用户设置或跟随系统）→ 网格内统一倍率，换算规则见 GridFont
            val gridScale = GridFont.scaleFromDp(
                GridFont.resolveDp(systemFontScale, state.gridFontDp, days),
                days,
            )
            // 教室/教师：用户设置过目标 dp 时换算成卡片内的实际 sp——网格还套着 gridScale
            // 的密度倍率，预除一次才能让净渲染值等于目标 dp；未设置传 null，跟随课名等比（旧行为）
            val roomFontSp = state.gridRoomDp?.let {
                GridFont.resolveDetailDp(systemFontScale, it, days) / gridScale
            }
            val teacherFontSp = state.gridTeacherDp?.let {
                GridFont.resolveDetailDp(systemFontScale, it, days) / gridScale
            }
            // 时间轴 / 日期表头：与课名**完全解耦**。二者不在 gridScale 的语义范围内
            // （它们是定位参照，不是内容），但仍渲染在 GridTypography 提供的密度里，
            // 所以要把课名倍率除回去，使净渲染值只由自己的设置决定。
            //
            // 根因：这里不能写成 `state.gridRailDp?.let{...}`——用户没拖过滑块时结果是 null，
            // TimeRail 便回落到硬编码 sp，而那仍会被 gridScale 乘一遍，
            // 于是「调课名字号，时间轴/日期跟着变」的老问题在默认态下根本没被修掉。
            // 方案：无论用户是否设置过都解析出一个值——null 走 resolveXxxDp 的
            // 「基准 × 系统倍率」分支（跟随系统、与课名无关），设置过则取用户值。
            val railFontSp = GridFont.resolveRailDp(systemFontScale, state.gridRailDp, days) / gridScale
            val dateFontSp = GridFont.resolveDateDp(systemFontScale, state.gridDateDp, days) / gridScale

            val body: @Composable () -> Unit = {
                val ps = pagerState
                if (ps == null) {
                    // 初始化未完成：网格不渲染，避免 Pager 以第 1 周先出一帧（根因见 pagerState 注释）
                    Box(Modifier.fillMaxSize())
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
                    Box(Modifier.height(DayHeaderHeight + layout.gridHeight)) {
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
    if (displaySheetOpen) {
        DisplaySettingsOverlay(
            titled = state.timetableName,
            onDismiss = { displaySheetOpen = false },
            viewModel = viewModel(factory = MeViewModel.Factory(Graph.repository(context))),
        )
    }

    if (pickerOpen) {
        WeekPickerSheet(
            currentWeek = state.week,
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
            onDismiss = { importOpen = false },
        )
    }

    jsonPreview?.let { preview ->
        ImportTargetDialogHost(
            courses = preview.courses,
            title = "导入 JSON 课表",
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
}

/**
 * 把网格区域的字号倍率收进可排版区间。
 * 只包住网格本身——顶栏、弹层、空态提示仍跟随系统字体设置。
 */
@Composable
private fun GridTypography(fontScale: Float, content: @Composable () -> Unit) {
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
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val haptics = rememberAppHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
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

/**
 * 显示设置覆盖面板（替代 ModalBottomSheet，见调用点注释里的根因）。
 *
 * 结构：
 * - 全屏 Box 内先铺一层半透明遮罩（点击即关），再在底部放一块固定高度面板；
 * - 面板**没有**拖拽手势、也没有 sheet 的 nestedScroll 连接——它只是一个 Column，
 *   内部靠 DisplaySettingsContent 自己的 verticalScroll 滚动，滚动不会外泄成关闭动作；
 * - 系统返回由 [BackHandler] 接住，交给同一个关闭出口。
 */
@Composable
private fun DisplaySettingsOverlay(
    titled: String,
    viewModel: MeViewModel,
    onDismiss: () -> Unit,
) {
    val screenHeightDp = LocalConfiguration.current.screenHeightDp

    BackHandler { onDismiss() }

    Box(Modifier.fillMaxSize()) {
        // 遮罩：点它就关。用无波纹 clickable，避免整屏按下时出现大面积涟漪
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(
                    onClickLabel = "关闭显示设置",
                    role = Role.Button,
                    indication = null,
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    onClick = onDismiss,
                ),
        )
        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // 高度封顶 72%：上方留出至少四分之一屏的课表，改动才能即时被看见
                .heightIn(min = 220.dp, max = (screenHeightDp * 0.72f).dp),
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        ) {
            // 必须 fillMaxHeight：Card 只给了 heightIn 上界（不是固定高），
            // 内层 Column 不撑满的话 weight(1f) 拿不到确定高度，
            // 下面 fillMaxSize 的内容区会退化成「按内容量高」——面板高度随滚动内容跳变。
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "显示设置",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("完成") }
                }
                DisplaySettingsContent(
                    viewModel = viewModel,
                    headerNote = "课表级设置 · 「${titled.ifBlank { "—" }}」· 改动即时生效",
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun TimeRail(
    layout: GridLayout,
    slots: List<TimeSlot>,
    monthLabel: String?,
    railFontSp: Float?,
    dateFontSp: Float?,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    // 字号：用户设置过就用解析后的 sp（已预除课名倍率，净渲染值即目标 dp）；
    // 未设置时回落到本模块的固定基准档（原先的硬编码值），不再跟随课名缩放。
    val sectionSize = (railFontSp ?: 12.5f).sp
    val startSize = (railFontSp?.let { it * 0.76f } ?: 9.5f).sp
    val endSize = (railFontSp?.let { it * 0.72f } ?: 9f).sp
    Box(Modifier.width(RailWidth).fillMaxHeight()) {
        // 表头位置的月份角标：跟随所选周的周一所在月份，切周时跟着变
        if (monthLabel != null) {
            Box(
                Modifier
                    .width(RailWidth)
                    .height(DayHeaderHeight),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = monthLabel,
                    fontSize = (dateFontSp ?: 11f).sp,
                    fontWeight = FontWeight.Medium,
                    color = onSurface.copy(alpha = 0.5f),
                )
            }
        }
        for (section in 1..layout.sections) {
            val slot = slots.firstOrNull { it.number == section }
            Column(
                modifier = Modifier
                    .offset(y = DayHeaderHeight + layout.topOf(section))
                    .width(RailWidth)
                    .height(layout.rowHeight),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = section.toString(),
                    fontSize = sectionSize,
                    lineHeight = sectionSize * 1.12f,
                    fontWeight = FontWeight.SemiBold,
                    color = onSurface.copy(alpha = 0.82f),
                )
                if (slot != null) {
                    Text(
                        text = slot.startTime,
                        fontSize = startSize,
                        lineHeight = startSize * 1.26f,
                        color = onSurface.copy(alpha = 0.5f),
                        maxLines = 1,
                    )
                    Text(
                        text = slot.endTime,
                        fontSize = endSize,
                        lineHeight = endSize * 1.22f,
                        color = onSurface.copy(alpha = 0.32f),
                        maxLines = 1,
                    )
                }
            }
        }
        // 左侧当前时间胶囊已移除：时刻信息在每节的起止时间上已有，胶囊盖在节次文字上反而添乱；
        // 当前时刻只保留网格内今日列的线（见 WeekPage），一条线索就够了
    }
}

@Composable
private fun WeekPage(
    week: Int,
    layout: GridLayout,
    visibleDays: List<Int>,
    allCourses: List<Course>,
    slots: List<TimeSlot>,
    semester: SemesterConfig?,
    isTodayWeek: Boolean,
    todayDay: Int,
    now: LocalTimeLike,
    showNonCurrentWeek: Boolean,
    cellStyle: GridCellStyle,
    showNowLine: Boolean,
    showGridLines: Boolean,
    dateFontSp: Float?,
    tapBlankToAdd: Boolean,
    onAddEmpty: (day: Int, section: Int) -> Unit,
    onOpenCourse: (Course) -> Unit,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outlineVariant
    val haptics = rememberAppHaptics()

    // 列号一律由 visibleDays 的下标决定，不能用 day-1：
    // 单独隐藏周六后，周日在可见序列里是第 6 列（下标 5）而不是第 7 列。
    val days = visibleDays.size
    val weekCourses = remember(week, allCourses) {
        ScheduleCalculator.coursesInWeek(allCourses, week)
    }
    val otherWeekCourses = remember(week, allCourses) {
        allCourses.filter { week !in it.weeks }
    }
    val occupied = remember(weekCourses) {
        weekCourses.map { it.day to it.startSection }.toSet()
    }
    val todayVisible = isTodayWeek && todayDay in visibleDays

    Column(Modifier.fillMaxSize()) {
        // ---- 星期表头（WakeUp 式：周几取单字，下挂日期；今日整列加粗深色，不做圆底徽章——
        //      7 列窄列里徽章会把日期挤出对齐，今日列已有时刻线定位，不缺这一处强调）
        Row(
            Modifier
                .height(DayHeaderHeight)
                .fillMaxWidth(),
        ) {
            visibleDays.forEach { day ->
                val label = dayLabels[day - 1]
                val isToday = todayVisible && day == todayDay
                val date = weekDate(semester, week, day)
                Column(
                    Modifier
                        .width(layout.dayWidth)
                        .height(DayHeaderHeight),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = label.removePrefix("周"),
                        fontSize = (dateFontSp ?: 12f).sp,
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium,
                        color = if (isToday) onSurface else onSurface.copy(alpha = 0.5f),
                    )
                    Text(
                        text = date?.format(monthDayFmt) ?: "—",
                        fontSize = (dateFontSp?.let { it * 0.92f } ?: 11f).sp,
                        fontWeight = if (isToday) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isToday) onSurface else onSurface.copy(alpha = 0.4f),
                    )
                }
            }
        }

        Box(
            Modifier
                .width(layout.dayWidth * days)
                .height(layout.gridHeight),
        ) {
            // 今日列不再铺底色/边线：WakeUp 参考稿里今日只靠表头加粗 + 时刻线定位，
            // 铺底反而让当日卡片颜色被罩了一层，观感发闷

            // ---- 空位点击加课（显示设置可关：关掉后空白格只作留白，避免滑动/误触时弹编辑）
            if (tapBlankToAdd) {
                visibleDays.forEachIndexed { column, day ->
                    for (section in 1..layout.sections) {
                        if ((day to section) in occupied) continue
                        Box(
                            Modifier
                                .offset(
                                    x = layout.dayWidth * column,
                                    y = layout.topOf(section),
                                )
                                .width(layout.dayWidth)
                                .height(layout.bottomOf(section) - layout.topOf(section))
                                .clickable { onAddEmpty(day, section) },
                        )
                    }
                }
            }

            // ---- 行分隔：大节之间一条线，大节内一条极浅线（不画竖线）；显示设置可关
            if (showGridLines) {
                for (section in 1 until layout.sections) {
                    val isBigEnd = ScheduleCalculator.isBigSectionEnd(section)
                    val y = if (isBigEnd) {
                        layout.bottomOf(section) + InterGap / 2
                    } else {
                        layout.bottomOf(section) + IntraGap / 2
                    }
                    Box(
                        Modifier
                            .offset(y = y)
                            .width(layout.dayWidth * days)
                            .height(1.dp)
                            .background(
                                if (isBigEnd) outline.copy(alpha = 0.35f) else outline.copy(alpha = 0.16f),
                            ),
                    )
                }
            }

            // ---- 非本周灰态（默认关）
            if (showNonCurrentWeek) {
                val claimed = occupied.toMutableSet()
                otherWeekCourses
                    .sortedWith(compareBy({ it.day }, { it.startSection }, { it.name }))
                    .forEach { course ->
                        val column = ScheduleCalculator.columnOf(visibleDays, course.day)
                            ?: return@forEach
                        val span = (course.startSection..course.endSection).toList()
                        if (span.isEmpty()) return@forEach
                        if (span.any { (course.day to it) in claimed }) return@forEach
                        span.forEach { claimed += course.day to it }
                        GhostCourseCard(
                            name = course.name,
                            days = days,
                            cornerRadiusDp = cellStyle.cornerRadiusDp,
                            modifier = Modifier
                                .offset(
                                    x = layout.dayWidth * column + CellGap,
                                    y = layout.topOf(course.startSection) + CellGap,
                                )
                                .width(layout.dayWidth - CellGap * 2)
                                .height(layout.heightOf(course.startSection, course.endSection) - CellGap * 2),
                        )
                    }
            }

            // ---- 本周课程
            // 周内撞色兜底：学期级 16 色不够分时（理论 + 实验课混排），同一周里不同课名可能共用颜色，
            // 渲染时就地换成当周空闲色；onOpenCourse 仍传原始 course——存储色才是权威，避免编辑时把周内替色写回库
            val colorOverrides = remember(weekCourses) {
                ScheduleCalculator.weekColorOverrides(weekCourses)
            }
            weekCourses
                .sortedWith(compareBy({ it.day }, { it.startSection }))
                .forEach { course ->
                    val column = ScheduleCalculator.columnOf(visibleDays, course.day)
                        ?: return@forEach
                    val override = colorOverrides[course.name]
                    val display = if (override != null) course.copy(colorIndex = override) else course
                    val cardModifier = Modifier
                        .offset(
                            x = layout.dayWidth * column + CellGap,
                            y = layout.topOf(course.startSection) + CellGap,
                        )
                        .width(layout.dayWidth - CellGap * 2)
                        .height(
                            layout.heightOf(course.startSection, course.endSection) - CellGap * 2,
                        )
                    if (course.startSection == course.endSection) {
                        SingleSectionCard(
                            course = display,
                            days = days,
                            onClick = {
                                haptics.tap()
                                onOpenCourse(course)
                            },
                            modifier = cardModifier,
                            style = cellStyle,
                        )
                    } else {
                        GridCourseCard(
                            course = display,
                            days = days,
                            onClick = {
                                haptics.tap()
                                onOpenCourse(course)
                            },
                            modifier = cardModifier,
                            style = cellStyle,
                        )
                    }
                }

            // ---- 当前时刻线（显示设置可关）。列号同样走 visibleDays 下标。
            if (todayVisible && showNowLine) {
                val todayColumn = ScheduleCalculator.columnOf(visibleDays, todayDay) ?: 0
                // 终点收口到今日最后一节课：weekCourses 已是当前筛选口径，
                // 被筛掉的课不参与收口，避免「线还挂着、课却看不见」的错位
                val dayEnd = remember(weekCourses, slots, todayDay) {
                    ScheduleCalculator.dayLastEndMinutes(weekCourses, slots, todayDay)
                }
                nowMarker(slots, layout, now, dayEnd)?.let { marker ->
                    Box(
                        Modifier
                            .offset(x = layout.dayWidth * todayColumn, y = marker.offsetY)
                            .width(layout.dayWidth)
                            .height(1.5.dp)
                            .drawBehind {
                                if (marker.inBreak) {
                                    // 课间画虚线：位置贴在行边界上，虚线提示「这不在上课」
                                    val y = size.height / 2f
                                    drawLine(
                                        color = primary,
                                        start = Offset(0f, y),
                                        end = Offset(size.width, y),
                                        strokeWidth = size.height,
                                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 5f)),
                                    )
                                } else {
                                    drawRect(primary)
                                }
                            },
                    )
                    Box(
                        Modifier
                            .offset(
                                x = layout.dayWidth * todayColumn - 3.5.dp,
                                y = marker.offsetY - 2.5.dp,
                            )
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (marker.inBreak) primary.copy(alpha = 0.55f) else primary),
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyScheduleHint(
    filter: CourseFilter,
    onOpenJwImport: () -> Unit,
    tapBlankToAdd: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val filtered = filter != CourseFilter.All
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (filtered) "没有${filter.label}" else "课表为空",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = when {
                filtered -> "当前只显示「${filter.label}」。可在「显示设置」里切回全部。"
                // 关掉空白格加课后，提示必须同步改口，否则会指向一个不会发生的动作
                tapBlankToAdd -> "左右滑动切换周次，点空白格可加课。"
                else -> "左右滑动切换周次。空白格加课已在显示设置里关闭。"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            textAlign = TextAlign.Center,
        )
        // 筛选状态下的空网格不是「没导入」，不该出现导入引导
        if (!filtered) {
            TextButton(onClick = onOpenJwImport) { Text("从教务导入") }
        }
    }
}

private fun weekDate(semester: SemesterConfig?, week: Int, day: Int): LocalDate? {
    if (semester == null || week < 1 || day !in 1..7) return null
    return runCatching {
        val startMonday = ScheduleCalculator.startOfWeek(
            ScheduleCalculator.parseDate(semester.startDate),
        )
        startMonday
            .plusWeeks((week - 1).toLong())
            .plusDays((day - 1).toLong())
    }.getOrNull()
}
