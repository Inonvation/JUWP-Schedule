package edu.jxslu.schedule.ui.me

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.prefs.DisplayPrefs
import edu.jxslu.schedule.domain.Course
import edu.jxslu.schedule.domain.CourseFilter
import edu.jxslu.schedule.domain.GridFont
import edu.jxslu.schedule.domain.ScheduleCalculator
import edu.jxslu.schedule.ui.common.GhostCourseCard
import edu.jxslu.schedule.ui.common.GridCellStyle
import edu.jxslu.schedule.ui.common.GridCourseCard
import edu.jxslu.schedule.ui.common.SettingSwitchRow
import edu.jxslu.schedule.ui.common.SingleSectionCard
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import kotlin.math.roundToInt

/**
 * 课表显示设置（个性化）子页，交互对齐拾光 StyleSettingsScreen：
 * 上约 45% 为实时课表预览，下约 55% 为圆角设置卡（内部竖滑）。
 * 入口：「我的」→ 显示设置（带迷你预览的子页）；课表页眼睛 → 页内覆盖弹层
 * （[DisplaySettingsContent]，真实课表直接当预览，不再挤压/替换课表显示）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisplaySettingsScreen(
    onBack: () -> Unit,
    viewModel: MeViewModel = viewModel(
        factory = MeViewModel.Factory(Graph.repository(LocalContext.current)),
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val prefs = state.displayPrefs
    // 预览的列序列与真实课表同一口径（visibleDays），否则「隐藏周六」在预览里看不出效果
    val visibleDays = ScheduleCalculator.visibleDays(prefs.showSaturday, prefs.showSunday)
    val systemFontScale = LocalDensity.current.fontScale

    // 根因：迁到 SubpageActivity 独立窗口后没有外层 Scaffold 垫状态栏，
    // windowInsets 归零（嵌 NavHost 时期防双倍空白的老规避）会让顶栏顶进状态栏；
    // 现走 M3 默认——TopAppBar 自行消费状态栏，contentWindowInsets 管住手势条。
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("显示设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            DisplayPreview(
                courses = state.courses,
                currentWeek = state.currentWeek.coerceAtLeast(1),
                prefs = prefs,
                visibleDays = visibleDays,
                systemFontScale = systemFontScale,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.45f),
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.55f),
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                DisplaySettingsContent(
                    viewModel = viewModel,
                    headerNote = "课表级设置 · 「${state.timetableName.ifBlank { "—" }}」· 改动即时反映在上方预览",
                )
            }
        }
    }
}

/**
 * 显示设置的选项面板主体（字号 / 格子样式 / 内容开关）。
 *
 * 独立成函数的原因：两个宿主共用——
 * 1. 本子页（「我的」入口）下半卡内；
 * 2. 课表页眼睛的**覆盖弹层**（见 WeekScreen）：真实课表在上层保持可见，
 *    面板盖住下半屏，所有改动在真实网格上即时生效。
 *
 * [headerNote] 供两个宿主各自说明上下文；面板内部竖滑，高度由宿主约束。
 */
