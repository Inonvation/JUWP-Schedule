package edu.jxslu.schedule.ui.life

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.style.TextAlign
import me.rerere.hugeicons.stroke.Edit02
import me.rerere.hugeicons.stroke.Link01
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
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
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.R
import edu.jxslu.schedule.PowerBankPayActivity
import edu.jxslu.schedule.data.ykt.rechargeTargetCard
import edu.jxslu.schedule.domain.BalanceAlert
import edu.jxslu.schedule.domain.LifeFeedItem
import edu.jxslu.schedule.domain.LifeFeedKind
import edu.jxslu.schedule.domain.LifeFeedSections
import edu.jxslu.schedule.domain.RechargeDisclaimer
import edu.jxslu.schedule.startActivityOutsideApp
import edu.jxslu.schedule.ui.campus.CampusArrivalDialog
import edu.jxslu.schedule.ui.campus.CampusCardViewModel
import edu.jxslu.schedule.ui.campus.CampusPendingConfirmDialog
import edu.jxslu.schedule.ui.campus.RechargeSheet
import edu.jxslu.schedule.ui.common.AppCard
import edu.jxslu.schedule.ui.common.AppCardDivider
import edu.jxslu.schedule.ui.common.AppNoticeVisuals
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.LocalBottomBarClearance
import edu.jxslu.schedule.ui.common.NoticeTone
import edu.jxslu.schedule.ui.common.RechargeDisclaimerDialog
import edu.jxslu.schedule.ui.common.rememberResumeTick
import edu.jxslu.schedule.ui.common.QuickEntryCard
import edu.jxslu.schedule.ui.common.pinnedStatusBars
import edu.jxslu.schedule.ui.common.SectionHeader
import edu.jxslu.schedule.ui.common.SkeletonBox
import edu.jxslu.schedule.ui.common.lineHeightDp
import edu.jxslu.schedule.ui.common.postNotice
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import edu.jxslu.schedule.ui.common.showNotice
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Bolt
import me.rerere.hugeicons.stroke.CreditCard
import me.rerere.hugeicons.stroke.Receipt
import me.rerere.hugeicons.stroke.Settings01
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.ui.graphics.Brush
import com.google.zxing.BarcodeFormat
import com.google.zxing.client.android.Intents
import edu.jxslu.schedule.domain.QuickScanTarget
import edu.jxslu.schedule.domain.ShortcutItem
import edu.jxslu.schedule.domain.ShortcutSettings
import edu.jxslu.schedule.domain.quickScanTargetOf
import edu.jxslu.schedule.ui.theme.PrimaryLight
import edu.jxslu.schedule.ui.common.ShortcutIcon
import edu.jxslu.schedule.ui.common.ShortcutLauncher
import edu.jxslu.schedule.ui.common.ShortcutPinner
import edu.jxslu.schedule.ui.ebike.EbikeScanActivity
import edu.jxslu.schedule.ui.ujing.UjingOrderStatusLine
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Analytics01
import me.rerere.hugeicons.stroke.ChevronRight
import me.rerere.hugeicons.stroke.HeartHandshake
import me.rerere.hugeicons.stroke.InformationCircle
import me.rerere.hugeicons.stroke.Package01
import me.rerere.hugeicons.stroke.PackageSearch01
import me.rerere.hugeicons.stroke.QrCodeScan
import me.rerere.hugeicons.stroke.WashingMachine

/** 充值入口的种类（免责声明放行后决定打开哪个弹层）。 */
private enum class RechargeKind { Campus, Power }

/** U净 弹窗打开时对订单的节流窗口：10 秒内不重复请求（弹窗开关频繁时不必每次都打）。 */
private const val UJING_ORDER_REFRESH_TTL_MILLIS = 10_000L

