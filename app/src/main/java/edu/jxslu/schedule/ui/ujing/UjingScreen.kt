package edu.jxslu.schedule.ui.ujing

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.zxing.client.android.Intents
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.ujing.UjingHouse
import edu.jxslu.schedule.data.ujing.UjingOrderSnapshot
import edu.jxslu.schedule.data.ujing.UjingProgramData
import edu.jxslu.schedule.data.ujing.UjingWashModel
import edu.jxslu.schedule.domain.QzxyPhoneMask
import edu.jxslu.schedule.domain.UjingState
import edu.jxslu.schedule.ui.common.AppPermissions
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.ImeAwareModalBottomSheet
import edu.jxslu.schedule.ui.common.NoticeTone
import edu.jxslu.schedule.ui.common.SettingItem
import edu.jxslu.schedule.ui.common.SettingsSection
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
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.QrCodeScan
import me.rerere.hugeicons.stroke.WashingMachine
import kotlin.math.ceil

/** 订单进行中且页面可见时的轮询间隔（与 ViewModel 口径一致，只在 UI 侧用）。 */
private const val ORDER_POLL_INTERVAL_MILLIS = 15_000L

/** 使用须知正文（DESIGN §4.37；逐条口径见 docs/ujing-plan.md §3.10，别顺手精简）。 */
private val NoticeItems = listOf(
    "本功能为 U净（美的校园洗衣）的第三方客户端，与美的集团、无锡小净及相关运营方无任何关联。",
    "登录凭据（手机号、验证码、令牌）仅保存在本机加密存储，不会经过任何第三方服务器。",
    "费用与支付全部通过官方渠道完成，本应用不经手资金，不提供任何价格优惠或免费使用。",
    "第三方接口可能随平台调整而失效，届时请以官方 App / 小程序为准。",
)

