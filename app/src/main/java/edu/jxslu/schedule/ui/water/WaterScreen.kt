package edu.jxslu.schedule.ui.water

import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.prefs.DisplayPrefs
import edu.jxslu.schedule.data.qiekj.DeviceItem
import edu.jxslu.schedule.data.qiekj.OrderHistoryItem
import edu.jxslu.schedule.data.qiekj.ServerOrderItem
import edu.jxslu.schedule.domain.UnlockFlowState
import edu.jxslu.schedule.domain.UnlockResult
import edu.jxslu.schedule.domain.QzxyPhoneMask
import edu.jxslu.schedule.domain.calculateActualCost
import edu.jxslu.schedule.domain.cashPaidAmount
import edu.jxslu.schedule.domain.ticketPaidAmount
import edu.jxslu.schedule.ui.common.AppCardRow
import edu.jxslu.schedule.ui.common.AppNoticeVisuals
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.DialogWindowFadeEffect
import edu.jxslu.schedule.ui.common.FooterDisclaimer
import edu.jxslu.schedule.ui.common.NoticeTone
import edu.jxslu.schedule.ui.common.SettingChoiceRow
import edu.jxslu.schedule.ui.common.SettingItem
import edu.jxslu.schedule.ui.common.SettingSwitchRow
import edu.jxslu.schedule.ui.common.SettingsSection
import edu.jxslu.schedule.ui.common.WaterUnlockButton
import edu.jxslu.schedule.ui.common.postNotice
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import edu.jxslu.schedule.ui.common.showNotice
import edu.jxslu.schedule.ui.me.MeViewModel
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Droplet
import me.rerere.hugeicons.stroke.GlassWater
import me.rerere.hugeicons.stroke.History
import me.rerere.hugeicons.stroke.Logout04
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.hugeicons.stroke.Wallet01
import me.rerere.hugeicons.stroke.WalletAdd01

private val DATE_FORMAT = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

