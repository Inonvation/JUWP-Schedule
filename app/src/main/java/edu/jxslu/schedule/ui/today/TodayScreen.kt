package edu.jxslu.schedule.ui.today

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.R
import edu.jxslu.schedule.domain.Course
import edu.jxslu.schedule.domain.ScheduleCalculator
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
import edu.jxslu.schedule.ui.common.CourseEditSheet
import edu.jxslu.schedule.ui.common.DeleteConfirmDialog
import edu.jxslu.schedule.ui.common.EmptyHint
import edu.jxslu.schedule.ui.common.ShortcutIcon
import edu.jxslu.schedule.ui.common.ShortcutLauncher
import edu.jxslu.schedule.ui.common.ShortcutPinner
import edu.jxslu.schedule.ui.common.WaterUnlockButton
import edu.jxslu.schedule.ui.common.courseColor
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import edu.jxslu.schedule.ui.common.rememberWaterRequireDoubleClick
import edu.jxslu.schedule.ui.water.WaterViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Droplet
import me.rerere.hugeicons.stroke.Edit02
import me.rerere.hugeicons.stroke.Link01
import edu.jxslu.schedule.domain.MONTH_DAY_FORMAT

/**
 * 今日课表。
 *
 * 页面只有一条骨架：**顶部焦点卡（正在上课 / 下一节） + 一列时间轴课程行**。
 * - 焦点课只出现一次：焦点卡拿走第一门课，[TodayState.listCourses] 已把该课剔除，
 *   旧版「状态卡 + 列表首项」显示同一节课的问题不复存在。
 * - 时间只在行首出现一次（`10:15`），行内不再重复「第 N 节」与时刻——
 *   节次编号只在焦点卡标题里出现（`正在上课 · 第3-4节`）。
 * - 已结束的课不显示（保持既有取舍）；今天没有待上课程时才轮到「明天」上桌。
 *
 * 分区顺序：焦点卡 → 今天还有 N 节 → 明天（今天结束后）→ 快捷（开水）→ 快捷方式行（§3.8）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    onOpenJwImport: () -> Unit = {},
    /** 「尚未开学」空态的 CTA：跳课表设置（学期起止） */
    onOpenTimetableSettings: () -> Unit = {},
    /** 胖乖已登录时显示一键开水卡（DESIGN 3.3）；由外层传入登录态 */
    showWaterEntry: Boolean = false,
    onOpenWater: () -> Unit = {},
    /** 快捷方式设置页入口（chip 长按触发，DESIGN §3.8） */
    onOpenShortcuts: () -> Unit = {},
    /** 与开水页共享的 Activity 作用域实例；快捷卡的解锁进度两页一致 */
    waterViewModel: WaterViewModel? = null,
    viewModel: TodayViewModel = viewModel(
        factory = TodayViewModel.Factory(Graph.repository(LocalContext.current)),
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val shortcuts by viewModel.shortcuts.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Course?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Course?>(null) }
    val snackbar = remember { SnackbarHostState() }

    // 快捷方式拉起失败的兜底通道（DESIGN §3.8）：Snackbar 带「去设置」动作，
    // 比纯 Toast 多一步「就地修正配置」的出口（菜鸟 Activity 改名这类配置失效场景）
    val scope = rememberCoroutineScope()
    val showShortcutError: (String) -> Unit = { message ->
        scope.launch {
            val result = snackbar.showSnackbar(
                message,
                actionLabel = "去设置",
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) onOpenShortcuts()
        }
    }

    // 「还剩 X 分钟」要跟着时间走
    LaunchedEffect(Unit) {
        while (true) {
            viewModel.refreshTick()
            delay(30_000)
        }
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

    val openEditor: (Course?) -> Unit = { course ->
        editing = course
        editorOpen = true
    }

    Scaffold(
        // 顶部 inset 由外层消费（防顶栏双倍空白）；底部导航栏 inset 已由外层底栏
        // 高度提供，内层 contentWindowInsets 归零防底部双倍空白。
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                // 根因：外层 JuwApp Scaffold 无 topBar，contentWindowInsets（systemBars）已垫了一个
                // 状态栏高度；TopAppBar 默认 windowInsets 再消费一次 → 顶栏上方双倍空白。
                // 顶部 inset 统一只由外层消费，这里归零。
                windowInsets = WindowInsets(0, 0, 0, 0),
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
                // 一键开水入口不挂顶栏：列表尾部快捷卡整体可点进开水页，顶栏图标重复
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when {
            state.loading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "正在读取本机课表",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    )
                }
            }

            !state.inTerm -> TodayEmptyContent(
                padding,
                "尚未开学或未配置学期",
                "先在「课表设置」里填好开学日期与周数，也能手动加课。",
                actionLabel = "去设置学期",
                onAction = onOpenTimetableSettings,
                shortcuts = shortcuts,
                onOpenShortcuts = onOpenShortcuts,
                onShortcutError = showShortcutError,
            )

            state.totalCourseCount == 0 -> TodayEmptyContent(
                padding,
                "课表为空",
                "课表默认为空，请登录教务系统导入「学期理论课表」，也可手动加课。",
                actionLabel = "从教务导入",
                onAction = onOpenJwImport,
                shortcuts = shortcuts,
                onOpenShortcuts = onOpenShortcuts,
                onShortcutError = showShortcutError,
            )

            else -> TodayContent(
                state = state,
                padding = padding,
                onEdit = openEditor,
                waterEntry = if (showWaterEntry && waterViewModel != null) {
                    { WaterQuickEntry(waterViewModel, onOpenWater) }
                } else {
                    null
                },
                shortcuts = shortcuts,
                onOpenShortcuts = onOpenShortcuts,
                onShortcutError = showShortcutError,
            )
        }
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
}

