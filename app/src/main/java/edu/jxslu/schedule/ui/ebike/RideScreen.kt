package edu.jxslu.schedule.ui.ebike

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.SubpageActivity
import edu.jxslu.schedule.SubpageScreen
import edu.jxslu.schedule.domain.BikeNearby
import edu.jxslu.schedule.domain.EbikeFreeRide
import edu.jxslu.schedule.domain.EbikeQr
import edu.jxslu.schedule.domain.EbikeUseMode
import edu.jxslu.schedule.domain.KqcxAuth
import edu.jxslu.schedule.domain.capabilities
import edu.jxslu.schedule.ui.common.AppHaptics
import edu.jxslu.schedule.ui.common.AppNoticeVisuals
import edu.jxslu.schedule.ui.common.AppPermissions
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.NoticeTone
import edu.jxslu.schedule.ui.common.pinnedStatusBars
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import edu.jxslu.schedule.ui.theme.semanticColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ChevronDown
import me.rerere.hugeicons.stroke.Crosshair
import me.rerere.hugeicons.stroke.Lock
import me.rerere.hugeicons.stroke.MapsLocation02
import me.rerere.hugeicons.stroke.Settings01

/**
 * 快趣出行页的三种状态（DESIGN §3.9）。
 *
 * 三态是**同一个页面在变形**，不是三次跳转：地图始终在上面，底部动作条换内容。
 * 状态由数据推导，不另存一份可变状态（推导见 [RideScreen] 里的 `phase`）——
 * 多一份状态就多一个能和真值不一致的地方。
 *
 * 「选中某辆车」「选中某个停车点」不在三态里：它们只是**找车态的两种形态**
 * （动作条换成车辆卡 / 停车点卡），换的是内容不是页面。
 */
internal enum class RidePhase { Finding, Riding, Settled }

