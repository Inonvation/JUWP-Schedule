package edu.jxslu.schedule.ui.wenqu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.domain.WenquRules
import edu.jxslu.schedule.ui.common.AppCard
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.EmptyHint
import edu.jxslu.schedule.ui.common.FooterDisclaimer
import edu.jxslu.schedule.ui.common.LoadingHint
import edu.jxslu.schedule.ui.common.LocalBottomBarClearance
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import edu.jxslu.schedule.ui.common.showNotice
import edu.jxslu.schedule.ui.reminder.WenquActivityAlertReminder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert02
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.ChevronDown
import me.rerere.hugeicons.stroke.ChevronRight
import me.rerere.hugeicons.stroke.ChevronUp
import me.rerere.hugeicons.stroke.Filter
import me.rerere.hugeicons.stroke.HeartHandshake
import me.rerere.hugeicons.stroke.Medal02
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Settings01
import me.rerere.hugeicons.stroke.Target01

/**
 * 问渠 · 志愿服务页（DESIGN §3.28 / §4.45）。
 *
 * 未登录：默认「手机号 + 密码」登录；次入口「首次使用 / 忘记密码」走
 * 手机号 → 验证码 → 设密码（平台语义是重置密码，对没设过密码的账号即创建）。
 *
 * 已登录（两块）：
 * 1. **工时行**——一行放下工时数 + 星级 + 「记录 ›」入口（2026-10-11 收口：
 *    原先是整块大卡，占了首屏 1/6；记录入口也从设置页下钻提到了手边）；
 * 2. **可报名活动**——搜索行（常驻）+ 状态分段 + 条数/排序 + 活动卡列表，
 *    点卡片弹详情。筛选与排序的其余维度收进底部弹层（[WenquFilterSheet]）。
 *
 * 列表与搜索用的是**同一份全量快照**（`WenquRepository.activityIndex`）：进页抓一次、
 * 30 分钟内复用；抓全了之后筛选 / 排序 / 搜索全是内存操作、零请求。
 * 为什么不是「服务端第一页」：本地筛选 / 排序都作用在列表数据上，只拿 20 条的话
 * 「选招募中显示没有、选招募中优先却把旧的已结束排前面」（2026-10-11 用户报）。
 * 快照不完整时搜索补一次服务端查询兜底。
 *
 * 刷新走**下拉**（`PullToRefreshBox`，同流水 / 账单 / 生活 / 趣智 / 开水那几页的口径）。
 *
 * 提示一律走 [AppSnackbarHost]（全 App 统一的那张提示卡），不用裸 `SnackbarHost`——
 * 后者在这套主题里是另一副模样，用户会当成「不是软件内的弹窗」（2026-10-08 用户报）。
 *
 * 写操作（DESIGN §4.45，2026-10-11 起）：**报名 / 取消报名**直接提交到问渠平台，都过二次确认；
 * 签到、提交材料等其它写操作仍请用户到小程序里做。
 * 负责人姓名 / 电话会展示（用户要求），但**不进日志、不进持久化、不进测试夹具与文档**。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WenquScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenRecords: () -> Unit = {},
    onOpenGrab: () -> Unit = {},
) {
    val context = LocalContext.current
    val vm: WenquViewModel = viewModel(
        factory = WenquViewModel.Factory(Graph.wenqu(context), Graph.displayPrefs(context)),
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // 下拉刷新的触感（同流水 / 账单 / 生活那几页：轻一下，别只靠指示器）
    val haptics = rememberAppHaptics()
    // 检索框的文字放页面层：放 LazyColumn 的 item 里会随滚动被回收，滚回来就空了
    var searchText by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    var filterSheetOpen by remember { mutableStateOf(false) }
    var blockedExpanded by rememberSaveable { mutableStateOf(false) }
    // 「回到顶部」：滚过一屏才出现（derivedStateOf 只在布尔翻转时触发重组，不跟每帧滚动）
    val showBackToTop by remember { derivedStateOf { listState.firstVisibleItemIndex > 4 } }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbar.showNotice(message)
        vm.consumeMessage()
    }

    // 即输即搜（DESIGN §3.28）：搜索走本地全量索引，逐字过滤**不打服务端**，
    // 300ms 只是别让每次按键都重排整个列表。IME 搜索键 = 立即搜（见下面的 keyboardActions）。
    LaunchedEffect(searchText) {
        if (searchText.trim() == state.keyword) return@LaunchedEffect
        delay(WenquRules.SEARCH_DEBOUNCE_MS)
        vm.search(searchText)
    }

    // 一滚列表就收键盘：搜索完还顶着键盘看列表是多余的（键盘会一直占着半屏）
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling -> if (scrolling) keyboard?.hide() }
    }

    // 换了筛选 / 排序 / 检索词就**把列表拉回顶部**：列表是全量快照，换条件后内容整批换掉，
    // 停在原来的滚动位置等于把用户丢在「另一份结果的中间」（2026-10-11）。
    LaunchedEffect(
        state.statusFilter,
        state.hideFull,
        state.appliedOnly,
        state.sort,
        state.keyword,
    ) {
        if (listState.firstVisibleItemIndex > 0) listState.scrollToItem(0)
    }

    // 回到顶部按钮是贴底的悬浮件：它出现时把「提示卡让位」的量垫上，
    // 否则 Snackbar 会压在它上面（同底栏那套 `LocalBottomBarClearance` 口径）
    CompositionLocalProvider(
        LocalBottomBarClearance provides if (showBackToTop) BACK_TO_TOP_CLEARANCE else 0.dp,
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("志愿服务", style = MaterialTheme.typography.titleMedium) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        // 刷新走**下拉**（2026-10-11，同流水 / 账单 / 生活 / 趣智 / 开水那几页的
                        // `PullToRefreshBox` 口径）：顶栏不放刷新图标——下拉指示器本身就是
                        // 「有没有反应」的反馈。已登录时顶栏两枚：蹲点 + 设置。
                        if (state.loggedIn) {
                            // 蹲点抢活动（2026-10-11，DESIGN §3.28）：独立二级页 WenquGrabScreen，
                            // 到点自动检测目标活动、命中弹确认框（不自动提交）
                            IconButton(onClick = onOpenGrab) {
                                Icon(HugeIcons.Target01, contentDescription = "蹲点抢活动")
                            }
                            // 设置（DESIGN §3.28）：活动提醒 / 退出登录都在那一页
                            IconButton(onClick = onOpenSettings) {
                                Icon(HugeIcons.Settings01, contentDescription = "问渠设置")
                            }
                        }
                    },
                )
            },
            snackbarHost = { AppSnackbarHost(snackbar) },
        ) { padding ->
            if (!state.loggedIn) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    LoginContent(state = state, vm = vm)
                    FooterDisclaimer(DISCLAIMER)
                }
                return@Scaffold
            }

            val visible = state.visibleActivities
            PullToRefreshBox(
                // 首屏取数不走下拉指示器（列表区自己给「正在读取活动」），只有刷新时才转
                isRefreshing = state.refreshing,
                onRefresh = {
                    haptics.tap()
                    vm.refresh()
                },
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 76.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                    // 工时行 + 搜索行 + 状态分段 + 条数/排序 +（被限制报名时的）说明，合成**一个** item。
                    // 合成一个是为了「头部只有一项」这件事稳：以后往头部加一行不会改变列表的
                    // item 计数（页面里若有按 item 下标定位的逻辑，靠这个前提）。
                    item(key = "header") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            HoursRow(state = state, onOpenRecords = onOpenRecords)

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                OutlinedTextField(
                                    value = searchText,
                                    onValueChange = { searchText = it },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    shape = RoundedCornerShape(26.dp),
                                    placeholder = {
                                        Text(
                                            text = "搜索活动 / 单位 / 地点",
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    leadingIcon = { Icon(HugeIcons.Search01, contentDescription = null) },
                                    trailingIcon = {
                                        if (searchText.isNotEmpty()) {
                                            IconButton(onClick = {
                                                // 清空 = 回浏览模式；浏览那串一直留着，不用重新请求
                                                searchText = ""
                                                vm.search("")
                                            }) { Icon(HugeIcons.Cancel01, contentDescription = "清空") }
                                        }
                                    },
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    keyboardActions = KeyboardActions(onSearch = {
                                        keyboard?.hide()
                                        vm.search(searchText)
                                    }),
                                )
                                // 低频的两个开关与排序收在弹层里（见 [WenquFilterSheet] 的类注释）；
                                // 角标 = 弹层里开着几项（状态不在其中——它在页面上就看得见）
                                val sheetActive = listOf(state.appliedOnly, state.hideFull).count { it }
                                BadgedBox(
                                    badge = {
                                        if (sheetActive > 0) {
                                            Badge { Text("$sheetActive") }
                                        }
                                    },
                                ) {
                                    IconButton(onClick = { filterSheetOpen = true }) {
                                        Icon(HugeIcons.Filter, contentDescription = "筛选与排序")
                                    }
                                }
                            }

                            StatusSegmented(
                                status = state.statusFilter,
                                onSelect = vm::setStatusFilter,
                            )

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = metaText(state),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    modifier = Modifier.weight(1f),
                                )
                                // 排序入口：点开同一个弹层（排序也在里面）
                                Row(
                                    modifier = Modifier
                                        .heightIn(min = 44.dp)
                                        .clickable { filterSheetOpen = true }
                                        .padding(horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = state.sort.label,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                    )
                                    Spacer(Modifier.width(2.dp))
                                    Icon(
                                        imageVector = HugeIcons.ChevronDown,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }

                            if (state.applyBlockedReason.isNotBlank()) {
                                BlockedBanner(
                                    reason = state.applyBlockedReason,
                                    expanded = blockedExpanded,
                                    onToggle = { blockedExpanded = !blockedExpanded },
                                )
                            }
                        }
                    }

                    when {
                        // 首屏整份抓快照要几秒：给**与卡片同几何**的骨架（不是一句转圈提示），
                        // 数据回来时不跳（`.agents/rules/ui-common.md`「等数据先给骨架」）
                        state.indexing -> item(key = "act-skeleton") { WenquActivitySkeleton() }

                        // 一条都没抓到（快照建不起来）：列表的数据源就是它，这里必须给出口
                        !state.indexAvailable -> item(key = "act-unavailable") {
                            EmptyHint(
                                title = "活动没抓到",
                                body = "点下面重试，或下拉刷新；还是不行就是问渠平台这会儿连不上。",
                                actionLabel = "重试",
                                onAction = vm::refresh,
                            )
                        }

                        visible.isEmpty() -> item(key = "act-empty") {
                            // 原始条数（含被本地筛选藏掉的）：它 > 0 而列表为空 = **不是没数据，
                            // 是筛选全挡掉了**——必须说清，否则用户会以为「搜不到 / 平台上没有」。
                            val matched = state.sourceTotal
                            when {
                                matched > 0 -> EmptyHint(
                                    title = if (state.keyword.isNotBlank()) {
                                        "找到 $matched 条，但被当前筛选隐藏了"
                                    } else {
                                        "有 $matched 条活动被当前筛选隐藏了"
                                    },
                                    body = "当前筛选把命中的活动都挡掉了，清掉筛选就能看到。",
                                    actionLabel = "清除筛选",
                                    onAction = vm::clearFilters,
                                )

                                state.keyword.isNotBlank() -> EmptyHint(
                                    title = "没有匹配「${state.keyword}」的活动",
                                    body = if (state.indexComplete) {
                                        "平台上没有名称 / 项目 / 单位 / 地点含这段字的活动，换个关键词试试。"
                                    } else {
                                        "本地快照这次没抓全，服务端也没给出结果——下拉刷新再试。"
                                    },
                                )

                                else -> EmptyHint(
                                    title = "暂时没有可显示的活动",
                                    body = "平台上还没有能显示的活动，过会儿下拉刷新看看。",
                                )
                            }
                        }

                        else -> items(visible, key = { "act-${it.id}" }) { activity ->
                            ActivityCard(
                                activity = activity,
                                keyword = state.keyword,
                                onClick = { vm.openDetail(activity) },
                            )
                        }
                    }

                    // 列表 = 全量快照（本地筛 / 排 / 搜），没有「下一页」这回事；
                    // 到底给个句号，没抓全时也说清楚（诚实优先）。
                    if (visible.isNotEmpty()) {
                        item(key = "act-end") {
                            ListEnd(total = visible.size, complete = state.indexComplete)
                        }
                    }

                    item(key = "disclaimer") {
                        FooterDisclaimer(DISCLAIMER, modifier = Modifier.padding(top = 4.dp))
                    }
                }

                // 「回到顶部」：列表是全量快照（上千条），滚下去之后回顶很费劲。
                // 滚过一屏才出现；按下给一次触感，滚动本身带动画。
                if (showBackToTop) {
                    SmallFloatingActionButton(
                        onClick = {
                            haptics.tap()
                            scope.launch { listState.animateScrollToItem(0) }
                        },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                    ) {
                        Icon(HugeIcons.ArrowUp01, contentDescription = "回到顶部")
                    }
                }
            }
        }
    }

    // 筛选与排序弹层（DESIGN §3.28）：本地即时生效，「看 N 条」实时算
    if (filterSheetOpen) {
        WenquFilterSheet(
            appliedOnly = state.appliedOnly,
            hideFull = state.hideFull,
            sort = state.sort,
            visibleCount = state.visibleActivities.size,
            onToggleAppliedOnly = vm::setAppliedOnly,
            onToggleHideFull = vm::setHideFull,
            onSelectSort = vm::setSort,
            onClear = vm::clearFilters,
            onDismiss = { filterSheetOpen = false },
        )
    }

    // 活动详情弹层：本页只有这一个弹层（记录列表在独立二级页，详情在那边弹）
    state.detail?.let { activity ->
        WenquActivitySheet(
            activity = activity,
            detailLoading = state.detailLoading,
            onDismiss = vm::closeDetail,
            detailUnavailable = state.detailUnavailable,
            onNotice = { msg -> scope.launch { snackbar.showNotice(msg) } },
            applyBlockReason = WenquRules.applyBlockReason(
                status = activity.status,
                applied = activity.applied,
                peopleNum = activity.peopleNum,
                balanceNum = activity.balanceNum,
                applyStartTime = activity.applyStartTime,
                applyEndTime = activity.applyEndTime,
                blacklisted = state.applyBlockedReason.isNotBlank(),
                now = System.currentTimeMillis(),
            ),
            canCancel = WenquRules.canCancelFromActivity(activity.status, activity.applied),
            busy = state.applying,
            actionError = state.detailActionError,
            onApply = { vm.applyActivity(activity.id) },
            onCancel = { vm.cancelApply(activity.id, activity.applyId) },
            preregOpensAt = if (state.preregId == activity.id) state.preregOpensAt else 0L,
            onRequestPrereg = {
                scope.launch {
                    val opensAt = WenquRules.stampMillis(activity.applyStartTime)
                    if (opensAt == null) {
                        snackbar.showNotice("这条活动的报名时间读不出来，暂不能预报名")
                    } else {
                        WenquPreregReminder.arm(context, activity, opensAt)
                        snackbar.showNotice(
                            "已预报名：到点自动抢《${activity.title.ifBlank { "这条活动" }}》",
                        )
                    }
                }
            },
            onCancelPrereg = {
                scope.launch {
                    WenquPreregReminder.cancel(context)
                    snackbar.showNotice("已取消预报名")
                }
            },
            // 「盯这条」（2026-10-15）：落盘给蹲点页，那一页每轮单独拉它的实时详情。
            // 本页不自动跳蹲点页——用户可能还想接着翻列表，给一句提示让他自己决定。
            onPin = {
                scope.launch {
                    val prefs = Graph.displayPrefs(context)
                    prefs.setWenquGrabPinnedId(activity.id)
                    // 换了目标：清「已报过」键，让这条也能报一次「名额空出」
                    prefs.setWenquGrabPinnedAlertedId(0L)
                    // 盯了活动，共享周期任务必须存在（全关时它本来被取消了）
                    WenquActivityAlertReminder.onPinChanged(context)
                    vm.closeDetail()
                    snackbar.showNotice(
                        "已盯上「${activity.title.ifBlank { "这条活动" }}」· 去蹲点页开蹲",
                    )
                }
            },
        )
    }
}

/** 回到顶部按钮出现时，提示卡要垫开的高度（按钮 40dp + 上下留白，同底栏那套口径）。 */
private val BACK_TO_TOP_CLEARANCE = 64.dp

