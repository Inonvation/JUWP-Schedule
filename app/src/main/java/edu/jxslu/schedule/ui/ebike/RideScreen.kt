package edu.jxslu.schedule.ui.ebike

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
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
import edu.jxslu.schedule.domain.capabilities
import edu.jxslu.schedule.ui.common.AppNoticeVisuals
import edu.jxslu.schedule.ui.common.AppPermissions
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.NoticeTone
import edu.jxslu.schedule.ui.common.pinnedStatusBars
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import edu.jxslu.schedule.ui.theme.semanticColors
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
 * 骑行页的三种状态（DESIGN §3.9）。
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
 * 快趣出行 · 骑行页（DESIGN §3.9，唯一主页）。
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
    /** 待确认的写操作与它针对的车号（开锁用；其余动作不需要车号）。 */
    var pendingAction by remember { mutableStateOf<Pair<KvcxAction, String?>?>(null) }

    val phase = when {
        summary != null -> RidePhase.Settled
        riding -> RidePhase.Riding
        else -> RidePhase.Finding
    }
    /**
     * 列表要不要换成还车点：只有账号方式的骑行态。
     *
     * 小程序方式没有还车点图层（拉那一层要快趣账号的 token，见 `BikeMapViewModel.query`
     * 里的能力闸），换成还车点只会得到一屏"没有数据"，地图上的车还被清空——用户只是点了
     * 「打开微信扫一扫」，回来却像走错了页。那一档保留车辆列表，还车点的事写在动作区。
     */
    val showSpots = phase == RidePhase.Riding && caps.inAppRide

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
        } else {
            pendingAction = action to carNum
        }
    }
    val withNotificationPermission = rememberNotificationPermissionGate()

    // 内置相机扫车身码（账号方式）：识别后**回填车号 + 换成车辆卡**，不自动开锁——写操作仍走确认闸
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val carNum = result.contents?.let(EbikeQr::parseScannedCarNum)
        if (carNum == null) {
            showNotice(scope, snackbar, "未识别到有效车号，请对准车身上的二维码", NoticeTone.Warning)
        } else {
            haptics.tap()
            ebikeViewModel.onCarInput(carNum)
            pickedCar = carNum
            viewModel.focusCar(carNum)
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
     * 页面底部常驻块（动作区 + 免责）的实际高度，量出来才敢用它算面板上限——它随状态变
     * （骑行态最高）。先给个估值，量到了再纠正，避免首帧把面板限错。
     *
     * 提到 Scaffold 之外还有一个用处：Snackbar 默认贴在窗口最底，正好压住主动作
     * （骑行态的提示一来就看不见「还车」）。这里量出来的高度让它整个抬到常驻块上方。
     */
    var bottomBlockDp by remember(phase) {
        mutableStateOf(if (phase == RidePhase.Riding) 250f else 120f)
    }

    Scaffold(
        // 页面自己吃掉窗口底：动作条底色要一直铺到屏幕底边
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = pinnedStatusBars(),
                title = { Text("骑行") },
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
                            contentDescription = "骑行设置",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                        )
                    }
                },
            )
        },
        snackbarHost = {
            AppSnackbarHost(snackbar, Modifier.padding(bottom = bottomBlockDp.dp))
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
                maxHeight.value * PANEL_MAX_RATIO - bottomBlockDp,
            ).coerceAtLeast(MIN_PANEL_HEIGHT_DP)
            LaunchedEffect(maxPanelDp, state.panelHeightDp) {
                viewModel.clampPanelHeight(MIN_PANEL_HEIGHT_DP, maxPanelDp)
            }
            val panelHeight = state.panelHeightDp.coerceIn(MIN_PANEL_HEIGHT_DP, maxPanelDp).dp

            // 地图在上、面板与动作区在下（**不是覆盖**）：动作区展开时地图跟着让位。
            // 覆盖式布局会把「我的位置」压在动作区底下——地图中心即用户位置
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
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
                            bottomBlockDp = coords.size.height / density
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
                        spotsHint = if (caps.wechatScan) MINI_SPOTS_HINT else null,
                        summary = summary,
                        hasCode = hasCode,
                        onDismissPicked = { pickedCar = null },
                        onWechatScan = wechatScanPlain,
                        onScanBodyCode = scanBodyCode,
                        onOpenCarNumber = openCarNumberSheet,
                        onLogin = openAccount,
                        onUnlock = { carNum -> requestKvcxAction(KvcxAction.UNLOCK, carNum) },
                        onGenerateForCar = generateForCar,
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

    // 本机用车确认（写操作二次确认；文案唯一出处 kvcxConfirmDialog）
    pendingAction?.let { (action, carNum) ->
        val carLabel = carNum?.let { "车 $it" }
            ?: ride?.carNum?.let { "车 $it" }
            ?: "选中的车"
        val dialog = kvcxConfirmDialog(action, carLabel)
        if (dialog == null) {
            pendingAction = null
        } else {
            AlertDialog(
                onDismissRequest = { pendingAction = null },
                title = { Text(dialog.first) },
                text = { Text(dialog.second) },
                confirmButton = {
                    TextButton(onClick = {
                        pendingAction = null
                        haptics.tap()
                        when (action) {
                            KvcxAction.UNLOCK -> carNum?.let(viewModel.kvcx::unlock)
                            KvcxAction.RETRY_UNLOCK -> viewModel.kvcx.retryUnlock()
                            KvcxAction.RESUME -> viewModel.kvcx.resumeRide()
                            KvcxAction.LOCK -> viewModel.kvcx.tempLock()
                            KvcxAction.RETURN -> viewModel.kvcx.returnBike()
                        }
                    }) {
                        Text(
                            text = dialog.third,
                            color = if (action == KvcxAction.RETURN) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingAction = null }) { Text("取消") }
                },
            )
        }
    }
}

/**
 * 小程序方式骑行态的说明（还车点图层要快趣账号的 token，见 `BikeMapViewModel.query`）。
 *
 * 这一档的列表与地图都留在"附近的车"上，还车点的事只能让用户去微信里看，
 * 所以这里要说清"看不到"是设计如此，而不是这一带没有。
 */
private const val MINI_SPOTS_HINT =
    "小程序方式不显示还车点图层（那一层要快趣账号）；还车点可以在微信小程序里看。"

private const val DENIED_HINT = "已拒绝定位权限；可在系统设置里允许位置信息，或手动拖动地图找车"

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
