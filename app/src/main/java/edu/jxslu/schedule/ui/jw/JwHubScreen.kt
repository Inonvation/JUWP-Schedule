package edu.jxslu.schedule.ui.jw

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.repo.ExamSnapshotStore
import edu.jxslu.schedule.domain.CourseKind
import edu.jxslu.schedule.domain.ScheduleCalculator
import edu.jxslu.schedule.domain.ScoreCalculator
import edu.jxslu.schedule.ui.common.AppCard
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.LocalBottomBarClearance
import edu.jxslu.schedule.ui.common.SectionHeader
import edu.jxslu.schedule.ui.common.pinnedStatusBars
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import edu.jxslu.schedule.ui.common.rememberResumeTick
import edu.jxslu.schedule.ui.jwvw.JwImportOutcomeEffect
import java.time.LocalDate
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Analytics01
import me.rerere.hugeicons.stroke.Book01
import me.rerere.hugeicons.stroke.Calendar01
import me.rerere.hugeicons.stroke.CheckmarkCircle02
import me.rerere.hugeicons.stroke.ChevronRight
import me.rerere.hugeicons.stroke.File01
import me.rerere.hugeicons.stroke.Layers01
import me.rerere.hugeicons.stroke.Task01
import me.rerere.hugeicons.stroke.Toolbox

