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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.JwImportActivity
import edu.jxslu.schedule.data.jw.SelectionCenterParser
import edu.jxslu.schedule.data.repo.SelectionClientException
import edu.jxslu.schedule.data.repo.SelectionSubmitResult
import edu.jxslu.schedule.data.repo.UnconfiguredSelectionCenterClient
import edu.jxslu.schedule.data.session.AutoSyncRules
import edu.jxslu.schedule.domain.SelectionCourse
import edu.jxslu.schedule.domain.SelectionCourseTypes
import edu.jxslu.schedule.domain.SelectionCourses
import edu.jxslu.schedule.domain.SelectionFilter
import edu.jxslu.schedule.domain.SelectionQuery
import edu.jxslu.schedule.domain.SelectionRound
import edu.jxslu.schedule.domain.SelectionRoundPhase
import edu.jxslu.schedule.domain.SelectionRounds
import edu.jxslu.schedule.domain.SelectionSort
import edu.jxslu.schedule.domain.SelectionWishHits
import edu.jxslu.schedule.domain.WishHit
import edu.jxslu.schedule.ui.common.AppCard
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.EmptyHint
import edu.jxslu.schedule.ui.common.FooterDisclaimer
import edu.jxslu.schedule.ui.common.LoadingHint
import edu.jxslu.schedule.ui.common.showNotice
import edu.jxslu.schedule.ui.jwvw.JwImportMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 应用内选课中心（DESIGN §3.22 / §4.36）：**不经过 WebView**，用已存凭证直连接口。
 *
 * v2（2026-10-08 窗口期实测后）：**进页即浏览**（不用先输关键词）、筛选面对齐教务列表页
 * （课程/教师/类别/星期/节次/三个服务端开关），卡片字段对齐教务列；浏览全量的实现口径
 * （教务要求条件非空、kch=0 命中全部）见 `SelectionCenterParser.listQuery`。
 *
 * v3（2026-10-08 同日）：自动抢课下线——**没有任何自动提交路径**。预选清单改「预购匹配」：
 * 进页按清单逐条查教务（[SelectionWishHits] 归组），**命中课程置顶优先显示**（主列表里
 * 同样打标，不挪走）；选课 / 退课一律走 [SelectionConfirmDialog] 二次确认，由用户点。
 *
 * [focusRoundId]：选课页点轮次行带进来的直达轮次（走二级页 focusItemId 通道）；
 * 快照里认不出（已过期）就退回 [SelectionRounds.pickTarget] 的自动挑选，不报错。
 *
 * 服务端的开放闸门是真源头：非选课时间教务回**「当前不在选课时间范围内，具体请查看学校
 * 选课通知！」**（2026-10-01 实测），client 把它原样带上来显示，不要自己编状态。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionCenterScreen(onBack: () -> Unit, focusRoundId: String? = null) {
    val context = LocalContext.current
    val client = remember { Graph.selectionCenterClient(context) }
    val prefs = remember { Graph.displayPrefs(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val wishes by prefs.selectionWishes.collectAsStateWithLifecycle(initialValue = emptyList())

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

    /** 表单态（未提交的查询条件）。 */
    var draft by remember { mutableStateOf(SelectionQuery()) }
    /** 已提交的查询（「加载更多」按它翻页）；null = 还没查过。 */
    var active by remember { mutableStateOf<SelectionQuery?>(null) }
    var courses by remember { mutableStateOf<List<SelectionCourse>>(emptyList()) }
    /** 教务报的总条数（加载更多按它判到底）。 */
    var total by remember { mutableStateOf(0) }
    /**
     * 下一页的**服务端行偏移**（不是本地去重后的条数）：两页若有重叠行，按本地条数当偏移
     * 会原地打转；偏移只按服务端每次给的行数推进（见 [loadMore]）。
     */
    var nextStart by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    // 本地筛选只剩「服务端没有的维度」：只看已选 + 课程属性；余量/类别都改走服务端
    var filter by remember { mutableStateOf(SelectionFilter()) }
    var sort by remember { mutableStateOf(SelectionSort.Default) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    // 预选命中（v3）：命中分组 + 加载/失败态
    var wishHits by remember { mutableStateOf<List<WishHit>>(emptyList()) }
    var hitsLoading by remember { mutableStateOf(false) }
    var hitsError by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmSubmit by remember { mutableStateOf<SelectionCourse?>(null) }
    var confirmDrop by remember { mutableStateOf<SelectionCourse?>(null) }
    /** 筛选 + 排序后的列表（在组合层算好，LazyColumn 里只消费）。 */
    val shownCourses = remember(courses, filter, sort) {
        SelectionCourses.sort(SelectionCourses.filter(courses, filter), sort)
    }
    /** 命中课程 id（主列表打标用）。 */
    val hitIds = remember(wishHits) { SelectionWishHits.ids(wishHits) }
    // 上限按**服务端偏移**算（courses.size 会因去重少算，偏移才是真实请求量）
    val canLoadMore = active != null && courses.size < total &&
        nextStart < SelectionCenterParser.PAGE_SIZE * MAX_UI_PAGES

    fun doQuery(query: SelectionQuery) {
        val target = round ?: return
        query.validationError()?.let { message ->
            scope.launch { snackbar.showNotice(message) }
            return
        }
        if (loading) return
        active = query
        loading = true
        loadError = null
        courses = emptyList()
        total = 0
        nextStart = 0
        scope.launch {
            try {
                val page = client.fetchPage(target.id, query, start = 0)
                courses = page.rows
                total = page.total
                nextStart = page.rows.size
            } catch (e: SelectionClientException) {
                loadError = e.message ?: "选课接口不可用"
            } catch (e: Exception) {
                loadError = "拉取课程列表失败：${e.message ?: "未知错误"}"
            }
            loading = false
        }
    }

    /** 加载更多：按已提交的查询再取一页（用户驱动，一次一页）。 */
    fun loadMore() {
        val target = round ?: return
        val query = active ?: return
        if (loading || loadingMore || !canLoadMore) return
        loadingMore = true
        scope.launch {
            try {
                val page = client.fetchPage(target.id, query, start = nextStart)
                val seen = courses.mapTo(mutableSetOf()) { it.id }
                courses = courses + page.rows.filter { it.id !in seen }
                nextStart += page.rows.size
                total = maxOf(total, page.total)
                // 服务端不再给行（数据在翻页期间变少）：就地收口，按钮消失，不让用户空点
                if (page.rows.isEmpty()) total = courses.size
            } catch (e: SelectionClientException) {
                snackbar.showNotice(e.message ?: "加载更多失败")
            } catch (e: Exception) {
                snackbar.showNotice("加载更多失败：${e.message ?: "未知错误"}")
            }
            loadingMore = false
        }
    }

    /**
     * 预选命中（v3）：按清单逐条查一次（每条 1–3 页、串行礼貌间隔），本地归组。
     * 失败不影响浏览与手动选课——已查到的部分照常展示，区块里给「重试」。
     */
    fun loadWishHits() {
        val target = round ?: return
        if (wishes.isEmpty()) {
            wishHits = emptyList()
            hitsLoading = false
            hitsError = null
            return
        }
        if (hitsLoading) return
        hitsLoading = true
        hitsError = null
        scope.launch {
            val fetched = mutableMapOf<String, List<SelectionCourse>>()
            try {
                wishes.forEachIndexed { index, wish ->
                    if (index > 0) delay(AutoSyncRules.interRequestDelayMs())
                    fetched[wish.id] = client.fetchCourses(target.id, listOf(wish.nameKeyword))
                }
                wishHits = SelectionWishHits.group(wishes, fetched)
            } catch (e: SelectionClientException) {
                hitsError = e.message ?: "预选匹配失败"
                if (fetched.isNotEmpty()) wishHits = SelectionWishHits.group(wishes, fetched)
            } catch (e: Exception) {
                hitsError = "预选匹配失败：${e.message ?: "未知错误"}"
                if (fetched.isNotEmpty()) wishHits = SelectionWishHits.group(wishes, fetched)
            }
            hitsLoading = false
        }
    }

    // 进页/换轮次自动拉一次（浏览全量：默认条件即可出课程）；未接入直接把原因摆出来（不打网络）
    LaunchedEffect(round?.id) {
        if (!client.isConfigured) {
            loadError = UnconfiguredSelectionCenterClient.FATAL_HINT
        } else if (round != null) {
            doQuery(draft)
        }
    }

    // 预选清单变化（首帧到达 / 别处编辑后回来）时重新匹配；key 里带关键词，清单没变不重查
    val wishKey = wishes.joinToString("|") { "${it.id}:${it.nameKeyword}:${it.teacherKeyword}" }
    LaunchedEffect(round?.id, wishKey) {
        if (client.isConfigured && round != null) loadWishHits()
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
                    wishHits = wishHits.map { hit ->
                        hit.copy(courses = hit.courses.map { if (it.id == course.id) it.copy(selected = true) else it })
                    }
                    snackbar.showNotice("已选上：${course.name}")
                }
                is SelectionSubmitResult.Rejected -> snackbar.showNotice("未选上：${result.reason}")
                is SelectionSubmitResult.Fatal -> snackbar.showNotice("已停止：${result.reason}")
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
                    wishHits = wishHits.map { hit ->
                        hit.copy(courses = hit.courses.map { if (it.id == course.id) it.copy(selected = false) else it })
                    }
                    snackbar.showNotice("已退课：${course.name}")
                }
                is SelectionSubmitResult.Rejected -> snackbar.showNotice("退课被拒：${result.reason}")
                is SelectionSubmitResult.Fatal -> snackbar.showNotice("已停止：${result.reason}")
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
                    IconButton(
                        onClick = {
                            doQuery(active ?: draft)
                            loadWishHits()
                        },
                        enabled = client.isConfigured && !loading,
                    ) {
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
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
                    if (courses.isNotEmpty() || total > 0) {
                        val summary = SelectionCourses.summarize(courses)
                        val text = buildString {
                            append("共 ").append(total).append(" 门")
                            if (courses.size < total) append(" · 已加载 ").append(courses.size)
                            append(" · 已选 ").append(summary.selected)
                            append(" · 可选 ").append(summary.selectable)
                        }
                        Text(
                            text,
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
                                .heightIn(min = 44.dp),
                        ) { Text("打开教务页面（备用）") }
                    }
                }
            }

            // 预选命中（v3）：置顶优先显示；主列表里同样打标（不挪走，避免「课不见了」的错觉）
            if (round != null && (wishes.isNotEmpty() || wishHits.isNotEmpty())) {
                item {
                    Text(
                        "预选命中" + if (wishHits.isNotEmpty()) " · ${SelectionWishHits.count(wishHits)} 门" else "",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                if (hitsLoading) {
                    item {
                        LoadingHint("正在按预选清单匹配", Modifier.fillMaxWidth())
                    }
                }
                hitsError?.let { message ->
                    item {
                        AppCard {
                            Text(
                                message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                            )
                            TextButton(
                                onClick = { loadWishHits() },
                                modifier = Modifier
                                    .padding(top = 4.dp)
                                    .heightIn(min = 44.dp),
                            ) { Text("重试") }
                        }
                    }
                }
                if (!hitsLoading && wishHits.isEmpty() && hitsError == null && wishes.isNotEmpty()) {
                    item {
                        EmptyHint(
                            title = "本轮暂未匹配到预选课程",
                            body = "清单里的课程可能本轮没开；下方可直接浏览全部课程。",
                        )
                    }
                }
                wishHits.forEach { hit ->
                    item(key = "wish-${hit.wish.id}") {
                        Text(
                            wishAttribution(hit),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                    items(hit.courses, key = { "hit-${it.id}" }) { course ->
                        CourseCard(
                            course = course,
                            wishHit = true,
                            busy = course.id in submitting,
                            onSelect = { confirmSubmit = course },
                            onDrop = { confirmDrop = course },
                        )
                    }
                }
            }

            if (round != null) {
                item {
                    SelectionFilterCard(
                        draft = draft,
                        enabled = client.isConfigured && !loading,
                        onChange = { draft = it },
                        onQuery = { doQuery(draft) },
                        onReset = {
                            draft = SelectionQuery()
                            doQuery(draft)
                        },
                    )
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
                        body = "当前条件下教务没有返回课程——换个关键词、教师或星期试试，" +
                            "点上方「重置」回到浏览全部。",
                    )
                }
            }

            if (courses.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
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

                // 注意：筛选/排序结果要在 LazyColumn **外面**算（LazyListScope 不是 @Composable
                // 上下文，里面不能调 remember）
                val shown = shownCourses
                if (shown.isEmpty()) {
                    item {
                        EmptyHint(
                            title = "没有符合条件的课程",
                            body = "清掉筛选（只看已选 / 属性）再试。",
                        )
                    }
                }
                items(shown, key = { it.id }) { course ->
                    CourseCard(
                        course = course,
                        wishHit = course.id in hitIds,
                        busy = course.id in submitting,
                        onSelect = { confirmSubmit = course },
                        onDrop = { confirmDrop = course },
                    )
                }

                if (canLoadMore) {
                    item {
                        OutlinedButton(
                            onClick = { loadMore() },
                            enabled = !loadingMore,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 44.dp),
                        ) {
                            Text(if (loadingMore) "加载中…" else "加载更多（还有 ${total - courses.size} 门）")
                        }
                    }
                }
            }

            item {
                FooterDisclaimer(
                    body = "打开即浏览本轮全部课程（教务接口要求关键词，App 已自动处理）；" +
                        "预选命中的课程优先显示，选课 / 退课都由你逐次确认——App 不会自动提交。" +
                        "数据以教务为准，操作后可用右上角刷新。",
                )
            }
        }
    }

    confirmSubmit?.let { course ->
        SelectionConfirmDialog(
            course = course,
            dropping = false,
            onConfirm = {
                confirmSubmit = null
                submit(course)
            },
            onDismiss = { confirmSubmit = null },
        )
    }

    confirmDrop?.let { course ->
        SelectionConfirmDialog(
            course = course,
            dropping = true,
            onConfirm = {
                confirmDrop = null
                drop(course)
            },
            onDismiss = { confirmDrop = null },
        )
    }
}

/** 「加载更多」的页数上限（用户驱动，仍给一个大兜底防失控）。 */
private const val MAX_UI_PAGES = 20

/** 命中归属行：「预选：机械设计 · 教师：曾（2 门）」。 */
private fun wishAttribution(hit: WishHit): String = buildString {
    append("预选：").append(hit.wish.nameKeyword)
    if (hit.wish.teacherKeyword.isNotBlank()) append(" · 教师：").append(hit.wish.teacherKeyword)
    append("（").append(hit.courses.size).append(" 门）")
}

/**
 * 查询条件卡（表单态；下拉与开关只改表单，点「查询」才发请求——对齐教务的查询/重置）。
 * 表单校验（节次三条）在 `SelectionQuery.validationError`，错误走页面的 Snackbar。
 */
@Composable
private fun SelectionFilterCard(
    draft: SelectionQuery,
    enabled: Boolean,
    onChange: (SelectionQuery) -> Unit,
    onQuery: () -> Unit,
    onReset: () -> Unit,
) {
    AppCard {
        OutlinedTextField(
            value = draft.keyword,
            onValueChange = { onChange(draft.copy(keyword = it)) },
            label = { Text("课程（编号 / 名称）") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { if (enabled) onQuery() }),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.teacher,
            onValueChange = { onChange(draft.copy(teacher = it)) },
            label = { Text("上课教师") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { if (enabled) onQuery() }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterSelect(
                label = "课程类别",
                current = SelectionCourseTypes.OPTIONS.firstOrNull { it.first == draft.courseType }?.second,
                options = listOf(FilterOption(null, "全部")) +
                    SelectionCourseTypes.OPTIONS.map { FilterOption(it.first, it.second) },
                onPick = { onChange(draft.copy(courseType = it ?: "")) },
            )
            FilterSelect(
                label = "星期",
                current = draft.weekday?.let { WEEKDAY_LABELS.getOrNull(it - 1) },
                options = listOf(FilterOption(null, "全部")) +
                    WEEKDAY_LABELS.mapIndexed { i, s -> FilterOption((i + 1).toString(), s) },
                onPick = { onChange(draft.copy(weekday = it?.toIntOrNull())) },
            )
            FilterSelect(
                label = "节次起",
                current = draft.startSection?.toString(),
                options = listOf(FilterOption(null, "不限")) +
                    (1..11).map { FilterOption(it.toString(), it.toString()) },
                onPick = { onChange(draft.copy(startSection = it?.toIntOrNull())) },
            )
            Text("至", style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.CenterVertically))
            FilterSelect(
                label = "节次止",
                current = draft.endSection?.toString(),
                options = listOf(FilterOption(null, "不限")) +
                    (1..11).map { FilterOption(it.toString(), it.toString()) },
                onPick = { onChange(draft.copy(endSection = it?.toIntOrNull())) },
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = draft.hideFull,
                onClick = { onChange(draft.copy(hideFull = !draft.hideFull)) },
                label = { Text("过滤已满课程") },
            )
            FilterChip(
                selected = draft.hideConflict,
                onClick = { onChange(draft.copy(hideConflict = !draft.hideConflict)) },
                label = { Text("过滤冲突课程") },
            )
            FilterChip(
                selected = draft.hideRestricted,
                onClick = { onChange(draft.copy(hideRestricted = !draft.hideRestricted)) },
                label = { Text("过滤限选课程") },
            )
        }
        Text(
            "「过滤冲突」按教务口径会把已选课程一起隐藏——默认不勾，保证已选课留在列表里可退；" +
                "冲突课程默认带红字说明。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = 6.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onQuery,
                enabled = enabled,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp),
            ) { Text("查询") }
            OutlinedButton(
                onClick = onReset,
                enabled = enabled,
                modifier = Modifier.heightIn(min = 44.dp),
            ) { Text("重置") }
        }
    }
}

