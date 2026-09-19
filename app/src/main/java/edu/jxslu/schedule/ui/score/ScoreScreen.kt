package edu.jxslu.schedule.ui.score

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import edu.jxslu.schedule.domain.ScoreCalculator
import edu.jxslu.schedule.domain.ScoreRecord
import edu.jxslu.schedule.domain.TermSummary
import edu.jxslu.schedule.ui.common.EmptyHint
import edu.jxslu.schedule.ui.jwvw.JwImportMode
import kotlinx.coroutines.launch

/**
 * 成绩查询（DESIGN §4.15）：按学期切换展示 + 学期汇总 + 课程卡列表。
 *
 * 数据全局归属学生、按学期整体替换；页内「导入」复用教务 WebView（[JwImportMode.Scores]），
 * 「删除」清空当前学期（带确认）。评教未完成（pendingReview）的课程不显示分数也不进统计，
 * 单独呈现「待评教」标记，避免用户误以为成绩丢失。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScoreScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scoreRepo = remember { Graph.scoreRepository(context) }
    val scope = rememberCoroutineScope()

    val terms by scoreRepo.observeTerms().collectAsState(initial = emptyList())
    var selectedTerm by remember { mutableStateOf<String?>(null) }
    // 学期列表到位后的默认选择：最新有数据的学期；用户切换后以用户为准
    LaunchedEffect(terms) {
        if (selectedTerm == null || selectedTerm !in terms) {
            selectedTerm = terms.firstOrNull()
        }
    }
    val records by scoreRepo
        .observeForTerm(selectedTerm.orEmpty())
        .collectAsState(initial = emptyList())
    val summaries by scoreRepo.observeSummaries().collectAsState(initial = emptyList())
    val summary = summaries.firstOrNull { it.term == selectedTerm }

    var confirmDelete by remember { mutableStateOf(false) }

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
                    if (selectedTerm != null) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "清空本学期")
                        }
                    }
                },
            )
        },
    ) { padding ->
        when {
            terms.isEmpty() -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
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
                }
            }
            else -> {
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
                        TermChips(
                            terms = terms,
                            selected = selectedTerm,
                            onSelect = { selectedTerm = it },
                        )
                    }
                    item { SummaryCard(summary, total = records.size) }
                    // 不设自定义 key：同学期同课号可能有多行（补考/重修批次），任何业务键组合
                    // 都可能撞 key 导致 LazyColumn 直接抛异常；本列表静态无重排，默认位置键即可
                    items(records) { record ->
                        ScoreCard(record)
                    }
                    item {
                        Text(
                            "学期数据按整体替换存储；重新导入相同学期会覆盖。最长保留到手动删除。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("清空本学期成绩？") },
            text = {
                Text("将删除「${selectedTerm.orEmpty()}」的全部成绩记录；需要时可重新从教务导入。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val term = selectedTerm
                        confirmDelete = false
                        if (term != null) {
                            scope.launch { scoreRepo.deleteTerm(term) }
                        }
                    },
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun TermChips(
    terms: List<String>,
    selected: String?,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        terms.forEach { term ->
            FilterChip(
                selected = term == selected,
                onClick = { onSelect(term) },
                label = { Text(term) },
            )
        }
    }
}

@Composable
private fun SummaryCard(summary: TermSummary?, total: Int) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                if (summary?.weightedAverage != null) {
                    Text(
                        text = trimNum(summary.weightedAverage),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "  加权平均分",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                }
                if (summary?.gpa != null) {
                    Spacer(Modifier.width(16.dp))
                    Text(
                        text = trimNum(summary.gpa),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "  平均绩点",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            val note = buildString {
                append("共 $total 门")
                if (summary != null && summary.lockedCount > 0) {
                    append(" · ${summary.lockedCount} 门待评教暂不显示分数")
                }
                if (summary != null && summary.courseCount <= 0) append(" · 暂无有效分数")
            }
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun ScoreCard(record: ScoreRecord) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
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

/** 去掉无意义的尾部 0：3.0 → 3、84.50 → 84.5、84.0 → 84。 */
private fun trimNum(v: Double?): String {
    if (v == null) return "—"
    var s = "%.2f".format(v)
    s = s.trimEnd('0').trimEnd('.')
    return s.ifEmpty { "0" }
}
