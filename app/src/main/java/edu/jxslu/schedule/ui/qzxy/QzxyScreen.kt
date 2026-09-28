package edu.jxslu.schedule.ui.qzxy

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.qzxy.QzxyBluetoothScanner
import edu.jxslu.schedule.data.qzxy.QzxyBoundDevice
import edu.jxslu.schedule.data.qzxy.QzxyDeviceInfo
import edu.jxslu.schedule.data.qzxy.QzxyScannedDevice
import edu.jxslu.schedule.domain.QzxyWatering
import edu.jxslu.schedule.domain.QzxyWateringFormat
import edu.jxslu.schedule.domain.QzxyPhoneMask
import edu.jxslu.schedule.ui.common.AppCardRow
import edu.jxslu.schedule.ui.common.AppNoticeVisuals
import edu.jxslu.schedule.ui.common.AppPermissions
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.NoticeTone
import edu.jxslu.schedule.ui.common.SettingSwitchRow
import edu.jxslu.schedule.ui.common.SettingsSection
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.Droplet
import me.rerere.hugeicons.stroke.InformationCircle
import me.rerere.hugeicons.stroke.Logout04
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.hugeicons.stroke.WalletAdd01
import java.math.BigDecimal

/**
 * 趣智校园开热水页（DESIGN §3.18）。
 *
 * 页面按「此刻要做什么」排，一屏一个主动作：账号一行、设备一行、中间是随状态变化的
 * 状态卡，账单在下，协议调试整块搬到「诊断与调试」二级页。
 *
 * 为什么不再把调试信息留在页内：链路 2026-09-27 真机跑通之后，设备现场十四行、
 * 服务表、试签名、试清除、日志都只在协议对不上时才有用，常显会把日常动作挤到
 * 看不见的地方。需要时点页尾那一行进诊断页，内容一行没少。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QzxyScreen(
    onBack: () -> Unit = {},
    /** 打开诊断页。带当前设备地址过去，诊断页据此选中同一台。 */
    onOpenDiagnostics: (String?) -> Unit = {},
    /** 小组件「去开水」直达（WaterAutoStart）：进页自动开阀一次；普通入口保持 false。 */
    autoStart: Boolean = false,
    viewModel: QzxyViewModel = viewModel(
        factory = QzxyViewModel.Factory(
            Graph.qzxy(LocalContext.current),
            QzxyBluetoothScanner(LocalContext.current),
            Graph.qzxyLink(LocalContext.current),
            Graph.qzxyFlowLock,
            Graph.qzxyDevices(LocalContext.current),
            Graph.qzxyDebug(LocalContext.current),
            Graph.qzxyClear(LocalContext.current),
            Graph.qzxyWatering(LocalContext.current),
        ),
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberAppHaptics()
    var showDeviceSheet by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    // 发起过充值才在下次 ON_RESUME 刷余额：页面里还有权限弹窗等其他 resume 路径，
    // 无条件刷新会多打两轮请求（见下面的生命周期观察者）
    var awaitingRecharge by remember { mutableStateOf(false) }

    // 桌面胶囊直达：首帧触发一次（VM 内有防重入，未登录 / 用水中 / 流程进行中会自行跳过）
    LaunchedEffect(autoStart) {
        if (autoStart) viewModel.requestAutoOpen()
    }

    // 蓝牙扫描要运行时权限（Android 12+ 是「附近的设备」，更低版本是定位）。
    // 授权完直接接上这次点击要做的扫描，省得用户再点一次。
    val scanPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.all { granted -> granted }) {
            viewModel.startScan()
        } else {
            scope.launch {
                snackbar.showSnackbar(
                    AppNoticeVisuals(
                        "缺少「附近的设备」权限，扫不到热水器。可到系统设置里为本应用打开该权限后重试。",
                        tone = NoticeTone.Warning,
                    ),
                )
            }
        }
    }
    val requestScan: () -> Unit = {
        val missing = AppPermissions.missingBluetoothScan(context)
        if (missing.isEmpty()) {
            viewModel.startScan()
        } else {
            scanPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is QzxyEvent.Notice -> snackbar.showSnackbar(
                    AppNoticeVisuals(event.text, tone = event.tone),
                )
            }
        }
    }

    // 开阀与结算是一串「连蓝牙 → 下单 → 写设备」的动作，中途退出会把协程掐断：
    // 服务端可能已经预扣，设备却没收到开阀数据。处理期间拦一下返回，只提示不退出；
    // 真卡住了流程会自己超时，几秒后按钮就回来了。
    BackHandler(enabled = state.flow is QzxyFlowState.Working) {
        scope.launch {
            snackbar.showSnackbar(
                AppNoticeVisuals(
                    "正在处理，等这一步跑完再退出（卡住的话它会自己超时）",
                    tone = NoticeTone.Warning,
                ),
            )
        }
    }

    // 账单只在页面里拉：卡片那份实例不需要它，见 QzxyViewModel.loadBillsOnce。
    // 跟着登录态跑：首启引导里登录过之后进页面、或在页面里登录，都要拉到当月账单
    LaunchedEffect(state.loggedIn) {
        if (state.loggedIn) viewModel.loadBillsOnce()
    }

    // 充值在支付宝里完成，回到本页（ON_RESUME）时余额要重拉，否则还显示充值前的数。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && awaitingRecharge) {
                awaitingRecharge = false
                viewModel.refreshAccount()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val openRecharge: () -> Unit = {
        if (QzxyRecharge.open(context)) {
            awaitingRecharge = true
        } else {
            scope.launch {
                snackbar.showSnackbar(
                    AppNoticeVisuals(
                        "没找到能打开支付宝的应用。充值在支付宝的趣智校园小程序里完成，先装一个支付宝。",
                        tone = NoticeTone.Warning,
                    ),
                )
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("趣智校园") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(HugeIcons.ArrowLeft01, contentDescription = "返回")
                    }
                },
                actions = {
                    if (state.loggedIn) {
                        IconButton(onClick = { confirmLogout = true }) {
                            Icon(HugeIcons.Logout04, contentDescription = "退出登录")
                        }
                    }
                },
            )
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        // 下拉刷新：与消费流水页 / 缴费账单页同一套 PullToRefreshBox（DESIGN §3.18）。
        // padding 收在刷新容器上而不是列表上：Scaffold 的内容从 (0,0) 铺满整屏、顶栏压在
        // 它上面，指示器挂在容器顶边时整条滑入轨迹都在顶栏后面，得拖过阈值一大截才露出半圈。
        PullToRefreshBox(
            isRefreshing = state.refreshingBalance,
            onRefresh = {
                haptics.tap()
                viewModel.refreshAccount()
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (!state.loggedIn) {
                    LoginSection(state, viewModel)
                    // 登出或会话失效时水可能还在流、预扣还挂着，这条提醒不能跟着登录态一起消失
                    state.watering?.let { UnsettledNotice(it, viewModel) }
                } else {
                    AccountRow(state, viewModel, onRecharge = openRecharge)
                    DeviceCard(state = state, onPick = { showDeviceSheet = true })
                    StatusCard(
                        state = state,
                        viewModel = viewModel,
                        onOpenDiagnostics = onOpenDiagnostics,
                    )
                    BillSection(state, viewModel)
                    DiagnosticEntry(onOpen = { onOpenDiagnostics(state.selected?.address) })
                }
                DisplaySettingSection()
                Disclaimer()
            }
        }
    }

    if (showDeviceSheet) {
        DevicePickerSheet(
            state = state,
            viewModel = viewModel,
            onRequestScan = requestScan,
            onDismiss = { showDeviceSheet = false },
        )
    }

    // 退出二次确认（2026-09-27）：顶栏图标就在返回键旁边，一次误触就得重新收短信才能回来
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("退出趣智校园登录？") },
            text = {
                Text(
                    "将清除本机保存的趣智校园会话，今日页卡片回到未登录态。" +
                        "如果水还在流，退出前先点「结束用水」结算。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmLogout = false
                        viewModel.logout()
                    },
                ) { Text("退出登录") }
            },
            dismissButton = {
                TextButton(onClick = { confirmLogout = false }) { Text("取消") }
            },
        )
    }
}

