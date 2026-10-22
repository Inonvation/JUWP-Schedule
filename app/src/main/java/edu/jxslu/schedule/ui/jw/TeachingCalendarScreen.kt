package edu.jxslu.schedule.ui.jw

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.JwImportActivity
import edu.jxslu.schedule.data.jw.JwUrls
import edu.jxslu.schedule.domain.ScheduleCalculator
import edu.jxslu.schedule.domain.SemesterConfig
import edu.jxslu.schedule.ui.common.AppCard
import edu.jxslu.schedule.ui.common.PageEmpty
import edu.jxslu.schedule.ui.common.PageScaffold
import edu.jxslu.schedule.ui.jwvw.JwWebActionButton
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.first

/**
 * 教务 → 教学日历（2026-10-20）。
 *
 * 只展示本机课表的学期配置能确定的东西：第 1 周周一、当前周、每周起止日期。
 * 完整的教务周历（备注列等）仍在顶栏外链打开的原始网页里，不在本地重解析。
 */
@Composable
fun TeachingCalendarScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember(context) { Graph.repository(context) }
    val snackbar = remember { SnackbarHostState() }
    var config by remember { mutableStateOf<SemesterConfig?>(null) }
    var term by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        config = runCatching { repo.semester.first() }.getOrNull()
        term = runCatching { repo.currentTerm() }.getOrNull()
        loaded = true
    }

    val formatter = remember { DateTimeFormatter.ofPattern("MM-dd") }
    val weeks = remember(config) { config?.let(::weekRanges).orEmpty() }
    val currentWeek = config?.let {
        ScheduleCalculator.weekNumberOf(it, LocalDate.now()).takeIf { week ->
            ScheduleCalculator.isWeekInTerm(it, week)
        }
    }
    val webUrl = term?.let { JwUrls.teachingCalendarUrl(it) } ?: JwUrls.JXZL_QUERY

    PageScaffold(
        title = "教学日历",
        onBack = onBack,
        snackbar = snackbar,
        actions = { JwWebActionButton("教学日历", webUrl) },
        loading = !loaded,
        loadingText = "正在读取教学日历",
        empty = if (loaded && config == null) {
            PageEmpty(
                title = "还没有学期配置",
                body = "导入课表后会自动按教务教学日历校准开学日；也可以先手动设置学期。",
                actionLabel = "去导入课表",
                onAction = { JwImportActivity.start(context) },
            )
        } else null,
    ) {
        item {
            AppCard {
                Text(
                    text = "本学期",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    SummaryItem(
                        label = "第 1 周周一",
                        value = config?.startDate?.let {
                            runCatching { LocalDate.parse(it).format(formatter) }.getOrDefault(it)
                        } ?: "—",
                    )
                    SummaryItem(
                        label = "当前周",
                        value = currentWeek?.let { "第 $it 周" } ?: "不在学期内",
                    )
                    SummaryItem(
                        label = "总周数",
                        value = config?.let { "${it.totalWeeks} 周" } ?: "—",
                    )
                }
            }
        }

        item {
            AppCard(contentPadding = PaddingValues(0.dp)) {
                weeks.forEachIndexed { index, range ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 14.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                        )
                    }
                    WeekRow(
                        week = range.week,
                        start = range.start.format(formatter),
                        end = range.end.format(formatter),
                        current = range.week == currentWeek,
                    )
                }
            }
        }

        item {
            Text(
                text = "第 1 周周一来自教务教学日历；完整周历以网页为准。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            )
        }
    }
}

private data class WeekRange(
    val week: Int,
    val start: LocalDate,
    val end: LocalDate,
)

private fun weekRanges(config: SemesterConfig): List<WeekRange> {
    val start = runCatching { ScheduleCalculator.parseDate(config.startDate) }.getOrNull()
        ?.let(ScheduleCalculator::startOfWeek) ?: return emptyList()
    return (1..config.totalWeeks).map { week ->
        val monday = start.plusWeeks((week - 1).toLong())
        WeekRange(week = week, start = monday, end = monday.plusDays(6))
    }
}

@Composable
private fun SummaryItem(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun WeekRow(week: Int, start: String, end: String, current: Boolean) {
    val background = if (current) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
    } else {
        MaterialTheme.colorScheme.surface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "第 $week 周",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.width(58.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "$start 至 $end",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
        )
        if (current) {
            Spacer(Modifier.weight(1f))
            Text(
                text = "本周",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
