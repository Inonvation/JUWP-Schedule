package edu.jxslu.schedule.ui.jw

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.JwImportActivity
import edu.jxslu.schedule.data.jw.JwUrls
import edu.jxslu.schedule.domain.Course
import edu.jxslu.schedule.domain.CourseKind
import edu.jxslu.schedule.domain.ScheduleCalculator
import edu.jxslu.schedule.domain.TimeSlot
import edu.jxslu.schedule.domain.compactPosition
import edu.jxslu.schedule.domain.dayLabel
import edu.jxslu.schedule.ui.common.AppCard
import edu.jxslu.schedule.ui.common.PageEmpty
import edu.jxslu.schedule.ui.common.PageScaffold
import edu.jxslu.schedule.ui.common.SectionHeader
import edu.jxslu.schedule.ui.common.courseColor
import edu.jxslu.schedule.ui.jwvw.JwWebActionButton

/**
 * 教务 → 理论课表 / 实验课表（2026-10-21）。
 *
 * 此前这两个入口直接开教务网页；现在先落到本机课表的只读清单（按星期分组，
 * 含周次、节次、地点、教师），网页核对仍留给顶栏外链。
 * 数据就是导入写进 Room 的 `courses`，按 [CourseKind] 取其中一张表；
 * 不重新解析页面、也不另建数据层。
 */
@Composable
fun JwScheduleListScreen(kind: CourseKind, onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember(context) { Graph.repository(context) }
    val snackbar = remember { SnackbarHostState() }
    // 初值留 null：空列表与「还没读到」是两回事，直接给 emptyList 会先闪一下空态
    val courses by repo.courses.collectAsStateWithLifecycle(initialValue = null)
    val slots by repo.timeSlots.collectAsStateWithLifecycle(initialValue = emptyList())
    var term by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        term = runCatching { repo.currentTerm() }.getOrNull()
    }

    val isLab = kind == CourseKind.Lab
    val title = if (isLab) "实验课表" else "理论课表"
    val webUrl = if (isLab) JwUrls.labScheduleUrl(term) else JwUrls.scheduleListUrl(term)
    val mine = remember(courses, kind) { courses.orEmpty().filter { it.kind == kind } }
    // 星期升序；同一天按节次排，节次相同再按课名
    val grouped = remember(mine) {
        mine.groupBy { it.day }.toSortedMap().mapValues { (_, list) ->
            list.sortedWith(compareBy({ it.startSection }, { it.name }))
        }
    }

    PageScaffold(
        title = title,
        onBack = onBack,
        snackbar = snackbar,
        actions = { JwWebActionButton(title, webUrl) },
        loading = courses == null,
        loadingText = "正在读取课表",
        empty = if (courses != null && mine.isEmpty()) {
            PageEmpty(
                title = if (isLab) "这张课表还没有实验课" else "课表里还没有理论课",
                body = if (isLab) {
                    "有些学期本来就没有实验课；导入课表时会一并抓取实验课表。"
                } else {
                    "课表默认为空，从教务导入后这里按星期列出本学期的理论课。"
                },
                actionLabel = if (isLab) "在教务网页核对" else "去导入课表",
                onAction = {
                    if (isLab) {
                        JwImportActivity.startBrowse(context, title, webUrl)
                    } else {
                        JwImportActivity.start(context)
                    }
                },
            )
        } else null,
    ) {
        item {
            Text(
                text = listOfNotNull(
                    term?.takeIf { it.isNotBlank() } ?: "未配置学期",
                    "${mine.size} 门",
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
        }
        grouped.forEach { (day, list) ->
            item(key = "header-$day") {
                SectionHeader(
                    title = "周${dayLabel(day)}",
                    trailing = "${list.size} 门",
                    horizontalPadding = 0.dp,
                    topPadding = 8.dp,
                    bottomPadding = 0.dp,
                )
            }
            item(key = "day-$day") {
                AppCard(contentPadding = PaddingValues(0.dp)) {
                    list.forEachIndexed { index, course ->
                        if (index > 0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 36.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                            )
                        }
                        CourseRow(course, slots)
                    }
                }
            }
        }
        item(key = "note") {
            Text(
                text = "数据来自本机课表；以教务网页为准。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                modifier = Modifier.padding(start = 4.dp, top = 2.dp),
            )
        }
    }
}

@Composable
private fun CourseRow(course: Course, slots: List<TimeSlot>) {
    val muted = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Spacer(
            Modifier
                .padding(top = 6.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(courseColor(course.colorIndex)),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = course.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = timeText(course, slots),
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
            val extra = buildList {
                val weeks = ScheduleCalculator.formatWeeks(course.weeks)
                if (weeks.isNotBlank()) add("第 $weeks 周")
                compactPosition(course.position).takeIf { it.isNotBlank() }?.let(::add)
                course.teacher.takeIf { it.isNotBlank() }?.let(::add)
            }.joinToString(" · ")
            if (extra.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = extra,
                    style = MaterialTheme.typography.labelSmall,
                    color = muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** `第 3-4 节  08:00–09:35`；节次与时间有一项缺失就只显示另一项。 */
private fun timeText(course: Course, slots: List<TimeSlot>): String {
    val section = if (course.endSection > course.startSection) {
        "第 ${course.startSection}-${course.endSection} 节"
    } else {
        "第 ${course.startSection} 节"
    }
    val start = slots.firstOrNull { it.number == course.startSection }?.startTime
    val end = slots.firstOrNull { it.number == course.endSection }?.endTime
    return if (start != null && end != null) "$section  $start–$end" else section
}