/**
 * U净 洗衣房页（DESIGN §3.23 / §4.37）。
 *
 * 登录（手机号 + 短信验证码）→ 订单卡（下单 / 支付宝支付 / 倒计时 / 取消 / 云端启动）→
 * 扫码选模式下单 → 收藏洗衣房空闲看板。排版自上而下按「要紧程度」递减：
 * 在案订单 > 扫码 > 看板 > 账号。洗涤中订单由 [edu.jxslu.schedule.ui.reminder.UjingDoneReminder]
 * 在到点时发系统通知（渠道：洗衣完成提醒）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UjingScreen(
    onBack: () -> Unit = {},
    // payActivityProvider 的 lambda 会在支付时（非组合上下文）被调，这里在组合期把
    // Activity 捕获进闭包（LocalContext.current 不能在普通 lambda 里再取）
    viewModel: UjingViewModel = LocalContext.current.let { entryContext ->
        viewModel(
            factory = UjingViewModel.Factory(
                Graph.ujing(entryContext),
                Graph.ujingHouses(entryContext),
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
    var houseToRemove by remember { mutableStateOf<UjingHouse?>(null) }
    var confirmLogout by remember { mutableStateOf(false) }
    var confirmCancelOrder by remember { mutableStateOf(false) }

    // 一次性提示（登录成功、会话过期、看板错误等）
    LaunchedEffect(Unit) {
        viewModel.events.collect { message -> showNotice(scope, snackbar, message, NoticeTone.Info) }
    }

    // 订单进行中且页面可见时的 15 秒轮询（离开页面即随组合取消；无后台轮询，红线见 §4.37）
    LaunchedEffect(state.order?.orderId, state.order?.isTerminal) {
        val order = state.order ?: return@LaunchedEffect
        while (isActive && !order.isTerminal) {
            delay(ORDER_POLL_INTERVAL_MILLIS)
            viewModel.refreshOrder()
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
            if (!state.loggedIn) {
                LoginSection(state = state, viewModel = viewModel)
            } else {
                // 排版口径（2026-10-01 体验打磨）：要紧的事在最上、主操作次之、
                // 参考信息再次、账号收底——订单 > 扫码 > 看板 > 账号。
                // 在案订单卡（下单后出现）
                state.order?.let { order ->
                    OrderSection(
                        order = order,
                        busy = state.orderBusy,
                        onRefresh = { viewModel.refreshOrder() },
                        onRetryPay = viewModel::retryPay,
                        onCancel = { confirmCancelOrder = true },
                        onStart = viewModel::startOrder,
                        onDismiss = viewModel::dismissOrder,
                    )
                }
                ScanSection(
                    state = state,
                    onScan = startScan,
                    onRequestOrder = viewModel::requestOrder,
                    onSelectTemperature = viewModel::selectTemperature,
                )
                BoardSection(
                    state = state,
                    onAdd = requestNearby,
                    onRefresh = {
                        haptics.tap()
                        viewModel.refreshBoard()
                    },
                    onRemove = { houseToRemove = it },
                )
                AccountSection(state = state, onLogout = { confirmLogout = true })
            }
        }

        // ── 弹层与对话框 ──
        if (state.pickerOpen) {
            HousePickerSheet(state = state, viewModel = viewModel)
        }
        // 下单确认（写操作二次确认；正文核对设备 / 模式 / 水温 / 价格 / 2 分钟独占）
        state.confirmingModel?.let { model ->
            val ready = state.scan as? UjingScanState.Ready
            if (ready != null) {
                OrderConfirmDialog(
                    program = ready.program,
                    model = model,
                    selectedTemperatureId = state.selectedTemperatureId,
                    busy = state.orderBusy,
                    onConfirm = viewModel::confirmOrder,
                    onDismiss = viewModel::dismissConfirm,
                )
            }
        }
        state.order?.let { order ->
            if (confirmCancelOrder) {
                AlertDialog(
                    onDismissRequest = { confirmCancelOrder = false },
                    title = { Text("取消订单") },
                    text = { Text("取消「${order.modelName}」并释放机器？未支付订单取消不产生费用。") },
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
                text = { Text("从看板中删除「${house.name}」？不会影响 U净 账号里的任何数据。") },
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
                text = { Text("退出后需要重新用短信验证码登录。确定退出？") },
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
    // 60 秒冷却倒计时：只按发码时刻算，页面在就 tick、页面走就停
    val now by produceState(initialValue = System.currentTimeMillis(), key1 = state.codeSentAt) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val cooldownSeconds = if (state.codeSentAt > 0L) {
        ((state.codeSentAt + 60_000L - now).coerceAtLeast(0L) + 999L) / 1000L
    } else {
        0L
    }

    SettingsSection(
        title = "登录 U净",
        subtitle = "美的校园洗衣 · 手机号短信登录，登录状态长期有效",
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
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.loggingIn) "登录中…" else "登录")
            }
            Text(
                "登录凭据仅保存在本机加密存储，不会经过任何第三方服务器。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
        }
    }
}

// ── 账号 ──

@Composable
private fun AccountSection(state: UjingUiState, onLogout: () -> Unit) {
    SettingsSection(
        title = "U净 账号",
        subtitle = "会话过期时才需要重新登录 · 洗好提醒在系统通知的「洗衣完成提醒」渠道里关",
    ) {
        SettingItem(
            title = QzxyPhoneMask.mask(state.mobile),
            subtitle = "手机号账号 · 登录凭据存于本机（加密）",
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
        subtitle = "来自 U净 云端 · 约 30 秒滞后，出发前看一眼",
    ) {
        if (state.houses.isEmpty()) {
            Text(
                "还没有收藏洗衣房。添加宿舍楼里的洗衣房后，这里会显示洗衣机空闲数量。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        state.board.forEach { row ->
            BoardHouseRow(row = row, onRemove = { onRemove(row.house) })
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
                TextButton(onClick = onRefresh, enabled = !state.boardRefreshing) {
                    if (state.boardRefreshing) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text("刷新")
                }
            }
        }
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
        Column(Modifier.weight(1f)) {
            Text(row.house.name, style = MaterialTheme.typography.bodyLarge)
            val subtitle = when {
                row.failed -> "获取失败，点「刷新」重试"
                row.line == null -> "该店暂无洗衣机数据"
                else -> listOfNotNull(row.line.primaryText, row.line.secondaryText)
                    .joinToString(" · ")
            }
            val highlight = !row.failed && (row.line?.free ?: 0) > 0
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (highlight) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                },
            )
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
            Icon(
                HugeIcons.Delete02,
                contentDescription = "删除",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

// ── 扫码识别 ──

@Composable
private fun ScanSection(
    state: UjingUiState,
    onScan: () -> Unit,
    onRequestOrder: (UjingWashModel) -> Unit,
    onSelectTemperature: (Int) -> Unit,
) {
    SettingsSection(
        title = "扫码下单",
        subtitle = "对准洗衣机机身上的二维码（与官方 App 扫的是同一个码）",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (val scan = state.scan) {
                // 空态给一句引导，别让首屏只剩一枚孤零零的按钮
                UjingScanState.Idle -> Text(
                    "扫机身码识别设备，选好模式确认后直接下单，支付那一步会拉起支付宝。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
                UjingScanState.Parsing -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("正在识别设备…", style = MaterialTheme.typography.bodyMedium)
                }
                is UjingScanState.Failed -> Text(
                    scan.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                is UjingScanState.Ready -> ScanResult(
                    scan = scan,
                    selectedTemperatureId = state.selectedTemperatureId,
                    onSelectTemperature = onSelectTemperature,
                    onRequestOrder = onRequestOrder,
                )
            }
            Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
                Icon(HugeIcons.QrCodeScan, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("扫码识别洗衣机")
            }
        }
    }
}

@Composable
private fun ScanResult(
    scan: UjingScanState.Ready,
    selectedTemperatureId: Int?,
    onSelectTemperature: (Int) -> Unit,
    onRequestOrder: (UjingWashModel) -> Unit,
) {
    val program = scan.program
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                program.deviceTypeName.ifBlank { "洗衣机" },
                style = MaterialTheme.typography.bodyLarge,
            )
            ScanBadgePill(scan.badge)
        }
        program.deviceNo?.takeIf { it.isNotBlank() }?.let { deviceNo ->
            Text(
                "机号 $deviceNo",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
        }
        // 通信模块（诊断口径）：1/5 = 蓝牙机型——决定启动走 BLE 还是云端，点验时看这里
        Text(
            "通信模块：${UjingState.moduleTypeLabel(scan.scan.moduleType)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        )
        // 水温选择（机型开放且非烘干机才出现；档位是协议固定枚举，随确认弹层一起下单）
        if (selectedTemperatureId != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "水温",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                UjingState.temperatureOptions.forEach { (id, label) ->
                    FilterChip(
                        selected = selectedTemperatureId == id,
                        onClick = { onSelectTemperature(id) },
                        label = { Text(label) },
                    )
                }
            }
        }
        // 可选模式：服务端动态下发（价格单位分、时长单位分钟），hide 的不展示；
        // 点行 = 发起下单（空闲才可点）
        val models = program.deviceWashModel.filterNot { it.hide }
        if (models.isEmpty()) {
            Text(
                "该设备暂未返回可用模式",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
        } else {
            val orderable = scan.badge == UjingState.ScanBadge.Free && scan.scan.createOrderEnabled
            if (!orderable) {
                Text(
                    "设备使用中 / 不可下单，仅展示模式与价格",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }
            models.forEach { model ->
                SettingItem(
                    title = model.workModelName.ifBlank { "模式 ${model.workModelId}" },
                    subtitle = buildString {
                        append("¥").append(UjingState.fen2yuan(model.basePrice))
                        if (model.time > 0) append(" · ").append(model.time).append(" 分钟")
                    },
                    // 右侧落点写明动作，模式行不是「点着玩的」
                    value = if (orderable) "下单" else null,
                    enabled = orderable,
                    onClick = { onRequestOrder(model) },
                )
            }
        }
    }
}

@Composable
private fun ScanBadgePill(badge: UjingState.ScanBadge) {
    val color = when (badge) {
        UjingState.ScanBadge.Free -> MaterialTheme.colorScheme.primary
        UjingState.ScanBadge.InUse -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        UjingState.ScanBadge.Fault, UjingState.ScanBadge.Offline -> MaterialTheme.colorScheme.error
    }
    Surface(color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(50)) {
        Text(
            badge.label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

// ── 订单卡（P2） ──

/**
 * 在案订单卡：状态 + 本地倒计时 + 动作行。倒计时以快照时刻换算（不每秒请求）；
 * 服务端轮询由页面的 15 秒 LaunchedEffect 负责。
 *
 * 两条本地换算（都不发请求）：
 * - 洗涤中（status 40）：进度 = 1 − 剩余/总时长（总时长取下单时所选模式，旧快照没有就不显）；
 * - 未支付：机器独占窗口 = 下单时刻 + 2 分钟（协议口径），窗口用尽提示刷新确认。
 */
