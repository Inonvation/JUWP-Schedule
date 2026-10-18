package edu.jxslu.schedule.ui.life

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.ir.AcIrBlaster
import edu.jxslu.schedule.data.prefs.AcRemotePrefsStore
import edu.jxslu.schedule.data.prefs.AcRemoteSnapshot
import edu.jxslu.schedule.domain.AcProtocol
import edu.jxslu.schedule.domain.MideaAc
import edu.jxslu.schedule.domain.MideaR05d
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 空调遥控页状态（DESIGN §4.48）。
 *
 * 每次操作按**完整状态**发一帧——美的协议每帧都带电源 / 模式 / 温度 / 风速全程，
 * 没有「只发改动位」的说法。默认 R05D（国内美的挂机，`B2 4D` 口径），可选 A1
 * （IRremoteESP8266 `ir_Midea` 口径，带睡眠 / 定时 / 切换帧）；切换类功能
 * （摆风 / 屏显 / 强力 / ECO）只在 A1 下补发切换帧，R05D 没有公开编码。
 * 发完把新状态写回本机。发射不占 UI 线程。
 *
 * **每次 `transmit` 只发一组（R05D ≈192 ms / A1 ≈291 ms），强度靠调用次数**：小米 `ir-spi` HAL 会按时长
 * 上限整条丢弃（见 [MideaAc] 文件头），把主帧与切换帧拼成一条正是原先切换键全废的原因。
 * 普通操作补发 [DEFAULT_BURSTS] 次；温度步进（长按连发）只发 1 次，免得发送队列拖在
 * 手指后面；电源键长按走 [repeatCurrent] 连发 [HOLD_BURSTS] 次。
 */
