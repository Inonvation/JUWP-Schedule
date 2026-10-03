package edu.jxslu.schedule.ui.qzxy

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import edu.jxslu.schedule.domain.QzxyWatering
import edu.jxslu.schedule.domain.QzxyWateringFormat
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import edu.jxslu.schedule.ui.common.rememberSheetDismisser

/**
 * 今日页点余额弹出的趣智校园开水面板（DESIGN §3.18）。
 *
 * 与胖乖的开水面板同构：余额在顶、状态在中、按钮在下，跟页面共享同一个
 * [QzxyViewModel]（今日页那份挂在 MainActivity 上），面板里发起的开阀与结算
 * 在页面上状态一致。关闭面板不取消流程——卡片副行会继续显示「用水中 12:35」。
 *
 * 为什么入口是余额而不是卡片右侧另一个按钮：半行宽度放不下第二个动作，
 * 而余额本来就是这张卡最想被点的地方（与胖乖生活卡同口径）。
 *
 * 2026-10-02 手感收口（DESIGN §3.18）：关面板走退场动画（此前直接置
 * `showQzxyEntrySheet = false`，弹层从组合里被瞬间抽掉，"啪"地消失）；状态区换成
 * [AnimatedContent]，待机一个按钮到用水中六行的高度差跟着动画走；「用水中」加一枚
 * 呼吸的圆点徽标；开阀与结算成功各给一次触感；失败态补齐「清除设备记录」与「详情」，
 * 与趣智校园页的失败卡同一口径。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QzxyEntrySheet(
    state: QzxyUiState,
    /** 登录态取自仓库那条流，不由 [state] 转述：会话登录可能发生在别的窗口。 */
    loggedIn: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onAbandon: () -> Unit,
    /** 清设备上那条没结算的消费记录（与趣智校园页失败卡的次按钮同一个动作）。 */
    onClearDeviceRecord: () -> Unit,
    onDismissSettlement: () -> Unit,
    onOpenPage: () -> Unit,
    /** 失败详情里的「去诊断页」。参数是当前设备地址，诊断页据此选中同一台。 */
    onOpenDiagnostics: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val haptics = rememberAppHaptics()
    // 关弹层一律「先播退场动画、再落状态」（ui-common.md）：面板里没有输入框，
    // 走 rememberSheetDismisser。把它交给 dismiss(after)，三种出口
    // （去登录 / 完成结算 / 打开页面）在动画播完之前都不改 show 状态
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val dismiss = rememberSheetDismisser(sheetState, onDismiss)
    var failureDetail by remember { mutableStateOf<QzxyFlowState.Failed?>(null) }

    // 开阀成功（用水中出现）与结算完成各震一次。只在面板打开之后「从无到有」的那一帧触发：
    // 用水中点卡片进来只是看一眼，那时不该震
    var wasWatering by remember { mutableStateOf(state.watering != null) }
    var hadSettlement by remember { mutableStateOf(state.lastSettlement != null) }
    LaunchedEffect(state.watering != null) {
        val now = state.watering != null
        if (now && !wasWatering) haptics.success()
        wasWatering = now
    }
    LaunchedEffect(state.lastSettlement != null) {
        val now = state.lastSettlement != null
        if (now && !hadSettlement) haptics.success()
        hadSettlement = now
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "趣智校园",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Column {
                // 余额从「读取中」换成真值、或用完水后回填时淡入淡出，别跳一下
                val balanceText = when {
                    !loggedIn -> "还没登录"
                    state.balance != null -> "¥${state.balance?.text}"
                    else -> "余额读取中…"
                }
                AnimatedContent(
                    targetState = balanceText,
                    transitionSpec = {
                        fadeIn(animationSpec = tween(180)) togetherWith
                            fadeOut(animationSpec = tween(120))
                    },
                    label = "qzxy-sheet-balance",
                ) { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    text = if (loggedIn) {
                        state.schoolName.ifBlank { "水控账户" }
                    } else {
                        "洗澡开热水要先用趣智校园账号登录"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = onSurface.copy(alpha = 0.55f),
                )
            }
            if (loggedIn) {
                Text(
                    text = deviceLabelOf(state),
                    style = MaterialTheme.typography.bodyMedium,
                    color = onSurface.copy(alpha = 0.8f),
                )
            }

            // 待机态一行、用水中六行，高度差一百多 dp。给个下限兜住最短的几个形态，
            // 再让内容淡入淡出、高度平滑过渡（AnimatedContent 自带尺寸动画）——
            // 之前是 when 硬切，点「开始用水」弹窗会往上/下顶一截
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 112.dp),
            ) {
                AnimatedContent(
                    targetState = sheetContentOf(state, loggedIn),
                    transitionSpec = {
                        fadeIn(animationSpec = tween(200)) togetherWith
                            fadeOut(animationSpec = tween(120))
                    },
                    label = "qzxy-sheet-status",
                ) { content ->
                    // 每个形态都是好几行，必须自己竖着排：AnimatedContent 的内容槽是 Box，
                    // 多个子元素会直接叠在一起
                    Column(Modifier.fillMaxWidth()) {
                        when (content) {
                            QzxySheetContent.LoggedOut -> Button(
                                onClick = {
                                    haptics.tap()
                                    dismiss { onOpenPage() }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                            ) {
                                Text("去登录")
                            }

                            // 过渡期间旧内容还在，读最新的 flow 只影响文案，不影响类型安全
                            QzxySheetContent.Working -> WorkingBlock(
                                (state.flow as? QzxyFlowState.Working)?.step ?: "正在处理",
                            )

                            is QzxySheetContent.Settlement -> SettlementBlock(
                                settlement = content.settlement,
                                balanceText = state.balance?.text,
                                onDone = {
                                    haptics.tap()
                                    // 先关面板再清结算结果：清在前会让面板在滑走的路上
                                    // 变成待机态，白闪一下
                                    dismiss { onDismissSettlement() }
                                },
                            )

                            is QzxySheetContent.Watering -> WateringBlock(
                                watering = content.watering,
                                failure = state.flow as? QzxyFlowState.Failed,
                                onStop = {
                                    haptics.tap()
                                    onStop()
                                },
                                onAbandon = {
                                    haptics.tap()
                                    onAbandon()
                                },
                            )

                            is QzxySheetContent.Failed -> FailureBlock(
                                failure = content.failure,
                                canRetry = state.hasUsableDevice,
                                canClear = state.selected != null,
                                onRetry = {
                                    haptics.tap()
                                    onStart()
                                },
                                onClear = {
                                    haptics.tap()
                                    onClearDeviceRecord()
                                },
                                onDetail = { failureDetail = content.failure },
                            )

                            QzxySheetContent.Idle -> IdleBlock(
                                state = state,
                                onStart = {
                                    haptics.tap()
                                    onStart()
                                },
                            )
                        }
                    }
                }
            }

            TextButton(
                onClick = {
                    haptics.tap()
                    dismiss { onOpenPage() }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("打开趣智校园页")
            }
        }
    }

    failureDetail?.let { failure ->
        // 与趣智校园页同一个实现（QzxyScreen 里那个，同一个包）
        FailedDetailDialog(
            failure = failure,
            onOpenDiagnostics = {
                val address = state.selected?.address ?: state.lastUsedDevice?.address
                dismiss { onOpenDiagnostics(address) }
            },
            onDismiss = { failureDetail = null },
        )
    }
}
/** 设备行文案：用水中报那台，否则报选中的 / 上次用过的，都没有就直说。 */
private fun deviceLabelOf(state: QzxyUiState): String =
    state.watering?.deviceName
        ?: state.selected?.let { device ->
            state.deviceInfos[device.addressKey]?.deviceName?.takeIf { it.isNotBlank() }
                ?: device.name
        }
        ?: state.lastUsedDevice?.name
        ?: "还没选设备，去页面里挑一台"

