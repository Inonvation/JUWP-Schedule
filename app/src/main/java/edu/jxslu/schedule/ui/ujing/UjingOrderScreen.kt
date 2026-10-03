package edu.jxslu.schedule.ui.ujing

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.ujing.UjingDraftStore
import edu.jxslu.schedule.data.ujing.UjingProgramData
import edu.jxslu.schedule.data.ujing.UjingScanResult
import edu.jxslu.schedule.data.ujing.UjingWashModel
import edu.jxslu.schedule.domain.UjingState
import edu.jxslu.schedule.ui.common.AppCard
import edu.jxslu.schedule.ui.common.SettingsSection

/**
 * U净 下单页（DESIGN §3.23，2026-10-02 起）：扫码成功后的独立窗口。
 *
 * 设备信息 → **洗衣档位单选**（AppCard 高亮态即选中态）→ 水温 / 投放加购 chips →
 * 贴底动作条（所选摘要 + 「下单」）→ 确认弹层（写操作二次确认）→ 交回选择并关窗：
 * 真正的下单与支付宝拉起仍由下层主页的 [UjingViewModel] 执行（写操作单点发起，
 * 窗口切换不换执行者），交接通道是 [UjingDraftStore]。
 *
 * 草稿是进程内状态（[UjingDraftStore] 故意不落盘）：进程被回收后重进本页拿不到草稿，
 * 给一句话引导返回，不渲染半屏空壳。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UjingOrderScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val draftStore = remember { Graph.ujingDraft(context) }
    val draft by draftStore.draft.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("确认下单") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        val current = draft
        if (current == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "没有待下单的设备，请回上一页重新扫码。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onBack) { Text("返回") }
            }
            return@Scaffold
        }
        OrderReadyContent(
            draft = current,
            draftStore = draftStore,
            onBack = onBack,
            modifier = Modifier.padding(padding),
        )
    }
}

@Composable
private fun OrderReadyContent(
    draft: UjingDraftStore.Draft,
    draftStore: UjingDraftStore,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scan = draft.scan
    val program = draft.program
    // 可选档位：服务端动态下发（价格单位分、时长单位分钟），hide 的不展示
    val models = remember(program) { program.deviceWashModel.filterNot { it.hide } }
    val orderable = UjingState.scanBadge(scan.createOrderEnabled, scan.status, scan.reason) ==
        UjingState.ScanBadge.Free && scan.createOrderEnabled

    // ── 选择状态（窗口级，进程回收即丢——与草稿同一生命周期口径） ──
    var selectedModelId by rememberSaveable { mutableIntStateOf(models.firstOrNull()?.workModelId ?: 0) }
    // 水温默认常温（机型开放水温且非烘干机才给选项）；-1 = 本机无水温选项
    var temperatureId by rememberSaveable {
        mutableIntStateOf(
            if (program.isWashTemperatureEnable && program.type != 2) TEMPERATURE_DEFAULT_ID else -1,
        )
    }
    // 投放加购默认按机型强制投放声明（UjingState.addonDefaults 单一来源），可改
    val addonDefaults = remember(program) {
        UjingState.addonDefaults(program.type, program.isForceDetergent, program.isForceDisinfectant)
    }
    var addDetergent by rememberSaveable { mutableStateOf(addonDefaults.first) }
    var addDisinfectant by rememberSaveable { mutableStateOf(addonDefaults.second) }
    var confirmOpen by rememberSaveable { mutableStateOf(false) }

    val selected = models.firstOrNull { it.workModelId == selectedModelId }

    // 单层结构：滚动内容占满剩余高度，动作条贴底（navigationBarsPadding 自理）
    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DeviceCard(scan, program)
            SettingsSection(
                title = "洗衣档位",
                subtitle = if (orderable) "点一下选中，选好再下单" else "设备使用中 / 不可下单，仅展示模式与价格",
            ) {
                if (models.isEmpty()) {
                    Text(
                        "该设备暂未返回可用模式",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        models.forEach { model ->
                            ModelCard(
                                model = model,
                                selected = model.workModelId == selectedModelId,
                                onSelect = { selectedModelId = model.workModelId },
                            )
                        }
                    }
                }
            }
            PreferenceSection(
                program = program,
                temperatureId = temperatureId,
                onTemperature = { temperatureId = it },
                addDetergent = addDetergent,
                onDetergent = { addDetergent = !addDetergent },
                addDisinfectant = addDisinfectant,
                onDisinfectant = { addDisinfectant = !addDisinfectant },
            )
            Text(
                "下单后机器将为你保留 2 分钟，请在 2 分钟内完成支付；超时订单将自动取消。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        OrderActionBar(
            selected = selected,
            temperatureId = temperatureId,
            addDetergent = addDetergent,
            addDisinfectant = addDisinfectant,
            orderable = orderable,
            onOrder = { confirmOpen = true },
        )
    }

    if (confirmOpen && selected != null) {
        OrderConfirmDialog(
            program = program,
            model = selected,
            temperatureId = temperatureId.takeIf { it > 0 },
            addDetergent = addDetergent,
            addDisinfectant = addDisinfectant,
            onConfirm = {
                confirmOpen = false
                draftStore.submit(
                    UjingDraftStore.OrderSelection(
                        model = selected,
                        temperatureId = temperatureId.takeIf { it > 0 },
                        addDetergent = addDetergent,
                        addDisinfectant = addDisinfectant,
                    ),
                )
                // 窗口滑出回主页；主页取走选择后立刻执行下单 + 拉起支付宝
                onBack()
            },
            onDismiss = { confirmOpen = false },
        )
    }
}

/** 水温默认档：常温（协议枚举 1，与 [UjingState.temperatureOptions] 同源）。 */
private const val TEMPERATURE_DEFAULT_ID = 1

