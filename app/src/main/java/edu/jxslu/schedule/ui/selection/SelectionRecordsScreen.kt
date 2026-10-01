package edu.jxslu.schedule.ui.selection

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.repo.SelectionSync
import edu.jxslu.schedule.domain.CourseSelection
import edu.jxslu.schedule.ui.common.AppCard
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.EmptyHint
import edu.jxslu.schedule.ui.common.LoadingHint
import kotlinx.coroutines.launch

/**
 * 已选课程（DESIGN §3.19 / §4.35）：教务「选课日志」（`/jsxsd/xkgl/loadXsxkjgList?lx=xkrz`）
 * 按学期展示。2026-10-01 自选课主页挪出独立成页——主页只留入口行，不再平铺记录卡。
 *
 * 页面：学期 chips（默认最新有数据的学期；「更多学期」列出教务学期下拉全量、点选即抓
 * 该学期，抓到为空也留 chip 并显示空态）+ 汇总行（N 门 · X 学分）+ 记录卡。
 *
 * 数据闸门：进页自动同步一次、30 分钟内重复进页直接用缓存（`SelectionSync.MIN_REFRESH_MS`）；
 * 顶栏「更新」= 强制同步。失败不弹窗，用页内空态与气泡说明。只读教务数据，不写课表。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionRecordsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { Graph.selectionRepository(context) }
    val sync = remember { Graph.selectionSync(context) }
    val prefs = remember { Graph.displayPrefs(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // null = Room 流首帧未到（未就绪）；非 null 空列表 = 确实没有记录
    val allState by repo.observeAll().collectAsStateWithLifecycle(initialValue = null)
    val ready = allState != null
    val byTerm = remember(allState) { allState.orEmpty().groupBy { it.term } }
    val terms = remember(byTerm) { byTerm.keys.sortedDescending() }

    // 本地抓到过、但该学期一条记录都没有的学期（也要给 chip 与空态，不然用户以为没加载）
    var emptyTerms by remember { mutableStateOf(setOf<String>()) }
    val chipTerms = remember(terms, emptyTerms) { (terms + emptyTerms).distinct().sortedDescending() }

    var selectedTerm by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(chipTerms) {
        if (selectedTerm == null || selectedTerm !in chipTerms) {
            selectedTerm = chipTerms.firstOrNull()
        }
    }

    var knownTerms by remember { mutableStateOf<List<String>>(emptyList()) }
    var refreshing by remember { mutableStateOf(false) }
    var lastError by remember { mutableStateOf<String?>(null) }
    var showTermPicker by remember { mutableStateOf(false) }
    val moreTerms = remember(knownTerms, chipTerms) { knownTerms.filter { it !in chipTerms } }

    fun refresh(force: Boolean) {
        if (refreshing) return
        refreshing = true
        scope.launch {
            val outcome = sync.refresh(force = force)
            knownTerms = prefs.selectionTerms()
            refreshing = false
            lastError = outcome.failedReason
            // 进页那次自动刷新**失败不进气泡**：有旧数据就用旧的，没数据时由空态说明原因；
            // 手动「更新」才回报结果（成功/失败都要有回声，否则用户以为没反应）
            if (force) {
                val results = outcome.results
                snackbar.showSnackbar(
                    when {
                        outcome.failedReason != null -> "同步失败：${outcome.failedReason}"
                        results is SelectionSync.Result.Updated -> "已更新：${results.rowCount} 条选课记录"
                        outcome.rounds is SelectionSync.RoundsResult.Updated -> "选课轮次已更新"
                        else -> "已是最新"
                    },
                )
            }
        }
    }

    /** 「更多学期」里选中一个：单学期抓一次，抓到但为空也记住（给空态）。 */
    fun loadTerm(term: String) {
        if (refreshing) return
        refreshing = true
        scope.launch {
            when (val result = sync.syncTerm(term)) {
                is SelectionSync.Result.Updated -> {
                    if (result.rowCount == 0) emptyTerms = emptyTerms + term
                    selectedTerm = term
                    snackbar.showSnackbar(
                        if (result.rowCount == 0) "$term 暂无选课记录" else "已加载 $term：${result.rowCount} 条",
                    )
                }
                is SelectionSync.Result.Failed -> snackbar.showSnackbar("加载失败：${result.reason}")
                else -> Unit
            }
            refreshing = false
        }
    }

    LaunchedEffect(Unit) {
        knownTerms = prefs.selectionTerms()
        refresh(force = false)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("已选课程", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { refresh(force = true) }, enabled = !refreshing) {
                        Icon(Icons.Filled.Refresh, contentDescription = "更新")
                    }
                },
            )
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        if (!ready) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                LoadingHint("正在读取选课记录")
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (chipTerms.isEmpty()) {
                item {
                    EmptyHint(
                        title = "还没有选课记录",
                        body = lastError
                            ?: "从教务同步「选课日志」后，这里会按学期展示选课记录。\n" +
                            "只读教务数据，不会写入课表。",
                        actionLabel = "立即同步",
                        onAction = { refresh(force = true) },
                    )
                }
            } else {
                item {
                    TermChips(
                        terms = chipTerms,
                        selected = selectedTerm,
                        onSelect = { selectedTerm = it },
                        hasMore = moreTerms.isNotEmpty(),
                        onMore = { showTermPicker = true },
                    )
                }
                val rows = byTerm[selectedTerm].orEmpty()
                if (rows.isEmpty()) {
                    item {
                        EmptyHint(
                            title = "$selectedTerm 暂无选课记录",
                            body = "教务该学期的选课日志是空的。",
                        )
                    }
                } else {
                    item { TermHeader(term = selectedTerm.orEmpty(), rows = rows) }
                    items(rows, key = { it.id }) { row -> SelectionCard(row) }
                }
            }
        }
    }

    if (showTermPicker) {
        AlertDialog(
            onDismissRequest = { showTermPicker = false },
            title = { Text("加载其他学期") },
            text = {
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(moreTerms, key = { it }) { term ->
                        Text(
                            term,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showTermPicker = false
                                    loadTerm(term)
                                }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTermPicker = false }) { Text("关闭") }
            },
        )
    }
}