@Composable
private fun OrderSection(
    order: UjingOrderSnapshot,
    busy: Boolean,
    onRefresh: () -> Unit,
    onRetryPay: () -> Unit,
    onCancel: () -> Unit,
    onStart: () -> Unit,
    onDismiss: () -> Unit,
) {
    // 本地每秒 tick：剩余 = 快照 remainSeconds −（现在 − 快照时刻），只对运行中订单有意义
    val now by produceState(
        initialValue = System.currentTimeMillis(),
        key1 = order.snapshotAt,
    ) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val running = order.status == "40" && !order.isTerminal
    val remainSeconds = if (running) {
        order.remainSeconds - ((now - order.snapshotAt) / 1000).toInt()
    } else {
        order.remainSeconds
    }
    val terminal = order.isTerminal
    val payWindowRemain = if (!order.paid && !terminal) {
        UjingState.payWindowRemainSeconds(order.createdAt, now)
    } else {
        -1
    }
    SettingsSection(
        title = "进行中的订单",
        subtitle = if (terminal) "订单已结束" else "来自 U净 云端 · 页面停留期间自动更新",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    order.deviceName.ifBlank { "洗衣机" },
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    order.statusRemarkFallback,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                buildString {
                    append(order.modelName.ifBlank { "洗衣模式" })
                    if (order.priceFen > 0) {
                        append(" · ¥").append(UjingState.fen2yuan(order.priceFen))
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
            if (running) {
                Text(
                    "剩余 ${UjingState.remainText(remainSeconds)}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                // 洗涤进度：总时长来自下单时所选模式；旧版本快照没有（0）就不显
                if (order.durationSeconds > 0) {
                    val progress = (
                        1f - remainSeconds.coerceAtLeast(0).toFloat() / order.durationSeconds
                        ).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                        trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                    )
                }
            }
            if (payWindowRemain >= 0) {
                Text(
                    if (payWindowRemain > 0) {
                        "机器已保留，请在 ${UjingState.remainText(payWindowRemain)} 内完成支付"
                    } else {
                        "支付窗口已到点，点「刷新」确认订单状态（超时未付会自动取消）"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (payWindowRemain > 0) {
                        MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    },
                )
            }
            // 动作行：按状态给按钮（不摆点不了的按钮骗人）
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (terminal) {
                    TextButton(onClick = onDismiss) { Text("知道了") }
                } else {
                    if (!order.paid && UjingState.canCancel(order.status)) {
                        TextButton(onClick = onCancel, enabled = !busy) { Text("取消订单") }
                    }
                    if (!order.paid) {
                        Button(onClick = onRetryPay, enabled = !busy) {
                            if (busy) {
                                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(6.dp))
                            }
                            Text("去支付")
                        }
                    }
                    if (UjingState.canStart(order.status)) {
                        Button(onClick = onStart, enabled = !busy) {
                            if (busy) {
                                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
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
                )
            }
        }
    }
}

/** 订单状态文案：服务端 statusRemark 优先，本地表兜底。 */
private val UjingOrderSnapshot.statusRemarkFallback: String
    get() = UjingState.statusText(status)

/** 下单确认弹层（写操作二次确认；对齐快趣开锁 / 电费充值的确认口径）。 */
@Composable
private fun OrderConfirmDialog(
    program: UjingProgramData,
    model: UjingWashModel,
    selectedTemperatureId: Int?,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("确认下单") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    buildString {
                        append(program.deviceTypeName.ifBlank { "洗衣机" })
                        program.deviceNo?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    buildString {
                        append(model.workModelName.ifBlank { "洗衣模式" })
                        append(" · ¥").append(UjingState.fen2yuan(model.basePrice))
                        if (model.time > 0) append(" · ").append(model.time).append(" 分钟")
                        // 烘干机下单会自动带 dryTime（协议口径 = 时长/10），给用户交代一句
                        if (program.type == 2 && model.time > 0) {
                            append(" · 烘干档 ${UjingState.dryTimeFor(model.time)}")
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                selectedTemperatureId?.let { id ->
                    Text(
                        "水温：${UjingState.temperatureLabel(id)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    "下单后机器将为你保留 2 分钟，请在 2 分钟内完成支付；超时订单将自动取消。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !busy) {
                Text(if (busy) "下单中…" else "去支付")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("再想想") }
        },
    )
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
    val remainingMs = rememberCloseLock(3_000L)
    val locked = remainingMs > 0L
    AlertDialog(
        // 锁定期间点遮罩 / 返回键也不放走；解锁后点遮罩视为已读
        onDismissRequest = { if (!locked) onConfirm() },
        title = { Text("U净 使用须知") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                NoticeItems.forEachIndexed { index, item ->
                    Text(
                        "${index + 1}. $item",
                        style = MaterialTheme.typography.bodyMedium,
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