/**
 * 快趣出行页（DESIGN §3.9，唯一主页）。
 *
 * 结构（自下而上）：**页面底部常驻块**（动作区 [RideActionArea] + 免责那一行，高度由内容决定）
 * → **车辆面板** [RideBikePanel]（可拖高度、只装列表）→ 地图（吃掉剩下的空间，右上两枚浮钮）。
 * 两种使用方式共用同一套结构，模式只决定主动作是什么（`EbikeUseMode.capabilities()` 唯一判据）。
 *
 * **2026-10-01 收口**：动作区原来钉在面板的 footer 槽里，而面板是定高、动作区是变高——
 * 骑行态的仪表盘一长，最矮的档位就装不下（把手 + 仪表盘 + 免责超过面板高度），
 * 底部按钮被面板圆角裁掉。现在动作区在面板**之外**，面板的高度全部归列表。
 * 面板高度上限由「面板 + 常驻块」合计占窗口的比例算出：地图始终留三成。
 *
 * 找车态的动作区只有一枚主动作（内容随模式与"码是否已生成"翻面）+ 车辆卡；
 * 骑行 / 结算是仪表盘与结算卡。点地图上的数字 = 展开列表里对应那张停车点卡，
 * 点列表里的车 = 浮出车辆卡，两者都不另起浮层。
 *
 * 使用方式的切换入口是标题栏那枚常驻 chip（[RideModeChip]）——一个决定主动作是什么的开关，
 * 放在常驻位置才稳；**骑行中禁用**（切换会让在案订单从界面消失）。
 *
 * 用车动作全部走 [BikeMapViewModel.kvcx]（唯一一份编排，四道闸只有那一处）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RideScreen(
    onBack: () -> Unit = {},
    /**
     * 带车号进页（车号识别 / 深链 / 通知）：第一笔查询结果里找到就高亮并定位。
     * 不轮询——找不到就等用户下一次动作。
     */
    initialFocusCarNum: String? = null,
    viewModel: BikeMapViewModel = viewModel(
        factory = BikeMapViewModel.Factory(
            Graph.kqcxBikeClient(LocalContext.current),
            Graph.displayPrefs(LocalContext.current),
        ),
    ),
    /** 出码与计时归它管（生成 / 保存 / 焚码 / 免费时长计时的口径都在那边）。 */
    ebikeViewModel: EbikeViewModel = viewModel(
        factory = EbikeViewModel.Factory(Graph.displayPrefs(LocalContext.current)),
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val prefs by ebikeViewModel.ebikePrefs.collectAsStateWithLifecycle()
    val ebikeState by ebikeViewModel.uiState.collectAsStateWithLifecycle()
    val kvcxState by viewModel.kvcx.state.collectAsStateWithLifecycle()
    val kvcxLoggedIn by viewModel.kvcx.loggedIn.collectAsStateWithLifecycle()
    val useMode by viewModel.useMode.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val semantic = MaterialTheme.semanticColors
    val haptics = rememberAppHaptics()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val density = LocalDensity.current.density

    // 能力矩阵（唯一判据）：本页能做什么只看这几个布尔量，页面里不另写 if
    val caps = useMode.capabilities(loggedIn = kvcxLoggedIn, hasRide = kvcxState.ride != null)
    // 一处收口：小程序方式下不认内存里残留的快趣订单
    val ride = if (caps.inAppRide) kvcxState.ride else null
    // 倒计时只看「有没有在案计时」，不被提醒开关藏住（DESIGN §3.9 红线）
    var timerExpired by remember(prefs.rideStartAt) { mutableStateOf(false) }
    val timerActive = !timerExpired &&
        EbikeFreeRide.isActive(prefs.rideStartAt, System.currentTimeMillis())
    val riding = ride != null || timerActive
    // 结算卡只属于账号方式（小程序方式没有本机还车这个动作）
    val summary = if (caps.inAppRide) kvcxState.returnSummary else null
    // 手里有没有一张能用的码：小程序方式的主动作在"有码"之后翻面
    val hasCode = ebikeState.generatedBikeId != null && ebikeState.generatedBitmap != null
    /**
     * 头行要不要摆「按车号」。
     *
     * 小程序方式没出码时，主动作本身就是「按车号生成乘车码」，再摆一枚是重复
     * （2026-09-30 用户口径「小程序方式不需要重复的图标」）；其余情况摆在这里，
     * 带文字，比旧版地图左下角那枚没有文字的圆形图标好认。
     */
    val showCarNumberEntry = !(caps.wechatScan && !hasCode)

    /** 选中车辆后动作区换成车辆卡（[RideActionArea] 的一种形态）；null = 不显示。 */
    var pickedCar by remember { mutableStateOf<String?>(null) }
    /**
     * 想让哪辆车出码（车号）；车号面板里按它对齐二维码。
     *
     * 存"想要的车号"而不是一个布尔量：生成是异步的（zxing 渲染在位图线程），
     * 布尔量会在码还没出来时就铺一个空框；按车号对齐就只在真出好了才铺。
     */
    var qrRequest by remember { mutableStateOf<String?>(null) }
    var showCarNumberSheet by remember { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }
    var showModeSheet by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showEndTimerConfirm by remember { mutableStateOf(false) }
    /**
     * 待确认的写操作与它针对的车号（开锁用；其余动作不需要车号）。
     *
     * 故意用**裸 state 对象**（不在本函数体里 `by` 读它）：读的位置决定重组范围，读在这里
     * 就会让开关弹窗重组整页——见 [KvcxConfirmHost] 的注释。
     */
    val pendingAction = remember { mutableStateOf<Pair<KvcxAction, String?>?>(null) }

    val phase = when {
        summary != null -> RidePhase.Settled
        riding -> RidePhase.Riding
        else -> RidePhase.Finding
    }
    /**
     * 列表与地图要不要换成还车点（2026-10-01 第二次修订）。
     *
     * 骑行态优先给还车点：那一刻用户要找的是"停哪儿"。**两档都适用**——图层接口不需要
     * 凭证（见 `KvcxSessionRepository.queryZones`），小程序方式也拉得到，不再按使用方式分。
     *
     * 判据里带 `parkSpots` 非空：这一带没有还车点数据、或图层还没回来时，地图与列表
     * **一起**留在车上（两者共用这一个布尔量），不至于出现"空地图 + 一屏没有数据"
     * ——用户点完「打开微信扫一扫」回来那样就像走错了页。
     */
    val showSpots = phase == RidePhase.Riding && state.zones.parkSpots.isNotEmpty()

    // 骑行中禁切模式，切完也不该留着上一档的临时状态（选中车 / 打开的出码面板）
    LaunchedEffect(useMode) {
        pickedCar = null
        qrRequest = null
        showCarNumberSheet = false
    }
    // 进入骑行态就收起找车态那些临时形态
    LaunchedEffect(phase) {
        if (phase != RidePhase.Finding) {
            pickedCar = null
            showCarNumberSheet = false
        }
    }

    // ── 权限与动作 ──
    // 写操作要定位（快趣按位置校验）：未授权先申请，授权后用户再点一次
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        if (!BikeLocator.hasPermission(context)) {
            showNotice(scope, snackbar, "没有定位权限，快趣无法校验位置", NoticeTone.Warning)
        }
    }
    val requestKvcxAction: (KvcxAction, String?) -> Unit = { action, carNum ->
        haptics.tap()
        if (!BikeLocator.hasPermission(context)) {
            locationPermissionLauncher.launch(AppPermissions.location.toTypedArray())
            showNotice(scope, snackbar, "本机用车需要定位权限（快趣按位置校验）", NoticeTone.Warning)
        } else if (action == KvcxAction.LOCK) {
            // 临时锁车免二次确认：订单与计费继续、随时可再解锁
            viewModel.kvcx.tempLock()
        } else if (action == KvcxAction.RESUME) {
            viewModel.kvcx.resumeRide()
        } else if (action == KvcxAction.UNLOCK && !prefs.unlockConfirm) {
            // 用户在开锁弹窗勾过「不再提醒」（或关了设置里的开关）：直接发指令。
            // 这是四道闸里唯一允许用户自己关的一道，定位前置照旧在前面挡着；
            // 免确认的后果（按一下就开始计费）在勾选那一刻已经写明。
            carNum?.let(viewModel.kvcx::unlock)
        } else {
            pendingAction.value = action to carNum
        }
    }
    val withNotificationPermission = rememberNotificationPermissionGate()

    // 内置相机扫车身码（账号方式）：识别后**回填车号 + 换成车辆卡**，不自动开锁——写操作仍走确认闸
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val raw = result.contents
        val carNum = raw?.let(EbikeQr::parseScannedCarNum)
        when {
            // 内容为空 = **取消**：取景页没扫到就被关掉（返回键 / 手势返回）都落这里。
            // 取消不是失败——报一句「未识别到有效车号」等于每次放弃扫码都挨一次无端报错
            raw == null -> Unit
            // 扫到了、但内容不是车号（名片码 / 小程序码 / 别的链接）：这才是识别失败
            carNum == null ->
                showNotice(scope, snackbar, "未识别到有效车号，请对准车身上的二维码", NoticeTone.Warning)
            else -> {
                haptics.tap()
                ebikeViewModel.onCarInput(carNum)
                pickedCar = carNum
                viewModel.focusCar(carNum)
            }
        }
    }
    val scanBodyCode: () -> Unit = {
        haptics.tap()
        scanLauncher.launch(
            ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt("对准车身上的二维码")
                setBeepEnabled(false)
                setOrientationLocked(true)
                // 自带的 CaptureActivity 只有取景框 + 提示，没有手电筒与相册入口（扫描窗口见它）
                setCaptureActivity(EbikeScanActivity::class.java)
            },
        )
    }
    // 「生成乘车码」：按输入框里的车号生成一张，好了铺进车号面板（DESIGN §3.9）
    val generateCode: () -> Unit = {
        haptics.tap()
        val car = EbikeQr.resolveCarNum(ebikeState.carInput)
        if (car == null) {
            ebikeViewModel.generate() // 走它自己的行内校验提示
        } else {
            qrRequest = car
            ebikeViewModel.generate()
        }
    }
    // 主动作「打开微信扫一扫」：不要求先出码，拉起微信 + 起免费计时（顺带补存已有的码）
    val wechatScanPlain: () -> Unit = {
        haptics.tap()
        withNotificationPermission {
            ebikeViewModel.onWechatScanClicked()
            openWechatScan(context) { message -> showNotice(scope, snackbar, message) }
        }
    }
    // 停车点卡 / 车辆卡上的「出码」：按这辆车出码并打开面板
    val generateForCar: (String) -> Unit = { carNum ->
        haptics.tap()
        ebikeViewModel.onCarInput(carNum)
        ebikeViewModel.onPickCarNum(carNum)
        qrRequest = EbikeQr.resolveCarNum(carNum)
        showCarNumberSheet = true
    }
    val openAccount: () -> Unit = {
        haptics.tap()
        SubpageActivity.start(context, SubpageScreen.KVCX)
    }
    /**
     * 打开「车号 / 出码」面板：面板头行那枚「按车号」与动作区里同名的那枚共用一个入口。
     * `qrRequest` 清掉 = 面板里不预置哪辆车的码（让用户自己输）。
     */
    val openCarNumberSheet: () -> Unit = {
        haptics.tap()
        qrRequest = null
        showCarNumberSheet = true
    }

    // ── 事件与生命周期 ──
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is BikeMapEvent.Notice ->
                    snackbar.showSnackbar(AppNoticeVisuals(event.text, tone = event.tone))
                BikeMapEvent.Unlocked -> haptics.success()
            }
        }
    }
    LaunchedEffect(Unit) {
        ebikeViewModel.events.collect { event ->
            when (event) {
                is EbikeEvent.Notice ->
                    snackbar.showSnackbar(AppNoticeVisuals(event.text, tone = event.tone))
            }
        }
    }
    LaunchedEffect(Unit) {
        // 进页核对一次：计时中缺服务/闹钟就补上，过期状态就清干净（幂等）
        EbikeFreeRideReminder.check(context)
        viewModel.queryRideQuietly()
    }
    LaunchedEffect(initialFocusCarNum) {
        if (!initialFocusCarNum.isNullOrBlank()) {
            pickedCar = initialFocusCarNum
            viewModel.setPendingFocusCar(initialFocusCarNum)
        }
    }
    // 骑行状态在别处（微信 / 桌面）变了：回到本页时不能还是旧值；焚码兜底同口径
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                ebikeViewModel.burnPending()
                viewModel.queryRideQuietly()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            ebikeViewModel.burnPending()
        }
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // 进页定位一次（已授权静默定；没问过且没被拒就申请一次）——与地图页同一套口径。
    // `ebikeLocationAsked` 落 DataStore：系统在用户拒绝两次后静默拒绝，不看这个标记的话
    // 每次进页面都会白申请一次、再弹一句「已拒绝」，那就成了骚扰。
    var locationGranted by remember { mutableStateOf(BikeLocator.hasPermission(context)) }
    val notifyLocateIssue: suspend (String) -> Unit = { message ->
        val outcome = snackbar.showSnackbar(
            AppNoticeVisuals(
                message = message,
                actionLabel = "去设置",
                tone = NoticeTone.Warning,
            ),
        )
        if (outcome == SnackbarResult.ActionPerformed) AppPermissions.jumpAppDetails(context)
    }
    val locatePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        // 进状态是为了让下面的连续定位流跟着重启：授权后不用等下次进页蓝点就活了
        locationGranted = result.values.any { it }
        if (locationGranted) {
            locate(context, viewModel, scope, silent = false, onIssue = notifyLocateIssue)
        } else {
            showNotice(scope, snackbar, DENIED_HINT, NoticeTone.Warning)
        }
    }
    val prefsStore = remember { Graph.displayPrefs(context) }
    LaunchedEffect(Unit) {
        when {
            BikeLocator.hasPermission(context) ->
                locate(context, viewModel, scope, silent = true, onIssue = {})
            !prefsStore.ebikeLocationAsked.first() -> {
                prefsStore.setEbikeLocationAsked(true)
                locatePermissionLauncher.launch(AppPermissions.location.toTypedArray())
            }
            // 问过也被拒过：什么都不做，等用户点「定位」按钮
        }
    }
    // 蓝点实时更新：只挪蓝点与「距你」距离，不移镜头、不重查接口
    LaunchedEffect(lifecycleOwner, locationGranted) {
        if (!locationGranted) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            BikeLocator.updates(context).collect { result ->
                if (result is LocateResult.Ok) {
                    viewModel.onUserLocationChanged(result.lat, result.lng)
                }
            }
        }
    }

    // 标记配色跟着主题走；只在主题色变化时重算
    val markerColors = remember(scheme.primary, semantic.warning, scheme.outline, scheme.onPrimary, scheme.onSurface) {
        BikeMarkerColors(
            available = scheme.primary.toArgb(),
            lowBattery = semantic.warning.toArgb(),
            unavailable = scheme.outline.toArgb(),
            label = scheme.onPrimary.toArgb(),
            selectedRing = scheme.onSurface.toArgb(),
            userDot = scheme.primary.toArgb(),
            userHalo = scheme.primary.copy(alpha = 0.22f).toArgb(),
            centerMark = scheme.onSurface.toArgb(),
            centerHalo = scheme.surface.toArgb(),
            fenceStroke = Color(0xFF3D8BEF).copy(alpha = 0.85f).toArgb(),
            fenceFill = Color(0xFF64B5F6).copy(alpha = 0.30f).toArgb(),
            rideMarker = scheme.primary.toArgb(),
            rideHalo = scheme.primary.copy(alpha = 0.22f).toArgb(),
            rideGlyph = scheme.onPrimary.toArgb(),
            nogoStroke = Color(0xFF333333).copy(alpha = 0.45f).toArgb(),
            nogoFill = Color(0xFF333333).copy(alpha = 0.22f).toArgb(),
            spotStroke = Color(0xFFD7535D).copy(alpha = 0.9f).toArgb(),
            spotFill = Color(0xFFD7535D).copy(alpha = 0.26f).toArgb(),
            spotBadge = Color(0xFFD7535D).toArgb(),
            spotGlyph = Color(0xFFFFFFFF).toArgb(),
        )
    }
    // 「我的车」坐标口径：未锁 = 正在骑，车就在你身边（服务端坐标是拉取那刻的快照）
    val rideFollowsUser = ride?.locked == false && state.userLat != null && state.userLng != null
    val rideMarkerLat = if (rideFollowsUser) state.userLat else ride?.lat
    val rideMarkerLng = if (rideFollowsUser) state.userLng else ride?.lng
    /**
     * 页面底部常驻块（动作区 + 免责）**稳定态的实测高度**（含导航栏内边距）：面板上限要按
     * 它扣、地图才留得住三成，Snackbar 也靠它抬到常驻块之上。首帧先用估值兜底。
     *
     * **不按 phase 重置**：换形态时保留上一档的值，等动画结束量到新值再改（见 [mapReserveDp]）。
     * 按 phase 重置成估值的话，切换那一帧地图会先跳到估值、动画结束再跳一次真值——两次多余的
     * 整幅重绘，还可能露出地图底边与面板之间的空档。
     */
    var measuredBlockDp by remember {
        mutableStateOf(if (phase == RidePhase.Riding) 250f else 120f)
    }
    /** 布局回调里的**实时**高度。普通持有者：换形态期间只记账，不驱动重组。 */
    val blockHeight = remember { DpHolder(measuredBlockDp) }
    /**
     * 常驻块的实时高度，**只给 Snackbar 用**。
     *
     * 与 [measuredBlockDp] 分成两个状态是因为读点不同：这个只在 `snackbarHost` 的 lambda 里读，
     * 每帧写也只重组那一个作用域；而 [measuredBlockDp] 被 `BoxWithConstraints` 读，写它等于
     * 整页重组。分开之后 Snackbar 能跟着常驻块平稳上移（开锁成功的提示正好挂在换形态那一刻），
     * 页面本身仍然安静。
     */
    var liveBlockDp by remember { mutableStateOf(measuredBlockDp) }
    /**
     * 地图给底部让位用的高度（**冻结值**，与 [measuredBlockDp] 不是一回事）。
     *
     * [barKey] 一变，`RideActionArea` 就走一次 240ms 的高度动画（`AnimatedContent` 的
     * `SizeTransform`）。地图若跟着实时高度走，这 240ms 里**每一帧**都会 resize 一次视图，
     * osmdroid 每次 resize 都整幅重绘（瓦片 + 校园围栏 + 车标 + 还车点）——用户报的
     * 「开锁后掉帧、然后骑行面板弹出来」就是这一段（2026-10-01 定位）。
     *
     * 现在的分工：动画期间地图尺寸**不动**，升起来的底部块直接盖在它下沿；动画结束再一次性
     * 落位（被切掉的那一截正好在已经升上去的块后面，看不见）。视觉仍是"底部窗口升起"，
     * 但整段动画只 resize 一次地图。
     */
    var mapReserveDp by remember { mutableStateOf(measuredBlockDp) }
    /** 底部块是否正在换形态（见 [mapReserveDp]）。 */
    var blockAnimating by remember { mutableStateOf(false) }
    val barKey = rideBarKey(phase, caps, kvcxLoggedIn, pickedCar, hasCode)
    var lastBarKey by remember { mutableStateOf(barKey) }
    LaunchedEffect(barKey) {
        // 首次组合不是"换形态"：只有 key 真的变了才冻结
        if (lastBarKey == barKey) return@LaunchedEffect
        lastBarKey = barKey
        blockAnimating = true
        // 等高度动画走完（多留一点余量，动画末帧还有一次落位）
        delay(BAR_RISE_MS.toLong() + BLOCK_SETTLE_MS)
        measuredBlockDp = blockHeight.value
        blockAnimating = false
    }
    // 不换形态时随时跟随实测值（首帧的估值就是这么被纠正的）
    LaunchedEffect(measuredBlockDp, blockAnimating) {
        if (!blockAnimating) mapReserveDp = measuredBlockDp
    }

    Scaffold(
        // 页面自己吃掉窗口底：动作条底色要一直铺到屏幕底边
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = pinnedStatusBars(),
                title = { Text("快趣出行") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(HugeIcons.ArrowLeft01, contentDescription = "返回")
                    }
                },
                actions = {
                    RideModeChip(
                        mode = useMode,
                        locked = riding,
                        onClick = {
                            haptics.tap()
                            if (riding) {
                                showNotice(
                                    scope,
                                    snackbar,
                                    "骑行中不能切换使用方式，还车后再切",
                                    NoticeTone.Warning,
                                )
                            } else {
                                showModeSheet = true
                            }
                        },
                    )
                    IconButton(onClick = {
                        haptics.tap()
                        showSettings = true
                    }) {
                        Icon(
                            HugeIcons.Settings01,
                            contentDescription = "快趣出行设置",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                        )
                    }
                },
            )
        },
        snackbarHost = {
            // 用**实时**高度：提示要落在常驻块此刻真实的上缘之上（换形态时跟着一起上移）
            AppSnackbarHost(snackbar, Modifier.padding(bottom = liveBlockDp.dp))
        },
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // 面板高度是定值（用户可拖）：内容多少都不改变它，地图不被挤小。
            // 上限被两道压：窗口比例（面板 + 常驻块合计留三成给地图）与绝对上限
            val maxPanelDp = minOf(
                MAX_PANEL_HEIGHT_DP,
                // 用冻结值：面板上限在动画期间不该跟着变（那会让面板与地图同时动）
                maxHeight.value * PANEL_MAX_RATIO - mapReserveDp,
            ).coerceAtLeast(MIN_PANEL_HEIGHT_DP)
            LaunchedEffect(maxPanelDp, state.panelHeightDp) {
                viewModel.clampPanelHeight(MIN_PANEL_HEIGHT_DP, maxPanelDp)
            }
            val panelHeight = state.panelHeightDp.coerceIn(MIN_PANEL_HEIGHT_DP, maxPanelDp).dp

            // 地图在上、面板与动作区在下（**不是覆盖**）：动作区展开时地图跟着让位。
            // 覆盖式布局会把「我的位置」压在动作区底下——地图中心即用户位置。
            //
            // 结构上地图与底部分层叠在同一个 Box 里：地图按 mapReserveDp（冻结值）让位、
            // 底部（面板 + 常驻块）贴底。换形态的 240ms 里地图不动、被升起来的底部块盖住，
            // 动画结束才一次性落位——理由见 mapReserveDp 的注释。
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = panelHeight + mapReserveDp.dp),
                ) {
                    OsmMapView(
                        // 账号方式的骑行态让位给还车点：那一刻用户要找的是"停哪儿"。
                        // 小程序方式没有还车点图层，让位只会剩一张空地图（见 showSpots）
                        clusters = if (showSpots) emptyList() else state.clusters,
                        // 高亮跟列表的展开态走：点标记与在列表里展开是同一件事的两个入口
                        selectedKey = state.expandedKey,
                        userLat = state.userLat,
                        userLng = state.userLng,
                        rideLat = rideMarkerLat,
                        rideLng = rideMarkerLng,
                        highlightLat = state.highlightedCar?.lat,
                        highlightLng = state.highlightedCar?.lng,
                        highlightLabel = state.highlightedCar?.carNum
                            ?.let { "车 " + EbikeQr.chipLabel(it) },
                        zones = state.zones,
                        colors = markerColors,
                        camera = state.camera,
                        onCenterSettled = viewModel::onMapSettled,
                        // 点地图上的数字 = 展开列表里对应的那张停车点卡，并把列表滚过去。
                        // **不再弹第二份卡片**——同一批车只在一处出现（2026-09-30 用户口径）
                        onClusterTap = { key ->
                            haptics.tap()
                            viewModel.onClusterTap(key)
                        },
                        onRideTap = {
                            haptics.tap()
                            rideMarkerLat?.let { lat ->
                                rideMarkerLng?.let { lng -> viewModel.onFocusPoint(lat, lng) }
                            }
                        },
                        onCameraApplied = viewModel::onCameraApplied,
                        modifier = Modifier.fillMaxSize(),
                    )

                    // 车号输入搬进「车号 / 出码」面板，地图上不再有第二个入口抢注意力
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 12.dp)
                            .padding(end = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MapCircleButton(
                            icon = HugeIcons.Crosshair,
                            label = "定位到我的位置",
                            busy = state.locating,
                            onClick = {
                                haptics.tap()
                                if (BikeLocator.hasPermission(context)) {
                                    locate(context, viewModel, scope, silent = false, onIssue = notifyLocateIssue)
                                } else {
                                    locatePermissionLauncher.launch(AppPermissions.location.toTypedArray())
                                }
                            },
                        )
                        MapCircleButton(
                            icon = HugeIcons.MapsLocation02,
                            label = "回到校区",
                            onClick = {
                                haptics.tap()
                                viewModel.onResetToCampus()
                            },
                        )
                    }

                }

                // 面板 + 常驻块：贴底、叠在地图之上（换形态时它们就是"盖在图上"升起来的）
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter),
                ) {
                    // 底部车辆面板：默认就展开着（用户 2026-09-30 口径），高度可拖。
                    // 「按车号」在地图之外的头行里（见 showCarNumberEntry）：地图上不再挂
                    // 第二枚浮动图标，那一枚压在面板上沿、跟拖动把手抢位置
                    RideBikePanel(
                        state = state,
                        caps = caps,
                        showSpots = showSpots,
                        showCarNumberEntry = showCarNumberEntry,
                        spots = state.zones.parkSpots,
                        refLat = state.userLat ?: BikeNearby.DEFAULT_CENTER_LAT,
                        refLng = state.userLng ?: BikeNearby.DEFAULT_CENTER_LNG,
                        refFromUser = state.distanceFromUser,
                        panelHeight = panelHeight,
                        onResize = { dragPx ->
                            viewModel.resizePanelBy(
                                deltaDp = -dragPx / density,
                                minDp = MIN_PANEL_HEIGHT_DP,
                                maxDp = maxPanelDp,
                            )
                        },
                        onResizeFinished = viewModel::persistPanelHeight,
                        onClusterTap = { key ->
                            // 触感由 RideClusterCard 自己发（AppCard 口径：组件内部统一触发）
                            viewModel.onClusterTap(key)
                        },
                        onPick = { carNum ->
                            // 点列表里的车 = 动作条换成车辆卡；触感由 RideBikeRow 自己发
                            ebikeViewModel.onCarInput(carNum)
                            pickedCar = carNum
                        },
                        onUnlock = { carNum -> requestKvcxAction(KvcxAction.UNLOCK, carNum) },
                        onResetToCampus = {
                            haptics.tap()
                            viewModel.onResetToCampus()
                        },
                        onRefresh = {
                            haptics.tap()
                            viewModel.refresh()
                        },
                        onOpenCarNumber = openCarNumberSheet,
                        onOpenFilter = {
                            haptics.tap()
                            showFilterSheet = true
                        },
                        // 行尾的「生成乘车码」直接进车号面板（账号方式那枚是「开锁」，走确认闸）
                        onGenerateForCar = generateForCar,
                        onSpotTap = { spot ->
                            // 触感由 AppCardRow 自己发
                            viewModel.onFocusPoint(spot.lat, spot.lng)
                        },
                    )

                    // 页面底部常驻块：主动作 + 免责那一行。它在面板**之外**——面板定高、
                    // 动作区变高，装在一起就是把按钮挤出去（骑行仪表盘就是装不下的那个）。
                    // 导航栏内边距归这一块吃，面板不再自己垫。
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(scheme.surface)
                        .onGloballyPositioned { coords ->
                            // 量的是含导航栏内边距的总高：面板上限要按它扣，地图才留得住三成
                            blockHeight.value = coords.size.height / density
                            // Snackbar 用实时值（读点在它自己的作用域里，写它不贵）
                            liveBlockDp = blockHeight.value
                            // 换形态的动画期间只记账不写状态：每帧写一次会让整页跟着重组；
                            // 动画结束时由那个协程统一取一次（见 measuredBlockDp 的注释）
                            if (!blockAnimating) measuredBlockDp = blockHeight.value
                        }
                            .navigationBarsPadding(),
                    ) {
                        RideActionArea(
                            phase = phase,
                            caps = caps,
                            loggedIn = kvcxLoggedIn,
                            state = state,
                            pickedCar = pickedCar,
                            ride = ride,
                            rideFetchedAt = kvcxState.fetchedAt,
                            busy = kvcxState.busy,
                            unlockPending = kvcxState.unlockPending,
                            timerActive = timerActive,
                            timerStartAt = prefs.rideStartAt,
                            timerCanEnd = ride == null,
                            summary = summary,
                            hasCode = hasCode,
                            onDismissPicked = { pickedCar = null },
                            onWechatScan = wechatScanPlain,
                            onScanBodyCode = scanBodyCode,
                            onOpenCarNumber = openCarNumberSheet,
                            onLogin = openAccount,
                            onUnlock = { carNum -> requestKvcxAction(KvcxAction.UNLOCK, carNum) },
                            onGenerateForCar = generateForCar,
                            // 车辆卡头行那枚定位：把镜头移到这辆车（车号面板里那枚的卡片版）
                            onFocusCar = { carNum -> viewModel.focusCar(carNum) },
                            onFocusRide = {
                                rideMarkerLat?.let { lat ->
                                    rideMarkerLng?.let { lng -> viewModel.onFocusPoint(lat, lng) }
                                }
                            },
                            onTempLock = { requestKvcxAction(KvcxAction.LOCK, null) },
                            onResume = { requestKvcxAction(KvcxAction.RESUME, null) },
                            onReturn = { requestKvcxAction(KvcxAction.RETURN, null) },
                            onRetryUnlock = { requestKvcxAction(KvcxAction.RETRY_UNLOCK, null) },
                            onRefreshRide = {
                                haptics.tap()
                                viewModel.refreshRide()
                            },
                            // 响铃寻车（2026-09-30）：控制器本地先挡「没有骑行」，这里只管触发
                            onRingFind = {
                                haptics.tap()
                                viewModel.kvcx.ringFindCar()
                            },
                            // 锁状态查询（2026-09-30）：点骑行卡上的锁徽标现查一次
                            onQueryLock = {
                                haptics.tap()
                                viewModel.kvcx.queryLockState()
                            },
                            onEndTimer = { showEndTimerConfirm = true },
                            onTimerExpired = { timerExpired = true },
                            onSettle = {
                                haptics.tap()
                                openWechatForSettle(context) { message ->
                                    showNotice(scope, snackbar, message, NoticeTone.Info)
                                }
                            },
                        onContinue = {
                            haptics.tap()
                            pickedCar = null
                            viewModel.kvcx.dismissReturnSummary()
                        },
                        // 形态 key 由页面算（唯一出处 rideBarKey）：页面靠它冻结地图让位高度
                        barKey = barKey,
                        modifier = Modifier.fillMaxWidth(),
                    )
                        Text(
                            text = "车辆数据来自快趣接口，可能延迟或不准，以运营平台为准。",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurface.copy(alpha = 0.45f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .padding(bottom = 8.dp),
                        )
                    }
                }
            }
        }
    }

    if (showCarNumberSheet) {
        RideCarNumberSheet(
            caps = caps,
            carInput = ebikeState.carInput,
            inputError = ebikeState.inputError,
            // 最近生成的车号只留**一个**，摆在输入框内部右侧（2026-09-30 用户口径）
            lastRecent = prefs.recentIds.firstOrNull(),
            resolvedCarNum = EbikeQr.resolveCarNum(ebikeState.carInput),
            wantedCarNum = qrRequest,
            qrCarNum = ebikeState.generatedBikeId,
            qrBitmap = ebikeState.generatedBitmap,
            qrSaved = ebikeState.generatedSaved,
            onCarInput = ebikeViewModel::onCarInput,
            onPickRecent = {
                prefs.recentIds.firstOrNull()?.let { carNum ->
                    haptics.tap()
                    qrRequest = EbikeQr.resolveCarNum(carNum)
                    ebikeViewModel.onPickRecent(carNum)
                }
            },
            onScanBodyCode = if (caps.cameraScan) scanBodyCode else null,
            onLocateCar = {
                haptics.tap()
                EbikeQr.resolveCarNum(ebikeState.carInput)?.let(viewModel::focusCar)
                showCarNumberSheet = false
            },
            onGenerate = generateCode,
            onSave = {
                haptics.tap()
                ebikeViewModel.saveCurrent()
            },
            // 收面板与"关掉再去微信"都由弹层自己的退场序列负责（`RideCarNumberSheet` 里的
            // `pendingDismiss`），这里只管动作本身——在这里改状态会把弹层从组合里瞬间抽掉
            onWechatScan = wechatScanPlain,
            onUnlock = {
                EbikeQr.resolveCarNum(ebikeState.carInput)?.let { car ->
                    requestKvcxAction(KvcxAction.UNLOCK, car)
                }
            },
            onDismiss = { showCarNumberSheet = false },
        )
    }

    // 筛选弹层：两枚开关收进面板头行那枚图标（2026-09-30 用户口径）
    if (showFilterSheet) {
        RideFilterSheet(
            onlyOurCampus = state.onlyOurCampus,
            onlyAvailable = state.onlyAvailable,
            onToggleOnlyOurCampus = {
                haptics.tap()
                viewModel.setOnlyOurCampus(!state.onlyOurCampus)
            },
            onToggleOnlyAvailable = {
                haptics.tap()
                viewModel.setOnlyAvailable(!state.onlyAvailable)
            },
            onDismiss = { showFilterSheet = false },
        )
    }

    // 使用方式切换（骑行中打不开，见 chip 的 onClick）
    if (showModeSheet) {
        RideModeSheet(
            mode = useMode,
            onSelect = { mode ->
                haptics.tap()
                scope.launch { Graph.displayPrefs(context).setEbikeUseMode(mode) }
                showModeSheet = false
                showNotice(
                    scope,
                    snackbar,
                    "已切换到${mode.label}：" + if (mode.isAccount) {
                        "主动作变为「扫车身码」"
                    } else {
                        "主动作变为「按车号生成乘车码 / 打开微信扫一扫」"
                    },
                    NoticeTone.Info,
                )
            },
            onDismiss = { showModeSheet = false },
        )
    }

    if (showSettings) {
        EbikeSettingsSheet(
            viewModel = ebikeViewModel,
            loggedIn = kvcxLoggedIn,
            onDismiss = { showSettings = false },
            onOpenKvcxAccount = openAccount,
            onNotice = { message, tone -> showNotice(scope, snackbar, message, tone) },
        )
    }

    // 结束计时（本地计时专用，与「还车」是两个动作）
    if (showEndTimerConfirm) {
        AlertDialog(
            onDismissRequest = { showEndTimerConfirm = false },
            title = { Text("结束计时？") },
            text = { Text("结束后将清空免费时长计时，并撤掉通知栏上的倒计时与提醒。开车 / 还车仍在微信小程序里完成。") },
            confirmButton = {
                TextButton(onClick = {
                    showEndTimerConfirm = false
                    haptics.tap()
                    ebikeViewModel.onEndRide()
                }) {
                    Text("结束计时", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEndTimerConfirm = false }) { Text("继续骑行") }
            },
        )
    }

    // 本机用车确认（写操作二次确认；文案唯一出处 kvcxConfirmDialog）。
    // 状态读在 KvcxConfirmHost 里面（见那处注释），本函数体不读它。
    KvcxConfirmHost(
        pending = pendingAction,
        ride = ride,
        kvcx = viewModel.kvcx,
        scope = scope,
        snackbar = snackbar,
        haptics = haptics,
        context = context,
    )
}