@Composable
private fun TermChips(
    terms: List<String>,
    selected: String?,
    onSelect: (String) -> Unit,
    hasMore: Boolean,
    onMore: () -> Unit,
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
        if (hasMore) {
            AssistChip(onClick = onMore, label = { Text("更多学期") })
        }
    }
}

@Composable
private fun TermHeader(term: String, rows: List<CourseSelection>) {
    val credits = rows.sumOf { it.credit }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            "$term · 共 ${rows.size} 门",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Text(
            "${trimCredit(credits)} 学分",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        )
    }
}

/** 一条选课记录（卡面语言与成绩卡/考试卡一致：左主信息、右侧学分）。 */
@Composable
private fun SelectionCard(row: CourseSelection) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (row.credit > 0.0) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "${trimCredit(row.credit)} 学分",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                )
            }
        }
        val meta = listOf(row.attribute, row.category).filter { it.isNotBlank() }.joinToString(" · ")
        if (meta.isNotBlank()) {
            Text(
                meta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        val teacherLine = listOf(row.teacher, row.className).filter { it.isNotBlank() }.joinToString(" · ")
        if (teacherLine.isNotBlank()) {
            Text(
                teacherLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        // 上课时间与地点按行对齐（教务两列同为 <br> 分隔，行数一般一一对应；对不齐就各自展示）
        val times = row.timeText.lines().filter { it.isNotBlank() }
        val places = row.placeText.lines().filter { it.isNotBlank() }
        for (index in 0 until maxOf(times.size, places.size)) {
            val line = listOfNotNull(times.getOrNull(index), places.getOrNull(index))
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            if (line.isNotBlank()) {
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        val audit = listOf(row.status, row.remark).filter { it.isNotBlank() }.joinToString(" · ")
        if (audit.isNotBlank()) {
            Text(
                audit,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** 学分展示：整数不带小数点（2.0 → 2），小数最多两位。 */
private fun trimCredit(value: Double): String {
    val rounded = Math.round(value * 100) / 100.0
    return if (rounded == rounded.toLong().toDouble()) {
        rounded.toLong().toString()
    } else {
        rounded.toString().trimEnd('0').trimEnd('.')
    }
}