/** 下拉筛选的一项：值（null = 不限/全部）+ 显示名。 */
private data class FilterOption(val value: String?, val label: String)

/**
 * 下拉筛选 chip：点开菜单选一项（自带「不限/全部」项）。[current] 是选中项的显示名，
 * null 时 chip 显示 [label] 本身（未筛选态）；值的解释交给调用方（星期/节次转 Int、
 * 类别用代码）。
 */
@Composable
private fun FilterSelect(
    label: String,
    current: String?,
    options: List<FilterOption>,
    onPick: (String?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = current != null,
            onClick = { open = true },
            label = { Text(current ?: label) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        open = false
                        onPick(option.value)
                    },
                )
            }
        }
    }
}

private val WEEKDAY_LABELS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

private fun sortLabel(sort: SelectionSort): String = when (sort) {
    SelectionSort.Default -> "教务顺序"
    SelectionSort.Remaining -> "余量多优先"
    SelectionSort.Name -> "课程名"
    SelectionSort.Teacher -> "教师"
}

@Composable
private fun CourseCard(
    course: SelectionCourse,
    wishHit: Boolean,
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
        if (wishHit) {
            Badge("预选命中")
        }
        // 卡片字段对齐教务列表列：编号 / 课程类别 / 学分 / 教师 / 已选·容量 / 校区 / 授课方式 / 分组名
        val head = listOfNotNull(
            course.courseNo.takeIf { it.isNotBlank() },
            course.courseType.takeIf { it.isNotBlank() },
            course.credit?.let { "${creditText(it)} 学分" },
        ).joinToString(" · ")
        if (head.isNotBlank()) {
            Text(
                head,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        val meta = listOfNotNull(
            course.teacher.takeIf { it.isNotBlank() },
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
        val seats = when {
            course.enrolled != null && course.capacity != null -> "已选 ${course.enrolled} / 容量 ${course.capacity}"
            course.enrolled != null -> "已选 ${course.enrolled}"
            course.capacity != null -> "容量 ${course.capacity}"
            else -> null
        }
        val extra = listOfNotNull(
            seats,
            course.campus.takeIf { it.isNotBlank() },
            course.teachMode.takeIf { it.isNotBlank() }?.let { "授课方式：$it" },
            course.groupName.takeIf { it.isNotBlank() }?.let { "分组：$it" },
        ).joinToString(" · ")
        if (extra.isNotBlank()) {
            Text(
                extra,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        // 教务的冲突/已选说明（ctsm，2026-10-08 实测字段）：未选的冲突课在点「选课」前
        // 就看得见原因（已选行的「该课堂已选」与「已选」徽标重复，不显）
        if (!course.selected && course.conflictNote.isNotBlank()) {
            Text(
                course.conflictNote,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
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
                OutlinedButton(onClick = onDrop, enabled = !busy, modifier = Modifier.heightIn(min = 44.dp)) {
                    Text(if (busy) "提交中…" else "退课")
                }
            } else {
                Button(
                    onClick = onSelect,
                    // 余量为 0 明确禁选；余量未知不禁（交给教务裁定，别在家门口拦）
                    enabled = !busy && (course.remaining?.let { it > 0 } ?: true),
                    modifier = Modifier.heightIn(min = 44.dp),
                ) { Text(if (busy) "提交中…" else "选课") }
            }
        }
    }
}

/** 小徽标（软底块形态）：预选命中标记等。 */
@Composable
private fun Badge(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.padding(top = 4.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
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