/**
 * 教务 Tab（2026-10-20 新 IA，2026-10-20 第三版；2026-10-21 课表入原生页）。
 *
 * 这一版只保留「总览 + 快捷入口 + 分组卡」：网页核对按钮不再堆在每一行，
 * 已经分别落到成绩、考试、教材、学业、培养方案、实习基地、选课、教学日历
 * 各自的页面顶栏。理论 / 实验课表也先落到本机课表的只读清单（网页核对在页顶栏）。
 * 学籍信息不在这里——按用户口径收进左侧栏的头像区（2026-10-21）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JwHubScreen(
    onOpenTheorySchedule: () -> Unit,
    onOpenLabSchedule: () -> Unit,
    onOpenScores: () -> Unit,
    onOpenExams: () -> Unit,
    onOpenTextbooks: () -> Unit,
    onOpenScholar: () -> Unit,
    onOpenCurriculum: () -> Unit,
    onOpenSelection: () -> Unit,
    onOpenInternship: () -> Unit,
    onOpenTeachingCalendar: () -> Unit,
    onOpenTeachingEval: () -> Unit,
    onOpenLeave: () -> Unit,
    onOpenRepair: () -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenImport: () -> Unit,
) {
    val clearance = LocalBottomBarClearance.current
    val stats = rememberJwHubStats()
    val snackbar = remember { SnackbarHostState() }
    // 从本页顶栏「导入」回来的结果气泡落在这里（与今日 / 课表 / 成绩 / 考试同一条通道）。
    JwImportOutcomeEffect(snackbar)

    val quickEntries = listOf(
        QuickEntry("理论课表", stats.theoryShort, HugeIcons.Book01, onOpenTheorySchedule),
        QuickEntry("实验课表", stats.labShort, HugeIcons.Layers01, onOpenLabSchedule),
        QuickEntry("成绩查询", stats.scoresShort, HugeIcons.Analytics01, onOpenScores),
        QuickEntry("考试安排", stats.examsShort, HugeIcons.Calendar01, onOpenExams),
    )

    val sections = listOf(
        HubSection(
            title = "教务服务",
            entries = listOf(
                HubEntry("教材", "本学期教材清单", HugeIcons.Book01, onOpenTextbooks),
                HubEntry("选课", "记录 · 轮次", HugeIcons.Task01, onOpenSelection),
                HubEntry("教学日历", "第 1 周周一 · 每周起止", HugeIcons.Calendar01, onOpenTeachingCalendar),
            ),
        ),
        HubSection(
            title = "学业与规划",
            entries = listOf(
                HubEntry("学业完成情况", "培养方案达成度", HugeIcons.Analytics01, onOpenScholar),
                HubEntry("培养方案", "课程设置总表", HugeIcons.Book01, onOpenCurriculum),
                HubEntry("实习基地", "基地清单", HugeIcons.Layers01, onOpenInternship),
            ),
        ),
        HubSection(
            title = "学工与校历",
            entries = listOf(
                HubEntry("学生评教", "评完解锁成绩", HugeIcons.CheckmarkCircle02, onOpenTeachingEval),
                HubEntry("请假", "学工系统 · 填表", HugeIcons.File01, onOpenLeave),
                HubEntry("宿舍报修", "进度 · 审批", HugeIcons.Toolbox, onOpenRepair),
                HubEntry("校历", "学年校历", HugeIcons.Calendar01, onOpenCalendar),
            ),
        ),
    )

    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        snackbarHost = { AppSnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                windowInsets = pinnedStatusBars(),
                title = {
                    Column {
                        Text("教务", fontWeight = FontWeight.SemiBold)
                        Text(
                            text = stats.header,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onOpenImport) {
                        Text("导入")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 4.dp,
                bottom = 20.dp + clearance,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "summary") {
                SummaryCard(stats)
            }

            item(key = "quick") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    quickEntries.forEach { entry ->
                        QuickTile(entry, Modifier.weight(1f))
                    }
                }
            }

            sections.forEach { section ->
                item(key = "header-${section.title}") {
                    SectionHeader(
                        title = section.title,
                        trailing = "${section.entries.size} 项",
                        horizontalPadding = 0.dp,
                        topPadding = 8.dp,
                        bottomPadding = 0.dp,
                    )
                }
                item(key = "card-${section.title}") {
                    AppCard(contentPadding = PaddingValues(0.dp)) {
                        section.entries.forEachIndexed { index, entry ->
                            if (index > 0) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 48.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                                )
                            }
                            HubRow(entry)
                        }
                    }
                }
            }

            item(key = "footer") {
                Text(
                    text = "数据来自教务系统，非学校官方应用；有疑问以教务网页为准。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.padding(start = 4.dp, top = 10.dp, end = 4.dp),
                )
            }
        }
    }
}

private data class HubEntry(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

private data class HubSection(
    val title: String,
    val entries: List<HubEntry>,
)

private data class QuickEntry(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

@Composable
private fun SummaryCard(stats: JwHubStats) {
    AppCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "学业概览",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stats.scoreTerm,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            Metric("成绩", stats.scores, Modifier.weight(1f))
            Metric("考试", stats.exams, Modifier.weight(1f))
        }
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 8.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        )
        Row(Modifier.fillMaxWidth()) {
            Metric("教材", stats.textbooks, Modifier.weight(1f))
            Metric("课表", stats.schedule, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun QuickTile(entry: QuickEntry, modifier: Modifier = Modifier) {
    AppCard(
        modifier = modifier.heightIn(min = 78.dp),
        onClick = entry.onClick,
        onClickLabel = entry.title,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = entry.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(17.dp),
                )
            }
            Spacer(Modifier.height(5.dp))
            Text(
                text = entry.title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = entry.subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun HubRow(entry: HubEntry) {
    val haptics = rememberAppHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = entry.title) {
                haptics.tap()
                entry.onClick()
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = entry.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = entry.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = HugeIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
            modifier = Modifier.size(16.dp),
        )
    }
}

private data class JwHubStats(
    val header: String,
    val scoreTerm: String,
    val scores: String,
    val scoresShort: String,
    val exams: String,
    val examsShort: String,
    val textbooks: String,
    val schedule: String,
    val theoryShort: String,
    val labShort: String,
)

/**
 * 从现有仓库拼出首页数字，不新建数据层：
 * - 成绩取**最近有数据的学期**，不拿空的本学期充数；
 * - 考试读 `ExamSnapshotStore`（考试提醒链写的快照）；
 * - 教材取当前课表学期；
 * - 课表取当前周次与小节合计。
 */
