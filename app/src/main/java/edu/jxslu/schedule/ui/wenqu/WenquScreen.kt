package edu.jxslu.schedule.ui.wenqu

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.domain.WenquRules
import edu.jxslu.schedule.ui.common.AmountHeroText
import edu.jxslu.schedule.ui.common.AppCard
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.EmptyHint
import edu.jxslu.schedule.ui.common.FooterDisclaimer
import edu.jxslu.schedule.ui.common.LoadingHint
import edu.jxslu.schedule.ui.common.showNotice
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert02
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.CheckmarkCircle02
import me.rerere.hugeicons.stroke.HeartHandshake
import me.rerere.hugeicons.stroke.Medal02
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Settings01

/**
 * 问渠 · 志愿服务页（DESIGN §3.28 / §4.45）。
 *
 * 未登录：默认「手机号 + 密码」登录；次入口「首次使用 / 忘记密码」走
 * 手机号 → 验证码 → 设密码（平台语义是重置密码，对没设过密码的账号即创建）。
 *
 * 已登录（两块）：
 * 1. **工时卡**——工时大字 + 星级（**只展示工时数**；记录入口、提醒设置、退出登录都在
 *    设置页 `WENQU_SETTINGS`，2026-10-08 用户口径）；
 * 2. **可报名活动**——平台上的志愿服务活动（就是小程序「水利文化 → 劳动教育」那张表），
 *    关键词检索 + 本地筛选（状态 / 隐藏已满）+ 本地排序（招募中优先）+ 翻页，
 *    点卡片弹详情。
 *
 * 提示一律走 [AppSnackbarHost]（全 App 统一的那张提示卡），不用裸 `SnackbarHost`——
 * 后者在这套主题里是另一副模样，用户会当成「不是软件内的弹窗」（2026-10-08 用户报）。
 *
 * 红线（DESIGN §4.45）：全程只读——报名 / 签到 / 提交等写操作请用户到小程序里做。
 * 负责人姓名 / 电话会展示（用户要求），但**不进日志、不进持久化、不进测试夹具与文档**。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WenquScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit = {},
) {
    val context = LocalContext.current
    val vm: WenquViewModel = viewModel(
        factory = WenquViewModel.Factory(Graph.wenqu(context), Graph.displayPrefs(context)),
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // 检索框的文字放页面层：放 LazyColumn 的 item 里会随滚动被回收，滚回来就空了
    var searchText by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    // 「加载更多」这一下的起点：点了之后要看新增的卡片落在哪，用来滚过去 / 报新增条数
    var loadMoreAnchor by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbar.showNotice(message)
        vm.consumeMessage()
    }

    // 检索框文字与状态里的关键词对齐：进程被杀后重建时 saveable 的文字会留下，
    // 而 ViewModel 里已经是空关键词（列表是全量）——不同步就会出现「框里写着支教、列表却是全部」
    LaunchedEffect(state.keyword) {
        if (state.keyword != searchText) searchText = state.keyword
    }

    // 一滚列表就收键盘：搜索完还顶着键盘看列表是多余的（键盘会一直占着半屏）
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling -> if (scrolling) keyboard?.hide() }
    }

    // 加载更多落地后的反馈（2026-10-08 用户报「点了没反应」，且要求说清加载了多少条）：
    // 新增卡片在折叠线以下时屏上什么都不变——滚到第一张新增的卡 + 报条数；
    // 本地筛选把新页整页滤掉时更是什么都不变，那就明说一句。
    LaunchedEffect(state.loadMoreSeq) {
        if (state.loadMoreSeq == 0) return@LaunchedEffect
        val anchor = loadMoreAnchor ?: return@LaunchedEffect
        loadMoreAnchor = null
        val added = state.visibleActivities.size - anchor
        if (added > 0) {
            listState.animateScrollToItem(HEADER_ITEM_COUNT + anchor)
        }
        snackbar.showNotice(
            when {
                added > 0 && state.hasMore -> "已加载 $added 条"
                added > 0 -> "已加载 $added 条 · 没有更多了"
                state.hasMore -> "这一页没有符合筛选的活动，可继续加载"
                else -> "没有更多活动了"
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("问渠 · 志愿服务", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (state.loggedIn) {
                        // 强刷 / 首屏取数期间换成进度圈：缓存命中时它是「点刷新有没有反应」的唯一反馈
                        if (state.loading || state.refreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(end = 14.dp).size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            IconButton(onClick = { vm.refresh() }) {
                                Icon(HugeIcons.Refresh01, contentDescription = "刷新")
                            }
                        }
                        // 设置（DESIGN §3.28）：记录入口 / 活动提醒 / 退出登录都在那一页
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
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 工时卡 + 搜索 + 筛选 + （被限制报名时的）平台原话，合成**一个** item。
            // 合成一个是为了 [HEADER_ITEM_COUNT] 稳——加载更多的滚动落点靠它算。
            item(key = "header") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    MineCard(state)
                    OutlinedTextField(
                        value = searchText,
                        onValueChange = { searchText = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("搜索活动名 / 单位") },
                        leadingIcon = { Icon(HugeIcons.Search01, contentDescription = null) },
                        trailingIcon = {
                            if (searchText.isNotEmpty()) {
                                IconButton(onClick = {
                                    searchText = ""
                                    vm.loadActivities(keyword = "")
                                }) { Icon(HugeIcons.Cancel01, contentDescription = "清空") }
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            keyboard?.hide()
                            vm.loadActivities(keyword = searchText)
                        }),
                    )
                    FilterRow(
                        status = state.statusFilter,
                        hideFull = state.hideFull,
                        onSelectStatus = vm::setStatusFilter,
                        onToggleHideFull = vm::setHideFull,
                    )
                    if (state.applyBlockedReason.isNotBlank()) {
                        BlockedBanner(reason = state.applyBlockedReason)
                    }
                }
            }

            when {
                state.activitiesLoading -> item(key = "act-loading") {
                    LoadingHint("正在读取活动", modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp))
                }

                visible.isEmpty() -> item(key = "act-empty") {
                    EmptyHint(
                        title = if (state.keyword.isBlank()) "暂时没有可显示的活动"
                        else "没有匹配「${state.keyword}」的活动",
                        body = if (state.keyword.isBlank()) {
                            "平台上还没有能显示的活动，过会儿再来看看。"
                        } else {
                            "换个关键词试试；也可能是筛选条件太窄，或点右上角刷新。"
                        },
                    )
                }

                else -> items(visible, key = { "act-${it.id}" }) { activity ->
                    ActivityCard(activity = activity, onClick = { vm.openDetail(activity) })
                }
            }

            if (state.hasMore && !state.activitiesLoading) {
                item(key = "act-more") {
                    LoadMoreRow(
                        loading = state.loadingMore,
                        onClick = {
                            // 记下起点：新增的卡片会插在「现在这几张」之后，落地后滚过去
                            loadMoreAnchor = visible.size
                            vm.loadMore()
                        },
                    )
                }
            } else if (!state.activitiesLoading && visible.isNotEmpty()) {
                item(key = "act-end") { ListEnd(total = state.activities.size) }
            }

            item(key = "disclaimer") {
                FooterDisclaimer(DISCLAIMER, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }

    // 活动详情弹层：本页只有这一个弹层（记录列表在独立二级页，详情在那边弹）
    state.detail?.let { activity ->
        WenquActivitySheet(
            activity = activity,
            detailLoading = state.detailLoading,
            onDismiss = vm::closeDetail,
            detailUnavailable = state.detailUnavailable,
            onNotice = { msg -> scope.launch { snackbar.showNotice(msg) } },
        )
    }
}

private const val DISCLAIMER =
    "志愿工时 / 劳动教育数据来自「问渠」实践教育平台（第三方系统，非学校官方功能）。" +
        "本应用只做只读展示，报名、签到、提交等操作请到微信小程序内完成。"

/**
 * 活动列表之前有几个 item（加载更多的滚动落点 = 这个数 + 可见活动条数）。
 *
 * **头部那三块（工时卡 / 搜索 / 筛选）合成了一个 `item("header")`**，所以这里是 1——
 * 合成一个就是为了让这个常量稳：往头部加一行（比如那句「被限制报名」的说明）
 * 不会改这个数，加载更多就不会悄悄滚错位置。
 */
