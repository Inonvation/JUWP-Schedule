package edu.jxslu.schedule.ui.homework

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.domain.Course
import edu.jxslu.schedule.domain.Homework
import edu.jxslu.schedule.domain.HomeworkCourseGroup
import edu.jxslu.schedule.domain.courseHomeworkOrder
import edu.jxslu.schedule.domain.homeworkDisplayTitle
import edu.jxslu.schedule.ui.common.AppCard
import edu.jxslu.schedule.ui.common.AppCardDivider
import edu.jxslu.schedule.ui.common.AppNoticeVisuals
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.EmptyHint
import edu.jxslu.schedule.ui.common.ImageViewerDialog
import edu.jxslu.schedule.ui.common.LoadingHint
import edu.jxslu.schedule.ui.common.NoticeTone
import edu.jxslu.schedule.ui.common.SectionHeader
import edu.jxslu.schedule.ui.common.courseTint
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import edu.jxslu.schedule.ui.common.showNotice
import edu.jxslu.schedule.ui.reminder.ClassReminder
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowDown01
import java.time.LocalDate

/**
 * 作业库（我的 → 学习 → 作业，DESIGN §3.11）：**按课程折叠分组**（2026-09-23 改）。
 *
 * 每门课程一张 [AppCard]：头行 = 课程色点 + 课程名 + 未完成计数，点行展开/收起
 * 该课程的作业列表（AnimatedVisibility：高度展开/收起 + 淡入淡出，250ms/200ms）。
 * 展开区条目是**详情块**（2026-10-21）：标题 + 截止 chip + 完整 Markdown 正文 +
 * 图片缩略图，行间走卡内细线；完成项单独一段（「已完成 · N」）。
 * 头行右侧「＋」直接进该课程的新建页；条目右侧铅笔直达编辑态。
 * 展开时按培养方案同款 [scrollToReveal] 补足下方空间，避免「点了没反应」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeworkLibraryScreen(
    onBack: () -> Unit,
    onOpenHomework: (courseName: String, id: Long, startEditing: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val today = LocalDate.now()
    val repo = remember { Graph.homeworkRepository(context) }
    val scheduleRepo = remember { Graph.repository(context) }
    val groupsState by repo.observeGroups().collectAsStateWithLifecycle(initialValue = null)
    val allItems by repo.observeAll().collectAsStateWithLifecycle(initialValue = null)
    val courses by scheduleRepo.courses.collectAsStateWithLifecycle(initialValue = emptyList())
    val haptics = rememberAppHaptics()
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    var viewer by remember { mutableStateOf<String?>(null) }

    val revealSpacePx = with(LocalDensity.current) { 240.dp.toPx() }

    /** 展开时只补足下方缺的空间，上限是课程头贴到视口顶部（与培养方案同款）。 */
    fun scrollToReveal(key: String) {
        val layout = listState.layoutInfo
        val info = layout.visibleItemsInfo.firstOrNull { it.key == key } ?: return
        val leftover = layout.viewportEndOffset - (info.offset + info.size)
        val need = revealSpacePx - leftover
        if (need <= 0f) return
        val maxScroll = (info.offset - layout.viewportStartOffset).coerceAtLeast(0).toFloat()
        val delta = minOf(need, maxScroll)
        if (delta > 0f) scope.launch { listState.animateScrollBy(delta) }
    }

    Scaffold(
        snackbarHost = { AppSnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("作业") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        val groups = groupsState
        val all = allItems
        when {
            groups == null || all == null ->
                LoadingHint("正在读取作业", Modifier.fillMaxSize().padding(padding))

            groups.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                EmptyHint(
                    title = "还没有作业",
                    body = "在今日页或课表页点课程卡片，选「作业」记下第一条。",
                )
            }

            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "header") { SectionHeader("按课程") }
                items(groups, key = { it.courseName }) { group ->
                    val courseItems = all.filter { it.courseName == group.courseName }
                    HomeworkCourseCard(
                        courseName = group.courseName,
                        subtitle = if (group.pending > 0) {
                            "${group.pending} 项未完成 / 共 ${group.total} 项"
                        } else {
                            "全部完成 · 共 ${group.total} 项"
                        },
                        items = courseHomeworkOrder(courseItems, today),
                        today = today,
                        courses = courses,
                        expanded = expanded[group.courseName] == true,
                        onToggle = {
                            haptics.tap()
                            val opening = expanded[group.courseName] != true
                            expanded[group.courseName] = opening
                            if (opening) scrollToReveal(group.courseName)
                        },
                        onAdd = { onOpenHomework(group.courseName, 0L, false) },
                        onOpenItem = { id -> onOpenHomework(group.courseName, id, false) },
                        onEditItem = { id -> onOpenHomework(group.courseName, id, true) },
                        onToggleItem = { id, done ->
                            scope.launch {
                                repo.setDone(id, done)
                                ClassReminder.enqueueCheck(context)
                                if (done) {
                                    val hw = courseItems.firstOrNull { it.id == id }
                                    val label = hw?.let { homeworkDisplayTitle(it.detail) } ?: "作业"
                                    val result = snackbar.showNotice(
                                        "已完成「$label」",
                                        actionLabel = "撤销",
                                        duration = SnackbarDuration.Short,
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        repo.setDone(id, false)
                                        ClassReminder.enqueueCheck(context)
                                    }
                                }
                            }
                        },
                        onImageClick = { viewer = it },
                        onLinkClick = { url ->
                            if (!openLink(context, url)) {
                                scope.launch {
                                    snackbar.showNotice(
                                        AppNoticeVisuals("打不开这个链接", tone = NoticeTone.Warning),
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    viewer?.let { fileName ->
        ImageViewerDialog(fileName = fileName, onDismiss = { viewer = null })
    }
}

/**
 * 课程分组折叠卡（作业库与作业中心共用，2026-09-23）：头行 + AnimatedVisibility 条目列表。
 * 条目是详情块（2026-10-21，`HomeworkItemBlock`），行间与头行下走 [AppCardDivider]。
 * [onAdd] 为 null 时隐藏「＋」（作业中心不需要新建入口）。
 */
@Composable
internal fun HomeworkCourseCard(
    courseName: String,
    subtitle: String,
    items: List<Homework>,
    today: LocalDate,
    courses: List<Course>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onAdd: (() -> Unit)?,
    onOpenItem: (Long) -> Unit,
    onEditItem: (Long) -> Unit,
    onToggleItem: (Long, Boolean) -> Unit,
    onImageClick: (String) -> Unit,
    onLinkClick: (String) -> Unit,
) {
    AppCard(contentPadding = PaddingValues(0.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = "展开或收起 $courseName 的作业") { onToggle() }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(color = courseTint(courses, courseName), shape = CircleShape),
            )
            Spacer(Modifier.size(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = courseName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }
            if (onAdd != null) {
                IconButton(onClick = onAdd, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = HugeIcons.Add01,
                        contentDescription = "在 $courseName 新建作业",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Icon(
                imageVector = HugeIcons.ArrowDown01,
                contentDescription = null,
                modifier = Modifier
                    .size(18.dp)
                    .rotate(if (expanded) 180f else 0f),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = tween(250)) + fadeIn(tween(250)),
            exit = shrinkVertically(animationSpec = tween(200)) + fadeOut(tween(200)),
        ) {
            Column(modifier = Modifier.padding(bottom = 6.dp)) {
                AppCardDivider()
                val firstDone = items.indexOfFirst { it.done }
                val doneCount = items.count { it.done }
                items.forEachIndexed { index, homework ->
                    if (index > 0) AppCardDivider()
                    if (index == firstDone) {
                        HomeworkDoneHeader(count = doneCount)
                    }
                    HomeworkItemBlock(
                        homework = homework,
                        today = today,
                        onClick = { onOpenItem(homework.id) },
                        onEdit = { onEditItem(homework.id) },
                        onToggle = { done -> onToggleItem(homework.id, done) },
                        onImageClick = onImageClick,
                        onLinkClick = onLinkClick,
                    )
                }
            }
        }
    }
}
