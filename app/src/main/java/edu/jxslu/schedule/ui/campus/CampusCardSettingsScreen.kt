package edu.jxslu.schedule.ui.campus

import android.content.Context
import android.app.Activity
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.prefs.PendingRecharge
import edu.jxslu.schedule.data.prefs.DisplayPrefsStore
import edu.jxslu.schedule.data.repo.ScheduleRepository
import edu.jxslu.schedule.domain.BalanceAlert
import edu.jxslu.schedule.domain.BalanceAlertSource
import edu.jxslu.schedule.domain.YktArrival
import edu.jxslu.schedule.domain.YktPayment
import edu.jxslu.schedule.data.ykt.YktCard
import edu.jxslu.schedule.data.ykt.YktClient
import edu.jxslu.schedule.data.ykt.YktCredentialStore
import edu.jxslu.schedule.data.ykt.YktException
import edu.jxslu.schedule.data.ykt.YktRechargeOrder
import edu.jxslu.schedule.data.ykt.YktRepository
import edu.jxslu.schedule.ui.common.AppNoticeVisuals
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.NoticeFeedback
import edu.jxslu.schedule.ui.common.NoticeTone
import edu.jxslu.schedule.ui.common.WheelValueDialog
import edu.jxslu.schedule.ui.reminder.BalanceAlertReminder
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.text.font.FontWeight

