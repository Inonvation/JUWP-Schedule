package edu.jxslu.schedule.ui.ebike

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.SubpageActivity
import edu.jxslu.schedule.SubpageScreen
import edu.jxslu.schedule.data.session.SessionService
import edu.jxslu.schedule.domain.EbikeCardExpiry
import edu.jxslu.schedule.domain.KqcxAuth
import edu.jxslu.schedule.domain.RideRecord
import edu.jxslu.schedule.domain.capabilities
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.DialogWindowFadeEffect
import edu.jxslu.schedule.ui.common.FooterDisclaimer
import edu.jxslu.schedule.ui.common.InlineNoticeRow
import edu.jxslu.schedule.ui.common.LoadingHint
import edu.jxslu.schedule.ui.common.NoticeTone
import edu.jxslu.schedule.ui.common.SettingItem
import edu.jxslu.schedule.ui.common.SessionExpiredNotice
import edu.jxslu.schedule.ui.common.SettingsSection
import edu.jxslu.schedule.ui.common.postNotice
import edu.jxslu.schedule.ui.theme.semanticColors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ScooterElectric
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 快趣账号页（DESIGN §4.32；**2026-09-29 起只管账号与本机记录**）。
 *
 * 骑行状态并进了快趣出行页（`RideScreen`）的骑行态面板，
 * 使用方式切换收进了「快趣出行设置」弹层——本页只留登录 / 账号 / 本机骑行记录，
 * 与出码页（工作台）不再有重复区块。
 *
 * 两态：未登录 = 手机号 + 密码表单（失败保留输入、Snackbar 提示）；
 * 已登录 = 账号掩码 + 退出登录。「打开快趣出行」进快趣出行页（两档共用那一个页面）。
 *
 * **免责边界**：开锁 / 临时锁车 / 还车在工作台逐次确认后执行；支付与免押授权不做
 * ——页尾一行常驻说明。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KvcxScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: KvcxViewModel = viewModel(factory = KvcxViewModel.factory(context))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val records by viewModel.rideRecords.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // 使用方式（DESIGN §3.9 / §4.32）：初值阻塞读一次，页面不该先按另一档画一帧
    val ebikePrefs = remember(context) { Graph.displayPrefs(context) }
    val useMode by ebikePrefs.ebikeUseMode.collectAsStateWithLifecycle(
        initialValue = remember { runBlocking { ebikePrefs.ebikeUseMode.first() } },
    )
    // 能力矩阵（DESIGN §3.9）：本页的账号 / 记录属于 App 内用车那一档
    val appRideEnabled = useMode.capabilities().inAppRide

    // 账号方式下上次判定登录过期：进页说一句为什么退回登录表单（小程序方式没有账号，不提示）
    if (appRideEnabled) {
        SessionExpiredNotice(SessionService.Kqcx, state.loggedIn, snackbar)
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                // 事件流用 postNotice 单开协程发：收集协程不卡在「等这条提示收场」上，
                // 事件成串来时新的提示才能当场顶掉旧的（见 AppNotice.kt）
                is KvcxEvent.Notice -> scope.postNotice(snackbar, event.text, event.tone)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("快趣账号") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = {
            AppSnackbarHost(state = snackbar)
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
            if (appRideEnabled) {
                if (state.loggedIn) {
                    AccountSection(state, viewModel)
                    // 快趣资产（2026-09-30）：余额 / 卡券 / 会员卡，只读
                    AssetsSection(state = state, onRefresh = viewModel::refreshAssets)
                } else {
                    LoginSection(state, viewModel)
                }
                // 本机记录与登录态无关：退出登录也照样看得到自己的骑行史
                RideHistorySection(records = records, onClear = viewModel::clearRecords)
            } else {
                // 兜底：本页入口在「小程序方式」下是隐藏的（校园服务里那一行按模式显隐），
                // 正常进不来。真进来了也不该给一个死胡同，所以说清去哪儿切
                InlineNoticeRow(
                    message = "当前是「微信小程序」使用方式：本页的账号与本机记录都已收起，" +
                        "开车与还车在微信小程序里完成。要切换使用方式，请到快趣出行页标题栏的" +
                        "「使用方式」入口。",
                    tone = NoticeTone.Info,
                )
            }
            // 边界说明只留一行（此前整段免责区与出码页重复，2026-09-29 收敛）
            FooterDisclaimer(
                body = "非官方账号页：开锁 / 锁车 / 还车在「快趣出行」工作台逐次确认后执行，" +
                    "计费与善后以快趣为准；不提供支付与免押授权。",
            )
        }
    }

    // 骑行卡券临近到期（前一天）的提醒：查资产时算出，关掉即落「已提醒」键（同一张卡只弹一次）
    state.cardExpiry?.let { coupons ->
        CardExpiryDialog(coupons = coupons, onDismiss = viewModel::dismissCardExpiry)
    }
}

