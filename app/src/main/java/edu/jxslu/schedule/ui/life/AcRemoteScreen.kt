package edu.jxslu.schedule.ui.life

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.jxslu.schedule.domain.MideaAc
import edu.jxslu.schedule.ui.common.AppCard
import edu.jxslu.schedule.ui.common.AppCardDivider
import edu.jxslu.schedule.ui.common.SettingsNote
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ChevronRight
import me.rerere.hugeicons.stroke.MinusSign
import me.rerere.hugeicons.stroke.PlusSign
import me.rerere.hugeicons.stroke.Power

/**
 * 生活页 → 校园服务 → 空调遥控（DESIGN §3.31 / §4.48）。
 *
 * 页面结构（自上而下，全走描边卡）：主控卡（电源圆钮 · 状态行 · 温度 ±）→
 * 模式 / 风速卡 → 附加功能卡（睡眠 / 风向 / 屏显 / 强力 / ECO）→ 定时卡（两行，
 * 点开弹层）。温度步进支持长按连发；送风档温度置灰（该档没有温度目标）。
 *
 * 每次操作按完整状态发一帧，协议见 [MideaAc]；状态本机持久化。无红外发射器的
 * 机型进页只给提示；底部一行说明「面板是本机记忆」的口径。
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
    val state = snapshot.state
    var timerTarget by remember { mutableStateOf<TimerKind?>(null) }

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
                    PowerButton(on = state.power) { viewModel.togglePower() }
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
                        enabled = state.mode != MideaAc.Mode.Fan,
                        onDelta = viewModel::bumpTemp,
                    )
                }
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
                        items = MideaAc.Fan.entries,
                        selected = state.fan,
                        label = ::fanLabel,
                        onSelect = viewModel::setFan,
                    )
                }
            }

            // 附加功能：切换类按钮（协议无状态位，芯片亮 = 本机记的「最近一次」）
            AppCard {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ToggleChip("睡眠", selected = state.sleep) { viewModel.toggleSleep() }
                    ToggleChip("风向", selected = snapshot.toggles.swingOn) {
                        viewModel.toggleSpecial(MideaAc.Toggle.SwingV)
                    }
                    ToggleChip("屏显", selected = snapshot.toggles.lightOn) {
                        viewModel.toggleSpecial(MideaAc.Toggle.Light)
                    }
                    ToggleChip("强力", selected = snapshot.toggles.turboOn) {
                        viewModel.toggleSpecial(MideaAc.Toggle.Turbo)
                    }
                    ToggleChip("ECO", selected = snapshot.toggles.ecoOn) {
                        viewModel.toggleSpecial(MideaAc.Toggle.Eco)
                    }
                }
            }

            // 定时：两行，点开弹层选预设
            AppCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp)) {
                Column {
                    TimerRow("定时关机", state.offTimerMinutes) { timerTarget = TimerKind.Off }
                    AppCardDivider()
                    TimerRow("定时开机", state.onTimerMinutes) { timerTarget = TimerKind.On }
                }
            }

            SettingsNote("面板显示的是本机记住的设置；红外遥控没有回执，可能与空调实际状态不一致。")
            Spacer(Modifier.height(4.dp))
        }
    }

    timerTarget?.let { kind ->
        TimerPickerDialog(
            title = if (kind == TimerKind.Off) "定时关机" else "定时开机",
            currentMinutes = if (kind == TimerKind.Off) state.offTimerMinutes else state.onTimerMinutes,
            onPick = { minutes ->
                if (kind == TimerKind.Off) viewModel.setOffTimer(minutes) else viewModel.setOnTimer(minutes)
                timerTarget = null
            },
            onDismiss = { timerTarget = null },
        )
    }
}

/** 模式芯片的展示顺序（与美的遥控习惯一致：自动 → 制冷 → 除湿 → 送风 → 制热）。 */
private val MODE_ORDER = listOf(
    MideaAc.Mode.Auto,
    MideaAc.Mode.Cool,
    MideaAc.Mode.Dry,
    MideaAc.Mode.Fan,
    MideaAc.Mode.Heat,
)

/** 定时预设（分钟）：30 分钟起、30 分钟一档，最多 8 小时，够寝室场景。 */
private val TIMER_PRESETS = listOf(30, 60, 90, 120, 180, 240, 360, 480)

/** 温度长按连发的起判时长与重复间隔（毫秒）：发射一组约 260ms，间隔留足。 */
private const val HOLD_START_DELAY_MS = 420L
private const val HOLD_REPEAT_INTERVAL_MS = 680L

/** 开关机定时的弹层目标。 */
private enum class TimerKind { On, Off }

@Composable
private fun PowerButton(on: Boolean, onClick: () -> Unit) {
    val haptics = rememberAppHaptics()
    Box(
        modifier = Modifier
            .size(100.dp)
            .clip(CircleShape)
            .background(
                if (on) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .clickable {
                haptics.tap()
                onClick()
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
private fun ToggleChip(label: String, selected: Boolean, onToggle: () -> Unit) {
    val haptics = rememberAppHaptics()
    FilterChip(
        selected = selected,
        onClick = {
            haptics.tap()
            onToggle()
        },
        label = { Text(label, style = MaterialTheme.typography.bodySmall) },
    )
}

/** 定时行（卡内平铺行口径）：标题 + 当前值 + 右箭头，点开选择弹层。 */
@Composable
private fun TimerRow(label: String, minutes: Int?, onClick: () -> Unit) {
    val haptics = rememberAppHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClickLabel = label) {
                haptics.tap()
                onClick()
            }
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.weight(1f))
        Text(
            text = minutes?.let { "${hoursLabel(it)}后" } ?: "未设置",
            style = MaterialTheme.typography.bodyMedium,
            color = if (minutes != null) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(4.dp))
        Icon(
            HugeIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun ControlLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 定时选择弹层：预设 + 「不定时」，当前值加粗高亮。 */
@Composable
private fun TimerPickerDialog(
    title: String,
    currentMinutes: Int?,
    onPick: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                TIMER_PRESETS.forEach { minutes ->
                    val current = minutes == currentMinutes
                    TextButton(
                        onClick = { onPick(minutes) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = hoursLabel(minutes),
                            color = if (current) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
                val none = currentMinutes == null
                TextButton(
                    onClick = { onPick(null) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "不定时",
                        color = if (none) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (none) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

private fun modeLabel(mode: MideaAc.Mode): String = when (mode) {
    MideaAc.Mode.Auto -> "自动"
    MideaAc.Mode.Cool -> "制冷"
    MideaAc.Mode.Dry -> "除湿"
    MideaAc.Mode.Fan -> "送风"
    MideaAc.Mode.Heat -> "制热"
}

private fun fanLabel(fan: MideaAc.Fan): String = when (fan) {
    MideaAc.Fan.Auto -> "自动"
    MideaAc.Fan.Low -> "低"
    MideaAc.Fan.Medium -> "中"
    MideaAc.Fan.High -> "高"
}

private fun hoursLabel(minutes: Int): String =
    if (minutes % 60 == 0) "${minutes / 60}小时" else "${minutes / 60}.5小时"