/**
 * 我的 → 校园卡（DESIGN §3.10）。管开关、凭证与两个余额提醒，不看付款码。
 *
 * 凭证交互与「调课自动检测」（§4.17）同口径（用户拍板的文案语义）：
 * **默认关闭**；开启 = 输入学号密码先真实登录验证一次，成功才落库并置开关；
 * 关闭 = 二次确认后清除凭证。密码框留空 = 沿用已保存的密码（覆盖场景才需要重输）。
 *
 * 2026-09-24 追加：**已保存的学号明文回填到输入框**（用户拍板），以及
 * **寝室电费提醒 / 一卡通余额提醒**两个设置项（DESIGN §3.13）——它们共用本页这份凭证，
 * 因此关掉凭证时两个提醒开关一并回落，不留「亮着但永远不生效」的死开关。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CampusCardSettingsScreen(
    onBack: () -> Unit,
    /** 跳消费流水页（DESIGN §4.19 B4/B5） */
    onOpenStatement: () -> Unit = {},
    /** 跳付款码页（充值成功弹窗「查看付款码」直达） */
    onOpenPayCode: () -> Unit = {},
    viewModel: CampusCardViewModel = viewModel(
        factory = CampusCardViewModel.Factory(LocalContext.current.applicationContext),
    ),
) {
    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
    val balance by viewModel.balance.collectAsStateWithLifecycle()
    val arrivalState by viewModel.arrivalState.collectAsStateWithLifecycle()
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // Activity 窗口 context：通知权限申请、外部跳转（微信/浏览器）都用它
    val context = LocalContext.current

    // 超时安抚提示（只弹一次：Timeout 状态被确认后转 Idle）
    androidx.compose.runtime.LaunchedEffect(arrivalState) {
        val st = arrivalState
        if (st is CampusCardViewModel.ArrivalState.Timeout) {
            snackbar.showSnackbar(
                AppNoticeVisuals(
                    "暂未检测到到账——充值常有延迟，到账后「消费流水」会自动显示",
                    tone = NoticeTone.Info,
                ),
            )
            viewModel.dismissArrival()
        }
    }

    // 从微信/浏览器返回的瞬间立即补检一轮（不等 5 秒周期）；同时兜进程被杀重启恢复
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.onHostResume()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 已保存学号**明文回填**（2026-09-24 用户拍板）：进页时读一次加密存储填进输入框，
    // 之后的编辑由 rememberSaveable 管（转屏/重建不丢）。改了就覆盖，留空才报错。
    val savedUsername = remember { viewModel.savedUsername }
    var username by rememberSaveable { mutableStateOf(savedUsername.orEmpty()) }
    var password by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var confirmDisable by remember { mutableStateOf(false) }
    var showRechargeSheet by remember { mutableStateOf(false) }
    var showPowerPicker by remember { mutableStateOf(false) }
    var showYktPicker by remember { mutableStateOf(false) }

    val powerAlertEnabled by viewModel.powerAlertEnabled.collectAsStateWithLifecycle()
    val powerAlertYuan by viewModel.powerAlertYuan.collectAsStateWithLifecycle()
    val yktAlertEnabled by viewModel.yktAlertEnabled.collectAsStateWithLifecycle()
    val yktAlertYuan by viewModel.yktAlertYuan.collectAsStateWithLifecycle()

    fun feedback(notice: NoticeFeedback) {
        busy = false
        if (notice.tone != NoticeTone.Error) password = ""
        scope.launch {
            snackbar.showSnackbar(AppNoticeVisuals(notice.text, tone = notice.tone))
        }
    }

    fun showNotice(text: String, tone: NoticeTone = NoticeTone.Info) {
        scope.launch { snackbar.showSnackbar(AppNoticeVisuals(text, tone = tone)) }
    }

    // 两个提醒开关共用：开启那一刻请求 POST_NOTIFICATIONS（API 33+），拒绝不阻塞开关本身
    // （口径同「我的 → 上课提醒」页：功能在系统设置里授权后自动生效）
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) showNotice("未授予通知权限，提醒不会显示；可在系统设置里重新开启", NoticeTone.Warning)
    }
    val requestNotifPermission = {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // 开关入口共用：提醒要拿这份凭证去读余额，没凭证就别让开关亮起来
    val toggleAlert: (Boolean, (Boolean) -> Unit) -> Unit = { want, apply ->
        if (want && !viewModel.hasSavedAccount) {
            showNotice("请先在上方开启并保存学号密码，提醒需要用它读取余额", NoticeTone.Warning)
        } else {
            apply(want)
            if (want) requestNotifPermission()
        }
    }

    Scaffold(
        // 与其余二级页同口径：M3 默认 inset（本页跑在 SubpageActivity 独立窗口里，无外层垫 inset）
        snackbarHost = { AppSnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("水宝宝一卡通") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsCard(
                title = "水宝宝一卡通",
                content = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("在今日页显示付款码入口", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (enabled) "已开启 · 今日页底部显示入口" else "默认关闭",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            )
                        }
                        if (busy) {
                            // size 而非 height：height-only 约束下 40×40 的圆环被画成 40×24 椭圆
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(12.dp))
                        }
                        Switch(
                            checked = enabled,
                            onCheckedChange = { wantOn ->
                                if (busy) return@Switch
                                if (wantOn) {
                                    busy = true
                                    viewModel.saveAndEnable(username, password, ::feedback)
                                } else {
                                    confirmDisable = true
                                }
                            },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "付款码等同现金，会随时变更，请勿截图或分享给他人，否则可能被盗刷。" +
                            "学号与密码仅保存在本机（Android Keystore 加密存储），不进入云备份、" +
                            "不发送到任何第三方服务器；可随时关闭本功能，关闭即清除已保存的凭证。" +
                            "若因启用本功能造成损失，开发者概不负责。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                },
            )

            // 余额状态区（DESIGN §4.19 B5）：开启即显示（骨架占位避免进页跳动），点击跳流水页
            val balanceSnapshot = balance
            if (enabled) {
                SettingsCard(
                    title = "余额",
                    content = {
                        if (balanceSnapshot != null) {
                            Text(
                                "正式卡 ¥%.2f".format(balanceSnapshot.cardFen / 100.0) +
                                    if (balanceSnapshot.accountFen > 0) " · 电子账户 ¥%.2f".format(balanceSnapshot.accountFen / 100.0) else "",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        } else {
                            // 骨架：进页拉余额的 1–3 秒内占位，数据到达平滑填充
                            Text(
                                "卡余额 获取中…",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "充值后回到本页，余额与流水会自动刷新",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                        Spacer(Modifier.height(8.dp))
                        // 到账等待状态行（Watching 时替换按钮区上方，格式与余额一致不跳动）
                        val watching = arrivalState as? CampusCardViewModel.ArrivalState.Watching
                        if (watching != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "正在等待充值到账（¥%.2f）· 每 5 秒自动检测".format(watching.orderFen / 100.0),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            androidx.compose.material3.OutlinedButton(
                                onClick = onOpenStatement,
                                modifier = Modifier.weight(1f),
                            ) { Text("消费流水") }
                            androidx.compose.material3.OutlinedButton(
                                onClick = { showRechargeSheet = true },
                                modifier = Modifier.weight(1f),
                                enabled = watching == null,
                            ) { Text("充值") }
                        }
                    },
                )
            }

            SettingsCard(
                title = "校园卡账号",
                content = {
                    Text(
                        "账号为学号，默认密码通常为身份证后六位（仅支持数字密码）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it.filter { c -> c.isDigit() } },
                        label = { Text(if (viewModel.hasSavedAccount) "学号（已保存，可覆盖）" else "学号") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it.filter { c -> c.isDigit() } },
                        label = { Text(if (viewModel.hasSavedAccount) "密码（留空沿用已保存）" else "密码") },
                        singleLine = true,
                        visualTransformation =
                            if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    if (showPassword) HugeIcons.ViewOff else HugeIcons.View,
                                    contentDescription = if (showPassword) "隐藏密码" else "显示密码",
                                )
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
            )

            // 余额提醒（DESIGN §3.13）：两个来源各一张卡，都需要上面那份凭证
            AlertCard(
                title = "寝室电费提醒",
                switchTitle = "电费低于阈值时提醒",
                subtitle = "每天检查一次，低于设定金额时发一条通知",
                checked = powerAlertEnabled,
                thresholdLabel = BalanceAlert.powerLabel(powerAlertYuan),
                onToggle = { want -> toggleAlert(want) { viewModel.setPowerAlertEnabled(it) } },
                onPickThreshold = { showPowerPicker = true },
            )

            AlertCard(
                title = "一卡通余额提醒",
                switchTitle = "余额低于阈值时提醒",
                subtitle = "只算正式卡余额（付款码扣款的那个钱包）",
                checked = yktAlertEnabled,
                thresholdLabel = BalanceAlert.yktLabel(yktAlertYuan),
                onToggle = { want -> toggleAlert(want) { viewModel.setYktAlertEnabled(it) } },
                onPickThreshold = { showYktPicker = true },
            )

            SettingsCard(title = "关于余额提醒") {
                Text(
                    "· 检查在后台进行，每天一次（约 09:00），可能被系统省电策略推迟；打开 App 时若当天还没查过会补查一次；\n" +
                        "· 同一天最多提醒一条：余额一直偏低也不会反复打扰，充值回到阈值以上即自然停止；\n" +
                        "· 提醒依赖上面保存的学号与查询密码，关闭凭证时两个提醒会一并关掉；\n" +
                        "· 取数失败（网络不通、平台改版）当次不提醒，也不会重试——不会因为反复登录触发风控。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                )
            }
        }
    }

    if (showPowerPicker) {
        WheelValueDialog(
            title = "电费低于多少时提醒",
            values = BalanceAlert.POWER_CHOICES.map { "¥$it" },
            initialIndex = BalanceAlert.powerChoiceIndex(powerAlertYuan),
            onConfirm = { index ->
                showPowerPicker = false
                viewModel.setPowerAlertYuan(BalanceAlert.POWER_CHOICES[index])
            },
            onDismiss = { showPowerPicker = false },
        )
    }

    if (showYktPicker) {
        WheelValueDialog(
            title = "余额低于多少时提醒",
            values = BalanceAlert.YKT_CHOICES.map { "¥$it" },
            initialIndex = BalanceAlert.yktChoiceIndex(yktAlertYuan),
            onConfirm = { index ->
                showYktPicker = false
                viewModel.setYktAlertYuan(BalanceAlert.YKT_CHOICES[index])
            },
            onDismiss = { showYktPicker = false },
        )
    }

    if (confirmDisable) {
        AlertDialog(
            onDismissRequest = { confirmDisable = false },
            title = { Text("关闭校园卡付款码？") },
            text = {
                Text(
                    "将清除已保存的学号密码，付款码入口同时隐藏，寝室电费与余额提醒也会一并关闭" +
                        "（它们都要用这份凭证）；重新开启时需要重新输入并验证。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDisable = false
                        busy = true
                        viewModel.disable {
                            busy = false
                            username = ""
                            scope.launch {
                                snackbar.showSnackbar(
                                    AppNoticeVisuals("已关闭并清除凭证", tone = NoticeTone.Info),
                                )
                            }
                        }
                    },
                ) { Text("关闭并清除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDisable = false }) { Text("取消") }
            },
        )
    }

    // 充值流程（DESIGN §4.19「充值」）：金额弹层 → 二次确认 → 下单 → 直拉微信 → 等待到账
    if (showRechargeSheet) {
        RechargeSheet(
            balanceFen = balance?.totalFen,
            // 电子账户余额来自同一份 queryCard（ACCOUNT 行）；null = 不显示账户切换
            accountFen = balance?.accountFen,
            onDismiss = { showRechargeSheet = false },
            onLaunch = { yuan, toElectric ->
                showRechargeSheet = false
                busy = true
                scope.launch {
                    val target = if (toElectric) viewModel.electricAccountType() else null
                    viewModel.recharge(
                        yuan,
                        targetAccount = target,
                        // Activity context 直接启动（不设 NEW_TASK）：微信/浏览器在调用方 task
                        // 内打开，返回无缝、无 task 重排 → 顶栏不跳动
                        launchExternal = { intent ->
                            runCatching {
                                (context as? Activity)?.startActivity(intent)
                                    ?: context.startActivity(
                                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                true
                            }.getOrDefault(false)
                        },
                    ) { notice ->
                        busy = false
                        scope.launch {
                            snackbar.showSnackbar(AppNoticeVisuals(notice.text, tone = notice.tone))
                        }
                    }
                }
            },
        )
    }

    // 「正在确认到账」弹窗（微信返回且未立即到账时；关闭不影响轮询）
    val pendingConfirm by viewModel.pendingConfirmVisible.collectAsStateWithLifecycle()
    val watchingForDialog = arrivalState as? CampusCardViewModel.ArrivalState.Watching
    if (pendingConfirm && watchingForDialog != null) {
        CampusPendingConfirmDialog(
            orderFen = watchingForDialog.orderFen,
            onDismiss = { viewModel.dismissPendingConfirm() },
            onNotPaid = { viewModel.notPaid() },
        )
    }

    // 充值成功弹窗（共享组件， CampusRechargeUi.kt）；确认按钮直达付款码页
    val arrived = arrivalState as? CampusCardViewModel.ArrivalState.Arrived
    if (arrived != null) {
        CampusArrivalDialog(
            arrived = arrived,
            onDismiss = { viewModel.dismissArrival() },
            onOpenPayCode = onOpenPayCode,
        )
    }

    // 「支付成功」：付款码页检测到扣款会自己退出，弹窗落在退回来的这一页
    // （今日页也有同一份，DESIGN §3.10）。取值即消费，避免被压在后头的页面再弹一次
    val payResult by PayCodeResultBus.result.collectAsStateWithLifecycle()
    var paidPayment by remember { mutableStateOf<YktPayment?>(null) }
    androidx.compose.runtime.LaunchedEffect(payResult) {
        payResult?.let {
            paidPayment = it
            PayCodeResultBus.consume()
        }
    }
    paidPayment?.let { paid ->
        CampusPaymentDialog(payment = paid, onDismiss = { paidPayment = null })
    }
}


@Composable
private fun SettingsCard(
    title: String,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}

/**
 * 余额提醒卡片（DESIGN §3.13）：开关行 + 阈值行。
 *
 * 阈值行在开关关闭时**置灰且点不动**，但值照旧显示（用户能看见上次设的是多少，
 * 重新打开时不用重设）。卡片容器沿用本页私有的 [SettingsCard]，与上面三张卡同一观感。
 */
@Composable
private fun AlertCard(
    title: String,
    switchTitle: String,
    subtitle: String,
    checked: Boolean,
    thresholdLabel: String,
    onToggle: (Boolean) -> Unit,
    onPickThreshold: () -> Unit,
) {
    SettingsCard(title = title) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(switchTitle, style = MaterialTheme.typography.bodyLarge)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = checked, onCheckedChange = onToggle)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = checked) { onPickThreshold() }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "提醒阈值",
                style = MaterialTheme.typography.bodyLarge,
                color = if (checked) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                },
            )
            Spacer(Modifier.weight(1f))
            Text(
                thresholdLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = if (checked) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                },
            )
        }
    }
}

