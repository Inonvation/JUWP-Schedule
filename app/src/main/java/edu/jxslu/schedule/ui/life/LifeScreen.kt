package edu.jxslu.schedule.ui.life

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.jxslu.schedule.R
import edu.jxslu.schedule.PowerBankPayActivity
import edu.jxslu.schedule.domain.BalanceAlert
import edu.jxslu.schedule.domain.LifeFeedItem
import edu.jxslu.schedule.domain.LifeFeedKind
import edu.jxslu.schedule.domain.LifeFeedSections
import edu.jxslu.schedule.startActivityOutsideApp
import edu.jxslu.schedule.ui.campus.CampusArrivalDialog
import edu.jxslu.schedule.ui.campus.CampusCardViewModel
import edu.jxslu.schedule.ui.campus.CampusPaymentDialog
import edu.jxslu.schedule.ui.campus.CampusPendingConfirmDialog
import edu.jxslu.schedule.ui.campus.PayCodeResultBus
import edu.jxslu.schedule.ui.campus.PayCodeViewModel
import edu.jxslu.schedule.ui.campus.RechargeSheet
import edu.jxslu.schedule.ui.common.AppCard
import edu.jxslu.schedule.ui.common.AppCardDivider
import edu.jxslu.schedule.ui.common.AppNoticeVisuals
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.LocalBottomBarClearance
import edu.jxslu.schedule.ui.common.NoticeTone
import edu.jxslu.schedule.ui.common.rememberResumeTick
import edu.jxslu.schedule.ui.common.pinnedStatusBars
import edu.jxslu.schedule.ui.common.SectionHeader
import edu.jxslu.schedule.ui.common.SkeletonBox
import edu.jxslu.schedule.ui.common.lineHeightDp
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Bolt
import me.rerere.hugeicons.stroke.CreditCard
import me.rerere.hugeicons.stroke.InformationCircle
import me.rerere.hugeicons.stroke.Receipt
import me.rerere.hugeicons.stroke.Settings01

