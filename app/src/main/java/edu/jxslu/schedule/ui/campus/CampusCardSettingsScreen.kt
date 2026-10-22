package edu.jxslu.schedule.ui.campus

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.domain.BalanceAlert
import edu.jxslu.schedule.domain.RechargeDisclaimer
import edu.jxslu.schedule.domain.YktArrival
import edu.jxslu.schedule.data.ykt.rechargeTargetCard
import edu.jxslu.schedule.data.session.LoginState
import edu.jxslu.schedule.data.session.LoginStateRules
import edu.jxslu.schedule.data.session.LoginTarget
import edu.jxslu.schedule.data.session.SessionStatus
import edu.jxslu.schedule.startActivityOutsideApp
import edu.jxslu.schedule.ui.common.AccountCard
import edu.jxslu.schedule.ui.common.AppNoticeVisuals
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.DialogWindowFadeEffect
import edu.jxslu.schedule.ui.common.NoticeFeedback
import edu.jxslu.schedule.ui.common.NoticeTone
import edu.jxslu.schedule.ui.common.RechargeDisclaimerDialog
import edu.jxslu.schedule.ui.common.SettingsNote
import edu.jxslu.schedule.ui.common.SettingSwitchRow
import edu.jxslu.schedule.ui.common.SettingsSection
import edu.jxslu.schedule.ui.common.WheelValueDialog
import edu.jxslu.schedule.ui.common.emphasizedText
import edu.jxslu.schedule.ui.common.pinnedStatusBars
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import edu.jxslu.schedule.ui.common.showNotice
import edu.jxslu.schedule.ui.life.PowerRoomPickerSheet
import edu.jxslu.schedule.ui.life.PowerRoomPickerViewModel
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.CreditCard
import me.rerere.hugeicons.stroke.Home01
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import kotlinx.coroutines.launch
import androidx.compose.ui.text.font.FontWeight

