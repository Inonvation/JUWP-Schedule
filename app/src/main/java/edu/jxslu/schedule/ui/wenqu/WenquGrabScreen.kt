package edu.jxslu.schedule.ui.wenqu

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.wenqu.WenquActivity
import edu.jxslu.schedule.domain.WenquRules
import edu.jxslu.schedule.ui.common.AppCard
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.DialogWindowFadeEffect
import edu.jxslu.schedule.ui.common.EmptyHint
import edu.jxslu.schedule.ui.common.FooterDisclaimer
import edu.jxslu.schedule.ui.common.pinnedStatusBars
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import edu.jxslu.schedule.ui.common.showNotice
import edu.jxslu.schedule.ui.reminder.ReminderChime
import edu.jxslu.schedule.ui.reminder.ReminderVibration
import edu.jxslu.schedule.ui.reminder.WenquActivityAlertReminder
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Bookmark01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Target01
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 蹲点抢活动页（DESIGN §3.28 / §4.45，2026-10-11；用户口径）。
 *
 * 从问渠主页顶栏的「蹲点」图标进来。页面两件事：
 * 1. **控制卡**：填目标活动名（预填 + 落盘）、开始 / 停止蹲点、状态行
 *    （已检查次数 / 上轮耗时 / 收工原因），排了预报名时另挂一行「已预报名」；
 * 2. **活动列表**：与主页同一份本地快照（探测结果顺手并进来，通常零额外请求），
 *    点卡片看详情。
 *
 * 命中时弹 [WenquGrabHitDialog]（确认报名 / 先看看 / 关闭），**不自动提交**；
 * 蹲点期间屏幕常亮（息屏后系统会冻结进程，探测就断了）；命中直接震动
 * （同提醒的「直接震动」口径，用户可能没盯着屏幕）。
 *
 * 本页顶栏用 [pinnedStatusBars]：详情弹层里有「去微信报名」出口，
 * 跨 task 过渡期间状态栏 insets 会瞬时归零，实时值会让顶栏跳一下
 * （见 `.agents/rules/nav-window.md`）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WenquGrabScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: WenquGrabViewModel = viewModel(
        factory = WenquGrabViewModel.Factory(Graph.wenqu(context), Graph.displayPrefs(context)),
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val haptics = rememberAppHaptics()
    val listState = rememberLazyListState()

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbar.showNotice(message)
        vm.consumeMessage()
    }

    // 命中那一刻震一下 + 响一声：用户可能没盯着屏幕（同提醒的直接震动口径）
    LaunchedEffect(state.hit?.id) {
        if (state.hit != null) {
            ReminderVibration.vibrate(context)
            ReminderChime.play(context)
        }
    }

    // 蹲点期间保持屏幕常亮（用户口径）：息屏后系统可能把进程冻住，探测就断了。
    // 命中弹窗开着时也常亮，别让用户回来时屏幕已经黑了。
    val keepScreenOn = state.phase == WenquGrabViewModel.Phase.Checking ||
        state.hit != null
    val view = LocalView.current
    DisposableEffect(keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    // 填了 / 改了活动名，匹配项会整批置前：把列表拉回顶部，别把用户丢在
    // 「另一份结果」的中间（同主页换检索词就回顶的口径）
    LaunchedEffect(state.keyword) {
        if (listState.firstVisibleItemIndex > 0) listState.scrollToItem(0)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("蹲点抢活动", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                windowInsets = pinnedStatusBars(),
            )
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        if (!state.loggedIn) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
            ) {
                AppCard {
                    Text(
                        text = "未登录问渠。先回上一页登录，再来蹲点。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            return@Scaffold
        }

        // 过滤 + 置前后的最终列表（匹配目标的排在前面，见 UiState.visibleActivities）
        val visible = state.visibleActivities
        PullToRefreshBox(
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
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "grab-control") {
                    GrabControlCard(
                        state = state,
                        onKeyword = vm::setKeyword,
                        onStart = {
                            haptics.tap()
                            vm.start()
                        },
                        onStop = vm::stop,
                        onUnpin = {
                            vm.unpin()
                            scope.launch { WenquActivityAlertReminder.onPinChanged(context) }
                        },
                        onCancelPrereg = {
                            scope.launch {
                                WenquPreregReminder.cancel(context)
                                snackbar.showNotice("已取消预报名")
                            }
                        },
                    )
                }

                // 过滤行（2026-10-11 用户点验反馈补上）：复用主页那份「隐藏已满」落盘值，
                // 一处开关两边一致；右边给条数与被藏掉的条数，别让用户猜
                item(key = "grab-filter-row") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(
                            selected = state.hideFull,
                            onClick = { vm.setHideFull(!state.hideFull) },
                            label = { Text("过滤已满") },
                        )
                        Spacer(Modifier.weight(1f))
                        val hidden = state.items.size - visible.size
                        Text(
                            text = buildString {
                                append("共 ${state.items.size} 条")
                                if (hidden > 0) append(" · 隐藏 $hidden 条")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        )
                    }
                }

                when {
                    state.indexing -> item(key = "grab-skeleton") { WenquActivitySkeleton() }

                    state.items.isEmpty() && !state.indexAvailable -> item(key = "grab-unavailable") {
                        EmptyHint(
                            title = "活动没抓到",
                            body = "点下面重试，或下拉刷新；还是不行就是问渠平台这会儿连不上。",
                            actionLabel = "重试",
                            onAction = vm::refresh,
                        )
                    }

                    state.items.isEmpty() -> item(key = "grab-empty") {
                        EmptyHint(
                            title = "暂时没有可显示的活动",
                            body = "平台上还没有能显示的活动，过会儿下拉刷新看看。",
                        )
                    }

                    visible.isEmpty() -> item(key = "grab-hidden") {
                        EmptyHint(
                            title = "有 ${state.items.size} 条活动被「过滤已满」藏起来了",
                            body = "当前筛选把活动都挡掉了，清掉筛选就能看到。",
                            actionLabel = "显示全部",
                            onAction = { vm.setHideFull(false) },
                        )
                    }

                    else -> items(visible, key = { "grab-${it.id}" }) { activity ->
                        ActivityCard(
                            activity = activity,
                            // 目标活动名的命中段高亮（与主页搜索同一份口径）：扫一眼就找到它
                            keyword = state.keyword,
                            onClick = { vm.openDetail(activity) },
                        )
                    }
                }

                if (visible.isNotEmpty()) {
                    item(key = "grab-end") {
                        Text(
                            text = "已到底 · 共 ${visible.size} 条",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )
                    }
                }

                item(key = "grab-disclaimer") {
                    FooterDisclaimer(WENQU_GRAB_FOOTER, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }

    // 命中弹窗：直接弹「确认报名」（**不自动提交**，仍是用户点一下才提交）
    state.hit?.let { hit ->
        WenquGrabHitDialog(
            activity = hit,
            busy = state.applying,
            error = state.hitError,
            onConfirm = vm::applyHit,
            onPeek = vm::openHitDetail,
            onDismiss = vm::dismissHit,
        )
    }

    // 详情弹层：点列表卡 / 命中弹窗的「先看看」
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
                blacklisted = state.blacklisted,
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
            onPin = {
                vm.pin(activity)
                vm.closeDetail()
                scope.launch {
                    WenquActivityAlertReminder.onPinChanged(context)
                    snackbar.showNotice("已盯上「${activity.title.ifBlank { "这条活动" }}」，可以报名时会提醒")
                }
            },
        )
    }
}