/**
 * 生活页（DESIGN 3.13）：付款码入口 / 一卡通与寝室电费 / 最近流水与账单入口。
 *
 * 三条口径：
 * 1. **码不预取**：本页只放一条入口，点进去才是 PayCodeScreen，取码发生在那一页的进页
 *    load()。本页不再内嵌码位（2026-09-26 收口：内嵌卡在未取码时要占掉半屏的空框与三个
 *    置灰按钮，而这些位置不产生任何信息）。
 * 2. **失败不编数**：电费读失败时保留上次读数并标注「读取失败，显示上次读数」，不报零。
 * 3. **一处凭证**：电费与一卡通共用 YktCredentialStore，关掉一卡通时两栏一起回空态。
 *
 * 版式（自上而下）：付款码条 -> 我的钱包卡（一卡通 / 寝室电费两栏）-> 最近流水（按来源分
 * 两段，出口各自落在段标题上）。原来那行「常用」四格已拆掉：两个充值跟着各自的钱包栏走，
 * 消费流水与缴费账单跟着流水段走。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LifeScreen(
    /** 消费流水页（一卡通消费明细 + 月度统计） */
    onOpenStatement: () -> Unit = {},
    /** 缴费账单页（寝室电费用电统计 + 充值账单，进页默认用电统计） */
    onOpenPowerBill: () -> Unit = {},
    /** 全屏付款码页（付款码条的点击落点） */
    onOpenPayCode: () -> Unit = {},
    /** 一卡通设置页（凭证未开启时的「去开启」） */
    onOpenCampusSettings: () -> Unit = {},
    viewModel: LifeViewModel = viewModel(factory = LifeViewModel.Factory(LocalContext.current)),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = rememberAppHaptics()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 一卡通侧（余额、到账轮询、充值）复用 CampusCardViewModel：那套状态机已经在
    // 「我的 -> 校园卡」跑通，本页只做入口与展示。
    val campusViewModel: CampusCardViewModel = viewModel(
        factory = CampusCardViewModel.Factory(context.applicationContext),
    )
    val campusEnabled by campusViewModel.enabled.collectAsStateWithLifecycle()
    val balance by campusViewModel.balance.collectAsStateWithLifecycle()
    val balanceLoaded by campusViewModel.balanceLoaded.collectAsStateWithLifecycle()
    val balanceRefreshing by campusViewModel.balanceRefreshing.collectAsStateWithLifecycle()
    val arrival by campusViewModel.arrivalState.collectAsStateWithLifecycle()

    var showRechargeSheet by remember { mutableStateOf(false) }
    var showPowerRecharge by remember { mutableStateOf(false) }
    val powerRecharge by viewModel.powerRecharge.collectAsStateWithLifecycle()
    // 「去充值电子账户」联动：打开一卡通充值时预选电子账户（DESIGN 4.24）
    var campusRechargePreferElectric by rememberSaveable { mutableStateOf(false) }

    // 进页刷新一次：电费读数 + 一卡通流水增量同步 + 一卡通余额（开关关时各自短路）。
    // force = false = 走缓存/闸门（DESIGN 4.24「请求节流」）：切 Tab 来回不重复打平台，
    // 用户要看最新就下拉刷新（唯一入口），点钱包卡的那一栏仍各刷各的。
    LaunchedEffect(Unit) {
        viewModel.refreshAll(force = false)
        campusViewModel.refreshBalance(force = false)
    }

    // 从微信/浏览器返回的瞬间立即补检一轮（不等 5 秒周期），并在未立即到账时弹
    // 「正在确认充值结果」；进程被杀重启也能靠持久化的未确认充值记录恢复
    // （与「我的 -> 校园卡」同一份逻辑，DESIGN 4.19「充值」）。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, campusViewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) campusViewModel.onHostResume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 电费充值进入密码步骤：下单成功即取键盘挑战（passwordMap）。
    // **电子账户渠道专属**（2026-09-28）：农行渠道下单后走 BankPay 步，不该在这里
    // 多打一条 ACCOUNT 的 paystep=2。
    LaunchedEffect(powerRecharge.orderId, powerRecharge.step, powerRecharge.channel) {
        if (powerRecharge.channel == LifeViewModel.PowerRechargeUi.Channel.Account &&
            powerRecharge.step == LifeViewModel.PowerRechargeUi.Step.Amount &&
            powerRecharge.orderId != null
        ) {
            viewModel.loadPayChallenge()
        }
    }

    // 农行支付：从浏览器切回本页时自动查一次到账（静默——银行侧回调常有几十秒延迟，
    // 没查到只留一句中性提示，不弹错误）。口径同「我的 → 校园卡」的 onHostResume。
    val resumeTick = rememberResumeTick()
    LaunchedEffect(resumeTick) {
        if (resumeTick > 0 && powerRecharge.step == LifeViewModel.PowerRechargeUi.Step.BankPay) {
            viewModel.checkBankPaid(silent = true)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is LifeEvent.Notice -> snackbar.showSnackbar(AppNoticeVisuals(event.text, tone = event.tone))

                // 农行收银台：**App 内嵌页**打开（2026-09-28 用户拍板，不再甩给浏览器）。
                // 卡号 / 手机号 / 短信验证码 / 支付密码只在农行页面里输入，本 App 不读不存。
                is LifeEvent.OpenBankCashier -> {
                    runCatching { PowerBankPayActivity.start(context, event.url) }
                        .onFailure {
                            snackbar.showSnackbar(
                                AppNoticeVisuals("农行支付页打不开，请重试", tone = NoticeTone.Error),
                            )
                        }
                }
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
                windowInsets = pinnedStatusBars(),
                title = { Text(stringResource(R.string.tab_life)) },
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
        // 下拉刷新：与消费流水页 / 缴费账单页同一套 PullToRefreshBox。
        // 一次刷三样：电费读数 + 一卡通流水增量同步 + 一卡通余额，全部 force = true
        // （用户明确要看最新，不走缓存与 10 分钟闸门，DESIGN 4.24「请求节流」）。
        //
        // padding 收在刷新容器上：Scaffold 的内容从 (0,0) 铺满整屏、顶栏压在它上面，
        // 指示器挂在容器顶边时整条滑入轨迹都在顶栏后面，得拖过阈值一大截才露出半圈。
        //
        // 驻留兜底：M3 的 onRefresh 在松手那一下回调，而各路的 loading 标志要等协程跑起来
        // 才置 true，直接拿它当 isRefreshing，指示器会在回调瞬间被收回（体感「拉下去又弹
        // 回去」）。manualRefreshing 顶住回调瞬间，等标志归零再收；650ms 既是最短驻留，
        // 也是那段竞态的窗口。**不用 manualRefreshing || busy**：进页那次 refreshPower
        // 也会把 loading 置 true，那样一进页就转圈——指示器只认下拉这个动作。
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
                PayCodeBar(
                    enabled = campusEnabled,
                    onOpenPayCode = onOpenPayCode,
                    onOpenSettings = onOpenCampusSettings,
                )

                WalletCard(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    enabled = campusEnabled,
                    balanceFen = balance?.cardFen,
                    accountFen = balance?.accountFen,
                    balanceLoaded = balanceLoaded,
                    balanceRefreshing = balanceRefreshing,
                    arrivalWatching = arrival is CampusCardViewModel.ArrivalState.Watching,
                    balance = balance,
                    power = state.power,
                    onRefreshCampus = {
                        haptics.tap()
                        campusViewModel.refreshBalance()
                    },
                    onRefreshPower = {
                        haptics.tap()
                        viewModel.refreshPower()
                    },
                    onRechargeCampus = {
                        haptics.tap()
                        campusRechargePreferElectric = false
                        showRechargeSheet = true
                    },
                    onRechargePower = {
                        haptics.tap()
                        // 打开弹层即重置流程并清一遍未支付单（平台不自动清，堆积会让新下单 500）
                        viewModel.preparePowerRecharge()
                        showPowerRecharge = true
                    },
                    onOpenSettings = onOpenCampusSettings,
                )

                SectionHeader(title = "最近流水")
                FeedSectionsCard(
                    sections = state.feed,
                    campusLoading = state.campusFeedLoading,
                    powerLoading = state.powerFeedLoading,
                    credentialsEnabled = campusEnabled,
                    onOpenStatement = onOpenStatement,
                    onOpenPowerBill = onOpenPowerBill,
                )
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
            onSelectChannel = { channel -> viewModel.selectPowerChannel(channel) },
            onPlaceOrder = { yuan -> viewModel.placePowerOrder(yuan) },
            onLoadChallenge = { viewModel.loadPayChallenge() },
            onSubmitPassword = { cipher -> viewModel.submitPowerPassword(cipher) },
            onReopenCashier = { viewModel.reopenBankCashier() },
            onCheckPaid = { viewModel.checkBankPaid() },
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
                        // 与一卡通设置页同口径：统一走 startActivityOutsideApp，
                        // 拉起外部应用前先把本窗口的过渡覆盖换成静止（2026-09-26 修页面跳动）
                        launchExternal = { intent ->
                            runCatching { context.startActivityOutsideApp(intent) }.isSuccess
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

    // 「支付成功」：全屏付款码页检测到扣款会自行退出，结果经 PayCodeResultBus 落到本页
    // （取值即消费，与今日页同口径）。
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
        context.startActivityOutsideApp(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure {
        onError("打不开浏览器：${it.message ?: "未知错误"}", NoticeTone.Error)
    }
}

/**
 * 付款码条：一行，点了进全屏付款码页。
 *
 * 取代原来的「付款码卡」（2026-09-26）。那张卡在未取码时要占 QR 空框 1:1 + 条码位 + 三枚
 * 置灰按钮，按 392dp 宽的机器算是约 280dp 的空白，而它展示的信息是「还没有出示」。全屏码页
 * （PayCodeScreen）本来就有 QR、Code128、余额、消费流水入口与扣款自动退出，把出示这件事放在
 * 那边比在卡片里挤着看更清楚。**点击次数没有变多**：过去是「点占位条 -> 等取码 -> 在卡里看」，
 * 现在是「点这一条 -> 全屏看」，都是一次点击。
 *
 * 凭证未开启时整条变「去开启」，落点是「我的 -> 校园卡」。
 */
@Composable
private fun PayCodeBar(
    enabled: Boolean,
    onOpenPayCode: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    AppCard(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        onClick = if (enabled) onOpenPayCode else onOpenSettings,
        onClickLabel = if (enabled) "出示付款码" else "去开启一卡通",
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                HugeIcons.CreditCard,
                contentDescription = null,
                tint = if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    onSurface.copy(alpha = 0.45f)
                },
            )
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (enabled) "付款码" else "付款码未开启",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (enabled) "等同现金，也能刷宿舍门禁" else "开启后才能出示，凭证加密存本机",
                    style = MaterialTheme.typography.bodySmall,
                    color = onSurface.copy(alpha = 0.62f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            LinkLabel(if (enabled) "出示" else "去开启")
        }
    }
}

/** 主色文字 + 右向箭头，作为「会跳走」的出口标记（同 SettingsRow 的箭头口径）。 */
@Composable
private fun LinkLabel(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
        )
        Icon(
            HugeIcons.ArrowRight01,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * 我的钱包卡：一卡通与寝室电费并排两栏，中间一条竖分隔。
 *
 * 合并的理由（2026-09-26）：两张半宽卡各只有约 160dp 内宽，房间号与「约 ¥34.45 · 0.62 元/度」
 * 常被省略号吃掉；且为了两卡恒等高，外面还搭了一套 IntrinsicSize.Min 加双向 weight 的骨架。
 * 合成一张全宽卡后两栏各自的信息都放得下，等高仍由同一个 IntrinsicSize.Min 解决。
 *
 * 更新时间从底行挪到各栏标题行右侧（底行那套 24dp 钉高与说明图标随之取消）：一卡通与电费的
 * 取数时刻本来就不一样，摆在同一行会让人以为两个数字是同时取的。说明文字改由卡头那枚圆圈
 * 问号承载，两个来源的口径合成一个弹窗。
 */
@Composable
private fun WalletCard(
    modifier: Modifier,
    enabled: Boolean,
    balanceFen: Long?,
    accountFen: Long?,
    balanceLoaded: Boolean,
    balanceRefreshing: Boolean,
    arrivalWatching: Boolean,
    balance: PayCodeViewModel.BalanceSnapshot?,
    power: PowerCardState,
    onRefreshCampus: () -> Unit,
    onRefreshPower: () -> Unit,
    onRechargeCampus: () -> Unit,
    onRechargePower: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    var showNote by remember { mutableStateOf(false) }

    // 卡头状态只承载「正在发生什么」；两个数字的新鲜度各自写在栏内
    val statusText = when {
        !enabled -> null
        arrivalWatching -> "有充值正在确认到账…"
        balanceRefreshing -> "正在查询余额…"
        else -> null
    }

    val campusValue = when {
        !enabled || balanceFen == null -> "—"
        else -> "¥%.2f".format(balanceFen / 100.0)
    }
    val campusSub = when {
        !enabled -> "未开启凭证"
        balanceFen == null -> if (balanceLoaded) "余额暂不可用" else "读取中…"
        else -> "电子账户 ¥%.2f".format((accountFen ?: 0L) / 100.0)
    }

    val meter = power.snapshot?.meter
    val pricePerUnit = power.snapshot?.feeItem?.priceYuan
    val powerValue = when {
        power.noCredentials -> "—"
        meter?.remain != null -> "%.2f 度".format(meter.remain)
        else -> "—"
    }
    val powerSub = when {
        power.noCredentials -> "未开启凭证"
        meter?.remain != null && power.error != null -> "读取失败，显示上次读数"
        meter?.remain != null -> {
            // 「元」的换算只有一处口径（domain/BalanceAlert.remainingYuan，余额提醒共用）：
            // 电量或单价缺失就只报电量字段，不折算金额
            val remainYuan = BalanceAlert.remainingYuan(meter.remain, pricePerUnit)
            if (remainYuan != null && pricePerUnit != null) {
                "≈ ¥%.2f · %.2f 元/度".format(remainYuan, pricePerUnit)
            } else {
                meter.remainField.orEmpty()
            }
        }
        power.loading -> "读取中…"
        power.error != null -> power.error
        else -> "点一下刷新"
    }

    AppCard(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "我的钱包",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (statusText != null) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = onSurface.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (enabled && !arrivalWatching) {
                InfoDot(onClick = { showNote = true })
            }
        }
        Spacer(Modifier.height(6.dp))
        // IntrinsicSize.Min：两栏取较高者的内容高，矮的一栏用内部 weight 补齐并把「充值」
        // 按钮压到底——两个按钮落在同一水平线
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
        ) {
            WalletPane(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                icon = HugeIcons.CreditCard,
                label = "一卡通",
                timeText = if (enabled && balance != null) formatTime(balance.fetchedAtMs) else null,
                value = campusValue,
                valueMuted = !enabled || balanceFen == null,
                sub = campusSub,
                subIsError = false,
                actionLabel = if (enabled) "充值" else "去开启",
                onAction = if (enabled) onRechargeCampus else onOpenSettings,
                onRefresh = if (enabled) onRefreshCampus else null,
                onClickLabel = "刷新一卡通余额",
            )
            Spacer(
                Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .padding(vertical = 6.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
            WalletPane(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                icon = HugeIcons.Bolt,
                label = "寝室电费",
                room = meter?.room?.room?.takeIf { it.isNotBlank() },
                timeText = if (enabled && meter != null) formatTime(meter.fetchedAtMs) else null,
                value = powerValue,
                valueMuted = power.noCredentials || meter == null,
                sub = powerSub,
                subIsError = power.error != null,
                actionLabel = if (enabled) "充值" else "去开启",
                onAction = if (enabled) onRechargePower else onOpenSettings,
                onRefresh = if (enabled) onRefreshPower else null,
                onClickLabel = "刷新寝室电费读数",
            )
        }
    }

    if (showNote) {
        AlertDialog(
            onDismissRequest = { showNote = false },
            title = { Text("钱包说明") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(BALANCE_NOTE)
                    Text(ELECTRICITY_NOTE)
                }
            },
            confirmButton = {
                TextButton(onClick = { showNote = false }) { Text("知道了") }
            },
        )
    }
}

/**
 * 钱包卡的一栏：标题行（图标 + 名称 + 房间 + 取数时刻）/ 数值 / 副行 / 「充值」。
 *
 * 整栏可点刷新（onRefresh 非 null 时），但「充值」按钮自己吃掉点击，不会连带触发刷新
 * （父级 clickable 收不到已被子级消费的事件）。
 */
@Composable
private fun WalletPane(
    modifier: Modifier,
    icon: ImageVector,
    label: String,
    room: String? = null,
    timeText: String?,
    value: String,
    valueMuted: Boolean,
    sub: String,
    subIsError: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
    onRefresh: (() -> Unit)?,
    onClickLabel: String?,
) {
    val haptics = rememberAppHaptics()
    val onSurface = MaterialTheme.colorScheme.onSurface
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .then(
                if (onRefresh == null) {
                    Modifier
                } else {
                    Modifier.clickable(onClickLabel = onClickLabel) {
                        haptics.tap()
                        onRefresh()
                    }
                },
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = onSurface.copy(alpha = 0.6f),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = onSurface.copy(alpha = 0.7f),
                maxLines = 1,
            )
            if (room != null) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = room,
                    style = MaterialTheme.typography.labelSmall,
                    color = onSurface.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (timeText != null) {
                Text(
                    text = timeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = onSurface.copy(alpha = 0.5f),
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (valueMuted) onSurface.copy(alpha = 0.5f) else onSurface,
            maxLines = 1,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = sub,
            style = MaterialTheme.typography.bodySmall,
            color = if (subIsError) MaterialTheme.colorScheme.error else onSurface.copy(alpha = 0.62f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // 弹性空隙：两栏内容不等高时多出来的高度堆在副行与按钮之间，
        // 两个「充值」按钮因此落在同一水平线上
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = {
                haptics.tap()
                onAction()
            },
            modifier = Modifier.height(32.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
        ) {
            Text(actionLabel, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * 卡头那枚圆圈问号说明图标：24dp 触摸面，点击弹说明。
 *
 * 用圆圈问号而不是感叹号（2026-09-24 用户反馈「看着像账号有风险」）：这里补的是中性说明，
 * 不是告警；感叹号只留给真的出错（消息条 NoticeTone.Warning 用它）。
 */
@Composable
private fun InfoDot(onClick: () -> Unit) {
    val haptics = rememberAppHaptics()
    Row(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .clickable(onClickLabel = "查看说明") {
                haptics.tap()
                onClick()
            },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            HugeIcons.InformationCircle,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
        )
    }
}

/**
 * 最近流水：按来源分两段，每段标题行右侧是自己那一页的出口（DESIGN 3.13，2026-09-26）。
 *
 * 分段的理由：两个来源的数据形态与去向都不一样（一卡通在本地库、落消费流水页；电费在缴费
 * 平台、落缴费账单页）。混在一条列表里挂两个出口，用户点之前看不出会跳去哪。
 */
@Composable
private fun FeedSectionsCard(
    sections: LifeFeedSections,
    /** 一卡通段还在等本地库首帧：该段渲染骨架行。 */
    campusLoading: Boolean,
    /** 电费段还在等平台流水链路跑完一次：该段渲染骨架行。 */
    powerLoading: Boolean,
    credentialsEnabled: Boolean,
    onOpenStatement: () -> Unit,
    onOpenPowerBill: () -> Unit,
) {
    AppCard(
        modifier = Modifier.padding(horizontal = 16.dp),
        contentPadding = PaddingValues(0.dp),
    ) {
        FeedSection(
            title = "一卡通",
            linkLabel = "全部消费流水",
            onLink = onOpenStatement,
            items = sections.campusCard,
            loading = campusLoading,
            emptyText = if (credentialsEnabled) "还没有消费记录" else "未开启凭证",
        )
        AppCardDivider()
        FeedSection(
            title = "寝室电费",
            linkLabel = "缴费账单 · 用电统计",
            onLink = onOpenPowerBill,
            items = sections.power,
            loading = powerLoading,
            emptyText = if (credentialsEnabled) "还没有电费流水" else "未开启凭证",
        )
    }
}

/**
 * 流水卡的一段：带底色的段标题（右侧是出口）+ 至多两条记录。
 *
 * [loading] 时渲染两条骨架行（而不是空态文案）：真实数据多数时候正好两条，高度对上；
 * 只有一条时卡片会缩一次，这是「不该有的空态文案」与「绝对零位移」之间取的折中——
 * 预知条数才能做到绝对不动，而那要先拿到数据。
 */
@Composable
private fun FeedSection(
    title: String,
    linkLabel: String,
    onLink: () -> Unit,
    items: List<LifeFeedItem>,
    loading: Boolean,
    emptyText: String,
) {
    val haptics = rememberAppHaptics()
    val onSurface = MaterialTheme.colorScheme.onSurface
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(start = 14.dp, end = 8.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = onSurface.copy(alpha = 0.75f),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClickLabel = linkLabel) {
                        haptics.tap()
                        onLink()
                    }
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LinkLabel(linkLabel)
            }
        }
        when {
            loading -> repeat(2) { FeedRowSkeleton() }
            items.isEmpty() -> Text(
                text = emptyText,
                style = MaterialTheme.typography.bodySmall,
                color = onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            )
            else -> items.forEach { FeedRow(it) }
        }
    }
}

/**
 * 流水行的骨架：与 [FeedRow] 同内边距、同图标尺寸、同两行行高（行高取自样式，
 * 系统字体调大时两边一起长），数据到达时零位移。
 *
 * 文字条**不占满行盒**（2026-09-26 用户反馈「骨架矩形都粘在一起了」）：实心条按整行行高
 * 画，上下两条贴死成一块；一行文字的字形本来就只占行盒中间约六成，条照 [SKELETON_LINE_RATIO]
 * 画、在各自行盒里垂直居中，缝隙与真实文字一致，行盒总高不变、零位移照旧。
 */
@Composable
private fun FeedRowSkeleton() {
    val titleLine = lineHeightDp(MaterialTheme.typography.bodyMedium)
    val subLine = lineHeightDp(MaterialTheme.typography.bodySmall)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SkeletonBox(Modifier.size(18.dp), RoundedCornerShape(4.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            SkeletonLine(width = 132.dp, line = titleLine)
            SkeletonLine(width = 96.dp, line = subLine)
        }
        Spacer(Modifier.width(8.dp))
        SkeletonLine(width = 52.dp, line = titleLine)
    }
}

/** 行内一根骨架条：包在整行行高的盒子里垂直居中，条高 = 行高 × [SKELETON_LINE_RATIO]。 */
@Composable
private fun SkeletonLine(width: Dp, line: Dp) {
    Box(
        modifier = Modifier.height(line),
        contentAlignment = Alignment.CenterStart,
    ) {
        SkeletonBox(
            Modifier.width(width).height(line * SKELETON_LINE_RATIO),
            RoundedCornerShape(4.dp),
        )
    }
}

/** 骨架条占行盒高的比例：一行文字的字形大约只画行盒中间的六成。 */
private const val SKELETON_LINE_RATIO = 0.62f

/** 一条流水：来源图标 + 标题与副标题 + 金额（入账带 + 走主色）。 */
@Composable
private fun FeedRow(item: LifeFeedItem) {
    val onSurface = MaterialTheme.colorScheme.onSurface
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
                onSurface.copy(alpha = 0.6f)
            },
        )
        Spacer(Modifier.width(10.dp))
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
                color = onSurface.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = (if (item.income) "+" else "-") + "%.2f".format(kotlin.math.abs(item.amountFen) / 100.0),
            style = MaterialTheme.typography.bodyMedium,
            color = if (item.income) MaterialTheme.colorScheme.primary else onSurface,
        )
    }
}

/** 一卡通余额说明（钱包卡说明弹窗的前一段，2026-09-24 口径）。 */
private const val BALANCE_NOTE =
    "一卡通余额与流水都来自一卡通平台，服务端落账有延迟，刚发生的消费或充值可能暂时不计入。"

/** 电费说明（钱包卡说明弹窗的后一段，2026-09-24 用户给的原话；只有这一处口径）。 */
private const val ELECTRICITY_NOTE = "受服务器影响，实际费用可能有延迟，电费不足时请及时缴费"

private val TIME_FORMAT = java.time.format.DateTimeFormatter.ofPattern("HH:mm")

private fun formatTime(epochMs: Long): String = runCatching {
    java.time.Instant.ofEpochMilli(epochMs)
        .atZone(java.time.ZoneId.systemDefault())
        .format(TIME_FORMAT)
}.getOrDefault("—")
