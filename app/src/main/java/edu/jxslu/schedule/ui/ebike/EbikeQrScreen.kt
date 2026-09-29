package edu.jxslu.schedule.ui.ebike

import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.SubpageActivity
import edu.jxslu.schedule.SubpageRequest
import edu.jxslu.schedule.SubpageScreen
import edu.jxslu.schedule.domain.EbikeFreeRide
import edu.jxslu.schedule.domain.EbikeQr
import edu.jxslu.schedule.domain.EbikeCapabilities
import edu.jxslu.schedule.domain.EbikeUseMode
import edu.jxslu.schedule.domain.capabilities
import edu.jxslu.schedule.domain.KqcxAuth
import edu.jxslu.schedule.openSubpageForResult
import edu.jxslu.schedule.startActivityOutsideApp
import edu.jxslu.schedule.ui.common.AppCard
import edu.jxslu.schedule.ui.common.AppCardRow
import edu.jxslu.schedule.ui.common.AppNoticeVisuals
import edu.jxslu.schedule.ui.common.AppPermissions
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.InlineNoticeRow
import edu.jxslu.schedule.ui.common.NoticeTone
import edu.jxslu.schedule.ui.common.SettingChoiceRow
import edu.jxslu.schedule.ui.common.SettingItem
import edu.jxslu.schedule.ui.common.SettingSwitchRow
import edu.jxslu.schedule.ui.common.SettingsSection
import edu.jxslu.schedule.ui.common.pinnedStatusBars
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import edu.jxslu.schedule.ui.theme.semanticColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ChevronDown
import me.rerere.hugeicons.stroke.ChevronRight
import me.rerere.hugeicons.stroke.MapsLocation02
import me.rerere.hugeicons.stroke.QrCode
import me.rerere.hugeicons.stroke.QrCodeScan
import me.rerere.hugeicons.stroke.Refresh
import me.rerere.hugeicons.stroke.ScooterElectric
import me.rerere.hugeicons.stroke.UserAccount