private const val DISCLAIMER =
    "志愿工时 / 劳动教育数据来自「问渠」实践教育平台（第三方系统，非学校官方功能）。" +
        "报名 / 取消报名会直接提交到该平台；签到、提交材料等其它操作请到微信小程序内完成。"

/** 条数行文案：浏览说「共 N 条」，搜索说「找到 N 条」，被本地筛选藏掉的都单独说出来。 */
private fun metaText(state: WenquViewModel.UiState): String = buildString {
    if (state.sourceTotal == 0) return@buildString
    append(
        if (state.keyword.isBlank()) "共 ${state.sourceTotal} 条" else "找到 ${state.sourceTotal} 条",
    )
    val hidden = state.hiddenByFilter
    if (hidden > 0) append(" · 筛选隐藏 $hidden 条")
}

// ---- 未登录 ----------------------------------------------------------------

/** 未登录：登录 / 首次设置 两个 tab。 */
@Composable
private fun LoginContent(state: WenquViewModel.UiState, vm: WenquViewModel) {
    var tab by remember { mutableStateOf(0) }
    var phone by remember { mutableStateOf(state.phone) }
    var password by remember { mutableStateOf("") }
    var captcha by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }

    // 5 分钟重发倒计时（平台实测约 5 分钟一条，见 WenquRules.RESEND_COOLDOWN_MS）
    var resendRemaining by remember { mutableStateOf(0L) }
    LaunchedEffect(state.codeSentAt) {
        if (state.codeSentAt == 0L) {
            resendRemaining = 0L
            return@LaunchedEffect
        }
        while (true) {
            val left = WenquRules.resendRemainingMs(state.codeSentAt, System.currentTimeMillis())
            resendRemaining = left
            if (left <= 0L) break
            delay(1_000)
        }
    }

    AppCard {
        Text(
            text = "登录问渠",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = tab == 0,
                onClick = { tab = 0 },
                label = { Text("手机号登录") },
            )
            FilterChip(
                selected = tab == 1,
                onClick = { tab = 1 },
                label = { Text("首次使用 / 忘记密码") },
            )
        }
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = phone,
            onValueChange = { phone = it.filter { ch -> ch.isDigit() }.take(11) },
            label = { Text("手机号") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth(),
        )

        if (tab == 0) {
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { vm.login(phone, password) },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (state.busy) "登录中…" else "登录") }
        } else {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { vm.sendCode(phone) },
                    enabled = !state.busy && resendRemaining <= 0L,
                ) {
                    Text(
                        if (resendRemaining > 0L) {
                            "重发（${resendRemaining / 1000 / 60}:${(resendRemaining / 1000 % 60).toString().padStart(2, '0')}）"
                        } else {
                            "获取验证码"
                        },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = captcha,
                onValueChange = { captcha = it.filter { ch -> ch.isDigit() }.take(6) },
                label = { Text("短信验证码") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = newPassword,
                onValueChange = { newPassword = it },
                label = { Text("设置密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { vm.setupPassword(phone, captcha, newPassword) },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (state.busy) "设置中…" else "设置密码并登录") }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "首次使用没有密码？走这里设一个，以后用「手机号 + 密码」登录即可。" +
                    "短信可能限流（约 5 分钟一条），没收到请稍后再试。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

// ---- 已登录 ----------------------------------------------------------------

/**
 * 工时行（2026-10-11 由整块大卡收成一行）：工时数 + 星级 + 右侧「记录 ›」。
 *
 * 收一行的理由：原先是 ~100dp 的大卡，加上搜索框与筛选行，首屏只剩 2 张活动卡——
 * 而这一页的主角是活动列表。记录入口顺手从设置页下钻提到了手边（设置页那行保留）。
 */
@Composable
private fun HoursRow(state: WenquViewModel.UiState, onOpenRecords: () -> Unit) {
    // 首屏还没数时别把 0 摆成大数字（看着像工时被清了），给个占位
    val hoursText = if (state.loading && state.hours == 0.0) "…" else state.hoursText
    AppCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = HugeIcons.HeartHandshake,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = hoursText,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "小时",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
            Spacer(Modifier.weight(1f))
            if (state.starLabel.isNotBlank()) {
                Icon(
                    imageVector = HugeIcons.Medal02,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    text = "${state.starLabel}志愿者",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
                Spacer(Modifier.width(8.dp))
            }
            Row(
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .clickable(onClick = onOpenRecords)
                    .padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "记录",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                )
                Icon(
                    imageVector = HugeIcons.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * 状态筛选：**等宽分段控件**（2026-10-11 由横向滚动的 chips 改来）。
 *
 * 四枚 chip 在 360dp 屏上会被裁掉最右边那枚（粗算约 400dp > 内容宽 328dp），
 * 「看不见的选项等于不存在」；分段件等宽、永远全在屏内，也不用滚。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatusSegmented(status: Int?, onSelect: (Int?) -> Unit) {
    val options = remember { listOf(null, 1, 2, 3) }
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, value ->
            SegmentedButton(
                selected = status == value,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                label = {
                    Text(
                        text = if (value == null) "全部" else WenquRules.statusLabel(value),
                        maxLines = 1,
                    )
                },
            )
        }
    }
}

/**
 * 平台把账号限制了报名时的说明（`isBlack` + `stop_apply_reason`）。
 *
 * 它是**账号级**的、长期不变，所以默认只占一行（点开看平台原话）——
 * 一直摊开会白占一屏（2026-10-11 收口）。
 */
@Composable
private fun BlockedBanner(reason: String, expanded: Boolean, onToggle: () -> Unit) {
    AppCard(onClick = onToggle) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = HugeIcons.Alert02,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "报名受限",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) HugeIcons.ChevronUp else HugeIcons.ChevronDown,
                contentDescription = if (expanded) "收起" else "展开",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
        }
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
    }
}

/**
 * 列表到底：给个明确的句号。列表来自**全量快照**，没有「下一页」——
 * 快照没抓全时说清楚（少的那部分不是不存在，是这次没拿到）。
 */
@Composable
private fun ListEnd(total: Int, complete: Boolean) {
    Text(
        text = if (complete) {
            "已到底 · 共 $total 条"
        } else {
            "已到底 · 共 $total 条（这次没抓全，下拉刷新可重试）"
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    )
}
