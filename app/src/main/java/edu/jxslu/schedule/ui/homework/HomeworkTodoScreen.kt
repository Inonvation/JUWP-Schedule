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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.domain.Course
import edu.jxslu.schedule.domain.Homework
import edu.jxslu.schedule.domain.homeworkDisplayTitle
import edu.jxslu.schedule.domain.pendingHomework
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.EmptyHint
import edu.jxslu.schedule.ui.common.LoadingHint
import edu.jxslu.schedule.ui.common.SectionHeader
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import edu.jxslu.schedule.ui.reminder.ClassReminder
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 作业中心（今日页作业卡与截止提醒的落点，DESIGN §3.11）：**管理页同款折叠分组**
 * （2026-09-23 改，用户拍板 B 方案）。
 *
 * 与作业库（`HomeworkLibraryScreen`）的差异只有两点：
 * 1. 只列**未完成**作业（`observePending` + `pendingHomework` 排序：逾期→今天→未来→无截止），
 *    已完成的不出现；课程分组卡没有「＋」新建入口（去作业库新建）；
 * 2. 课程组**默认全展开**（今日页点进来是要处理作业的，先展开免得逐个点开）。
 *
 * 行内勾选即完成（给「撤销」Snackbar），点行进入该作业详情。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeworkTodoScreen(
    onBack: () -> Unit,
    onOpenHomework: (courseName: String, id: Long) -> Unit,
    onOpenLibrary: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { Graph.homeworkRepository(context) }
    val scheduleRepo = remember { Graph.repository(context) }
    val courses by scheduleRepo.courses.collectAsStateWithLifecycle(initialValue = emptyList())
    val all by repo.observePending().collectAsStateWithLifecycle(initialValue = null)
    val snackbar = remember { SnackbarHostState() }
    val haptics = rememberAppHaptics()
    val today = LocalDate.now()
    // 默认全展开：collapsed 集合记录用户手动收起的课程（与「默认展开」语义对齐）
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }
    val pending = remember(all) { all?.let { pendingHomework(it, today) } }

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
        val summary = pending
        when {
            summary == null -> LoadingHint("正在读取作业", Modifier.fillMaxSize().padding(padding))

            summary.isEmpty -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                EmptyHint(
                    title = "没有未完成的作业",
                    body = "布置了新的作业，会显示在这里和今日页。",
                    actionLabel = "去作业管理",
                    onAction = onOpenLibrary,
                )
            }

            else -> {
                // 按课程分组，组内已按统一口径排序；组间按组内最早截止（逾期组天然在前）
                val byCourse = summary.items.groupBy { it.courseName }
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item(key = "header") { SectionHeader("未完成 · 按课程") }
                    items(byCourse.keys.toList(), key = { it }) { courseName ->
                        val items = byCourse[courseName].orEmpty()
                        val expanded = collapsed[courseName] != true
                        HomeworkCourseCard(
                            courseName = courseName,
                            subtitle = "${items.size} 项未完成",
                            items = items,
                            today = today,
                            courses = courses,
                            expanded = expanded,
                            onToggle = {
                                haptics.tap()
                                collapsed[courseName] = expanded
                            },
                            onAdd = null,
                            onOpenItem = { id -> onOpenHomework(courseName, id) },
                            onToggleItem = { id, done ->
                                scope.launch {
                                    repo.setDone(id, done)
                                    ClassReminder.enqueueCheck(context)
                                    if (done) {
                                        val hw = items.firstOrNull { it.id == id }
                                        val label = hw?.let { homeworkDisplayTitle(it.detail) } ?: "作业"
                                        val result = snackbar.showSnackbar(
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
                        )
                    }
                }
            }
        }
    }
}
