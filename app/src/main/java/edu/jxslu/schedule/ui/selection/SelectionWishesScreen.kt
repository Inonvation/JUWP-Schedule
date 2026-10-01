package edu.jxslu.schedule.ui.selection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import edu.jxslu.schedule.domain.CourseSelection
import edu.jxslu.schedule.domain.SelectionWish
import edu.jxslu.schedule.domain.SelectionWishes
import edu.jxslu.schedule.ui.common.AppCard
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.EmptyHint
import edu.jxslu.schedule.ui.common.LoadingHint
import kotlinx.coroutines.launch

/**
 * 预选清单（DESIGN §3.20，我的 → 学习 → 选课 → 预选清单）。
 *
 * 选课开放前录入「想选的课」：课程名关键词（必填、包含匹配）+ 教师名关键词（可选）+ 优先级。
 * 选课开放后抢课引擎按优先级匹配教务课程列表（DESIGN §4.36，窗口期联调后接通）。
 *
 * **防重复对照**（2026-10-01）：每条预选在「已选课程」（Room 选课日志）与「当前课表」
 * 里做一次本地匹配（同一套 [SelectionWishes.matches] 口径），命中就在卡上标出来——
 * 录清单最常见的错是这门课其实已经选过/课表里已有，不用等接口，纯本机数据就能拦一道。
 *
 * 只在本机保存（DataStore JSON，口径同快捷方式）；页面明说「只在你手动开始后发出指令」，
 * 不让用户以为录完清单就会自动抢。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionWishesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Graph.displayPrefs(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // null = DataStore 首帧未到；非 null 空列表 = 确实没有条目
    val wishesState by prefs.selectionWishes.collectAsStateWithLifecycle(initialValue = null)
    val sorted = remember(wishesState) { SelectionWishes.sorted(wishesState.orEmpty()) }

    // 防重复对照的数据源：已选课程（null = Room 流首帧未到，先不标）与当前课表课程
    val selectionsState by remember { Graph.selectionRepository(context) }.observeAll()
        .collectAsStateWithLifecycle(initialValue = null)
    val timetableCourses by remember { Graph.repository(context) }.courses
        .collectAsStateWithLifecycle(initialValue = emptyList())

    var editing by remember { mutableStateOf<SelectionWish?>(null) }
    var deleting by remember { mutableStateOf<SelectionWish?>(null) }

    // 每条预选的防重复提示，组合层一次算好（清单 × 已选 × 课表，量级很小不值得流式）
    val dupHints = remember(wishesState, selectionsState, timetableCourses) {
        val selections = selectionsState.orEmpty()
        wishesState.orEmpty().associate { wish ->
            wish.id to buildList {
                selections.firstOrNull { SelectionWishes.matches(wish, it.name, it.teacher) }?.let {
                    add(DupLine("已选：" + it.name + if (it.term.isNotBlank()) "（${it.term}）" else "", true))
                }
                timetableCourses.firstOrNull { SelectionWishes.matches(wish, it.name, it.teacher) }?.let {
                    add(DupLine("课表已有：" + it.name, false))
                }
            }
        }
    }

    fun persist(next: List<SelectionWish>) {
        scope.launch { prefs.setSelectionWishes(next) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("预选清单", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { editing = SelectionWish() }) {
                        Icon(Icons.Filled.Add, contentDescription = "添加")
                    }
                },
            )
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        when {
            wishesState == null -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                LoadingHint("正在读取预选清单")
            }
            sorted.isEmpty() -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center,
            ) {
                EmptyHint(
                    title = "还没有预选课程",
                    body = "选课开放前把想选的课录在这里：填课程名关键词（必填）与教师名（可选），" +
                        "再标一个优先级。\n抢课只在你手动开始后发出指令。",
                    actionLabel = "添加一条",
                    onAction = { editing = SelectionWish() },
                )
            }
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text(
                        "共 ${sorted.size} 条 · 抢课时按优先级从高到低匹配",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    )
                }
                items(sorted, key = { it.id }) { wish ->
                    WishCard(
                        wish = wish,
                        dup = dupHints[wish.id].orEmpty(),
                        onClick = { editing = wish },
                        onDelete = { deleting = wish },
                    )
                }
                item {
                    Text(
                        "清单只保存在本机；「开始抢课」需要手动发起（或打开对应开关），" +
                            "不会在后台自行选课。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }

    editing?.let { current ->
        WishEditDialog(
            initial = current,
            onSave = { saved ->
                editing = null
                val next = if (saved.id.isEmpty()) {
                    wishesState.orEmpty() + saved.copy(id = SelectionWishes.newId())
                } else {
                    wishesState.orEmpty().map { if (it.id == saved.id) saved else it }
                }
                persist(next)
            },
            onDismiss = { editing = null },
        )
    }

    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除这条预选？") },
            text = { Text("「${target.nameKeyword}」将从清单里移除。") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    persist(wishesState.orEmpty().filterNot { it.id == target.id })
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("取消") }
            },
        )
    }
}

/** 一条防重复提示。[fromSelections] = 来自已选课程（eyecatcher 用醒目色），否则来自课表。 */
private data class DupLine(val text: String, val fromSelections: Boolean)

@Composable
private fun WishCard(
    wish: SelectionWish,
    dup: List<DupLine>,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    AppCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                wish.nameKeyword,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                "优先级 ${SelectionWishes.priorityLabel(wish.priority)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                )
            }
        }
        if (wish.teacherKeyword.isNotBlank()) {
            Text(
                "教师：${wish.teacherKeyword}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        dup.forEach { line ->
            Text(
                line.text,
                style = MaterialTheme.typography.bodySmall,
                color = if (line.fromSelections) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (wish.note.isNotBlank()) {
            Text(
                wish.note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** 添加/编辑弹窗：课程名关键词（必填）、教师名（可选）、优先级三档、备注。 */
@Composable
private fun WishEditDialog(
    initial: SelectionWish,
    onSave: (SelectionWish) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial.nameKeyword) }
    var teacher by remember { mutableStateOf(initial.teacherKeyword) }
    var note by remember { mutableStateOf(initial.note) }
    var priority by remember { mutableIntStateOf(initial.priority) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id.isEmpty()) "添加预选课程" else "编辑预选课程") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("课程名关键词（必填）") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = teacher,
                    onValueChange = { teacher = it },
                    label = { Text("教师名关键词（可选）") },
                    singleLine = true,
                )
                Column {
                    Text(
                        "优先级",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SelectionWishes.PRIORITIES.sortedDescending().forEach { level ->
                            FilterChip(
                                selected = level == priority,
                                onClick = { priority = level },
                                label = { Text(SelectionWishes.priorityLabel(level)) },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注（可选）") },
                    singleLine = true,
                )
                error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val candidate = initial.copy(
                    nameKeyword = name.trim(),
                    teacherKeyword = teacher.trim(),
                    note = note.trim(),
                    priority = priority,
                )
                val problem = SelectionWishes.validate(candidate)
                if (problem != null) {
                    error = problem
                } else {
                    onSave(candidate)
                }
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