/** 设备信息卡：机型 + 状态徽标 + 机号 + 通信模块（点验时看「通信模块」行）。 */
@Composable
private fun DeviceCard(scan: UjingScanResult, program: UjingProgramData) {
    SettingsSection(title = "设备", subtitle = program.storeName.ifBlank { null }) {
        AppCard {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    program.deviceTypeName.ifBlank { "洗衣机" },
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                ScanBadgePill(UjingState.scanBadge(scan.createOrderEnabled, scan.status, scan.reason))
            }
            program.deviceNo?.takeIf { it.isNotBlank() }?.let { deviceNo ->
                Text(
                    "机号 $deviceNo",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }
            Text(
                "通信模块：${UjingState.moduleTypeLabel(scan.moduleType)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
        }
    }
}

/** 一档洗衣模式：AppCard 高亮态 = 选中态（描边、底色、触感都由 AppCard 统一给）。 */
@Composable
private fun ModelCard(
    model: UjingWashModel,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    AppCard(
        onClick = onSelect,
        onClickLabel = "选择${model.workModelName.ifBlank { "该模式" }}",
        highlighted = selected,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    model.workModelName.ifBlank { "模式 ${model.workModelId}" },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Text(
                    buildString {
                        append("¥").append(UjingState.fen2yuan(model.basePrice))
                        if (model.time > 0) append(" · ").append(model.time).append(" 分钟")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }
            // 勾选标记随选中态淡入淡出 + 原位缩放，选中哪档一眼可辨
            AnimatedVisibility(
                visible = selected,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "已选中",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** 水温 / 投放加购（仅按机型能力出现；烘干机没有投放概念）。 */
@Composable
private fun PreferenceSection(
    program: UjingProgramData,
    temperatureId: Int,
    onTemperature: (Int) -> Unit,
    addDetergent: Boolean,
    onDetergent: () -> Unit,
    addDisinfectant: Boolean,
    onDisinfectant: () -> Unit,
) {
    val showTemperature = program.isWashTemperatureEnable && program.type != 2
    val showAddons = program.type != 2
    if (!showTemperature && !showAddons) return
    SettingsSection(title = "选项", subtitle = null) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (showTemperature) {
                ChipRow("水温") {
                    UjingState.temperatureOptions.forEach { (id, label) ->
                        FilterChip(
                            selected = temperatureId == id,
                            onClick = { onTemperature(id) },
                            label = { Text(label) },
                        )
                    }
                }
            }
            if (showAddons) {
                ChipRow("投放") {
                    FilterChip(
                        selected = addDetergent,
                        onClick = onDetergent,
                        label = { Text("洗衣液") },
                    )
                    FilterChip(
                        selected = addDisinfectant,
                        onClick = onDisinfectant,
                        label = { Text("消毒液") },
                    )
                }
                Text(
                    "投放按标准档加购，费用以支付宝实付金额为准。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }
        }
    }
}

@Composable
private fun ChipRow(label: String, content: @Composable RowScope.() -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        content()
    }
}

/** 贴底动作条：所选档位摘要 + 「下单」主按钮（不可下单 / 未选档时置灰并说明原因）。 */
@Composable
private fun OrderActionBar(
    selected: UjingWashModel?,
    temperatureId: Int,
    addDetergent: Boolean,
    addDisinfectant: Boolean,
    orderable: Boolean,
    onOrder: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    selected?.workModelName?.ifBlank { "洗衣模式" } ?: "未选择洗衣档位",
                    style = MaterialTheme.typography.titleSmall,
                )
                if (selected != null) {
                    Text(
                        buildString {
                            append("¥").append(UjingState.fen2yuan(selected.basePrice))
                            if (selected.time > 0) append(" · ").append(selected.time).append(" 分钟")
                            if (temperatureId > 0) {
                                append(" · ").append(UjingState.temperatureLabel(temperatureId))
                            }
                            if (addDetergent) append(" · 洗衣液")
                            if (addDisinfectant) append(" · 消毒液")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    )
                }
            }
            Button(onClick = onOrder, enabled = orderable && selected != null) {
                Text("下单")
            }
        }
    }
}

/** 扫码状态徽标（Free / InUse / Fault / Offline）。 */
@Composable
internal fun ScanBadgePill(badge: UjingState.ScanBadge) {
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

/** 下单确认弹层（写操作二次确认；对齐快趣开锁 / 电费充值的确认口径）。 */
@Composable
private fun OrderConfirmDialog(
    program: UjingProgramData,
    model: UjingWashModel,
    temperatureId: Int?,
    addDetergent: Boolean,
    addDisinfectant: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
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
                temperatureId?.let { id ->
                    Text(
                        "水温：${UjingState.temperatureLabel(id)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (addDetergent || addDisinfectant) {
                    val addons = listOfNotNull(
                        "洗衣液".takeIf { addDetergent },
                        "消毒液".takeIf { addDisinfectant },
                    ).joinToString("、")
                    Text(
                        "投放：$addons（加购费用以支付宝实付金额为准）",
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
            TextButton(onClick = onConfirm) { Text("去支付") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("再想想") }
        },
    )
}
