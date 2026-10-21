package edu.jxslu.schedule.ui.life

import android.content.Intent
import android.net.Uri
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.PowerBankPayActivity
import edu.jxslu.schedule.data.ykt.rechargeTargetCard
import edu.jxslu.schedule.domain.RechargeDisclaimer
import edu.jxslu.schedule.startActivityOutsideApp
import edu.jxslu.schedule.ui.campus.CampusArrivalDialog
import edu.jxslu.schedule.ui.campus.CampusCardViewModel
import edu.jxslu.schedule.ui.campus.CampusPendingConfirmDialog
import edu.jxslu.schedule.ui.campus.RechargeSheet
import edu.jxslu.schedule.ui.common.AppNoticeVisuals
import edu.jxslu.schedule.ui.common.NoticeTone
import edu.jxslu.schedule.ui.common.RechargeDisclaimerDialog
import edu.jxslu.schedule.ui.common.rememberResumeTick
import edu.jxslu.schedule.ui.common.postNotice
import edu.jxslu.schedule.ui.common.showNotice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 充值入口的种类（免责声明放行后决定打开哪个弹层）。 */
enum class RechargeKind { Campus, Power }

/**
 * 充值宿主（2026-10-22 第二版）：挂在 MainActivity 壳层，侧栏点「充值」后**不跳页面**，
 * 直接在这里弹对应的一卡通 / 电费充值弹层。旧「生活」页与它上面的钱包、校园服务、
 * 流水全部删除（那些入口已在侧栏 / 教务 Tab 各有正式落点）。
 *
 * 宿主常驻组合，但只有 [pending] 非空时才开流程；弹层打开后由本宿主持有，
 * 用户关掉弹层就结束，壳层/侧栏都不动。
 */