/**
 * 共享单车出码页（DESIGN §3.9 / §4.32）。**两套布局由「使用方式」隔开**（[EbikeUseMode]，
 * 2026-09-29；两档都先摆「使用方式」卡，其余按各档顺序）：
 *
 * **微信小程序方式 = 旧版平铺**（承接 `origin/main` 的十一项）：附近单车地图入口整行卡 →
 * 车号输入 → 生成二维码（整宽）→ 出码位（**整宽方形**）→ 保存到相册 / 打开微信扫一扫 →
 * 免费时长计时条（**独立一行卡**，整行可点 = 结束骑行）→ 最近生成 → 出码设置 →
 * 免费时长提醒（含精确倒计时）→ 打开快趣出行 → 免责。App 内不做任何写操作。
 *
 * **账号登录方式 = 三段式**（2026-09-28 重排）：① 状态区「当前骑行」卡（本地免费倒计时 +
 * 快趣订单合并，锁车 / 还车 / 结束骑行都收在这里）→ ② 出码卡（车号输入 → 生成 / 直接开锁
 * → 出码位 240dp 居中 → 车号核对行 → 保存到相册；骑行中折叠成一行「给下一辆车出码」，
 * 点开即用；带车号进页自动展开）→ ③ 辅助区（最近生成 → 快趣账号 → 骑行设置 → 地图缓存 →
 * 官方入口 → 免责）。
 *
 * 两档的**能力**由 [edu.jxslu.schedule.domain.EbikeCapabilities] 判定（唯一判据，见
 * `EbikeUseMode.capabilities()`），页面只消费 `caps` 上的布尔量，**不要在别处再写一份 if**。
 *
 * 车号有两条进路：手输/粘贴，或从地图页选中一辆车（车号经 Activity Result 回传，
 * 见 [SubpageActivity.EXTRA_PICKED_CAR_NUM]，收到即出码）。两条路最后都走
 * [EbikeQr.resolveCarNum] + [EbikeQr.bikeUrl]，校验只有一处。
 *
 * 结果提示走页面 Snackbar（二级页窗口内无更高层弹层，不会穿透问题）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EbikeQrScreen(
    onBack: () -> Unit = {},
    /**
     * 进页即出码的车号（2026-09-24 加）：今日页快趣出行码卡「附近单车 ›」选车后的链路
     * ——地图页收起、车号经 `SubpageRequest.focusItemId` 带到这里，回填并直接生成二维码
     * （与从地图页选中后经 Activity Result 回传同走 [EbikeViewModel.onPickCarNum]）。
     */
    initialCarNum: String? = null,
    viewModel: EbikeViewModel = viewModel(
        factory = EbikeViewModel.Factory(Graph.displayPrefs(LocalContext.current)),
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val prefs by viewModel.ebikePrefs.collectAsStateWithLifecycle()
    /**
     * 使用方式（DESIGN §3.9 / §4.32，2026-09-29）：**本页所有能力判据的唯一来源**。
     * 小程序方式 = 出码 + 微信扫一扫开车（旧版形态）；账号登录方式 = App 内直接开锁 /
     * 锁车 / 还车（无「打开微信扫一扫」）。两套能力不混排，矩阵在
     * [edu.jxslu.schedule.domain.EbikeCapabilities]，页面只消费那里的布尔量。
     */
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val haptics = rememberAppHaptics()
    // 「结束骑行」二次确认弹窗（2026-09-22 用户口径：误触代价是提醒失效）
    var showEndConfirm by remember { mutableStateOf(false) }
    // 骑行中出码卡折叠成一行（2026-09-28）：骑行中不需要出码，换车时点开
    var qrExpanded by remember { mutableStateOf(false) }
    // 本地计时到点的信号（2026-09-28）：秒针 tick 只在骑行卡内部跑，到点回通知这里把卡片撤掉，
    // 免得留一张「免费剩余 0:00」的僵尸卡。remember 绑起点：换车重新计时自动复位。
    var timerExpired by remember(prefs.rideStartAt) { mutableStateOf(false) }
    // 地图缓存占用（2026-09-28）：「地图缓存」卡的统计值。进页量一次，回到本页（ON_RESUME）
    // 再量一次——瓦片是浏览地图时长的；清除走二次确认弹窗
    var cacheUsage by remember { mutableStateOf<EbikeMapCache.Usage?>(null) }
    var clearingCache by remember { mutableStateOf(false) }
    var showClearCache by remember { mutableStateOf(false) }
    // 快趣骑行状态 + 写动作（DESIGN §4.32）：与地图页共用同一份编排（KvcxRideController）
    val kvcx by viewModel.kvcx.state.collectAsStateWithLifecycle()
    val kvcxLoggedIn by viewModel.kvcx.loggedIn.collectAsStateWithLifecycle()
    // 能力矩阵（[edu.jxslu.schedule.domain.EbikeCapabilities]）：模式 + 登录态 + 有无在案订单
    // 算出本页能做什么，下面只消费这几个布尔量，不再散写 `if`
    val caps = prefs.useMode.capabilities(loggedIn = kvcxLoggedIn, hasRide = kvcx.ride != null)
    // 小程序方式下不认快趣订单（那是账号方式的能力）：切换使用方式后残留在内存里的订单
    // 状态不该在小程序方式的页面上冒出来——一处收口，下面的「当前骑行」卡与还车结果卡
    // 都只看这个 ride
    val ride = if (caps.inAppRide) kvcx.ride else null
    // 倒计时只看「有没有在案计时」，不再要求提醒开关开着（2026-09-28 用户口径：能软件开车的
    // 今天，开车后的免费计时要同步显示）。开关只管**通知**（闹钟 + 常驻倒计时通知），
    // 页面上的计时是事实展示，不该被一个通知偏好藏起来
    val timerActive = !timerExpired &&
        EbikeFreeRide.isActive(prefs.rideStartAt, System.currentTimeMillis())
    /** 骑行中：有快趣订单，或本地计时在案（普通用户点了扫一扫）。 */
    val riding = ride != null || timerActive
    // 快趣写操作要定位（官方按位置校验）：未授权时点按钮先走申请。
    // 与地图页同款 launcher；授权结果回来后用户再点一次（不自动续跑）
    val locationPermissionLauncher = rememberLauncherForActivityResult(
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
    // 本机用车确认弹窗（用户拍板：开锁 / 重试开锁 / 临时锁车 / 还车四类动作都要确认）
    var kvcxPendingAction by remember { mutableStateOf<KvcxAction?>(null) }
    // 本机用车入口：先保证定位授权（写操作硬前置），再按动作决定要不要二次确认
    val requestKvcxAction: (KvcxAction) -> Unit = { action ->
        haptics.tap()
        if (!BikeLocator.hasPermission(context)) {
            locationPermissionLauncher.launch(AppPermissions.location.toTypedArray())
            scope.launch {
                snackbar.showSnackbar(
                    AppNoticeVisuals("本机用车需要定位权限（快趣按位置校验）", tone = NoticeTone.Warning),
                )
            }
        } else if (action == KvcxAction.LOCK) {
            // 临时锁车**免二次确认**（2026-09-28 用户拍板）：订单与计费继续、随时可再解锁，
            // 误触代价低于多一次确认的打扰；「计费继续」由成功后的提示文案交代
            viewModel.kvcxTempLock()
        } else if (action == KvcxAction.RESUME) {
            // 解锁继续骑同理免确认：本来就在计费中，没有新增的计费后果
            viewModel.kvcxResumeRide()
        } else {
            kvcxPendingAction = action
        }
    }
    // 「通知使用权」是否已授予（DESIGN §3.9 精确倒计时）：进页读一次，从系统设置返回时
    // 再读一次——用户刚勾选完回来，未授权的提示行要立刻消失。
    var listenerGranted by remember {
        mutableStateOf(AppPermissions.notificationListenerGranted(context))
    }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    // 免费时长提醒走 App 通知（DESIGN §3.9，2026-09-24 由系统日历改回）：开开关 /
    // 点扫一扫那一刻申请通知权限（API 33+）。拒绝也照样续跑——计时与开关状态本身
    // 不依赖通知权限，只是提醒发不出来（提示由 VM 给）。
    var resumeAfterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        val resume = resumeAfterPermission
        resumeAfterPermission = null
        resume?.invoke()
    }
    // 有权限直接跑，缺权限先申请、授予后跑（与上课提醒同口径）
    fun withNotificationPermission(action: () -> Unit) {
        val needed = AppPermissions.missingNotification(context)
        if (needed.isEmpty()) {
            action()
        } else {
            resumeAfterPermission = action
            notificationPermissionLauncher.launch(needed.toTypedArray())
        }
    }
    // 地图页选中的车（DESIGN §3.9）：走 Activity Result，只回到**发起这次跳转的**这一页。
    // 不用进程级单例——那种通道会被任何一个还活着的出码页实例抢先消费，
    // 用户眼前这页反而收不到（2026-09-23 真机排查：退后台再进来必现）。
    // 骑行中选车必然是"换车"：把折叠的出码卡展开
    val mapLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val carNum = result.data?.getStringExtra(SubpageActivity.EXTRA_PICKED_CAR_NUM)
        if (!carNum.isNullOrBlank()) {
            viewModel.onPickCarNum(carNum)
            qrExpanded = true
        }
    }
    // 地图入口（2026-09-28 从整行卡降级为输入框尾图标）：点进去选车，车号带回本页即出码
    val openMap: () -> Unit = {
        haptics.tap()
        keyboard?.hide()
        focusManager.clearFocus()
        // 走 launcher 而不是 openSubpage：地图页要用 Activity Result 把选中的
        // 车号带回来，普通 startActivity 收不到（DESIGN §3.9 「选中」一行）。
        // 转场由 openSubpageForResult 补，跟其他二级页入口一致
        openSubpageForResult(
            context,
            mapLauncher::launch,
            SubpageRequest(SubpageScreen.EBIKE_MAP),
        )
    }
    // 出码动作与生成动作（两档共用同一份定义，见下面的「动作只写一次」注释）：
    // 每个动作在两个布局分支里都出现，**不许各写一份**——副本早晚漂移
    val generateCode: () -> Unit = {
        haptics.tap()
        keyboard?.hide()
        focusManager.clearFocus()
        viewModel.generate()
    }
    val saveToAlbum: () -> Unit = {
        haptics.tap()
        viewModel.saveCurrent()
    }
    val openWechatScanFlow: () -> Unit = {
        haptics.tap()
        withNotificationPermission {
            viewModel.onWechatScanClicked()
            openWechatScan(context) { message -> showNotice(scope, snackbar, message) }
        }
    }
    val openKvcooFlow: () -> Unit = {
        haptics.tap()
        openKvcoo(context) { message -> showNotice(scope, snackbar, message) }
    }
    val clearRecent: () -> Unit = {
        haptics.tap()
        viewModel.clearRecent()
    }

    LaunchedEffect(Unit) {
        // 进页核对一次：计时中缺服务/闹钟就补上，过期状态就清干净（幂等）
        EbikeFreeRideReminder.check(context)
        // 快趣骑行状态（DESIGN §4.32，A 档只读）：登录快趣时进页查一次，
        // 骑行中骑行卡显示订单信息；未登录/失败/无骑行都不打扰
        viewModel.queryKvcxRideQuietly()
        // 地图缓存占用（统计放 IO，不占首帧）
        cacheUsage = EbikeMapCache.measure(context)
    }

    // 带车号进页（今日页地图选车链路）：立即回填并出码（车号非法时 onPickCarNum 静默忽略）；
    // 骑行中带车进页必然是"换车"，把折叠的出码卡展开
    LaunchedEffect(initialCarNum) {
        if (!initialCarNum.isNullOrBlank()) {
            viewModel.onPickCarNum(initialCarNum)
            qrExpanded = true
        }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is EbikeEvent.Notice -> snackbar.showSnackbar(
                    AppNoticeVisuals(event.text, tone = event.tone),
                )
                // 开锁成功（等了几秒终于成了）：补一次成功触感
                EbikeEvent.Unlocked -> haptics.success()
            }
        }
    }

    // 扫完即焚的兜底触发点（DESIGN §3.9）：从微信/桌面回到 App（ON_RESUME）时尝试
    // 清掉已保存的二维码——计时中（免费时长未结束）不删，见 [EbikeViewModel.burnPending]。
    // DisposableEffect 组合提交晚于 ON_RESUME 的场景（冷启动恢复）
    // 用 isAtLeast(RESUMED) 兜底执行一次；pending 为空时 burnPending 是 no-op，天然幂等。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.burnPending()
                // 从「通知使用权」设置页回来：刷新授权状态
                listenerGranted = AppPermissions.notificationListenerGranted(context)
                // 骑行状态也补一次：刚在地图页开的车 / 刚在微信里还的车，回到本页时骑行卡
                // 不能还是进页那一刻的旧值（LaunchedEffect(Unit) 不会因为回前台重放）。
                // 仍然是一次性查询，不是轮询
                viewModel.queryKvcxRideQuietly()
                // 地图缓存占用：中途去地图页浏览会加瓦片，回来要重新统计才准
                scope.launch { cacheUsage = EbikeMapCache.measure(context) }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            viewModel.burnPending()
        }
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                windowInsets = pinnedStatusBars(),
                title = { Text("快趣出行码") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(HugeIcons.ArrowLeft01, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (caps.inAppRide) {
                // ══ 账号登录方式：三段式（2026-09-28 重排；DESIGN §3.9）══
                // ① 状态区（置顶）：本地计时与快趣订单合并；没有骑行时整块不出现。
                // 出现/消失走高度动画——骑行开始或结束的那一下不该让整页硬跳
                AnimatedVisibility(
                    visible = riding,
                    enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                    exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
                ) {
                    CurrentRideCard(
                        ride = ride,
                        rideFetchedAt = kvcx.fetchedAt,
                        busy = kvcx.busy,
                        unlockPending = kvcx.unlockPending,
                        timerActive = timerActive,
                        timerStartAt = prefs.rideStartAt,
                        onTempLock = { requestKvcxAction(KvcxAction.LOCK) },
                        onResume = { requestKvcxAction(KvcxAction.RESUME) },
                        onReturn = { requestKvcxAction(KvcxAction.RETURN) },
                        onRetryUnlock = { requestKvcxAction(KvcxAction.RETRY_UNLOCK) },
                        onRefresh = {
                            haptics.tap()
                            viewModel.refreshKvcxRide()
                        },
                        onEndRide = { showEndConfirm = true },
                        onTimerExpired = { timerExpired = true },
                    )
                }

                // ② 出码卡：骑行中折叠成一行（点开即用）。卡 ↔ 折叠行之间走「尺寸渐变 + 淡入淡出」
                // 过渡；首次组合不播动画（从微信回到本页时，首帧就该是收好的样子）
                AnimatedContent(
                    targetState = riding && !qrExpanded,
                    transitionSpec = {
                        (fadeIn(animationSpec = tween(180)) togetherWith
                            fadeOut(animationSpec = tween(120)))
                            .using(SizeTransform(clip = false))
                    },
                    label = "qrSection",
                ) { collapsed ->
                    if (collapsed) {
                        QrCollapsedRow(onClick = {
                            haptics.tap()
                            qrExpanded = true
                        })
                        return@AnimatedContent
                    }
                    QrCard(
                        state = state,
                        wechatScan = caps.wechatScan,
                        canUnlock = caps.directUnlock,
                        busy = kvcx.busy,
                        scoreAuthRequired = caps.directUnlock && kvcx.scoreAuthRequired,
                        collapsible = riding,
                        onCollapse = {
                            haptics.tap()
                            qrExpanded = false
                        },
                        onCarInput = viewModel::onCarInput,
                        onGenerate = generateCode,
                        onOpenMap = openMap,
                        onSave = saveToAlbum,
                        onWechatScan = openWechatScanFlow,
                        onWechatScanFallback = {
                            haptics.tap()
                            openWechatScan(context) { message -> showNotice(scope, snackbar, message) }
                        },
                        onUnlock = { requestKvcxAction(KvcxAction.UNLOCK) },
                    )
                }

                // ③ 辅助区：最近生成 → 快趣账号 → 骑行设置 → 地图缓存 → 官方入口 → 免责
                RecentChips(
                    recentIds = prefs.recentIds,
                    currentInput = state.carInput,
                    onPick = viewModel::onPickRecent,
                    onClear = clearRecent,
                )

                KvcxAccountRow(
                    loggedIn = kvcxLoggedIn,
                    onClick = { SubpageActivity.start(context, SubpageScreen.KVCX) },
                )

                // 使用方式（DESIGN §3.9 / §4.32）：**本页能力的总开关**，两档共用同一份。
                // 摆在这里而不是页顶：它是**设置项**（用户口径「在对应设置中切换」），
                // 页顶要留给主任务（出码 / 当前骑行），别让一个低频开关占掉首屏
                UseModeSection(
                    mode = prefs.useMode,
                    onSelect = { mode ->
                        haptics.tap()
                        scope.launch { Graph.displayPrefs(context).setEbikeUseMode(mode) }
                    },
                )

                RideSettingsCard(
                    prefs = prefs,
                    caps = caps,
                    listenerGranted = listenerGranted,
                    onFreeReminderChanged = viewModel::onFreeReminderChanged,
                    onReminderPermissionGranted = viewModel::onReminderPermissionGranted,
                    withNotificationPermission = ::withNotificationPermission,
                )

                MapCacheCard(
                    usage = cacheUsage,
                    onClear = {
                        if (cacheUsage?.isEmpty == true) {
                            showNotice(scope, snackbar, "暂无缓存可清除", NoticeTone.Info)
                        } else {
                            showClearCache = true
                        }
                    },
                )

                OpenKvcooButton(onClick = openKvcooFlow)

                EbikeDisclaimer()
            } else {
                // ══ 微信小程序方式：旧版平铺布局（承接 origin/main 的十一项；DESIGN §3.9）══
                // 用车方式回到旧版：出码 → 打开微信扫一扫（顺带起免费计时）→ 计时条；
                // 没有任何 App 内写操作（无「直接开锁」、无骑行卡、无账号行、无本机记录）。
                // 地图入口仍是整行卡（账号方式那版降级成了输入框尾图标）。
                AppCardRow(
                    onClick = openMap,
                    onClickLabel = "打开附近单车地图",
                ) {
                    Icon(
                        HugeIcons.MapsLocation02,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "附近单车地图",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "在地图上看车在哪，点一下自动填车号",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                    Icon(
                        HugeIcons.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        modifier = Modifier.size(16.dp),
                    )
                }

                // 输入区。前缀与示例占位一律用 outline 灰——2026-09-22 真机反馈：
                // 默认色读起来像"已经帮填好了"，置灰后一眼可辨是提示。
                // 前缀是动态的（`EbikeQr.inputPrefix`）：输成完整车号后它自己消失，
                // 免得选中的是别的校区的车却顶着 `100000` 的前缀
                val hintGray = MaterialTheme.colorScheme.outline
                val prefixText = EbikeQr.inputPrefix(state.carInput)
                OutlinedTextField(
                    value = state.carInput,
                    onValueChange = viewModel::onCarInput,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("车身号") },
                    prefix = if (prefixText.isEmpty()) null else {
                        { Text(prefixText, color = hintGray) }
                    },
                    placeholder = { Text("669", color = hintGray) },
                    supportingText = { Text(EbikeQr.INPUT_HINT) },
                    isError = state.inputError != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                )
                // 输入校验行内提示；非法车号不发事件，就地展示
                state.inputError?.let { error ->
                    InlineNoticeRow(message = error, tone = NoticeTone.Warning)
                }

                GenerateButton(
                    primary = EbikeQr.needsRegenerate(state.carInput, state.generatedBikeId),
                    onClick = generateCode,
                    modifier = Modifier.fillMaxWidth(),
                )

                // 出码区 + 车号核对行：与账号方式**同一块 240dp 码区**（2026-09-29 统一排版）。
                // 旧版那个整宽方形（390dp 机上 ≈361dp 高）会把「保存 / 扫一扫」顶出首屏，
                // 是 DESIGN §3.9 里早已作废的写法；核对行则防"改了车号却拿着旧码去扫"
                QrPanel(
                    bitmap = state.generatedBitmap,
                    bikeId = state.generatedBikeId,
                    emptyHint = "填好车号，点「生成二维码」\n再用微信「扫一扫」即可开车",
                )
                if (state.generatedBitmap != null) {
                    val generated = state.generatedBikeId.orEmpty()
                    val otherCar = EbikeQr.resolveCarNum(state.carInput)?.takeIf { it != generated }
                    Text(
                        text = if (otherCar == null) {
                            "车 $generated"
                        } else {
                            "车 $generated · 输入是 $otherCar，点「生成二维码」更新"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (otherCar == null) {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                        } else {
                            MaterialTheme.semanticColors.warning
                        },
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // 用码动作：与账号方式同一套主次——扫一扫是目标（实心）、保存是手段（描边）。
                // 常显，未出码时置灰不可点（按钮整行出现或消失会顶动布局）
                val hasCode = state.generatedBitmap != null
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = openWechatScanFlow,
                        enabled = hasCode,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            HugeIcons.QrCodeScan,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("打开微信扫一扫")
                    }
                    OutlinedButton(
                        onClick = saveToAlbum,
                        enabled = hasCode,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("保存到相册")
                    }
                }

                // 免费时长计时条：独立一行卡，整行可点 = 结束骑行。
                // 可见性沿用 2026-09-28 的口径（有在案计时就显示，不被提醒开关藏起来）——
                // 那是「一个通知偏好不该把事实藏起来」的修正，与页面形状无关
                AnimatedVisibility(
                    visible = timerActive,
                    enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                    exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
                ) {
                    LegacyFreeRideTimerBar(
                        startAtMillis = prefs.rideStartAt,
                        onEnd = { showEndConfirm = true },
                    )
                }

                // 辅助区：与账号方式同一套卡片（最近生成 / 骑行设置 / 地图缓存 / 官方入口 / 免责）
                RecentChips(
                    recentIds = prefs.recentIds,
                    currentInput = state.carInput,
                    onPick = viewModel::onPickRecent,
                    onClear = clearRecent,
                )

                // 使用方式（DESIGN §3.9 / §4.32）：**本页能力的总开关**，两档共用同一份。
                // 摆在这里而不是页顶：它是**设置项**（用户口径「在对应设置中切换」），
                // 页顶要留给主任务（出码 / 当前骑行），别让一个低频开关占掉首屏
                UseModeSection(
                    mode = prefs.useMode,
                    onSelect = { mode ->
                        haptics.tap()
                        scope.launch { Graph.displayPrefs(context).setEbikeUseMode(mode) }
                    },
                )

                RideSettingsCard(
                    prefs = prefs,
                    caps = caps,
                    listenerGranted = listenerGranted,
                    onFreeReminderChanged = viewModel::onFreeReminderChanged,
                    onReminderPermissionGranted = viewModel::onReminderPermissionGranted,
                    withNotificationPermission = ::withNotificationPermission,
                )

                MapCacheCard(
                    usage = cacheUsage,
                    onClear = {
                        if (cacheUsage?.isEmpty == true) {
                            showNotice(scope, snackbar, "暂无缓存可清除", NoticeTone.Info)
                        } else {
                            showClearCache = true
                        }
                    },
                )

                OpenKvcooButton(onClick = openKvcooFlow)

                EbikeDisclaimer()
            }
        }
    }


    // 结束骑行确认（DESIGN §3.9）：误触 = 提醒失效，比直接清掉多一道闸
    if (showEndConfirm) {
        AlertDialog(
            onDismissRequest = { showEndConfirm = false },
            title = { Text("结束骑行？") },
            text = { Text("结束后将清空免费时长计时，并撤掉通知栏上的倒计时与提醒。") },
            confirmButton = {
                TextButton(onClick = {
                    showEndConfirm = false
                    haptics.tap()
                    viewModel.onEndRide()
                }) {
                    Text("结束骑行", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEndConfirm = false }) {
                    Text("继续骑行")
                }
            },
        )
    }

    // 还车结果卡（DESIGN §3.9）：时长 / 费用 / 结算状态——还车是花钱那一刻，一张卡说完。
    // 欠费时额外给「去微信结清」出路（我们不做支付，只能把人送到微信）。
    // 只在账号方式下出现：小程序方式根本没有本机还车这个动作
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

    // 清除地图缓存（2026-09-28 用户口径）：二次确认后才动。文案把"下次会重新下载"
    // 与"不影响账号/订单/车辆查询"都交代清楚——清缓存最容易被误解成"数据没了"
    if (showClearCache) {
        val usage = cacheUsage
        AlertDialog(
            onDismissRequest = { if (!clearingCache) showClearCache = false },
            title = { Text("清除地图缓存？") },
            text = {
                Text(
                    buildString {
                        append("将删除离线地图瓦片")
                        usage?.let { append("（${EbikeMapCache.sizeLabel(it.tileBytes)}）") }
                        append("与停车点 / 禁停区数据")
                        usage?.let { append("（${EbikeMapCache.sizeLabel(it.zoneBytes)}）") }
                        append("。下次打开「附近单车地图」会重新下载；不影响账号、订单与车辆查询。")
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        clearingCache = true
                        scope.launch {
                            val ok = EbikeMapCache.clear(context)
                            cacheUsage = EbikeMapCache.measure(context)
                            clearingCache = false
                            showClearCache = false
                            snackbar.showSnackbar(
                                AppNoticeVisuals(
                                    message = if (ok) "已清除地图缓存" else "缓存没清干净，请稍后重试",
                                    tone = if (ok) NoticeTone.Success else NoticeTone.Warning,
                                ),
                            )
                        }
                    },
                    enabled = !clearingCache,
                ) {
                    Text(
                        text = if (clearingCache) "清除中…" else "清除",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showClearCache = false },
                    enabled = !clearingCache,
                ) { Text("取消") }
            },
        )
    }

    // 本机用车确认（DESIGN §4.32 B/C 档）：四类动作各自文案，确认后才发请求。
    // 用户拍板：写操作（有计费后果）一律二次确认
    kvcxPendingAction?.let { action ->
        val carNumLabel =
            EbikeQr.resolveCarNum(state.carInput)?.let { "车 $it" } ?: "输入框中的车号"
        // 文案与地图页共用同一份（见 KvcxRideController.kt）：这是用户唯一一次看清
        // 「开锁起计费 / 还车可能有调度费」的机会，不许各写一份。
        // null = 该动作免确认（当前只有锁车，且它根本不会进到这里）
        val dialog = kvcxConfirmDialog(action, carNumLabel) ?: return@let
        AlertDialog(
            onDismissRequest = { kvcxPendingAction = null },
            title = { Text(dialog.first) },
            text = { Text(dialog.second) },
            confirmButton = {
                TextButton(onClick = {
                    kvcxPendingAction = null
                    haptics.tap()
                    when (action) {
                        KvcxAction.UNLOCK -> viewModel.kvcxUnlock(state.carInput)
                        KvcxAction.RETRY_UNLOCK -> viewModel.kvcxRetryUnlock()
                        KvcxAction.RESUME -> viewModel.kvcxResumeRide()
                        KvcxAction.LOCK -> viewModel.kvcxTempLock()
                        KvcxAction.RETURN -> viewModel.kvcxReturn()
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
                TextButton(onClick = { kvcxPendingAction = null }) {
                    Text("取消")
                }
            },
        )
    }

}

/**
 * 出码卡（DESIGN §3.9，2026-09-28 整合）：车号段（输入 + 出码 / 开锁动作）
 * 与码段（出码位 + 用码动作）收进一张描边卡，页面上不再散落四个顶层元素。
 *
 * 地图入口做成输入框的尾图标：它和手输是**同一个输入**的两种给法，不该再占一整行卡。
 * 「生成二维码」的主次由 [EbikeQr.needsRegenerate] 决定——输入与已出码车号不一致时才是主色，
 * 把"改了车号但码还是旧的那张"显性化。
 *
 * 2026-09-29 起按 [wechatScan] 分两套用码动作（见 [EbikeUseMode]）：小程序方式是
 * 「打开微信扫一扫」（实心）+「保存到相册」；账号方式去掉扫一扫，只留保存，
 * 车号段的「直接开锁」成为主任务。
 */
@Composable
private fun QrCard(
    state: EbikeUiState,
    /** 小程序方式：给「打开微信扫一扫」（账号方式去掉它，只留保存）。 */
    wechatScan: Boolean,
    canUnlock: Boolean,
    busy: KvcxAction?,
    /** 本次会话命中过 11035（支付分免押）：给说明与出路，别让用户反复白点。 */
    scoreAuthRequired: Boolean,
    collapsible: Boolean,
    onCollapse: () -> Unit,
    onCarInput: (String) -> Unit,
    onGenerate: () -> Unit,
    onOpenMap: () -> Unit,
    onSave: () -> Unit,
    onWechatScan: () -> Unit,
    onWechatScanFallback: () -> Unit,
    onUnlock: () -> Unit,
) {
    val hasCode = state.generatedBitmap != null
    val resolved = EbikeQr.resolveCarNum(state.carInput)
    val generatePrimary = EbikeQr.needsRegenerate(state.carInput, state.generatedBikeId)
    val hintGray = MaterialTheme.colorScheme.outline
    AppCard(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 骑行中点开时的标题行：折叠是骑行中的默认态，展开后给一个明确的收回去路
        if (collapsible) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "给下一辆车出码",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCollapse) { Text("收起") }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // 输入区。前缀与示例占位一律用 outline 灰——2026-09-22 真机反馈：
            // 默认色读起来像"已经帮填好了"，置灰后一眼可辨是提示。
            // 前缀是动态的（`EbikeQr.inputPrefix`）：输成完整车号后它自己消失，
            // 免得选中的是别的校区的车却顶着 `100000` 的前缀
            val prefixText = EbikeQr.inputPrefix(state.carInput)
            OutlinedTextField(
                value = state.carInput,
                onValueChange = onCarInput,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("车身号") },
                prefix = if (prefixText.isEmpty()) null else {
                    { Text(prefixText, color = hintGray) }
                },
                placeholder = { Text("669", color = hintGray) },
                supportingText = { Text("${EbikeQr.INPUT_HINT}；点右侧地图可选车") },
                isError = state.inputError != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = onOpenMap) {
                        Icon(
                            HugeIcons.MapsLocation02,
                            contentDescription = "从地图选车",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
            )
            // 输入校验行内提示；非法车号不发事件，就地展示
            state.inputError?.let { error ->
                InlineNoticeRow(message = error, tone = NoticeTone.Warning)
            }
        }

        // 车号段动作：生成（本地）与直接开锁（已登录且未骑行）并排——同一个输入的两种出路
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GenerateButton(
                primary = generatePrimary,
                onClick = onGenerate,
                modifier = if (canUnlock) Modifier.weight(1f) else Modifier.fillMaxWidth(),
            )
            if (canUnlock) {
                Button(
                    onClick = onUnlock,
                    enabled = resolved != null && busy == null,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (busy == KvcxAction.UNLOCK) "开锁中…" else "直接开锁")
                }
            }
        }
        if (canUnlock) {
            Text(
                text = if (resolved != null) {
                    "将用快趣账号为 车 $resolved 创建订单并开锁（从开锁起计费）"
                } else {
                    "输入车号后可本机直接开锁（1~3 位尾部或完整车号）"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        // 支付分授权的两段提示（DESIGN §4.32「11035 的硬约束」，2026-09-29）：
        // ① 未命中时先告知：部分账号（微信支付分免押）开锁要在微信里完成授权，
        //    并给出「联系客服关闭授权」这条唯一能让本机开锁端到端可用的出路；
        // ② 命中过 11035 后换成实打实的出路按钮，别让用户反复白点。
        // 两条都只在账号方式下出现（[canUnlock] 已经带了这个前提）
        if (canUnlock && !scoreAuthRequired) {
            Text(
                text = "若账号是微信支付分免押，开锁时需跳转微信完成支付分授权（微信限制，App 无法代做）；" +
                    "可联系快趣客服为账号关闭该授权，之后即可直接开锁。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        if (scoreAuthRequired && canUnlock) {
            // 支付分免押账号：开锁授权只能在微信里点（wxpayScoreUse 是微信客户端专属 API）
            InlineNoticeRow(
                message = "该账号为微信支付分免押：开锁需在微信内完成支付分授权（微信限制）。" +
                    "可联系快趣客服关闭该授权（之后本机直接开锁可用）；" +
                    "或用下方按钮去微信内完成授权 / 骑车。",
                tone = NoticeTone.Warning,
            )
            OutlinedButton(onClick = onWechatScanFallback, modifier = Modifier.fillMaxWidth()) {
                Text("去微信扫一扫")
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))

        // 出码位：固定占位，出码前也占满同一块高度；出码后下方补一行车号供核对
        QrPanel(
            bitmap = state.generatedBitmap,
            bikeId = state.generatedBikeId,
            // 占位文案说清这张码接下来给谁用，**且只说页面上真有的事**：小程序方式拿去微信扫；
            // 账号方式看有没有「直接开锁」（= 已登录且无在案订单）——没登录时不能提「开锁」，
            // 那按钮根本不在（2026-09-29 排版与文案核对时抓到）
            emptyHint = if (wechatScan) {
                "填好车号，点「生成二维码」\n再用微信「扫一扫」即可开车"
            } else if (canUnlock) {
                "填好车号，点「生成二维码」\n可保存到相册，或直接用上方「开锁」"
            } else {
                // 没登录时不提「开锁」（那按钮不在）；「登录后能干什么」由下面的
                // 「快趣账号」行交代，这里只留一行，免得三行文案在码区里折行
                "填好车号，点「生成二维码」\n保存到相册后可在微信扫"
            },
        )
        if (hasCode) {
            // 车号核对行：输入已经换成**另一辆合法车**而码还是旧的那张时，直接点破
            // （"改了车号但码没更新"是扫码扫错车的主要来源，见 EbikeQr.needsRegenerate）
            val generated = state.generatedBikeId.orEmpty()
            val otherCar = EbikeQr.resolveCarNum(state.carInput)?.takeIf { it != generated }
            Text(
                text = if (otherCar == null) {
                    "车 $generated"
                } else {
                    "车 $generated · 输入是 $otherCar，点「生成二维码」更新"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (otherCar == null) {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                } else {
                    MaterialTheme.semanticColors.warning
                },
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // 用码动作：常显，未出码时置灰不可点——按钮整行出现或消失会顶动布局。
        // 主次分明：扫一扫是目标（实心），保存是手段（描边）。
        // **小程序方式才有扫一扫**（账号方式的码只用于保存 / 分享，见 [EbikeUseMode]）：
        // 账号方式下保存独占整行，主任务在上方车号段的「直接开锁」
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (wechatScan) {
                Button(
                    onClick = onWechatScan,
                    enabled = hasCode,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(HugeIcons.QrCodeScan, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("打开微信扫一扫")
                }
            }
            OutlinedButton(
                onClick = onSave,
                enabled = hasCode,
                modifier = Modifier.weight(1f),
            ) {
                Text("保存到相册")
            }
        }
    }
}

/** 「生成二维码」按钮：主色（需要一次生成动作）与描边（备着再点一次）两态。 */
@Composable
private fun GenerateButton(
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (primary) {
        Button(onClick = onClick, modifier = modifier) { Text("生成二维码") }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) { Text("生成二维码") }
    }
}

/** 骑行中的出码入口（折叠态，DESIGN §3.9）：一行卡，点开即用。 */
@Composable
private fun QrCollapsedRow(onClick: () -> Unit) {
    AppCardRow(onClick = onClick, onClickLabel = "展开出码") {
        Icon(
            HugeIcons.QrCode,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "给下一辆车出码",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "当前骑行结束后再扫；点一下展开",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        Icon(
            HugeIcons.ChevronDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * 「使用方式」设置卡（DESIGN §3.9 / §4.32，2026-09-29）：本页两套能力的互斥开关。
 *
 * 摆在出码卡正下方——用户一进页就知道自己在哪一档、以及另一档是什么。
 * 两档的差异不只是文案，是**能力的全集**（见 [EbikeUseMode] 的表）：小程序方式不碰
 * 快趣账号、不做任何写操作；账号方式去掉「打开微信扫一扫」与微信通知校准。
 *
 * 选账号方式时把风险写在卡里（用户要求）：写操作有计费后果、非官方客户端、
 * 支付分免押账号还要跳微信授权（唯一能让本机开锁端到端可用的出路是找客服关授权）。
 *
 * **两处入口共用这一份**（出码页 + 快趣出行账号页）：口径只留一份，改文案只改这里。
 */
@Composable
internal fun UseModeSection(mode: EbikeUseMode, onSelect: (EbikeUseMode) -> Unit) {
    SettingsSection(
        title = "使用方式",
        subtitle = "两档互斥，随时可切。",
    ) {
        SettingChoiceRow(
            title = "用车方式",
            options = EbikeUseMode.entries.map { it.label },
            selectedIndex = EbikeUseMode.entries.indexOf(mode),
            onSelect = { index -> EbikeUseMode.entries.getOrNull(index)?.let(onSelect) },
            icon = HugeIcons.ScooterElectric,
        )
        Text(
            text = if (mode.isAccount) {
                "登录后在 App 内直接开锁 / 还车，从开锁起计费；出码与保存照常可用。"
            } else {
                "本页只出码与计时，开车、还车都在微信小程序里完成；App 内不做任何写操作。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        if (mode.isAccount) {
            // 风险提示（2026-09-29 用户要求）：账号方式的写操作有计费后果，且不是官方客户端
            InlineNoticeRow(
                message = "账号方式的用车动作由本 App 代你的快趣账号发起，开锁即计费；" +
                    "非官方客户端，订单与善后以快趣为准。",
                tone = NoticeTone.Warning,
            )
        }
    }
}

/**
 * 快趣账号行（DESIGN §4.32）：登录 / 骑行状态 / 退出的入口。
 * 未登录时副标题写清登录后能干什么——本机开锁能力的发现入口就在这一行。
 */
@Composable
private fun KvcxAccountRow(loggedIn: Boolean, onClick: () -> Unit) {
    AppCardRow(
        onClick = onClick,
        onClickLabel = if (loggedIn) "打开快趣账号" else "登录快趣",
    ) {
        Icon(
            HugeIcons.UserAccount,
            contentDescription = null,
            tint = if (loggedIn) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            },
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "快趣账号",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = if (loggedIn) {
                    "已登录 · 骑行状态与退出登录"
                } else {
                    "未登录 · 登录后可本机开锁与还车"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        Text(
            text = if (loggedIn) "管理" else "去登录",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(4.dp))
        Icon(
            HugeIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * 「当前骑行」卡（DESIGN §3.9 / §4.32，2026-09-28 合并）：本地免费倒计时与快趣骑行订单
 * 本来就是同一件事的两半，旧版分居两处、中间隔着整块码区。
 *
 * - 有快趣订单：车号 + 锁状态 + 已骑（本地秒表走时，不轮询）+ 当前费用；
 * - 有本地计时：免费剩余 + 进度条；**只有订单不在案时**才给「结束骑行」——订单在案时
 *   还车即结束计时，两个"结束"并存只会让用户点错；
 * - 动作：临时锁车 / 还车（各走确认弹窗 + 定位前置，口径不变）。
 */
@Composable
private fun CurrentRideCard(
    ride: KqcxAuth.Ride?,
    rideFetchedAt: Long,
    busy: KvcxAction?,
    unlockPending: Boolean,
    timerActive: Boolean,
    timerStartAt: Long,
    onTempLock: () -> Unit,
    onResume: () -> Unit,
    onReturn: () -> Unit,
    onRetryUnlock: () -> Unit,
    onRefresh: () -> Unit,
    onEndRide: () -> Unit,
    onTimerExpired: () -> Unit,
) {
    AppCard(
        highlighted = true,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (ride != null) {
            // 本地秒表（不联网，见 rememberRideElapsed）
            val elapsed = rememberRideElapsed(ride, rideFetchedAt)
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
                    onClick = onRefresh,
                    enabled = busy == null,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        HugeIcons.Refresh,
                        contentDescription = "刷新骑行状态",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(26.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                LabeledValue(label = "已骑", value = elapsed ?: "--")
                ride.payMoneyCents?.takeIf { it > 0 }?.let { cents ->
                    LabeledValue(label = "当前费用", value = "¥%.2f".format(cents / 100.0))
                }
                // 车辆电量（0~100 才显示：接口把「没有数据」写成 -1 这类值，别照收）
                ride.batteryPercent?.takeIf { it in 1..100 }?.let { percent ->
                    LabeledValue(label = "电量", value = "$percent%")
                }
                Spacer(Modifier.weight(1f))
                // 数据新鲜度：不轮询的前提下，得让用户知道这是什么时候的订单状态
                Text(
                    text = "更新于 ${clockText(rideFetchedAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
        }
        if (timerActive) {
            if (ride != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            }
            FreeRideBlock(
                startAt = timerStartAt,
                showEndAction = ride == null,
                onEnd = onEndRide,
                onExpired = onTimerExpired,
            )
        }
        if (ride != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    unlockPending -> OutlinedButton(
                        onClick = onRetryUnlock,
                        enabled = busy == null,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (busy == KvcxAction.RETRY_UNLOCK) "重试中…" else "重试开锁")
                    }
                    // 本机锁的车要能在本机解锁（2026-09-28）：与「重试开锁」同一条调用，文案不同
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
}

/** 卡内「小标签 + 数值」一对（骑行卡的已骑 / 当前费用）。 */
@Composable
private fun LabeledValue(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold,
                fontFeatureSettings = "tnum", // 等宽数字：每秒跳动不抖
            ),
        )
    }
}

/**
 * 免费时长倒计时块（DESIGN §3.9）：大号倒计时 + 细进度线 +（仅普通用户）「结束骑行」。
 *
 * 每秒自刷新（[produceState] 计时循环）；到点调 [onExpired] 让**父级**重组一次把卡撤掉
 * ——tick 只跑在这块里，整页不跟着每秒重组。进度与剩余一律走 [EbikeFreeRide] 的域函数，
 * 与纯逻辑测试同一口径。
 */
@Composable
private fun FreeRideBlock(
    startAt: Long,
    showEndAction: Boolean,
    onEnd: () -> Unit,
    onExpired: () -> Unit,
) {
    val now by produceState(
        initialValue = System.currentTimeMillis(),
        key1 = startAt,
    ) {
        while (true) {
            val tick = System.currentTimeMillis()
            value = tick
            if (!EbikeFreeRide.isActive(startAt, tick)) {
                onExpired()
                break
            }
            delay(1_000L)
        }
    }
    val remaining = EbikeFreeRide.remainingSeconds(startAt, now)
    val progress = EbikeFreeRide.progressFraction(startAt, now)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = EbikeFreeRide.formatRemaining(remaining),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontFeatureSettings = "tnum", // 等宽数字：每秒跳动不抖
                ),
            )
            Text(
                text = "  免费剩余",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(bottom = 3.dp),
            )
            if (showEndAction) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onEnd) { Text("结束骑行") }
            }
        }
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 5.dp),
            trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
        )
    }
}

@Composable
private fun RecentChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val background = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        fontWeight = if (selected) FontWeight.SemiBold else null,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/**
 * 出码位（DESIGN §3.9）：**固定方形、始终占位**。
 *
 * 旧版「没码就没有这一块」，点生成后整页往下跳一次（2026-09-22 用户反馈）；
 * 2026-09-28 从 `fillMaxWidth`（大屏 ≈361dp）压到 240dp 居中——全宽的方块会把
 * 「保存 / 扫一扫」顶出首屏，而这两个按钮正是出码后马上要用的。码图 1:1
 * （[EbikeQr.QR_SIZE_PX] 方图）与占位框同比，无二次形变。
 */
@Composable
private fun QrPanel(
    bitmap: Bitmap?,
    bikeId: String?,
    emptyHint: String,
    /**
     * 旧版平铺布局（微信小程序方式）用**整宽方块**；账号登录方式用 240dp 居中。
     * 2026-09-29 由使用方式决定，形状本身仍是「固定方形、始终占位」。
     */
    fullWidth: Boolean = false,
) {
    val shape = RoundedCornerShape(14.dp)
    val onSurface = MaterialTheme.colorScheme.onSurface
    val panel: @Composable (Modifier) -> Unit = { sizeModifier ->
        Box(
            modifier = sizeModifier
                .clip(shape)
                .background(
                    if (bitmap == null) {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                )
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "骑行二维码 · ${bikeId.orEmpty()}",
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        HugeIcons.ScooterElectric,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                        modifier = Modifier.size(40.dp),
                    )
                    Text(
                        text = emptyHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = onSurface.copy(alpha = 0.55f),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
    if (fullWidth) {
        panel(Modifier.fillMaxWidth().aspectRatio(1f))
    } else {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            panel(Modifier.size(240.dp))
        }
    }
}

/**
 * 免费时长计时条（旧版形态，微信小程序方式用，DESIGN §3.9）。
 *
 * 2026-09-28 之后账号方式把倒计时并进了置顶的「当前骑行」卡；小程序方式沿用旧版：
 * **独立一行卡**，整行可点 = 结束骑行（右侧写「结束骑行」），倒计时 + 细进度线。
 * 到点由 [produceState] 的循环自己退出，父级按 [EbikeFreeRide.isActive] 撤掉这一行。
 */
@Composable
private fun LegacyFreeRideTimerBar(startAtMillis: Long, onEnd: () -> Unit) {
    val remaining by produceState(
        initialValue = EbikeFreeRide.remainingSeconds(startAtMillis, System.currentTimeMillis()),
        key1 = startAtMillis,
    ) {
        while (EbikeFreeRide.isActive(startAtMillis, System.currentTimeMillis())) {
            value = EbikeFreeRide.remainingSeconds(startAtMillis, System.currentTimeMillis())
            delay(1_000L)
        }
    }
    val progress = EbikeFreeRide.progressFraction(startAtMillis, System.currentTimeMillis())
    AppCardRow(
        onClick = onEnd,
        onClickLabel = "结束骑行",
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = EbikeFreeRide.formatRemaining(remaining),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontFeatureSettings = "tnum", // 等宽数字：每秒跳动不抖
                    ),
                )
                Text(
                    text = "  免费剩余",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 5.dp),
                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
            )
        }
        Text(
            text = "结束骑行",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * 「最近生成」chips（DESIGN §3.9）：两档共用同一份。
 *
 * 8 个**完整车号**、点一下回填并直接出码；标题行右侧「清空」无二次确认（纯回填便利数据）。
 * 抽出来是为了两套布局只留一份——副本早晚漂移（`EbikeQr.chipLabel` / `isCurrentInput`
 * 的口径也只在这里消费）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecentChips(
    recentIds: List<String>,
    currentInput: String,
    onPick: (String) -> Unit,
    onClear: () -> Unit,
) {
    if (recentIds.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "最近生成",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClear) { Text("清空") }
        }
        // FlowRow 而非固定两行：字体放大档位下 3 位数字 chip 也可能放不下 4 个
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            recentIds.forEach { carNum ->
                RecentChip(
                    label = EbikeQr.chipLabel(carNum),
                    selected = EbikeQr.isCurrentInput(currentInput, carNum),
                    onClick = { onPick(carNum) },
                )
            }
        }
    }
}

/**
 * 「骑行设置」卡（两档共用）：出码两开关 + 免费时长提醒（含提前量）。
 *
 * 两档只有两处不同，都由参数决定，不各写一张卡：**免费时长提醒的副标题**（计时起点是
 * 「点扫一扫」还是「开锁成功」）与**精确倒计时是否出现**（`caps.wechatNoticeCalibration`）。
 * 账号方式下 `caps` 已经把它关掉，所以那一档天然看不到这一行。
 */
@Composable
private fun RideSettingsCard(
    prefs: EbikePrefsSnapshot,
    caps: EbikeCapabilities,
    listenerGranted: Boolean,
    onFreeReminderChanged: () -> Unit,
    onReminderPermissionGranted: () -> Unit,
    withNotificationPermission: (() -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberAppHaptics()
    var autoSaveChecked by remember(prefs.autoSave) { mutableStateOf(prefs.autoSave) }
    var burnChecked by remember(prefs.burnAfterScan) { mutableStateOf(prefs.burnAfterScan) }
    var freeEnabled by remember(prefs.freeReminderEnabled) {
        mutableStateOf(prefs.freeReminderEnabled)
    }
    SettingsSection(
        title = "骑行设置",
        subtitle = "只影响本页的出码、保存与计时提醒，与登录状态无关。",
    ) {
        SettingSwitchRow(
            title = "生成后自动保存到相册",
            subtitle = "开启后每次出码即存入相册「水贝贝」",
            checked = autoSaveChecked,
            onCheckedChange = { checked ->
                autoSaveChecked = checked
                scope.launch { Graph.displayPrefs(context).setEbikeAutoSave(checked) }
            },
        )
        SettingSwitchRow(
            title = "骑完车自动删除",
            subtitle = "免费时长结束或手动结束骑行后，自动清除相册里的二维码",
            checked = burnChecked,
            onCheckedChange = { checked ->
                burnChecked = checked
                scope.launch { Graph.displayPrefs(context).setEbikeBurnAfterScan(checked) }
            },
        )
        // 免费时长提醒（DESIGN §3.9）：提前量 1~5 分钟（用户拍板默认 3）。
        // 2026-09-24 起提醒走 App 通知（前台服务常驻倒计时 + 两个精确闹钟），
        // 系统日历那条链路已整套删除
        SettingSwitchRow(
            title = "免费时长提醒",
            // 计时起点两档不同：小程序方式是「点打开微信扫一扫」这个准备动作
            // （比真正开车早 1~2 分钟），账号方式是服务端确认的开锁时刻
            subtitle = if (caps.wechatScan) {
                "点「打开微信扫一扫」后起常驻倒计时，到点发通知提醒"
            } else {
                "开锁成功后起常驻倒计时，到点发通知提醒"
            },
            checked = freeEnabled,
            onCheckedChange = { checked ->
                freeEnabled = checked
                scope.launch {
                    Graph.displayPrefs(context).setEbikeFreeReminderEnabled(checked)
                    onFreeReminderChanged()
                }
                // 只有开启才要权限：关闭是「撤掉提醒」，不需要任何权限
                if (checked) {
                    withNotificationPermission { onReminderPermissionGranted() }
                }
            },
        )
        if (freeEnabled) {
            SettingChoiceRow(
                title = "提前量",
                subtitle = "免费结束前几分钟提醒；结束那一刻再提醒一次",
                options = listOf("1 分钟", "2 分钟", "3 分钟", "4 分钟", "5 分钟"),
                selectedIndex = prefs.freeLeadMinutes - EbikeFreeRide.LEAD_MIN,
                onSelect = { index ->
                    scope.launch {
                        Graph.displayPrefs(context)
                            .setEbikeFreeLeadMinutes(index + EbikeFreeRide.LEAD_MIN)
                        onFreeReminderChanged()
                    }
                },
            )

            // 「精确倒计时」（DESIGN §3.9，2026-09-24）：默认关，开启后要「通知使用权」。
            // 计时起点原本只能取「点打开微信扫一扫」的时刻（比真正开车早 1~2 分钟），
            // 开了它就能拿微信的租车成功通知把起点校准到真正开始计费那一刻；
            // 2026-09-28 起兼听完成通知，还车后自动结束计时。
            //
            // **只在微信小程序方式下出现**（2026-09-29）：这条链路存在的理由是「点扫一扫」
            // 这个起点不够准；账号方式的开锁时刻本来就是服务端确认的骑行开始，没有可校准的
            // 偏差，留着只会让用户去开一个用不上的高危权限。服务端侧也按能力拦了一道
            // （`WechatRentListener` 判 `wechatNoticeCalibration`）
            if (caps.wechatNoticeCalibration) {
                var preciseEnabled by remember(prefs.preciseCountdownEnabled) {
                    mutableStateOf(prefs.preciseCountdownEnabled)
                }
                SettingSwitchRow(
                    title = "精确倒计时",
                    subtitle = "识别微信的先享后付通知：租车成功校准计时起点，还车后自动结束计时",
                    checked = preciseEnabled,
                    onCheckedChange = { checked ->
                        preciseEnabled = checked
                        scope.launch {
                            Graph.displayPrefs(context).setEbikePreciseCountdownEnabled(checked)
                        }
                        // 只有开启才要授权：关闭是「不再校准」，不需要任何权限。
                        // 通知使用权只能由用户去系统设置里勾选，没有弹框可申请。
                        if (checked && !AppPermissions.notificationListenerGranted(context)) {
                            AppPermissions.jumpNotificationListenerSettings(context)
                        }
                    },
                )
                // 开关开着但还没授权：功能不会生效，得让用户看得见（并且点得到设置页）
                if (preciseEnabled && !listenerGranted) {
                    InlineNoticeRow(
                        message = "还没授予通知使用权，精确倒计时不会生效",
                        tone = NoticeTone.Warning,
                    )
                    TextButton(onClick = {
                        haptics.tap()
                        AppPermissions.jumpNotificationListenerSettings(context)
                    }) {
                        Text("去开启通知使用权")
                    }
                }
            }
        }
    }
}

/**
 * 「地图缓存」卡（两档共用）：地图是两档共用的**最新**能力，缓存管理也就两档都要有
 * （旧版布局里没有这张卡，等于小程序方式的人看得到地图、却清不掉那 60 MB 瓦片）。
 */
@Composable
private fun MapCacheCard(usage: EbikeMapCache.Usage?, onClear: () -> Unit) {
    SettingsSection(
        title = "地图缓存",
        subtitle = "「附近单车地图」的离线瓦片与还车点 / 禁停区数据缓存；清除后下次打开重新下载，不影响车辆查询。",
    ) {
        SettingItem(
            title = when {
                usage == null -> "正在统计…"
                usage.isEmpty -> "暂无缓存"
                else -> "当前占用 ${EbikeMapCache.sizeLabel(usage.totalBytes)}"
            },
            subtitle = if (usage != null && !usage.isEmpty) {
                "离线瓦片 ${EbikeMapCache.sizeLabel(usage.tileBytes)} · " +
                    "停车点数据 ${EbikeMapCache.sizeLabel(usage.zoneBytes)}；点此清除"
            } else {
                "浏览地图时自动缓存，超过 60 MB 自动回收旧瓦片"
            },
            onClick = onClear,
        )
    }
}

/**
 * 「打开快趣出行」文字入口（两档共用）：内置地图已经能看车在哪，官方 App 不再是必经步骤，
 * 留一个文字入口给习惯用它的人。低频，所以沉到页底。
 */
@Composable
private fun OpenKvcooButton(onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text("打开快趣出行")
    }
}

/**
 * 免责声明（两档共用）：只说「数据来自运营方、非学校官方」这一件事。
 *
 * 写操作的责任边界**不在这里重复**——那是「使用方式」卡的风险行（账号方式才显示）与
 * 开锁确认弹窗的职责；页尾再抄一遍只会把这段小字堆成四行（2026-09-29 排版收敛）。
 */
@Composable
private fun EbikeDisclaimer() {
    Text(
        text = "非学校官方功能：二维码内容与地图车辆数据均来自共享电单车运营方，" +
            "最终以小程序加载结果为准。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
    )
}

/** 页内一次性提示（Snackbar）的统一出口：二级页窗口内没有更高层弹层，不会穿透。 */
private fun showNotice(
    scope: CoroutineScope,
    snackbar: SnackbarHostState,
    message: String,
    tone: NoticeTone = NoticeTone.Warning,
) {
    scope.launch { snackbar.showSnackbar(AppNoticeVisuals(message, tone = tone)) }
}

/**
 * 拉起微信「扫一扫」。入口按可靠性排序：
 * 1. `ShortCutDispatchAction` + `launch_type_scan_qrcode`——微信桌面长按「扫一扫」
 *    快捷方式的真身（`dumpsys shortcut com.tencent.mm` 实测），直达扫一扫相机页；
 * 2. `BIZSHORTCUT` + `LauncherUI.From.Scaner.Shortcut`——旧式快捷入口，部分版本
 *    只落微信首页（真机实测），仅作兜底；
 * 3. 打开微信首页给手动引导——出码本身已成功，这一步只是省一次手动切 App。
 *
 * 三级都走 [startActivityOutsideApp]：微信是 singleTask、永远开不进本 task，
 * 从它返回时本页的右推入过渡会被重放（用户报「界面跳动」），拉起前要换静止过渡。
 */
private fun openWechatScan(context: android.content.Context, onError: (String) -> Unit) {
    val dispatchScan = Intent("com.tencent.mm.ui.ShortCutDispatchAction")
        .setPackage("com.tencent.mm")
        .putExtra("LauncherUI.Shortcut.LaunchType", "launch_type_scan_qrcode")
    try {
        context.startActivityOutsideApp(dispatchScan)
        return
    } catch (_: Exception) {
        // 落到下一级
    }
    val bizShortcut = Intent("com.tencent.mm.action.BIZSHORTCUT")
        .setPackage("com.tencent.mm")
        .addFlags(0x14000000) // NEW_TASK | CLEAR_TOP（沿用微信 shortcut 的 launchFlags）
        .putExtra("LauncherUI.From.Scaner.Shortcut", true)
    try {
        context.startActivityOutsideApp(bizShortcut)
        return
    } catch (_: Exception) {
        // 落到手动引导
    }
    val launch = context.packageManager.getLaunchIntentForPackage("com.tencent.mm")
    if (launch != null) {
        try {
            context.startActivityOutsideApp(launch)
            onError("微信已打开，请在「发现 → 扫一扫」对准二维码")
            return
        } catch (_: Exception) {
            // 落到统一失败文案
        }
    }
    onError("无法自动打开微信，请手动打开「扫一扫」扫码")
}

/** 「快趣出行」App 包名（DESIGN §3.9）。 */
private const val KVCOO_PACKAGE = "com.kvcoo.go"

/**
 * 打开「快趣出行」App（需已安装）。
 *
 * 2026-09-23 收敛为一级：桌面启动意图（启动页，导出无门槛），没装给一句提示。
 * 删掉的两条路各有理由——「助手通道」（临时改写系统 `Settings.Secure.assistant` +
 * 反射 `SearchManager.launchAssist`，由 SystemUI 代启未导出的首页）要用户先跑一次
 * `adb shell pm grant <包名> android.permission.WRITE_SECURE_SETTINGS`，
 * 而且只在小米 ROM 上验证过；「未安装下载引导」指向的是第三方下载站。
 * 内置地图（`BikeMapScreen`）已经把「看车在哪」接过来，官方 App 不再是必经步骤。
 */
private fun openKvcoo(context: android.content.Context, onError: (String) -> Unit) {
    val launch = try {
        context.packageManager.getLaunchIntentForPackage(KVCOO_PACKAGE)
    } catch (_: Exception) {
        null
    }
    if (launch == null) {
        onError("未安装快趣出行；可直接用「附近单车地图」，或手动输入车号出码")
        return
    }
    try {
        context.startActivityOutsideApp(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        onError("打开快趣出行失败，请手动打开")
    }
}
