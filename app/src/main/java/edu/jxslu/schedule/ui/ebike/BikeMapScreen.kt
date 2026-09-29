package edu.jxslu.schedule.ui.ebike

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.domain.BikeCluster
import edu.jxslu.schedule.domain.BikeNearby
import edu.jxslu.schedule.domain.KqcxAuth
import edu.jxslu.schedule.domain.NearbyBike
import edu.jxslu.schedule.domain.capabilities
import edu.jxslu.schedule.ui.common.AppCard
import edu.jxslu.schedule.ui.common.AppCardRow
import edu.jxslu.schedule.ui.common.AppNoticeVisuals
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.AppPermissions
import edu.jxslu.schedule.ui.common.InlineNoticeRow
import edu.jxslu.schedule.ui.common.LoadingHint
import edu.jxslu.schedule.ui.common.NoticeTone
import edu.jxslu.schedule.ui.common.pinnedStatusBars
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import edu.jxslu.schedule.ui.theme.semanticColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.BatteryLow
import me.rerere.hugeicons.stroke.ChevronDown
import me.rerere.hugeicons.stroke.ChevronRight
import me.rerere.hugeicons.stroke.Crosshair
import me.rerere.hugeicons.stroke.MapsLocation02
import me.rerere.hugeicons.stroke.Navigation01
import me.rerere.hugeicons.stroke.Refresh
import me.rerere.hugeicons.stroke.ScooterElectric
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 「更新于」超过这个时长就把时间标成警告色，提示数据可能已经不准。 */
private const val STALE_AFTER_MS = 90_000L

/** 时间戳每 15 秒重算一次，够用来把「新鲜」翻成「可能过期」。 */
private const val CLOCK_TICK_MS = 15_000L

private val CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)

/** 用户拒了权限时的那一句话：既要说明去哪开，也要说明不给也能用。 */
private const val DENIED_HINT = "已拒绝定位权限；可在系统设置里允许位置信息，或手动拖动地图找车"

