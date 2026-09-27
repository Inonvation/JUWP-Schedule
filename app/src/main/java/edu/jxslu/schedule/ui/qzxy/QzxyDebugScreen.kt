package edu.jxslu.schedule.ui.qzxy

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.qzxy.QzxyBluetoothScanner
import edu.jxslu.schedule.data.qzxy.QzxyBoundDevice
import edu.jxslu.schedule.data.qzxy.QzxyScannedDevice
import edu.jxslu.schedule.domain.QzxyProtocol
import edu.jxslu.schedule.ui.common.AppNoticeVisuals
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.NoticeTone
import edu.jxslu.schedule.ui.common.SettingSwitchRow
import edu.jxslu.schedule.ui.common.SettingsSection
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01

/**
 * 趣智校园「诊断与调试」页（DESIGN §3.18）。
 *
 * 这一页就是原来挤在开热水页里的调试块，内容一行没少：设备现场（服务端说法与蓝牙
 * 现场并排）、设备服务表、试签名、试清除、调试日志、复制诊断。开阀链路 2026-09-27
 * 真机跑通之后它们只在协议对不上时才有用，日常用水不必看到。
 *
 * 独立窗口承载（`SubpageScreen.QZXY_DEBUG`）：页面自己不选设备，用上一页带过来的
 * 地址（[focusAddress]），没有就用上次用过的那台——诊断的对象永远是「当前这台」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QzxyDebugScreen(
    focusAddress: String? = null,
    onBack: () -> Unit = {},
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

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is QzxyEvent.Notice -> snackbar.showSnackbar(
                    AppNoticeVisuals(event.text, tone = event.tone),
                )
            }
        }
    }

    // 从开热水页带过来的设备优先；没有就用上次用过的那台。
    // 诊断页不做扫描：要换设备回开热水页换，这一页只负责看现场。
    LaunchedEffect(focusAddress) {
        if (viewModel.uiState.value.selected != null) return@LaunchedEffect
        val current = viewModel.uiState.value
        val target = focusAddress?.let { address ->
            current.boundDevices.firstOrNull { it.address.equals(address, ignoreCase = true) }
                ?: QzxyBoundDevice(address, address)
        } ?: viewModel.lastUsedDevice()
        target?.let { viewModel.selectBoundDevice(it) }
    }

    // 协议排查要精确字节，截图会丢字符，所以给一个整段复制的出口
    val copyDiagnostic: () -> Unit = {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(
            ClipData.newPlainText("趣智校园诊断", viewModel.diagnosticText()),
        )
        scope.launch {
            snackbar.showSnackbar(
                AppNoticeVisuals("诊断信息已复制，可直接粘贴发出来", tone = NoticeTone.Success),
            )
        }
    }

    /**
     * 复制会话串，供另一台设备在开热水页用「会话串登录」。
     *
     * 入口只放在这一页：这串东西等于账号通行证，摆在日常界面上不合适。
     * 提示语也按凭据的口径写，别让用户顺手发到群里。
     */
    val copySession: () -> Unit = {
        val text = viewModel.exportSession()
        if (text == null) {
            scope.launch {
                snackbar.showSnackbar(
                    AppNoticeVisuals("当前没有登录会话", tone = NoticeTone.Warning),
                )
            }
        } else {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText("趣智校园会话", text))
            scope.launch {
                snackbar.showSnackbar(
                    AppNoticeVisuals(
                        "会话串已复制。它等于账号通行证，只粘到本应用的「会话串登录」，别发到别处",
                        tone = NoticeTone.Warning,
                    ),
                )
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("诊断与调试") },
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
            Text(
                text = "协议对不上、开阀失败时才会用到这一页，日常用水不用进来。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            val selected = state.selected
            if (selected == null) {
                SettingsSection(
                    title = "当前设备",
                    subtitle = "还没有选中设备。回开热水页选一台再进来，或用上次用过的那台。",
                ) {}
            } else {
                SettingsSection(
                    title = "当前设备",
                    subtitle = "左边是服务端登记的信息，右边是蓝牙现场读到的",
                ) {
                    Text(
                        text = selected.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = selected.address,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    )
                    DeviceFieldReport(state, selected)
                }
            }
            GattTableSection(state)
            SettingsSection(
                title = "工具",
                subtitle = "试签名与试清除会真实向设备与服务端发指令。签名错误不会下单，可以放心试。",
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { viewModel.probeServices() },
                        enabled = state.selected != null,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("探测服务")
                    }
                    OutlinedButton(
                        onClick = { viewModel.probeSignatures() },
                        enabled = state.selected != null,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("试签名")
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { viewModel.probeClearCommands() },
                        enabled = state.selected != null,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("试清除命令")
                    }
                    OutlinedButton(
                        onClick = { viewModel.clearDeviceRecord() },
                        enabled = state.selected != null,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("清除设备记录")
                    }
                }
                OutlinedButton(
                    onClick = copyDiagnostic,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    Text("复制诊断")
                }
                OutlinedButton(
                    onClick = copySession,
                    enabled = state.loggedIn,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    Text("复制会话串（等于账号通行证，别外发）")
                }
                TrialList(
                    title = "清除命令试错结果",
                    trials = state.clearTrials,
                )
                state.clearResult?.let { result ->
                    Text(
                        text = result,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                TrialList(
                    title = "签名试错结果（服务端对签名错误的拒绝不会下单，可放心试）",
                    trials = state.signatureTrials,
                )
            }
            ConsumptionSection(state)
            DebugLogSection(state, viewModel)
        }
    }
}