class AcRemoteViewModel(
    private val store: AcRemotePrefsStore,
    private val blaster: AcIrBlaster,
) : ViewModel() {

    /** 本机没有红外发射器时为 false，页面改显示提示。 */
    val irSupported: Boolean = blaster.supported

    private val _snapshot = MutableStateFlow(AcRemoteSnapshot())
    val snapshot: StateFlow<AcRemoteSnapshot> = _snapshot.asStateFlow()

    /** 发射失败的原因（无发射器 / 系统拒绝），页面据此给一行提示；成功后清空。 */
    private val _transmitError = MutableStateFlow<String?>(null)
    val transmitError: StateFlow<String?> = _transmitError.asStateFlow()

    /** 首读落盘值之前用户已动过手（极窄窗口），就别用旧值覆盖。 */
    private var touched = false

    /**
     * 发射互斥：连点 / 温度长按连发时 `transmit` 不允许并发（顺序也要保，
     * 空调端对重叠帧的解析没保证）。
     */
    private val sendLock = Mutex()

    init {
        viewModelScope.launch {
            val saved = store.load()
            if (!touched) _snapshot.value = saved
        }
    }

    /**
     * 切换红外口径：只改本机设置、**不发射**。
     *
     * 红外是单向的，切换那一刻空调并不知道；下一次按键才会按新口径发完整状态。
     */
    fun setProtocol(protocol: AcProtocol) {
        val current = _snapshot.value
        if (current.protocol == protocol) return
        touched = true
        val next = current.copy(protocol = protocol)
        _snapshot.value = next
        viewModelScope.launch { store.save(next) }
    }

    fun togglePower() = update { it.copy(state = it.state.copy(power = !it.state.power)) }

    fun setMode(mode: MideaAc.Mode) = update { it.copy(state = it.state.copy(mode = mode)) }

    fun setFan(fan: MideaAc.Fan) = update { it.copy(state = it.state.copy(fan = fan)) }

    /** 温度步进：长按会连着走，所以单次只发 1 组，不补发。 */
    fun bumpTemp(delta: Int) = update(bursts = TEMP_BURSTS) {
        it.copy(
            state = it.state.copy(
                tempC = (it.state.tempC + delta).coerceIn(MideaAc.MIN_TEMP_C, MideaAc.MAX_TEMP_C),
            ),
        )
    }

    fun toggleSleep() = update { it.copy(state = it.state.copy(sleep = !it.state.sleep)) }

    fun setOnTimer(minutes: Int?) =
        update { it.copy(state = it.state.copy(onTimerMinutes = minutes)) }

    fun setOffTimer(minutes: Int?) =
        update { it.copy(state = it.state.copy(offTimerMinutes = minutes)) }

    fun toggleSpecial(toggle: MideaAc.Toggle) = update(
        transform = { snap ->
            val toggles = snap.toggles
            snap.copy(
                toggles = when (toggle) {
                    MideaAc.Toggle.SwingV -> toggles.copy(swingOn = !toggles.swingOn)
                    MideaAc.Toggle.Light -> toggles.copy(lightOn = !toggles.lightOn)
                    MideaAc.Toggle.Turbo -> toggles.copy(turboOn = !toggles.turboOn)
                    MideaAc.Toggle.Eco -> toggles.copy(ecoOn = !toggles.ecoOn)
                },
            )
        },
        special = toggle,
    )

    /**
     * 长按电源：**不改状态**，把当前帧连发几次。
     *
     * 红外是单向的，面板记的状态可能与空调实际不一致；长按只解决「离得远 / 电池低、
     * 单次发不中」这一个问题，所以不碰状态、也不翻转开关。
     */
    fun repeatCurrent(bursts: Int = HOLD_BURSTS) {
        val snapshot = _snapshot.value
        val frame = frameFor(snapshot)
        viewModelScope.launch(Dispatchers.Default) {
            sendLock.withLock { emit(snapshot.protocol, frame, bursts) }
        }
    }

    /**
     * 统一出口：改状态 → 界面先行 → 后台发射 → 落盘。
     * [special] 非空时在其后补发一条切换帧（先主帧、后切换帧，与上游一致；两条各自
     * 单独 `transmit`，绝不拼成一条）。
     *
     * [special] / [bursts] 带默认值且排在 [transform] 前，`update { … }` 的尾随 lambda
     * 才会落到 [transform] 上。
     */
    private fun update(
        special: MideaAc.Toggle? = null,
        bursts: Int = DEFAULT_BURSTS,
        transform: (AcRemoteSnapshot) -> AcRemoteSnapshot,
    ) {
        val current = _snapshot.value
        val next = transform(current)
        if (next == current) return
        touched = true
        _snapshot.value = next
        viewModelScope.launch(Dispatchers.Default) {
            sendLock.withLock {
                emit(next.protocol, frameFor(next), bursts)
                if (special != null && next.protocol == AcProtocol.A1) {
                    // 切换帧只能发一次：同一帧发两次会把摆风 / 屏显 / 强力 / ECO 翻回去。
                    emit(next.protocol, special.frameBytes, TOGGLE_BURSTS)
                }
            }
        }
        viewModelScope.launch { store.save(next) }
    }

    /** 当前口径下的完整状态帧。 */
    private fun frameFor(snapshot: AcRemoteSnapshot): IntArray = when (snapshot.protocol) {
        AcProtocol.A1 -> MideaAc.frame(snapshot.state)
        AcProtocol.R05D -> MideaR05d.frame(
            MideaR05d.State(
                power = snapshot.state.power,
                mode = snapshot.state.mode.toR05d(),
                tempC = snapshot.state.tempC,
                fan = snapshot.state.fan.toR05d(),
            ),
        )
    }

    private fun MideaAc.Mode.toR05d(): MideaR05d.Mode = when (this) {
        MideaAc.Mode.Auto -> MideaR05d.Mode.Auto
        MideaAc.Mode.Cool -> MideaR05d.Mode.Cool
        MideaAc.Mode.Dry -> MideaR05d.Mode.Dry
        MideaAc.Mode.Heat -> MideaR05d.Mode.Heat
        MideaAc.Mode.Fan -> MideaR05d.Mode.Fan
    }

    private fun MideaAc.Fan.toR05d(): MideaR05d.Fan = when (this) {
        MideaAc.Fan.Auto -> MideaR05d.Fan.Auto
        MideaAc.Fan.Low -> MideaR05d.Fan.Low
        MideaAc.Fan.Medium -> MideaR05d.Fan.Medium
        MideaAc.Fan.High -> MideaR05d.Fan.High
    }

    /** 发 [bursts] 次同一组波形；任一次失败就停并把原因抬到界面。 */
    private suspend fun emit(protocol: AcProtocol, frame: IntArray, bursts: Int) {
        val pattern = when (protocol) {
            AcProtocol.A1 -> MideaAc.pattern(frame)
            AcProtocol.R05D -> MideaR05d.pattern(frame)
        }
        repeat(bursts) { index ->
            when (val result = blaster.transmit(pattern)) {
                is AcIrBlaster.TransmitResult.Failed -> {
                    _transmitError.value = result.reason
                    return
                }
                AcIrBlaster.TransmitResult.Sent -> _transmitError.value = null
            }
            if (index != bursts - 1) delay(BURST_GAP_MS)
        }
    }

    companion object {
        /** 普通操作补发几次：一组 ≈291 ms，两次之间留 [BURST_GAP_MS]。 */
        const val DEFAULT_BURSTS = 2

        /** 温度步进的次数：长按连发时队列不能拖在手指后面。 */
        const val TEMP_BURSTS = 1

        /** 长按电源的连发次数。 */
        const val HOLD_BURSTS = 4

        /** A1 切换帧的发射次数：固定 1 次，重复会翻转两次。 */
        const val TOGGLE_BURSTS = 1

        /** 两次发射之间的间隔（毫秒）；波形自己尾部还带 100 ms 静默。 */
        const val BURST_GAP_MS = 100L

        fun Factory(context: Context) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = AcRemoteViewModel(
                Graph.acRemotePrefsStore(context.applicationContext),
                Graph.acIrBlaster(context.applicationContext),
            ) as T
        }
    }
}
