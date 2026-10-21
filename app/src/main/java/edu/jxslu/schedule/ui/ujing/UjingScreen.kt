package edu.jxslu.schedule.ui.ujing

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.zxing.client.android.Intents
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.ujing.UjingHouse
import edu.jxslu.schedule.data.ujing.UjingOrderSnapshot
import edu.jxslu.schedule.data.session.SessionService
import edu.jxslu.schedule.domain.QzxyPhoneMask
import edu.jxslu.schedule.domain.UjingState
import edu.jxslu.schedule.ui.common.AppPermissions
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.DialogWindowFadeEffect
import edu.jxslu.schedule.ui.common.FooterDisclaimer
import edu.jxslu.schedule.ui.common.HeroTone
import edu.jxslu.schedule.ui.common.ImeAwareModalBottomSheet
import edu.jxslu.schedule.ui.common.NoticeTone
import edu.jxslu.schedule.ui.common.SettingItem
import edu.jxslu.schedule.ui.common.SettingsSection
import edu.jxslu.schedule.ui.common.SessionExpiredNotice
import edu.jxslu.schedule.ui.common.SkeletonBox
import edu.jxslu.schedule.ui.common.StaggeredReveal
import edu.jxslu.schedule.ui.common.StatusHeroCard
import edu.jxslu.schedule.ui.common.heroToneAccent
import edu.jxslu.schedule.ui.common.lineHeightDp
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import edu.jxslu.schedule.ui.common.rememberCloseLock
import edu.jxslu.schedule.ui.ebike.BikeLocator
import edu.jxslu.schedule.ui.ebike.EbikeScanActivity
import edu.jxslu.schedule.ui.ebike.LocateResult
import edu.jxslu.schedule.ui.ebike.showNotice
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AlertCircle
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.QrCodeScan
import me.rerere.hugeicons.stroke.ReceiptText
import me.rerere.hugeicons.stroke.WashingMachine
import kotlin.math.ceil

/** 订单进行中且页面可见时的轮询间隔（与 ViewModel 口径一致，只在 UI 侧用）。 */
private const val ORDER_POLL_INTERVAL_MILLIS = 15_000L

/** 页面区块的出现 / 退出过渡时长（全页统一，别在各处各写一个数）。 */
private const val SectionEnterMillis = 220
private const val SectionExitMillis = 160

/** 使用须知正文（DESIGN §4.37；逐条口径见 docs/ujing-plan.md §3.10，别顺手精简）。 */
private val NoticeItems = listOf(
    "本功能为 U净（美的校园洗衣）的第三方客户端，与美的集团、无锡小净及相关运营方无任何关联。",
    "登录凭据（手机号、验证码、令牌）仅保存在本机加密存储，不会经过任何第三方服务器。",
    "费用与支付全部通过官方渠道完成，本应用不经手资金，不提供任何价格优惠或免费使用。",
    "第三方接口可能随平台调整而失效，届时请以官方 App / 小程序为准。",
)

/** 页尾免责声明（DESIGN §3.2「页尾免责声明」：五个第三方账号页统一走 FooterDisclaimer）。 */
private const val FooterDisclaimerText =
    "第三方非官方功能：与美的集团、无锡小净及相关运营方均无关联；" +
        "费用与支付全部通过官方渠道完成，本应用不经手资金。"