/**
 * 空态（未开学 / 课表为空）：居中提示 + 可选的快捷方式行。
 * 快捷行在空态也上桌（DESIGN §3.8）——假期恰是取件码高频时段，课表为空不等于入口该消失。
 */
@Composable
private fun TodayEmptyContent(
    padding: PaddingValues,
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    shortcuts: ShortcutSettings = ShortcutSettings(),
    onOpenShortcuts: () -> Unit = {},
    onShortcutError: (String) -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
    ) {
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            EmptyHint(title, body, actionLabel, onAction)
        }
        if (shortcuts.enabled && shortcuts.items.isNotEmpty()) {
            ShortcutQuickRow(shortcuts.items, onOpenShortcuts, onShortcutError)
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun TodayContent(
    state: TodayState,
    padding: PaddingValues,
    onEdit: (Course) -> Unit,
    /** 胖乖一键开水快捷卡（已登录时非 null）；放列表尾部、课程内容之后 */
    waterEntry: (@Composable () -> Unit)? = null,
    /** 今日页快捷方式（DESIGN §3.8）：开水卡下方一行横滑 chips */
    shortcuts: ShortcutSettings = ShortcutSettings(),
    onOpenShortcuts: () -> Unit = {},
    onShortcutError: (String) -> Unit = {},
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
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
                    fadeIn(tween(220)) togetherWith fadeOut(tween(150))
                },
                label = "todayFocus",
            ) { f ->
                if (f == null) DoneBlock(state) else FocusCard(state, f, onEdit)
            }
        }

        // 「今天还有」只列焦点之外的课；正在上的课已在焦点卡里，不重复出现。
        // 焦点课若是当天唯一剩余（focus 取走它后列表为空），整段标题也不出现——
        // 「今天还有 1 节」下面空着比不显示更费解。
        if (state.listCourses.isNotEmpty()) {
            item(key = "remaining-header") {
                SectionLabel("今天还有 ${state.remaining.size} 节")
            }
            items(state.listCourses, key = { it.id }) { course ->
                CourseTimelineRow(
                    course = course,
                    slots = state.slots,
                    onClick = { onEdit(course) },
                    // 删除/新增课程时列表项平滑进出场，不再整列硬跳
                    modifier = Modifier.animateItem(),
                )
            }
        }

        // 明天只在今天没有待上课程（上完 / 没课）时上桌，今天的信息优先
        if (state.tomorrowVisible) {
            item(key = "tomorrow") { TomorrowBlock(state, onEdit) }
        }

        // 开水快捷卡放列表尾部：课程内容优先，卡片整体可点进开水页
        if (waterEntry != null) {
            item(key = "water") {
                Box(Modifier.padding(top = 20.dp)) { waterEntry() }
            }
        }

        // 快捷方式行（DESIGN §3.8）：开水卡下方；开关关着或列表为空时不占位
        if (shortcuts.enabled && shortcuts.items.isNotEmpty()) {
            item(key = "shortcuts") {
                Box(Modifier.padding(top = 12.dp)) {
                    ShortcutQuickRow(shortcuts.items, onOpenShortcuts, onShortcutError)
                }
            }
        }
    }
}