/**
 * 附近单车地图（DESIGN §3.9 / §4.23）。
 *
 * 结构：顶栏 → 地图（占满剩余高度）→ 底部车辆面板。
 * 地图**不套外层滚动容器**：拖动地图与滚动页面抢同一个竖直手势。
 *
 * 定位权限：进页时没授权就**申请一次**（进「附近单车」本来就是申请定位的语境），
 * 已经授权就静默定一次。已经被问过或被拒过之后不再自动弹，改由「定位」按钮触发——
 * 系统在用户拒绝两次后就静默拒绝，不看标记的话每次进页面都会白弹一句提示。
 *
 * 选中一辆车后把完整车号经 Activity Result 回传，由**发起这次跳转的那个**出码页回填并出码。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BikeMapScreen(
    onBack: () -> Unit = {},
    /** 选中一辆车：把车号交回给**发起这次跳转的那个出码页**（Activity Result）。 */
    onPicked: (String) -> Unit = {},
    viewModel: BikeMapViewModel = viewModel(
        factory = BikeMapViewModel.Factory(
            Graph.kqcxBikeClient(LocalContext.current),
            Graph.displayPrefs(LocalContext.current),
        ),
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // 本机用车（DESIGN §4.32）：与出码页共用 KvcxRideController——地图上能直接开锁、
    // 锁车、还车，也把「当前用车」画在地图上（官方小程序同款能力）。
    // **只在账号登录方式下露出**（2026-09-29）：小程序方式的地图只查车与图层，
    // 车行不出现「开锁」、地图上没有「当前用车」卡与标记，见 [EbikeUseMode]
    val kvcx by viewModel.kvcx.state.collectAsStateWithLifecycle()
    val kvcxLoggedIn by viewModel.kvcx.loggedIn.collectAsStateWithLifecycle()
    val useMode by viewModel.useMode.collectAsStateWithLifecycle()
    // 能力矩阵（DESIGN §3.9，唯一判据见 EbikeCapabilities）：地图上的用车入口只在
    // App 内用车那一档出现
    val caps = useMode.capabilities(loggedIn = kvcxLoggedIn, hasRide = kvcx.ride != null)
    // 一处收口：小程序方式下不认内存里残留的快趣订单，下面所有用车 UI 只看这个 ride
    val ride = if (caps.inAppRide) kvcx.ride else null
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val semantic = MaterialTheme.semanticColors
    val haptics = rememberAppHaptics()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // 用车动作的结果提示（开锁成功/失败、还车结算等）走页面 Snackbar
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is BikeMapEvent.Notice -> snackbar.showSnackbar(
                    AppNoticeVisuals(event.text, tone = event.tone),
                )
                // 开锁成功：补一次成功触感（与出码页同口径）
                BikeMapEvent.Unlocked -> haptics.success()
            }
        }
    }

    // 进页查一次骑行状态（仅账号登录方式、失败静默）：地图上要能看见「当前用车」。
    // **不轮询**——后续只由手动刷新与动作完成驱动。
    LaunchedEffect(Unit) { viewModel.queryRideQuietly() }

    // 本机用车确认弹窗（动作 + 车号）：写操作一律二次确认，文案与出码页共用一份
    var kvcxPending by remember { mutableStateOf<Pair<KvcxAction, String>?>(null) }
    // 写操作要定位（快趣按位置校验）：未授权先申请，授权后用户再点一次（与出码页同口径）
    val kvcxPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        if (!BikeLocator.hasPermission(context)) {
            scope.launch {
                snackbar.showSnackbar(
                    AppNoticeVisuals("没有定位权限，快趣无法校验位置", tone = NoticeTone.Warning),
                )
            }
        }
    }
    val requestKvcxAction: (KvcxAction, String) -> Unit = { action, carNum ->
        haptics.tap()
        if (!BikeLocator.hasPermission(context)) {
            kvcxPermissionLauncher.launch(AppPermissions.location.toTypedArray())
            scope.launch {
                snackbar.showSnackbar(
                    AppNoticeVisuals("本机用车需要定位权限（快趣按位置校验）", tone = NoticeTone.Warning),
                )
            }
        } else if (action == KvcxAction.LOCK) {
            // 临时锁车免二次确认（与出码页同口径，2026-09-28 用户拍板）
            viewModel.kvcx.tempLock()
        } else if (action == KvcxAction.RESUME) {
            // 解锁继续骑同理免确认（本来就在计费中）
            viewModel.kvcx.resumeRide()
        } else {
            kvcxPending = action to carNum
        }
    }

    /**
     * 一条带「去设置」动作的警告提示。
     *
     * 定位失败有一半是权限被永久拒绝（系统不再弹框），只说一句"没有权限"用户无处可去，
     * 给一个直达应用设置页的动作比让他自己翻设置快。
     */
    val notifyLocateIssue: suspend (String) -> Unit = { message ->
        val outcome = snackbar.showSnackbar(
            AppNoticeVisuals(
                message = message,
                actionLabel = "去设置",
                tone = NoticeTone.Warning,
            ),
        )
        if (outcome == SnackbarResult.ActionPerformed) openAppPermissionSettings(context)
    }

    // 标记配色跟着主题走；只在主题色变化时重算，别每帧新建一份
    val markerColors = remember(
        scheme.primary,
        semantic.warning,
        scheme.outline,
        scheme.onPrimary,
        scheme.onSurface,
    ) {
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
            // 围栏填充：要"一眼看出整片包裹"（仿官方小程序）又不能"压暗/太深"——深青 primary 加到
            // 0.32、以及提亮 30% 的青，真机上都嫌深（2026-09-27 多轮反馈），最后**换色系**：
            // 直接用浅蓝（材质蓝 300）配更深一档的蓝描边；品牌深青不往地图上套。
            fenceStroke = Color(0xFF3D8BEF).copy(alpha = 0.85f).toArgb(),
            fenceFill = Color(0xFF64B5F6).copy(alpha = 0.30f).toArgb(),
            rideMarker = scheme.primary.toArgb(),
            rideHalo = scheme.primary.copy(alpha = 0.22f).toArgb(),
            rideGlyph = scheme.onPrimary.toArgb(),
            // 还车点 / 禁停区图层：色相逐字对齐官方小程序（#333333 / #D7535D），
            // 只有透明度压淡了一档——官方那个 67% 填充会把底图与车标一起吃掉
            nogoStroke = Color(0xFF333333).copy(alpha = 0.45f).toArgb(),
            nogoFill = Color(0xFF333333).copy(alpha = 0.22f).toArgb(),
            spotStroke = Color(0xFFD7535D).copy(alpha = 0.9f).toArgb(),
            spotFill = Color(0xFFD7535D).copy(alpha = 0.26f).toArgb(),
            spotBadge = Color(0xFFD7535D).toArgb(),
            spotGlyph = Color(0xFFFFFFFF).toArgb(),
        )
    }

    // 「我的车」标记的坐标口径（2026-09-28）：**未锁 = 正在骑**，车就在你身边——服务端
    // 坐标是拉取那一刻的快照，骑出去几百米后标记还杵在原地，看着像"我的车丢了"；
    // 已锁（或锁状态未知）= 车停在某处，用车位坐标。没有自己的定位时一律退回车位坐标。
    val rideFollowsUser = ride?.locked == false && state.userLat != null && state.userLng != null
    val rideMarkerLat = if (rideFollowsUser) state.userLat else ride?.lat
    val rideMarkerLng = if (rideFollowsUser) state.userLng else ride?.lng

    val prefs = remember { Graph.displayPrefs(context) }

    // 面板高度的当前值放在 VM 状态里（BikeMapUiState.panelHeightDp）：局部 remember 首帧
    // 只能给默认值，DataStore 读回来时面板会跳一下，转屏还会再跳一次
    val density = LocalDensity.current.density

    // 点地图标记要滚到对应的卡片：列表滚动位置交给 LazyColumn 自己管
    val listState = rememberLazyListState()
    LaunchedEffect(state.focusNonce) {
        val key = state.focusKey ?: return@LaunchedEffect
        val index = state.clusters.indexOfFirst { it.key == key }
        if (index >= 0) listState.animateScrollToItem(index)
    }

    /**
     * 取一次定位并把镜头移过去。[silent] = 失败不弹提示（进页自动定位那条路用）。
     *
     * 全程置 `locating`：接口最长等 8 秒，不告诉用户"在做事"的话他只会连点按钮。
     */
    val locate: (Boolean) -> Unit = { silent ->
        viewModel.setLocating(true)
        scope.launch {
            try {
                when (val result = BikeLocator.currentLocation(context)) {
                    is LocateResult.Ok -> viewModel.onLocated(
                        result.lat,
                        result.lng,
                        // 进页面那一次直接落位，不滑；用户点按钮才缓动
                        animated = !silent,
                    )
                    is LocateResult.Failed -> if (!silent) {
                        notifyLocateIssue(result.message)
                    }
                }
            } finally {
                viewModel.setLocating(false)
            }
        }
    }

    // API 31+ 的对话框会分开问「精确 / 大致」，两个都申请，给哪个都够用（粗略坐标也能定位到那一片）
    var locationGranted by remember { mutableStateOf(BikeLocator.hasPermission(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        // 进状态是为了让下面的连续定位流跟着重启：授权后不用等下次进页蓝点就活了
        locationGranted = result.values.any { it }
        if (locationGranted) {
            locate(false)
        } else {
            scope.launch { notifyLocateIssue(DENIED_HINT) }
        }
    }

    // 进页三分支：已授权 → 静默定一次（失败不提示，留在校园中心）；
    // 没授权且从没问过 → 申请一次并落标记；问过 → 什么都不做，等用户点「定位」
    LaunchedEffect(Unit) {
        when {
            BikeLocator.hasPermission(context) -> locate(true)
            !prefs.ebikeLocationAsked.first() -> {
                prefs.setEbikeLocationAsked(true)
                permissionLauncher.launch(AppPermissions.location.toTypedArray())
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current

    // 回到页面时补查一次骑行状态：在别处（出码页 / 微信）开的车或还的车，回来时地图上的
    // 「当前用车」不能还是旧值。一次性查询，不是轮询；小程序方式下 VM 会直接跳过
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.queryRideQuietly()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 蓝点实时更新（DESIGN §3.9）：页面可见期间挂平台定位流，只挪蓝点与「距你」距离，
    // 不移镜头也不重查接口（那是「定位」按钮那次一次性定位与用户动作的职责）。
    // 失败静默：看得见的失败提示都长在一次性定位那条路上。
    // 退到后台 repeatOnLifecycle 会取消收集、注销系统定位监听，不在后台耗电。
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

    Scaffold(
        // 页面自己吃掉窗口底：面板底色要一直铺到屏幕底边，中间不能留系统栏那一条缝
        // （2026-09-23 之前用 Scaffold 默认的 inset，缝里会透出地图）。
        // 底部系统栏的净空改由面板内部用 navigationBarsPadding 让
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = pinnedStatusBars(),
                title = { Text("附近单车") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(HugeIcons.ArrowLeft01, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // 面板高度是**定值**（用户可拖把手改）：内容从「正在查附近的车」变成「二十个分组」时
            // 面板不长高、地图不被挤小。面板内部自己滚动，加载态与结果态的地图一模一样大。
            // 上限再被窗口比例压一道，窗口再矮也要给地图留三成
            // 窗口矮到连下限都容不下时（多窗口 / 分屏）以下限为准：
            // `coerceIn(min, max)` 在 min > max 时抛 IllegalArgumentException，
            // 而 `minOf` 算出来的上限正好会在那种窗口下小于 180
            val maxPanelDp = minOf(MAX_PANEL_HEIGHT_DP, maxHeight.value * PANEL_MAX_RATIO)
                .coerceAtLeast(MIN_PANEL_HEIGHT_DP)
            // 上限一变（转屏 / 分屏 / 改系统字号）就把状态也收进来：只夹渲染值的话，
            // 状态还停在超限的高度，拖动要从那里开始算，手指走一大截面板才动
            LaunchedEffect(maxPanelDp, state.panelHeightDp) {
                viewModel.clampPanelHeight(MIN_PANEL_HEIGHT_DP, maxPanelDp)
            }
            val panelHeight = state.panelHeightDp.coerceIn(MIN_PANEL_HEIGHT_DP, maxPanelDp).dp
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    OsmMapView(
                        clusters = state.clusters,
                        selectedKey = state.expandedKey,
                        userLat = state.userLat,
                        userLng = state.userLng,
                        rideLat = rideMarkerLat,
                        rideLng = rideMarkerLng,
                        zones = state.zones,
                        colors = markerColors,
                        camera = state.camera,
                        onCenterSettled = viewModel::onMapSettled,
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

                    // 当前用车卡：浮在地图下沿（面板之上），出现/消失走「上滑 + 淡入」。
                    // 用 AnimatedContent（普通函数）而不是 AnimatedVisibility：这里同时具备
                    // BoxScope 与 ColumnScope，两个作用域版本的 AnimatedVisibility 会撞解析
                    AnimatedContent(
                        targetState = ride,
                        transitionSpec = {
                            (fadeIn() + slideInVertically { it / 2 }) togetherWith
                                (fadeOut() + slideOutVertically { it / 2 })
                        },
                        contentAlignment = Alignment.BottomCenter,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        label = "rideCard",
                    ) { current -> current?.let {
                            MapRideCard(
                                ride = current,
                                fetchedAt = kvcx.fetchedAt,
                                busy = kvcx.busy,
                                unlockPending = kvcx.unlockPending,
                                followsUser = rideFollowsUser,
                                onFocus = {
                                    haptics.tap()
                                    rideMarkerLat?.let { lat ->
                                        rideMarkerLng?.let { lng -> viewModel.onFocusPoint(lat, lng) }
                                    }
                                },
                                onRefresh = {
                                    haptics.tap()
                                    viewModel.refreshRide()
                                },
                                onTempLock = {
                                    requestKvcxAction(KvcxAction.LOCK, current.carNum)
                                },
                                onResume = {
                                    requestKvcxAction(KvcxAction.RESUME, current.carNum)
                                },
                                onReturn = {
                                    requestKvcxAction(KvcxAction.RETURN, current.carNum)
                                },
                                onRetryUnlock = {
                                    requestKvcxAction(KvcxAction.RETRY_UNLOCK, current.carNum)
                                },
                            )
                        }
                    }

                    Column(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MapCircleButton(
                            icon = HugeIcons.Crosshair,
                            label = "定位到我的位置",
                            busy = state.locating,
                            onClick = {
                                haptics.tap()
                                if (BikeLocator.hasPermission(context)) {
                                    locate(false)
                                } else {
                                    // 与首次进页那条路同一份清单（AppPermissions.location），
                                    // 别再另立一个常量：漏改一处就是编译期直接挂
                                    permissionLauncher.launch(AppPermissions.location.toTypedArray())
                                }
                            },
                        )
                        // 默认中心是车最集中的那一片，拖远了回不来会让人卡在空地图上
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

                BikePanel(
                    state = state,
                    listState = listState,
                    modifier = Modifier.height(panelHeight),
                    onResize = { dragPx ->
                        // 往下拖 = 面板变矮。px 转 dp 后交给 VM 按当前值加增量夹取
                        // （不在这里读 state 快照：一帧多个事件时会丢位移）
                        viewModel.resizePanelBy(
                            deltaDp = -dragPx / density,
                            minDp = MIN_PANEL_HEIGHT_DP,
                            maxDp = maxPanelDp,
                        )
                    },
                    onResizeFinished = viewModel::persistPanelHeight,
                    onRefresh = {
                        haptics.tap()
                        viewModel.refresh()
                    },
                    onToggleOnlyAvailable = {
                        haptics.tap()
                        viewModel.setOnlyAvailable(!state.onlyAvailable)
                    },
                    onToggleOnlyOurCampus = {
                        haptics.tap()
                        viewModel.setOnlyOurCampus(!state.onlyOurCampus)
                    },
                    onResetToCampus = {
                        haptics.tap()
                        viewModel.onResetToCampus()
                    },
                    onClusterTap = { key ->
                        // 触感由 ClusterCard 的 AppCard 自带（与 BikeRow 同口径）——
                        // 这里再 tap 就会响两下（2026-09-24 用户反馈）
                        viewModel.onClusterTap(key)
                    },
                    onPick = { carNum ->
                        // 同上：BikeRow 是 AppCardRow，触感由卡片内部给
                        onPicked(carNum)
                    },
                    // 账号登录方式且已登录才给「开锁」；已有进行中订单时禁用
                    // （仓库层还有在案订单闸兜底）。小程序方式的车行只有「出码」
                    canUnlock = caps.directUnlock,
                    unlockEnabled = ride == null && kvcx.busy == null,
                    onUnlock = { carNum -> requestKvcxAction(KvcxAction.UNLOCK, carNum) },
                )
            }
        }
    }

    // 还车结果卡（与出码页同一张，共用 KvcxReturnDialog）；欠费时给「去微信结清」出路。
    // 只在账号方式下出现（小程序方式没有本机还车这个动作）
    if (caps.inAppRide) {
        kvcx.returnSummary?.let { summary ->
            KvcxReturnDialog(
                summary = summary,
                onDismiss = { viewModel.kvcx.dismissReturnSummary() },
                onSettle = {
                    haptics.tap()
                    openWechatForSettle(context) { message ->
                        scope.launch {
                            snackbar.showSnackbar(
                                AppNoticeVisuals(message, tone = NoticeTone.Info),
                            )
                        }
                    }
                },
            )
        }
    }

    // 本机用车确认（DESIGN §4.32）：与出码页**同一套文案与四道闸**（kvcxConfirmDialog）。
    // 地图上开车/还车只是入口不同，责任边界一模一样
    kvcxPending?.let { (action, carNum) ->
        val dialog = kvcxConfirmDialog(action, "车 $carNum") ?: return@let
        AlertDialog(
            onDismissRequest = { kvcxPending = null },
            title = { Text(dialog.first) },
            text = { Text(dialog.second) },
            confirmButton = {
                TextButton(onClick = {
                    kvcxPending = null
                    haptics.tap()
                    when (action) {
                        KvcxAction.UNLOCK -> viewModel.kvcx.unlock(carNum)
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
                TextButton(onClick = { kvcxPending = null }) {
                    Text("取消")
                }
            },
        )
    }
}

/**
 * 地图上的「当前用车」卡（DESIGN §3.9 / §4.32，2026-09-28）：开着车也能在地图上直接
 * 锁车 / 还车——官方小程序同款能力，旧版这张卡只能回出码页才有。
 *
 * 位置与出码页的骑行卡同源（[KvcxRideController]）；这里换成浮动在地图下沿的紧凑形态：
 * 车号 + 锁状态 + 已骑（本地走时）+ 费用 + 位置更新时间 + 两个动作。
 */
@Composable
private fun MapRideCard(
    ride: KqcxAuth.Ride,
    fetchedAt: Long,
    busy: KvcxAction?,
    unlockPending: Boolean,
    /** 标记是否并到了用户位置（未锁 = 正在骑）：说明文案跟着换。 */
    followsUser: Boolean,
    onFocus: () -> Unit,
    onRefresh: () -> Unit,
    onTempLock: () -> Unit,
    onResume: () -> Unit,
    onReturn: () -> Unit,
    onRetryUnlock: () -> Unit,
) {
    val elapsed = rememberRideElapsed(ride, fetchedAt)
    AppCard(
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Icon(
                HugeIcons.ScooterElectric,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = "车 ${ride.carNum}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            ride.locked?.let { locked ->
                Text(
                    text = if (locked) "已锁" else "未锁",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = onFocus,
                enabled = busy == null,
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    HugeIcons.Navigation01,
                    contentDescription = "在地图上定位到车",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(
                onClick = onRefresh,
                enabled = busy == null,
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    HugeIcons.Refresh,
                    contentDescription = "刷新骑行状态",
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "已骑 ${elapsed ?: "--"}",
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontFeatureSettings = "tnum",
                ),
            )
            ride.payMoneyCents?.takeIf { it > 0 }?.let { cents ->
                Text(
                    text = "¥%.2f".format(cents / 100.0),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            // 电量（0~100 才显示，与出码页骑行卡同口径）
            ride.batteryPercent?.takeIf { it in 1..100 }?.let { percent ->
                Text(
                    text = "电量 $percent%",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.weight(1f))
            // 位置是**拉取那一刻**的快照（不轮询）：写出来它有多新，别让用户以为车就在这；
            // 未锁时标记并到蓝点，说明也随之换成"随你"（不是车位坐标）
            Text(
                text = if (followsUser) "未锁 · 标记随你" else "位置 ${clockText(fetchedAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                unlockPending -> OutlinedButton(
                    onClick = onRetryUnlock,
                    enabled = busy == null,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (busy == KvcxAction.RETRY_UNLOCK) "重试中…" else "重试开锁")
                }
                // 本机锁的车要能在本机解锁（与出码页同口径）
                ride.locked == true -> OutlinedButton(
                    onClick = onResume,
                    enabled = busy == null,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (busy == KvcxAction.RESUME) "解锁中…" else "解锁继续骑")
                }
                else -> OutlinedButton(
                    onClick = onTempLock,
                    enabled = busy == null,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (busy == KvcxAction.LOCK) "锁车中…" else "临时锁车")
                }
            }
            Button(
                onClick = onReturn,
                enabled = busy == null,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (busy == KvcxAction.RETURN) "还车中…" else "还车")
            }
        }
    }
}

/**
 * 地图右上角的圆形浮层按钮（定位 / 回到校区）。
 *
 * [busy] 时把图标换成进度指示并停止响应点击：定位最长 8 秒，没有这个状态用户看不出来
 * 点没点上，只会接着点。
 */
@Composable
private fun MapCircleButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    busy: Boolean = false,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        enabled = !busy,
        onClick = onClick,
    ) {
        Box(
            modifier = Modifier
                .padding(10.dp)
                .size(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (busy) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    icon,
                    contentDescription = label,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** 跳到本应用的系统设置页：权限被永久拒绝后唯一还有用的去处（统一走 AppPermissions）。 */
private fun openAppPermissionSettings(context: Context) = AppPermissions.jumpAppDetails(context)
/**
 * 底部车辆面板：拖动把手 + 一行摘要 + 分组列表 + 免责声明。
 *
 * 四态：还没查过（加载）/ 查过没车（空）/ 失败（提示 + 保留上一次列表）/ 有数据。
 * 失败时**不清空列表**：留着上次那批车配一条提示，比清成空白有用。
 *
 * 列表用 [LazyColumn] 而不是 `Column` + `verticalScroll`：点地图标记要能滚到指定卡片，
 * 惰性列表有现成的 `animateScrollToItem`，手写滚动偏移得自己记录每一项的位置。
 * 高度由调用方定死，所以列表滚动不会带动地图。
 */
@Composable
private fun BikePanel(
    state: BikeMapUiState,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    onResize: (Float) -> Unit,
    onResizeFinished: () -> Unit,
    onRefresh: () -> Unit,
    onToggleOnlyAvailable: () -> Unit,
    onToggleOnlyOurCampus: () -> Unit,
    onResetToCampus: () -> Unit,
    onClusterTap: (String) -> Unit,
    onPick: (String) -> Unit,
    canUnlock: Boolean,
    unlockEnabled: Boolean,
    onUnlock: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    // 每 15 秒重算一次"现在"，用来把更新时间从新鲜翻成可能过期
    val now by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            delay(CLOCK_TICK_MS)
            value = System.currentTimeMillis()
        }
    }
    val stale = state.queried && state.updatedAtMillis > 0 &&
        now - state.updatedAtMillis > STALE_AFTER_MS

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(scheme.surface)
            // 底部系统栏的净空让在面板**内部**：底色因此一直铺到屏幕底边，
            // 手势条那一条不再是"面板之外"，也就不会有缝
            .navigationBarsPadding(),
    ) {
        PanelDragHandle(onResize = onResize, onResizeFinished = onResizeFinished)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when {
                        !state.queried -> "附近单车"
                        state.onlyAvailable -> "可用 ${state.bikeCount} 辆"
                        state.onlyOurCampus -> "本校 ${state.bikeCount} 辆"
                        else -> "附近 ${state.bikeCount} 辆"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = subtitleText(state),
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        state.loading -> scheme.onSurface.copy(alpha = 0.5f)
                        stale -> MaterialTheme.semanticColors.warning
                        else -> scheme.onSurface.copy(alpha = 0.5f)
                    },
                )
            }
            // 只看本校：快趣同时服务隔壁江西师大，不筛的话师大校园的车也会画进来
            FilterChip(
                selected = state.onlyOurCampus,
                onClick = onToggleOnlyOurCampus,
                label = { Text("只看本校") },
            )
            Spacer(Modifier.width(8.dp))
            // 只看可用的车：校园里总有几辆离线或电量见底的，混在列表里要一行行看状态
            FilterChip(
                selected = state.onlyAvailable,
                onClick = onToggleOnlyAvailable,
                label = { Text("只看可用") },
            )
            // 请求在飞的时候换成进度指示，与「定位」按钮同款反馈
            if (state.loading) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .size(20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        color = scheme.primary,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else {
                IconButton(onClick = onRefresh) {
                    Icon(
                        HugeIcons.Refresh,
                        contentDescription = "刷新",
                        tint = scheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        // 失败提示放在列表**外面**：它要一直看得见，而且放进来会打乱"分组在列表里的下标"
        // （点地图标记要按下标滚过去）
        state.failure?.let { failure ->
            InlineNoticeRow(
                message = BikeMapViewModel.failureText(failure),
                tone = NoticeTone.Warning,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                // 撑满面板剩下的高度：面板高度由调用方定死，内容多少都不影响它
                .weight(1f)
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                !state.queried -> item {
                    LoadingHint(
                        title = "正在查附近的车",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                    )
                }

                state.clusters.isEmpty() -> item {
                    EmptyState(
                        message = when {
                            state.onlyOurCampus && state.onlyAvailable ->
                                "这一带没有可用的本校车，可关掉「只看本校」或「只看可用」看看全部"
                            state.onlyOurCampus ->
                                "这一带没有本校的车，关掉「只看本校」看看全部"
                            state.onlyAvailable ->
                                "这一带没有可用的车，关掉「只看可用」看看全部"
                            else ->
                                "这一带暂时没有车，把地图拖到别处再看看"
                        },
                        onResetToCampus = onResetToCampus,
                    )
                }

                else -> items(state.clusters, key = { cluster -> cluster.key }) { cluster ->
                    ClusterCard(
                        cluster = cluster,
                        expanded = cluster.key == state.expandedKey,
                        distanceFromUser = state.distanceFromUser,
                        onClick = { onClusterTap(cluster.key) },
                        onPick = onPick,
                        canUnlock = canUnlock,
                        unlockEnabled = unlockEnabled,
                        onUnlock = onUnlock,
                        // 刷新后新数据进来时卡片平滑落位，而不是整列跳一下
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }

        Text(
            text = "地图车辆数据来自共享电单车运营方接口，可能延迟或不准，实际可用情况以小程序为准。" +
                "红色「P」与红框是还车点、灰块是禁停区（同样来自运营方接口，会滞后）。",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurface.copy(alpha = 0.45f),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

/**
 * 分组卡片：收起时一行，展开后在其下逐个列出该停车点的车。
 *
 * 展开/收起带动画：列表里突然多出十几行、下面的卡片整体跳一下，很难看清发生了什么。
 * 高亮用 [AppCard] 的 highlighted（点地图标记滚过来的那一条会亮）。
 */
@Composable
private fun ClusterCard(
    cluster: BikeCluster,
    expanded: Boolean,
    distanceFromUser: Boolean,
    onClick: () -> Unit,
    onPick: (String) -> Unit,
    canUnlock: Boolean,
    unlockEnabled: Boolean,
    onUnlock: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AppCard(
            onClick = onClick,
            onClickLabel = if (expanded) "收起该停车点" else "展开该停车点",
            highlighted = expanded,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    HugeIcons.ScooterElectric,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = cluster.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "${cluster.bikes.size} 辆 · " +
                            clusterDistanceText(cluster.nearestDistanceMeters, distanceFromUser) +
                            " · " + clusterStatusText(cluster),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                // 箭头转到朝下表示展开，转场比直接换图标顺眼
                val rotation by animateFloatAsState(
                    targetValue = if (expanded) 90f else 0f,
                    label = "clusterChevron",
                )
                Icon(
                    HugeIcons.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier
                        .size(16.dp)
                        .graphicsLayer { rotationZ = rotation },
                )
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
            exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
        ) {
            // 缩进一层：展开项属于上面那张卡，缩进比再加一道描边更省视觉噪音
            Column(
                modifier = Modifier.padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                cluster.bikes.forEach { bike ->
                    BikeRow(
                        bike = bike,
                        canUnlock = canUnlock,
                        unlockEnabled = unlockEnabled,
                        onClick = { onPick(bike.carNum) },
                        onUnlock = { onUnlock(bike.carNum) },
                    )
                }
            }
        }
    }
}


@Composable
private fun BikeRow(
    bike: NearbyBike,
    canUnlock: Boolean,
    unlockEnabled: Boolean,
    onClick: () -> Unit,
    onUnlock: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val lowBadge = bike.batteryLowBadge
    AppCardRow(
        onClick = onClick,
        onClickLabel = "用 ${bike.carNum} 生成二维码",
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    ) {
        if (lowBadge) {
            Icon(
                HugeIcons.BatteryLow,
                contentDescription = "电量低",
                tint = MaterialTheme.semanticColors.warning,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = bike.carNum,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                if (lowBadge) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "电量低",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.semanticColors.warning,
                    )
                }
            }
            Text(
                text = bikeInfoText(bike, scheme.onSurface.copy(alpha = 0.6f)),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurface.copy(alpha = 0.6f),
            )
        }
        Text(
            text = BikeNearby.formatDistance(bike.distanceMeters),
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurface.copy(alpha = 0.6f),
        )
        // 两个动作都是显式按钮：整行点击仍然 = 出码（老习惯不变），
        // 「开锁」只在登录快趣后出现——它是写操作，有计费后果
        TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 12.dp)) {
            Text("出码")
        }
        if (canUnlock) {
            FilledTonalButton(
                onClick = onUnlock,
                enabled = unlockEnabled,
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                Text("开锁")
            }
        }
    }
}

/**
 * 车辆那行小字：状态 · 电量 · 车型。电量低于档位就单独着色——扫列表时最先想看的就是它。
 *
 * 用 [buildAnnotatedString] 而不是拼字符串：只有电量那一段换色，其余跟着基准色走。
 */
@Composable
private fun bikeInfoText(bike: NearbyBike, baseColor: Color): AnnotatedString {
    val batteryColor = if (bike.batteryLow) {
        MaterialTheme.semanticColors.warning
    } else {
        baseColor
    }
    return buildAnnotatedString {
        append(bike.status.label)
        append(" · ")
        withStyle(SpanStyle(color = batteryColor)) { append(bike.batteryText) }
        if (bike.model.isNotBlank()) {
            append(" · ")
            append(bike.model)
        }
    }
}

/**
 * 空态：一句说明加一个出口。
 *
 * 只有一句话的话，用户站在一个没车的区域里没有下一步可做；「回到校区」是最短的那条路。
 */
@Composable
private fun EmptyState(message: String, onResetToCampus: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            textAlign = TextAlign.Center,
        )
        OutlinedButton(onClick = onResetToCampus) {
            Text("回到校区")
        }
    }
}

/**
 * 分组行里的距离文案，**带上参照点**。
 *
 * 不标参照点的话，「473 米」在定位之后是"离你"，拖一下地图就变成"离屏幕中心"了，
 * 而用户会一直按"离我"去读。两种都以文字说明，不靠猜。
 */
private fun clusterDistanceText(meters: Int, fromUser: Boolean): String =
    (if (fromUser) "距你 " else "距中心 ") + BikeNearby.formatDistance(meters)

/**
 * 面板顶上的拖动把手：捏住上下拖，改面板高度（地图跟着让位）。
 *
 * 用 [detectVerticalDragGestures] 而不是 `draggable`：这里只需要垂直方向，
 * 且要 1:1 跟手，不做吸附动画——拖动过程中任何动画都会让地图跟着抖。
 * 松手才落盘，拖动途中不写 DataStore。
 */
@Composable
private fun PanelDragHandle(onResize: (Float) -> Unit, onResizeFinished: () -> Unit) {
    // 回调走 rememberUpdatedState 再进 pointerInput：`pointerInput(Unit)` 的块只跑一次，
    // 闭包里捕获的会是首次组合那版的 lambda（它的 maxPanelDp 是首帧窗口高度算出来的）。
    // 窗口尺寸变了之后拖把手，夹取用的还是旧上限。
    val resize by rememberUpdatedState(onResize)
    val finished by rememberUpdatedState(onResizeFinished)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(22.dp)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = { finished() },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        resize(dragAmount)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(36.dp)
                .height(4.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)),
        )
    }
}

/**
 * 摘要行下方那句状态：刷新中 / 更新于几点（补全中） / 还没拿到数据。
 *
 * 2026-09-28 拆分：**中心结果落地就不再是"刷新中"**（拖动换地方时等待感主要来自那个圈），
 * 周围的撒点还在飞时只挂一句「正在补全周围…」——它只影响列表的完整度，不影响已看到的车。
 */
private fun subtitleText(state: BikeMapUiState): String = when {
    state.loading && state.queried -> "刷新中…"
    state.completing && state.updatedAtMillis > 0 ->
        "更新于 ${clockText(state.updatedAtMillis)} · 正在补全周围…"
    state.updatedAtMillis > 0 -> "更新于 ${clockText(state.updatedAtMillis)}"
    state.queried -> "尚未获取到数据"
    else -> "正在获取…"
}

/** 分组里有多少辆能骑，比逐个看状态省事。 */
private fun clusterStatusText(cluster: BikeCluster): String {
    val usable = cluster.bikes.count { it.available }
    return if (usable == cluster.bikes.size) {
        "全部可用"
    } else {
        "$usable 辆可用"
    }
}

