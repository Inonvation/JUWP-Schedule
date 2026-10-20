package edu.jxslu.schedule.ui.score

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.JwImportActivity
import edu.jxslu.schedule.SubpageActivity
import edu.jxslu.schedule.SubpageScreen
import edu.jxslu.schedule.TranscriptActivity
import edu.jxslu.schedule.domain.ScoreCalculator
import edu.jxslu.schedule.domain.ScoreGroups
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.showNotice
import edu.jxslu.schedule.ui.jwvw.JwImportOutcomeEffect
import edu.jxslu.schedule.domain.ScoreRecord
import edu.jxslu.schedule.domain.ScoreSortMode
import edu.jxslu.schedule.domain.TermSummary
import edu.jxslu.schedule.domain.YearGroup
import edu.jxslu.schedule.ui.common.EmptyHint
import edu.jxslu.schedule.ui.common.AppCard
import edu.jxslu.schedule.ui.common.AppCardRow
import edu.jxslu.schedule.ui.common.LoadingHint
import edu.jxslu.schedule.ui.common.SectionHeader
import edu.jxslu.schedule.ui.jwvw.JwImportMode
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.BellRing

/** 「全部」档的选中哨兵值：学期号/学年号不可能出现的字符串，避免与真实数据撞值。 */
private const val SCOPE_ALL = "\u0000all"

