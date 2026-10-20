package edu.jxslu.schedule.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import edu.jxslu.schedule.domain.Course
import edu.jxslu.schedule.domain.ScheduleCalculator
import edu.jxslu.schedule.domain.ScheduleDiff
import edu.jxslu.schedule.domain.ScheduleOverride
import edu.jxslu.schedule.domain.ScheduleOverrides
import edu.jxslu.schedule.domain.SemesterConfig
import edu.jxslu.schedule.ui.common.courseColor
import java.time.format.DateTimeFormatter

private val WEEKDAY_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")
private val DAY_MONTH = DateTimeFormatter.ofPattern("M/d")

/**
 * AI 调课的**课表前后对照预览**（DESIGN §3.25 方案 B，2026-10-06）。
 *
 * 直接画「应用后这一周长什么样」，而不是让用户对着文字自己拼课表：
 * - 调休借来的课画在**目标列**上（与真课表同一个 `weekColumns` 口径）；
 * - 放假整列标「放假」；
 * - [changedCells] 命中的格子描主色边框 —— 一眼看出这次动了哪几格。
 *
 * **纯预览，不落库**：传进来的 [courses] 已经由 `ScheduleDiff.preview` 套过勾选的改动，
 * [overrides] 只含勾选的覆盖。列固定 7 天（预览要看全周），行只画有课的节次区间。
 */
