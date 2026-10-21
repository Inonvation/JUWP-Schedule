package edu.jxslu.schedule.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import edu.jxslu.schedule.domain.Course
import edu.jxslu.schedule.domain.ScheduleDiff
import edu.jxslu.schedule.ui.theme.semanticColors

/**
 * 明细行的标签文案。默认是**调课处置页的「教务」口径**；AI 调课页传自己的「建议」口径，
 * 免得把模型给的建议写成「教务新增」。
 */
data class ChangeDetailLabels(
    val modifiedOld: String = "原",
    val modifiedNew: String = "新",
    val added: String = "教务新增",
    val removed: String = "教务已无此课",
)

/**
 * 一条课表差异的卡片：类型徽章 + 课程名 + 变化明细 + 勾选框。
 *
 * 从调课处置页（`ScheduleChangesScreen`）抽出来共用（2026-10-06，AI 调课 §4.39 起
 * 两处都要渲染差异）：**差异的呈现口径只此一份**——徽章文案、明细行、周次压缩
 * 改一处两页同步。
 *
 * [conflicted] 与 [conflictText] 供处置页用（与用户本地修改冲突的提示）；
 * [note] 供 AI 页展示模型给的理由（可空）；[labels] 切换明细行的措辞；
 * [onRowToggle] 非空时整卡可点切换勾选（涟漪与触感走 `AppCard` 的统一口径，
 * 勾选框自身的点击不冒泡、不会双触发）。
 */
@Composable
fun ScheduleChangeCard(
    change: ScheduleDiff.Change,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    conflicted: Boolean = false,
    conflictText: String = "与你修改过的课冲突，采纳会覆盖你的修改",
    note: String? = null,
    labels: ChangeDetailLabels = ChangeDetailLabels(),
    onRowToggle: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    AppCard(modifier = modifier.fillMaxWidth(), onClick = onRowToggle) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    KindBadge(change.kind)
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = (change.new ?: change.old)?.name.orEmpty(),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.size(6.dp))
                when (change.kind) {
                    ScheduleDiff.ChangeKind.MODIFIED -> {
                        ChangeDetail(labels.modifiedOld, change.old)
                        ChangeDetail(labels.modifiedNew, change.new)
                    }
                    ScheduleDiff.ChangeKind.ADDED -> ChangeDetail(labels.added, change.new)
                    ScheduleDiff.ChangeKind.REMOVED -> ChangeDetail(labels.removed, change.old)
                }
                if (!note.isNullOrBlank()) {
                    Spacer(Modifier.size(6.dp))
                    Text(
                        note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                if (conflicted) {
                    Spacer(Modifier.size(6.dp))
                    Text(
                        conflictText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }
            Checkbox(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = CheckboxDefaults.colors(
                    disabledCheckedColor = MaterialTheme.colorScheme.outlineVariant,
                ),
            )
        }
    }
}

/**
 * 一**组**差异的卡片（2026-10-21）：处置页按 [ScheduleDiff.group] 的结果渲染。
 * 单周调课显示为一张「调课」卡——原 / 新两行只列**让出周次**与目标块，与教务通知
 * 「原上课周次 / 新上课周次」同口径；普通差异原样委托 [ScheduleChangeCard]。
 */
@Composable
fun ScheduleChangeGroupCard(
    group: ScheduleDiff.ChangeGroup,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    conflicted: Boolean = false,
    conflictText: String = "与你修改过的课冲突，采纳会覆盖你的修改",
    onRowToggle: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val move = group.move
    if (move == null) {
        ScheduleChangeCard(
            change = group.anchor,
            checked = checked,
            onCheckedChange = onCheckedChange,
            conflicted = conflicted,
            conflictText = conflictText,
            onRowToggle = onRowToggle,
            modifier = modifier,
        )
        return
    }
    AppCard(modifier = modifier.fillMaxWidth(), onClick = onRowToggle) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ChangeBadge("调课", MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = move.to.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.size(6.dp))
                // 周次放最前：一眼对上通知的「原上课周次 / 新上课周次」
                MoveDetail("原", move.from)
                MoveDetail("新", move.to)
                if (conflicted) {
                    Spacer(Modifier.size(6.dp))
                    Text(
                        conflictText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }
            Checkbox(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = CheckboxDefaults.colors(
                    disabledCheckedColor = MaterialTheme.colorScheme.outlineVariant,
                ),
            )
        }
    }
}

@Composable
private fun KindBadge(kind: ScheduleDiff.ChangeKind) {
    val (text, color) = when (kind) {
        ScheduleDiff.ChangeKind.MODIFIED -> "调整" to MaterialTheme.colorScheme.primary
        ScheduleDiff.ChangeKind.ADDED -> "新增" to MaterialTheme.semanticColors.success
        ScheduleDiff.ChangeKind.REMOVED -> "删除" to MaterialTheme.colorScheme.error
    }
    ChangeBadge(text, color)
}

@Composable
private fun ChangeBadge(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
    )
}

/** 调课的一端：周次 · 星期节次 · 地点 · 教师（周次在前的口径只此一份）。 */
@Composable
private fun MoveDetail(label: String, course: Course) {
    Text(
        text = buildString {
            append(label).append('：')
            append(weeksText(course.weeks))
            append(" · 周").append(dayName(course.day))
            append(" 第${course.startSection}-${course.endSection}节")
            if (course.position.isNotBlank()) append(" · ").append(course.position)
            if (course.teacher.isNotBlank()) append(" · ").append(course.teacher)
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 一行课程明细：标签 + 星期节次 / 教师 / 地点 / 周次。 */
@Composable
private fun ChangeDetail(label: String, course: Course?) {
    if (course == null) return
    Text(
        text = buildString {
            append(label).append('：')
            append("周").append(dayName(course.day))
            append(" 第${course.startSection}-${course.endSection}节")
            if (course.position.isNotBlank()) append(" · ").append(course.position)
            if (course.teacher.isNotBlank()) append(" · ").append(course.teacher)
            append(" · ").append(weeksText(course.weeks))
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun dayName(day: Int): String =
    listOf("一", "二", "三", "四", "五", "六", "日").getOrNull(day - 1) ?: day.toString()

/** 周次集合压缩成「1-3,5,8-16」的紧凑写法；连续段才合并。 */
private fun weeksText(weeks: Set<Int>): String {
    if (weeks.isEmpty()) return "无周次"
    val sorted = weeks.sorted()
    val parts = mutableListOf<String>()
    var start = sorted.first()
    var prev = start
    for (w in sorted.drop(1)) {
        if (w == prev + 1) {
            prev = w
        } else {
            parts += if (start == prev) "$start" else "$start-$prev"
            start = w
            prev = w
        }
    }
    parts += if (start == prev) "$start" else "$start-$prev"
    return "第${parts.joinToString(",")}周"
}