/**
 * 用车确认弹窗的宿主：**「待确认动作」这个状态由它自己读**，页面函数体只负责写。
 *
 * 为什么这么切（2026-10-01 用户报「点确认开锁后卡一下」）：写操作那几帧本来就要做不少事
 * （关弹窗窗口、按钮切「开锁中…」、发请求），而状态若在 `RideScreen` 函数体里读，开关弹窗
 * 会**重组整页**——连页面下半部的地图一起，`AndroidView` 的 update 跟着跑一遍（历史上那里
 * 收尾无条件 `invalidate()`，一次整幅重绘）。把读收进这个小组件后，弹窗的开关只重组它自己
 * （地图那侧另有一道"画的东西没变就不重画"的判定，见 `MapOverlayInputs`）。
 */
@Composable
private fun KvcxConfirmHost(
    pending: MutableState<Pair<KvcxAction, String?>?>,
    ride: KqcxAuth.Ride?,
    kvcx: KvcxRideController,
    scope: CoroutineScope,
    snackbar: SnackbarHostState,
    haptics: AppHaptics,
    context: Context,
) {
    val pendingAction by pending
    val (action, carNum) = pendingAction ?: return
    val carLabel = carNum?.let { "车 $it" }
        ?: ride?.carNum?.let { "车 $it" }
        ?: "选中的车"
    val dialog = kvcxConfirmDialog(action, carLabel)
    if (dialog == null) {
        // 规格里没有这个动作（不该发生：调用方已经挡掉免确认的动作）
        pending.value = null
        return
    }
    // 勾选不记住：每次打开都从"没勾"开始——勾选是这一次的明确决定
    var skipNextTime by remember(action, carNum) { mutableStateOf(false) }
    KvcxConfirmDialog(
        dialog = dialog,
        skipNextTime = skipNextTime,
        onSkipChange = { skipNextTime = it },
        onConfirm = {
            pending.value = null
            haptics.tap()
            if (dialog.allowSkip && skipNextTime) {
                scope.launch {
                    Graph.displayPrefs(context).setEbikeUnlockConfirm(false)
                    showNotice(
                        scope,
                        snackbar,
                        "以后开锁不再弹这个确认；在「快趣出行设置 → 开锁与还车」里可随时恢复",
                        NoticeTone.Info,
                    )
                }
            }
            when (action) {
                KvcxAction.UNLOCK -> carNum?.let(kvcx::unlock)
                KvcxAction.RETRY_UNLOCK -> kvcx.retryUnlock()
                KvcxAction.RESUME -> kvcx.resumeRide()
                KvcxAction.LOCK -> kvcx.tempLock()
                KvcxAction.RETURN -> kvcx.returnBike()
                // 响铃寻车 / 锁状态查询不走二次确认闸，到不了这里
                KvcxAction.RING, KvcxAction.QUERY_LOCK -> Unit
            }
        },
        onDismiss = { pending.value = null },
        // 还车是花钱那一刻：确认键用错误色，与其它动作的主色区分开
        danger = action == KvcxAction.RETURN,
    )
}

