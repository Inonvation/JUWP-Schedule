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
import edu.jxslu.schedule.ui.common.StudyCourseRow
import edu.jxslu.schedule.ui.common.courseTint
import edu.jxslu.schedule.ui.common.epochMonthDay

/**
 * 笔记·课件库（我的 → 学习 → 笔记·课件，DESIGN §3.11）：按课程分组，点进课程列表。
 *
 * 仓库直订冷 Flow（与成绩页同一范式：页面无写操作、无需 ViewModel），
 * `initial = null` 当"未就绪"门闸，避免空列表先闪一帧。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteLibraryScreen(
    onBack: () -> Unit,
    onOpenCourse: (String) -> Unit,
) {
    val context = LocalContext.current
    val repo = remember { Graph.noteRepository(context) }
    val scheduleRepo = remember { Graph.repository(context) }
    val groupsState by repo.observeGroups().collectAsStateWithLifecycle(initialValue = null)
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
        when {
            groups == null -> LoadingHint("正在读取笔记", Modifier.fillMaxSize().padding(padding))

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
                items(groups, key = { it.courseName }) { group ->
                    NoteCourseRow(group, courses.map { it }, onOpenCourse)
                }
            }
        }
    }
}

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
