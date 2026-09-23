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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.jxslu.schedule.R
import edu.jxslu.schedule.domain.LifeFeedItem
import edu.jxslu.schedule.domain.LifeFeedKind
import edu.jxslu.schedule.ui.campus.CampusArrivalDialog
import edu.jxslu.schedule.ui.campus.CampusCardViewModel
import edu.jxslu.schedule.ui.campus.CampusPaymentDialog
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
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Bolt
import me.rerere.hugeicons.stroke.CreditCard
import me.rerere.hugeicons.stroke.Invoice01
import me.rerere.hugeicons.stroke.MoneyAdd01
import me.rerere.hugeicons.stroke.Receipt
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Settings01
import me.rerere.hugeicons.stroke.Exchange01

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
    val arrival by campusViewModel.arrivalState.collectAsStateWithLifecycle()

    val payCodeViewModel: PayCodeViewModel = viewModel(factory = PayCodeViewModel.Factory(context))
    val codeState by payCodeViewModel.uiState.collectAsStateWithLifecycle()
    val bitmaps by payCodeViewModel.bitmaps.collectAsStateWithLifecycle()
    val detectedPayment by payCodeViewModel.detectedPayment.collectAsStateWithLifecycle()

    var showRechargeSheet by remember { mutableStateOf(false) }
    /** 余额卡当前展示哪个钱包（DESIGN §3.10）：false = 正式卡（默认），true = 电子账户。 */
    var showElectricBalance by rememberSaveable { mutableStateOf(false) }
    var showPowerRecharge by remember { mutableStateOf(false) }
    val powerRecharge by viewModel.powerRecharge.collectAsStateWithLifecycle()
    // 「去充值电子账户」联动：打开一卡通充值时预选电子账户（DESIGN §4.24）
    var campusRechargePreferElectric by rememberSaveable { mutableStateOf(false) }

    // 进页刷新一次：电费读数 + 一卡通流水增量同步 + 一卡通余额（开关关时各自短路）
    LaunchedEffect(Unit) {
        viewModel.refreshAll()
        campusViewModel.refreshBalance()
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
                actions = {
                    IconButton(onClick = {
                        haptics.tap()
                        viewModel.refreshAll()
                        campusViewModel.refreshBalance()
                    }) {
                        Icon(HugeIcons.Refresh01, contentDescription = "刷新")
                    }
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
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

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CampusBalanceCard(
                    modifier = Modifier.weight(1f),
                    enabled = campusEnabled,
                    balanceFen = balance?.cardFen,
                    accountFen = balance?.accountFen,
                    balanceLoaded = balanceLoaded,
                    showElectric = showElectricBalance,
                    onToggleAccount = { showElectricBalance = !showElectricBalance },
                    onRefresh = {
                        haptics.tap()
                        campusViewModel.refreshBalance()
                    },
                )
                PowerCard(
                    modifier = Modifier.weight(1f),
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
                    haptics.tap()
                    if (campusEnabled) {
                        showRechargeSheet = true
                    } else {
                        showNotice("先在「我的 → 校园卡」开启一卡通", NoticeTone.Warning)
                    }
                }
                LifeTool(Modifier.weight(1f), HugeIcons.Bolt, "电费充值") {
                    haptics.tap()
                    if (campusEnabled) {
                        showPowerRecharge = true
                    } else {
                        showNotice("先在「我的 → 校园卡」开启一卡通", NoticeTone.Warning)
                    }
                }
                LifeTool(Modifier.weight(1f), HugeIcons.Receipt, "消费流水") {
                    haptics.tap()
                    onOpenStatement()
                }
                LifeTool(Modifier.weight(1f), HugeIcons.Invoice01, "缴费账单") {
                    haptics.tap()
                    viewModel.openPowerBillPage { url -> openExternal(context, url, showNotice) }
                }
            }

            SectionHeader(title = "最近流水")
            FeedCard(items = state.feed)
            Spacer(Modifier.height(8.dp))
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

/** 打开站外链接（缴费平台网页）；失败给一次性提示。 */
private fun openExternal(
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
 * 付款码卡：**占位 → 点击取码**（DESIGN §3.13）。
 *
 * 展开态与全屏付款码页同一套渲染（QR + Code128 + 信息行），只是内嵌在卡片里；
 * 折叠/离开页面即丢码，窗口里不留付款码。
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

            state is PayCodeUiState.Idle -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                        .clickable(onClick = onExpand)
                        .padding(vertical = 26.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("点击显示付款码", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "出示后扫码消费",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    )
                }
            }

            state is PayCodeUiState.Loading -> CodeSkeleton()

            state is PayCodeUiState.Error -> Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                if (state.canRetry) OutlinedButton(onClick = onExpand) { Text("重试") }
            }

            state is PayCodeUiState.Success -> {
                if (bitmaps == null) {
                    CodeSkeleton()
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Image(
                            bitmap = bitmaps.qr.asImageBitmap(),
                            contentDescription = "校园卡付款码二维码",
                            modifier = Modifier
                                .fillMaxWidth(0.62f)
                                .clip(RoundedCornerShape(12.dp)),
                        )
                        Image(
                            bitmap = bitmaps.barcode.asImageBitmap(),
                            contentDescription = "校园卡付款码条形码",
                            modifier = Modifier
                                .fillMaxWidth(0.62f)
                                .clip(RoundedCornerShape(6.dp)),
                        )
                        Text(
                            text = "${state.accountMasked} · 第 ${state.index + 1}/${state.codes.size} 个 · " +
                                "约 ${state.expiresSeconds / 3600} 小时内有效",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onNext) { Text("换下一个") }
                            OutlinedButton(onClick = onFullScreen) { Text("全屏出示") }
                            OutlinedButton(onClick = onCollapse) { Text("收起") }
                        }
                    }
                }
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