private const val HEADER_ITEM_COUNT = 1

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
 * 工时卡：**只展示工时数**（大字 + 星级）。
 *
 * 2026-10-08 用户口径：记录入口、活动提醒、退出登录都搬进设置页（`WENQU_SETTINGS`），
 * 主页这张卡不再挂任何入口——它是「一眼看到自己多少工时」的地方。
 */
@Composable
private fun MineCard(state: WenquViewModel.UiState) {
    // 首屏还没数时别把 0 摆成大数字（看着像工时被清了），给个占位
    val hoursText = if (state.loading && state.hours == 0.0) "…" else state.hoursText
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = HugeIcons.HeartHandshake,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "志愿工时",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (state.starLabel.isNotBlank()) {
                Icon(
                    imageVector = HugeIcons.Medal02,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "${state.starLabel}志愿者",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            AmountHeroText(text = hoursText)
            Spacer(Modifier.width(6.dp))
            Text(
                text = "小时",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
    }
}

/**
 * 平台把账号限制了报名时的说明（`isBlack` + `stop_apply_reason`）。
 *
 * 用平台原话——它是「为什么我报不了名」的唯一权威回答，静默丢掉等于让用户自己猜。
 */
@Composable
private fun BlockedBanner(reason: String) {
    AppCard {
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
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = reason,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
    }
}

/**
 * 筛选行：状态 chips（本地筛）+ 一枚「隐藏已满」开关 chip。
 *
 * 「隐藏已满」放在同一行末尾——它和状态筛选是同一件事的两个维度（都是「我不想看到什么」），
 * 另起一行反而多占一屏高度。两个筛选都**落盘记住**（用户专门要过「隐藏已满」）。
 */
@Composable
private fun FilterRow(
    status: Int?,
    hideFull: Boolean,
    onSelectStatus: (Int?) -> Unit,
    onToggleHideFull: (Boolean) -> Unit,
) {
    val options = remember {
        listOf(
            null to "全部",
            1 to WenquRules.statusLabel(1),
            2 to WenquRules.statusLabel(2),
            3 to WenquRules.statusLabel(3),
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = status == value,
                onClick = { onSelectStatus(value) },
                label = { Text(label) },
            )
        }
        FilterChip(
            selected = hideFull,
            onClick = { onToggleHideFull(!hideFull) },
            label = { Text("隐藏已满") },
            leadingIcon = if (hideFull) {
                { Icon(HugeIcons.CheckmarkCircle02, contentDescription = null, modifier = Modifier.size(16.dp)) }
            } else {
                null
            },
        )
    }
}

/**
 * 「加载更多」：接口不给总数，只能一页页要（见 `WenquActivityPage.hasMore`）。
 * 进行中给进度圈——只把文案换成「加载中…」在窄屏上太容易被忽略。
 */
@Composable
private fun LoadMoreRow(loading: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        TextButton(onClick = onClick, enabled = !loading) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (loading) "加载中…" else "加载更多")
        }
    }
}

/** 列表到底：给个明确的句号，别让用户以为还有下一页。 */
@Composable
private fun ListEnd(total: Int) {
    Text(
        text = "已到底 · 本次共加载 $total 条",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    )
}
