package edu.jxslu.schedule.ui.life

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.WindowManager
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import edu.jxslu.schedule.R
import edu.jxslu.schedule.domain.BalanceAlert
import edu.jxslu.schedule.domain.LifeFeedItem
import edu.jxslu.schedule.domain.LifeFeedKind
import edu.jxslu.schedule.ui.campus.CampusArrivalDialog
import edu.jxslu.schedule.ui.campus.CampusCardViewModel
import edu.jxslu.schedule.ui.campus.CampusPaymentDialog
import edu.jxslu.schedule.ui.campus.CampusPendingConfirmDialog
import edu.jxslu.schedule.ui.campus.PayCodeBitmaps
import edu.jxslu.schedule.ui.campus.PayCodeEvent
import edu.jxslu.schedule.ui.campus.PayCodeResultBus
import edu.jxslu.schedule.ui.campus.PayCodeUiState
import edu.jxslu.schedule.ui.campus.PayCodeViewModel
import edu.jxslu.schedule.ui.campus.RechargeSheet
import edu.jxslu.schedule.ui.common.AppCard
import edu.jxslu.schedule.ui.common.AppNoticeVisuals
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.LocalBottomBarClearance
import edu.jxslu.schedule.ui.common.NoticeTone
import edu.jxslu.schedule.ui.common.SectionHeader
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Bolt
import me.rerere.hugeicons.stroke.CreditCard
import me.rerere.hugeicons.stroke.InformationCircle
import me.rerere.hugeicons.stroke.Invoice01
import me.rerere.hugeicons.stroke.MoneyAdd01
import me.rerere.hugeicons.stroke.Receipt
import me.rerere.hugeicons.stroke.Settings01