/**
 * 生活页（DESIGN 3.13）：钱包卡（一卡通余额主视觉 + 充值 / 付款码 / 账单）→ 校园服务
 * （取快递 / 问渠志愿 / 成绩查询）→ U净 整行卡 → 最近流水与账单入口。
 *
 * 三条口径：
 * 1. **码不预取**：付款码只在钱包卡上留一枚按钮，点进去才是 PayCodeScreen，取码发生在
 *    那一页的进页 load()。本页不再内嵌码位，也不再单独占一条付款码卡（2026-10-13）。
 * 2. **失败不编数**：电费读失败时保留上次读数并标注「读取失败，显示上次读数」，不报零。
 * 3. **一处凭证**：电费与一卡通共用 YktCredentialStore，关掉一卡通时两栏一起回空态。
 *
 * 充值 / 取快递 / U净 三个入口都是**从下弹出的底部弹窗**（2026-10-13）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LifeScreen(
    /** 消费流水页（一卡通消费明细 + 月度统计）；钱包卡「账单」按钮的落点 */
    onOpenStatement: () -> Unit = {},
    /** 缴费账单页（寝室电费用电统计 + 充值账单，进页默认用电统计） */
    onOpenPowerBill: () -> Unit = {},
    /** 全屏付款码页（钱包卡「付款码」按钮的落点） */
    onOpenPayCode: () -> Unit = {},
    /** 一卡通设置页（凭证未开启时的「去开启」） */
    onOpenCampusSettings: () -> Unit = {},
    /** 校园服务：问渠 · 志愿服务页 */
    onOpenWenqu: () -> Unit = {},
    /** 校园服务：成绩查询页 */
    onOpenScores: () -> Unit = {},
    /** U净 · 洗衣房页（U净 弹窗的副行落点） */
    onOpenUjing: () -> Unit = {},
    /** U净 扫码：扫到的机身码原文交给 U净 页识别（走一次性令牌） */
    onUjingScan: (String) -> Unit = {},
    /** 快捷方式设置页（取快递弹窗「新增」/ 长按编辑的落点；null=不定位） */
    onOpenShortcuts: (String?) -> Unit = {},
    /** 取快递弹窗里的「菜鸟批量查」（2026-10-13 从百宝箱移入）：多单号一次导入取件码 */
    onOpenCainiao: () -> Unit = {},
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
    // 「选择寝室」弹层（DESIGN §4.24「未设置寝室」）：电费栏未设置寝室时的 CTA 与
    // 已设置后的改绑（校园卡设置页那边同款）都走它；打开前 start() 重置流程
    var showRoomPicker by remember { mutableStateOf(false) }
    val roomPickerViewModel: PowerRoomPickerViewModel = viewModel(
        factory = PowerRoomPickerViewModel.factory(context.applicationContext),
    )
    fun openRoomSetup() {
        roomPickerViewModel.start()
        showRoomPicker = true
    }
    val powerRecharge by viewModel.powerRecharge.collectAsStateWithLifecycle()
    // 「去充值电子账户」联动：打开一卡通充值时预选电子账户（DESIGN 4.24）
    var campusRechargePreferElectric by rememberSaveable { mutableStateOf(false) }

    // 充值免责声明（2026-09-29 用户要求）：电费与一卡通**每个充值入口**打开弹层前都要过
    // 一道提醒，勾「一周内不再提醒」静默 7 天（窗口口径 domain/RechargeDisclaimer，
    // 两种充值共用）。disclaimerFor 非空 = 弹窗正开着；点「继续充值」才放行进充值流程。
    // 首次（从未确认过）弹出锁 5 秒才能关，之后立即可关——「确认过」= 继续或取消都算。
    val displayPrefs = remember { Graph.displayPrefs(context) }
    val disclaimerSuppressUntil by displayPrefs.rechargeDisclaimerSuppressUntil
        .collectAsStateWithLifecycle(initialValue = 0L)
    val disclaimerSeenAt by displayPrefs.rechargeDisclaimerSeenAt
        .collectAsStateWithLifecycle(initialValue = 0L)
    var disclaimerFor by remember { mutableStateOf<RechargeKind?>(null) }

    // ── 底部弹窗与校园服务（2026-10-13）──
    // 充值选择：钱包卡「充值」→ 底部弹窗（一卡通 / 电费 二选一），选完再进各自的充值流程
    var showRechargeChoice by remember { mutableStateOf(false) }
    // 取快递：生活页「取快递」→ 底部弹窗（「取快递」预设 + 「其他」自定义，分割线区分）
    var showShortcutSheet by remember { mutableStateOf(false) }
    // U净：整行卡 → 底部弹窗（扫码按钮 + 进页副行）
    var showUjingSheet by remember { mutableStateOf(false) }
    // U净 在案订单：弹窗打开时对一次服务端（快照只在 U净 页可见时轮询，不然是下单时的旧状态）；
    // 节流窗口内不重复，避免频繁开关弹窗反复打请求
    val ujingRepo = remember(context) { Graph.ujing(context) }
    var ujingOrderRefreshedAt by remember { mutableStateOf(0L) }
    val refreshUjingOrderIfStale: () -> Unit = {
        val id = ujingRepo.currentOrder()?.orderId
        val now = System.currentTimeMillis()
        if (id != null && ujingRepo.loggedIn.value &&
            now - ujingOrderRefreshedAt > UJING_ORDER_REFRESH_TTL_MILLIS
        ) {
            ujingOrderRefreshedAt = now
            scope.launch { runCatching { ujingRepo.refreshOrder(id) } }
        }
    }
    // 快捷方式（预设 + 自定义）：直接订阅仓库那一份，与「取快递」弹窗的分组口径同源
    val shortcutSettings by remember(context) { Graph.repository(context).shortcutSettings }
        .collectAsStateWithLifecycle(initialValue = ShortcutSettings())

    fun reallyOpenRecharge(kind: RechargeKind) {
        when (kind) {
            // 打开弹层即重置流程并清一遍未支付单（平台不自动清，堆积会让新下单 500）
            RechargeKind.Power -> {
                // 未设置寝室（平台侧判据，DESIGN §4.24）：没有房间可读，充值无从谈起——
                // 直接引到选择器，别让用户走到「下单读不到电表」那一步
                if (state.power.roomNotBound) {
                    openRoomSetup()
                } else {
                    viewModel.preparePowerRecharge()
                    showPowerRecharge = true
                }
            }

            RechargeKind.Campus -> showRechargeSheet = true
        }
    }

    fun openRecharge(kind: RechargeKind) {
        if (RechargeDisclaimer.isSuppressed(disclaimerSuppressUntil, System.currentTimeMillis())) {
            reallyOpenRecharge(kind)
        } else {
            disclaimerFor = kind
        }
    }

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
                // 事件流用 postNotice 单开协程发：收集协程不卡在「等这条提示收场」上，
                // 事件成串来时新的提示才能当场顶掉旧的（见 AppNotice.kt）
                is LifeEvent.Notice -> scope.postNotice(snackbar, event.text, event.tone)

                // 农行收银台：**App 内嵌页**打开（2026-09-28 用户拍板，不再甩给浏览器）。
                // 卡号 / 手机号 / 短信验证码 / 支付密码只在农行页面里输入，本 App 不读不存。
                is LifeEvent.OpenBankCashier -> {
                    runCatching { PowerBankPayActivity.start(context, event.url) }
                        .onFailure {
                            scope.postNotice(snackbar, "农行支付页打不开，请重试", NoticeTone.Error)
                        }
                }
            }
        }
    }

    val showNotice: (String, NoticeTone) -> Unit = { message, tone ->
        scope.launch { snackbar.showNotice(AppNoticeVisuals(message, tone = tone)) }
    }

    // U净 弹窗的扫码按钮：取景走原文模式（与今日页「+ → 扫一扫」同一套判据），
    // 洗衣机机身码原文交给 U净 页识别；车身码 / 别家码就地提示，不跳页。
    val ujingScanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val raw = result.data?.getStringExtra(Intents.Scan.RESULT)
        when (val target = quickScanTargetOf(raw)) {
            null -> Unit
            is QuickScanTarget.Washer -> {
                showUjingSheet = false
                onUjingScan(target.raw)
            }
            is QuickScanTarget.Ebike -> showNotice("这是快趣车身码，不是洗衣机机身码", NoticeTone.Warning)
            is QuickScanTarget.Foreign -> showNotice(
                "这是${target.label}的码，不是洗衣机机身码",
                NoticeTone.Warning,
            )
        }
    }
    val startUjingScan: () -> Unit = {
        haptics.tap()
        ujingScanLauncher.launch(
            Intent(context, EbikeScanActivity::class.java).apply {
                putExtra(Intents.Scan.PROMPT_MESSAGE, "对准洗衣机机身上的二维码")
                putExtra(EbikeScanActivity.EXTRA_RESULT_MODE, EbikeScanActivity.MODE_RAW)
                // 机身码是二维码：限定格式，别让商品一维码也当码送出去。
                // 键走 Intents.Scan.FORMATS 的**逗号分隔字符串**（DecodeFormatManager 只读 StringExtra）
                putExtra(Intents.Scan.FORMATS, BarcodeFormat.QR_CODE.name)
            },
        )
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
        // 关掉触底回弹（stretch overscroll，2026-10-13 用户报「上拉到底再下拉会弹动卡顿」，
        // 与当时天气页同一个 bug）：本页是一条 `Column + verticalScroll` 的长内容（非懒加载），
        // 拖到头之后系统把整块做拉伸（RenderEffect 图层）、那一帧要重栅格化，就是这个卡顿；
        // 今日页/问渠那种 LazyColumn 的图层只有一屏高，所以没这问题。展示型页面的边到边回弹
        // 没有信息量，直接关掉。`LocalOverscrollConfiguration provides null` 是这套 API 的正规
        // 开关（glow 与 stretch 都归它管）；**别删**——删了就回到「触底卡一下」。
        CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
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
                    WalletHeroCard(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        enabled = campusEnabled,
                        balanceFen = balance?.cardFen,
                        accountFen = balance?.accountFen,
                        balanceFetchedAtMs = balance?.fetchedAtMs,
                        balanceLoaded = balanceLoaded,
                        balanceRefreshing = balanceRefreshing,
                        arrivalWatching = arrival is CampusCardViewModel.ArrivalState.Watching,
                        power = state.power,
                        onRecharge = {
                            haptics.tap()
                            // 凭证未开启（一卡通 / 电费共用一份）时「去开启」，否则弹选择框
                            if (campusEnabled) showRechargeChoice = true else onOpenCampusSettings()
                        },
                        onPayCode = {
                            haptics.tap()
                            if (campusEnabled) onOpenPayCode() else onOpenCampusSettings()
                        },
                        onStatement = {
                            haptics.tap()
                            onOpenStatement()
                        },
                    )

                    // 区块标题与卡片都收一档间距（2026-10-13 用户反馈「各部分之间太宽松」）
                    SectionHeader(title = "校园服务", topPadding = 12.dp, bottomPadding = 6.dp)
                    CampusServicesCard(
                        onPackagePickup = {
                            haptics.tap()
                            showShortcutSheet = true
                        },
                        onWenqu = {
                            haptics.tap()
                            onOpenWenqu()
                        },
                        onScores = {
                            haptics.tap()
                            onOpenScores()
                        },
                    )

                    UjingEntryCard(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        onClick = {
                            haptics.tap()
                            refreshUjingOrderIfStale()
                            showUjingSheet = true
                        },
                    )

                    SectionHeader(title = "最近流水", topPadding = 12.dp, bottomPadding = 6.dp)
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
                openRecharge(RechargeKind.Campus)
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
            // 二次确认弹窗里加粗展示的目标卡号：与下单链路同一份选取口径
            // （`rechargeTargetCard` = 第一张非挂失卡）
            cardAccount = balance?.cards?.rechargeTargetCard()?.account,
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

    if (showRoomPicker) {
        PowerRoomPickerSheet(
            viewModel = roomPickerViewModel,
            onDismiss = { showRoomPicker = false },
            onBound = { room ->
                showRoomPicker = false
                // 绑定后立即按新房间刷新卡面（force：平台刚写完，缓存里那份还是「没房间」）
                viewModel.refreshPower(force = true)
                scope.launch {
                    snackbar.showNotice(
                        AppNoticeVisuals("已设置寝室：$room", tone = NoticeTone.Success),
                    )
                }
            },
        )
    }

    // 充值选择弹窗（从下弹出）：一排两个——一卡通 / 电费；选完再过免责声明闸进各自流程
    if (showRechargeChoice) {
        RechargeChoiceSheet(
            onDismiss = { showRechargeChoice = false },
            onPickCampus = {
                showRechargeChoice = false
                campusRechargePreferElectric = false
                openRecharge(RechargeKind.Campus)
            },
            onPickPower = {
                showRechargeChoice = false
                openRecharge(RechargeKind.Power)
            },
        )
    }

    // 取快递弹窗：预设归「取快递」段、自定义归「其他」段，分割线区分；末尾「新增」进设置页
    if (showShortcutSheet) {
        ShortcutSheet(
            settings = shortcutSettings,
            onDismiss = { showShortcutSheet = false },
            onLaunchItem = { item ->
                showShortcutSheet = false
                ShortcutLauncher.launch(context, item)?.let { message ->
                    showNotice(message, NoticeTone.Warning)
                }
            },
            onPinItem = { item ->
                ShortcutPinner.pin(context, item)?.let { message ->
                    showNotice(message, NoticeTone.Warning)
                }
            },
            onEdit = { itemId ->
                showShortcutSheet = false
                onOpenShortcuts(itemId)
            },
            onAdd = {
                showShortcutSheet = false
                onOpenShortcuts(null)
            },
            onOpenCainiao = {
                showShortcutSheet = false
                onOpenCainiao()
            },
        )
    }

    // U净 弹窗：扫码按钮 + 进页副行
    if (showUjingSheet) {
        UjingEntrySheet(
            onDismiss = { showUjingSheet = false },
            onScan = startUjingScan,
            onOpenPage = {
                showUjingSheet = false
                onOpenUjing()
            },
        )
    }

    // 充值免责声明弹窗：点「继续充值」落「已确认」（勾选则再落一周静默）后放行，
    // 取消只落「已确认」不静默——下次充值还会再弹，但立即可关。
    disclaimerFor?.let { kind ->
        RechargeDisclaimerDialog(
            closableAfterMs = RechargeDisclaimer.closeLockMs(disclaimerSeenAt, System.currentTimeMillis()),
            onContinue = { suppressWeek ->
                disclaimerFor = null
                if (suppressWeek) {
                    scope.launch {
                        displayPrefs.setRechargeDisclaimerSuppressUntil(
                            RechargeDisclaimer.suppressUntil(System.currentTimeMillis()),
                        )
                    }
                }
                scope.launch { displayPrefs.markRechargeDisclaimerSeen() }
                reallyOpenRecharge(kind)
            },
            onDismiss = {
                disclaimerFor = null
                scope.launch { displayPrefs.markRechargeDisclaimerSeen() }
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

    // 「支付成功」弹窗不在这里自持（2026-10-07 起）：宿主 `CampusPayResultDialogHost` 挂在
    // MainActivity 根层，四个 Tab 共用一份——此前只挂在生活页，从今日页「+ → 付款码」出码
    // 付款后没人消费结果，弹窗会一直挂着、直到下次打开生活页才迟到弹出。
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
 * 我的钱包卡（2026-10-13 改版）：一卡通余额做主视觉的 hero 卡。
 *
 * 版式（参考图二）：一卡通余额大字 → 「电子账户 / 寝室电费」副行 → 充值 · 付款码 · 账单
 * 三枚胶囊按钮。取代原来「一卡通 / 寝室电费」两栏等宽卡（两栏各约 160dp 内宽，房间号与
 * 金额常被省略号吃掉；且为了两栏等高还搭了一套 IntrinsicSize.Min 骨架）。
 *
 * 取数失败仍保留上次读数（口径不变）；凭证未开启时整卡转「去开启」。
 */
@Composable
private fun WalletHeroCard(
    modifier: Modifier,
    enabled: Boolean,
    balanceFen: Long?,
    accountFen: Long?,
    /** 一卡通余额的取数时刻（epoch 毫秒，0 / null = 没有），显示在卡片右上角。 */
    balanceFetchedAtMs: Long?,
    balanceLoaded: Boolean,
    balanceRefreshing: Boolean,
    arrivalWatching: Boolean,
    power: PowerCardState,
    onRecharge: () -> Unit,
    onPayCode: () -> Unit,
    onStatement: () -> Unit,
) {
    // hero 卡固定走品牌青、不吃动态取色（2026-10-13 用户口径）：钱包是主视觉，方案稿定的
    // 就是这套青；跟随壁纸变蓝 / 变紫会和整页其它卡片脱节
    val heroTop = PrimaryLight
    val heroBottom = lerp(PrimaryLight, Color.Black, 0.22f)
    val onHero = Color.White
    val haptics = rememberAppHaptics()
    var showNote by remember { mutableStateOf(false) }

    val statusText = when {
        !enabled -> null
        arrivalWatching -> "有充值正在确认到账…"
        balanceRefreshing -> "正在查询余额…"
        else -> null
    }
    // 右上角：正常显示取数时刻（2026-10-13 用户口径），有正在进行的动作时让位给状态文案
    val metaText = statusText ?: if (enabled) {
        walletTimeText(balanceFetchedAtMs)?.let { "更新于 $it" }
    } else {
        null
    }
    val balanceText = when {
        !enabled || balanceFen == null -> "—"
        else -> "¥%.2f".format(balanceFen / 100.0)
    }
    val accountText = "电子账户 " + if (enabled && accountFen != null) {
        "¥%.2f".format(accountFen / 100.0)
    } else {
        "—"
    }

    val meter = power.snapshot?.meter
    val pricePerUnit = power.snapshot?.feeItem?.priceYuan
    val powerText = when {
        power.noCredentials || power.roomNotBound -> "寝室电费 —"
        meter?.remain != null -> {
            // 「元」的换算只有一处口径（domain/BalanceAlert.remainingYuan，余额提醒共用）
            val remainYuan = BalanceAlert.remainingYuan(meter.remain, pricePerUnit)
            if (remainYuan != null && pricePerUnit != null) {
                "寝室电费 %.2f 度 · ≈¥%.2f".format(meter.remain, remainYuan)
            } else {
                "寝室电费 %.2f 度".format(meter.remain)
            }
        }
        power.loading -> "寝室电费 读取中…"
        power.error != null -> "寝室电费 读取失败"
        else -> "寝室电费 —"
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(heroTop, heroBottom))),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (enabled) "一卡通余额" else "一卡通未开启",
                    style = MaterialTheme.typography.bodySmall,
                    color = onHero.copy(alpha = 0.8f),
                )
                Spacer(Modifier.weight(1f))
                if (metaText != null) {
                    Text(
                        text = metaText,
                        style = MaterialTheme.typography.labelSmall,
                        color = onHero.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 「钱包说明」入口（2026-10-13 补回：hero 化时误删，2026-09-24 用户要求的口径）
                if (enabled) {
                    Spacer(Modifier.width(6.dp))
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
                            tint = onHero.copy(alpha = 0.75f),
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = balanceText,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = onHero,
                maxLines = 1,
            )
            Spacer(Modifier.height(9.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                Text(
                    text = accountText,
                    style = MaterialTheme.typography.bodySmall,
                    color = onHero.copy(alpha = 0.92f),
                    maxLines = 1,
                )
                Text(
                    text = powerText,
                    style = MaterialTheme.typography.bodySmall,
                    color = onHero.copy(alpha = 0.92f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(15.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HeroPillButton(if (enabled) "充值" else "去开启", HugeIcons.Add01, Modifier.weight(1f), onRecharge)
                HeroPillButton("付款码", HugeIcons.QrCodeScan, Modifier.weight(1f), onPayCode)
                HeroPillButton("账单", HugeIcons.Receipt, Modifier.weight(1f), onStatement)
            }
        }
    }

    // 钱包说明（2026-09-24 用户要求；2026-10-13 hero 化时误删，同日补回）
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

/** 钱包 hero 卡上的胶囊按钮：半透明白底 + 图标 + 文字。 */
@Composable
private fun HeroPillButton(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val onHero = Color.White
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(onHero.copy(alpha = 0.17f))
            .clickable(onClickLabel = label) { onClick() }
            .padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = onHero, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = onHero,
            maxLines = 1,
        )
    }
}

/**
 * 校园服务卡：三格入口（取快递 / 问渠志愿 / 成绩查询）。
 * U净 不在这格里——它单独占一整行（[UjingEntryCard]）。
 */
@Composable
private fun CampusServicesCard(
    onPackagePickup: () -> Unit,
    onWenqu: () -> Unit,
    onScores: () -> Unit,
) {
    AppCard(
        modifier = Modifier.padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ServiceTile("取快递", HugeIcons.Package01, onPackagePickup)
            ServiceTile("问渠志愿", HugeIcons.HeartHandshake, onWenqu)
            ServiceTile("成绩查询", HugeIcons.Analytics01, onScores)
        }
    }
}

/** 校园服务的一格：圆角图标块 + 单行名称。 */
@Composable
private fun ServiceTile(label: String, icon: ImageVector, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClickLabel = label) { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(primary.copy(alpha = 0.09f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = primary, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(7.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * U净 · 洗衣房 整行卡（2026-10-13）：点击弹底部弹窗（扫码按钮 + 进页副行）。
 *
 * 复用 `ui/common/QuickEntryCard`——与今日页「快趣出行」同一张入口卡（2026-10-13 统一：
 * 此前这里是自带图标底座的另一套样式，和今日页那张对不上）。
 */
@Composable
private fun UjingEntryCard(modifier: Modifier, onClick: () -> Unit) {
    QuickEntryCard(
        icon = HugeIcons.WashingMachine,
        title = "U净 · 洗衣房",
        subtitle = "空闲看板 · 扫码下单",
        onClickLabel = "U净 · 洗衣房",
        onClick = onClick,
        modifier = modifier,
    )
}

/** 主色文字 + 右向箭头，作为「会跳走」的出口标记（同 SettingsRow 的箭头口径）。 */
@Composable
private fun LinkLabel(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
        )
        Icon(
            HugeIcons.ArrowRight01,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(15.dp),
        )
    }
}

/**
 * 充值选择弹窗（从下弹出）：一排两个——一卡通充值 / 寝室电费充值。
 * 选完由调用方再过充值免责声明闸，然后进各自的充值流程。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RechargeChoiceSheet(
    onDismiss: () -> Unit,
    onPickCampus: () -> Unit,
    onPickPower: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("选择充值项目", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = "两个系统各充各的，请确认要充哪一个",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SheetBigTile("一卡通充值", "正式卡 / 电子账户", HugeIcons.CreditCard, Modifier.weight(1f), onPickCampus)
                SheetBigTile("寝室电费充值", "缴费平台 · 房间电表", HugeIcons.Bolt, Modifier.weight(1f), onPickPower)
            }
        }
    }
}

/** 弹窗里的大图标块（充值两项并排用）。 */
@Composable
private fun SheetBigTile(
    title: String,
    sub: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable(onClickLabel = title) { onClick() }
            .padding(vertical = 16.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = primary, modifier = Modifier.size(23.dp))
        }
        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(
            text = sub,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 取快递弹窗（从下弹出）：预设归「取快递」段、自定义归「其他」段，中间一条分割线；
 * 条目并列铺开。长按条目弹菜单（添加到桌面 / 编辑），末尾虚线「新增」进设置页。
 *
 * 「取快递」段末尾另有一条「菜鸟批量查」整宽行（2026-10-13 从百宝箱移入）——
 * 它是 App 内页面（粘贴多个单号一次导入菜鸟），与「点一下拉起外部 App」的图标块不同，
 * 故单独一行、放在预设块之下。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShortcutSheet(
    settings: ShortcutSettings,
    onDismiss: () -> Unit,
    onLaunchItem: (ShortcutItem) -> Unit,
    onPinItem: (ShortcutItem) -> Unit,
    onEdit: (String) -> Unit,
    onAdd: () -> Unit,
    onOpenCainiao: () -> Unit,
) {
    val presets = settings.items.filter { it.presetIndex >= 0 }
    val customs = settings.items.filter { it.presetIndex < 0 }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Text("快捷方式", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = "点一下直接跳转，长按可编辑",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
            )
            if (presets.isNotEmpty()) {
                ShortcutCategoryLabel("取快递", presets.size)
                ShortcutTileGrid(
                    items = presets,
                    onLaunch = onLaunchItem,
                    onPin = onPinItem,
                    onEdit = onEdit,
                    onAdd = null,
                )
                Spacer(Modifier.height(12.dp))
                SheetActionRow(
                    icon = HugeIcons.PackageSearch01,
                    title = "菜鸟批量查",
                    subtitle = "多单号一次导入取件码",
                    onClick = onOpenCainiao,
                )
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
            ShortcutCategoryLabel("其他", customs.size)
            ShortcutTileGrid(
                items = customs,
                onLaunch = onLaunchItem,
                onPin = onPinItem,
                onEdit = onEdit,
                onAdd = onAdd,
            )
        }
    }
}

/** 弹窗里的分类标题：「取快递 · 3 项」。没有条目（count = 0）时不带计数后缀，只留分类名。 */
@Composable
private fun ShortcutCategoryLabel(title: String, count: Int) {
    Row(Modifier.padding(bottom = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        if (count > 0) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = "· $count 项",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            )
        }
    }
}

/** 弹窗里的一条整宽动作行（图标 + 标题 + 副标题 + 右箭头）：如「菜鸟批量查」。 */
@Composable
private fun SheetActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable(onClickLabel = title) { onClick() }
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = onSurface.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            HugeIcons.ChevronRight,
            contentDescription = null,
            tint = onSurface.copy(alpha = 0.35f),
            modifier = Modifier.size(18.dp),
        )
    }
}

/** 三列图标块网格；[onAdd] 非空时末尾补一枚虚线「新增」格子。 */
@Composable
private fun ShortcutTileGrid(
    items: List<ShortcutItem>,
    onLaunch: (ShortcutItem) -> Unit,
    onPin: (ShortcutItem) -> Unit,
    onEdit: (String) -> Unit,
    onAdd: (() -> Unit)?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val cells: List<Pair<ShortcutItem?, Boolean>> =
            items.map { it to false } + listOfNotNull(onAdd?.let { null to true })
        cells.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (item, isAdd) ->
                    if (isAdd) {
                        AddShortcutTile(Modifier.weight(1f)) { onAdd?.invoke() }
                    } else {
                        ShortcutSheetTile(
                            item = item!!,
                            modifier = Modifier.weight(1f),
                            onLaunch = { onLaunch(item) },
                            onPin = { onPin(item) },
                            onEdit = { onEdit(item.id) },
                        )
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** 弹窗里的一枚快捷方式：图标块 + 名称；长按弹「添加到桌面 / 编辑」。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShortcutSheetTile(
    item: ShortcutItem,
    modifier: Modifier = Modifier,
    onLaunch: () -> Unit,
    onPin: () -> Unit,
    onEdit: () -> Unit,
) {
    val haptics = rememberAppHaptics()
    var menuOpen by remember { mutableStateOf(false) }
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .combinedClickable(
                        onClick = {
                            haptics.tap()
                            onLaunch()
                        },
                        onLongClick = {
                            haptics.tap()
                            menuOpen = true
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                ShortcutIcon(item, Modifier.size(30.dp))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("添加到桌面") },
                    leadingIcon = { Icon(HugeIcons.Link01, null, modifier = Modifier.size(18.dp)) },
                    onClick = {
                        menuOpen = false
                        haptics.tap()
                        onPin()
                    },
                )
                DropdownMenuItem(
                    text = { Text("编辑") },
                    leadingIcon = { Icon(HugeIcons.Edit02, null, modifier = Modifier.size(18.dp)) },
                    onClick = {
                        menuOpen = false
                        onEdit()
                    },
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = item.name,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** 虚线「新增」格子。 */
@Composable
private fun AddShortcutTile(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(onSurface.copy(alpha = 0.03f))
                .clickable(onClickLabel = "新增快捷方式") { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                HugeIcons.Add01,
                contentDescription = null,
                tint = onSurface.copy(alpha = 0.5f),
                modifier = Modifier.size(26.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "新增",
            style = MaterialTheme.typography.labelMedium,
            color = onSurface.copy(alpha = 0.6f),
            maxLines = 1,
        )
    }
}

/**
 * U净 弹窗（从下弹出）：与胖乖 / 趣智校园那种入口面板同形态。
 * 有在案洗涤订单时，顶部先显示它的状态与剩余时间（[UjingOrderStatusLine]，订单对表在
 * 打开弹窗那一下做，见调用点）；主按钮「扫码识别洗衣机」，副行进 U净 页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UjingEntrySheet(
    onDismiss: () -> Unit,
    onScan: () -> Unit,
    onOpenPage: () -> Unit,
) {
    val context = LocalContext.current
    val repo = remember(context) { Graph.ujing(context) }
    val order by repo.orderState.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("U净 · 洗衣房", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            order?.takeIf { !it.isTerminal }?.let { UjingOrderStatusLine(it) }
            Text(
                text = "扫洗衣机机身上的二维码即可下单",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Button(
                onClick = onScan,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(HugeIcons.QrCodeScan, contentDescription = null, modifier = Modifier.size(21.dp))
                Spacer(Modifier.width(9.dp))
                Text("扫码识别洗衣机", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClickLabel = "查看洗衣房空闲情况") { onOpenPage() }
                    .padding(vertical = 11.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("查看洗衣房空闲情况", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.weight(1f))
                Icon(
                    HugeIcons.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
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
 * 流水卡的一段：分组标题（标题 + 右侧出口，无底色）+ 至多两条记录（行间内缩细线）。
 *
 * 标题行下接一条整宽细线、记录之间接内缩细线，靠「整宽 / 内缩」区分分组边界与组内行；
 * 不再给标题铺灰底（2026-10-14 整块重排：灰底标题条 + 彩色加粗链接视觉重量压过内容）。
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
                .padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = onSurface.copy(alpha = 0.9f),
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
        AppCardDivider()
        when {
            loading -> repeat(2) { index ->
                if (index > 0) AppCardDivider(Modifier.padding(start = FEED_ROW_DIVIDER_INSET))
                FeedRowSkeleton()
            }
            items.isEmpty() -> Text(
                text = emptyText,
                style = MaterialTheme.typography.bodySmall,
                color = onSurface.copy(alpha = 0.55f),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            )
            else -> items.forEachIndexed { index, item ->
                if (index > 0) AppCardDivider(Modifier.padding(start = FEED_ROW_DIVIDER_INSET))
                FeedRow(item)
            }
        }
    }
}

/** 组内行分隔线的左缩进：与行内标题文字对齐（14dp 行边距 + 18dp 图标 + 12dp 间隔）。 */
private val FEED_ROW_DIVIDER_INSET = 44.dp

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
    val amountLine = lineHeightDp(MaterialTheme.typography.bodyLarge)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SkeletonBox(Modifier.size(18.dp), RoundedCornerShape(4.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            SkeletonLine(width = 132.dp, line = titleLine)
            SkeletonLine(width = 96.dp, line = subLine)
        }
        Spacer(Modifier.width(8.dp))
        SkeletonLine(width = 56.dp, line = amountLine)
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

/**
 * 一条流水：来源图标 + 标题与副标题 + 金额。
 *
 * 行规格对齐消费流水页 `TurnoverRow` / 缴费账单页 `BillRow`（2026-10-14）：标题
 * `bodyMedium` Medium、副标题 `bodySmall` 0.55、金额 `bodyLarge` SemiBold + `¥` 前缀，
 * 入账走主色、支出用常规色与 `−`。
 */
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
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = onSurface.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = (if (item.income) "+" else "−") + "¥%.2f".format(kotlin.math.abs(item.amountFen) / 100.0),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (item.income) MaterialTheme.colorScheme.primary else onSurface,
        )
    }
}

/** 一卡通余额说明（钱包说明弹窗的前一段，2026-09-24 口径）。 */
private const val BALANCE_NOTE =
    "一卡通余额与流水都来自一卡通平台，服务端落账有延迟，刚发生的消费或充值可能暂时不计入。"

/** 电费说明（钱包说明弹窗的后一段，2026-09-24 用户给的原话；只有这一处口径）。 */
private const val ELECTRICITY_NOTE = "受服务器影响，实际费用可能有延迟，电费不足时请及时缴费"

private val WalletTimeFormat = java.time.format.DateTimeFormatter.ofPattern("HH:mm")

/** 取数时刻 → `22:56`；0 / null / 解析失败给 null（卡片不显示时刻）。 */
private fun walletTimeText(epochMs: Long?): String? = epochMs
    ?.takeIf { it > 0 }
    ?.let {
        runCatching {
            java.time.Instant.ofEpochMilli(it)
                .atZone(java.time.ZoneId.systemDefault())
                .format(WalletTimeFormat)
        }.getOrNull()
    }