@Composable
private fun rememberJwHubStats(): JwHubStats {
    val context = LocalContext.current
    val repo = remember(context) { Graph.repository(context) }
    val scoreRepo = remember(context) { Graph.scoreRepository(context) }
    val resumeTick = rememberResumeTick()

    val semester by repo.semester.collectAsStateWithLifecycle(initialValue = null)
    val courses by repo.courses.collectAsStateWithLifecycle(initialValue = emptyList())
    val scoresByTerm by scoreRepo.observeAllGroupedByTerm()
        .collectAsStateWithLifecycle(initialValue = emptyMap())
    val textbookTitles by repo.textbookTitlesByCourse
        .collectAsStateWithLifecycle(initialValue = emptyMap())

    var term by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(resumeTick) {
        term = runCatching { repo.currentTerm() }.getOrNull()
    }
    val examSnapshot = remember(resumeTick) {
        runCatching { ExamSnapshotStore(context).load() }.getOrNull()
    }

    return remember(semester, courses, scoresByTerm, textbookTitles, term, examSnapshot) {
        val today = LocalDate.now()
        val latestScoreTerm = scoresByTerm.entries
            .filter { it.value.isNotEmpty() }
            .maxByOrNull { it.key }
            ?.key
        val summary = latestScoreTerm?.let { t ->
            ScoreCalculator.summarize(t, scoresByTerm[t].orEmpty())
        }
        val scores = when {
            summary == null || summary.courseCount == 0 -> "暂无成绩"
            else -> buildString {
                append("${summary.courseCount} 门")
                summary.weightedAverage?.let { append(" · 加权 ${"%.1f".format(it)}") }
            }
        }
        val scoresShort = summary?.takeIf { it.courseCount > 0 }
            ?.let { "${it.courseCount} 门" }
            ?: "暂无"

        val exams = examSnapshot?.exams.orEmpty().filter { it.date.isNotBlank() }
        val upcoming = exams.filter { it.date >= today.toString() }
        val examText = when {
            exams.isEmpty() -> "暂无考试"
            upcoming.isNotEmpty() ->
                "${upcoming.size} 场 · 最近 ${upcoming.minOf { it.date }.substring(5)}"
            else -> "${exams.size} 场 · 均已结束"
        }
        val examsShort = when {
            upcoming.isNotEmpty() -> "${upcoming.size} 场"
            exams.isNotEmpty() -> "${exams.size} 场"
            else -> "暂无"
        }

        val bookCount = textbookTitles.values.sumOf { it.size }
        val textbooks = if (bookCount == 0) "暂无教材" else "$bookCount 本"

        val week = semester?.let { cfg ->
            ScheduleCalculator.weekNumberOf(cfg, today).takeIf { it in 1..cfg.totalWeeks }
        }
        val sections = week
            ?.let { ScheduleCalculator.coursesInWeek(courses, it) }
            ?.sumOf { it.endSection - it.startSection + 1 }
            ?: 0
        val schedule = when {
            courses.isEmpty() -> "课表为空"
            week == null -> "共 ${courses.size} 门课"
            sections == 0 -> "第 $week 周无课"
            else -> "第 $week 周 · $sections 节"
        }
        val header = when {
            term.isNullOrBlank() -> "未配置学期"
            week == null -> term.orEmpty()
            else -> "第 $week 周 · $term"
        }

        JwHubStats(
            header = header,
            scoreTerm = latestScoreTerm ?: "最近学期",
            scores = scores,
            scoresShort = scoresShort,
            exams = examText,
            examsShort = examsShort,
            textbooks = textbooks,
            schedule = schedule,
            // 快捷格副标题：理论 / 实验各自的课次数（不是「门」，一门课可能分多块）
            theoryShort = courses.count { it.kind == CourseKind.Theory }
                .takeIf { it > 0 }?.let { "$it 条" } ?: "暂无",
            labShort = courses.count { it.kind == CourseKind.Lab }
                .takeIf { it > 0 }?.let { "$it 条" } ?: "暂无",
        )
    }
}