class CampusCardViewModel(private val appContext: Context) : ViewModel() {

    private val prefs = Graph.displayPrefs(appContext)
    private val credentialStore = Graph.yktCredentialStore(appContext)
    private val repo = Graph.yktRepository(appContext)
    private val db = edu.jxslu.schedule.data.local.JuwDatabase.get(appContext)
    private val syncer = edu.jxslu.schedule.data.ykt.YktTurnoverSyncer(repo, db)

    val hasSavedAccount: Boolean get() = credentialStore.read() != null

    /**
     * 已保存的学号（明文回填到「校园卡账号」输入框，2026-09-24 用户拍板）。
     * 普通 getter：只在进页时被读一次（UI 用 `remember` 承接），不做 State 免得每次重组都读加密存储。
     */
    val savedUsername: String? get() = credentialStore.read()?.username

    val enabled: StateFlow<Boolean> = prefs.campusCardEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // ---- 余额提醒（DESIGN §3.13）：默认关；两个来源各自独立，但共用同一份凭证 ----

    val powerAlertEnabled: StateFlow<Boolean> = prefs.powerAlertEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val powerAlertYuan: StateFlow<Int> = prefs.powerAlertYuan
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BalanceAlert.DEFAULT_POWER_YUAN)

    val yktAlertEnabled: StateFlow<Boolean> = prefs.yktAlertEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val yktAlertYuan: StateFlow<Int> = prefs.yktAlertYuan
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BalanceAlert.DEFAULT_YKT_YUAN)

    fun setPowerAlertEnabled(value: Boolean) {
        viewModelScope.launch {
            prefs.setPowerAlertEnabled(value)
            BalanceAlertReminder.onSettingsChanged(appContext, BalanceAlertSource.Power)
        }
    }

    fun setPowerAlertYuan(value: Int) {
        viewModelScope.launch {
            prefs.setPowerAlertYuan(value)
            BalanceAlertReminder.onSettingsChanged(appContext, BalanceAlertSource.Power)
        }
    }

    fun setYktAlertEnabled(value: Boolean) {
        viewModelScope.launch {
            prefs.setYktAlertEnabled(value)
            BalanceAlertReminder.onSettingsChanged(appContext, BalanceAlertSource.Ykt)
        }
    }

    fun setYktAlertYuan(value: Int) {
        viewModelScope.launch {
            prefs.setYktAlertYuan(value)
            BalanceAlertReminder.onSettingsChanged(appContext, BalanceAlertSource.Ykt)
        }
    }

    /** 余额快照（开启后进页静默拉一次；失败静默——设置页只做引导不做主流程）。 */
    private val _balance = MutableStateFlow<PayCodeViewModel.BalanceSnapshot?>(null)
    val balance: StateFlow<PayCodeViewModel.BalanceSnapshot?> = _balance

    private val _balanceLoaded = MutableStateFlow(false)

    /** 余额拉取进行中（今日页下拉刷新聚合指示器用）。 */
    private val _balanceRefreshing = MutableStateFlow(false)
    val balanceRefreshing: StateFlow<Boolean> = _balanceRefreshing

    /**
     * 余额状态是否**已确定**（2026-09-22 加）：尝试过加载（成功或失败）、
     * 或压根没得加载（无凭证 / 开关关）都算。
     *
     * 今日页卡片副行据此区分两种 null：还没确定 → 「余额读取中…」；
     * 确定但没有 → 「点击出示付款码」。少了这个标志，首帧只能先显示后者、
     * 数据到了再换成数字，看着就是一次突变（用户反馈）。
     */
    val balanceLoaded: StateFlow<Boolean> = _balanceLoaded

    /**
     * 刷新余额（生活页进页、点余额卡、顶栏刷新都走它）。无凭证/开关关时静默直返；
     * 失败静默（与 init 的口径一致，卡片副行维持旧值或「暂不可用」）。
     *
     * [force] = false 只给**进页**那条路用：吃 `YktRepository` 的 60 秒余额缓存
     * （DESIGN §4.24「请求节流」）。用户点卡片 / 顶栏刷新传 true，照旧拿实时值。
     */
    fun refreshBalance(force: Boolean = true) {
        viewModelScope.launch { fetchBalance(notify = false, force = force) }
    }

    /**
     * 电子账户充值目标（`accinfo` 首项 type，`<account>-000` 形态，DESIGN §3.10）。
     * 充值弹层选「电子账户」时由调用方取；取不到（平台不支持）返回 null = 走正式卡口径。
     * suspend：由充值弹层所在协程调用，不自己开作用域。
     */
    suspend fun electricAccountType(): String? {
        val credentials = credentialStore.read() ?: return null
        return runCatching { repo.electricAccountType(credentials.username, credentials.password) }.getOrNull()
    }

    /**
     * 拉取余额快照。[notify] 为真时（init 首拉）同时恢复未确认充值的轮询；
     * 手动刷新不需要重复挂轮询——init 已挂、`watchRechargeArrival` 自行收口。
     */
    private suspend fun fetchBalance(notify: Boolean, force: Boolean = true) {
        _balanceRefreshing.value = true
        try {
            if (credentialStore.read() == null) return
            val enabledNow = prefs.campusCardEnabled.first()
            if (!enabledNow) return
            val saved = credentialStore.read() ?: return
            val cards = repo.cards(saved.username, saved.password, force = force)
            if (cards.isNotEmpty()) {
                _balance.value = buildSnapshot(cards, force = force)
                // 账号条姓名（DESIGN §3.3）：queryCard 原生带持卡人姓名，
                // 首张非空即落库；班级仍归教务学籍卡管（一卡通没有这个字段）。
                prefs.setProfile(cards.firstOrNull { it.ownerName.isNotBlank() }?.ownerName, null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 静默：余额取不到不影响设置页其余功能
        } finally {
            _balanceLoaded.value = true
            _balanceRefreshing.value = false
        }
    }

    /**
     * 由 queryCard 构造余额快照（DESIGN §3.10 账户口径）：正式卡 = card 表字段；
     * 电子账户 = accinfo[] 首项 balance（独立钱包，2026-09-23 实测 codebarPayinfo
     * 的 ACCOUNT 行是正式卡镜像，不能用作电子账户余额）。accinfo 取不到当 0。
     */
    private suspend fun buildSnapshot(
        cards: List<YktCard>,
        force: Boolean = true,
    ): PayCodeViewModel.BalanceSnapshot {
        val credentials = credentialStore.read()
        val accountFen = if (credentials != null) {
            runCatching {
                repo.rechargeAccountDetail(credentials.username, credentials.password, force = force)
            }
                .getOrNull()?.second ?: 0L
        } else {
            0L
        }
        return PayCodeViewModel.BalanceSnapshot(
            cards = cards,
            totalFen = cards.sumOf { it.cardBalanceFen },
            cardFen = cards.sumOf { it.cardBalanceFen },
            accountFen = accountFen,
            elecFen = cards.sumOf { it.elecBalanceFen },
        )
    }

    init {
        // 今日页也挂本 VM（卡片余额+弹窗充值）；开关关时绝不发起任何一卡通网络动作
        if (credentialStore.read() != null) {
            viewModelScope.launch {
                fetchBalance(notify = true)
                // 恢复未确认充值（进程被杀场景，DESIGN §4.19「充值」）：窗口内重启轮询，超窗清除。
                // 基线一并从记录里恢复，重启后余额口径照常可用（只靠流水口径会漏判到账）
                val pending = runCatching { prefs.pendingRecharge.first() }.getOrNull()
                if (pending != null) {
                    val saved = credentialStore.read()
                    if (saved == null || System.currentTimeMillis() - pending.startedAt >= ARRIVAL_WATCH_MS) {
                        prefs.clearPendingRecharge()
                    } else {
                        restoreArrivalBaseline(pending)
                        _arrivalState.value = ArrivalState.Watching(
                            orderFen = pending.orderFen,
                            startedAt = pending.startedAt,
                        )
                        startArrivalWatch(saved.username, saved.password, pending.orderFen, pending.startedAt)
                    }
                }
            }
        } else {
            // 没有凭证 = 没有余额可等，直接算「已确定」：卡片副行落「点击出示付款码」
            _balanceLoaded.value = true
        }
    }

    /** 开启流程：验证（空白密码沿用已存）→ 保存凭证 → 置开关。失败保持关并给原因。 */
    fun saveAndEnable(username: String, password: String, onResult: (NoticeFeedback) -> Unit) {
        viewModelScope.launch {
            val user = username.trim()
            val saved = credentialStore.read()
            val pwd = password.ifBlank { saved?.password.orEmpty() }
            if (user.isEmpty() || pwd.isEmpty()) {
                onResult(NoticeFeedback("请填入学号和密码", NoticeTone.Warning))
                return@launch
            }
            // 整链总超时兜底：键盘 + 登录 + 账户三跳，正常 1–3 秒
            val result = withTimeoutOrNull(LOGIN_TOTAL_TIMEOUT_MS) { verifyAndEnable(user, pwd) }
            if (result == null) {
                onResult(
                    NoticeFeedback(
                        "登录超时（${LOGIN_TOTAL_TIMEOUT_MS / 1000} 秒无响应），请检查网络后重试",
                        NoticeTone.Error,
                    ),
                )
                return@launch
            }
            onResult(result)
        }
    }

    private suspend fun verifyAndEnable(user: String, pwd: String): NoticeFeedback {
        try {
            // 真实登录验证一次（顺带校验字形表/协议），成功才落库
            repo.login(user, pwd)
        } catch (e: CancellationException) {
            throw e
        } catch (e: YktException) {
            return NoticeFeedback(e.message ?: "登录失败，请检查账号密码", NoticeTone.Error)
        } catch (e: Exception) {
            return NoticeFeedback(
                "登录异常：${e.javaClass.simpleName} ${e.message.orEmpty()}".trim(),
                NoticeTone.Error,
            )
        }
        // 开启即验证 CARD 账户（DESIGN §4.19 小优化）：绑多账号/无实体卡在这里暴露，
        // 而不是等第一次取码才发现。查询失败（网络）不阻塞开启——凭证已验证有效。
        val cards = try {
            repo.cards(user, pwd)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (cards != null && cards.isEmpty()) {
            return NoticeFeedback("该账号下没有可用的校园卡账户，无法出示付款码", NoticeTone.Error)
        }
        cards?.let {
            _balance.value = buildSnapshot(it)
        }
        credentialStore.save(user, pwd)
        prefs.setCampusCardEnabled(true)
        return NoticeFeedback("已开启，付款码入口已显示在今日页", NoticeTone.Success)
    }

    /**
     * 关闭：清凭证，并**一并关掉两个余额提醒**（DESIGN §3.13）——提醒都要用这份凭证去读
     * 余额，凭证没了还把开关留在「开」只会得到一个亮着但永远不生效的死开关。
     */
    fun disable(onDone: () -> Unit) {
        viewModelScope.launch {
            prefs.setCampusCardEnabled(false)
            prefs.setPowerAlertEnabled(false)
            prefs.setYktAlertEnabled(false)
            credentialStore.clear()
            // 两个提醒都关了 → 内部会撤销每日周期任务，不白唤醒设备
            BalanceAlertReminder.ensurePeriodicWork(appContext)
            onDone()
        }
    }

    /**
     * 充值：下单 → 拉起微信/收银台 → 回流轮询到账（DESIGN §4.19「充值」）。
     *
     * [launchExternal] 由 UI 层注入（当前 Activity 直接 startActivity，**不带
     * FLAG_ACTIVITY_NEW_TASK**）——外部浏览器/微信支付会在调用方 task 内打开并在支付后
     * 无缝返回，避免 appContext+NEW_TASK 的 task 重排导致顶栏/界面跳动（2026-09-21 实测）。
     * 返回 false = 无法打开（未装微信等），调用方给文案。
     */
    fun recharge(
        yuan: String,
        /** 电子账户 type（`<account>-000`）；null = 充正式卡。见 [electricAccountType]。 */
        targetAccount: String? = null,
        launchExternal: (android.content.Intent) -> Boolean,
        onResult: (NoticeFeedback) -> Unit,
    ) {
        viewModelScope.launch {
            val saved = credentialStore.read()
            if (saved == null) {
                onResult(NoticeFeedback("凭证已失效，请重新填写学号密码", NoticeTone.Warning))
                return@launch
            }
            val placed = try {
                withTimeoutOrNull(RECHARGE_TIMEOUT_MS) {
                    repo.rechargeCreate(saved.username, saved.password, yuan, targetAccount = targetAccount)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: YktException.NotInServiceTime) {
                // 服务时间外：App 内提示，**不打开浏览器、不清等待态**（订单 30 分钟自动失效）
                prefs.clearPendingRecharge()
                _arrivalState.value = ArrivalState.Idle
                _arrivalBaseFen = null
                _arrivalAccount = null
                _arrivalTarget = null
                _arrivalWalletBaseFen = null
                onResult(NoticeFeedback(e.message ?: "当前不在充值服务时间内", NoticeTone.Warning))
                return@launch
            } catch (e: YktException) {
                onResult(NoticeFeedback(e.message ?: "下单失败，请稍后重试", NoticeTone.Error))
                return@launch
            } catch (e: Exception) {
                onResult(
                    NoticeFeedback(
                        "下单异常：${e.javaClass.simpleName}（平台可能已改版）",
                        NoticeTone.Error,
                    ),
                )
                return@launch
            }
            if (placed == null) {
                onResult(NoticeFeedback("下单超时，请检查网络后重试", NoticeTone.Error))
                return@launch
            }
            val order = placed.order
            // 到账检测基线（付款前的该卡余额 + 卡号，随下单一起拿到）+
            // 持久化未确认充值（进程被杀后可恢复，DESIGN §4.19「充值」）
            val orderFen = parsedFenOf(yuan)
            val startedAt = System.currentTimeMillis()
            _arrivalBaseFen = placed.cardBalanceBeforeFen
            _arrivalAccount = placed.cardAccount
            _arrivalTarget = placed.targetAccount
            _arrivalWalletBaseFen = placed.walletBalanceBeforeFen
            prefs.setPendingRecharge(
                fen = orderFen,
                at = startedAt,
                balanceBeforeFen = placed.cardBalanceBeforeFen,
                cardAccount = placed.cardAccount,
                targetAccount = placed.targetAccount,
                walletBalanceBeforeFen = placed.walletBalanceBeforeFen,
            )
            when (order) {
                is YktRechargeOrder.WechatPay -> {
                    // 直拉微信（跳过浏览器与收银台页；weixin://wap/pay 由微信客户端接手）
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(order.wechatUrl),
                    )
                    if (launchExternal(intent)) {
                        onResult(NoticeFeedback("已拉起微信支付，支付完成后回到本页等待到账", NoticeTone.Info))
                    } else {
                        onResult(NoticeFeedback("未找到微信，请确认已安装微信后重试", NoticeTone.Error))
                    }
                }
                is YktRechargeOrder.Cashier -> {
                    // 兜底：打开官方收银台（服务端 302 下发的完整 URL）
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(order.cashierUrl),
                    )
                    if (!launchExternal(intent)) {
                        onResult(NoticeFeedback("无法打开浏览器，请检查设备后重试", NoticeTone.Error))
                        return@launch
                    }
                    onResult(NoticeFeedback("已跳转官方收银台，支付完成后回到本页等待到账", NoticeTone.Info))
                }
            }
            watchRechargeArrivalLaunch(
                username = saved.username,
                password = saved.password,
                orderFen = orderFen,
                startedAt = startedAt,
            )
        }
    }

    /** 下单后启动轮询（基线/持久化已就绪；startedAt=下单时刻，进程重启恢复也用它）。 */
    private fun watchRechargeArrivalLaunch(username: String, password: String, orderFen: Long, startedAt: Long) {
        startArrivalWatch(username, password, orderFen, startedAt)
    }

    /** "12.50" → 1250 分；调用前已经 RechargeSheet 校验过，异常值返回 0（只影响流水判定精度）。 */
    private fun parsedFenOf(yuan: String): Long =
        yuan.toBigDecimalOrNull()
            ?.multiply(java.math.BigDecimal(100))
            ?.toLong()
            ?: 0L

    /**
     * 到账轮询：5 秒一轮余额检测（用户要求的高频口径）+ 每 3 轮（15 秒）一次流水增量。
     * 从微信返回、甚至进程被杀重启后，[onHostResume]/init 都会立即补检一轮（见下）。
     */
    private fun startArrivalWatch(username: String, password: String, orderFen: Long, startedAt: Long) {
        watchJob?.cancel()
        _arrivalState.value = ArrivalState.Watching(orderFen = orderFen, startedAt = startedAt)
        watchJob = viewModelScope.launch {
            val deadline = startedAt + ARRIVAL_WATCH_MS
            var round = 0
            while (isActive && System.currentTimeMillis() < deadline) {
                kotlinx.coroutines.delay(ARRIVAL_POLL_MS)
                round++
                if (checkArrivalOnce(username, password, orderFen)) return@launch
                // 流水同步降频：每 3 轮（15s）一次，减轻服务端压力
                if (round % 3 == 0) {
                    if (checkTurnoverArrival(username, password, orderFen, startedAt)) return@launch
                }
            }
            prefs.clearPendingRecharge()
            _pendingConfirmVisible.value = false
            _arrivalState.value = ArrivalState.Timeout(orderFen = orderFen)
        }
    }

    /**
     * 单轮到账检测（余额口径）。**有基线才判**（基线未知时不能用"余额 > 基线"——会把
     * 未到账误报成到账），且只认**付款那张卡**的增长：判定口径在 [YktArrival.balanceArrival]。
     * 基线/卡号缺失（升级前的旧记录）时本口径直接放弃，交给流水口径。
     */
    private suspend fun checkArrivalOnce(
        username: String,
        password: String,
        orderFen: Long,
    ): Boolean {
        try {
            if (_arrivalTarget != null) {
                // 电子账户：查目标钱包当前余额，与钱包基线比对（卡余额不动）。
                // force = true：到账判定必须看实时值，吃缓存会把「已到账」判成「还没到」
                val current = repo.rechargeAccountDetail(username, password, force = true)
                val arrivedFen = YktArrival.walletArrival(
                    balanceBeforeFen = _arrivalWalletBaseFen,
                    orderFen = orderFen,
                    currentFen = current?.second,
                )
                if (arrivedFen != null) {
                    markArrived(orderFen = orderFen, newBalanceFen = arrivedFen)
                    if (current != null) refreshBalanceSilently(username, password)
                    return true
                }
            } else {
                val cards = repo.cards(username, password, force = true)
                if (cards.isNotEmpty()) {
                    _balance.value = buildSnapshot(cards)
                    val arrivedFen = YktArrival.balanceArrival(
                        account = _arrivalAccount,
                        balanceBeforeFen = _arrivalBaseFen,
                        orderFen = orderFen,
                        cardBalances = cards.associate { it.account to it.cardBalanceFen },
                    )
                    if (arrivedFen != null) {
                        markArrived(orderFen = orderFen, newBalanceFen = arrivedFen)
                        return true
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 余额失败不中断，流水口径再试
        }
        return false
    }

    /** 到账后刷一次余额快照（静默；电子账户到账时让生活页余额立即更新）。 */
    private fun refreshBalanceSilently(username: String, password: String) {
        viewModelScope.launch {
            runCatching {
                val cards = repo.cards(username, password, force = true)
                if (cards.isNotEmpty()) _balance.value = buildSnapshot(cards)
            }
        }
    }

    /** 判定到账的三处动作只有这一份：置 Arrived、撤「正在确认」弹窗、清持久化等待记录。 */
    private suspend fun markArrived(orderFen: Long, newBalanceFen: Long?) {
        _arrivalState.value = ArrivalState.Arrived(orderFen = orderFen, newBalanceFen = newBalanceFen)
        _pendingConfirmVisible.value = false
        prefs.clearPendingRecharge()
    }

    private suspend fun checkTurnoverArrival(
        username: String,
        password: String,
        orderFen: Long,
        startedAt: Long,
    ): Boolean {
        try {
            // force = true：这是到账核对，闸门挡掉就等于到账永远发现不了
            syncer.sync(username, password, maxPages = 1, force = true)
            val count = db.yktTurnoverDao().countIncomeSince(startedAt)
            if (count > 0) {
                markArrived(orderFen = orderFen, newBalanceFen = _balance.value?.totalFen)
                return true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 下一轮再试
        }
        return false
    }

    /**
     * 页面回到前台（从微信返回等）：立即补检一轮——不等下一个 5 秒周期，
     * 这是「支付完回来马上看到结果」的关键；进程被杀重启时同时恢复等待态。
     *
     * 本回调在**每次**进页/回前台都会触发（生命周期观察者补齐时会补发 ON_RESUME），
     * 所以「正在确认到账」弹窗按**每笔充值一次**弹：弹过就落库标记，之后同一笔充值
     * 不再弹，等待态改由余额卡的「正在等待到账」行承载。用户关掉弹窗不影响轮询。
     */
    fun onHostResume() {
        val st = _arrivalState.value
        if (st is ArrivalState.Arrived || st is ArrivalState.Timeout) return // 已有结论待用户确认
        viewModelScope.launch {
            val saved = credentialStore.read() ?: return@launch
            val pending = prefs.pendingRecharge.first()
            val orderFen: Long
            val startedAt: Long
            when {
                st is ArrivalState.Watching -> {
                    orderFen = st.orderFen
                    startedAt = st.startedAt
                }
                pending != null -> {
                    orderFen = pending.orderFen
                    startedAt = pending.startedAt
                    restoreArrivalBaseline(pending)
                }
                else -> return@launch
            }
            if (_arrivalState.value !is ArrivalState.Watching) {
                _arrivalState.value = ArrivalState.Watching(orderFen = orderFen, startedAt = startedAt)
            }
            if (checkArrivalOnce(saved.username, saved.password, orderFen)) return@launch // 成功弹窗已就位
            // 未立即到账：给出「正在确认」反馈弹窗——延迟一拍（等返回动画/窗口稳定），
            // 避免回到前台瞬间弹窗引发顶栏 insets 重算跳动（2026-09-21 实测）
            if (pending != null && !pending.confirmShown) {
                kotlinx.coroutines.delay(PENDING_CONFIRM_DELAY_MS)
                if (_arrivalState.value is ArrivalState.Arrived) return@launch
                _pendingConfirmVisible.value = true
                prefs.markPendingRechargeConfirmShown()
            }
            if (!checkTurnoverArrival(saved.username, saved.password, orderFen, startedAt)) {
                startArrivalWatch(saved.username, saved.password, orderFen, startedAt)
            }
        }
    }

    /**
     * 从持久化记录恢复余额口径的基线（付款前卡余额 + 付款卡号）。
     *
     * 两者缺一就无法判定，只能退到流水口径；**不拿「现在的余额」当基线**——
     * 钱已经到账时那等于把判定门槛抬到自己头上，「确认到账」永远不成立。
     */
    private fun restoreArrivalBaseline(pending: PendingRecharge) {
        if (_arrivalBaseFen == null) _arrivalBaseFen = pending.balanceBeforeFen
        if (_arrivalAccount == null) _arrivalAccount = pending.cardAccount
        if (_arrivalTarget == null) _arrivalTarget = pending.targetAccount
        if (_arrivalWalletBaseFen == null) _arrivalWalletBaseFen = pending.walletBalanceBeforeFen
    }

    /** 「正在确认到账」提示弹窗可见性（回到前台且未到账时显示；到账/超时/用户关闭即撤）。 */
    private val _pendingConfirmVisible = MutableStateFlow(false)
    val pendingConfirmVisible: StateFlow<Boolean> = _pendingConfirmVisible

    fun dismissPendingConfirm() {
        _pendingConfirmVisible.value = false
    }

    /**
     * 用户声明「我没有付款」：停到账轮询、清等待态与持久化记录。
     * 未支付订单由平台 30 分钟自动失效，不扣款（DESIGN §4.19）。
     */
    fun notPaid() {
        watchJob?.cancel()
        watchJob = null
        _pendingConfirmVisible.value = false
        _arrivalState.value = ArrivalState.Idle
        _arrivalBaseFen = null
        _arrivalAccount = null
        _arrivalTarget = null
        _arrivalWalletBaseFen = null
        viewModelScope.launch { prefs.clearPendingRecharge() }
    }

    /**
     * 到账判定的余额基线：付款前该卡余额（分）+ 付款卡号。
     * 下单时从 [edu.jxslu.schedule.data.ykt.YktRechargeStart] 拿到，并随未确认充值落库；
     * 进程重启后由 [restoreArrivalBaseline] 从记录里恢复。取不到就是 null → 只用流水口径。
     */
    @Volatile
    private var _arrivalBaseFen: Long? = null

    @Volatile
    private var _arrivalAccount: String? = null

    /** 充值目标：`<account>-000` = 电子账户（到账判定走钱包口径）；null = 正式卡。 */
    @Volatile
    private var _arrivalTarget: String? = null

    /** 充电子账户时：付款前目标钱包余额（分）。 */
    @Volatile
    private var _arrivalWalletBaseFen: Long? = null

    private var watchJob: kotlinx.coroutines.Job? = null

    /** 充值到账状态（UI 据此渲染等待卡/成功弹窗/超时提示）。 */
    sealed interface ArrivalState {
        data object Idle : ArrivalState

        /** 等待到账：[orderFen] 下单金额（分），[startedAt] 下单时刻。 */
        data class Watching(val orderFen: Long, val startedAt: Long) : ArrivalState

        /** 已到账：[newBalanceFen] 可为 null（流水先出而余额视图未刷新）。 */
        data class Arrived(val orderFen: Long, val newBalanceFen: Long?) : ArrivalState

        /** 窗口内未检测到（多为到账延迟，账单页会自动补显）。 */
        data class Timeout(val orderFen: Long) : ArrivalState
    }

    private val _arrivalState = MutableStateFlow<ArrivalState>(ArrivalState.Idle)
    val arrivalState: StateFlow<ArrivalState> = _arrivalState

    fun dismissArrival() {
        _arrivalState.value = ArrivalState.Idle
    }

    companion object {
        private const val LOGIN_TOTAL_TIMEOUT_MS = 30_000L

        /** 充值下单总超时（queryCard + thirdOrder 两跳，正常 1–3 秒）。 */
        private const val RECHARGE_TIMEOUT_MS = 20_000L

        /** 到账轮询总时长（30 分钟：饭卡充值常见延迟数分钟至数十分钟）。 */
        private const val ARRIVAL_WATCH_MS = 30 * 60_000L

        /** 轮询间隔。 */
        private const val ARRIVAL_POLL_MS = 5_000L

        /** 返回前台后「确认中」弹窗的延迟（ms）：避开返回动画期的窗口/insets 重排。 */
        private const val PENDING_CONFIRM_DELAY_MS = 800L

        fun Factory(context: Context) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                CampusCardViewModel(context.applicationContext) as T
        }
    }
}