/**
 * U净 洗衣房主页（DESIGN §3.23 / §4.37）。
 *
 * 登录（手机号 + 短信验证码）→ 订单英雄卡（下单 / 支付宝支付 / 倒计时 / 取消 / 云端启动）→
 * 扫码入口（识别成功即开草稿并跳 [UjingOrderScreen]，选档 / 投放都在那一页）→
 * 收藏洗衣房空闲看板 → 账号与记录（历史账单入口 [UjingHistoryScreen] + 退出登录）。
 *
 * 层级靠**视觉**而不是顺序表达（2026-10-16 重排）：在案订单是唯一一张
 * [StatusHeroCard]（实心 tonal 底、大剩余时间），扫码是主操作按钮，看板与账号收成
 * 普通设置卡。登录态切换、订单卡出现/终结、看板首拉都有过渡，不做硬切。
 *
 * 洗涤中订单由 [edu.jxslu.schedule.ui.reminder.UjingDoneReminder] 在到点时发系统通知
 * （渠道：洗衣完成提醒）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UjingScreen(
    onBack: () -> Unit = {},
    /** 扫码识别成功 → 跳独立下单页（导航由窗口层接，页里只发事件）。 */
    onOpenOrder: () -> Unit = {},
    /** 历史账单入口。 */
    onOpenHistory: () -> Unit = {},
    /**
     * 今日页「+ → 扫一扫」扫到机身码时带进来的二维码原文（`UjingScanHandoff` 一次性令牌）：
     * 进页即按普通扫码处理，识别成功照常跳下单页。普通入口传 null。
     */
    scanRaw: String? = null,
    // payActivityProvider 的 lambda 会在支付时（非组合上下文）被调，这里在组合期把
    // Activity 捕获进闭包（LocalContext.current 不能在普通 lambda 里再取）
    viewModel: UjingViewModel = LocalContext.current.let { entryContext ->
        viewModel(
            factory = UjingViewModel.Factory(
                Graph.ujing(entryContext),
                Graph.ujingHouses(entryContext),
                Graph.ujingHistory(entryContext),
                Graph.ujingDraft(entryContext),
                Graph.displayPrefs(entryContext),
                appContext = entryContext.applicationContext,
                payActivityProvider = { entryContext as? Activity },
            ),
        )
    },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberAppHaptics()

    // 上次在别处判定登录过期：进页说一句为什么退回登录表单（只提示一次）
    SessionExpiredNotice(SessionService.Ujing, state.loggedIn, snackbar)
    var houseToRemove by remember { mutableStateOf<UjingHouse?>(null) }
    var confirmLogout by remember { mutableStateOf(false) }
    var confirmCancelOrder by remember { mutableStateOf(false) }
    // 扫码草稿（「继续上次识别」行用）；下单页关掉后仍留着，直到下单受理或重扫覆盖
    val draftStore = remember { Graph.ujingDraft(context) }
    val hasDraft by draftStore.draft.collectAsStateWithLifecycle()

    // 一次性提示（登录成功、会话过期、看板错误等）
    LaunchedEffect(Unit) {
        viewModel.events.collect { message -> showNotice(scope, snackbar, message, NoticeTone.Info) }
    }
    // 一次性导航：识别成功 → 独立下单页
    LaunchedEffect(Unit) {
        viewModel.openOrder.collect { onOpenOrder() }
    }
    // 今日页扫到的原文：进页识别一次。键是 scanRaw 本身——重组不会重跑，
    // 配置变更后 Activity 重建时令牌早已消费（拿到 null），也不会重扫
    LaunchedEffect(scanRaw) {
        if (!scanRaw.isNullOrBlank()) viewModel.onQrScanned(scanRaw)
    }

    // 订单进行中且页面可见时的 15 秒轮询（离开页面即随组合取消；无后台轮询，红线见 §4.37）。
    // 走 silent 刷新：不置 busy、不弹错误——后台轮询不该每 15 秒把按钮闪成加载圈。
    LaunchedEffect(state.order?.orderId, state.order?.isTerminal) {
        val order = state.order ?: return@LaunchedEffect
        while (isActive && !order.isTerminal) {
            delay(ORDER_POLL_INTERVAL_MILLIS)
            viewModel.refreshOrder(silent = true)
        }
    }

    // 附近洗衣房：定位一次 → 拉门店列表（与单车地图同一套定位口径：权限先申请、8 秒超时）
    val locateAndLoad: () -> Unit = {
        viewModel.openPicker()
        scope.launch {
            when (val result = BikeLocator.currentLocation(context)) {
                is LocateResult.Ok -> viewModel.loadNearby(result.lat, result.lng)
                is LocateResult.Failed -> {
                    viewModel.closePicker()
                    showNotice(scope, snackbar, result.message, NoticeTone.Warning)
                }
            }
        }
    }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.any { it }) {
            locateAndLoad()
        } else {
            showNotice(scope, snackbar, "没有定位权限，无法获取附近洗衣房", NoticeTone.Warning)
        }
    }
    val requestNearby: () -> Unit = {
        haptics.tap()
        if (!BikeLocator.hasPermission(context)) {
            locationPermissionLauncher.launch(AppPermissions.location.toTypedArray())
        } else {
            locateAndLoad()
        }
    }

    // 扫码：复用内置取景窗口的原文模式（相机权限由取景窗口自己处理，与骑行页同一条通道）
    val scanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val raw = result.data?.getStringExtra(Intents.Scan.RESULT)
        if (result.resultCode == Activity.RESULT_OK && !raw.isNullOrBlank()) {
            viewModel.onQrScanned(raw)
        }
    }
    val startScan: () -> Unit = {
        haptics.tap()
        scanLauncher.launch(
            Intent(context, EbikeScanActivity::class.java).apply {
                putExtra(Intents.Scan.PROMPT_MESSAGE, "对准洗衣机机身上的二维码")
                putExtra(EbikeScanActivity.EXTRA_RESULT_MODE, EbikeScanActivity.MODE_RAW)
            },
        )
    }

    // 订单卡退场期间快照可能已被清空（点「知道了」），AnimatedVisibility 在 exit 时
    // 仍继续组合旧内容——留一份最后非空快照，退场才是「卡片收起」而不是「空块收缩」。
    val liveOrder = state.order
    var lastOrder by remember { mutableStateOf<UjingOrderSnapshot?>(null) }
    LaunchedEffect(liveOrder) { if (liveOrder != null) lastOrder = liveOrder }
    val heroOrder = liveOrder ?: lastOrder

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("U净") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { AppSnackbarHost(state = snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 登录态切换走尺寸 + 淡入淡出过渡（两态高度差很大，硬切会「跳」一截）
            AnimatedContent(
                targetState = state.loggedIn,
                transitionSpec = {
                    fadeIn(tween(SectionEnterMillis, easing = FastOutSlowInEasing)) togetherWith
                        fadeOut(tween(SectionExitMillis))
                },
                label = "ujingAuth",
            ) { loggedIn ->
                if (!loggedIn) {
                    LoginSection(state = state, viewModel = viewModel)
                } else {
                    // 层级口径（2026-10-16 重排）：在案订单（英雄卡）> 扫码（主操作）>
                    // 看板 > 账号与记录。账号收底，不再单独占一张卡。
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        AnimatedVisibility(
                            visible = liveOrder != null,
                            enter = fadeIn(tween(SectionEnterMillis)) +
                                expandVertically(
                                    tween(SectionEnterMillis, easing = FastOutSlowInEasing),
                                    expandFrom = Alignment.Top,
                                ),
                            exit = fadeOut(tween(SectionExitMillis)) +
                                shrinkVertically(
                                    tween(SectionExitMillis),
                                    shrinkTowards = Alignment.Top,
                                ),
                        ) {
                            heroOrder?.let { order ->
                                OrderHero(
                                    order = order,
                                    // 支付 SDK 阻塞期间（paying）同样禁动作：收银台在那边，这里点取消
                                    // 会和支付结果赛跑（busy 只覆盖请求本身，支付窗口是另一段）
                                    busy = state.orderBusy || state.paying,
                                    onRefresh = { viewModel.refreshOrder() },
                                    onRetryPay = viewModel::retryPay,
                                    onCancel = { confirmCancelOrder = true },
                                    onStart = viewModel::startOrder,
                                    onDismiss = viewModel::dismissOrder,
                                )
                            }
                        }
                        // 常驻的两块错峰落位（订单卡由 AnimatedVisibility 管，别叠加）
                        StaggeredReveal(index = 0) {
                            ScanSection(
                                state = state,
                                hasDraft = hasDraft != null,
                                onScan = startScan,
                                onOpenOrder = onOpenOrder,
                            )
                        }
                        StaggeredReveal(index = 1) {
                            BoardSection(
                                state = state,
                                onAdd = requestNearby,
                                onRefresh = {
                                    haptics.tap()
                                    viewModel.refreshBoard()
                                },
                                onRemove = { houseToRemove = it },
                            )
                        }
                        RecordsSection(
                            mobile = state.mobile,
                            historyCount = state.historyCount,
                            onOpenHistory = onOpenHistory,
                            onLogout = { confirmLogout = true },
                        )
                    }
                }
            }
            FooterDisclaimer(FooterDisclaimerText)
        }

        // ── 弹层与对话框 ──
        if (state.pickerOpen) {
            HousePickerSheet(state = state, viewModel = viewModel)
        }
        state.order?.let { order ->
            if (confirmCancelOrder) {
                AlertDialog(
                    onDismissRequest = { confirmCancelOrder = false },
                    title = { Text("取消订单") },
                    text = {
                        // 弹窗进出场动画必须挂在本弹窗窗口上：调用点要落在内容槽第一行，
                        // 写在 AlertDialog 外面拿到的是页面窗口（死代码，见 DialogWindowFadeEffect）
                        DialogWindowFadeEffect()
                        Text("取消「${order.modelName}」并释放机器？未支付订单取消不产生费用。")
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                confirmCancelOrder = false
                                viewModel.cancelOrder()
                            },
                        ) { Text("取消订单") }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmCancelOrder = false }) { Text("保留") }
                    },
                )
            }
        }
        houseToRemove?.let { house ->
            AlertDialog(
                onDismissRequest = { houseToRemove = null },
                title = { Text("删除洗衣房") },
                text = {
                    DialogWindowFadeEffect()
                    Text("从看板中删除「${house.name}」？不会影响 U净 账号里的任何数据。")
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.removeHouse(house)
                            houseToRemove = null
                        },
                    ) { Text("删除") }
                },
                dismissButton = {
                    TextButton(onClick = { houseToRemove = null }) { Text("取消") }
                },
            )
        }
        if (confirmLogout) {
            AlertDialog(
                onDismissRequest = { confirmLogout = false },
                title = { Text("退出登录") },
                text = {
                    DialogWindowFadeEffect()
                    Text("退出后需要重新用短信验证码登录。确定退出？")
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirmLogout = false
                            viewModel.logout()
                        },
                    ) { Text("退出") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmLogout = false }) { Text("取消") }
                },
            )
        }
        if (state.noticeVisible) {
            UjingNoticeDialog(onConfirm = viewModel::onNoticeConfirmed)
        }
    }
}