@Composable
fun SchedulePreviewGrid(
    courses: List<Course>,
    semester: SemesterConfig?,
    week: Int,
    overrides: List<ScheduleOverride>,
    changedCells: Set<Pair<Int, Int>>,
    modifier: Modifier = Modifier,
) {
    val days = (1..7).toList()
    val plan = remember(semester, overrides) { ScheduleOverrides.plan(overrides, semester) }
    val cells = remember(courses, week, plan) {
        ScheduleCalculator.weekColumns(courses, week, days, plan)
    }
    val sectionRange = remember(cells) {
        val used = cells.flatMap { it.second.startSection..it.second.endSection }
        if (used.isEmpty()) 1..11 else (used.min()..used.max())
    }
    val rowCount = sectionRange.count()
    // 行高/列宽跟随字体缩放：格子是固定 dp 而文字是 sp（2026-10-06 复核）
    val fontScale = LocalDensity.current.fontScale.coerceIn(1f, 2f)
    val rowHeight = 22.dp * fontScale
    val gap = 3.dp
    val railWidth = 26.dp * fontScale

    val outline = MaterialTheme.colorScheme.outlineVariant
    val emptyCell = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    val primary = MaterialTheme.colorScheme.primary
    val error = MaterialTheme.colorScheme.error

    Column(modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columnWidth = (maxWidth - railWidth) / days.size

            Column {
                // ---- 表头：星期 + 日期（放假 / 补课替换日期）
                Row(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.width(railWidth))
                    days.forEach { day ->
                        val suspended = ScheduleOverrides.isSuspended(plan, week, day)
                        val followed = ScheduleOverrides.followedFrom(plan, week, day) != null
                        val date = ScheduleOverrides.dateOf(semester, week, day)
                        Column(
                            Modifier.width(columnWidth),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                WEEKDAY_LABELS[day - 1],
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (suspended) FontWeight.Normal else FontWeight.Medium,
                                color = if (suspended) {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                                } else {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                },
                            )
                            Text(
                                when {
                                    suspended -> "放假"
                                    followed -> "补课"
                                    else -> date?.format(DAY_MONTH).orEmpty()
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = when {
                                    suspended -> error.copy(alpha = 0.7f)
                                    followed -> primary
                                    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(5.dp))

                // ---- 网格本体：绝对定位，行高固定，课块跨节次
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(rowHeight * rowCount + gap * (rowCount - 1)),
                ) {
                    // 底格 + 放假列的整列底色
                    days.forEachIndexed { column, day ->
                        val suspended = ScheduleOverrides.isSuspended(plan, week, day)
                        val x = railWidth + columnWidth * column
                        for (row in 0 until rowCount) {
                            Box(
                                Modifier
                                    .offset(x = x, y = (rowHeight + gap) * row)
                                    .width(columnWidth - gap)
                                    .height(rowHeight)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (suspended) error.copy(alpha = 0.10f) else emptyCell),
                            )
                        }
                    }
                    // 课块
                    cells.forEach { (day, course) ->
                        val column = day - 1
                        val startIndex = course.startSection - sectionRange.first
                        val endIndex = course.endSection - sectionRange.first
                        if (startIndex < 0 || endIndex < 0) return@forEach
                        val height = rowHeight * (endIndex - startIndex + 1) + gap * (endIndex - startIndex)
                        val changed = changedCells.any { (d, section) ->
                            d == day && section in course.startSection..course.endSection
                        }
                        val shape = RoundedCornerShape(5.dp)
                        Box(
                            Modifier
                                .offset(
                                    x = railWidth + columnWidth * column,
                                    y = (rowHeight + gap) * startIndex,
                                )
                                .width(columnWidth - gap)
                                .height(height)
                                .clip(shape)
                                .background(courseColor(course.colorIndex).copy(alpha = 0.30f))
                                .then(
                                    if (changed) {
                                        Modifier.border(1.5.dp, primary, shape)
                                    } else {
                                        Modifier.border(0.5.dp, outline.copy(alpha = 0.5f), shape)
                                    },
                                )
                                .padding(horizontal = 3.dp, vertical = 2.dp),
                        ) {
                            Text(
                                course.name,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Legend(primary, error)
    }
}

/** 图例：三条色义（本次改动 / 补课 / 放假）在深色底上不解释一下容易看不懂。 */
@Composable
private fun Legend(primary: androidx.compose.ui.graphics.Color, error: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LegendDot(primary, outline = true)
        Text(
            "本次改动",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Spacer(Modifier.width(12.dp))
        LegendDot(MaterialTheme.colorScheme.surfaceVariant)
        Text(
            "有课",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Spacer(Modifier.width(12.dp))
        LegendDot(error.copy(alpha = 0.25f))
        Text(
            "放假",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}

@Composable
private fun LegendDot(color: androidx.compose.ui.graphics.Color, outline: Boolean = false) {
    val shape = RoundedCornerShape(3.dp)
    Box(
        Modifier
            .size(10.dp)
            .clip(shape)
            .background(color)
            .then(
                if (outline) {
                    Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, shape)
                } else {
                    Modifier
                },
            ),
    )
    Spacer(Modifier.width(5.dp))
}

/**
 * 本次改动命中的格子（列 = 星期，值 = 节次），供预览描边。
 *
 * 自旧对话式 UI 的 `AiMessageBubbles` 迁来（2026-10-07）：它是预览的伴生逻辑，
 * 与 [SchedulePreviewGrid] 同属一处。改动条目按勾选计；FOLLOW 借来的课
 * 画在**目标日**的格子上（借课日按源槽位周次的课程描边）。
 */
internal fun changedCellSet(
    changes: List<ScheduleDiff.Change>,
    accepted: Set<Int>,
    overrides: List<ScheduleOverride>,
    previewCourses: List<Course>,
    week: Int,
    semester: SemesterConfig?,
): Set<Pair<Int, Int>> {
    val out = mutableSetOf<Pair<Int, Int>>()
    changes.forEachIndexed { index, change ->
        if (index !in accepted) return@forEachIndexed
        // MODIFIED 的新旧两侧都要标：只标新态时，「应用前」栏里被挪走 / 被删掉的那一格
        // 不会描边（单周调课看不出动了哪一周）；REMOVED / ADDED 只有一侧
        val sides = when (change.kind) {
            ScheduleDiff.ChangeKind.MODIFIED -> listOfNotNull(change.old, change.new)
            else -> listOfNotNull(change.new ?: change.old)
        }
        sides.forEach { course ->
            if (week in course.weeks) {
                for (section in course.startSection..course.endSection) out += course.day to section
            }
        }
    }
    overrides.forEach { override ->
        if (override.kind != edu.jxslu.schedule.domain.OverrideKind.FOLLOW) return@forEach
        val sourceWeek = override.sourceWeek ?: return@forEach
        val sourceDay = override.sourceWeekday ?: return@forEach
        val target = semester?.let { config ->
            runCatching { ScheduleCalculator.parseDate(override.fromDate) }.getOrNull()
                ?.let { ScheduleOverrides.slotOf(config, it) }
        } ?: return@forEach
        if (target.week != week) return@forEach
        previewCourses
            .filter { it.day == sourceDay && sourceWeek in it.weeks }
            .forEach { borrowed ->
                for (section in borrowed.startSection..borrowed.endSection) {
                    out += target.day to section
                }
            }
    }
    return out
}

/**
 * 预览的周次候选（纯函数，`AiWorkflowTest` 钉住）。
 *
 * - 每条勾选的改动取它（新态优先）的**第一周**：同一条改动在它覆盖的所有周里长得一个样，
 *   逐周列出来只是刷屏；
 * - 每个日期覆盖取 [ScheduleOverrides.weeksOf] 展开的周。
 *
 * 旧实现只在有覆盖时才有周次、纯课表改动一律回落第 1 周——改动不涉及第 1 周时
 * 「应用前 / 应用后」两栏长得一模一样，预览等于白做（本次修的就是这个）。
 */
internal fun previewWeeksOf(
    changes: List<ScheduleDiff.Change>,
    accepted: Set<Int>,
    overrides: List<ScheduleOverride>,
    semester: SemesterConfig?,
): List<Int> {
    val out = sortedSetOf<Int>()
    out += ScheduleOverrides.weeksOf(overrides, semester)
    changes.forEachIndexed { index, change ->
        if (index !in accepted) return@forEachIndexed
        val course = change.new ?: change.old ?: return@forEachIndexed
        course.weeks.minOrNull()?.let { out += it }
        // 只删周次的调整（old 有、new 没有的那几周）：补上被删的第一周。否则候选只看
        // 新态，删掉的那一周不在候选里，「应用前 / 应用后」两栏看不出差别
        if (change.kind == ScheduleDiff.ChangeKind.MODIFIED) {
            val removedWeeks = change.old?.weeks.orEmpty() - change.new?.weeks.orEmpty()
            removedWeeks.minOrNull()?.let { out += it }
        }
    }
    return out.toList()
}
