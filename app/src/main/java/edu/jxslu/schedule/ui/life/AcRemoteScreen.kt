package edu.jxslu.schedule.ui.life

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.jxslu.schedule.domain.MideaR05d
import edu.jxslu.schedule.ui.common.AppCard
import edu.jxslu.schedule.ui.common.AppCardDivider
import edu.jxslu.schedule.ui.common.SettingsNote
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.MinusSign
import me.rerere.hugeicons.stroke.PlusSign
import me.rerere.hugeicons.stroke.Power

/**
 * 侧栏「空调遥控」入口（DESIGN §3.31 / §4.48）。
 *
 * 页面结构（自上而下，全走描边卡）：主控卡（电源圆钮 · 状态行 · 温度 ±）→
 * 模式 / 风速卡 → 附加功能卡（摆风 / 强劲 / ECO 切换芯片）→ 底部说明。
 * 只走 R05D；温度步进支持长按连发，送风档温度置灰。关机时附加功能不可用
 * （原装遥控的摆风 / 强劲键在关机时不响应）。无红外发射器的机型进页只给提示。
 *
 * 每次操作按完整状态发一帧，状态本机持久化；扩展键见 [MideaR05d]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AcRemoteScreen(
    onBack: () -> Unit,
    viewModel: AcRemoteViewModel = viewModel(
        factory = AcRemoteViewModel.Factory(LocalContext.current),
    ),
) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val transmitError by viewModel.transmitError.collectAsStateWithLifecycle()
    val state = snapshot.state

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("空调遥控") },
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
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!viewModel.irSupported) {
                Text(
                    text = "本机没有红外发射器，无法发送遥控信号。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 48.dp),
                )
                return@Column
            }

            // 主控卡：电源 + 状态 + 温度
            AppCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp)) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    PowerButton(
                        on = state.power,
                        onTap = viewModel::togglePower,
                        onHold = { viewModel.repeatCurrent() },
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = if (state.power) {
                            "${modeLabel(state.mode)} · ${state.tempC}°C · 风速${fanLabel(state.fan)}"
                        } else {
                            "已关机 · 点按上方按钮开机"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(18.dp))
                    TemperatureRow(
                        tempC = state.tempC,
                        enabled = state.mode != MideaR05d.Mode.Fan,
                        onDelta = viewModel::bumpTemp,
                    )
                }
            }

            // 发射失败不再静默：无发射器 / 系统拒发（含序列超长被 HAL 丢弃）都走这一行
            transmitError?.let { reason ->
                Text(
                    text = "发射失败：$reason",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // 模式 / 风速：一张卡两组，中间细分隔线
            AppCard {
                Column {
                    ControlLabel("模式")
                    Spacer(Modifier.height(10.dp))
                    ChipRow(
                        items = MODE_ORDER,
                        selected = state.mode,
                        label = ::modeLabel,
                        onSelect = viewModel::setMode,
                    )
                    Spacer(Modifier.height(14.dp))
                    AppCardDivider()
                    Spacer(Modifier.height(14.dp))
                    ControlLabel("风速")
                    Spacer(Modifier.height(10.dp))
                    ChipRow(
                        items = MideaR05d.Fan.entries,
                        selected = state.fan,
                        label = ::fanLabel,
                        onSelect = viewModel::setFan,
                    )
                }
            }

            // 附加功能：切换类固定帧（R05D 没有状态位，芯片只记本机最近一次）
            AppCard {
                Column {
                    ControlLabel("附加功能")
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ToggleChip("摆风", snapshot.swingOn, enabled = state.power) {
                            viewModel.toggleSwing()
                        }
                        ToggleChip("强劲", snapshot.turboOn, enabled = state.power) {
                            viewModel.toggleTurbo()
                        }
                        ToggleChip("ECO", snapshot.ecoOn, enabled = state.power) {
                            viewModel.toggleEco()
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "摆风 / 强劲 / ECO 是切换指令，芯片亮 = 本机记住的最近一次；关机时不可用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SettingsNote("面板显示的是本机记住的设置；红外遥控没有回执，可能与空调实际状态不一致。摆风 / 强劲 / ECO 重复按会翻转。信号弱时对准空调长按电源键，会按当前设定连发几次。")
            Spacer(Modifier.height(4.dp))
        }
    }
}

/** 模式芯片的展示顺序（与美的遥控习惯一致：自动 → 制冷 → 除湿 → 送风 → 制热）。 */
private val MODE_ORDER = listOf(
    MideaR05d.Mode.Auto,
    MideaR05d.Mode.Cool,
    MideaR05d.Mode.Dry,
    MideaR05d.Mode.Fan,
    MideaR05d.Mode.Heat,
)

