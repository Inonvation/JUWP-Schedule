package edu.jxslu.schedule.ui.homework

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.domain.Homework
import edu.jxslu.schedule.domain.homeworkDisplayTitle
import edu.jxslu.schedule.domain.courseHomeworkOrder
import edu.jxslu.schedule.domain.dueLabel
import edu.jxslu.schedule.ui.common.AppCardRow
import edu.jxslu.schedule.ui.common.EmptyHint
import edu.jxslu.schedule.ui.common.LoadingHint
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import edu.jxslu.schedule.ui.reminder.ClassReminder
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 某课程的作业列表（DESIGN §3.11）。排序口径在 domain：未完成在前（逾期 → 今天 → 未来 →
 * 无截止），已完成沉底（最近完成的在前）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeworkCourseScreen(
    courseName: String,
    onBack: () -> Unit,
    onOpenHomework: (Long) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { Graph.homeworkRepository(context) }
    val list by repo.observeForCourse(courseName).collectAsStateWithLifecycle(initialValue = null)
    val today = LocalDate.now()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(courseName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 新建：进入详情页的「新建」形态（itemId = 0）
                    IconButton(onClick = { onOpenHomework(0L) }) {
                        Icon(HugeIcons.Add01, contentDescription = "新建作业")
                    }
                },
            )
        },
    ) { padding ->
        val items = list
        when {
            items == null -> LoadingHint("正在读取作业", Modifier.fillMaxSize().padding(padding))

            items.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                EmptyHint(
                    title = "这门课还没有作业",
                    body = "点右上角「+」记下一条，可以顺手填上截止日期。",
                )
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(courseHomeworkOrder(items, today), key = { it.id }) { homework ->
                    HomeworkRow(
                        homework = homework,
                        today = today,
                        onClick = { onOpenHomework(homework.id) },
                        onToggle = { done ->
                            scope.launch {
                                repo.setDone(homework.id, done)
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
 * 作业列表行：勾选框（勾上即完成，像待办）+ 标题 + 截止 chip；完成后置灰 + 删除线。
 *
 * [embedded] = true 时画成**组卡展开区的平铺行**（2026-10-09）：无卡底、无描边，只有整行涟漪
 * ——作业库 / 作业中心的课程折叠卡里每条再套一张卡就是「气泡套气泡」。顶层列表（某课程的作业页）
 * 仍是 [AppCardRow]。
 */
@Composable
internal fun HomeworkRow(
    homework: Homework,
    today: LocalDate,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    showCourseName: Boolean = false,
    embedded: Boolean = false,
) {
    val haptics = rememberAppHaptics()
    val label = dueLabel(homework.dueDate, today)
    val overdue = homework.dueDate?.isBefore(today) == true && !homework.done
    val toggle: (Boolean) -> Unit = { checked ->
        haptics.toggle()
        onToggle(checked)
    }
    if (embedded) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 不自己 clip：涟漪由外层组卡的 14dp 圆角裁形
                .clickable {
                    haptics.tap()
                    onClick()
                }
                // 勾选框自带 M3 的 48dp 触达区与内边距，行内间距向它让位（左右不对称是有意的）
                .padding(start = 4.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HomeworkRowBody(
                homework = homework,
                label = label,
                overdue = overdue,
                showCourseName = showCourseName,
                onToggle = toggle,
            )
        }
    } else {
        AppCardRow(
            onClick = onClick,
            // 勾选框自带 M3 的 48dp 触达区与内边距，卡片内间距向它让位（左右不对称是有意的）
            contentPadding = PaddingValues(start = 4.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
        ) {
            HomeworkRowBody(
                homework = homework,
                label = label,
                overdue = overdue,
                showCourseName = showCourseName,
                onToggle = toggle,
            )
        }
    }
}

/** [HomeworkRow] 的内容本体（勾选框 + 标题 / 副行），平铺态与卡片态共用。 */
@Composable
private fun RowScope.HomeworkRowBody(
    homework: Homework,
    label: String?,
    overdue: Boolean,
    showCourseName: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Checkbox(
        checked = homework.done,
        onCheckedChange = onToggle,
    )
    Spacer(Modifier.width(4.dp))
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = homeworkDisplayTitle(homework.detail),
            style = MaterialTheme.typography.bodyLarge,
            color = if (homework.done) {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            textDecoration = if (homework.done) TextDecoration.LineThrough else null,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val sub = buildList {
            if (showCourseName) add(homework.courseName)
            label?.let { add(if (overdue) it else "截止 $it") }
        }.joinToString(" · ")
        if (sub.isNotBlank()) {
            Text(
                text = sub,
                style = MaterialTheme.typography.bodySmall,
                color = if (overdue) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
