package edu.jxslu.schedule.ui.jw

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.jw.JwTeachingEval
import edu.jxslu.schedule.data.jw.JwUrls
import edu.jxslu.schedule.data.jw.PtworkTranscript
import edu.jxslu.schedule.data.xg.XgUrls
import edu.jxslu.schedule.data.repo.ExamSnapshotStore
import edu.jxslu.schedule.domain.CampusCalendarSchoolRules
import edu.jxslu.schedule.domain.ScheduleCalculator
import edu.jxslu.schedule.domain.ScoreCalculator
import java.time.LocalDate
import edu.jxslu.schedule.ui.common.LocalBottomBarClearance
import edu.jxslu.schedule.ui.common.pinnedStatusBars
import edu.jxslu.schedule.ui.common.rememberResumeTick
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Analytics01
import me.rerere.hugeicons.stroke.Book01
import me.rerere.hugeicons.stroke.Calendar01
import me.rerere.hugeicons.stroke.CheckmarkCircle02
import me.rerere.hugeicons.stroke.ChevronRight
import me.rerere.hugeicons.stroke.File01
import me.rerere.hugeicons.stroke.ExternalLink
import me.rerere.hugeicons.stroke.Layers01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Task01
import me.rerere.hugeicons.stroke.Toolbox

/**
 * 教务 Tab（2026-10-20，新 IA）。
 *
 * 入口汇总页，不新建数据层：每个入口落到原来的二级页；右侧「网页」是核对动作，
 * 打开教务/学工/签章系统的原始页面。分类按用户口径：
 * 本学期 → 学业与规划 → 教务服务（选课在这里）→ 学工与校历（学生评教在这里）→ 同步。
 *
 * 排版口径（2026-10-20 第二版）：**不画卡片**。上一版的 2×2 数据卡 + 顶部「本学期」色块
 * 既占地方又碎；这里统一成 54dp 的列表行，图标 + 标题/副标题 + 「网页」胶囊 + 箭头，
 * 只有分段标题做层级。网页胶囊有独立高度和命中区，图标不再被 26dp 的 IconButton 挤小。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JwHubScreen(
    onOpenScores: () -> Unit,
    onOpenExams: () -> Unit,
    onOpenTextbooks: () -> Unit,
    onOpenSchedule: () -> Unit,
    onOpenScholar: () -> Unit,
    onOpenCurriculum: () -> Unit,
    onOpenSelection: () -> Unit,
    onOpenInternship: () -> Unit,
    onOpenTranscript: () -> Unit,
    onOpenStudentInfo: () -> Unit,
    onOpenTeachingCalendar: () -> Unit,
    onOpenTeachingEval: () -> Unit,
    onOpenLeave: () -> Unit,
    onOpenRepair: () -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenImport: () -> Unit,
    onWebCheck: (title: String, url: String) -> Unit,
) {
    val clearance = LocalBottomBarClearance.current
    val muted = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    val stats = rememberJwHubStats()

    val sections = listOf(
        "本学期" to listOf(
            HubEntry(
                title = "成绩查询",
                subtitle = stats.scores,
                icon = HugeIcons.Analytics01,
                onClick = onOpenScores,
                onWeb = { onWebCheck("成绩查询", JwUrls.SCORE_FRM) },
            ),
            HubEntry(
                title = "考试安排",
                subtitle = stats.exams,
                icon = HugeIcons.Calendar01,
                onClick = onOpenExams,
                onWeb = { onWebCheck("考试安排", JwUrls.EXAM_QUERY) },
            ),
            HubEntry(
                title = "教材",
                subtitle = stats.textbooks,
                icon = HugeIcons.Book01,
                onClick = onOpenTextbooks,
                onWeb = { onWebCheck("教材", JwUrls.TEXTBOOK_QUERY) },
            ),
            HubEntry(
                title = "课表核对",
                subtitle = stats.schedule,
                icon = HugeIcons.Task01,
                onClick = onOpenSchedule,
                onWeb = { onWebCheck("课表", JwUrls.SCHEDULE_LIST) },
            ),
        ),
        "学业与规划" to listOf(
            HubEntry(
                title = "学业完成情况",
                subtitle = "培养方案达成度",
                icon = HugeIcons.Analytics01,
                onClick = onOpenScholar,
                onWeb = { onWebCheck("学业完成情况", JwUrls.SCHOLAR_PROGRESS) },
            ),
            HubEntry(
                title = "培养方案",
                subtitle = "课程设置总表",
                icon = HugeIcons.Book01,
                onClick = onOpenCurriculum,
                onWeb = { onWebCheck("培养方案", JwUrls.CURRICULUM) },
            ),
            HubEntry(
                title = "实习基地",
                subtitle = "基地清单",
                icon = HugeIcons.Layers01,
                onClick = onOpenInternship,
                onWeb = { onWebCheck("实习基地", JwUrls.INTERNSHIP_BASES_SHELL) },
            ),
        ),
        "教务服务" to listOf(
            HubEntry(
                title = "选课",
                subtitle = "记录 · 轮次 · 进入选课",
                icon = HugeIcons.Task01,
                onClick = onOpenSelection,
                onWeb = { onWebCheck("选课", JwUrls.SELECTION_CENTER) },
            ),
            HubEntry(
                title = "成绩单",
                subtitle = "盖章 PDF · 最近导出",
                icon = HugeIcons.File01,
                onClick = onOpenTranscript,
                onWeb = { onWebCheck("成绩单", PtworkTranscript.CAS_ENTRY) },
            ),
            HubEntry(
                title = "学籍信息",
                subtitle = "学号 · 专业 · 班级",
                icon = HugeIcons.File01,
                onClick = onOpenStudentInfo,
                onWeb = { onWebCheck("学籍信息", JwUrls.STUDENT_CARD) },
            ),
            HubEntry(
                title = "教学日历",
                subtitle = "第 1 周周一 · 校历",
                icon = HugeIcons.Calendar01,
                onClick = onOpenTeachingCalendar,
                onWeb = { onWebCheck("教学日历", JwUrls.JXZL_QUERY) },
            ),
        ),
        "学工与校历" to listOf(
            HubEntry(
                title = "学生评教",
                subtitle = "评完解锁成绩",
                icon = HugeIcons.CheckmarkCircle02,
                onClick = onOpenTeachingEval,
                onWeb = {
                    onWebCheck("学生评教", JwUrls.XSD_BASE + JwTeachingEval.TOKEN_PATH)
                },
            ),
            HubEntry(
                title = "请假",
                subtitle = "学工系统",
                icon = HugeIcons.File01,
                onClick = onOpenLeave,
                onWeb = { onWebCheck("请假", XgUrls.HOME) },
            ),
            HubEntry(
                title = "宿舍报修",
                subtitle = "学工系统 · 进度",
                icon = HugeIcons.Toolbox,
                onClick = onOpenRepair,
                onWeb = { onWebCheck("宿舍报修", XgUrls.OFFICE) },
            ),
            HubEntry(
                title = "校历",
                subtitle = "学年校历",
                icon = HugeIcons.Calendar01,
                onClick = onOpenCalendar,
                onWeb = { onWebCheck("校历", CampusCalendarSchoolRules.BASE) },
            ),
        ),
    )

    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = pinnedStatusBars(),
                title = {
                    Column {
                        Text("教务", fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "数据来自教务系统 · 以网页为准",
                            style = MaterialTheme.typography.bodySmall,
                            color = muted,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { onWebCheck("教务首页", JwUrls.STUDENT_HOME) }) {
                        Icon(HugeIcons.ExternalLink, contentDescription = "在教务网页核对")
                    }
                    IconButton(onClick = onOpenImport) {
                        Icon(HugeIcons.Refresh01, contentDescription = "从教务导入")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 16.dp,
                top = 4.dp,
                bottom = 20.dp + clearance,
            ),
        ) {
            sections.forEach { (title, entries) ->
                item(key = "section-$title") { SectionLabel(title) }
                items(entries, key = { it.title }) { entry -> HubRow(entry) }
            }

            item(key = "sync") {
                Column {
                    SectionLabel("同步")
                    HubRow(
                        HubEntry(
                            title = "从教务导入",
                            subtitle = "课表 · 成绩 · 考试 · 教材",
                            icon = HugeIcons.Refresh01,
                            onClick = onOpenImport,
                        ),
                    )
                    Text(
                        text = "打开应用时会自动同步课表、成绩、考试与教材，失败静默。",
                        style = MaterialTheme.typography.labelSmall,
                        color = muted,
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                    )
                }
            }

            item(key = "footer") {
                Text(
                    text = "数据来自教务系统，非学校官方应用；有疑问以教务网页为准。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    modifier = Modifier.padding(start = 4.dp, top = 20.dp, end = 4.dp),
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
    val onWeb: (() -> Unit)? = null,
)

@Composable
private fun SectionLabel(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        fontSize = 12.sp,
        letterSpacing = 0.6.sp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        modifier = Modifier.padding(start = 4.dp, top = 18.dp, bottom = 2.dp),
    )
}

@Composable
private fun HubRow(entry: HubEntry) {
    val muted = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClickLabel = entry.title, onClick = entry.onClick)
            .heightIn(min = 54.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = entry.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            modifier = Modifier.size(21.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = entry.subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (entry.onWeb != null) {
            WebChip(onClick = entry.onWeb)
            Spacer(Modifier.width(2.dp))
        }
        Icon(
            imageVector = HugeIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.24f),
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * 「网页」胶囊。
 *
 * 上一版是 26dp 的 IconButton 里塞 14dp 图标，命中区与图标一起被压扁、看着像被裁了一半；
 * 这里给 30dp 固定高度 + 10dp 横向内边距，图标 15dp，整块都是命中区。
 */