/**
 * 面板的展示形态。
 *
 * 带数据的形态把数据装进来（而不是在内容里读最新的 `state`）：AnimatedContent
 * 过渡期间旧内容仍在合成，那时 `state` 已经换成新状态，`as Watering` 这类强转会直接崩。
 * Working 只带标记、步骤文字现读——步骤每变一次就触发一次淡入淡出反而更闪。
 */
private sealed interface QzxySheetContent {
    data object LoggedOut : QzxySheetContent
    data object Working : QzxySheetContent
    data class Settlement(val settlement: QzxySettlement) : QzxySheetContent
    data class Watering(val watering: QzxyWatering) : QzxySheetContent
    data class Failed(val failure: QzxyFlowState.Failed) : QzxySheetContent
    data object Idle : QzxySheetContent
}

/** 分支顺序即优先级，与趣智校园页的状态卡一致（见 `QzxyScreen` 的 `statusContentOf`）。 */
private fun sheetContentOf(state: QzxyUiState, loggedIn: Boolean): QzxySheetContent {
    val flow = state.flow
    val settlement = state.lastSettlement
    val watering = state.watering
    return when {
        !loggedIn -> QzxySheetContent.LoggedOut
        flow is QzxyFlowState.Working -> QzxySheetContent.Working
        settlement != null -> QzxySheetContent.Settlement(settlement)
        watering != null -> QzxySheetContent.Watering(watering)
        flow is QzxyFlowState.Failed -> QzxySheetContent.Failed(flow)
        else -> QzxySheetContent.Idle
    }
}