// ── 登录 ──

@Composable
private fun LoginSection(state: UjingUiState, viewModel: UjingViewModel) {
    // 60 秒冷却倒计时：只按发码时刻算，页面在就 tick、页面走就停；
    // 冷却归零（或压根没发过码）就停循环——不然进页即永久每秒唤醒重组
    val now by produceState(initialValue = System.currentTimeMillis(), key1 = state.codeSentAt) {
        val deadline = state.codeSentAt + 60_000L
        while (state.codeSentAt > 0L && System.currentTimeMillis() < deadline) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
        value = System.currentTimeMillis()
    }
    val cooldownSeconds = if (state.codeSentAt > 0L) {
        ((state.codeSentAt + 60_000L - now).coerceAtLeast(0L) + 999L) / 1000L
    } else {
        0L
    }

    SettingsSection(
        title = "登录 U净",
        subtitle = "美的校园洗衣 · 手机号短信登录",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = state.mobile,
                onValueChange = viewModel::onMobileChange,
                label = { Text("手机号") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                enabled = !state.loggingIn,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = state.captcha,
                    onValueChange = viewModel::onCaptchaChange,
                    label = { Text("短信验证码") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    enabled = !state.loggingIn,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = viewModel::sendCode,
                    enabled = cooldownSeconds <= 0L && !state.codeSending,
                ) {
                    Text(
                        when {
                            state.codeSending -> "发送中…"
                            cooldownSeconds > 0L -> "$cooldownSeconds 秒"
                            else -> "获取验证码"
                        },
                    )
                }
            }
            Button(
                onClick = viewModel::login,
                enabled = !state.loggingIn,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Text(if (state.loggingIn) "登录中…" else "登录")
            }
            Text(
                "登录凭据仅保存在本机加密存储，不会经过任何第三方服务器；只保存登录状态，不保存密码。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
        }
    }
}

