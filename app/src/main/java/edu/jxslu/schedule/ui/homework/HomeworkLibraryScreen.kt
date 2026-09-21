package edu.jxslu.schedule.ui.homework

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
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
import edu.jxslu.schedule.ui.common.EmptyHint
import edu.jxslu.schedule.ui.common.LoadingHint
import edu.jxslu.schedule.ui.common.StudyCourseRow
import edu.jxslu.schedule.ui.common.courseTint
import edu.jxslu.schedule.ui.common.epochMonthDay

/**
 * 作业库（我的 → 学习 → 作业，DESIGN §3.11）：按课程分组，行 = 未完成数 / 总数。
 * 点进课程作业列表；勾选在列表行内完成（像待办），完成后置灰 + 删除线，不隐藏（可反悔）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeworkLibraryScreen(
    onBack: () -> Unit,
    onOpenCourse: (String) -> Unit,
) {
    val context = LocalContext.current
    val repo = remember { Graph.homeworkRepository(context) }
    val scheduleRepo = remember { Graph.repository(context) }
    val groupsState by repo.observeGroups().collectAsStateWithLifecycle(initialValue = null)
    val courses by scheduleRepo.courses.collectAsStateWithLifecycle(initialValue = emptyList())

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
        when {
            groups == null -> LoadingHint("正在读取作业", Modifier.fillMaxSize().padding(padding))

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
                items(groups, key = { it.courseName }) { group ->
                    val subtitle = if (group.pending > 0) {
                        "${group.pending} 项未完成 / 共 ${group.total} 项 · 最近 ${epochMonthDay(group.latestAt)}"
                    } else {
                        "全部完成 · 共 ${group.total} 项"
                    }
                    StudyCourseRow(
                        dotColor = courseTint(courses, group.courseName),
                        title = group.courseName,
                        subtitle = subtitle,
                        onClick = { onOpenCourse(group.courseName) },
                    )
                }
            }
        }
    }
}
