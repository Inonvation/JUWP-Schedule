package edu.jxslu.schedule.ui.selection

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.JwImportActivity
import edu.jxslu.schedule.data.repo.SelectionClientException
import edu.jxslu.schedule.data.repo.SelectionSubmitResult
import edu.jxslu.schedule.data.repo.UnconfiguredSelectionCenterClient
import edu.jxslu.schedule.domain.SelectionCourse
import edu.jxslu.schedule.domain.SelectionCourses
import edu.jxslu.schedule.domain.SelectionFilter
import edu.jxslu.schedule.domain.SelectionRound
import edu.jxslu.schedule.domain.SelectionRoundPhase
import edu.jxslu.schedule.domain.SelectionRounds
import edu.jxslu.schedule.domain.SelectionSort
import edu.jxslu.schedule.ui.common.AppCard
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.EmptyHint
import edu.jxslu.schedule.ui.common.LoadingHint
import edu.jxslu.schedule.ui.jwvw.JwImportMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 应用内选课中心（DESIGN §3.22 / §4.36）：**不经过 WebView**，用已存凭证直连接口。
 *
 * 页面：状态卡（轮次 / 汇总 / 未接入与错误说明 / 「打开教务页面」备用入口）→ 搜索与筛选
 * （关键词 + 只看有余额 + 只看已选 + 课程属性 + 排序）→ 课程卡（余量 / 已选标记 /
 * 选课-退课按钮）。
 *
 * [focusRoundId]：选课页点轮次行带进来的直达轮次（走二级页 focusItemId 通道）；
 * 快照里认不出（已过期）就退回 [SelectionRounds.pickTarget] 的自动挑选，不报错。
 *
 * 与抢课的边界（红线，见 §4.36）：**抢课引擎只加课、不自动退课**；这里的退课是用户在
 * 本页逐次确认的**手动**操作。写操作与读操作都走同一个 `SelectionCenterClient`，
 * 真实接口等窗口期实测（现在整页会显示「接口待接入」）。
 *
 * 服务端的开放闸门是真源头：非选课时间教务回**「当前不在选课时间范围内，具体请查看学校
 * 选课通知！」**（2026-10-01 实测），真实 client 应把它原样带上来显示，不要自己编状态。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionCenterScreen(onBack: () -> Unit, focusRoundId: String? = null) {
    val context = LocalContext.current
    val client = remember { Graph.selectionCenterClient(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var rounds by remember { mutableStateOf<List<SelectionRound>>(emptyList()) }
    LaunchedEffect(Unit) {
        rounds = withContext(Dispatchers.IO) { Graph.selectionSync(context).cachedRounds() }
    }
    // 目标轮次：先认点轮次行带进来的直达轮次（focusRoundId），认不出再退回自动挑选
    // （进行中的优先，其次最近一个「即将开始」的；都没有 = 没有可操作的轮次）
    val round = remember(rounds, focusRoundId) {
        rounds.firstOrNull { it.id == focusRoundId }
            ?: SelectionRounds.pickTarget(rounds, System.currentTimeMillis())
    }

    var courses by remember { mutableStateOf<List<SelectionCourse>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf(SelectionFilter()) }
    var sort by remember { mutableStateOf(SelectionSort.Default) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmSubmit by remember { mutableStateOf<SelectionCourse?>(null) }
    var confirmDrop by remember { mutableStateOf<SelectionCourse?>(null) }
    /** 筛选 + 排序后的列表（在组合层算好，LazyColumn 里只消费）。 */
    val shownCourses = remember(courses, filter, sort) {
        SelectionCourses.sort(SelectionCourses.filter(courses, filter), sort)
    }

    fun refresh() {
        val target = round ?: return
        if (loading) return
        loading = true
        loadError = null
        scope.launch {
            try {
                courses = client.fetchCourses(target.id)
            } catch (e: SelectionClientException) {
                loadError = e.message ?: "选课接口不可用"
            } catch (e: Exception) {
                loadError = "拉取课程列表失败：${e.message ?: "未知错误"}"
            }
            loading = false
        }
    }

    // 进页/换轮次自动拉一次；未接入直接把原因摆出来（不打网络）
    LaunchedEffect(round?.id) {
        if (!client.isConfigured) {
            loadError = UnconfiguredSelectionCenterClient.FATAL_HINT
        } else {
            refresh()
        }
    }

    fun submit(course: SelectionCourse) {
        val target = round ?: return
        submitting = submitting + course.id
        scope.launch {
            val result = try {
                client.submit(target.id, course.id)
            } catch (e: SelectionClientException) {
                SelectionSubmitResult.Fatal(e.message ?: "选课接口不可用")
            } catch (e: Exception) {
                SelectionSubmitResult.Rejected("网络异常：${e.message ?: "未知错误"}")
            }
            submitting = submitting - course.id
            when (result) {
                is SelectionSubmitResult.Success -> {
                    // 本地先改状态（下次刷新以教务为准），列表立刻可见反馈
                    courses = courses.map { if (it.id == course.id) it.copy(selected = true) else it }
                    snackbar.showSnackbar("已选上：${course.name}")
                }
                is SelectionSubmitResult.Rejected -> snackbar.showSnackbar("未选上：${result.reason}")
                is SelectionSubmitResult.Fatal -> snackbar.showSnackbar("已停止：${result.reason}")
            }
        }
    }

    fun drop(course: SelectionCourse) {
        val target = round ?: return
        submitting = submitting + course.id
        scope.launch {
            val result = try {
                client.drop(target.id, course.id)
            } catch (e: SelectionClientException) {
                SelectionSubmitResult.Fatal(e.message ?: "选课接口不可用")
            } catch (e: Exception) {
                SelectionSubmitResult.Rejected("网络异常：${e.message ?: "未知错误"}")
            }
            submitting = submitting - course.id
            when (result) {
                is SelectionSubmitResult.Success -> {
                    courses = courses.map { if (it.id == course.id) it.copy(selected = false) else it }
                    snackbar.showSnackbar("已退课：${course.name}")
                }
                is SelectionSubmitResult.Rejected -> snackbar.showSnackbar("退课被拒：${result.reason}")
                is SelectionSubmitResult.Fatal -> snackbar.showSnackbar("已停止：${result.reason}")
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("选课中心", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { refresh() }, enabled = client.isConfigured && !loading) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                },
            )
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                AppCard {
                    Text(
                        round?.name ?: "当前没有进行中的选课轮次",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (round != null && round.timeText.isNotBlank()) {
                        Text(
                            round.timeText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    if (courses.isNotEmpty()) {
                        val summary = SelectionCourses.summarize(courses)
                        Text(
                            "共 ${summary.total} 门 · 已选 ${summary.selected} · 可选 ${summary.selectable}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    loadError?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    if (!client.isConfigured) {
                        OutlinedButton(
                            onClick = {
                                JwImportActivity.start(
                                    context,
                                    JwImportMode.Selection,
                                    selectionRoundId = round?.id,
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                                // min 而不是固定高：系统大字体档位下按钮要能撑高，别把字裁了
                                .heightIn(min = 40.dp),
                        ) { Text("打开教务页面（备用）") }
                    }
                }
            }

            if (loading) {
                item {
                    LoadingHint(
                        "正在拉取课程列表",
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                    )
                }
            } else if (courses.isEmpty() && round != null && loadError == null) {
                item {
                    EmptyHint(
                        title = "暂无课程",
                        body = "教务该轮次的课程列表是空的；选课开放后再试。",
                    )
                }
            }

            if (courses.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = filter.query,
                            onValueChange = { filter = filter.copy(query = it) },
                            label = { Text("搜索课程名 / 教师 / 时间地点") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(
                                selected = filter.onlyAvailable,
                                onClick = { filter = filter.copy(onlyAvailable = !filter.onlyAvailable) },
                                label = { Text("只看有余额") },
                            )
                            FilterChip(
                                selected = filter.onlySelected,
                                onClick = { filter = filter.copy(onlySelected = !filter.onlySelected) },
                                label = { Text("只看已选") },
                            )
                            SelectionCourses.attributes(courses).forEach { attribute ->
                                FilterChip(
                                    selected = filter.attribute == attribute,
                                    onClick = {
                                        filter = filter.copy(
                                            attribute = attribute.takeIf { filter.attribute != attribute },
                                        )
                                    },
                                    label = { Text(attribute) },
                                )
                            }
                            Box {
                                AssistChip(
                                    onClick = { sortMenuOpen = true },
                                    label = { Text("排序：${sortLabel(sort)}") },
                                )
                                DropdownMenu(
                                    expanded = sortMenuOpen,
                                    onDismissRequest = { sortMenuOpen = false },
                                ) {
                                    SelectionSort.entries.forEach { option ->
                                        DropdownMenuItem(
                                            text = { Text(sortLabel(option)) },
                                            onClick = {
                                                sort = option
                                                sortMenuOpen = false
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 注意：筛选/排序结果要在 LazyColumn **外面**算（LazyListScope 不是 @Composable
                // 上下文，里面不能调 remember）
                val shown = shownCourses
                if (shown.isEmpty()) {
                    item {
                        EmptyHint(
                            title = "没有符合条件的课程",
                            body = "换个关键词或清掉筛选再试。",
                        )
                    }
                }
                items(shown, key = { it.id }) { course ->
                    CourseCard(
                        course = course,
                        busy = course.id in submitting,
                        onSelect = { confirmSubmit = course },
                        onDrop = { confirmDrop = course },
                    )
                }
            }

            item {
                Text(
                    "抢课引擎只加课、不自动退课；这里的退课是逐次确认的手动操作。" +
                        "数据以教务为准，操作后可用右上角刷新。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                )
            }
        }
    }

    confirmSubmit?.let { course ->
        AlertDialog(
            onDismissRequest = { confirmSubmit = null },
            title = { Text("确认选课？") },
            text = { Text(courseDetailText(course)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmSubmit = null
                    submit(course)
                }) { Text("选课") }
            },
            dismissButton = {
                TextButton(onClick = { confirmSubmit = null }) { Text("取消") }
            },
        )
    }

    confirmDrop?.let { course ->
        AlertDialog(
            onDismissRequest = { confirmDrop = null },
            title = { Text("确认退课？") },
            text = {
                Text(courseDetailText(course) + "\n\n退课以教务处理结果为准，可能涉及选课规则（如退课次数限制）。")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDrop = null
                    drop(course)
                }) { Text("退课", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDrop = null }) { Text("取消") }
            },
        )
    }
}

private fun sortLabel(sort: SelectionSort): String = when (sort) {
    SelectionSort.Default -> "教务顺序"
    SelectionSort.Remaining -> "余量多优先"
    SelectionSort.Name -> "课程名"
    SelectionSort.Teacher -> "教师"
}

private fun courseDetailText(course: SelectionCourse): String = buildString {
    append(course.name)
    if (course.teacher.isNotBlank()) append("\n教师：").append(course.teacher)
    val time = course.timeText.lines().firstOrNull { it.isNotBlank() }
    if (time != null) append("\n时间：").append(time)
    val place = course.placeText.lines().firstOrNull { it.isNotBlank() }
    if (place != null) append("\n地点：").append(place)
    course.remaining?.let { append("\n余量：").append(it) }
}

@Composable
private fun CourseCard(
    course: SelectionCourse,
    busy: Boolean,
    onSelect: () -> Unit,
    onDrop: () -> Unit,
) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                course.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            RemainingTag(course.remaining)
            if (course.selected) {
                Spacer(Modifier.width(6.dp))
                Text(
                    "已选",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        val meta = listOfNotNull(
            course.teacher.takeIf { it.isNotBlank() },
            course.credit?.let { "${trimCredit(it)} 学分" },
            course.attribute.takeIf { it.isNotBlank() },
            course.category.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        if (meta.isNotBlank()) {
            Text(
                meta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        val times = course.timeText.lines().filter { it.isNotBlank() }
        val places = course.placeText.lines().filter { it.isNotBlank() }
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            if (course.selected) {
                OutlinedButton(onClick = onDrop, enabled = !busy) {
                    Text(if (busy) "提交中…" else "退课")
                }
            } else {
                Button(
                    onClick = onSelect,
                    // 余量为 0 明确禁选；余量未知不禁（交给教务裁定，别在家门口拦）
                    enabled = !busy && (course.remaining?.let { it > 0 } ?: true),
                ) { Text(if (busy) "提交中…" else "选课") }
            }
        }
    }
}

@Composable
private fun RemainingTag(remaining: Int?) {
    if (remaining == null) return
    val label = if (remaining <= 0) "已满" else "余 $remaining"
    val color = if (remaining <= 0) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
    } else {
        MaterialTheme.colorScheme.primary
    }
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = Modifier.padding(start = 8.dp),
    )
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