// ── 账号与记录 ──

@Composable
private fun RecordsSection(
    mobile: String,
    historyCount: Int,
    onOpenHistory: () -> Unit,
    onLogout: () -> Unit,
) {
    SettingsSection(
        title = "账号与记录",
        subtitle = "账单只存本机 · 会话过期才需重登",
    ) {
        SettingItem(
            title = "历史账单",
            subtitle = if (historyCount > 0) "共 $historyCount 笔 · 点看明细" else "还没有账单",
            icon = HugeIcons.ReceiptText,
            onClick = onOpenHistory,
        )
        SettingItem(
            title = QzxyPhoneMask.mask(mobile),
            subtitle = "手机号账号 · 凭据存于本机（加密）",
            icon = HugeIcons.WashingMachine,
            value = "退出",
            onClick = onLogout,
        )
    }
}

// ── 空闲看板 ──

@Composable
private fun BoardSection(
    state: UjingUiState,
    onAdd: () -> Unit,
    onRefresh: () -> Unit,
    onRemove: (UjingHouse) -> Unit,
) {
    SettingsSection(
        title = "洗衣房空闲",
        subtitle = "云端数据 · 约 30 秒滞后",
    ) {
        if (state.houses.isEmpty()) {
            Text(
                "还没有收藏洗衣房。添加宿舍楼里的洗衣房后，这里会显示洗衣机空闲数量。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        // 首拉（有收藏、还没数据）先渲染与成品同几何的骨架，不许先空态再跳
        // （ui-common「等数据的地方先给骨架」）。高度差由 animateContentSize 收平。
        val firstLoad = state.boardRefreshing && state.board.isEmpty() && state.houses.isNotEmpty()
        Column(Modifier.animateContentSize()) {
            if (firstLoad) {
                state.houses.forEach { BoardSkeletonRow() }
            } else {
                state.board.forEach { row ->
                    BoardHouseRow(row = row, onRemove = { onRemove(row.house) })
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onAdd) {
                Text(if (state.houses.isEmpty()) "添加洗衣房" else "添加")
            }
            if (state.houses.isNotEmpty()) {
                TextButton(
                    onClick = onRefresh,
                    enabled = !state.boardRefreshing,
                    // 固定最小宽度：行内 spinner 不再把按钮撑宽、把这一行带得抖一下
                    modifier = Modifier.widthIn(min = 88.dp),
                ) {
                    if (state.boardRefreshing) {
                        CircularProgressIndicator(
                            Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = LocalContentColor.current,
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text("刷新")
                }
            }
        }
    }
}

/** 看板行首拉的骨架：一行店名 + 两行设备，几何与 [BoardHouseRow] 对齐。 */
@Composable
private fun BoardSkeletonRow() {
    val nameHeight = lineHeightDp(MaterialTheme.typography.bodyLarge)
    val lineHeight = lineHeightDp(MaterialTheme.typography.bodySmall)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        SkeletonBox(Modifier.height(nameHeight).fillMaxWidth(0.42f))
        SkeletonBox(Modifier.height(lineHeight).fillMaxWidth(0.55f))
        SkeletonBox(Modifier.height(lineHeight).fillMaxWidth(0.48f))
    }
}

@Composable
private fun BoardHouseRow(row: UjingBoardRow, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(row.house.name, style = MaterialTheme.typography.bodyLarge)
            if (row.failed) {
                BoardSubtitle(
                    row.error?.let { "获取失败：$it" } ?: "获取失败，点「刷新」重试",
                    highlight = false,
                )
            } else {
                row.line?.let { DeviceLine(label = "洗衣机", line = it) }
                row.dryerLine?.let { DeviceLine(label = "烘干机", line = it) }
                if (row.line == null && row.dryerLine == null) {
                    BoardSubtitle("该店暂无设备数据", highlight = false)
                }
            }
        }
        // 命中区 40dp（M3 IconButton 自带 48dp 触摸目标），此前 32dp 低于无障碍下限
        IconButton(onClick = onRemove, modifier = Modifier.size(40.dp)) {
            Icon(
                HugeIcons.Delete02,
                contentDescription = "删除 ${row.house.name}",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * 一行设备：类型标签 + 空闲数（放大走主色）/ 全满文案。
 * 数字取 [UjingState.BoardLine.freeCountText]（语义单一来源，UI 不另拼）。
 */
@Composable
private fun DeviceLine(label: String, line: UjingState.BoardLine) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            modifier = Modifier.width(40.dp),
        )
        val freeText = line.freeCountText
        if (freeText != null) {
            Text(
                freeText,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "空闲",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
        } else {
            Text(
                listOfNotNull(line.primaryText, line.secondaryText).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
        }
    }
}

/** 看板行的一行小字（失败 / 空态共用）：[highlight] 时空闲数走主色。 */
@Composable
private fun BoardSubtitle(text: String, highlight: Boolean) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (highlight) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
        },
        // 失败原因可能是一段服务端文案，最多三行，别把看板撑开
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
}

// ── 扫码识别 ──

/**
 * 扫码入口。主页只管「扫没扫成」：识别成功即跳独立下单页（[UjingOrderScreen]），
 * 档位 / 水温 / 投放的选择都不在这页；识别过但没下完单的，草稿还在，
 * 给一行「继续上次识别」就近接回。
 *
 * 识别中的反馈落在按钮自己身上（按钮常显、就地变 loading）——另起一行状态会把
 * 下方内容顶动一下，而那正是「跳变」的来源。
 */
@Composable
private fun ScanSection(
    state: UjingUiState,
    hasDraft: Boolean,
    onScan: () -> Unit,
    onOpenOrder: () -> Unit,
) {
    val parsing = state.scan is UjingScanState.Parsing
    val failed = state.scan as? UjingScanState.Failed
    SettingsSection(
        title = "扫码下单",
        subtitle = "对准机身二维码即可",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onScan,
                enabled = !parsing,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (parsing) {
                    CircularProgressIndicator(
                        Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = LocalContentColor.current,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("正在识别设备…")
                } else {
                    Icon(HugeIcons.QrCodeScan, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("扫码识别洗衣机")
                }
            }
            // 状态行常驻组合、按状态淡入淡出，切换不跳变
            AnimatedVisibility(visible = failed != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        HugeIcons.AlertCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        failed?.message.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            AnimatedVisibility(visible = !parsing && failed == null && hasDraft) {
                SettingItem(
                    title = "继续上次识别的设备",
                    subtitle = "档位与投放还没定，点这里接着选",
                    onClick = onOpenOrder,
                )
            }
            AnimatedVisibility(visible = !parsing && failed == null && !hasDraft) {
                // 空态给一句引导，别让首屏只剩一枚孤零零的按钮
                Text(
                    "扫机身码识别设备，选好档位确认后直接下单，支付那一步会拉起支付宝。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }
        }
    }
}

// ── 订单英雄卡 ──

/**
 * 在案订单：唯一一张 [StatusHeroCard]。状态 + 大剩余时间 + 进度条 + 动作行。
 *
 * 倒计时以快照时刻本地换算（不每秒请求），服务端轮询由页面的 15 秒 `LaunchedEffect` 负责。
 * 两条本地换算（都不发请求）：
 * - 洗涤中（status 40）：进度 = 1 − 剩余/总时长（总时长取下单时所选模式，旧快照没有就不显）；
 * - 未支付：机器独占窗口 = 下单时刻 + 2 分钟（协议口径），窗口用尽提示刷新确认。
 *
 * 倒计时文字**不做 crossfade**（每秒都在变，动画会一直闪）；变化感交给进度条：
 * 进度值走 400ms 过渡，每秒更新接续平滑，观感是连续推进。
 */
@Composable
private fun OrderHero(
    order: UjingOrderSnapshot,
    busy: Boolean,
    onRefresh: () -> Unit,
    onRetryPay: () -> Unit,
    onCancel: () -> Unit,
    onStart: () -> Unit,
    onDismiss: () -> Unit,
) {
    // 本地每秒 tick：剩余 = 快照 remainSeconds −（现在 − 快照时刻），只对运行中订单有意义；
    // 终态订单倒计时没有意义，不再每秒唤醒（刷新必写新 snapshotAt，状态翻转时本 tick 会重启）
    val terminal = order.isTerminal
    val now by produceState(
        initialValue = System.currentTimeMillis(),
        key1 = order.snapshotAt,
        key2 = terminal,
    ) {
        while (!terminal) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val running = order.status == "40" && !terminal
    val remainSeconds = if (running) {
        order.remainSeconds - ((now - order.snapshotAt) / 1000).toInt()
    } else {
        order.remainSeconds
    }
    val payWindowRemain = if (!order.paid && !terminal) {
        UjingState.payWindowRemainSeconds(order.createdAt, now)
    } else {
        -1
    }
    // 语气：完成 = 成功，其余终结态（取消 / 超时 / 失败）= 中性，进行中 = 主色
    val tone = when {
        !terminal -> HeroTone.Primary
        order.status == "50" -> HeroTone.Success
        else -> HeroTone.Neutral
    }
    val accent = heroToneAccent(tone)
    // 右侧大数值：洗涤中给剩余时间，未支付给支付窗口倒计时（最要紧的那个数）
    val metric = when {
        running && order.remainSeconds > 0 -> UjingState.remainText(remainSeconds) to accent
        payWindowRemain > 0 -> UjingState.remainText(payWindowRemain) to MaterialTheme.colorScheme.error
        else -> null
    }
    StatusHeroCard(tone = tone) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(accent),
            )
            Text(
                UjingState.statusLabel(order.status, order.statusRemark),
                style = MaterialTheme.typography.titleSmall,
                color = accent,
                modifier = Modifier.weight(1f),
            )
            metric?.let { (text, color) ->
                Text(text, style = MaterialTheme.typography.titleLarge, color = color)
            }
        }
        Text(
            buildString {
                append(order.deviceName.ifBlank { "洗衣机" })
                append(" · ").append(order.modelName.ifBlank { "洗衣模式" })
                if (order.priceFen > 0) {
                    append(" · ¥").append(UjingState.fen2yuan(order.priceFen))
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            modifier = Modifier.padding(top = 2.dp),
        )
        if (running && order.durationSeconds > 0) {
            val target = (
                1f - remainSeconds.coerceAtLeast(0).toFloat() / order.durationSeconds
                ).coerceIn(0f, 1f)
            val progress by animateFloatAsState(
                targetValue = target,
                animationSpec = tween(durationMillis = 400, easing = LinearOutSlowInEasing),
                label = "ujingWashProgress",
            )
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
            )
        }
        if (payWindowRemain >= 0) {
            Text(
                if (payWindowRemain > 0) {
                    "机器已保留，请在倒计时内完成支付"
                } else {
                    "支付窗口已到点，点「刷新」确认订单状态（超时未付会自动取消）"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (payWindowRemain > 0) {
                    MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                },
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        // 动作行：按状态给按钮（不摆点不了的按钮骗人）
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 10.dp),
        ) {
            if (terminal) {
                TextButton(onClick = onDismiss) { Text("知道了") }
            } else {
                if (!order.paid && UjingState.canCancel(order.status)) {
                    TextButton(onClick = onCancel, enabled = !busy) { Text("取消订单") }
                }
                if (!order.paid) {
                    Button(onClick = onRetryPay, enabled = !busy) {
                        if (busy) {
                            CircularProgressIndicator(
                                Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = LocalContentColor.current,
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text("去支付")
                    }
                }
                if (UjingState.canStart(order.status)) {
                    Button(onClick = onStart, enabled = !busy) {
                        if (busy) {
                            CircularProgressIndicator(
                                Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = LocalContentColor.current,
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text("启动")
                    }
                }
                TextButton(onClick = onRefresh, enabled = !busy) { Text("刷新") }
            }
        }
        if (UjingState.canStart(order.status)) {
            Text(
                "蓝牙机型需站在机器旁（约 10 米内）再点启动；2G / NB / 4G 机型可直接远程启动。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/**
 * 在案订单的紧凑状态行（生活页 U净 弹窗复用）：设备名 + 状态文案 + 洗涤中剩余时间。
 * 剩余时间以快照时刻本地换算（不请求）；是否显示由调用方定（如仅非终结订单）。
 */
@Composable
internal fun UjingOrderStatusLine(order: UjingOrderSnapshot, modifier: Modifier = Modifier) {
    val running = order.status == "40"
    val now by produceState(
        initialValue = System.currentTimeMillis(),
        key1 = order.snapshotAt,
        key2 = running,
    ) {
        while (running) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val remain = UjingState.liveRemainSeconds(order.status, order.remainSeconds, order.snapshotAt, now)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            order.deviceName.ifBlank { "洗衣机" },
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            buildString {
                append(UjingState.statusLabel(order.status, order.statusRemark))
                if (running && order.remainSeconds > 0) {
                    append(" · 剩余 ").append(UjingState.remainText(remain))
                }
            },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

// ── 附近洗衣房弹层 ──

@Composable
private fun HousePickerSheet(state: UjingUiState, viewModel: UjingViewModel) {
    ImeAwareModalBottomSheet(onDismiss = viewModel::closePicker) {
        Text(
            "附近的洗衣房",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Text(
            "点击添加到你自己的看板，可以加多间。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        when {
            state.pickerLoading -> Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("正在获取附近洗衣房…")
            }
            state.pickerStores.isEmpty() -> Text(
                "附近没有找到洗衣房。到校后重试，或检查定位权限。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(20.dp),
            )
            else -> Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp),
            ) {
                state.pickerStores.forEach { store ->
                    val added = state.houses.any { it.storeId == store.id }
                    SettingItem(
                        title = store.name,
                        subtitle = if (added) "已添加" else "点击添加",
                        enabled = !added,
                        onClick = { if (!added) viewModel.addHouse(store) },
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

// ── 使用须知 ──

/**
 * 使用须知（DESIGN §4.37）：首次弹出锁 3 秒，确认后一周内不再弹
 * （与充值免责声明同一种"先知情再使用"的口径，正文见 [NoticeItems]）。
 */
@Composable
private fun UjingNoticeDialog(onConfirm: () -> Unit) {
    // 首次进页异步弹出（等 ujingNoticeSeenAt 读到才置 true），没有窗口动画就是
    // 凭空一跳——与快趣确认弹窗同款修法（2026-10-03）
    val remainingMs = rememberCloseLock(3_000L)
    val locked = remainingMs > 0L
    AlertDialog(
        // 锁定期间点遮罩 / 返回键也不放走；解锁后点遮罩视为已读
        onDismissRequest = { if (!locked) onConfirm() },
        title = { Text("U净 使用须知") },
        text = {
            // 动画调用点必须在弹窗自己的组合里（内容槽第一行），放 AlertDialog 外面是死代码
            DialogWindowFadeEffect()
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                NoticeItems.forEachIndexed { index, item ->
                    Text(
                        "${index + 1}. $item",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !locked) {
                Text(
                    if (locked) "知道了（${ceil(remainingMs / 1000.0).toInt()} 秒）" else "知道了",
                )
            }
        },
    )
}