/**
 * 生活页（DESIGN §3.13）：一卡通余额 / 付款码 / 寝室电费 / 充值入口 / 最近流水。
 *
 * 三个口径：
 * 1. **码不预取**：进页只拉余额与电费；付款码必须点占位条才登录取批，收起即清码。
 * 2. **失败不编数**：电费读失败保留上次读数并标注时刻，另起一行报错。
 * 3. **一页一个主行动**：付款码卡在最上，其余是卡片与入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LifeScreen(
    /** 消费流水页（消费明细 + 月度统计） */
    onOpenStatement: () -> Unit = {},
    /** 缴费账单页（寝室电费充值/退款按月汇总，DESIGN §3.13） */
    onOpenPowerBill: () -> Unit = {},
    /** 全屏付款码页（付款码卡的「全屏出示」） */
    onOpenPayCode: () -> Unit = {},
    /** 一卡通设置页（凭证未开启时的「去设置开启」） */
    onOpenCampusSettings: () -> Unit = {},
    viewModel: LifeViewModel = viewModel(factory = LifeViewModel.Factory(LocalContext.current)),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = rememberAppHaptics()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 一卡通侧（余额/充值/到账）与付款码侧各自复用既有 ViewModel：
    // 这两个页面的状态机（到账轮询、扫码检测）已经在那边跑通，本页只做入口与展示。
    val campusViewModel: CampusCardViewModel = viewModel(
        factory = CampusCardViewModel.Factory(context.applicationContext),
    )
    val campusEnabled by campusViewModel.enabled.collectAsStateWithLifecycle()
    val balance by campusViewModel.balance.collectAsStateWithLifecycle()
    val balanceLoaded by campusViewModel.balanceLoaded.collectAsStateWithLifecycle()
    val balanceRefreshing by campusViewModel.balanceRefreshing.collectAsStateWithLifecycle()
    val arrival by campusViewModel.arrivalState.collectAsStateWithLifecycle()

    val payCodeViewModel: PayCodeViewModel = viewModel(factory = PayCodeViewModel.Factory(context))
    val codeState by payCodeViewModel.uiState.collectAsStateWithLifecycle()
    val bitmaps by payCodeViewModel.bitmaps.collectAsStateWithLifecycle()
    val detectedPayment by payCodeViewModel.detectedPayment.collectAsStateWithLifecycle()

    var showRechargeSheet by remember { mutableStateOf(false) }
    var showPowerRecharge by remember { mutableStateOf(false) }
    val powerRecharge by viewModel.powerRecharge.collectAsStateWithLifecycle()
    // 「去充值电子账户」联动：打开一卡通充值时预选电子账户（DESIGN §4.24）
    var campusRechargePreferElectric by rememberSaveable { mutableStateOf(false) }

    // 进页刷新一次：电费读数 + 一卡通流水增量同步 + 一卡通余额（开关关时各自短路）。
    // force = false = 走缓存/闸门（DESIGN §4.24「请求节流」）：切 Tab 来回不重复打平台，
    // 用户要看最新就下拉刷新（2026-09-24 起唯一入口），点卡片仍各刷各的那一张。
    LaunchedEffect(Unit) {
        viewModel.refreshAll(force = false)
        campusViewModel.refreshBalance(force = false)
    }

    // 付款码展开期间才防截屏 + 拉满亮度：收起或离开页面立即恢复（同 §3.10 口径）
    val codeVisible = campusEnabled && codeState is PayCodeUiState.Success
    DisposableEffect(codeVisible) {
        val window = (context as? Activity)?.window
        val previousBrightness =
            window?.attributes?.screenBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        if (codeVisible) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            window?.let { w ->
                w.attributes = w.attributes.apply {
                    screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
                }
            }
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            window?.let { w ->
                w.attributes = w.attributes.apply { screenBrightness = previousBrightness }
            }
        }
    }

    // 扫码扣款被检测到：收起码（本页不弹窗，结果经 PayCodeResultBus 落到下面那个对话框）
    LaunchedEffect(detectedPayment) {
        if (detectedPayment != null) payCodeViewModel.collapse()
    }

    // 离开生活页（切 Tab / 关掉开关）即清码：窗口里不留付款码，位图一并回收（DESIGN §3.13）
    DisposableEffect(Unit) {
        onDispose { payCodeViewModel.collapse() }
    }

    // 从微信/浏览器返回的瞬间立即补检一轮（不等 5 秒周期），并在未立即到账时弹
    // 「正在确认充值结果」；进程被杀重启也能靠持久化的未确认充值记录恢复
    // （与「我的 → 校园卡」同一份逻辑，DESIGN §4.19「充值」）。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, campusViewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) campusViewModel.onHostResume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 电费充值进入密码步骤：下单成功即取键盘挑战（passwordMap）
    LaunchedEffect(powerRecharge.orderId, powerRecharge.step) {
        if (powerRecharge.step == LifeViewModel.PowerRechargeUi.Step.Amount &&
            powerRecharge.orderId != null
        ) {
            viewModel.loadPayChallenge()
        }
    }

    LaunchedEffect(Unit) {
        payCodeViewModel.events.collect { event ->
            when (event) {
                is PayCodeEvent.Notice -> snackbar.showSnackbar(AppNoticeVisuals(event.text, tone = event.tone))
            }
        }
    }
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is LifeEvent.Notice -> snackbar.showSnackbar(AppNoticeVisuals(event.text, tone = event.tone))
            }
        }
    }

    val showNotice: (String, NoticeTone) -> Unit = { message, tone ->
        scope.launch { snackbar.showSnackbar(AppNoticeVisuals(message, tone = tone)) }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets.statusBars,
                title = { Text(stringResource(R.string.tab_life)) },
                // 刷新只剩下拉一个入口（2026-09-24 用户要求，与其他页同款）：
                // 顶栏那颗刷新图标删掉——「点卡片刷新」那两处还在，但它们各管一张卡，
                // 「余额 + 电费 + 流水一次全刷」这个动作只留下拉。
                actions = {
                    IconButton(onClick = {
                        haptics.tap()
                        onOpenCampusSettings()
                    }) {
                        Icon(HugeIcons.Settings01, contentDescription = "一卡通设置")
                    }
                },
            )
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        val bottomClearance = LocalBottomBarClearance.current
        // 下拉刷新（2026-09-24）：与消费流水页 / 缴费账单页同一套 `PullToRefreshBox`。
        // 一次刷三样：电费读数 + 一卡通流水增量同步 + 一卡通余额，全部 force = true
        // （用户明确要看最新，不走缓存与 10 分钟闸门，DESIGN §4.24「请求节流」）。
        //
        // padding 收在刷新容器上（同今日页口径）：Scaffold 的内容从 (0,0) 铺满整屏、
        // 顶栏压在它上面，指示器挂在容器顶边时整条滑入轨迹都在顶栏后面，得拖过阈值
        // 一大截才露出半圈，读起来就是「拉了半天没反应」。
        //
        // 驻留兜底（同今日页）：M3 的 onRefresh 在松手那一下回调，而各路的 loading 标志
        // 要等协程跑起来才置 true——直接拿它当 isRefreshing，指示器会在回调瞬间被收回，
        // 体感是「拉下去又弹回去」。manualRefreshing 顶住回调瞬间，等标志归零再收；
        // 650ms 既是最短驻留（网络快时也不闪一下），也是那段竞态的窗口。
        // **不用 `manualRefreshing || busy`**：进页那次 refreshPower 也会把 loading 置 true，
        // 那样一进页就转圈——指示器只认下拉这个动作。
        var manualRefreshing by remember { mutableStateOf(false) }
        val busy = state.power.loading || balanceRefreshing
        LaunchedEffect(manualRefreshing, busy) {
            if (!manualRefreshing) return@LaunchedEffect
            if (busy) return@LaunchedEffect
            delay(650)
            if (!busy) manualRefreshing = false
        }
        PullToRefreshBox(
            isRefreshing = manualRefreshing,
            onRefresh = {
                haptics.tap()
                manualRefreshing = true
                viewModel.refreshAll()
                campusViewModel.refreshBalance()
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = if (bottomClearance > 0.dp) bottomClearance + 16.dp else 16.dp),
            ) {
                PaymentCodeCard(
                    enabled = campusEnabled,
                    state = codeState,
                    bitmaps = bitmaps,
                    onExpand = {
                        haptics.tap()
                        payCodeViewModel.load()
                    },
                    onCollapse = {
                        haptics.tap()
                        payCodeViewModel.collapse()
                    },
                    onNext = {
                        haptics.tap()
                        payCodeViewModel.next()
                    },
                    onFullScreen = {
                        haptics.tap()
                        onOpenPayCode()
                    },
                    onOpenSettings = onOpenCampusSettings,
                )

                // IntrinsicSize.Min：两张并排卡取较大者的内容高，矮的一张用内部 weight 弹性
                // 补齐并把底行钉到卡片底——两卡恒等高（2026-09-24 用户反馈「高度不一样」）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CampusBalanceCard(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        enabled = campusEnabled,
                        balanceFen = balance?.cardFen,
                        accountFen = balance?.accountFen,
                        balanceLoaded = balanceLoaded,
                        balanceRefreshing = balanceRefreshing,
                        arrivalWatching = arrival is CampusCardViewModel.ArrivalState.Watching,
                        balance = balance,
                        onRefresh = {
                            haptics.tap()
                            campusViewModel.refreshBalance()
                        },
                    )
                    PowerCard(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        power = state.power,
                        onRefresh = {
                            haptics.tap()
                            viewModel.refreshPower()
                        },
                    )
                }

                SectionHeader(title = "常用")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    LifeTool(Modifier.weight(1f), HugeIcons.MoneyAdd01, "一卡通充值") {
                        if (campusEnabled) {
                            showRechargeSheet = true
                        } else {
                            showNotice("先在「我的 → 校园卡」开启一卡通", NoticeTone.Warning)
                        }
                    }
                    LifeTool(Modifier.weight(1f), HugeIcons.Bolt, "电费充值") {
                        if (campusEnabled) {
                            // 打开弹层即重置流程并清一遍未支付单（平台不自动清，堆积会让新下单 500）
                            viewModel.preparePowerRecharge()
                            showPowerRecharge = true
                        } else {
                            showNotice("先在「我的 → 校园卡」开启一卡通", NoticeTone.Warning)
                        }
                    }
                    LifeTool(Modifier.weight(1f), HugeIcons.Receipt, "消费流水") {
                        onOpenStatement()
                    }
                    LifeTool(Modifier.weight(1f), HugeIcons.Invoice01, "缴费账单") {
                        onOpenPowerBill()
                    }
                }

                SectionHeader(title = "最近流水")
                FeedCard(items = state.feed)
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    if (showPowerRecharge) {
        PowerRechargeSheet(
            state = powerRecharge,
            accountFen = powerRecharge.challenge?.accountBalanceFen ?: balance?.accountFen,
            roomLabel = state.power.snapshot?.meter?.room?.room,
            remainText = state.power.snapshot?.meter?.remain?.let { "%.2f 度".format(it) },
            onDismiss = {
                showPowerRecharge = false
                viewModel.dismissPowerRecharge()
            },
            onOpenCampusRecharge = {
                showPowerRecharge = false
                viewModel.dismissPowerRecharge()
                campusRechargePreferElectric = true
                showRechargeSheet = true
            },
            onPlaceOrder = { yuan -> viewModel.placePowerOrder(yuan) },
            onLoadChallenge = { viewModel.loadPayChallenge() },
            onSubmitPassword = { cipher -> viewModel.submitPowerPassword(cipher) },
        )
    }
    if (showRechargeSheet) {
        RechargeSheet(
            balanceFen = balance?.cardFen,
            accountFen = balance?.accountFen,
            initiallyElectric = campusRechargePreferElectric,
            onDismiss = {
                showRechargeSheet = false
                campusRechargePreferElectric = false
            },
            onLaunch = { yuan, toElectric ->
                showRechargeSheet = false
                scope.launch {
                    val target = if (toElectric) campusViewModel.electricAccountType() else null
                    campusViewModel.recharge(
                        yuan,
                        targetAccount = target,
                        launchExternal = { intent ->
                            runCatching {
                                (context as? Activity)?.startActivity(intent)
                                    ?: context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                true
                            }.getOrDefault(false)
                        },
                    ) { notice -> showNotice(notice.text, notice.tone) }
                }
            },
        )
    }

    val arrived = arrival as? CampusCardViewModel.ArrivalState.Arrived
    if (arrived != null) {
        CampusArrivalDialog(
            arrived = arrived,
            onDismiss = { campusViewModel.dismissArrival() },
            onOpenPayCode = onOpenPayCode,
        )
    }

    // 「正在确认充值结果」：从微信返回且余额还没更新时给反馈（关闭不影响后台轮询）。
    // 与成功弹窗互斥——到账那一刻 VM 会先把本弹窗撤下（markArrived），不会两张叠着。
    val pendingConfirm by campusViewModel.pendingConfirmVisible.collectAsStateWithLifecycle()
    val watchingArrival = arrival as? CampusCardViewModel.ArrivalState.Watching
    if (pendingConfirm && watchingArrival != null) {
        CampusPendingConfirmDialog(
            orderFen = watchingArrival.orderFen,
            onDismiss = { campusViewModel.dismissPendingConfirm() },
            onNotPaid = { campusViewModel.notPaid() },
        )
    }

    // 「支付成功」：付款码检测到扣款会收起码，弹窗落在本页（取值即消费，与今日页同口径）
    val payResult by PayCodeResultBus.result.collectAsStateWithLifecycle()
    var paidPayment by remember { mutableStateOf<edu.jxslu.schedule.domain.YktPayment?>(null) }
    LaunchedEffect(payResult) {
        payResult?.let {
            paidPayment = it
            PayCodeResultBus.consume()
        }
    }
    paidPayment?.let { paid ->
        CampusPaymentDialog(payment = paid, onDismiss = { paidPayment = null })
    }
}

