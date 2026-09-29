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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
import edu.jxslu.schedule.domain.RideRecord
import edu.jxslu.schedule.domain.KqcxAuth
import edu.jxslu.schedule.domain.capabilities
import edu.jxslu.schedule.ui.common.AppNoticeVisuals
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.InlineNoticeRow
import edu.jxslu.schedule.ui.common.NoticeTone
import edu.jxslu.schedule.ui.common.SettingItem
import edu.jxslu.schedule.ui.common.SettingsSection
import edu.jxslu.schedule.ui.theme.semanticColors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 快趣出行页（DESIGN §4.32，2026-09-28；2026-09-29 起只属于「账号登录」使用方式）。
 *
 * 两态：
 * - 未登录：手机号 + 密码表单（UX 对齐胖乖 `WaterScreen.LoginSection`：失败保留输入、
 *   Snackbar 提示）；
 * - 已登录：账号掩码显示、骑行状态卡（进页自动查一次 + 手动刷新）、退出登录。
 *
 * 顶部常驻「使用方式」卡（与出码页**同一份** [UseModeSection]）：两处入口都能切换，
 * 口径只有一处。切到「微信小程序」方式后本页的账号 / 骑行状态 / 本机记录整片收起
 * （那是账号方式的能力），只留一句说明与切回去的开关。
 *
 * **免责边界**：本页只做账号与状态；开锁 / 临时锁车 / 还车在「快趣出行码」页
 * （`EbikeQrScreen`），且都经确认弹窗；支付与免押授权不做——页内常驻声明，
 * 不做成可关闭的一次性提示。
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
    // 能力矩阵（DESIGN §3.9）：本页整片内容属于 App 内用车那一档
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
                title = { Text("快趣出行") },
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
                    RideStatusSection(state, viewModel)
                    AccountSection(state, viewModel)
                } else {
                    LoginSection(state, viewModel)
                }
                // 本机记录与登录态无关：退出登录也照样看得到自己的骑行史
                RideHistorySection(records = records, onClear = viewModel::clearRecords)
            } else {
                // 小程序方式：本页的账号 / 骑行状态 / 本机记录整片收起（都是账号方式的能力）
                InlineNoticeRow(
                    message = "当前是「微信小程序」使用方式：本页的账号、骑行状态与本机记录都已收起，" +
                        "开车与还车在微信小程序里完成。切到「账号登录」后本页才有登录与骑行状态。",
                    tone = NoticeTone.Info,
                )
            }
            // 使用方式：与出码页共用同一份组件（含账号方式的风险提示）。
            // 摆位与出码页一致——**任务在前、设置在后**，两页都是「用」的部分先看到
            UseModeSection(
                mode = useMode,
                onSelect = { mode -> scope.launch { ebikePrefs.setEbikeUseMode(mode) } },
            )
            DisclaimerSection()
        }
    }
}

// ── 已登录：骑行状态 ──

@Composable
private fun RideStatusSection(state: KvcxUiState, viewModel: KvcxViewModel) {
    SettingsSection(
        title = "骑行状态",
        subtitle = "进页自动查一次；不自动轮询，需要最新状态点刷新。",
    ) {
        when {
            state.querying -> Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.height(20.dp))
                Spacer(Modifier.width(10.dp))
                Text("查询中…", style = MaterialTheme.typography.bodyMedium)
            }
            state.ride != null -> {
                val ride = state.ride
                val duration = KqcxAuth.formatRideDuration(ride?.totalDateSeconds)
                SettingItem(
                    title = buildString {
                        append("骑行中 · ${ride?.carNum.orEmpty()}")
                        if (duration != null) append(" · 已骑 $duration")
                    },
                    subtitle = buildString {
                        val lock = ride?.locked
                        if (lock == true) append("车辆已锁（可在「快趣出行码」页或地图页解锁继续骑 / 还车）")
                        else append(ride?.bluetoothName?.let { "蓝牙锁：$it" } ?: "蓝牙锁名未上报")
                        ride?.payMoneyCents?.takeIf { it > 0 }?.let {
                            append(" · 当前费用 ¥").append(it / 100.0)
                        }
                    },
                    onClick = { viewModel.refreshRide() },
                )
            }
            else -> SettingItem(
                title = "当前没有进行中的骑行",
                subtitle = "扫码用车后这里会显示车号与蓝牙锁名",
                onClick = { viewModel.refreshRide() },
            )
        }
        OutlinedButton(
            onClick = { viewModel.refreshRide() },
            enabled = !state.querying && state.loggedIn,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("刷新骑行状态")
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

// ── 已登录：账号 ──

@Composable
private fun AccountSection(state: KvcxUiState, viewModel: KvcxViewModel) {
    val context = LocalContext.current
    SettingsSection(title = "账号", subtitle = "凭证存于本机加密存储，token 只在内存；不进云备份。") {
        SettingItem(
            title = state.accountMobile.ifBlank { "已登录" },
            subtitle = "快趣出行账号",
        )
        // 用车在出码页（DESIGN §4.32）：账号与用车互相可达，入口不藏在两个深处（用户口径 2026-09-28）
        OutlinedButton(
            onClick = { SubpageActivity.start(context, SubpageScreen.EBIKE) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("去用车（快趣出行码）")
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
    }
}

// ── 免责声明 ──

@Composable
private fun DisclaimerSection() {
    SettingsSection(
        title = "边界说明",
        subtitle = "本页为快趣出行的非官方账号与状态页（§4.32）。",
    ) {
        Text(
            "开锁 / 锁车 / 还车在「快趣出行码」页逐次确认后执行，计费与善后以快趣为准；" +
                "不提供支付与免押授权。接口口径来自快趣公开客户端，若官方调整导致失效属预期内。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(vertical = 4.dp),
        )
    }
}