/**
 * 「骑行卡即将到期」提醒弹窗（DESIGN §4.32，2026-10-14）：资产查询发现卡券落在
 * 「到期前一天」窗口内时弹一次。明细与后果文案都从 [EbikeCardExpiry] 取（唯一出处），
 * 这里只排版；长文配 `heightIn(max)` + `verticalScroll`，小屏与大字体档位下按钮不被挤出。
 */
@Composable
private fun CardExpiryDialog(coupons: List<KqcxAuth.AssetCoupon>, onDismiss: () -> Unit) {
    val lines = remember(coupons) {
        EbikeCardExpiry.reminderLines(coupons, System.currentTimeMillis())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("骑行卡即将到期") },
        text = {
            // 过渡效果必须落在弹窗窗口自己的组合里（内容槽第一行），放外面取到的是页面窗口
            DialogWindowFadeEffect()
            Column(
                modifier = Modifier
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                lines.forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                Text(
                    text = EbikeCardExpiry.REMINDER_NOTE,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("知道了") }
        },
    )
}

// ── 本机骑行记录 ──

/**
 * 「最近骑行」（DESIGN §3.9，2026-09-28）：只记**本机用车**这条链路（起点 / 终点 /
 * 车号 / 费用都齐的那条），「点扫一扫 → 在微信里骑」只有猜测值，不进统计。
 */