/**
 * 控制卡：目标活动名 + 开始 / 停止 + 状态行。
 *
 * 状态行给「上轮耗时 / 连败」诊断数据，供抢活动当天按实测调参。
 */
@Composable
private fun GrabControlCard(
    state: WenquGrabViewModel.UiState,
    onKeyword: (String) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onUnpin: () -> Unit,
    onCancelPrereg: () -> Unit,
) {
    val phase = state.phase
    val armed = phase == WenquGrabViewModel.Phase.Checking

    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = HugeIcons.Target01,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "蹲点",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "到点自动反复看最新活动，出现目标且能报名时弹确认框（不会自动提交）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                maxLines = 2,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = state.keyword,
            onValueChange = onKeyword,
            singleLine = true,
            label = { Text("要蹲的活动名") },
            placeholder = { Text("如：爱心家教") },
            supportingText = { Text("留空只浏览列表；检测中改名字会立即改蹲新名字，清空即停") },
            modifier = Modifier.fillMaxWidth(),
        )
        // 「盯着的活动」（2026-10-15）：每轮单独拉它的实时详情，所以**已满的老活动**也能盯
        // ——名额一空出就弹确认框。与上面的名字**并行**，两条路都在跑。
        if (state.pinnedId > 0L) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = HugeIcons.Bookmark01,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "盯着：${state.pinnedActivity?.title.orEmpty().ifBlank { "这条活动" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onUnpin) {
                    Icon(
                        imageVector = HugeIcons.Cancel01,
                        contentDescription = "不盯了",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        // 「预报名」（2026-10-16）：详情里开的到点自动抢，这里只展示状态 + 取消入口。
        if (state.preregId > 0L) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = buildString {
                        append("已预报名：")
                        append(
                            WenquRules.preregOpenLabel(
                                state.preregOpensAt,
                                System.currentTimeMillis(),
                            ),
                        )
                        append(" 自动抢")
                        if (state.preregTitle.isNotBlank()) append("《${state.preregTitle}》")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onCancelPrereg) {
                    Icon(
                        imageVector = HugeIcons.Cancel01,
                        contentDescription = "取消预报名",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = if (armed) onStop else onStart,
                enabled = armed || state.keyword.isNotBlank() || state.pinnedId > 0L,
            ) {
                Text(if (armed) "停止蹲点" else "开始蹲点")
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = statusText(state),
                style = MaterialTheme.typography.bodySmall,
                color = when (phase) {
                    WenquGrabViewModel.Phase.Checking -> MaterialTheme.colorScheme.primary
                    WenquGrabViewModel.Phase.Stopped ->
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)

                    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                },
                maxLines = 2,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private fun statusText(state: WenquGrabViewModel.UiState): String =
    when (state.phase) {
        WenquGrabViewModel.Phase.Idle ->
            "未开始。填好活动名点「开始蹲点」；开始即检测，发布前 20 秒到后 15 秒自动加密到 1 秒一轮。"

        WenquGrabViewModel.Phase.Checking -> buildString {
            append("检测中 · 蹲『")
            append(state.keyword.trim().ifBlank { "盯着的活动" })
            append("』· 已检查 ${state.checks} 次")
            if (state.lastRoundMs > 0L) {
                append(" · 上轮 ${"%.1f".format(state.lastRoundMs / 1000.0)}s")
                if (state.failures > 0) append("（连败 ${state.failures}）")
            }
            if (state.lastCheckAt > 0L) append(" · 最近 ${clockText(state.lastCheckAt)}")
        }

        WenquGrabViewModel.Phase.Stopped ->
            if (state.stopReason.isNotBlank()) "已停止 · ${state.stopReason}" else "已停止"
    }

/** 时刻（HH:mm:ss，本地时区）。 */
private fun clockText(millis: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date(millis))

/**
 * 命中弹窗：**直接弹「确认报名」**（用户口径），但不自动提交，提交由这一次点击触发。
 *
 * 「先看看」打开活动详情（详情里仍有报名入口）；「关闭」之后活动已经在列表里，
 * 随时能点开。报名失败的原因留在弹窗里（页面级提示会被弹窗盖住，同详情弹层的口径）。
 */
@Composable
private fun WenquGrabHitDialog(
    activity: WenquActivity,
    busy: Boolean,
    error: String?,
    onConfirm: () -> Unit,
    onPeek: () -> Unit,
    onDismiss: () -> Unit,
) {
    val name = activity.title.ifBlank { activity.projectName.ifBlank { "志愿服务活动" } }
    val quota = WenquRules.quotaText(activity.joinNum, activity.peopleNum)
    val applyRange = WenquRules.timeRange(activity.applyStartTime, activity.applyEndTime)
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("蹲到了") },
        text = {
            DialogWindowFadeEffect()
            Column {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                if (quota.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = quota,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    )
                }
                if (applyRange.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "报名时间 $applyRange",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "点「确认报名」才会提交到「问渠」平台；本应用不会自动提交。" +
                        "关闭后蹲点继续，出现新的匹配活动会再弹。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                if (!error.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !busy) {
                Text(if (busy) "报名中…" else "确认报名")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onPeek, enabled = !busy) { Text("先看看") }
                TextButton(onClick = onDismiss, enabled = !busy) { Text("关闭") }
            }
        },
    )
}
