package edu.jxslu.schedule.ui.homework

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.domain.Course
import edu.jxslu.schedule.domain.Homework
import edu.jxslu.schedule.domain.HomeworkCourseGroup
import edu.jxslu.schedule.domain.courseHomeworkOrder
import edu.jxslu.schedule.ui.common.AppCard
import edu.jxslu.schedule.ui.common.EmptyHint
import edu.jxslu.schedule.ui.common.LoadingHint
import edu.jxslu.schedule.ui.common.SectionHeader
import edu.jxslu.schedule.ui.common.courseTint
import edu.jxslu.schedule.ui.common.rememberAppHaptics
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
 * 行内勾选照旧；点条目进详情；头行右侧「＋」直接进该课程的新建页。
 * 「最近更新」区块移除（用户拍板 2026-09-23）。
 *
 * 展开状态存 [mutableStateMapOf]（内存，退出页面重置为全收起——展开是临时浏览动作）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeworkLibraryScreen(
    onBack: () -> Unit,
    onOpenHomework: (courseName: String, id: Long) -> Unit,
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

    Scaffold(
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
                            expanded[group.courseName] = !(expanded[group.courseName] == true)
                        },
                        onAdd = { onOpenHomework(group.courseName, 0L) },
                        onOpenItem = { id -> onOpenHomework(group.courseName, id) },
                        onToggleItem = { id, done ->
                            scope.launch {
                                repo.setDone(id, done)
                                ClassReminder.enqueueCheck(context)
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * 课程分组折叠卡（作业库与作业中心共用，2026-09-23）：头行 + AnimatedVisibility 条目列表。
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
    onToggleItem: (Long, Boolean) -> Unit,
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
            Column(
                modifier = Modifier.padding(start = 32.dp, end = 14.dp, bottom = 10.dp),
            ) {
                items.forEach { homework ->
                    HomeworkRow(
                        homework = homework,
                        today = today,
                        onClick = { onOpenItem(homework.id) },
                        onToggle = { done -> onToggleItem(homework.id, done) },
                    )
                }
            }
        }
    }
}
