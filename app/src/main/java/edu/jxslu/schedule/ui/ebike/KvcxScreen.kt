package edu.jxslu.schedule.ui.ebike

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import edu.jxslu.schedule.domain.KqcxAuth
import edu.jxslu.schedule.domain.RideRecord
import edu.jxslu.schedule.domain.capabilities
import edu.jxslu.schedule.ui.common.AppNoticeVisuals
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.InlineNoticeRow
import edu.jxslu.schedule.ui.common.LoadingHint
import edu.jxslu.schedule.ui.common.NoticeTone
import edu.jxslu.schedule.ui.common.SettingItem
import edu.jxslu.schedule.ui.common.SettingsSection
import edu.jxslu.schedule.ui.theme.semanticColors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
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
    // 使用方式（DESIGN §3.9 / §4.32）：初值阻塞读一次，页面不该先按另一档画一帧
    val ebikePrefs = remember(context) { Graph.displayPrefs(context) }
    val useMode by ebikePrefs.ebikeUseMode.collectAsStateWithLifecycle(
        initialValue = remember { runBlocking { ebikePrefs.ebikeUseMode.first() } },
    )
    // 能力矩阵（DESIGN §3.9）：本页的账号 / 记录属于 App 内用车那一档
    val appRideEnabled = useMode.capabilities().inAppRide

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is KvcxEvent.Notice ->
                    snackbar.showSnackbar(AppNoticeVisuals(event.text, tone = event.tone))
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("快趣账号") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(HugeIcons.ArrowLeft01, contentDescription = "返回")
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
            Text(
                text = "非官方账号页：开锁 / 锁车 / 还车在「快趣出行」工作台逐次确认后执行，" +
                    "计费与善后以快趣为准；不提供支付与免押授权。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            )
        }
    }
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
            text = { Text("只清本机这份骑行史，快趣侧的订单与账单不受影响。") },
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
                    SettingItem(title = balance, subtitle = "充值余额与赠送余额")
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

@Composable
private fun AccountSection(state: KvcxUiState, viewModel: KvcxViewModel) {
    val context = LocalContext.current
    SettingsSection(title = "账号", subtitle = "凭证存于本机加密存储，token 只在内存；不进云备份。") {
        SettingItem(
            title = state.accountMobile.ifBlank { "已登录" },
            subtitle = "快趣出行账号",
        )
        // 用车在快趣出行页（两档共用同一个页面，2026-09-29 结构重构）
        OutlinedButton(
            onClick = { SubpageActivity.start(context, SubpageScreen.RIDE) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("打开快趣出行")
        }
        OutlinedButton(
            onClick = { viewModel.logout() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("退出登录")
        }
    }
}

// ── 未登录：表单 ──

@Composable
private fun LoginSection(state: KvcxUiState, viewModel: KvcxViewModel) {
    var showPassword by remember { mutableStateOf(false) }
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
                IconButton(onClick = { showPassword = !showPassword }) {
                    Text(
                        if (showPassword) "隐藏" else "显示",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { viewModel.login() },
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
                "密码只保存在本机（加密存储），请妥善保管。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}