/**
 * 顶部焦点卡：正在上的课，或今天下一节。
 *
 * 与旧版「状态区」的差别：焦点课**从列表里拿走**（不再两处重复）；正在上课时补一条
 * 整门课进度条——「还剩 25 分钟」与「一共 80 分钟」是两件事，进度条把后者也交代了。
 * 点卡片进编辑（与列表行同一动作）。
 */
@Composable
private fun FocusCard(
    state: TodayState,
    focus: Course,
    onEdit: (Course) -> Unit,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val primary = MaterialTheme.colorScheme.primary
    val ongoing = state.ongoing != null
    val haptics = rememberAppHaptics()

    val countdown: String? = if (ongoing) {
        state.ongoingCountdown
            ?: state.minutesToOngoingEnd?.let { "还有 $it 分钟下课" }
    } else {
        val minutes = state.minutesToNext
        when {
            minutes == null -> null
            minutes <= 60 -> "还有 $minutes 分钟上课"
            else -> "${clockOf(state.slots, focus)} 上课"
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(14.dp))
            .background(primary.copy(alpha = 0.08f))
            .clickable {
                haptics.tap()
                onEdit(focus)
            },
    ) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(primary),
        )
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 13.dp, vertical = 11.dp),
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
                color = onSurface.copy(alpha = 0.62f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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

/** 今天结束后的落点：上完课 / 本来就没课。 */
@Composable
private fun DoneBlock(state: TodayState) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (state.todayAllDone) "今天的课都上完了" else "今天没有课",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (state.todayAllDone) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "今天共 ${state.todayTotal} 节",
                style = MaterialTheme.typography.bodySmall,
                color = onSurface.copy(alpha = 0.55f),
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
    )
}

/**
 * 时间轴课程行：行首时刻（`10:15`）+ 课程色卡。
 *
 * 行内只在副行放「@地点 · 教师」——时刻在行首、节次在焦点卡，重复摆放是旧版显乱的主因；
 * 整行高度因此从约 110dp 收到约 60dp，4 节课一屏放得下。
 * **整行**可点进编辑（含时刻列，避免"点了没反应"），无行内删除（删除只在编辑 Sheet 二级操作）。
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
            .clickable(onClickLabel = "编辑课程") {
                haptics.tap()
                onClick()
            }
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = clockOf(slots, course),
            style = MaterialTheme.typography.labelLarge,
            color = onSurface.copy(alpha = 0.72f),
            modifier = Modifier
                .width(50.dp)
                // 与色卡内课名同一起排（色卡上下内边距 9dp），两列文字视觉对齐
                .padding(top = 9.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(accent.copy(alpha = 0.16f))
                .padding(horizontal = 11.dp, vertical = 9.dp),
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
    onEdit: (Course) -> Unit,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 22.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "明天 · 周${dayLabel(state.tomorrowDay)}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (state.tomorrowCourses.isEmpty()) {
                    "没有课"
                } else {
                    "${state.tomorrowCourses.size} 节"
                },
                style = MaterialTheme.typography.bodySmall,
                color = onSurface.copy(alpha = 0.5f),
            )
        }
        if (state.tomorrowCourses.isEmpty()) {
            // 标题行已交代「没有课」，这里只补一句收尾，不再复述一遍
            Spacer(Modifier.height(6.dp))
            Text(
                text = "可以放松一下",
                style = MaterialTheme.typography.bodySmall,
                color = onSurface.copy(alpha = 0.55f),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            return@Column
        }
        Spacer(Modifier.height(6.dp))
        state.tomorrowCourses.forEach { course ->
            CourseTimelineRow(
                course = course,
                slots = state.slots,
                onClick = { onEdit(course) },
            )
        }
    }
}

// monthDayFmt 已收拢为 domain/TodayFormat.kt 的 MONTH_DAY_FORMAT（与调课页共用）


/**
 * 一键开水快捷卡：与开水页共享同一 ViewModel，点「开水」直接走解锁流程，
 * 进度/结果原地显示；点卡片其余位置进开水页（选设备、看订单详情）。
 * 开水按钮支持单击/双击（全局偏好，默认双击）；进行中禁点防重复解锁（VM 内另有 Mutex 兜底）。
 *
 * 样式用 1dp 描边而不是主色底：焦点卡已是主色底，两个同款色块一个是信息一个是动作，
 * 分不清哪个能点。
 */