@Composable
private fun WebChip(onClick: () -> Unit) {
    val tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    Row(
        modifier = Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(99.dp))
            .clickable(onClickLabel = "在教务网页核对", onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = HugeIcons.ExternalLink,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = "网页",
            fontSize = 11.sp,
            color = tint,
        )
    }
}

/** 「本学期」四行的实时数字（2026-10-20）。 */
private data class JwHubStats(
    val scores: String,
    val exams: String,
    val textbooks: String,
    val schedule: String,
)

/**
 * 从现有仓库拼出四行副标题——**不新建数据层**，四个数都来自已经落库/落盘的东西：
 *
 * - 成绩：当前学期号 → `ScoreCalculator.summarize`（与成绩页同一个汇总口径，
 *   加权平均默认不含任选课）；
 * - 考试：`ExamSnapshotStore` 的快照文件（考试提醒那条链写的，回前台重读一次）；
 * - 教材：当前课表学期的教材表（`textbookTitlesByCourse` 已按学期取好）；
 * - 课表：当前周次 + `coursesInWeek` 的节次合计（小节号，与网格同一口径）。
 *
 * 取不到就明说（「暂无成绩」「课表为空」），不留一个看起来像数据位的空串。
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

    // 学期号是 suspend 读（要查课表行）：进页 / 回前台各读一次
    var term by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(resumeTick) {
        term = runCatching { repo.currentTerm() }.getOrNull()
    }
    // 考试快照是文件，后台同步会改写它：同样回前台重读
    val examSnapshot = remember(resumeTick) {
        runCatching { ExamSnapshotStore(context).load() }.getOrNull()
    }

    return remember(semester, courses, scoresByTerm, textbookTitles, term, examSnapshot) {
        val today = LocalDate.now()

        val summary = term?.let { t -> scoresByTerm[t]?.let { ScoreCalculator.summarize(t, it) } }
        val scores = when {
            summary == null || summary.courseCount == 0 -> "本学期暂无成绩"
            else -> buildString {
                append("${summary.courseCount} 门")
                summary.weightedAverage?.let { append(" · 加权 ${"%.1f".format(it)}") }
            }
        }

        val exams = examSnapshot?.exams.orEmpty().filter { it.date.isNotBlank() }
        val upcoming = exams.filter { it.date >= today.toString() }
        val examText = when {
            exams.isEmpty() -> "暂无考试安排"
            upcoming.isNotEmpty() ->
                "${upcoming.size} 场 · 最近 ${upcoming.minOf { it.date }.substring(5)}"
            else -> "${exams.size} 场 · 均已结束"
        }

        val bookCount = textbookTitles.values.sumOf { it.size }
        val textbooks = if (bookCount == 0) "本学期暂无教材" else "$bookCount 本 · 本学期"

        val week = semester?.let { cfg ->
            ScheduleCalculator.weekNumberOf(cfg, today).takeIf { it in 1..cfg.totalWeeks }
        }
        val sections = week
            ?.let { ScheduleCalculator.coursesInWeek(courses, it) }
            ?.sumOf { it.endSection - it.startSection + 1 }
            ?: 0
        val schedule = when {
            courses.isEmpty() -> "课表为空 · 点开导入"
            week == null -> "共 ${courses.size} 门课"
            sections == 0 -> "第 $week 周无课"
            else -> "第 $week 周 · $sections 节"
        }

        JwHubStats(scores = scores, exams = examText, textbooks = textbooks, schedule = schedule)
    }
}