@Composable
fun DisplaySettingsContent(
    viewModel: MeViewModel,
    headerNote: String? = null,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val prefs = state.displayPrefs
    // 本面板两个宿主共用；列数口径必须与真实网格一致（见 ScheduleCalculator.visibleDays）
    val days = ScheduleCalculator.visibleDays(prefs.showSaturday, prefs.showSunday).size

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
    ) {
        if (headerNote != null) {
            Text(
                headerNote,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                modifier = Modifier.padding(top = 14.dp),
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
        }

        SectionLabel("字号")
        GridFontSizeRow(
            title = "课程名字号",
            subtitle = "课名目标字号，范围 8–14dp",
            dp = prefs.gridFontDp,
            baseSp = { GridFont.baseNameSp(days) },
            minDp = GridFont.MinDp,
            maxDp = GridFont.MaxDp,
            followHint = "跟随系统字体设置",
            onChange = viewModel::setGridFontDp,
        )
        GridFontSizeRow(
            title = "教室字号",
            subtitle = "色块内「@地点」；未设置时跟随课名缩放",
            dp = prefs.gridRoomDp,
            baseSp = { GridFont.baseDetailSp(days) },
            minDp = GridFont.MinDetailDp,
            maxDp = GridFont.MaxDetailDp,
            followHint = "跟随课名等比缩放",
            onChange = viewModel::setGridRoomDp,
        )
        GridFontSizeRow(
            title = "教师字号",
            subtitle = "色块内教师行；需开启「显示授课教师」",
            dp = prefs.gridTeacherDp,
            baseSp = { GridFont.baseDetailSp(days) },
            minDp = GridFont.MinDetailDp,
            maxDp = GridFont.MaxDetailDp,
            followHint = "跟随课名等比缩放",
            onChange = viewModel::setGridTeacherDp,
        )
        // 时间轴 / 日期表头独立成项。
        // 根因：它们此前和课名共用同一个 gridScale（由课程名字号换算），
        // 调课名会连带把左侧节次、时间、月份日期一起放大缩小——两处关注点不同，必须拆开。
        GridFontSizeRow(
            title = "时间轴字号",
            subtitle = "左侧节次号与起止时间；独立于课程名",
            dp = prefs.gridRailDp,
            baseSp = { GridFont.baseRailSp(days) },
            minDp = GridFont.MinRailDp,
            maxDp = GridFont.MaxRailDp,
            followHint = "跟随系统字体设置",
            onChange = viewModel::setGridRailDp,
        )
        GridFontSizeRow(
            title = "日期字号",
            subtitle = "顶部星期与日期、左上角月份；独立于课程名",
            dp = prefs.gridDateDp,
            baseSp = { GridFont.baseDateSp(days) },
            minDp = GridFont.MinRailDp,
            maxDp = GridFont.MaxRailDp,
            followHint = "跟随系统字体设置",
            onChange = viewModel::setGridDateDp,
        )

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SectionLabel("格子样式")
        SliderSettingRow(
            title = "格子高度",
            subtitle = "乘在自适应行高上",
            valueText = percentLabel(prefs.rowHeightScale.coerceIn(0.5f, 1.5f)),
            value = prefs.rowHeightScale.coerceIn(0.5f, 1.5f),
            valueRange = 0.5f..1.5f,
            onValueChange = { viewModel.setRowHeightScale(snapStep(it, 0.05f)) },
            onReset = { viewModel.setRowHeightScale(1f) },
            minText = "50%",
            maxText = "150%",
        )
        SliderSettingRow(
            title = "格子圆角",
            subtitle = "超过 12dp 会开始像胶囊",
            valueText = GridFont.dpLabel(prefs.cellRadiusDp.coerceIn(0f, 12f)),
            value = prefs.cellRadiusDp.coerceIn(0f, 12f),
            valueRange = 0f..12f,
            onValueChange = { viewModel.setCellRadiusDp(snapStep(it, 0.5f)) },
            onReset = { viewModel.setCellRadiusDp(6f) },
            minText = "0dp",
            maxText = "12dp",
        )
        SliderSettingRow(
            title = "格子不透明度",
            subtitle = "50% 是白字可读边界",
            valueText = percentLabel(prefs.cellOpacity.coerceIn(0.5f, 1f)),
            value = prefs.cellOpacity.coerceIn(0.5f, 1f),
            valueRange = 0.5f..1f,
            onValueChange = { viewModel.setCellOpacity(snapStep(it, 0.05f)) },
            onReset = { viewModel.setCellOpacity(1f) },
            minText = "50%",
            maxText = "100%",
        )

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SectionLabel("内容与开关")
        SettingSwitchRow(
            title = "文字水平居中",
            subtitle = "关 = 靠左对齐（WakeUp 式）",
            checked = prefs.cellCenterH,
            onCheckedChange = viewModel::setCellCenterH,
        )
        SettingSwitchRow(
            title = "文字竖直居中",
            subtitle = "关 = 顶部起排、教师沉底",
            checked = prefs.cellCenterV,
            onCheckedChange = viewModel::setCellCenterV,
        )
        SettingSwitchRow(
            title = "显示授课教师",
            subtitle = "关闭后只显示课名与地点",
            checked = prefs.showTeacher,
            onCheckedChange = viewModel::setShowTeacher,
        )
        SettingSwitchRow(
            title = "显示时刻线",
            subtitle = "今日列上的当前时间指示线",
            checked = prefs.showNowLine,
            onCheckedChange = viewModel::setShowNowLine,
        )
        SettingSwitchRow(
            title = "显示虚线描边",
            subtitle = "色块四周白色虚线框",
            checked = prefs.showCellBorder,
            onCheckedChange = viewModel::setShowCellBorder,
        )
        SettingSwitchRow(
            title = "显示网格辅助线",
            subtitle = "行与行之间的浅色分隔线",
            checked = prefs.showGridLines,
            onCheckedChange = viewModel::setShowGridLines,
        )
        SettingSwitchRow(
            title = "地点显示「@」",
            subtitle = "关掉只显示地点本身，窄列能多排一个字",
            checked = prefs.showAtSign,
            onCheckedChange = viewModel::setShowAtSign,
        )
        SettingSwitchRow(
            title = "点空白格新建课程",
            subtitle = "关掉后空白格只作留白，防滑动时误触",
            checked = prefs.tapBlankToAdd,
            onCheckedChange = viewModel::setTapBlankToAdd,
        )
        // 周末拆成两项（而非原来的单一「显示周六、周日」）：
        // 根因：一个布尔只能表达「都显示 / 都不显示」，
        // 而实际存在「周六有课、周日无课」这类课表，用户希望只留有用的那一列。
        SettingSwitchRow(
            title = "显示周六",
            subtitle = "关闭后该列隐藏，其余列自动变宽",
            checked = prefs.showSaturday,
            onCheckedChange = viewModel::setShowSaturday,
        )
        SettingSwitchRow(
            title = "显示周日",
            subtitle = "关闭后该列隐藏，其余列自动变宽",
            checked = prefs.showSunday,
            onCheckedChange = viewModel::setShowSunday,
        )
        SettingSwitchRow(
            title = "显示非本周课程",
            subtitle = "空闲格子用灰态标出本周不上的课",
            checked = prefs.showNonCurrentWeek,
            onCheckedChange = viewModel::setShowNonCurrentWeek,
        )
        CourseFilterRow(filter = prefs.courseFilter, onChange = viewModel::setCourseFilter)
    }
}