/** 打开站外链接（缴费平台网页）；失败给一次性提示。同包内（缴费账单页页脚）共用。 */
internal fun openExternal(
    context: android.content.Context,
    url: String,
    onError: (String, NoticeTone) -> Unit,
) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure {
        onError("打不开浏览器：${it.message ?: "未知错误"}", NoticeTone.Error)
    }
}

/**
 * 付款码卡：**码位常驻、状态原地替换**（DESIGN §3.13，2026-09-24 二改）。
 *
 * Idle/取码中/成功/失败四态共用同一套码位几何（[CodeSlots]，参考快趣出行码页出码位的
 * 「固定方形占位」）：空着是描边空框 + 提示，取码是呼吸色块，出码原地换图——
 * 任何状态切换下方内容零位移。展开态与全屏付款码页同一套渲染（QR + Code128 + 信息行），
 * 只是内嵌在卡片里；折叠/离开页面即丢码，窗口里不留付款码。
 */
@Composable
private fun PaymentCodeCard(
    enabled: Boolean,
    state: PayCodeUiState,
    bitmaps: PayCodeBitmaps?,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    onNext: () -> Unit,
    onFullScreen: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val expanded = state is PayCodeUiState.Success || state is PayCodeUiState.Loading
    AppCard(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        highlighted = expanded,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(HugeIcons.CreditCard, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text("付款码", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "等同现金，也能刷宿舍门禁",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                )
            }
            if (enabled) {
                TextButton(onClick = if (expanded) onCollapse else onExpand) {
                    Text(if (expanded) "收起" else "点击出示 ›")
                }
            }
        }

        when {
            !enabled -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("付款码未开启", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "在「我的 → 校园卡」开启后才能出示；凭证加密存本机，可随时关闭",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = onOpenSettings) { Text("去开启") }
                }
            }

            else -> {
                // 四态（Idle/取码中/成功/失败）**共用同一套码位几何**（2026-09-24 二改，
                // 用户反馈「预设占用位置太低」）：参考快趣出行码页出码位的口径——
                // 码位常驻、尺寸用 aspectRatio 占死，空着时是描边空框，出码原地替换，
                // 任何状态切换下面的卡片都不动。按钮行常显，不可用即置灰（同出码页）。
                CodeSlots(
                    qrContent = {
                        when {
                            state is PayCodeUiState.Success && bitmaps != null ->
                                Image(
                                    bitmap = bitmaps.qr.asImageBitmap(),
                                    contentDescription = "校园卡付款码二维码",
                                    modifier = Modifier.fillMaxSize(),
                                )

                            state is PayCodeUiState.Loading ||
                                (state is PayCodeUiState.Success && bitmaps == null) -> ShimmerBox(
                                RoundedCornerShape(12.dp),
                            )

                            state is PayCodeUiState.Error -> Text(
                                text = state.message,
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(12.dp)
                                    .wrapContentSize(Alignment.Center),
                            )

                            else -> Column(
                                modifier = Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Icon(
                                    HugeIcons.CreditCard,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "点击显示付款码",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    "出示后扫码消费",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                                )
                            }
                        }
                    },
                    barcodeContent = {
                        when {
                            state is PayCodeUiState.Success && bitmaps != null ->
                                Image(
                                    bitmap = bitmaps.barcode.asImageBitmap(),
                                    contentDescription = "校园卡付款码条形码",
                                    modifier = Modifier.fillMaxSize(),
                                )

                            state is PayCodeUiState.Loading ||
                                (state is PayCodeUiState.Success && bitmaps == null) -> ShimmerBox(
                                RoundedCornerShape(6.dp),
                            )

                            else -> Box(Modifier.fillMaxSize())
                        }
                    },
                    infoText = when {
                        state is PayCodeUiState.Success ->
                            "${state.accountMasked} · 第 ${state.index + 1}/${state.codes.size} 个 · " +
                                "约 ${state.expiresSeconds / 3600} 小时内有效"
                        state is PayCodeUiState.Loading -> "正在登录一卡通并取码…"
                        state is PayCodeUiState.Error -> "出码失败"
                        else -> "出示后扫码消费"
                    },
                    infoShimmer = state is PayCodeUiState.Loading ||
                        (state is PayCodeUiState.Success && bitmaps == null),
                    // 空框在 Idle 态整块可点；错误态点框重试（canRetry 时），其余不响应
                    onSlotClick = when {
                        state is PayCodeUiState.Idle -> onExpand
                        state is PayCodeUiState.Error && state.canRetry -> onExpand
                        else -> null
                    },
                    buttons = {
                        when {
                            state is PayCodeUiState.Success -> {
                                OutlinedButton(onClick = onNext, modifier = Modifier.weight(1f)) {
                                    Text("换下一个")
                                }
                                OutlinedButton(onClick = onFullScreen, modifier = Modifier.weight(1f)) {
                                    Text("全屏出示")
                                }
                                OutlinedButton(onClick = onCollapse, modifier = Modifier.weight(1f)) {
                                    Text("收起")
                                }
                            }

                            state is PayCodeUiState.Error && state.canRetry -> {
                                OutlinedButton(onClick = onExpand, modifier = Modifier.weight(1f)) {
                                    Text("重试")
                                }
                                OutlinedButton(
                                    enabled = false,
                                    onClick = {},
                                    modifier = Modifier.weight(1f),
                                ) { Text("全屏出示") }
                                OutlinedButton(
                                    enabled = false,
                                    onClick = {},
                                    modifier = Modifier.weight(1f),
                                ) { Text("收起") }
                            }

                            state is PayCodeUiState.Loading ||
                                (state is PayCodeUiState.Success && bitmaps == null) -> {
                                OutlinedButton(
                                    enabled = false,
                                    onClick = {},
                                    modifier = Modifier.weight(1f),
                                ) { Text("换下一个") }
                                OutlinedButton(
                                    enabled = false,
                                    onClick = {},
                                    modifier = Modifier.weight(1f),
                                ) { Text("全屏出示") }
                                OutlinedButton(
                                    enabled = false,
                                    onClick = {},
                                    modifier = Modifier.weight(1f),
                                ) { Text("收起") }
                            }

                            else -> {
                                OutlinedButton(
                                    enabled = false,
                                    onClick = {},
                                    modifier = Modifier.weight(1f),
                                ) { Text("换下一个") }
                                OutlinedButton(onClick = onFullScreen, modifier = Modifier.weight(1f)) {
                                    Text("全屏出示")
                                }
                                OutlinedButton(
                                    enabled = false,
                                    onClick = {},
                                    modifier = Modifier.weight(1f),
                                ) { Text("收起") }
                            }
                        }
                    },
                )
            }
        }

        if (enabled) {
            Text(
                text = "付款码等同现金，勿截图、勿分享",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
            )
        }
    }
}