/**
 * 成绩查询（DESIGN §4.15）：按学期 / 按学年两种分组切换展示 + 汇总 + 课程卡列表，
 * 两种视图首位都有「全部」档（全量聚合：总加权平均 / 总绩点 / 总修得学分）。
 *
 * 数据全局归属学生、按学期整体替换；页内「导入」复用教务 WebView（[JwImportMode.Scores]），
 * 清空学期已迁到「成绩设置」的「数据」段（选择学期后二次确认）。
 * 评教未完成（pendingReview）的课程不显示分数也不进统计，单独呈现「待评教」标记，
 * 避免用户误以为成绩丢失。
 *
 * 顶部「导出带章成绩单」（DESIGN §3.14）是另一个数据源：出的是教务处签章系统生成的正式单据，
 * 与本页的本地成绩库无关，所以放在学期/学年分组之外（不随 chip 变化）。
 *
 * 列表排序（DESIGN §4.15）：设置页三档——默认/成绩/绩点，只作用于学期分组内部；
 * 加权平均分与平均绩点不计任选课（通识任选、专业任选等），汇总卡展示计算公式并标注「仅供参考」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScoreScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scoreRepo = remember { Graph.scoreRepository(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // 成绩导入成功的气泡（DESIGN §3.3）：导入窗口 finish 后落回的是这一页
    JwImportOutcomeEffect(snackbar)

    // null = Room 流首帧未到（未就绪），非 null 空列表 = 确实没有成绩。
    // 不区分的话，首帧会先渲染「还没有成绩」空态再跳真实数据（与今日页 ready
    // 门闸同根因的闪屏，今日页修在 ViewModel，这里数据流是冷流直订，收口在 UI 侧）
    val termsState by scoreRepo.observeTerms()
        .collectAsState(initial = null)
    val allByTermState by scoreRepo.observeAllGroupedByTerm()
        .collectAsState(initial = null)
    val ready = termsState != null && allByTermState != null
    val terms = termsState.orEmpty()
    val allByTerm = allByTermState.orEmpty()
    val yearGroups = remember(allByTerm) { ScoreGroups.group(allByTerm.keys.toList()) }

    // 分组模式/排序/任选课口径持久化在 DataStore（DESIGN §4.15）：重进页面不重置；
    // 学期内具体选中项页内记忆即可
    val prefsStore = remember { Graph.displayPrefs(context) }
    val includeFreeElectives by prefsStore.scoreIncludeFreeElectives.collectAsState(initial = false)
    val groupByYear by prefsStore.scoreGroupByYear.collectAsState(initial = false)
    val sortMode by prefsStore.scoreSortMode.collectAsState(initial = ScoreSortMode.Default)
    var selectedTerm by remember { mutableStateOf<String?>(null) }
    var selectedYear by remember { mutableStateOf<String?>(null) }

    // 列表到位后的默认选择：最新有数据的学期/学年；用户切换后以用户为准
    LaunchedEffect(terms) {
        if (selectedTerm == null || (selectedTerm != SCOPE_ALL && selectedTerm !in terms)) {
            selectedTerm = terms.firstOrNull()
        }
    }
    LaunchedEffect(yearGroups) {
        if (selectedYear != SCOPE_ALL && yearGroups.none { it.academicYear == selectedYear }) {
            selectedYear = yearGroups.firstOrNull()?.academicYear
        }
    }

    val selectedGroup = yearGroups.firstOrNull { it.academicYear == selectedYear }
    val summary: TermSummary? = when {
        groupByYear && selectedYear == SCOPE_ALL ->
            ScoreCalculator.summarize("全部", allByTerm.values.flatten(), includeFreeElectives)
        groupByYear -> selectedGroup?.let { group ->
            ScoreCalculator.summarize(
                group.academicYear,
                group.terms.flatMap { allByTerm[it].orEmpty() },
                includeFreeElectives,
            )
        }
        selectedTerm == SCOPE_ALL ->
            ScoreCalculator.summarize("全部", allByTerm.values.flatten(), includeFreeElectives)
        else -> allByTerm[selectedTerm]?.let {
            ScoreCalculator.summarize(selectedTerm.orEmpty(), it, includeFreeElectives)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("成绩查询", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        JwImportActivity.start(context, JwImportMode.Scores)
                    }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "从教务导入")
                    }
                    IconButton(onClick = { SubpageActivity.start(context, SubpageScreen.SCORE_SETTINGS) }) {
                        Icon(Icons.Filled.Settings, contentDescription = "成绩设置")
                    }
                },
            )
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        when {
            // 未就绪：水滴呼吸加载态，避免「还没有成绩」空态闪现
            !ready -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    LoadingHint("正在读取成绩")
                }
            }
            terms.isEmpty() -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Center,
                ) {
                    EmptyHint(
                        title = "还没有成绩",
                        body = "从教务导入全部学期的成绩后，这里会按学期展示。\n导入只读教务数据，不会写入课表。",
                        actionLabel = "去导入",
                        onAction = {
                            JwImportActivity.start(context, JwImportMode.Scores)
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                    // 本地没导入过成绩，也照样能从教务处签章系统导出正式单据（两边数据源不同）
                    ExportTranscriptCard(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        onClick = { TranscriptActivity.start(context) },
                    )
                    Spacer(Modifier.height(10.dp))
                    // 提醒等出分是主场景之一：空态给一行入口卡（与导出卡同语言，
                    // 开关与间隔在「成绩设置」页承载——别在这再长出一个设置块）
                    AppCardRow(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        onClick = { SubpageActivity.start(context, SubpageScreen.SCORE_SETTINGS) },
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "出分提醒",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "新出成绩或复查改分时通知 · 在成绩设置里开启",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            )
                        }
                        Icon(
                            HugeIcons.BellRing,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            else -> {
                val rows: List<ScoreRow> = when {
                    // 「全部」档：按学期小节头平铺全量（两种分组模式同一口径）
                    (!groupByYear && selectedTerm == SCOPE_ALL) ||
                        (groupByYear && selectedYear == SCOPE_ALL) ->
                        allByTerm.entries.sortedByDescending { it.key }.flatMap { (term, list) ->
                            listOf(ScoreRow.Header(term, list.size)) +
                                ScoreCalculator.sort(list, sortMode).map { ScoreRow.Record(it) }
                        }
                    groupByYear -> selectedGroup?.terms?.flatMap { term ->
                        val list = allByTerm[term].orEmpty()
                        listOf(ScoreRow.Header(term, list.size)) +
                            ScoreCalculator.sort(list, sortMode).map { ScoreRow.Record(it) }
                    }.orEmpty()
                    else -> ScoreCalculator.sort(allByTerm[selectedTerm].orEmpty(), sortMode)
                        .map { ScoreRow.Record(it) }
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 16.dp,
                        vertical = 12.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        AppCard(contentPadding = PaddingValues(12.dp)) {
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                SegmentedButton(
                                    selected = !groupByYear,
                                    onClick = { scope.launch { prefsStore.setScoreGroupByYear(false) } },
                                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                                ) { Text("按学期") }
                                SegmentedButton(
                                    selected = groupByYear,
                                    onClick = { scope.launch { prefsStore.setScoreGroupByYear(true) } },
                                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                                ) { Text("按学年") }
                            }
                            Spacer(Modifier.height(8.dp))
                            if (groupByYear) {
                                YearChips(
                                    groups = yearGroups,
                                    selected = selectedYear,
                                    onSelect = { selectedYear = it },
                                )
                            } else {
                                TermChips(
                                    terms = terms,
                                    selected = selectedTerm,
                                    onSelect = { selectedTerm = it },
                                )
                            }
                        }
                    }
                    item {
                        SummaryCard(
                            summary,
                            total = rows.count { it is ScoreRow.Record },
                            includeFreeElectives = includeFreeElectives,
                        )
                    }
                    item {
                        SectionHeader(
                            title = "成绩明细",
                            trailing = "${rows.count { it is ScoreRow.Record }} 门",
                            horizontalPadding = 0.dp,
                            topPadding = 6.dp,
                            bottomPadding = 0.dp,
                        )
                    }
                    // 不设自定义 key：同学期同课号可能有多行（补考/重修批次），任何业务键组合
                    // 都可能撞 key 导致 LazyColumn 直接抛异常；本列表静态无重排，默认位置键即可
                    items(rows) { row ->
                        when (row) {
                            is ScoreRow.Header -> TermSectionHeader(row.term, row.count)
                            is ScoreRow.Record -> ScoreCard(row.record)
                        }
                    }
                    item {
                        ExportTranscriptCard(
                            onClick = { TranscriptActivity.start(context) },
                        )
                    }
                    item {
                        Text(
                            "数据按学期整体替换存储；重新导入相同学期会覆盖。" +
                                "清空学期在「成绩设置」里操作。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }

}

/** 列表行：按学年视图里学期小节头与成绩卡混排。 */
private sealed interface ScoreRow {
    data class Header(val term: String, val count: Int) : ScoreRow
    data class Record(val record: ScoreRecord) : ScoreRow
}

