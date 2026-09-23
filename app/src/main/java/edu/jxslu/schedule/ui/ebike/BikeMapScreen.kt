package edu.jxslu.schedule.ui.ebike

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.domain.BikeCluster
import edu.jxslu.schedule.domain.BikeNearby
import edu.jxslu.schedule.domain.NearbyBike
import edu.jxslu.schedule.ui.common.AppCard
import edu.jxslu.schedule.ui.common.AppCardRow
import edu.jxslu.schedule.ui.common.AppNoticeVisuals
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.InlineNoticeRow
import edu.jxslu.schedule.ui.common.LoadingHint
import edu.jxslu.schedule.ui.common.NoticeTone
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import edu.jxslu.schedule.ui.theme.semanticColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ChevronDown
import me.rerere.hugeicons.stroke.ChevronRight
import me.rerere.hugeicons.stroke.Crosshair
import me.rerere.hugeicons.stroke.MapsLocation02
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

/** API 31+ 的对话框会分开问「精确 / 大致」，两个一起申请，给哪个都够用。 */
private val LOCATION_PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

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
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val semantic = MaterialTheme.semanticColors
    val haptics = rememberAppHaptics()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

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
        )
    }

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
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.any { it }) {
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
                permissionLauncher.launch(LOCATION_PERMISSIONS)
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
            val maxPanelDp = minOf(MAX_PANEL_HEIGHT_DP, maxHeight.value * PANEL_MAX_RATIO)
            val panelHeight = state.panelHeightDp
                .coerceIn(MIN_PANEL_HEIGHT_DP, maxPanelDp)
                .dp
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
                        colors = markerColors,
                        camera = state.camera,
                        onCenterChanged = viewModel::onCenterChanged,
                        onClusterTap = { key ->
                            haptics.tap()
                            viewModel.onClusterTap(key)
                        },
                        onCameraApplied = viewModel::onCameraApplied,
                        modifier = Modifier.fillMaxSize(),
                    )

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
                                    permissionLauncher.launch(LOCATION_PERMISSIONS)
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
                    onResetToCampus = {
                        haptics.tap()
                        viewModel.onResetToCampus()
                    },
                    onClusterTap = { key ->
                        haptics.tap()
                        viewModel.onClusterTap(key)
                    },
                    onPick = { carNum ->
                        haptics.tap()
                        onPicked(carNum)
                    },
                )
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

/** 跳到本应用的系统设置页：权限被永久拒绝后唯一还有用的去处。 */
private fun openAppPermissionSettings(context: Context) {
    try {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (_: Exception) {
        // 打不开就算了：提示本身的文案已经写清了手动路径
    }
}
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
    onResetToCampus: () -> Unit,
    onClusterTap: (String) -> Unit,
    onPick: (String) -> Unit,
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
                        message = if (state.onlyAvailable) {
                            "这一带没有可用的车，关掉「只看可用」看看全部"
                        } else {
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
                    )
                }
            }
        }

        Text(
            text = "地图车辆数据来自共享电单车运营方接口，可能延迟或不准，" +
                "实际可用情况以小程序为准。",
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
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
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
                    BikeRow(bike = bike, onClick = { onPick(bike.carNum) })
                }
            }
        }
    }
}


@Composable
private fun BikeRow(bike: NearbyBike, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    AppCardRow(
        onClick = onClick,
        onClickLabel = "用 ${bike.carNum} 生成二维码",
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = bike.carNum,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
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
        Spacer(Modifier.width(12.dp))
        Text(
            text = "出码",
            style = MaterialTheme.typography.labelLarge,
            color = scheme.primary,
        )
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

/** 摘要行下方那句状态：刷新中 / 更新于几点 / 还没拿到数据。 */
private fun subtitleText(state: BikeMapUiState): String = when {
    state.loading && state.queried -> "刷新中…"
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

private fun clockText(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalTime().format(CLOCK_FORMAT)