/**
 * 用车确认弹窗（开锁 / 重试开锁 / 还车共用一份排版）。
 *
 * 正文是**编号要点**（规格与文案在 `KvcxConfirm` / `kvcxConfirmDialog`）：三件事各有各的
 * 责任，糊成一段谁都不看。长文配 `heightIn(max)` + `verticalScroll`，小屏或大字体档位下
 * 按钮不会被挤出可视区（这一条是 `ui-common.md` 对确认型弹窗的硬要求）。
 *
 * [skipNextTime] 只对 [KvcxConfirm.allowSkip] 的动作渲染（当前只有开锁）：勾上之后弹出一行
 * 说明——「免确认」意味着按一下就开始计费，这件事必须在用户做决定的那一刻讲清楚，
 * 而不是等他下次误触才发现。勾选框整行可点（48dp 交互区，与充值免责声明那份同一手法）。
 */
@Composable
private fun KvcxConfirmDialog(
    dialog: KvcxConfirm,
    skipNextTime: Boolean,
    onSkipChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(dialog.title) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                dialog.points.forEachIndexed { index, point ->
                    Row(modifier = Modifier.padding(bottom = 8.dp)) {
                        Text(
                            text = "${index + 1}.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.width(20.dp),
                        )
                        Text(text = point, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (dialog.allowSkip) {
                    Spacer(Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSkipChange(!skipNextTime) },
                    ) {
                        Checkbox(checked = skipNextTime, onCheckedChange = onSkipChange)
                        Text(
                            text = "以后开锁不再确认",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    AnimatedVisibility(visible = skipNextTime) {
                        Text(
                            text = "关掉后点「开锁」就直接发指令并开始计费；" +
                                "要恢复去「快趣出行设置 → 开锁与还车」打开「开锁前确认」。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = dialog.confirmLabel,
                    color = if (danger) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private const val DENIED_HINT = "已拒绝定位权限；可在系统设置里允许位置信息，或手动拖动地图找车"

/**
 * 动作区高度动画结束后的落位余量（毫秒）：动画本身是 [BAR_RISE_MS]，多留一点，
 * 让最后一帧的尺寸也落定再冻结地图的让位高度（见 RideScreen 里 mapReserveDp 的注释）。
 */
private const val BLOCK_SETTLE_MS = 60L

/**
 * 一个不进组合的可变格子：布局回调里记账用（RideScreen 的常驻块高度）。
 * **别换成 `mutableStateOf`**——每帧写状态会让整页跟着重组。
 */
private class DpHolder(var value: Float)

/**
 * 取一次定位并把镜头移过去。[silent] = 失败不弹提示（进页自动定位那条路用）。
 * 全程置 `locating`：接口最长等 8 秒，不告诉用户"在做事"的话他只会连点按钮。
 */
private fun locate(
    context: android.content.Context,
    viewModel: BikeMapViewModel,
    scope: kotlinx.coroutines.CoroutineScope,
    silent: Boolean,
    onIssue: suspend (String) -> Unit,
) {
    viewModel.setLocating(true)
    scope.launch {
        try {
            when (val result = BikeLocator.currentLocation(context)) {
                is LocateResult.Ok ->
                    viewModel.onLocated(result.lat, result.lng, animated = !silent)
                is LocateResult.Failed -> if (!silent) onIssue(result.message)
            }
        } finally {
            viewModel.setLocating(false)
        }
    }
}

/**
 * 标题栏的使用方式 chip（DESIGN §3.9）：**常驻可见**，一眼知道自己在哪一档。
 *
 * 骑行中变灰并给锁定图标：切换会让在案订单从界面消失，是最容易让人以为"车丢了 / 钱没了"
 * 的一刻，所以那一刻不让切（点它只给一句说明）。
 *
 * 竖向内边距 8dp（约 36dp 高，旧版 6dp 只有 32dp）：它决定主动作是什么，是全页点击最
 * 频繁的那一枚之一；再往里垫会把胶囊撑得过粗，触摸区大小与观感的折中就在这里。
 */
@Composable
private fun RideModeChip(
    mode: EbikeUseMode,
    locked: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (locked) {
                    scheme.onSurface.copy(alpha = 0.07f)
                } else {
                    scheme.primaryContainer
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (locked) {
            Icon(
                HugeIcons.Lock,
                contentDescription = null,
                tint = scheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.size(13.dp),
            )
        }
        Text(
            text = mode.label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (locked) {
                scheme.onSurface.copy(alpha = 0.5f)
            } else {
                scheme.onPrimaryContainer
            },
        )
        Icon(
            HugeIcons.ChevronDown,
            contentDescription = null,
            tint = if (locked) {
                scheme.onSurface.copy(alpha = 0.4f)
            } else {
                scheme.onPrimaryContainer
            },
            modifier = Modifier.size(14.dp),
        )
    }
}