/**
 * 设备现场：左边是蓝牙读到的、右边是服务端说的，两边都摆出来。
 * 数据对不上时能一眼看出断在哪一侧。
 */
@Composable
private fun DeviceFieldReport(state: QzxyUiState, selected: QzxyScannedDevice) {
    val info = state.serverDevice
    val deviceState = state.deviceState
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "服务端",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
            InfoRow("设备名", info?.deviceName ?: "—")
            InfoRow("登记 MAC", info?.macAddress ?: "—")
            InfoRow(
                "位置",
                listOfNotNull(info?.buildingName, info?.floorName, info?.roomName)
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                    .ifBlank { "—" },
            )
            InfoRow("在线", info?.onlineStatusId ?: "—")
            InfoRow("通信", info?.communicationTypeId ?: "—")
            InfoRow("迁移", info?.isMigrated ?: "—")
            InfoRow("类型", info?.bigTypeName ?: "—")
        }
        Column(Modifier.weight(1f)) {
            Text(
                "蓝牙现场",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
            InfoRow("状态", deviceState?.let { QzxyProtocol.stateText(it.deviceState) } ?: "尚未读取")
            InfoRow("随机数", deviceState?.randomNumber ?: "—")
            InfoRow("协议版本", deviceState?.protocolType ?: "—")
            InfoRow("地址", selected.address)
        }
    }
    deviceState?.rawHex?.let { raw ->
        Text(
            text = "状态包数据体：$raw",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            modifier = Modifier.padding(top = 6.dp),
        )
    }
    state.lastDownDataHex?.let { down ->
        Text(
            text = "downData（${down.length / 2} 字节）：$down",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** 结算链路现场：读回的消费记录、服务端签发的清除凭据、解密明文。 */
@Composable
private fun ConsumptionSection(state: QzxyUiState) {
    val hasContent = state.consumeRaw != null ||
        state.consumeSummary != null ||
        state.clDataRaw != null
    if (!hasContent) return
    SettingsSection(
        title = "结算现场",
        subtitle = "结束用水时读回的消费数据与服务端签发的清除凭据",
    ) {
        state.consumeSummary?.let { summary ->
            Text(summary, style = MaterialTheme.typography.bodySmall)
        }
        state.consumeRaw?.let { raw ->
            Text(
                text = "消费数据：$raw",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        state.consumeFrameRaw?.let { raw ->
            Text(
                text = "完整回包：$raw",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        state.clDataRaw?.let { raw ->
            Text(
                text = "clData：$raw",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        state.clDataPlainText?.let { text ->
            Text(
                text = "clData 明文：$text",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * 设备服务表：连接后把设备声明的服务与特征值原样列出来。
 *
 * 开阀要往「写」那一项发数据、回包看「通知」那一项——UUID 是设备给的，不是我们猜的，
 * 所以这份表既是排查依据，也是下一步确定写入口的唯一来源。
 */
@Composable
private fun GattTableSection(state: QzxyUiState) {
    if (state.gattTable.isEmpty()) return
    SettingsSection(
        title = "设备服务表",
        subtitle = "来自设备自身的声明。开阀要往「写」的特征值发数据，回包看「通知」那一项。",
    ) {
        state.gattTable.forEach { entry ->
            Column(Modifier.padding(top = 8.dp)) {
                Text(
                    entry.characteristicUuid,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
                entry.role?.let { role ->
                    Text(
                        role,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    "属性：${entry.propertyText}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                Text(
                    "服务：${entry.serviceUuid}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }
        }
    }
}

@Composable
private fun TrialList(title: String, trials: List<SignatureTrial>) {
    if (trials.isEmpty()) return
    Text(
        text = title,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        modifier = Modifier.padding(top = 10.dp),
    )
    trials.forEach { trial ->
        Column(Modifier.padding(top = 6.dp)) {
            Text(
                trial.label,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (trial.ok) FontWeight.SemiBold else FontWeight.Normal,
                color = if (trial.ok) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                },
            )
            Text(
                trial.result,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
        }
    }
}

/**
 * 调试日志（DESIGN §4.30）：**默认关**，日志含设备地址与接口原文。
 * 只留最近若干条，够翻一次现场即可。
 */
@Composable
private fun DebugLogSection(state: QzxyUiState, viewModel: QzxyViewModel) {
    SettingsSection(title = "调试日志") {
        SettingSwitchRow(
            title = "记录调试日志",
            subtitle = "默认关。开启后每次开阀与结算都会留下诊断信息，复制完记得关掉",
            checked = state.debugLogEnabled,
            onCheckedChange = viewModel::setDebugLogEnabled,
        )
        if (state.debugLog.isEmpty()) return@SettingsSection
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "最近 ${state.debugLog.takeLast(20).size} 条",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { viewModel.clearDebugLog() }) { Text("清空") }
        }
        state.debugLog.takeLast(20).forEach { line ->
            Text(
                line,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.width(64.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
    }
}
