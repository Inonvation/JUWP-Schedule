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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.domain.pendingHomework
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.EmptyHint
import edu.jxslu.schedule.ui.common.LoadingHint
import edu.jxslu.schedule.ui.reminder.ClassReminder
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 作业中心（今日页作业卡与截止提醒的落点，DESIGN §3.11）：**未完成作业汇总**。
 *
 * 排序口径在 domain（`pendingHomework`：逾期 → 今天 → 未来 → 无截止）；
 * 勾选框即完成（给「撤销」Snackbar），点行进入该作业详情。
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
    val all by repo.observePending().collectAsStateWithLifecycle(initialValue = null)
    val snackbar = remember { SnackbarHostState() }
    val today = LocalDate.now()
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

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // 逾期靠排序在前 + 行内 error 色截止文案表达，不再加第三条视觉线
                items(summary.items, key = { it.id }) { homework ->
                    HomeworkRow(
                        homework = homework,
                        today = today,
                        showCourseName = true,
                        onClick = { onOpenHomework(homework.courseName, homework.id) },
                        onToggle = { checked ->
                            // 勾上即完成；「撤销」把它放回未完成（像待办的误勾修正）
                            scope.launch {
                                repo.setDone(homework.id, checked)
                                ClassReminder.enqueueCheck(context)
                                val result = snackbar.showSnackbar(
                                    "已完成「${homework.title.ifBlank { "未命名作业" }}」",
                                    actionLabel = "撤销",
                                    duration = SnackbarDuration.Short,
                                )
                                if (result == SnackbarResult.ActionPerformed) {
                                    repo.setDone(homework.id, false)
                                    ClassReminder.enqueueCheck(context)
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}