@Composable
private fun TermChips(
    terms: List<String>,
    selected: String?,
    onSelect: (String) -> Unit,
) {
    GroupChips(
        items = listOf("全部" to SCOPE_ALL) + terms.map { it to it },
        selected = selected,
        onSelect = onSelect,
    )
}

@Composable
private fun YearChips(
    groups: List<YearGroup>,
    selected: String?,
    onSelect: (String) -> Unit,
) {
    GroupChips(
        items = listOf("全部" to SCOPE_ALL) + groups.map { g ->
            val label = g.gradeLabel?.let { "$it ${g.academicYear}" } ?: g.academicYear
            label to g.academicYear
        },
        selected = selected,
        onSelect = onSelect,
    )
}

@Composable
private fun GroupChips(
    items: List<Pair<String, String>>,
    selected: String?,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { (label, value) ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun SummaryCard(
    summary: TermSummary?,
    total: Int,
    includeFreeElectives: Boolean,
) {
    AppCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SummaryMetric(
                label = "加权平均",
                value = summary?.weightedAverage?.let(::trimNum) ?: "—",
                modifier = Modifier.weight(1f),
            )
            SummaryMetric(
                label = "平均绩点",
                value = summary?.gpa?.let(::trimNum) ?: "—",
                modifier = Modifier.weight(1f),
            )
        }
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 10.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        )
        val note = buildString {
            append("共 $total 门")
            if (summary != null && summary.credits > 0.0) {
                append(" · 修得 ${trimNum(summary.credits)} 学分")
            }
            if (summary != null && summary.excludedCount > 0) {
                if (includeFreeElectives) {
                    append(" · 已计入 ${summary.excludedCount} 门任选课")
                } else {
                    append(" · 已不计 ${summary.excludedCount} 门任选课")
                }
            }
            if (summary != null && summary.lockedCount > 0) {
                append(" · ${summary.lockedCount} 门待评教暂不显示分数")
            }
            if (summary != null && summary.courseCount <= 0) append(" · 暂无有效分数")
        }
        Text(
            note,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
        )
        Spacer(Modifier.height(3.dp))
        // 公式已收进「成绩设置」；卡上只留一句口径去向，避免首屏被公式占满。
        Text(
            "仅供参考 · 统计口径在「成绩设置」里调整",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
        )
    }
}

@Composable
private fun SummaryMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** 学年内按学期分组的小节头：一门不落都看得出来源学期。 */
@Composable
private fun TermSectionHeader(term: String, count: Int) {
    Text(
        "$term · ${count}门",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun ScoreCard(record: ScoreRecord) {
    AppCard {
        Row(
            modifier = Modifier
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = record.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val tags = buildList {
                    if (record.category.isNotBlank()) add(record.category)
                    if (record.examForm.isNotBlank()) add(record.examForm)
                    if (record.status.isNotBlank() && record.status != "正常考试") add(record.status)
                    if (record.credit > 0.0) add("${trimNum(record.credit)} 学分")
                }
                if (tags.isNotEmpty()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        tags.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                if (record.pendingReview) {
                    Box(
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.secondaryContainer,
                                RoundedCornerShape(8.dp),
                            )
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text(
                            "待评教",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                } else {
                    Text(
                        text = record.scoreStr.ifBlank { "—" },
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (record.gradePoint != null) {
                        Text(
                            "绩点 ${trimNum(record.gradePoint)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 「导出带章成绩单」入口卡（DESIGN §3.14）。
 *
 * 与「从教务导入」并列但不同源：导入读的是强智成绩接口（本地按学期存），
 * 导出拿的是签章系统生成的正式 PDF。文案里把「教务处电子签章」写出来，
 * 因为这个入口的实际用途就是评优评先要交的那张单据。
 */
@Composable
private fun ExportTranscriptCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AppCardRow(modifier = modifier, onClick = onClick) {
        Column(Modifier.weight(1f)) {
            Text(
                "导出带章成绩单",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "教务处电子签章 PDF · 可选学期 · 评优评先可用",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        Icon(
            Icons.Filled.FileDownload,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

/** 去掉无意义的尾部 0：3.0 → 3、84.50 → 84.5、84.0 → 84。 */
private fun trimNum(v: Double?): String {
    if (v == null) return "—"
    var s = "%.2f".format(v)
    s = s.trimEnd('0').trimEnd('.')
    return s.ifEmpty { "0" }
}