// ── 登录 ──

@Composable
private fun LoginSection(state: QzxyUiState, viewModel: QzxyViewModel) {
    SettingsSection(
        title = "登录趣智校园",
        subtitle = "用你在趣智校园 App / 小程序注册的手机号登录。本应用不提供注册；" +
            "充值在登录后点账号一行的「充值」，跳到官方支付宝小程序完成。",
    ) {
        OutlinedTextField(
            value = state.phone,
            onValueChange = viewModel::updatePhone,
            label = { Text("手机号") },
            singleLine = true,
            isError = state.phoneError != null,
            supportingText = state.phoneError?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.useSmsLogin) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = state.code,
                    onValueChange = viewModel::updateCode,
                    label = { Text("短信验证码") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { viewModel.sendCode() },
                    enabled = !state.sendingCode,
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Text(if (state.sendingCode) "发送中…" else "获取验证码")
                }
            }
        } else {
            OutlinedTextField(
                value = state.password,
                onValueChange = viewModel::updatePassword,
                label = { Text("密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
        }
        Button(
            onClick = { viewModel.login() },
            enabled = !state.loggingIn,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .height(48.dp),
        ) {
            Text(if (state.loggingIn) "登录中…" else "登录")
        }
        TextButton(
            onClick = { viewModel.toggleUseSmsLogin() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.useSmsLogin) "改用密码登录" else "改用验证码登录")
        }

        // 会话串登录：与胖乖的 Token 登录同一个交互。差别在凭据形态——
        // 那边服务端签发的 token 单独可用，这边得凑齐 loginCode + projectId 等字段。
        if (!state.showSessionLogin) {
            OutlinedButton(
                onClick = { viewModel.toggleSessionLogin() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("会话串登录")
            }
            Text(
                text = "已从其他渠道拿到会话串时可直接粘贴登录；手机号登录会使旧会话失效。" +
                    "会话串等于账号通行证，别外发。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
        } else {
            OutlinedTextField(
                value = state.sessionLoginInput,
                onValueChange = viewModel::updateSessionLoginInput,
                label = { Text("会话串") },
                minLines = 2,
                maxLines = 4,
                supportingText = {
                    Text("粘登录响应原文即可，JSON / 表单 / 逐行键值三种写法都认")
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { viewModel.loginWithSession() },
                enabled = !state.sessionLoggingIn && state.sessionLoginInput.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Text(if (state.sessionLoggingIn) "验证中…" else "会话登录")
            }
            TextButton(
                onClick = { viewModel.toggleSessionLogin() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("收起会话串登录")
            }
        }
    }
}

/** 未登录时的未结算提醒：水可能还在流，得让人看见，也得有个出口。 */
@Composable
private fun UnsettledNotice(watering: QzxyWatering, viewModel: QzxyViewModel) {
    SettingsSection(
        title = "上次开阀还没结算",
        subtitle = "${QzxyWateringFormat.clockText(watering.startedAtMillis)} 在" +
            "「${watering.deviceName}」开的阀。登录后点「结束用水」按实际用量结算。",
    ) {
        TextButton(onClick = { viewModel.abandonWatering() }) {
            Text("水已经停了，标记为已结束")
        }
    }
}

// ── 账号与设备 ──

/**
 * 账户卡片：余额 + 账户 + 刷新 + 充值。
 *
 * 充值做成带图标的实心按钮而不是文字按钮：它要跳到另一个应用里完成付款，
 * 点不着或看不出来都会让人以为功能没做。卡片底下那行写清钱进哪个账户、谁负责退款，
 * 与支付宝页面上显示的商户对得上。
 */
@Composable
private fun AccountRow(
    state: QzxyUiState,
    viewModel: QzxyViewModel,
    onRecharge: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val accountLine = buildString {
        append(state.schoolName.ifBlank { "水控账户" })
        if (state.accountPhone.isNotBlank()) {
            append(" · ")
            append(
                if (state.showPhone) {
                    state.accountPhone
                } else {
                    QzxyPhoneMask.mask(state.accountPhone)
                },
            )
        }
    }
    SettingsSection(title = "余额与充值") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                HugeIcons.Droplet,
                contentDescription = null,
                tint = primary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = when {
                        state.balance != null -> "¥${state.balance?.text}"
                        state.balanceLoaded -> "余额暂不可用"
                        else -> "余额读取中…"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                // 手机号默认遮蔽，点眼睛才展开：页面会被截图、会被旁人瞥见，
                // 与「我的」页学号同一个口径
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = accountLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = onSurface.copy(alpha = 0.55f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (state.accountPhone.isNotBlank()) {
                        Icon(
                            imageVector = if (state.showPhone) HugeIcons.ViewOff else HugeIcons.View,
                            contentDescription = if (state.showPhone) "隐藏手机号" else "显示手机号",
                            tint = onSurface.copy(alpha = 0.5f),
                            modifier = Modifier
                                .padding(start = 6.dp)
                                .size(16.dp)
                                .clickable { viewModel.toggleShowPhone() },
                        )
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            FilledTonalButton(onClick = onRecharge) {
                Icon(
                    HugeIcons.WalletAdd01,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("充值")
            }
        }
        Text(
            text = "点「充值」跳到支付宝小程序付款，钱进上面这个账户；充值与退款由学校运营方负责。",
            style = MaterialTheme.typography.bodySmall,
            color = onSurface.copy(alpha = 0.5f),
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/**
 * 设备一行 + 「更换」。
 *
 * 用水中不给换：那一台正在放水，切到别的设备会让结束用水的按钮连错机器。
 */
@Composable
private fun DeviceCard(state: QzxyUiState, onPick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val watering = state.watering
    val name = watering?.deviceName
        ?: state.selected?.let { device ->
            state.deviceInfos[device.addressKey]?.deviceName?.takeIf { it.isNotBlank() }
                ?: device.name
        }
        // 没选中也要认得上次那台：进页面时设备名是空的会让人以为绑定丢了
        ?: state.lastUsedDevice?.name
    val subtitle = when {
        watering != null -> "用水中，暂时不能换设备"
        state.flow is QzxyFlowState.Working -> "正在处理，等这一步跑完再换"
        state.selected != null -> "已选中 · 点右侧可换"
        state.lastUsedDevice != null -> "上次用过 · 点右侧可换"
        else -> "还没选设备，点右侧挑一台"
    }
    // 进行中不许换：换设备会走 preconnect，它会把正在用的那条链路关掉重建，
    // 把开阀/结算打断在半路——而那时服务端可能已经下过单、扣过预扣
    val canPick = watering == null && state.flow !is QzxyFlowState.Working
    AppCardRow(
        modifier = Modifier.fillMaxWidth(),
        onClick = if (canPick) onPick else null,
        onClickLabel = "选择热水器",
    ) {
        Icon(
            HugeIcons.Droplet,
            contentDescription = null,
            tint = if (name != null) primary else onSurface.copy(alpha = 0.45f),
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = name ?: "未选择热水器",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = onSurface.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = "更换 ›",
            style = MaterialTheme.typography.bodySmall,
            color = if (watering == null) primary else onSurface.copy(alpha = 0.35f),
            maxLines = 1,
        )
    }
}

// ── 状态卡 ──

/**
 * 主状态卡：一个时刻只给一个主动作。
 *
 * 分支顺序即优先级——进行中的流程盖过一切（此时点别的按钮没有意义），
 * 结算结果盖过用水中（结算完 store 已经清了，这一条只是防竞态），
 * 用水中盖过失败（结束用水失败时水还在流，主按钮仍是结束用水）。
 */
@Composable
private fun StatusCard(
    state: QzxyUiState,
    viewModel: QzxyViewModel,
    onOpenDiagnostics: (String?) -> Unit,
) {
    var failureDetail by remember { mutableStateOf<QzxyFlowState.Failed?>(null) }
    val flow = state.flow
    SettingsSection(
        title = "开热水",
        // 副标题固定，动态状态说明放进内容区跟着一起做高度动画：副标题文字长短不一时
        // 会在换行与不换行之间跳，那是另一处高度抖动
        subtitle = "出水按实际用量计费，结束后从趣智校园账户扣",
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 待机态两行、用水中六行，高度差一百多 dp。给个下限兜住最短的两个状态，
                // 再让内容淡入淡出、高度平滑过渡（AnimatedContent 自带尺寸动画）。
                // 之前是硬切，点「开始用水」时整页会往上顶一截。
                .heightIn(min = 112.dp),
        ) {
            Text(
                text = statusSubtitle(state),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            AnimatedContent(
                targetState = statusContentOf(state),
                transitionSpec = {
                    fadeIn(animationSpec = tween(200)) togetherWith
                        fadeOut(animationSpec = tween(120))
                },
                label = "qzxy-status",
            ) { content ->
                // 每个形态都是好几行（按钮、说明、提示），必须自己竖着排：
                // AnimatedContent 的内容槽是 Box，多个子元素会直接叠在一起
                Column(Modifier.fillMaxWidth()) {
                    when (content) {
                        // 过渡期间旧内容还在，读最新的 flow 只影响文案，不影响类型安全
                        QzxyStatusContent.Working ->
                            WorkingBlock((state.flow as? QzxyFlowState.Working)?.step ?: "正在处理")
                        is QzxyStatusContent.Settlement ->
                            SettlementBlock(content.settlement, state, viewModel)
                        is QzxyStatusContent.Watering ->
                            WateringBlock(content.watering, state.flow, viewModel)
                        is QzxyStatusContent.Failed -> FailureBlock(
                            failure = content.failure,
                            state = state,
                            viewModel = viewModel,
                            onShowDetail = { failureDetail = content.failure },
                        )
                        QzxyStatusContent.Idle -> IdleBlock(state, viewModel)
                    }
                }
            }
        }
    }
    failureDetail?.let { failure ->
        FailedDetailDialog(
            failure = failure,
            onOpenDiagnostics = { onOpenDiagnostics(state.selected?.address) },
            onDismiss = { failureDetail = null },
        )
    }
}

/**
 * 状态卡的展示形态。
 *
 * 带数据的三个形态把数据装进来（而不是在内容里读最新的 `state`）：AnimatedContent
 * 过渡期间旧内容仍在合成，那时 `state` 已经换成新状态，`as Working` 这类强转会直接崩。
 * Working 只带标记、步骤文字现读——步骤每变一次就触发一次淡入淡出反而更闪。
 */
private sealed interface QzxyStatusContent {
    data object Working : QzxyStatusContent
    data class Settlement(val settlement: QzxySettlement) : QzxyStatusContent
    data class Watering(val watering: QzxyWatering) : QzxyStatusContent
    data class Failed(val failure: QzxyFlowState.Failed) : QzxyStatusContent
    data object Idle : QzxyStatusContent
}

private fun statusContentOf(state: QzxyUiState): QzxyStatusContent {
    val flow = state.flow
    val settlement = state.lastSettlement
    val watering = state.watering
    return when {
        flow is QzxyFlowState.Working -> QzxyStatusContent.Working
        settlement != null -> QzxyStatusContent.Settlement(settlement)
        watering != null -> QzxyStatusContent.Watering(watering)
        flow is QzxyFlowState.Failed -> QzxyStatusContent.Failed(flow)
        else -> QzxyStatusContent.Idle
    }
}

private fun statusSubtitle(state: QzxyUiState): String = when {
    state.flow is QzxyFlowState.Working -> "正在处理，别离开这一页"
    state.lastSettlement != null -> "本次用水已结算"
    state.watering != null -> "设备在放水，用完点结束用水结算"
    state.flow is QzxyFlowState.Failed -> "上一次操作没成功，按下面的提示来"
    !state.hasUsableDevice -> "先在上面选一台热水器"
    else -> "站在热水器旁边点开始用水"
}

@Composable
private fun IdleBlock(state: QzxyUiState, viewModel: QzxyViewModel) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Button(
        // 没主动选过设备时用上次那台兜底（与今日页面板同口径），
        // 省掉「进页面 → 选设备 → 再点开始」这三步
        onClick = { viewModel.openValveFromCard() },
        enabled = state.hasUsableDevice,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .height(48.dp),
    ) {
        Text("开始用水")
    }
    if (!state.hasUsableDevice) {
        Text(
            text = "还没选设备，先点上面那行「更换」挑一台。",
            style = MaterialTheme.typography.bodySmall,
            color = onSurface.copy(alpha = 0.55f),
            modifier = Modifier.padding(top = 8.dp),
        )
    } else {
        // 待机态填最近一次用水：既省下卡片底部那块空白，也是开机后最想知道的一条
        // （上次花了多少、什么时候）。数据来自服务端账单，当月没有就直说。
        val lastBill = state.bills.firstOrNull()
        Text(
            text = when {
                lastBill != null ->
                    "最近一次 ${billTimeText(lastBill.consumeDate)} · ¥${lastBill.consumeMoney ?: "-"}"
                // 加载中与「确实没有」不能都说成没有，那是在报假消息
                state.billLoaded -> "本月还没有用水记录"
                else -> "正在读取用水记录…"
            },
            style = MaterialTheme.typography.bodySmall,
            color = onSurface.copy(alpha = 0.55f),
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

/**
 * 账单时间压成「9月27日 22:11」。服务端给的是 `2026-09-27 22:11:53` 这种定长串，
 * 格式对不上就原样返回，不猜。
 */
private fun billTimeText(raw: String?): String {
    val text = raw?.trim().orEmpty()
    if (text.length < 16) return text
    val month = text.substring(5, 7).toIntOrNull() ?: return text
    val day = text.substring(8, 10).toIntOrNull() ?: return text
    return "${month}月${day}日 ${text.substring(11, 16)}"
}

@Composable
private fun WorkingBlock(step: String) {
    Row(
        modifier = Modifier.padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            text = step,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
        )
    }
    // 给个时间预期：这一段要连蓝牙再下单，几秒内没动才是异常
    Text(
        text = "连接热水器并下单，通常几秒",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        modifier = Modifier.padding(top = 8.dp),
    )
}

/**
 * 用水中。计时是这一块的主角：它同时回答「水还开着吗」与「开了多久」，
 * 也是用户判断该不该去结算的依据。
 */
@Composable
private fun WateringBlock(
    watering: QzxyWatering,
    flow: QzxyFlowState,
    viewModel: QzxyViewModel,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val clock = rememberQzxyWateringClock(watering.startedAtMillis)
    val failed = flow as? QzxyFlowState.Failed

    Row(
        modifier = Modifier.padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "用水中",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier
                .background(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(50),
                )
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "开始于 ${QzxyWateringFormat.clockText(watering.startedAtMillis)}",
            style = MaterialTheme.typography.bodySmall,
            color = onSurface.copy(alpha = 0.55f),
        )
    }
    Text(
        text = clock,
        // 等宽数字（tnum）：默认字体的「1」比「8」窄，每秒刷新会让整行左右抽动，
        // 还会连带触发上面那个高度动画
        style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"),
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 6.dp),
    )
    Text(
        text = watering.preDeductMilli?.let {
            "服务端预扣 ${QzxyWateringFormat.money(it)}，结束后按实际用量结算，多扣的部分退回"
        } ?: "结束后按实际用量结算，多扣的部分退回",
        style = MaterialTheme.typography.bodySmall,
        color = onSurface.copy(alpha = 0.6f),
        modifier = Modifier.padding(top = 2.dp),
    )
    if (failed != null) {
        Text(
            text = failed.reason,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 10.dp),
        )
        failed.detail?.let { detail ->
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = onSurface.copy(alpha = 0.55f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    Button(
        onClick = { viewModel.stopWater() },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .height(48.dp),
    ) {
        Text(if (failed != null) "重试结束用水" else "结束用水")
    }
    Text(
        text = "结算要走蓝牙，点之前先站到热水器旁边",
        style = MaterialTheme.typography.bodySmall,
        color = onSurface.copy(alpha = 0.55f),
        modifier = Modifier.padding(top = 8.dp),
    )
    TextButton(onClick = { viewModel.abandonWatering() }) {
        Text("水已经停了，标记为已结束")
    }
}

@Composable
private fun SettlementBlock(
    settlement: QzxySettlement,
    state: QzxyUiState,
    viewModel: QzxyViewModel,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Text(
        text = "已结束用水",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 12.dp),
    )
    Text(
        text = QzxyWateringFormat.money(settlement.consumeMoneyMilli),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp),
    )
    val parts = buildList {
        settlement.durationMillis?.let { add("本次用水 ${QzxyWateringFormat.duration(it)}") }
        state.balance?.let { add("余额 ¥${it.text}") }
    }
    if (parts.isNotEmpty()) {
        Text(
            text = parts.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = onSurface.copy(alpha = 0.6f),
        )
    }
    settlement.note?.let { note ->
        Text(
            text = note,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
    Text(
        text = "金额来自服务端账单，不是本地推算",
        style = MaterialTheme.typography.bodySmall,
        color = onSurface.copy(alpha = 0.5f),
        modifier = Modifier.padding(top = 6.dp),
    )
    OutlinedButton(
        onClick = { viewModel.dismissSettlement() },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .height(48.dp),
    ) {
        Text("完成")
    }
}

@Composable
private fun FailureBlock(
    failure: QzxyFlowState.Failed,
    state: QzxyUiState,
    viewModel: QzxyViewModel,
    onShowDetail: () -> Unit,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Text(
        text = failure.reason,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(top = 12.dp),
    )
    failure.detail?.let { detail ->
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = onSurface.copy(alpha = 0.6f),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    Button(
        onClick = { viewModel.openValveFromCard() },
        enabled = state.hasUsableDevice,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .height(48.dp),
    ) {
        Text("重试开阀")
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = { viewModel.clearDeviceRecord() },
            enabled = state.selected != null,
            modifier = Modifier
                .weight(1f)
                .height(42.dp),
        ) {
            Text("清除设备记录")
        }
        OutlinedButton(
            onClick = onShowDetail,
            modifier = Modifier
                .weight(1f)
                .height(42.dp),
        ) {
            Text("详情")
        }
    }
}

/** 失败详情：原始数据不铺在页面上，要看的自己点开，或者进诊断页看全。 */
@Composable
private fun FailedDetailDialog(
    failure: QzxyFlowState.Failed,
    onOpenDiagnostics: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("失败详情") },
        text = {
            Column {
                Text(failure.reason, style = MaterialTheme.typography.bodyMedium)
                failure.detail?.let { detail ->
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Text(
                    text = "设备服务表、原始数据体与调试日志都在「诊断与调试」页。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    onOpenDiagnostics()
                },
            ) { Text("去诊断页") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

// ── 设备弹层 ──

/**
 * 选择热水器：已绑定在上、附近设备在下。
 *
 * 扫描搬进弹层的原因：一次能扫出十几台，列表边扫边长，铺在页面上会把下面的账单
 * 与入口一路推走。放在弹层里，列表怎么长都不影响页面。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DevicePickerSheet(
    state: QzxyUiState,
    viewModel: QzxyViewModel,
    onRequestScan: () -> Unit,
    onDismiss: () -> Unit,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                text = "选择热水器",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "绑定过的不用扫蓝牙，点一下就能连",
                style = MaterialTheme.typography.bodySmall,
                color = onSurface.copy(alpha = 0.55f),
            )

            if (state.boundDevices.isNotEmpty()) {
                Text(
                    text = "已绑定",
                    style = MaterialTheme.typography.labelLarge,
                    color = onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 14.dp),
                )
                state.boundDevices.forEach { bound ->
                    DeviceRow(
                        title = bound.name,
                        subtitle = "已绑定 · 不用扫描直接连",
                        selected = state.selected?.address.equals(bound.address, ignoreCase = true),
                        onClick = {
                            viewModel.selectBoundDevice(bound)
                            onDismiss()
                        },
                        action = {
                            TextButton(onClick = { viewModel.unbindDevice(bound.address) }) {
                                Text("解绑")
                            }
                        },
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "附近设备",
                    style = MaterialTheme.typography.labelLarge,
                    color = onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.weight(1f),
                )
                if (state.scanning) {
                    TextButton(onClick = { viewModel.stopScan() }) { Text("停止") }
                } else {
                    TextButton(onClick = onRequestScan) { Text("扫描") }
                }
            }
            if (state.scanning) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "扫描中，站到热水器旁边",
                        style = MaterialTheme.typography.bodySmall,
                        color = onSurface.copy(alpha = 0.6f),
                    )
                }
            }
            state.devices.forEach { device ->
                val info = state.deviceInfos[device.addressKey]
                // 扫到的可能正是已绑定的那台，右侧别再给一个「绑定」按钮
                val alreadyBound = state.boundDevices.any { bound ->
                    bound.address.equals(device.address, ignoreCase = true)
                }
                DeviceRow(
                    // 服务端有登记就用它的设备名（含楼栋楼层房间），没有才退回广播名
                    title = info?.deviceName?.takeIf { it.isNotBlank() } ?: device.name,
                    subtitle = deviceSubtitle(device, info),
                    selected = state.selected?.address == device.address,
                    onClick = {
                        viewModel.selectDevice(device)
                        onDismiss()
                    },
                    action = {
                        if (alreadyBound) {
                            Text(
                                text = "已绑定",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                            )
                        } else {
                            TextButton(onClick = { viewModel.bindDevice(device) }) { Text("绑定") }
                        }
                    },
                )
            }
            if (!state.scanning && state.devices.isEmpty()) {
                Text(
                    text = "还没扫到设备。若一直为空，确认手机蓝牙已开、已授予「附近的设备」权限，" +
                        "并站在热水器旁边重试。",
                    style = MaterialTheme.typography.bodySmall,
                    color = onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/**
 * 设备列表项的副行：查到了服务端信息就把位置补上，并把广播名留在最前面——
 * 它是现场唯一不会骗人的标识，服务端那条记录有可能是别人登记错的。
 */
private fun deviceSubtitle(device: QzxyScannedDevice, info: QzxyDeviceInfo?): String = buildList {
    if (info?.deviceName?.isNotBlank() == true) add(device.name)
    info?.let { detail ->
        val place = listOfNotNull(detail.buildingName, detail.floorName, detail.roomName)
            .filter { it.isNotBlank() }
            .joinToString("")
        if (place.isNotBlank()) add(place)
    }
    add(device.address)
    add("信号 ${device.rssi} dBm")
    add("编号 ${device.deviceKey}")
}.joinToString(" · ")

@Composable
private fun DeviceRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    action: (@Composable () -> Unit)? = null,
) {
    val haptics = rememberAppHaptics()
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .background(
                color = if (selected) accent.copy(alpha = 0.12f) else Color.Transparent,
                shape = RoundedCornerShape(10.dp),
            )
            .clickable {
                haptics.tap()
                onClick()
            }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            HugeIcons.Droplet,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
        }
        action?.invoke()
    }
}

// ── 账单与页尾 ──

/**
 * 消费记录（DESIGN §4.30）：按月拉趣智校园账单。
 *
 * 金额只认服务端账单（`order/query/account/bill/list`），不做本地推算——预扣与实扣
 * 是两笔账，之前把预扣当实扣读过一次，口径只有一个来源才不会再错。合计只是把
 * 服务端这一页返回的金额相加，不参与任何计费判断。
 */
@Composable
private fun BillSection(state: QzxyUiState, viewModel: QzxyViewModel) {
    // 一个月二三十笔很常见，全展开会把页面拉得很长，默认只给最近几笔
    var expanded by remember { mutableStateOf(false) }
    SettingsSection(
        title = "消费记录",
        subtitle = "按月查热水消费，金额与官方账单同源。",
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { viewModel.shiftBillMonth(-1) }) { Text("上月") }
            Text(
                text = state.billMonth.ifBlank { "—" },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { viewModel.shiftBillMonth(1) }) { Text("下月") }
        }
        when {
            state.loadingBills -> Text(
                "读取中…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                modifier = Modifier.padding(top = 4.dp),
            )

            !state.billLoaded -> Unit

            state.bills.isEmpty() -> Text(
                "这个月没有消费记录",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                modifier = Modifier.padding(top = 4.dp),
            )

            else -> {
                val total = state.bills
                    .mapNotNull { bill -> bill.consumeMoney?.trim()?.toBigDecimalOrNull() }
                    .fold(BigDecimal.ZERO, BigDecimal::add)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Text(
                        text = "¥%.2f".format(total),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "本月合计 · ${state.bills.size} 笔",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
                val visible = if (expanded) state.bills else state.bills.take(BILL_PREVIEW_COUNT)
                visible.forEach { bill ->
                    Column(Modifier.padding(top = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = bill.description ?: bill.deviceDescription ?: "热水消费",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "¥${bill.consumeMoney ?: "-"}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Text(
                            bill.consumeDate.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        )
                    }
                }
                if (state.bills.size > BILL_PREVIEW_COUNT) {
                    TextButton(
                        onClick = { expanded = !expanded },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (expanded) {
                                "收起"
                            } else {
                                "展开全部 ${state.bills.size} 笔"
                            },
                        )
                    }
                }
            }
        }
    }
}

/** 账单默认展开几笔，其余收在「展开全部」后面。 */
private const val BILL_PREVIEW_COUNT = 5

/** 诊断入口：日常用不到，收成一行放在账单下面。 */
@Composable
private fun DiagnosticEntry(onOpen: () -> Unit) {
    AppCardRow(
        modifier = Modifier.fillMaxWidth(),
        onClick = onOpen,
        onClickLabel = "打开诊断与调试",
    ) {
        Icon(
            HugeIcons.InformationCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = "诊断与调试",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "设备现场、服务表、试签名、调试日志",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
        }
        Text(
            text = "›",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
        )
    }
}

@Composable
private fun DisplaySettingSection() {
    val context = LocalContext.current
    val prefs = remember(context) { Graph.displayPrefs(context) }
    val enabled by prefs.qzxyCardEnabled.collectAsStateWithLifecycle(initialValue = true)
    val scope = rememberCoroutineScope()
    SettingsSection(title = "显示") {
        SettingSwitchRow(
            title = "在今日页显示趣智校园卡片",
            subtitle = "关掉后今日页只剩胖乖生活卡，独占一整行",
            checked = enabled,
            onCheckedChange = { value ->
                scope.launch { Graph.repository(context).setQzxyCardEnabled(value) }
            },
        )
    }
}

@Composable
private fun Disclaimer() {
    Column(Modifier.padding(bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                HugeIcons.InformationCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "免责声明",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
        Text(
            text = "趣智校园开热水是第三方非官方功能，通信协议来自公开逆向资料，" +
                "与校方及设备厂商无关。开阀照常从你的趣智校园账户扣费，本应用不绕过计费。" +
                "接口或设备固件变更都可能让它失效。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