/**
 * 码位四件套（QR 空框/码图 + 条码 + 信息行 + 按钮行）：**全状态共用，几何恒定**。
 *
 * QR 位 = `aspectRatio(1f)`（码图 720×720）、条码位 = `aspectRatio(6f)`（720×120），
 * 空态是描边空框 + 提示，出码原地替换——参考快趣出行码页出码位的「固定方形占位」口径，
 * 状态切换时下方内容零位移（2026-09-24 用户反馈「预设占用位置太低」的根治）。
 *
 * [onSlotClick] 非 null 时两个码位 + 信息行整体可点（Idle 展开 / 失败重试）。
 * [buttons] 恒渲染三枚按钮位（不足的用置灰按钮占位），行高不变。
 */
@Composable
private fun CodeSlots(
    qrContent: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
    barcodeContent: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
    infoText: String,
    infoShimmer: Boolean,
    onSlotClick: (() -> Unit)?,
    buttons: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val haptics = rememberAppHaptics()
    val clickable = if (onSlotClick == null) {
        Modifier
    } else {
        Modifier.clickable(onClickLabel = "出示付款码") {
            haptics.tap()
            onSlotClick()
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .then(clickable),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.62f)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant,
                    RoundedCornerShape(12.dp),
                ),
            content = qrContent,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(0.62f)
                .aspectRatio(6f)
                .clip(RoundedCornerShape(6.dp))
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant,
                    RoundedCornerShape(6.dp),
                ),
            content = barcodeContent,
        )
        if (infoShimmer) {
            ShimmerBox(
                RoundedCornerShape(6.dp),
                modifier = Modifier
                    .fillMaxWidth(0.5f)
                    .height(16.dp),
            )
        } else {
            Text(
                text = infoText,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = buttons)
    }
}