/** 取码骨架：与成功态同布局（方图 + 条码条 + 文本行），渲染完成零位移替换。 */
@Composable
private fun CodeSkeleton() {
    val transition = rememberInfiniteTransition(label = "lifeCodeSkeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 700), RepeatMode.Reverse),
        label = "lifeCodeSkeletonAlpha",
    )
    val shimmer = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.62f)
                .height(180.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(shimmer),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(0.62f)
                .height(46.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(shimmer),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(shimmer),
        )
        Text(
            text = "正在登录一卡通并取码…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        )
    }
}

/**
 * 一卡通余额卡：点卡片刷新；未开启凭证时给缺口文案。
 *
 * 流水入口只在「常用」格里留一个（2026-09-23 收口）：这一页原先有余额卡「流水 ›」、
 * 常用格「消费流水」、列表尾「全部流水 ›」三处入口，用户明确要求只留一个。
 */
@Composable
private fun CampusBalanceCard(
    modifier: Modifier,
    enabled: Boolean,
    balanceFen: Long?,
    accountFen: Long?,
    balanceLoaded: Boolean,
    /** true = 当前展示电子账户（点卡片标签切换；展示不影响点卡片刷新）。 */
    showElectric: Boolean,
    onToggleAccount: () -> Unit,
    onRefresh: () -> Unit,
) {
    AppCard(modifier = modifier, onClick = onRefresh) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                HugeIcons.CreditCard,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Spacer(Modifier.size(6.dp))
            Text(
                text = if (showElectric) "电子账户" else "一卡通",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
        Spacer(Modifier.height(6.dp))
        val shownFen = if (showElectric) accountFen else balanceFen
        when {
            !enabled -> {
                Text("—", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = "未开启凭证",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }

            shownFen == null -> {
                Text("—", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = if (balanceLoaded) "点卡片刷新" else "读取中…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }

            else -> {
                Text(
                    text = "¥%.2f".format(shownFen / 100.0),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (showElectric) "用于电费等线上缴费" else "食堂 · 门禁 · 消费",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (enabled) "点卡片刷新" else "去设置开启",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.weight(1f),
            )
            // 钱包切换（DESIGN §3.10）：右下角切换图标，点它换显示正式卡/电子账户
            if (enabled && accountFen != null) {
                Icon(
                    imageVector = HugeIcons.Exchange01,
                    contentDescription = if (showElectric) "切换到一卡通余额" else "切换到电子账户余额",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(18.dp)
                        .clickable(onClick = onToggleAccount),
                )
            }
        }
    }
}

/** 寝室电费卡：剩余电量 + 折算金额 + 房间 + 更新时刻；失败保留上次值并标注。 */
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
                Text(
                    text = if (pricePerUnit != null) {
                        "≈ ¥%.2f · %.2f 元/度".format(meter.remain * pricePerUnit, pricePerUnit)
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
        Spacer(Modifier.height(6.dp))
        Text(
            text = when {
                power.noCredentials -> "去设置开启"
                power.error != null && meter != null ->
                    "上次 %.2f 度 · %s".format(meter.remain ?: 0.0, formatTime(meter.fetchedAtMs))
                meter != null -> "%s 更新".format(formatTime(meter.fetchedAtMs))
                else -> "点卡片刷新"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
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

private val TIME_FORMAT = java.time.format.DateTimeFormatter.ofPattern("HH:mm")

private fun formatTime(epochMs: Long): String = runCatching {
    java.time.Instant.ofEpochMilli(epochMs)
        .atZone(java.time.ZoneId.systemDefault())
        .format(TIME_FORMAT)
}.getOrDefault("—")