/**
 * 我的 → 校园卡（DESIGN §3.10）。管开启状态、凭证与两个余额提醒，不看付款码。
 *
 * 凭证交互（2026-09-28 改：开关换成按钮）：**默认关闭**；在「校园卡账号」卡里填好学号密码
 * 点「保存并开启」，先真实登录验证一次，成功才落库并置开启；已开启后同一颗按钮变成「保存」
 * （覆盖更新凭证），卡头的「关闭并清除」二次确认后清除凭证。密码框留空 = 沿用已保存的
 * 密码（覆盖场景才需要重输）。
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
            snackbar.showNotice(
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
    // 同一份值也供账户卡显示；开启 / 关闭 / 覆盖保存后由 [feedback] 重读（2026-09-27）。
    var savedUsername by remember { mutableStateOf(viewModel.savedUsername) }
    var username by rememberSaveable { mutableStateOf(savedUsername.orEmpty()) }
    var password by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var confirmDisable by remember { mutableStateOf(false) }
    var showRechargeSheet by remember { mutableStateOf(false) }
    var showPowerPicker by remember { mutableStateOf(false) }
    var showYktPicker by remember { mutableStateOf(false) }
    var showPowerIntervalPicker by remember { mutableStateOf(false) }
    var showYktIntervalPicker by remember { mutableStateOf(false) }
    // 「选择寝室」弹层（DESIGN §4.24「未设置寝室」，2026-10-08）：入口在下方「寝室电费」卡。
    // 房号显示取本机最新读数（零网络；权威状态以生活页电费卡为准），改绑成功后就地更新
    var showRoomPicker by remember { mutableStateOf(false) }
    var roomLabel by remember { mutableStateOf<String?>(null) }
    val roomPickerViewModel: PowerRoomPickerViewModel = viewModel(
        factory = PowerRoomPickerViewModel.factory(context.applicationContext),
    )
    androidx.compose.runtime.LaunchedEffect(Unit) {
        roomLabel = runCatching { Graph.powerReadingStore(context.applicationContext).latest() }
            .getOrNull()?.roomName?.takeIf { it.isNotBlank() }
    }

    // 充值免责声明（与生活页同一条口径，domain/RechargeDisclaimer）：充值入口打开
    // 弹层前先过提醒，勾「一周内不再提醒」静默 7 天；首次（从未确认过）锁 5 秒
    // 才能关，之后任何一次弹出立即可关。继续或取消都算「已确认」。
    val displayPrefs = remember { Graph.displayPrefs(context) }
    val disclaimerSuppressUntil by displayPrefs.rechargeDisclaimerSuppressUntil
        .collectAsStateWithLifecycle(initialValue = 0L)
    val disclaimerSeenAt by displayPrefs.rechargeDisclaimerSeenAt
        .collectAsStateWithLifecycle(initialValue = 0L)
    var showRechargeDisclaimer by remember { mutableStateOf(false) }

    val powerAlertEnabled by viewModel.powerAlertEnabled.collectAsStateWithLifecycle()
    val powerAlertYuan by viewModel.powerAlertYuan.collectAsStateWithLifecycle()
    val yktAlertEnabled by viewModel.yktAlertEnabled.collectAsStateWithLifecycle()
    val yktAlertYuan by viewModel.yktAlertYuan.collectAsStateWithLifecycle()
    val powerAlertIntervalHours by viewModel.powerAlertIntervalHours.collectAsStateWithLifecycle()
    val yktAlertIntervalHours by viewModel.yktAlertIntervalHours.collectAsStateWithLifecycle()

    fun feedback(notice: NoticeFeedback) {
        busy = false
        if (notice.tone != NoticeTone.Error) {
            password = ""
            // 开启 / 关闭 / 覆盖保存都从这里收尾：重读一次凭证，账户卡跟着换身份
            savedUsername = viewModel.savedUsername
        }
        scope.launch {
            snackbar.showNotice(AppNoticeVisuals(notice.text, tone = notice.tone))
        }
    }

    fun showNotice(text: String, tone: NoticeTone = NoticeTone.Info) {
        scope.launch { snackbar.showNotice(AppNoticeVisuals(text, tone = tone)) }
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
                windowInsets = pinnedStatusBars(),
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 账户卡（DESIGN §3.3）：与教务账户页**同一形态**——同样一句「我是谁」，
            // 两个页面长得一样。原先这一页只有开关和余额，看不出当前用的是哪个账号。
            val profileName by remember { Graph.displayPrefs(context).profileName }
                .collectAsStateWithLifecycle(initialValue = "")
            val profileStudentId by remember { Graph.displayPrefs(context).profileStudentId }
                .collectAsStateWithLifecycle(initialValue = "")
            val suspendedTargets by SessionStatus.suspended.collectAsStateWithLifecycle()
            val yktState = LoginStateRules.derive(
                credentialExists = savedUsername != null,
                webSessionExists = false,
                suspended = LoginTarget.Ykt in suspendedTargets,
            )
            AccountCard(
                username = savedUsername ?: profileStudentId,
                name = profileName,
                statusText = when (yktState) {
                    LoginState.Expired -> "登录状态已失效，请重新填写查询密码"
                    LoginState.LoggedIn -> "已登录 · 余额、付款码、电费共用这一份凭证"
                    LoginState.NotLoggedIn -> "未登录 · 填学号与查询密码后开启"
                },
                statusState = yktState,
            )

            // 开启状态（DESIGN §3.10）：状态行置顶说清「现在能不能用」，尾行安全说明不变
            SettingsSection(
                title = "校园卡账号",
                subtitle = "账号为学号，默认密码通常为身份证后六位（仅支持数字密码）",
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (enabled) "已开启" else "未开启",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            if (enabled) {
                                "左侧栏钱包可用付款码与电费，充值也从侧栏发起"
                            } else {
                                "在下方填入学号与查询密码，保存并验证成功后即开启"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        )
                    }
                    if (enabled) {
                        TextButton(
                            onClick = { confirmDisable = true },
                            enabled = !busy,
                        ) { Text("关闭并清除") }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    emphasizedText(
                        "**付款码等同现金**，会随时变更，**请勿截图或分享**。" +
                            "学号与密码**只存本机**（Keystore 加密，排除云备份），登录时只发给学校对应接口；" +
                            "**启用风险自负**。完整条款见「设置 → 条款与声明」。",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }

            // 余额状态区（DESIGN §4.19 B5）：开启即显示（骨架占位避免进页跳动），点击跳流水页
            val balanceSnapshot = balance
            if (enabled) {
                SettingsSection(
                    title = "余额",
                    subtitle = "充值后回到本页，余额与流水会自动刷新",
                ) {
                    // 余额区可点重拉（2026-10-07 补重试出路）：进页只拉一次、失败静默，
                    // 失败后原先永远停在「获取中…」，只能重进页面。快趣资产同场景给了
                    // 「刷新」，这里对齐——余额行整块可点，拉取中不重复发起
                    val balanceRefreshing by viewModel.balanceRefreshing.collectAsStateWithLifecycle()
                    val canRetry = balanceSnapshot == null && !balanceRefreshing
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (canRetry) {
                                    Modifier.clickable(
                                        onClickLabel = "重新获取余额",
                                    ) { viewModel.refreshBalance(force = true) }
                                } else {
                                    Modifier
                                }
                            ),
                    ) {
                        if (balanceSnapshot != null) {
                            Text(
                                "正式卡 ¥%.2f".format(balanceSnapshot.cardFen / 100.0) +
                                    if (balanceSnapshot.accountFen > 0) " · 电子账户 ¥%.2f".format(balanceSnapshot.accountFen / 100.0) else "",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        } else {
                            // 骨架：进页拉余额的 1–3 秒内占位，数据到达平滑填充
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "卡余额 获取中…",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                )
                                Spacer(Modifier.width(8.dp))
                                if (canRetry) {
                                    Text(
                                        "点此重新获取",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
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
                                ("正在等待充值到账（¥%.2f）· 约 %d 秒检测一次".format(
                                    watching.orderFen / 100.0,
                                    YktArrival.arrivalPollIntervalMs(System.currentTimeMillis() - watching.startedAt) / 1000,
                                )),
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
                            onClick = {
                                if (RechargeDisclaimer.isSuppressed(
                                        disclaimerSuppressUntil,
                                        System.currentTimeMillis(),
                                    )
                                ) {
                                    showRechargeSheet = true
                                } else {
                                    showRechargeDisclaimer = true
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = watching == null,
                        ) { Text("充值") }
                    }
                }
            }

            SettingsSection(
                title = "账号凭证",
                content = {
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
                    Spacer(Modifier.height(12.dp))
                    // 保存即开启（2026-09-28 改）：填完学号密码点这一颗，先真实登录验证一次，
                    // 成功才落库并置开启；已开启时按钮只作「覆盖更新凭证」用，关闭走上面那张卡。
                    Button(
                        onClick = {
                            if (busy) return@Button
                            busy = true
                            viewModel.saveAndEnable(username, password, ::feedback)
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) {
                        if (busy) {
                            // size 而非 height：height-only 约束下 40×40 的圆环被画成 40×24 椭圆
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (enabled) "保存" else "保存并开启")
                    }
                },
            )

            // 寝室电费房间（DESIGN §4.24「未设置寝室」，2026-10-08）：官方要求先在缴费平台
            // 确认寝室，电费查询与充值才可用；未设置时生活页电费栏也会给同一个出口。
            // 这里是「设置 / 改绑」的常驻入口（官方页同为 upsert 语义，允许改绑）。
            SettingsSection(
                title = "寝室电费",
                subtitle = "在缴费平台确认的寝室；电费查询与充值都以它为准",
            ) {
                SettingValueRow(
                    title = "寝室电费房间",
                    value = roomLabel ?: "未设置",
                    enabled = enabled,
                    onClick = {
                        roomPickerViewModel.start()
                        showRoomPicker = true
                    },
                )
            }

            // 余额提醒（DESIGN §3.13）：两个来源共用上面那份凭证，收成一张卡两行——
            // 开关行 + 各自的阈值行（开关关闭时阈值行置灰但值照旧显示）。
            SettingsSection(
                title = "余额提醒",
                subtitle = "两个提醒共用这份凭证；关闭凭证时会一并关掉",
            ) {
                SettingSwitchRow(
                    title = "寝室电费提醒",
                    subtitle = "低于设定金额时发通知；检查间隔在下一行调整",
                    icon = HugeIcons.Home01,
                    checked = powerAlertEnabled,
                    onCheckedChange = { want -> toggleAlert(want) { viewModel.setPowerAlertEnabled(it) } },
                )
                SettingValueRow(
                    title = "电费提醒阈值",
                    value = BalanceAlert.powerLabel(powerAlertYuan),
                    enabled = powerAlertEnabled,
                    onClick = { showPowerPicker = true },
                )
                SettingValueRow(
                    title = "电费检查间隔",
                    value = BalanceAlert.intervalLabel(powerAlertIntervalHours),
                    enabled = powerAlertEnabled,
                    onClick = { showPowerIntervalPicker = true },
                )
                SettingSwitchRow(
                    title = "一卡通余额提醒",
                    subtitle = "只算正式卡余额（付款码扣款的那个钱包）",
                    icon = HugeIcons.CreditCard,
                    checked = yktAlertEnabled,
                    onCheckedChange = { want -> toggleAlert(want) { viewModel.setYktAlertEnabled(it) } },
                )
                SettingValueRow(
                    title = "余额提醒阈值",
                    value = BalanceAlert.yktLabel(yktAlertYuan),
                    enabled = yktAlertEnabled,
                    onClick = { showYktPicker = true },
                )
                SettingValueRow(
                    title = "余额检查间隔",
                    value = BalanceAlert.intervalLabel(yktAlertIntervalHours),
                    enabled = yktAlertEnabled,
                    onClick = { showYktIntervalPicker = true },
                )
            }

            SettingsSection(title = "关于余额提醒") {
                SettingsNote("后台按间隔检查（默认 6 小时），可能被省电推迟；打开 App 会补查一次")
                SettingsNote("余额持续偏低只提醒一次，充值回升后消停")
                SettingsNote("提醒依赖上面保存的凭证；关掉凭证时两个提醒一并关")
                SettingsNote("取数失败当次不提醒也不重试，避免反复登录触发风控")
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

    if (showPowerIntervalPicker) {
        WheelValueDialog(
            title = "电费检查间隔",
            values = BalanceAlert.INTERVAL_CHOICES_HOURS.map { BalanceAlert.intervalLabel(it) },
            initialIndex = BalanceAlert.intervalChoiceIndex(powerAlertIntervalHours),
            onConfirm = { index ->
                showPowerIntervalPicker = false
                viewModel.setPowerAlertIntervalHours(BalanceAlert.INTERVAL_CHOICES_HOURS[index])
            },
            onDismiss = { showPowerIntervalPicker = false },
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

    if (showYktIntervalPicker) {
        WheelValueDialog(
            title = "余额检查间隔",
            values = BalanceAlert.INTERVAL_CHOICES_HOURS.map { BalanceAlert.intervalLabel(it) },
            initialIndex = BalanceAlert.intervalChoiceIndex(yktAlertIntervalHours),
            onConfirm = { index ->
                showYktIntervalPicker = false
                viewModel.setYktAlertIntervalHours(BalanceAlert.INTERVAL_CHOICES_HOURS[index])
            },
            onDismiss = { showYktIntervalPicker = false },
        )
    }

    // 选择寝室（设置 / 改绑）：与生活页同一个弹层与状态机；成功后更新行上的房号
    if (showRoomPicker) {
        PowerRoomPickerSheet(
            viewModel = roomPickerViewModel,
            onDismiss = { showRoomPicker = false },
            onBound = { room ->
                showRoomPicker = false
                roomLabel = room
                scope.launch {
                    snackbar.showNotice(AppNoticeVisuals("已设置寝室：$room", tone = NoticeTone.Success))
                }
            },
        )
    }

    if (confirmDisable) {
        AlertDialog(
            onDismissRequest = { confirmDisable = false },
            title = { Text("关闭并清除凭证？") },
            text = {
                DialogWindowFadeEffect()
                Text(
                    "将清除已保存的学号密码，左侧栏的付款码与钱包回到未开启状态，寝室电费与余额提醒也会一并关闭" +
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
                                snackbar.showNotice(
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
    if (showRechargeDisclaimer) {
        RechargeDisclaimerDialog(
            closableAfterMs = RechargeDisclaimer.closeLockMs(disclaimerSeenAt, System.currentTimeMillis()),
            onContinue = { suppressWeek ->
                showRechargeDisclaimer = false
                if (suppressWeek) {
                    scope.launch {
                        displayPrefs.setRechargeDisclaimerSuppressUntil(
                            RechargeDisclaimer.suppressUntil(System.currentTimeMillis()),
                        )
                    }
                }
                scope.launch { displayPrefs.markRechargeDisclaimerSeen() }
                showRechargeSheet = true
            },
            onDismiss = {
                showRechargeDisclaimer = false
                scope.launch { displayPrefs.markRechargeDisclaimerSeen() }
            },
        )
    }

    if (showRechargeSheet) {
        RechargeSheet(
            balanceFen = balance?.totalFen,
            // 电子账户余额来自同一份 queryCard（ACCOUNT 行）；null = 不显示账户切换
            accountFen = balance?.accountFen,
            // 二次确认弹窗里加粗展示的目标卡号：与下单链路同一份选取口径
            cardAccount = balance?.cards?.rechargeTargetCard()?.account,
            onDismiss = { showRechargeSheet = false },
            onLaunch = { yuan, toElectric ->
                showRechargeSheet = false
                busy = true
                scope.launch {
                    // 下单到回执最长 [RECHARGE_TIMEOUT_MS]（农行收银台那跳更久），
                    // 弹层已关、页面无任何变化——先给一条常驻的进行中提示（Indefinite），
                    // 结束回调里 busy=false 后由结果 Snackbar 顶掉它
                    val progressJob = scope.launch {
                        snackbar.showNotice(
                            AppNoticeVisuals(
                                "正在发起充值…请稍候",
                                tone = NoticeTone.Info,
                                duration = SnackbarDuration.Indefinite,
                            ),
                        )
                    }
                    val target = if (toElectric) viewModel.electricAccountType() else null
                    viewModel.recharge(
                        yuan,
                        targetAccount = target,
                        // 统一走 startActivityOutsideApp：拉起前把本窗口的推入/滑出过渡换成
                        // 静止，防止从外部应用返回时重放右推动画（微信是 singleTask，开不进
                        // 本 task，「不设 NEW_TASK」只救得了浏览器——2026-09-26 修页面跳动）
                        launchExternal = { intent ->
                            runCatching { context.startActivityOutsideApp(intent) }.isSuccess
                        },
                    ) { notice ->
                        busy = false
                        progressJob.cancel()
                        scope.launch {
                            snackbar.showNotice(AppNoticeVisuals(notice.text, tone = notice.tone))
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

    // 「支付成功」：付款码页检测到扣款会自己退出，由共享宿主弹在退回来的这一页
    // （`CampusPayResultDialogHost`，2026-10-07 起本页与 MainActivity 根层共用一份实现）
    CampusPayResultDialogHost()
}


/**
 * 阈值行（提醒卡的值行）：标题 + 右侧当前值，可点换值。
 * 开关关闭时置灰不可点，但值照旧显示（用户能看见上次设的是多少，重新打开时不用重设）。
 */
@Composable
private fun SettingValueRow(
    title: String,
    value: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val haptics = rememberAppHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(
                if (enabled) {
                    Modifier.clickable {
                        haptics.tap()
                        onClick()
                    }
                } else {
                    Modifier
                }
            )
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            },
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            },
        )
    }
}