@Composable
private fun RideHistorySection(records: List<RideRecord>, onClear: () -> Unit) {
    var confirmClear by remember { mutableStateOf(false) }
    SettingsSection(
        title = "最近骑行",
        subtitle = "本机记录，只记在 App 里开的车；保留最近 ${RideRecord.LIMIT} 次。",
    ) {
        if (records.isEmpty()) {
            Text(
                text = "还没有记录。在 App 里开锁骑一次，这里就会留一条。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(vertical = 8.dp),
            )
            return@SettingsSection
        }
        records.take(RideRecord.SHOW_LIMIT).forEach { record ->
            RideRecordRow(record)
        }
        if (records.size > RideRecord.SHOW_LIMIT) {
            Text(
                text = "只显示最近 ${RideRecord.SHOW_LIMIT} 次",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        TextButton(onClick = { confirmClear = true }) {
            Text("清空本机记录", color = MaterialTheme.colorScheme.error)
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空本机记录？") },
            text = {
                // 过渡落在弹窗窗口自己的组合里（内容槽第一行）；放 AlertDialog 外面取到的是
                // 页面窗口、挂不上本应用样式（等于没调用，见 ui-common.md）
                DialogWindowFadeEffect()
                Text("只清本机这份骑行史，快趣侧的订单与账单不受影响。")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClear()
                }) {
                    Text("清空", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun RideRecordRow(record: RideRecord) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "车 ${record.carNum}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = RECORD_TIME_FORMAT.format(
                    Instant.ofEpochMilli(record.endAt).atZone(ZoneId.systemDefault()),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        Text(
            text = buildString {
                append(RideRecord.durationText(record.durationSeconds))
                append(" · ")
                append(record.feeCents?.let { "¥%.2f".format(it / 100.0) } ?: "费用未记录")
                append(if (record.settled) " · 已结清" else " · 未结清")
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (record.settled) {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            } else {
                MaterialTheme.semanticColors.warning
            },
        )
    }
}

private val RECORD_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.US)

// ── 快趣资产（2026-09-30，只读） ──

/**
 * 「快趣资产」：余额 / 卡券 / 会员卡的**只读**展示（DESIGN §4.32）。
 *
 * 充值、退押、买卡都走微信收银台（App 不做支付，DESIGN §4.32 红线），所以这里只有"看"——
 * 区标题下一行就把出路说清，别让人在这张卡里找充值按钮。进页自动查一次，失败保留
 * 已有内容、只给一行说明与「刷新」出路（不打扰：资产不是本页的主任务）。
 * 余额行 2026-10-07 排版统一：金额是这一节的主角，用 titleMedium 加重大字呈现
 * （原先是 SettingItem 把金额塞进标题位，与副行「充值余额与赠送余额」大小倒挂）。
 */
@Composable
private fun AssetsSection(state: KvcxUiState, onRefresh: () -> Unit) {
    SettingsSection(
        title = "快趣资产",
        subtitle = "只读展示；充值、退押、买卡在快趣官方渠道（微信小程序）完成。",
    ) {
        when {
            state.assetsLoading && state.assets == null -> LoadingHint("正在查询资产…")

            state.assets == null -> Text(
                text = "资产查询失败，点「刷新」再试一次。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(vertical = 8.dp),
            )

            else -> {
                val assets = state.assets
                // 余额行：充值 + 赠送（官方钱包页同两项；缺数据不显示成 ¥0）
                assets.balance?.displayText?.let { balance ->
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Text(
                            text = balance,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "充值余额与赠送余额",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        )
                    }
                }
                assets.coupons.forEach { coupon -> AssetCouponRow(coupon) }
                assets.members.forEach { member -> AssetMemberRow(member) }
            }
        }
        TextButton(onClick = onRefresh, enabled = !state.assetsLoading) {
            Text(if (state.assetsLoading) "查询中…" else "刷新")
        }
    }
}

/** 一张骑行卡券：标题（次数 / 无限 + 车型）+ 副行（免费时长与有效期）。 */
@Composable
private fun AssetCouponRow(coupon: KqcxAuth.AssetCoupon) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Text(
            text = "${coupon.titleText} · ${coupon.deviceLabel}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        if (coupon.detailText.isNotBlank()) {
            Text(
                text = coupon.detailText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** 一张会员卡：折扣 + 有效期。 */
@Composable
private fun AssetMemberRow(member: KqcxAuth.AssetMember) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Text(
            text = listOfNotNull("会员卡", member.discountText?.let { "$it 优惠" }).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        member.endAt?.trim()?.takeIf { it.isNotEmpty() }?.let { end ->
            Text(
                text = "有效期至 ${end.split(" ").first()}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

// ── 已登录：账号 ──

/**
 * 账号区（2026-10-07 排版统一）：身份行（SettingItem）+ 动作行。
 * 原先是身份行下面全宽堆两颗 OutlinedButton——「打开快趣出行」是主去向、
 * 「退出登录」是低频破坏性动作，两者并列等重不合理。现在主去向独占一行
 * OutlinedButton，退出收进右侧的 error 色文字按钮。
 */
@Composable
private fun AccountSection(state: KvcxUiState, viewModel: KvcxViewModel) {
    val context = LocalContext.current
    SettingsSection(title = "账号", subtitle = "凭证存于本机加密存储，token 只在内存；不进云备份。") {
        SettingItem(
            title = state.accountMobile.ifBlank { "已登录" },
            subtitle = "快趣出行账号",
            icon = HugeIcons.ScooterElectric,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = { SubpageActivity.start(context, SubpageScreen.RIDE) },
                modifier = Modifier.weight(1f).height(42.dp),
            ) {
                Text("打开快趣出行")
            }
            TextButton(
                onClick = { viewModel.logout() },
                modifier = Modifier.padding(start = 4.dp),
            ) {
                Text("退出登录", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

// ── 未登录：表单 ──

@Composable
private fun LoginSection(state: KvcxUiState, viewModel: KvcxViewModel) {
    var showPassword by remember { mutableStateOf(false) }
    // 记住登录（2026-10-21 用户拍板：默认开）：默认把凭证加密存在本机，token 失效时静默重登
    var remember by remember { mutableStateOf(true) }
    // 卡片化（2026-09-29 统一风格）：本页其余区块都是 SettingsSection，登录表单裸在页面
    // 背景上会显得"没装进容器"；它又是本页的主任务，套同一套容器最稳
    SettingsSection(
        title = "登录",
        subtitle = "账号即快趣出行 App / 小程序的注册手机号与密码。",
    ) {
        OutlinedTextField(
            value = state.mobile,
            onValueChange = viewModel::updateMobile,
            label = { Text("手机号") },
            isError = state.mobileError != null,
            supportingText = state.mobileError?.let { { Text(it) } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.password,
            onValueChange = viewModel::updatePassword,
            label = { Text("密码") },
            singleLine = true,
            visualTransformation =
                if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                // 眼睛图标与一卡通 / 胖乖 / 「我的」页同一套（2026-10-07 统一，原是文字按钮）
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(
                        imageVector = if (showPassword) HugeIcons.ViewOff else HugeIcons.View,
                        contentDescription = if (showPassword) "隐藏密码" else "显示密码",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("记住登录", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "关闭则不保存密码；下次打开需要重新登录",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }
            Switch(checked = remember, onCheckedChange = { remember = it })
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { viewModel.login(remember) },
            enabled = !state.loggingIn && state.mobile.isNotBlank() && state.password.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Text("登录", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        // 忘记密码的出路（2026-09-30 用户口径）：改密码只能在小程序里做，App 这边既不存
        // "找回"流程也没有客服通道，所以必须把去哪儿改写清楚。两个落点从解包产物核对过：
        // 小程序登录页的「忘记密码」（手机号 + 短信验证码重设，`pages/login/forget`），
        // 登录后「设置 → 修改密码」（只填新密码，`pages/setting/modifypassword`）。
        Text(
            text = "忘记或不知道密码？在微信里打开「快趣出行」小程序：登录页点「忘记密码」，" +
                "用手机号 + 短信验证码重设；登录后在「设置 → 修改密码」也能改。" +
                "密码默认加密保存在本机（可关闭「记住登录」）；不进云备份，请妥善保管。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}