@Composable
private fun WorkingBlock(step: String) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Column(Modifier.padding(top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                text = step,
                style = MaterialTheme.typography.bodyMedium,
                color = onSurface.copy(alpha = 0.75f),
            )
        }
        // 不定态进度条：这几秒里唯一在动的东西，比一个转圈更能压住等待
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        )
        Text(
            text = "连接热水器并下单，通常几秒",
            style = MaterialTheme.typography.bodySmall,
            color = onSurface.copy(alpha = 0.5f),
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/**
 * 「用水中」徽标：左侧一枚呼吸的圆点。
 *
 * 面板里唯一的常驻动画，只改透明度、不动尺寸（走 `graphicsLayer` 的 lambda 形式，
 * 动画值在绘制阶段读，不触发重组），也不是骨架流光那套（那个在
 * `ui/common/Skeleton.kt`）。呼吸给的是「水还在流」的现场感。
 */
@Composable
private fun WateringBadge() {
    val scheme = MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition(label = "qzxy-watering")
    val dotAlpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "qzxy-watering-dot",
    )
    Row(
        modifier = Modifier
            .background(scheme.primaryContainer, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .graphicsLayer { alpha = dotAlpha }
                .background(scheme.primary, CircleShape),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "用水中",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = scheme.onPrimaryContainer,
        )
    }
}
@Composable
private fun WateringBlock(
    watering: QzxyWatering,
    failure: QzxyFlowState.Failed?,
    onStop: () -> Unit,
    onAbandon: () -> Unit,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    if (isQzxyWateringExpired(watering.startedAtMillis)) {
        // 过期的那一帧（store 清理在 ViewModel 侧异步完成）显示待处理文案，
        // 不显示进行中的计时
        Text(
            text = "上次用水已超过 1 小时，点「结束用水」核对设备记录",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        Button(
            onClick = onStop,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .height(48.dp),
        ) {
            Text("结束用水")
        }
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WateringBadge()
        Spacer(Modifier.weight(1f))
        Text(
            text = "开始于 ${QzxyWateringFormat.clockText(watering.startedAtMillis)}",
            style = MaterialTheme.typography.bodySmall,
            color = onSurface.copy(alpha = 0.55f),
        )
    }
    Text(
        text = rememberQzxyWateringClock(watering.startedAtMillis),
        // 等宽数字，避免每秒刷新时整行抽动
        style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"),
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 6.dp),
    )
    Text(
        text = watering.preDeductMilli?.let {
            "服务端预扣 ${QzxyWateringFormat.money(it)}，按实际用量结算"
        } ?: "结束后按实际用量结算",
        style = MaterialTheme.typography.bodySmall,
        color = onSurface.copy(alpha = 0.6f),
        modifier = Modifier.padding(top = 2.dp),
    )
    if (failure != null) {
        Text(
            text = failure.reason,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 10.dp),
        )
        failure.detail?.let { detail ->
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
        onClick = onStop,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .height(48.dp),
    ) {
        Text(if (failure != null) "重试结束用水" else "结束用水")
    }
    Text(
        text = "结算要走蓝牙，点之前先站到热水器旁边",
        style = MaterialTheme.typography.bodySmall,
        color = onSurface.copy(alpha = 0.55f),
        modifier = Modifier.padding(top = 8.dp),
    )
    TextButton(onClick = onAbandon) {
        Text("水已经停了，标记为已结束")
    }
}

@Composable
private fun SettlementBlock(
    settlement: QzxySettlement,
    balanceText: String?,
    onDone: () -> Unit,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Text(
        text = "已结束用水",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 4.dp),
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
        balanceText?.let { add("余额 ¥$it") }
    }
    if (parts.isNotEmpty()) {
        Text(
            text = parts.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = 2.dp),
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
        onClick = onDone,
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
    canRetry: Boolean,
    canClear: Boolean,
    onRetry: () -> Unit,
    onClear: () -> Unit,
    onDetail: () -> Unit,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Text(
        text = failure.reason,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(top = 4.dp),
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
        onClick = onRetry,
        enabled = canRetry,
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
            onClick = onClear,
            enabled = canClear,
            modifier = Modifier
                .weight(1f)
                .height(42.dp),
        ) {
            Text("清除设备记录")
        }
        OutlinedButton(
            onClick = onDetail,
            modifier = Modifier
                .weight(1f)
                .height(42.dp),
        ) {
            Text("详情")
        }
    }
}

@Composable
private fun IdleBlock(state: QzxyUiState, onStart: () -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Button(
        onClick = onStart,
        // 面板没经过「选设备」这一步：有上次用的那台或绑过设备就算就绪，
        // 真正开阀前 ViewModel 会把它选上
        enabled = state.hasUsableDevice,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
    ) {
        Text("开始用水")
    }
    Text(
        text = if (state.hasUsableDevice) {
            "开始前站到热水器旁边，连接要几秒"
        } else {
            "还没选设备，去趣智校园页挑一台"
        },
        style = MaterialTheme.typography.bodySmall,
        color = onSurface.copy(alpha = 0.55f),
        modifier = Modifier.padding(top = 8.dp),
    )
}