/**
 * 胖乖生活页（DESIGN §3.4 / §4.16，页内核心动作是开水）。
 * 交互：未登录 → 登录卡（验证码 / Token 两种）；已登录 → 余额行、设备选择、
 * 大按钮开水、状态原地切换（Idle/进行中/成功/失败）、订单快照列表。
 * 风格克制：无大圆角卡片、无 elevation，错误一行主因 + 详情弹窗。
 *
 * 2026-09-20 起「开水设置」子页并入本页尾部（`WaterSettingsSection`，登录与
 * 未登录两态共用）：**显示开水卡片**开关 + **点击方式**（单击/双击）。
 * 「我的」侧入口名 2026-09-20 起为「胖乖生活」，2026-09-26 去掉「一键开水」后缀，
 * 与页标题、账号卡三格统一；不再有独立设置页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WaterScreen(
    onBack: () -> Unit = {},
    /** 小组件「去开水」直达（WaterAutoStart）：进页自动开水一次；普通入口保持 false。 */
    autoStart: Boolean = false,
    viewModel: WaterViewModel = viewModel(
        factory = WaterViewModel.Factory(Graph.qiekj(LocalContext.current)),
    ),
    // 开水卡显示与点击方式的读写（原 WaterSettingsScreen 的 ViewModel，2026-09-20 并入）
    prefsViewModel: MeViewModel = viewModel(
        factory = MeViewModel.Factory(Graph.repository(LocalContext.current)),
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val prefsState by prefsViewModel.uiState.collectAsStateWithLifecycle()
    val haptics = rememberAppHaptics()
    val context = LocalContext.current
    var showDeviceSheet by remember { mutableStateOf(false) }
    var detailItem by remember { mutableStateOf<Any?>(null) }
    var confirmLogout by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 桌面胶囊直达：首帧触发一次（VM 内有防重入，未登录 / 流程进行中会自行跳过）
    LaunchedEffect(autoStart) {
        if (autoStart) viewModel.requestAutoUnlock()
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                // 登录/出水/查询的结果都在页面自己的提示卡里显示（不再用系统 Toast 的黑框）；
                // 事件都源自页面内的点击与随后的异步回调，此刻不会有我们的弹层盖在上面
                // postNotice 单开协程发：收集协程不卡在「等这条提示收场」上，
                // 事件成串来时新的提示才能当场顶掉旧的（见 AppNotice.kt）
                is WaterEvent.Notice -> scope.postNotice(snackbar, event.text, event.tone)
            }
        }
    }

    // 充值在支付宝里完成，回到本页（ON_RESUME）时余额要重拉，否则还显示充值前的数。
    // 与趣智页（QzxyScreen）同一套 awaiting + ON_RESUME 模式；走 refresh() 让下拉指示器跟着转。
    var awaitingRecharge by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && awaitingRecharge) {
                awaitingRecharge = false
                viewModel.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val openRecharge: () -> Unit = {
        if (QiekjRecharge.open(context)) {
            awaitingRecharge = true
        } else {
            scope.launch {
                snackbar.showNotice(
                    AppNoticeVisuals(
                        "没找到能打开支付宝的应用。充值在支付宝的胖乖生活小程序里完成，先装一个支付宝。",
                        tone = NoticeTone.Warning,
                    ),
                )
            }
        }
    }

    // 复制 Token，供另一台设备在「Token 登录」里粘贴（换机不用再收一次短信）。
    // 与趣智「复制会话串」同口径：提示按凭据写——它等于账号通行证，别让用户顺手外发。
    val copyToken: () -> Unit = {
        val token = viewModel.exportToken()
        if (token == null) {
            scope.launch {
                snackbar.showNotice(
                    AppNoticeVisuals("当前没有登录 Token", tone = NoticeTone.Warning),
                )
            }
        } else {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText("胖乖生活 Token", token))
            scope.launch {
                snackbar.showNotice(
                    AppNoticeVisuals(
                        "Token 已复制。它等于账号通行证，只粘到本应用的「Token 登录」，别发到别处",
                        tone = NoticeTone.Warning,
                    ),
                )
            }
        }
    }

    // 根因：迁到 SubpageActivity 独立窗口后没有外层 Scaffold 垫状态栏，
    // windowInsets 归零（嵌 NavHost 时期防双倍空白的老规避）会让顶栏顶进状态栏；
    // 现走 M3 默认——TopAppBar 自行消费状态栏，contentWindowInsets 管住手势条。
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("胖乖生活") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (state.loggedIn) {
                        IconButton(onClick = copyToken) {
                            Icon(HugeIcons.Copy01, contentDescription = "复制 Token")
                        }
                        IconButton(onClick = { confirmLogout = true }) {
                            Icon(HugeIcons.Logout04, contentDescription = "退出登录")
                        }
                    }
                },
            )
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        if (!state.loggedIn) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LoginSection(state = state, viewModel = viewModel)
                WaterSettingsSection(
                    prefs = prefsState.displayPrefs,
                    onSetRequireDoubleClick = prefsViewModel::setWaterRequireDoubleClick,
                    onToggleHaptics = { haptics.toggle() },
                )
                Disclaimer()
            }
        } else {
            // 下拉刷新：与趣智页 / 消费流水页同一套 PullToRefreshBox（2026-09-28 起，
            // 余额行的刷新按钮随之删除）。padding 收在刷新容器上而不是列表上：Scaffold 的
            // 内容从 (0,0) 铺满整屏、顶栏压在它上面，指示器挂在容器顶边时整条滑入轨迹
            // 都在顶栏后面，得拖过阈值一大截才露出半圈。
            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = {
                    haptics.tap()
                    viewModel.refresh()
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    BalanceRow(
                        state = state,
                        onRecharge = openRecharge,
                        onToggleShowPhone = { viewModel.toggleShowPhone() },
                    )

                    // 设备行（与趣智页同构：一行 AppCardRow，右侧「更换 ›」）
                    DeviceRow(
                        state = state,
                        onPick = { showDeviceSheet = true },
                    )

                    UnlockStatusArea(
                        state = state,
                        onToggleUsePoints = { viewModel.toggleUsePoints() },
                        onShowDetail = { detailItem = it },
                    )

                    when (val flow = state.flow) {
                        is UnlockFlowState.Idle -> WaterUnlockButton(
                            enabled = state.selectedDevice != null,
                            onUnlock = { viewModel.unlock() },
                            fillMaxWidth = true,
                            height = 48.dp,
                        )
                        // 出水满 5 秒，「开水进行中…」原地换成「结束用水」（动作位共用组件）
                        is UnlockFlowState.PreChecking -> WaterWorkingAction(
                            canEndSession = false,
                            onEndSession = { viewModel.endSession() },
                        )
                        is UnlockFlowState.Working -> WaterWorkingAction(
                            canEndSession = flow.elapsedSeconds >= WaterViewModel.END_SESSION_MIN_SECONDS,
                            onEndSession = { viewModel.endSession() },
                            stopping = flow.stopping,
                        )
                        is UnlockFlowState.Success -> OutlinedButton(
                            onClick = { viewModel.dismissFlow() },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                        ) { Text("完成") }
                        is UnlockFlowState.Failed -> Row(modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = { viewModel.unlock() },
                                modifier = Modifier.weight(1f).height(48.dp),
                            ) { Text("重试") }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = { viewModel.dismissFlow() },
                                modifier = Modifier.height(48.dp),
                            ) { Text("关闭") }
                        }
                    }

                    OrderSection(
                        orders = state.orderHistory,
                        serverOrders = state.serverOrders,
                        serverLoading = state.loadingServerOrders,
                        serverLoaded = state.serverOrdersLoaded,
                        onLoadServer = { viewModel.loadServerOrders() },
                        onClick = { detailItem = it },
                        onClickServer = { viewModel.openServerOrder(it) },
                    )

                    WaterSettingsSection(
                        prefs = prefsState.displayPrefs,
                        onSetRequireDoubleClick = prefsViewModel::setWaterRequireDoubleClick,
                        onToggleHaptics = { haptics.toggle() },
                    )
                    Disclaimer()
                }
            }
        }
    }

    if (showDeviceSheet) {
        DeviceSheet(
            devices = state.devices,
            selected = state.selectedDevice,
            onSelect = {
                viewModel.selectDevice(it)
                showDeviceSheet = false
            },
            onDismiss = { showDeviceSheet = false },
        )
    }

    when (val d = detailItem) {
        is UnlockFlowState.Failed -> ErrorDetailDialog(item = d, onDismiss = { detailItem = null })
        is edu.jxslu.schedule.domain.UnlockResult -> OrderDetailDialog(item = d, onDismiss = { detailItem = null })
        is OrderHistoryItem -> OrderDetailDialog(item = toResult(d), onDismiss = { detailItem = null })
        null -> {}
    }

    // 服务端账单明细（点了「服务端账单」某一条后由 VM 拉到）
    state.serverOrderDetail?.let { result ->
        OrderDetailDialog(item = result, onDismiss = { viewModel.dismissServerOrderDetail() })
    }

    // 退出二次确认（2026-09-27）：顶栏图标就在返回键旁边，一次误触就得重新收短信才能回来
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("退出胖乖登录？") },
            text = {
                DialogWindowFadeEffect()
                Text(
                    "将清除本机保存的胖乖 Token 与开水订单快照。" +
                        "今日页开水卡回到未登录态，重新登录需要再收一次短信验证码。",
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

/**
 * 登录卡（2026-10-07 卡片化）：验证码 / Token 两种方式收进同一张 SettingsSection，
 * 与趣智 / 快趣 / U净 的登录卡同一形态——本页其余区块都是卡片，表单裸在页面背景上
 * 会显得「没装进容器」。
 */
@Composable
private fun LoginSection(state: WaterUiState, viewModel: WaterViewModel) {
    SettingsSection(
        title = "登录",
        subtitle = "手机号短信登录；只保存登录状态，不保存密码。充值与退款走官方渠道。",
    ) {
        OutlinedTextField(
            value = state.phone,
            onValueChange = viewModel::updatePhone,
            label = { Text("手机号") },
            isError = state.phoneError != null,
            supportingText = state.phoneError?.let { { Text(it) } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.code,
                onValueChange = viewModel::updateCode,
                label = { Text("验证码") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { viewModel.sendCode() }, enabled = !state.sendingCode && state.phone.isNotBlank()) {
                Text(if (state.sendingCode) "发送中" else "发送验证码")
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { viewModel.login() },
            enabled = !state.loggingIn && state.phone.isNotBlank() && state.code.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Text("登录", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(8.dp))
        if (!state.showTokenLogin) {
            OutlinedButton(onClick = { viewModel.toggleTokenLogin() }, modifier = Modifier.fillMaxWidth()) {
                Text("Token 登录")
            }
            Text(
                "已从其他渠道拿到 Token 时可直接粘贴登录；手机号登录会使旧 Token 失效。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                modifier = Modifier.padding(top = 6.dp),
            )
        } else {
            OutlinedTextField(
                value = state.tokenLoginInput,
                onValueChange = viewModel::updateTokenLoginInput,
                label = { Text("Token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { viewModel.loginWithToken() },
                enabled = !state.tokenLoggingIn && state.tokenLoginInput.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text("Token 登录") }
            // 切回短信登录的出口（2026-10-07 补）：此前切进 Token 模式只有输入框 + 登录键，
            // 想回去只能杀页面重进；趣智的会话串模式一直有「收起」按钮，这里对齐
            TextButton(
                onClick = { viewModel.toggleTokenLogin() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("改用短信验证码登录") }
        }
    }
}

/**
 * 开水设置区（原「开水设置」子页主体，2026-09-20 并入本页，DESIGN §3.4）。
 * 放在页面尾部，登录与未登录两态共用。今日页不再展示开水卡片，生活服务统一
 * 从左侧栏进入；本区只留行为项。
 */
@Composable
private fun WaterSettingsSection(
    prefs: DisplayPrefs,
    onSetRequireDoubleClick: (Boolean) -> Unit,
    onToggleHaptics: () -> Unit,
) {
    SettingsSection(
        title = "开水卡设置",
        subtitle = "本节只管理开水卡行为。",
    ) {
        SettingChoiceRow(
            title = "点击方式",
            subtitle = "双击确认防误触，对上方「开水」大按钮生效（今日页余额面板固定单击）",
            icon = HugeIcons.GlassWater,
            options = listOf("单击", "双击"),
            selectedIndex = if (prefs.waterRequireDoubleClick) 1 else 0,
            onSelect = { index ->
                onToggleHaptics()
                onSetRequireDoubleClick(index == 1)
            },
        )
    }
}

@Composable
private fun Disclaimer() {
    FooterDisclaimer(
        body = "胖乖功能模拟客户端操作，仅供学习测试，可能违反平台服务条款；账号与封禁风险自负。" +
            "本应用与学校、胖乖官方均无关。",
    )
}

// ── 已登录内容 ──

/**
 * 余额与充值卡（2026-10-07 卡片化，与趣智页 AccountRow 同构）：余额 + 登录手机号
 * （遮蔽 + 眼睛）+ 充值按钮 + 说明文，全部收进一张 SettingsSection。
 * 原先这几样裸在页面背景上，与全 App 的卡片语言脱节。
 */
@Composable
private fun BalanceRow(state: WaterUiState, onRecharge: () -> Unit, onToggleShowPhone: () -> Unit) {
    val balance = state.balance
    SettingsSection(title = "余额与充值") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                HugeIcons.Wallet01,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "小票 ¥${balance?.ticketText ?: "-"} · 积分 ${balance?.pointsText ?: "-"}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 登录手机号默认遮蔽，点眼睛才展开：页面会被截图、会被旁人瞥见，
                // 与趣智账号行 / 「我的」页学号同一个口径（QzxyPhoneMask 是通用遮蔽工具）
                if (state.accountPhone.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "登录手机号 " +
                                if (state.showPhone) state.accountPhone else QzxyPhoneMask.mask(state.accountPhone),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Icon(
                            imageVector = if (state.showPhone) HugeIcons.ViewOff else HugeIcons.View,
                            contentDescription = if (state.showPhone) "隐藏手机号" else "显示手机号",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier
                                .padding(start = 6.dp)
                                .size(16.dp)
                                .clickable { onToggleShowPhone() },
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
            text = "点「充值」跳到支付宝的胖乖生活小程序，钱进胖乖账户；充值与退款由胖乖官方负责。" +
                "小程序按支付宝的登录态进账号，充值前先核对它与本 App 登录的手机号一致，避免充错账户。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/**
 * 设备行（2026-10-07 与趣智页同构）：AppCardRow + 图标 + 设备名 + 「更换 ›」。
 * 原先是裸 Row + 分割线；用水中 / 流程进行中不可换（趣智同一条纪律）。
 */
@Composable
private fun DeviceRow(state: WaterUiState, onPick: () -> Unit) {
    val canPick = state.flow is UnlockFlowState.Idle && state.devices.isNotEmpty()
    AppCardRow(
        modifier = Modifier.fillMaxWidth(),
        onClick = if (canPick) onPick else null,
        onClickLabel = "更换开水设备",
    ) {
        Icon(
            HugeIcons.Droplet,
            contentDescription = null,
            tint = if (state.selectedDevice != null) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = state.selectedDevice?.goodsName?.ifBlank { "未命名设备" } ?: "暂无设备",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = when {
                    state.flow !is UnlockFlowState.Idle -> "正在处理，等这一步跑完再换"
                    state.selectedDevice != null -> "已选中 · 点右侧可换"
                    else -> "还没选设备，点右侧挑一台"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(4.dp))
        if (state.loadingDevices) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        } else {
            Text(
                text = "更换 ›",
                style = MaterialTheme.typography.bodySmall,
                color = if (canPick) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                maxLines = 1,
            )
        }
    }
}

/** 开水状态区：Idle 显示积分抵扣开关，其余状态原地切换，不弹新卡。 */
@Composable
private fun UnlockStatusArea(
    state: WaterUiState,
    onToggleUsePoints: () -> Unit,
    onShowDetail: (Any) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // 状态切换带过渡（2026-10-05 用户反馈「突变了都」）：待机↔准备↔出水↔结算之间淡入淡出，
        // 高度差跟着 AnimatedContent 的尺寸动画走；同相位内每秒走字不触发动画
        WaterFlowStatusHost(flow = state.flow) { flow ->
            PageFlowStatus(
                flow = flow,
                usePoints = state.usePoints,
                onToggleUsePoints = onToggleUsePoints,
                onShowDetail = onShowDetail,
            )
        }
    }
}

/** 开水页状态区各相位的内容；过渡由 [WaterFlowStatusHost] 负责。 */
@Composable
private fun PageFlowStatus(
    flow: UnlockFlowState,
    usePoints: Boolean,
    onToggleUsePoints: () -> Unit,
    onShowDetail: (Any) -> Unit,
) {
    when (flow) {
        is UnlockFlowState.Idle -> Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("使用积分抵扣", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "关闭后开水不消耗积分",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }
            Switch(checked = usePoints, onCheckedChange = { onToggleUsePoints() })
        }
        is UnlockFlowState.PreChecking -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                flow.step,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
        is UnlockFlowState.Working -> Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "正在出水 ${formatClock(flow.elapsedSeconds)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "${(WaterViewModel.AUTO_SETTLE_SECONDS - flow.elapsedSeconds).coerceAtLeast(0)} 秒后自动关闭",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = {
                    (flow.elapsedSeconds.toFloat() / WaterViewModel.AUTO_SETTLE_SECONDS).coerceIn(0f, 1f)
                },
                modifier = Modifier.fillMaxWidth().height(4.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                flow.step,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
        }
        is UnlockFlowState.Success -> Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "花费 ¥${calculateActualCost(flow.result)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "订单详情 ›",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onShowDetail(flow.result) },
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                // 支付构成副行（DESIGN §4.10 账单口径）：小票是账户余额支付，
                // 现金实付 0.00 只代表没走在线支付，不能当作「本次花费 0」
                costBreakdownText(flow.result),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
        }
        is UnlockFlowState.Failed -> Column {
            Text(flow.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "失败步骤：${flow.step}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "查看详情 ›",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onShowDetail(flow) },
                )
            }
        }
    }
}

private fun formatClock(totalSeconds: Int): String {
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return "%02d:%02d".format(m, s)
}

// ── 弹层与列表 ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceSheet(
    devices: List<DeviceItem>,
    selected: DeviceItem?,
    onSelect: (DeviceItem) -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = rememberAppHaptics()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
        ) {
            Text("选择开水设备", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            if (devices.isEmpty()) {
                Text(
                    "暂无设备，请确认已在胖乖生活用过饮水机后下拉刷新",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }
            devices.forEach { device ->
                val isSelected = device.effectiveGoodsId == selected?.effectiveGoodsId
                // 行规格与趣智设备弹层（DeviceRow）一致：选中态主色淡底 + 触感反馈
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            else Color.Transparent,
                            shape = RoundedCornerShape(10.dp),
                        )
                        .clickable {
                            haptics.tap()
                            onSelect(device)
                        }
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        HugeIcons.Droplet,
                        contentDescription = null,
                        tint = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        device.goodsName.ifBlank { "未命名设备" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

/**
 * 历史订单：卡片入口 + 底部弹层明细（2026-10-07 收口）。
 *
 * 订单列表原先整块平铺在页面上（十几笔时把「开水卡设置」一路顶出屏外），现在
 * 收成一行入口，明细装进与设备选择同规格的底部弹层。
 *
 * 2026-10-14：弹层里分两段——**本地快照**（本机结算留痕，最多 20 笔，含支付构成）与
 * **服务端账单**（`order/list`，账号下全部订单，点单笔拉 `order/detail` 看构成）。
 * 两段的分工与生活页「流水两段」同一约定：来源不同就分开列，别混。
 */
@Composable
private fun OrderSection(
    orders: List<OrderHistoryItem>,
    serverOrders: List<ServerOrderItem>,
    serverLoading: Boolean,
    serverLoaded: Boolean,
    onLoadServer: () -> Unit,
    onClick: (OrderHistoryItem) -> Unit,
    onClickServer: (ServerOrderItem) -> Unit,
) {
    var showOrders by remember { mutableStateOf(false) }
    SettingsSection(title = "历史订单", subtitle = "本地快照最多 20 笔；服务端账单看账号全部") {
        SettingItem(
            title = if (orders.isEmpty()) "还没有本地订单" else "本地 ${orders.size} 笔",
            subtitle = "点看明细",
            icon = HugeIcons.History,
            onClick = { showOrders = true },
        )
    }
    if (showOrders) {
        OrderListSheet(
            orders = orders,
            serverOrders = serverOrders,
            serverLoading = serverLoading,
            serverLoaded = serverLoaded,
            onLoadServer = onLoadServer,
            onClick = onClick,
            onClickServer = onClickServer,
            onDismiss = { showOrders = false },
        )
    }
}

/** 订单明细弹层：本地快照 + 服务端账单两段，行点击各开订单详情弹窗。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OrderListSheet(
    orders: List<OrderHistoryItem>,
    serverOrders: List<ServerOrderItem>,
    serverLoading: Boolean,
    serverLoaded: Boolean,
    onLoadServer: () -> Unit,
    onClick: (OrderHistoryItem) -> Unit,
    onClickServer: (ServerOrderItem) -> Unit,
    onDismiss: () -> Unit,
) {
    // 进弹层按需拉一次服务端账单（VM 里已拉过就跳过，不会重复请求）
    LaunchedEffect(Unit) { onLoadServer() }
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
                "历史订单",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            // ── 段一：本地快照 ──
            Spacer(Modifier.height(10.dp))
            Text(
                "本地快照 · 点单笔看支付构成",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
            Spacer(Modifier.height(4.dp))
            if (orders.isEmpty()) {
                Text(
                    "还没有本地订单",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                val shown = orders.take(20)
                shown.forEachIndexed { index, order ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onClick(order) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                order.goodsName,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                            )
                            Text(
                                DATE_FORMAT.format(Date(order.completedAt)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            )
                        }
                        Text(
                            "¥${calculateActualCost(toResult(order))}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    if (index < shown.size - 1) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                }
            }

            // ── 段二：服务端账单（order/list，2026-10-14） ──
            Spacer(Modifier.height(18.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Spacer(Modifier.height(12.dp))
            Text(
                "服务端账单 · 账号全部订单，点单笔看支付构成",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
            Spacer(Modifier.height(4.dp))
            when {
                serverLoading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "读取中…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                !serverLoaded -> Text(
                    "还没拉取服务端账单",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                serverOrders.isEmpty() -> Text(
                    "服务端没有账单",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                else -> serverOrders.forEachIndexed { index, order ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onClickServer(order) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                order.machineName?.takeIf { it.isNotBlank() }
                                    ?: order.shopName?.takeIf { it.isNotBlank() }
                                    ?: "订单",
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                            )
                            Text(
                                order.createTime.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                maxLines = 1,
                            )
                        }
                        Text(
                            "¥${order.markPrice ?: "-"}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    if (index < serverOrders.size - 1) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                }
            }
        }
    }
}

private fun toResult(item: OrderHistoryItem) = UnlockResult(
    orderNo = item.orderNo,
    orderId = item.orderId,
    originPrice = item.originPrice,
    ticketCost = item.ticketCost,
    integralCost = item.integralCost,
    otherPromotions = item.otherPromotions,
    completedAt = item.completedAt,
    realPrice = item.realPrice,
    payTypeName = item.payTypeName,
    tokenCoinDiscount = item.tokenCoinDiscount,
)

@Composable
private fun ErrorDetailDialog(item: UnlockFlowState.Failed, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("失败详情") },
        text = {
            DialogWindowFadeEffect()
            Column {
                Text(item.message, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "失败步骤：${item.step}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
                if (item.rawError.isNotBlank() && item.rawError != item.message) {
                    Text(
                        "错误详情：${item.rawError}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    )
                }
                item.suggestions.forEach { s ->
                    Spacer(Modifier.height(4.dp))
                    Text("• $s", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
    )
}

@Composable
private fun OrderDetailDialog(item: UnlockResult, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("订单详情") },
        text = {
            DialogWindowFadeEffect()
            Column {
                DetailRow("订单号", item.orderNo)
                DetailRow("原价", "¥${item.originPrice}")
                // 账单构成（DESIGN §4.10 账单口径）：小票是账户余额，扣了就是花了；
                // 「现金支付 ¥0.00」只代表没走在线支付，不叫「实付 0」
                ticketPaidAmount(item)?.takeIf { it > BigDecimal.ZERO }
                    ?.let { DetailRow("小票支付", "¥${money(it)}") }
                if (item.integralCost != "-") DetailRow("积分抵扣", item.integralCost)
                item.otherPromotions.forEach { p ->
                    DetailRow("其他优惠", p.discountAmount ?: "-")
                }
                cashPaidAmount(item)?.let { cash ->
                    val channel = item.payTypeName?.let { "（$it）" } ?: ""
                    DetailRow("现金支付", "¥${money(cash)}$channel")
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                DetailRow("本次花费", "¥${calculateActualCost(item)}")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/** 金额统一显示两位小数（服务端金额字符串可能缺位）。 */
private fun money(value: BigDecimal): String =
    value.setScale(2, RoundingMode.HALF_UP).toPlainString()

/**
 * 成功卡的支付构成副行（DESIGN §4.10 账单口径）：
 * 「小票支付 ¥0.16 · 积分抵扣 0.10 · 支付宝-代扣 ¥0.06」，全空兜底「开水成功」。
 */
private fun costBreakdownText(result: UnlockResult): String {
    val parts = buildList {
        ticketPaidAmount(result)?.takeIf { it > BigDecimal.ZERO }
            ?.let { add("小票支付 ¥${money(it)}") }
        result.integralCost.takeIf { it != "-" }?.let { add("积分抵扣 $it") }
        cashPaidAmount(result)?.takeIf { it > BigDecimal.ZERO }?.let { cash ->
            add("${result.payTypeName ?: "现金"} ¥${money(cash)}")
        }
    }
    return parts.joinToString(" · ").ifBlank { "开水成功" }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            modifier = Modifier.width(64.dp),
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
