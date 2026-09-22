package edu.jxslu.schedule.ui.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.domain.NoteCourseGroup
import edu.jxslu.schedule.ui.common.EmptyHint
import edu.jxslu.schedule.ui.common.LoadingHint
import edu.jxslu.schedule.ui.common.SectionHeader
import edu.jxslu.schedule.ui.common.StudyCourseRow
import edu.jxslu.schedule.ui.common.courseTint
import edu.jxslu.schedule.ui.common.epochMonthDay

/**
 * 笔记·课件库（我的 → 学习 → 笔记·课件，DESIGN §3.11）：按课程分组，点进课程列表。
 *
 * 2026-09-22 起顶部多一块「最近更新」（最多 [RECENT_LIMIT] 条，点击**直达笔记详情**）：
 * 原来「我的 → 笔记库 → 课程 → 列表 → 详情」要四下点击才能看到上次记的内容，
 * 而绝大多数回访就是找最近那几条。课程分组原样保留，库的按课程管理定位不变。
 *
 * 仓库直订冷 Flow（与成绩页同一范式：页面无写操作、无需 ViewModel），
 * `initial = null` 当"未就绪"门闸，避免空列表先闪一帧。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteLibraryScreen(
    onBack: () -> Unit,
    onOpenCourse: (String) -> Unit,
    /** 「最近更新」区块的直达入口（省掉「先进课程再挑笔记」那一跳）。 */
    onOpenNote: (courseName: String, noteId: Long) -> Unit,
) {
    val context = LocalContext.current
    val repo = remember { Graph.noteRepository(context) }
    val scheduleRepo = remember { Graph.repository(context) }
    val groupsState by repo.observeGroups().collectAsStateWithLifecycle(initialValue = null)
    // 两条流都到齐才渲染：只等 groups 的话，先到 groups、后到 allNotes 会让「最近更新」
    // 区块晚一帧插入，列表整体向下跳一下
    val allNotes by repo.observeAll().collectAsStateWithLifecycle(initialValue = null)
    val courses by scheduleRepo.courses.collectAsStateWithLifecycle(initialValue = emptyList())

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
                // repo.observeAll 已按 updatedAt 倒序，这里只截前几条
                val recent = notes.take(RECENT_LIMIT)
                if (recent.isNotEmpty()) {
                    item(key = "recent-header") { SectionHeader("最近更新") }
                    items(recent, key = { "recent-${it.id}" }) { note ->
                        NoteRow(
                            note = note,
                            showCourseName = true,
                            onClick = { onOpenNote(note.courseName, note.id) },
                        )
                    }
                    item(key = "courses-header") { SectionHeader("按课程") }
                }
                items(groups, key = { it.courseName }) { group ->
                    NoteCourseRow(group, courses.map { it }, onOpenCourse)
                }
            }
        }
    }
}

/** 「最近更新」区块的条数上限：库页定位是按课程管理，最近项只是少一跳的捷径。 */
private const val RECENT_LIMIT = 3

@Composable
private fun NoteCourseRow(
    group: NoteCourseGroup,
    courses: List<edu.jxslu.schedule.domain.Course>,
    onOpenCourse: (String) -> Unit,
) {
    StudyCourseRow(
        dotColor = courseTint(courses, group.courseName),
        title = group.courseName,
        subtitle = "${group.count} 篇 · 最近 ${epochMonthDay(group.latestAt)}",
        onClick = { onOpenCourse(group.courseName) },
        modifier = Modifier.fillMaxWidth(),
    )
}
