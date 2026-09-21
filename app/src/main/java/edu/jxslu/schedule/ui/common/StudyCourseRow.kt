package edu.jxslu.schedule.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import edu.jxslu.schedule.domain.Course
import edu.jxslu.schedule.domain.MONTH_DAY_FORMAT
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ChevronRight

/**
 * 笔记/作业课程库与列表的共用行（DESIGN §3.11「课程库」）。
 *
 * 归属键是课程名，同名课程取**当前课表**的配色（`Course.colorIndex`）；课表里已没有这门课时
 * 给中性色——旧内容仍要能看见、能编辑（换学期后旧笔记照样可查，见 §4.20「归属」）。
 */
@Composable
fun courseTint(courses: List<Course>, courseName: String): Color =
    courses.firstOrNull { it.name == courseName }?.let { courseColor(it.colorIndex) }
        ?: MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)

/** 时间戳 → `9月21日`（课程库「最近更新」文案口径）。 */
fun epochMonthDay(millis: Long): String =
    MONTH_DAY_FORMAT.format(
        java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).toLocalDate(),
    )

/** 课程分组行：色点 + 课程名 + 副标题（篇数/未完成数）+ 右箭头。 */
@Composable
fun StudyCourseRow(
    dotColor: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberAppHaptics()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f),
                shape = RoundedCornerShape(12.dp),
            )
            .clickable {
                haptics.tap()
                onClick()
            }
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Spacer(Modifier.width(10.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = HugeIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
            modifier = Modifier.size(18.dp),
        )
    }
}