/** 星期字面量，下标 = day - 1。预览表头按 visibleDays 取此表，不能用列序号直接当星期号。 */
private val WEEK_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")

/**
 * 预览里节次号 / 日期表头的小字号基准（sp）。
 * 取改动前的实际值：节次号原为硬编码 7sp，表头原用 labelSmall（M3 为 11sp）——
 * 保持一致才能在默认态下做到「视觉零变化」。
 */
private const val PREVIEW_RAIL_SP = 7f
private const val PREVIEW_DATE_SP = 11f

/** 预览用演示课（课表为空时）。不写库。day 只会落在可见列里，避免隐藏周末后演示课凭空消失。 */
private fun demoCourses(visibleDays: List<Int>): List<Course> {
    if (visibleDays.isEmpty()) return emptyList()
    fun pick(i: Int) = visibleDays[i % visibleDays.size]
    return listOf(
        Course(
            id = -1, name = "高等数学", teacher = "张老师",
            position = "教学南大楼(南B302)", day = pick(0),
            startSection = 1, endSection = 2, weeks = setOf(1), colorIndex = 0,
        ),
        Course(
            id = -2, name = "大学物理", teacher = "李老师",
            position = "教学北楼(北A105)", day = pick(1),
            startSection = 3, endSection = 4, weeks = setOf(1), colorIndex = 3,
        ),
        Course(
            id = -3, name = "程序设计基础", teacher = "王老师",
            position = "机房(南C401)", day = pick(3),
            startSection = 5, endSection = 6, weeks = setOf(1), colorIndex = 7,
        ),
    )
}