/** 取码加载中的呼吸色块（配合 [CodeSlots] 占位，颜色随主题前景色呼吸）。 */
@Composable
private fun ShimmerBox(shape: RoundedCornerShape, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "lifeCodeSkeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 700), RepeatMode.Reverse),
        label = "lifeCodeSkeletonAlpha",
    )
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)),
    )
}

/**
 * 一卡通余额卡：点卡片刷新；未开启凭证时给缺口文案。
 *
 * 底行（2026-09-24 三改/四改）：余额下写「电子账户余额」；有更新时刻时底行只留「HH:mm 更新」，
 * 延迟说明收进右侧圆圈说明图标（[CardFooter]），点击弹说明。无时刻时回退状态文字。
 * 卡片本体可点刷新不变；流水入口只在「常用」格里留一个（2026-09-23 收口）。
 */
@Composable
private fun CampusBalanceCard(
    modifier: Modifier,
    enabled: Boolean,
    balanceFen: Long?,
    accountFen: Long?,
    balanceLoaded: Boolean,
    /** 余额查询进行中（点卡片 / 进页 / 下拉刷新）。 */
    balanceRefreshing: Boolean,
    /** 有充值订单在等待确认到账（[CampusCardViewModel.ArrivalState.Watching]）。 */
    arrivalWatching: Boolean,
    /** 余额快照（取 fetchedAtMs 当更新时间；null = 还没取到）。 */
    balance: PayCodeViewModel.BalanceSnapshot?,
    onRefresh: () -> Unit,
) {
    AppCard(modifier = modifier, onClick = onRefresh) {
        // 上下各留一个弹性空隙（2026-09-24 四改）：卡片被旁边更高的那张撑开时，
        // 多出来的高度上下均分，主内容块（标题 + 余额）在「卡片顶到脚注之间」居中，
        // 不再全堆在余额下方（用户反馈「内容不居中、下方空白较多」）
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                HugeIcons.CreditCard,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Spacer(Modifier.size(6.dp))
            Text(
                text = "一卡通",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
        Spacer(Modifier.height(6.dp))
        when {
            !enabled -> {
                Text("—", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = "未开启凭证",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }

            balanceFen == null -> {
                Text("—", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = if (balanceLoaded) "余额暂不可用" else "读取中…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }

            else -> {
                Text(
                    text = "¥%.2f".format(balanceFen / 100.0),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "电子账户 ¥%.2f".format((accountFen ?: 0L) / 100.0),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        // 脚注钉在卡片底：两张并排卡的底行同一水平线（状态文字优先，其次是更新时刻）
        when {
            !enabled -> CardFooter("去设置开启")
            arrivalWatching -> CardFooter("有充值正在确认到账…")
            balanceRefreshing -> CardFooter("正在查询余额…")
            balance != null -> CardFooter(
                text = "%s 更新".format(formatTime(balance.fetchedAtMs)),
                noteTitle = "余额说明",
                noteText = "余额与流水都来自一卡通平台，服务端落账有延迟，" +
                    "刚发生的消费或充值可能暂时不计入。",
            )
            else -> CardFooter("")
        }
    }
}

/**
 * 寝室电费卡：剩余电量 + 折算金额 + 房间 + 更新时刻；失败保留上次值并标注。
 *
 * 底行（2026-09-24 三改/四改）：更新时刻 + 右侧圆圈说明图标（[CardFooter]），
 * 延迟与缴费提示收在图标里，不再占满半屏宽的底行。
 */
@Composable
private fun PowerCard(
    modifier: Modifier,
    power: PowerCardState,
    onRefresh: () -> Unit,
) {
    val snapshot = power.snapshot
    val meter = snapshot?.meter
    /** 单价（元/度），取不到就只报电量、不折算金额。 */
    val pricePerUnit = snapshot?.feeItem?.priceYuan
    AppCard(modifier = modifier, onClick = onRefresh) {
        // 上下弹性空隙：与一卡通余额卡同一套居中方案（见那边的注释）
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                HugeIcons.Bolt,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Spacer(Modifier.size(6.dp))
            Text(
                text = "寝室电费",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
            val roomLabel = meter?.room?.room
            if (!roomLabel.isNullOrBlank()) {
                Spacer(Modifier.size(6.dp))
                Text(
                    text = roomLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        when {
            power.noCredentials -> {
                Text("—", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = "未开启凭证",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }

            meter?.remain != null -> {
                Text(
                    text = "%.2f 度".format(meter.remain),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                // 「元」的换算只有一处口径（domain/BalanceAlert.remainingYuan，余额提醒共用）：
                // 电量或单价缺失就只报电量字段，不折算金额
                val unitPrice = pricePerUnit
                val remainYuan = BalanceAlert.remainingYuan(meter.remain, unitPrice)
                Text(
                    text = if (remainYuan != null && unitPrice != null) {
                        "≈ ¥%.2f · %.2f 元/度".format(remainYuan, unitPrice)
                    } else {
                        meter.remainField.orEmpty()
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            power.loading -> {
                Text("—", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = "读取中…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }

            else -> {
                Text("—", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = power.error ?: "点卡片刷新",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (power.error != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        // 脚注钉在卡片底（与余额卡同一水平线）
        when {
            power.noCredentials -> CardFooter("去设置开启")
            meter == null -> CardFooter("点卡片刷新")
            power.error != null -> CardFooter(
                text = "上次 %.2f 度 · %s".format(meter.remain ?: 0.0, formatTime(meter.fetchedAtMs)),
                noteTitle = "电费说明",
                noteText = ELECTRICITY_NOTE,
            )
            else -> CardFooter(
                text = "%s 更新".format(formatTime(meter.fetchedAtMs)),
                noteTitle = "电费说明",
                noteText = ELECTRICITY_NOTE,
            )
        }
    }
}

/**
 * 卡片底行（2026-09-24 四改）：左边一行小字（更新时刻或状态文字），右边一枚圆圈说明图标，
 * 点击弹说明弹窗（[noteTitle] / [noteText]）。
 *
 * 为什么收进图标：卡片只占半屏宽，原来「HH:mm 更新 · 实际费用更新有延迟」一行放不下，
 * 时刻常被省略号吃掉；说明本身也不是每次都看。图标自占一格并**吃掉自己那块点击**，
 * 不会连带触发卡片的「点卡片刷新」（父级 clickable 收不到已消费的事件）。
 *
 * 为什么图标是圆圈问号而不是感叹号（2026-09-24 用户反馈「看着像账号有风险」）：
 * 这里补的是一句中性说明，不是告警；感叹号只留给真的出错（消息条 `NoticeTone.Warning` 用它）。
 *
 * **行高钉死 24dp、文字垂直居中**：带图标的「20:36 更新」和不带图标的「正在查询余额…」
 * 两种内容切换时文字零位移（此前普通文字贴底、图标行被撑到 24dp，两种状态差 4dp）。
 */
@Composable
private fun CardFooter(
    text: String,
    noteTitle: String? = null,
    noteText: String? = null,
) {
    var showNote by remember { mutableStateOf(false) }
    val haptics = rememberAppHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (noteTitle != null && noteText != null) {
            // 24dp 触摸面：卡底行只有 24dp 高，图标用 IconButton（48dp）会把卡片顶高一截
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .clickable(onClickLabel = "查看说明") {
                        haptics.tap()
                        showNote = true
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    HugeIcons.InformationCircle,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                )
            }
        }
    }
    val dialogTitle = noteTitle
    val dialogText = noteText
    if (showNote && dialogTitle != null && dialogText != null) {
        AlertDialog(
            onDismissRequest = { showNote = false },
            title = { Text(dialogTitle) },
            text = { Text(dialogText) },
            confirmButton = {
                TextButton(onClick = { showNote = false }) { Text("知道了") }
            },
        )
    }
}

/** 常用格（一行四列，与今日页服务格同款观感）。 */
@Composable
private fun LifeTool(
    modifier: Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    AppCard(
        modifier = modifier,
        onClick = onClick,
        contentPadding = PaddingValues(vertical = 12.dp, horizontal = 4.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 最近流水：一卡通与电费混排（DESIGN §3.13）。入口统一在「常用」格的「消费流水」。 */
@Composable
private fun FeedCard(items: List<LifeFeedItem>) {
    AppCard(
        modifier = Modifier.padding(horizontal = 16.dp),
        contentPadding = PaddingValues(0.dp),
    ) {
        if (items.isEmpty()) {
            Text(
                text = "还没有流水。开启一卡通后，消费与电费充值都会记在这里",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(14.dp),
            )
        }
        items.forEach { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (item.kind == LifeFeedKind.Power) HugeIcons.Bolt else HugeIcons.Receipt,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (item.kind == LifeFeedKind.Power) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    },
                )
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = item.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.size(8.dp))
                Text(
                    text = (if (item.income) "+" else "-") + "%.2f".format(kotlin.math.abs(item.amountFen) / 100.0),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (item.income) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}

/** 电费说明（底行图标的弹窗文案，2026-09-24 用户给的原话；只有这一处口径）。 */
private const val ELECTRICITY_NOTE = "受服务器影响，实际费用可能有延迟，电费不足时请及时缴费"

private val TIME_FORMAT = java.time.format.DateTimeFormatter.ofPattern("HH:mm")

private fun formatTime(epochMs: Long): String = runCatching {
    java.time.Instant.ofEpochMilli(epochMs)
        .atZone(java.time.ZoneId.systemDefault())
        .format(TIME_FORMAT)
}.getOrDefault("—")
