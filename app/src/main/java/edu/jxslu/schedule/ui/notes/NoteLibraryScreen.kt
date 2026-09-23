package edu.jxslu.schedule.ui.notes

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.domain.Course
import edu.jxslu.schedule.domain.Note
import edu.jxslu.schedule.domain.NoteCourseGroup
import edu.jxslu.schedule.ui.common.AppCard
import edu.jxslu.schedule.ui.common.EmptyHint
import edu.jxslu.schedule.ui.common.LoadingHint
import edu.jxslu.schedule.ui.common.SectionHeader
import edu.jxslu.schedule.ui.common.courseTint
import edu.jxslu.schedule.ui.common.epochMonthDay
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowDown01

/**
 * 笔记·课件库（我的 → 学习 → 笔记·课件，DESIGN §3.11）：**按课程折叠分组**（2026-09-23 改）。
 *
 * 每门课程一张 [AppCard]：头行 = 课程色点 + 课程名 + 篇数，点行展开/收起该课程的笔记列表
 * （AnimatedVisibility：高度展开/收起 + 淡入淡出，250ms/200ms）。点条目直达详情；
 * 头行右侧「＋」直接在该课程下新建。
 * 「最近更新」区块原样保留（作业库不要，笔记库未明确要求移除）。
 *
 * 展开状态存 [mutableStateMapOf]（内存，退出页面重置为全收起）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteLibraryScreen(
    onBack: () -> Unit,
    onOpenNote: (courseName: String, noteId: Long) -> Unit,
) {
    val context = LocalContext.current
    val repo = remember { Graph.noteRepository(context) }
    val scheduleRepo = remember { Graph.repository(context) }
    val groupsState by repo.observeGroups().collectAsStateWithLifecycle(initialValue = null)
    val allNotes by repo.observeAll().collectAsStateWithLifecycle(initialValue = null)
    val courses by scheduleRepo.courses.collectAsStateWithLifecycle(initialValue = emptyList())
    val haptics = rememberAppHaptics()
    val expanded = remember { mutableStateMapOf<String, Boolean>() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("笔记·课件") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        val groups = groupsState
        val notes = allNotes
        when {
            groups == null || notes == null ->
                LoadingHint("正在读取笔记", Modifier.fillMaxSize().padding(padding))

            groups.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                EmptyHint(
                    title = "还没有笔记",
                    body = "在今日页或课表页点课程卡片，选「笔记·课件」就能记下第一篇。",
                )
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "header") { SectionHeader("按课程（点课程名展开/收起）") }
                items(groups, key = { it.courseName }) { group ->
                    NoteGroupCard(
                        group = group,
                        allNotes = notes,
                        courses = courses,
                        expanded = expanded[group.courseName] == true,
                        onToggle = {
                            haptics.tap()
                            expanded[group.courseName] = !(expanded[group.courseName] == true)
                        },
                        onAdd = { onOpenNote(group.courseName, 0L) },
                        onOpenItem = { id -> onOpenNote(group.courseName, id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun NoteGroupCard(
    group: NoteCourseGroup,
    allNotes: List<Note>,
    courses: List<Course>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onAdd: () -> Unit,
    onOpenItem: (Long) -> Unit,
) {
    val courseNotes = remember(allNotes, group.courseName) {
        allNotes.filter { it.courseName == group.courseName }
    }

    AppCard(contentPadding = PaddingValues(0.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = "展开或收起 ${group.courseName} 的笔记") { onToggle() }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(color = courseTint(courses, group.courseName), shape = CircleShape),
            )
            Spacer(Modifier.size(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = group.courseName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${group.count} 篇 · 最近 ${epochMonthDay(group.latestAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }
            IconButton(onClick = onAdd, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = HugeIcons.Add01,
                    contentDescription = "在 ${group.courseName} 新建笔记",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
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
                courseNotes.forEach { note ->
                    NoteRow(
                        note = note,
                        onClick = { onOpenItem(note.id) },
                    )
                }
            }
        }
    }
}