@Composable
fun RechargeHost(
    /** 侧栏选中的充值项目；null = 空闲。开完弹层后会回调 [onConsumed] 清掉。 */
    pending: RechargeKind?,
    onConsumed: () -> Unit,
    /** 到账弹窗的「出示付款码」落点 */
    onOpenPayCode: () -> Unit,
    /** 一卡通凭证未开启时的「去开启」落点 */
    onOpenCampusSettings: () -> Unit,
    /** 壳层 Snackbar（充值流程的提示共用这一份） */
    snackbar: SnackbarHostState,
    scope: CoroutineScope,
    viewModel: LifeViewModel = viewModel(factory = LifeViewModel.Factory(LocalContext.current)),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 一卡通侧（余额、到账轮询、充值）复用 CampusCardViewModel：那套状态机已经在
    // 「我的 -> 校园卡」跑通，这里只做入口与展示。
    val campusViewModel: CampusCardViewModel = viewModel(
        factory = CampusCardViewModel.Factory(context.applicationContext),
    )
    val campusEnabled by campusViewModel.enabled.collectAsStateWithLifecycle()
    val balance by campusViewModel.balance.collectAsStateWithLifecycle()
    val arrival by campusViewModel.arrivalState.collectAsStateWithLifecycle()

    var showRechargeSheet by remember { mutableStateOf(false) }
    var showPowerRecharge by remember { mutableStateOf(false) }
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
    // 一道提醒，勾「一周内不再提醒」静默 7 天（窗口口径 domain/RechargeDisclaimer）。
    // `initialValue = null`：此前初值 0，侧栏进页的 effect 抢在 DataStore 首帧前就判
    // 「没勾过」，于是每次都弹。
    val displayPrefs = remember { Graph.displayPrefs(context) }
    val disclaimerSuppressUntil by displayPrefs.rechargeDisclaimerSuppressUntil
        .collectAsStateWithLifecycle(initialValue = null)
    val disclaimerSeenAt by displayPrefs.rechargeDisclaimerSeenAt
        .collectAsStateWithLifecycle(initialValue = null)
    // 本次进程内刚勾下的截止时刻：DataStore 落盘是异步的，等它回来再判会漏掉「刚勾完
    // 又立刻点充值」这一下。
    var sessionSuppressUntil by remember { mutableStateOf(0L) }
    var disclaimerFor by remember { mutableStateOf<RechargeKind?>(null) }

    fun reallyOpenRecharge(kind: RechargeKind) {
        // 两个充值共用同一份一卡通凭证：没开启时先去设置页，不在充值弹层里卡住
        if (!campusEnabled) {
            onOpenCampusSettings()
            return
        }
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
        val until = maxOf(disclaimerSuppressUntil ?: 0L, sessionSuppressUntil)
        if (RechargeDisclaimer.isSuppressed(until, System.currentTimeMillis())) {
            reallyOpenRecharge(kind)
        } else {
            disclaimerFor = kind
        }
    }

    // 每次侧栏发起充值：先把电费读数/一卡通余额拉一轮（走缓存/闸门）。
    // **必须排在下面的 pending 判定之前**：`refreshPower` 会同步把 loading 置 true，
    // 后面的自动开弹层才能等到「房间是否已绑定」判出来，不会抢跑。
    LaunchedEffect(pending) {
        if (pending == null) return@LaunchedEffect
        viewModel.refreshPower(force = false)
        campusViewModel.refreshBalance(force = false)
    }

    // 等静默窗口与电费状态都就绪，再直接接上对应流程。
    LaunchedEffect(
        pending,
        disclaimerSuppressUntil,
        disclaimerSeenAt,
        state.power.loading,
    ) {
        val kind = pending ?: return@LaunchedEffect
        if (disclaimerSuppressUntil == null || disclaimerSeenAt == null) return@LaunchedEffect
        if (kind == RechargeKind.Power && state.power.loading) return@LaunchedEffect
        onConsumed()
        openRecharge(kind)
    }

    // 从微信/浏览器返回的瞬间立即补检一轮（不等 5 秒周期），并在未立即到账时弹
    // 「正在确认充值结果」；进程被杀重启也能靠持久化的未确认充值记录恢复。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, campusViewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) campusViewModel.onHostResume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 电费充值进入密码步骤：下单成功即取键盘挑战（passwordMap）。
    // **电子账户渠道专属**：农行渠道下单后走 BankPay 步，不该在这里多打一条 ACCOUNT 的请求。
    LaunchedEffect(powerRecharge.orderId, powerRecharge.step, powerRecharge.channel) {
        if (powerRecharge.channel == LifeViewModel.PowerRechargeUi.Channel.Account &&
            powerRecharge.step == LifeViewModel.PowerRechargeUi.Step.Amount &&
            powerRecharge.orderId != null
        ) {
            viewModel.loadPayChallenge()
        }
    }

    // 农行支付：从浏览器切回时自动查一次到账（静默——银行侧回调常有几十秒延迟，
    // 没查到只留一句中性提示，不弹错误）。
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
                // 绑定后立即按新房间刷新（force：平台刚写完，缓存里那份还是「没房间」）
                viewModel.refreshPower(force = true)
                scope.launch {
                    snackbar.showNotice(
                        AppNoticeVisuals("已设置寝室：$room", tone = NoticeTone.Success),
                    )
                }
            },
        )
    }

    // 充值免责声明弹窗：点「继续充值」落「已确认」（勾选则再落一周静默）后放行，
    // 取消只落「已确认」不静默——下次充值还会再弹，但立即可关。
    disclaimerFor?.let { kind ->
        RechargeDisclaimerDialog(
            closableAfterMs = RechargeDisclaimer.closeLockMs(
                disclaimerSeenAt,
                System.currentTimeMillis(),
            ),
            onContinue = { suppressWeek ->
                disclaimerFor = null
                if (suppressWeek) {
                    val until = RechargeDisclaimer.suppressUntil(System.currentTimeMillis())
                    sessionSuppressUntil = until
                    scope.launch { displayPrefs.setRechargeDisclaimerSuppressUntil(until) }
                }
                scope.launch { displayPrefs.markRechargeDisclaimerSeen() }
                reallyOpenRecharge(kind)
            },
            onDismiss = {
                disclaimerFor = null
                // 从电费弹层「去充值电子账户」过来时预选了电子账户；用户在这里取消，
                // 下次打开一卡通充值应回到默认（正式卡），别把预选留到下一次。
                if (kind == RechargeKind.Campus) campusRechargePreferElectric = false
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
    // MainActivity 根层，所有页面共用一份——从今日页「+ → 付款码」出码付款后也能当场消费结果。
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