@Composable
private fun WaterQuickEntry(vm: WaterViewModel, onOpen: () -> Unit) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val flow = state.flow
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val shape = RoundedCornerShape(14.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(enabled = flow is UnlockFlowState.Idle) { onOpen() }
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
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
                text = when (flow) {
                    is UnlockFlowState.Idle -> "一键开水 · " +
                        (state.selectedDevice?.goodsName?.ifBlank { "未命名设备" } ?: "未选择设备")
                    is UnlockFlowState.PreChecking -> flow.step
                    is UnlockFlowState.Working -> "正在出水 ${waterClock(flow.elapsedSeconds)}"
                    is UnlockFlowState.Success -> "开水成功 · 花费 ¥${calculateActualCost(flow.result)}"
                    is UnlockFlowState.Failed -> "开水失败 · ${flow.message}"
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (flow is UnlockFlowState.Idle) {
                Text(
                    text = if (rememberWaterRequireDoubleClick()) {
                        "双击「开水」出水防误触，点卡片管理设备与订单"
                    } else {
                        "点「开水」立即出水，点卡片管理设备与订单"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = onSurface.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        when (flow) {
            is UnlockFlowState.Idle -> WaterUnlockButton(
                enabled = state.selectedDevice != null,
                onUnlock = { vm.unlock() },
            )
            is UnlockFlowState.PreChecking, is UnlockFlowState.Working ->
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            is UnlockFlowState.Success ->
                TextButton(onClick = { vm.dismissFlow() }) { Text("完成") }
            is UnlockFlowState.Failed ->
                TextButton(onClick = { vm.unlock() }) { Text("重试") }
        }
    }
}

private fun waterClock(totalSeconds: Int): String {
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return "%02d:%02d".format(m, s)
}

/**
 * 快捷方式行（DESIGN §3.8）：横向滚动的入口 chips，放开水卡下方（空态也显示）。
 * 点击立即拉起（执行层与错误口径见 [ShortcutLauncher]，失败走 [onShortcutError] 的
 * Snackbar 兜底，不做预检确认）；长按弹菜单：添加到桌面 / 快捷方式设置。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShortcutQuickRow(
    items: List<ShortcutItem>,
    onOpenSettings: () -> Unit,
    onShortcutError: (String) -> Unit,
) {
    val context = LocalContext.current
    val haptics = rememberAppHaptics()
    val shape = RoundedCornerShape(14.dp)
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items, key = { it.id }) { item ->
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                Row(
                    modifier = Modifier
                        .clip(shape)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                        .combinedClickable(
                            onClick = {
                                haptics.tap()
                                ShortcutLauncher.launch(context, item)?.let(onShortcutError)
                            },
                            onLongClick = {
                                haptics.tap()
                                menuOpen = true
                            },
                        )
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                ) {
                    ShortcutIcon(item, Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("添加到桌面") },
                        leadingIcon = { Icon(HugeIcons.Link01, null, modifier = Modifier.size(18.dp)) },
                        onClick = {
                            menuOpen = false
                            haptics.tap()
                            // 钉桌面会弹系统确认框，成功无需再提示；失败（桌面不支持等）Toast
                            ShortcutPinner.pin(context, item)?.let { message ->
                                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
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
        }
    }
}