/** 温度长按连发的起判时长与重复间隔（毫秒）：一组约 192ms，间隔留足。 */
private const val HOLD_START_DELAY_MS = 420L
private const val HOLD_REPEAT_INTERVAL_MS = 680L

/**
 * 电源圆钮：**单击切换开关机，长按不动状态、连发当前帧**（红外单向，面板记的状态
 * 可能与空调不一致，长按只解决「单次发不中」）。长按由 `detectTapGestures` 接管，
 * 长按识别后不会再补一次单击。
 */
@Composable
private fun PowerButton(on: Boolean, onTap: () -> Unit, onHold: () -> Unit) {
    val haptics = rememberAppHaptics()
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnHold by rememberUpdatedState(onHold)
    Box(
        modifier = Modifier
            .size(100.dp)
            .clip(CircleShape)
            .background(
                if (on) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = {
                        haptics.tap()
                        currentOnHold()
                    },
                    onTap = {
                        haptics.tap()
                        currentOnTap()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            HugeIcons.Power,
            contentDescription = if (on) "关机" else "开机",
            tint = if (on) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp),
        )
    }
}

/** 温度行：− 数字 +。数字宽 150dp 居中，步进按钮支持长按连发。 */
@Composable
private fun TemperatureRow(tempC: Int, enabled: Boolean, onDelta: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepButton(HugeIcons.MinusSign, "降低温度", enabled) { onDelta(-1) }
        Text(
            text = "$tempC°C",
            style = MaterialTheme.typography.displaySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .width(150.dp)
                .alpha(if (enabled) 1f else 0.38f),
        )
        StepButton(HugeIcons.PlusSign, "升高温度", enabled) { onDelta(+1) }
    }
}

/**
 * 步进圆钮：单击一步；按住 [HOLD_START_DELAY_MS] 后每 [HOLD_REPEAT_INTERVAL_MS]
 * 连发一步。松手时点击回调会再补一步（与原装遥控的手感一致，数值看得到）。
 */
@Composable
private fun StepButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onStep: () -> Unit,
) {
    val haptics = rememberAppHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    LaunchedEffect(pressed, enabled) {
        if (!pressed || !enabled) return@LaunchedEffect
        delay(HOLD_START_DELAY_MS)
        while (isActive) {
            haptics.tap()
            onStep()
            delay(HOLD_REPEAT_INTERVAL_MS)
        }
    }
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .alpha(if (enabled) 1f else 0.38f)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                enabled = enabled,
                onClickLabel = contentDescription,
            ) {
                haptics.tap()
                onStep()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(24.dp))
    }
}

/** 通用芯片行：横向可滚（模式 5 枚在窄屏放不下时滚动，5 枚见宽屏一屏放下）。 */
@Composable
private fun <T> ChipRow(
    items: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    val haptics = rememberAppHaptics()
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { item ->
            FilterChip(
                selected = item == selected,
                onClick = {
                    haptics.tap()
                    onSelect(item)
                },
                label = { Text(label(item), style = MaterialTheme.typography.bodySmall) },
            )
        }
    }
}

@Composable
private fun ToggleChip(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onToggle: () -> Unit,
) {
    val haptics = rememberAppHaptics()
    FilterChip(
        selected = selected,
        onClick = {
            haptics.tap()
            onToggle()
        },
        enabled = enabled,
        label = { Text(label, style = MaterialTheme.typography.bodySmall) },
    )
}

@Composable
private fun ControlLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun modeLabel(mode: MideaR05d.Mode): String = when (mode) {
    MideaR05d.Mode.Auto -> "自动"
    MideaR05d.Mode.Cool -> "制冷"
    MideaR05d.Mode.Dry -> "除湿"
    MideaR05d.Mode.Fan -> "送风"
    MideaR05d.Mode.Heat -> "制热"
}

private fun fanLabel(fan: MideaR05d.Fan): String = when (fan) {
    MideaR05d.Fan.Auto -> "自动"
    MideaR05d.Fan.Low -> "低"
    MideaR05d.Fan.Medium -> "中"
    MideaR05d.Fan.High -> "高"
}