/** 上半区实时预览：迷你周网格，样式随滑块即时变。 */
@Composable
private fun DisplayPreview(
    courses: List<Course>,
    currentWeek: Int,
    prefs: DisplayPrefs,
    visibleDays: List<Int>,
    systemFontScale: Float,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val outline = MaterialTheme.colorScheme.outlineVariant
    val onSurface = MaterialTheme.colorScheme.onSurface

    val days = visibleDays.size.coerceAtLeast(1)
    val real = remember(courses, currentWeek, visibleDays) {
        ScheduleCalculator.coursesInWeek(courses, currentWeek)
            .filter { it.day in visibleDays }
    }
    val ghosts = remember(courses, currentWeek, prefs.showNonCurrentWeek, visibleDays) {
        if (!prefs.showNonCurrentWeek) emptyList()
        else courses.filter { currentWeek !in it.weeks && it.day in visibleDays }.take(2)
    }
    val display = if (real.isNotEmpty()) real else demoCourses(visibleDays)

    val cellStyle = GridCellStyle(
        cornerRadiusDp = prefs.cellRadiusDp.coerceIn(0f, 12f),
        opacity = prefs.cellOpacity.coerceIn(0.5f, 1f),
        centerHorizontal = prefs.cellCenterH,
        centerVertical = prefs.cellCenterV,
        showTeacher = prefs.showTeacher,
        showBorder = prefs.showCellBorder,
        showAtSign = prefs.showAtSign,
    )
    val gridScale = GridFont.scaleFromDp(
        GridFont.resolveDp(systemFontScale, prefs.gridFontDp, days),
        days,
    )
    val roomFontSp = prefs.gridRoomDp?.let {
        GridFont.resolveDetailDp(systemFontScale, it, days) / gridScale
    }
    val teacherFontSp = prefs.gridTeacherDp?.let {
        GridFont.resolveDetailDp(systemFontScale, it, days) / gridScale
    }
    val style = cellStyle.copy(roomFontSp = roomFontSp, teacherFontSp = teacherFontSp)

    // 时间轴 / 日期字号：预览是缩小版（栏宽 28dp / 表头 22dp），不能直接用真实 sp，
    // 改用「相对基准的倍率」缩放预览自己的小字号，拖滑块时才有可见反馈。
    //
    // 两个关键点，写错就是静默的尺寸偏差：
    // 1. 分母是 [baseSp × 系统倍率]，即「未设置时」的解析结果——倍率在默认态恰好为 1，
    //    渲染尺寸与改动前完全一致；若只除以 baseSp，系统字体放大时会变成倍率的平方。
    // 2. 要再除以 gridScale。本预览的密度是 `density.fontScale * gridScale`（**相乘**，
    //    与真实网格 GridTypography 的「替换成 gridScale」不同），
    //    不抵消掉课名倍率，时间轴/日期就会继续跟着课名滑块一起变——正是要修掉的老问题。
    val railRatio = GridFont.resolveRailDp(systemFontScale, prefs.gridRailDp, days) /
        (GridFont.baseRailSp(days) * systemFontScale)
    val dateRatio = GridFont.resolveDateDp(systemFontScale, prefs.gridDateDp, days) /
        (GridFont.baseDateSp(days) * systemFontScale)
    val railPreviewSp = PREVIEW_RAIL_SP * railRatio / gridScale
    val datePreviewSp = PREVIEW_DATE_SP * dateRatio / gridScale

    val rowH = 16.dp
    val intra = 2.dp
    val inter = 4.dp
    val cellGap = 1.dp
    val headerH = 22.dp
    val railW = 28.dp

    // 11 行几何
    val rowTops = ArrayList<Dp>(11)
    val rowBottoms = ArrayList<Dp>(11)
    run {
        var y = 0.dp
        for (s in 1..11) {
            rowTops += y
            y += rowH * prefs.rowHeightScale.coerceIn(0.5f, 1.5f)
            rowBottoms += y
            if (s < 11) y += if (ScheduleCalculator.isBigSectionEnd(s)) inter else intra
        }
    }
    val gridH = rowBottoms.last()

    Box(
        modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
    ) {
        // 字号倍率只作用在预览网格内
        CompositionLocalProvider(
            LocalDensity provides Density(density.density, density.fontScale * gridScale),
        ) {
            Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp)) {
                // 星期表头
                Row(Modifier.fillMaxWidth().height(headerH)) {
                    Spacer(Modifier.width(railW))
                    // 表头按 visibleDays 取星期号：隐藏周六后周日必须落在第 6 列而不是被截断
                    visibleDays.forEach { day ->
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            Text(
                                text = WEEK_LABELS.getOrNull(day - 1) ?: "",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = datePreviewSp.sp,
                                color = onSurface.copy(alpha = 0.65f),
                            )
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().height(gridH)) {
                    // 左侧节次号
                    Box(Modifier.width(railW).height(gridH)) {
                        for (s in 1..11) {
                            Text(
                                text = s.toString(),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = railPreviewSp.sp),
                                color = onSurface.copy(alpha = 0.4f),
                                modifier = Modifier
                                    .padding(start = 4.dp)
                                    .align(Alignment.TopStart)
                                    .padding(top = rowTops[s - 1]),
                            )
                        }
                    }
                    // 网格
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        if (prefs.showGridLines) {
                            for (s in 1 until 11) {
                                val isBigEnd = ScheduleCalculator.isBigSectionEnd(s)
                                val yLine =
                                    if (isBigEnd) rowBottoms[s - 1] + inter / 2
                                    else rowBottoms[s - 1] + intra / 2
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(top = yLine)
                                        .height(1.dp)
                                        .background(
                                            outline.copy(alpha = if (isBigEnd) 0.35f else 0.16f),
                                        ),
                                )
                            }
                        }
                        // 课块：按「列格子 + 行偏移」定位
                        Column(Modifier.fillMaxSize()) {
                            // 先用 Box 按绝对位置铺课程（每门课画在对应 day 列）
                            // 为简化，用 Row(7列) × Column(11行) 的占位格
                            Row(Modifier.fillMaxSize()) {
                                visibleDays.forEach { day ->
                                    Box(Modifier.weight(1f).fillMaxHeight()) {
                                        val dayCourses = display.filter { it.day == day }
                                        dayCourses.forEach { c ->
                                            if (c.startSection < 1 || c.startSection > 11) return@forEach
                                            val top = rowTops[c.startSection - 1]
                                            val h = (rowBottoms.getOrElse(c.endSection - 1) { rowTops.last() }
                                                - rowTops[c.startSection - 1]) - cellGap * 2
                                            if (h.value <= 0f) return@forEach
                                            Box(
                                                Modifier
                                                    .fillMaxWidth()
                                                    .padding(top = top + cellGap)
                                                    .height(h),
                                            ) {
                                                if (c.startSection == c.endSection) {
                                                    SingleSectionCard(
                                                        course = c,
                                                        days = days,
                                                        onClick = {},
                                                        modifier = Modifier.fillMaxSize().padding(cellGap),
                                                        style = style,
                                                    )
                                                } else {
                                                    GridCourseCard(
                                                        course = c,
                                                        days = days,
                                                        onClick = {},
                                                        modifier = Modifier.fillMaxSize().padding(cellGap),
                                                        style = style,
                                                    )
                                                }
                                            }
                                        }
                                        if (prefs.showNonCurrentWeek) {
                                            ghosts.filter { it.day == day }.forEach { c ->
                                                if (c.startSection < 1 || c.startSection > 11) return@forEach
                                                val top = rowTops[c.startSection - 1]
                                                val h = (rowBottoms.getOrElse(c.endSection - 1) { rowTops.last() }
                                                    - rowTops[c.startSection - 1]) - cellGap * 2
                                                if (h.value <= 0f) return@forEach
                                                Box(
                                                    Modifier
                                                        .fillMaxWidth()
                                                        .padding(top = top + cellGap)
                                                        .height(h),
                                                ) {
                                                    GhostCourseCard(
                                                        name = c.name,
                                                        days = days,
                                                        cornerRadiusDp = style.cornerRadiusDp,
                                                        modifier = Modifier.fillMaxSize(),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

private fun percentLabel(v: Float): String = "${(v * 100).roundToInt()}%"

private fun snapStep(value: Float, step: Float): Float =
    (value / step).roundToInt() * step

@Composable
private fun CourseFilterRow(
    filter: CourseFilter,
    onChange: (CourseFilter) -> Unit,
) {
    val haptics = rememberAppHaptics()
    Column(Modifier.padding(vertical = 12.dp)) {
        Text("显示哪些课程", style = MaterialTheme.typography.bodyLarge)
        Text(
            "默认全部；实验课与理论课可分开看",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            modifier = Modifier.padding(top = 2.dp),
        )
        Row(
            modifier = Modifier.padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CourseFilter.entries.forEach { option ->
                FilterChip(
                    selected = option == filter,
                    onClick = {
                        haptics.toggle()
                        onChange(option)
                    },
                    label = { Text(option.label) },
                )
            }
        }
    }
}

@Composable
private fun GridFontSizeRow(
    title: String,
    subtitle: String,
    dp: Float?,
    baseSp: () -> Float,
    minDp: Float,
    maxDp: Float,
    followHint: String,
    onChange: (Float?) -> Unit,
) {
    val systemFontScale = LocalDensity.current.fontScale
    val effective = (dp ?: baseSp() * systemFontScale).coerceIn(minDp, maxDp)
    Column(Modifier.padding(vertical = 10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.weight(1f))
            Text(
                GridFont.dpLabel(effective),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            modifier = Modifier.padding(top = 2.dp),
        )
        Slider(
            value = effective,
            onValueChange = { onChange(GridFont.snapDp(it)) },
            valueRange = minDp..maxDp,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                GridFont.dpLabel(minDp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            )
            Spacer(Modifier.weight(1f))
            if (dp == null) {
                Text(
                    followHint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                )
            } else {
                TextButton(onClick = { onChange(null) }) {
                    Text("跟随", style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                GridFont.dpLabel(maxDp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            )
        }
    }
}

@Composable
private fun SliderSettingRow(
    title: String,
    subtitle: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    minText: String,
    maxText: String,
    onReset: (() -> Unit)? = null,
) {
    Column(Modifier.padding(vertical = 10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.weight(1f))
            if (onReset != null) {
                TextButton(
                    onClick = onReset,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.height(28.dp),
                ) { Text("重置", style = MaterialTheme.typography.labelMedium) }
            }
            Text(
                valueText,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            modifier = Modifier.padding(top = 2.dp),
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                minText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            )
            Spacer(Modifier.weight(1f))
            Text(
                maxText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            )
        }
    }
}
