package edu.jxslu.schedule.ui.life

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.ir.AcIrBlaster
import edu.jxslu.schedule.data.prefs.AcRemotePrefsStore
import edu.jxslu.schedule.data.prefs.AcRemoteSnapshot
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
 * 只走 R05D（国内美的挂机）：普通操作按**完整状态**发一帧；摆风 / 强劲是固定
 * 扩展帧，ECO 是「经济帧 + 状态帧 ×2」。扩展键没有状态位，芯片只记本机最近一次。
 * 每次 `transmit` 只发一组（≈192ms；ECO 三帧 ≈286ms），普通操作发 1 次
 * （一声蜂鸣），长按电源连发 [HOLD_BURSTS] 次。发射不占 UI 线程，`sendLock` 串行化。
 *
 * ECO 的口径：按下时发「经济帧 + 状态帧 ×2」，再按一次发普通状态帧即取消；
 * 任意普通按键（开关 / 模式 / 温度 / 风速）也会取消，所以 `updateState` 顺手清
 * `ecoOn`——这是《R05d 电控功能说明书》的规则，真机未验证。
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

    fun togglePower() = updateState { it.copy(power = !it.power) }

    fun setMode(mode: MideaR05d.Mode) = updateState { it.copy(mode = mode) }

    fun setFan(fan: MideaR05d.Fan) = updateState { it.copy(fan = fan) }

    /** 温度步进：长按会连着走，单次只发 1 组（与普通操作同口径）。 */
    fun bumpTemp(delta: Int) = updateState {
        it.copy(tempC = (it.tempC + delta).coerceIn(MideaR05d.MIN_TEMP_C, MideaR05d.MAX_TEMP_C))
    }

    /** 摆风：固定帧发两遍；关机时不响应（原装遥控关机时该键也不发码）。 */
    fun toggleSwing() {
        val current = _snapshot.value
        if (!current.state.power) return
        apply(current.copy(swingOn = !current.swingOn), MideaR05d.swingPattern())
    }

    /** 强劲：固定帧发两遍，再按一次关闭；关机时不响应。 */
    fun toggleTurbo() {
        val current = _snapshot.value
        if (!current.state.power) return
        apply(current.copy(turboOn = !current.turboOn), MideaR05d.turboPattern())
    }

    /** ECO：开 = 经济帧 + 状态帧两遍；关 = 普通状态帧两遍；关机时不响应。 */
    fun toggleEco() {
        val current = _snapshot.value
        if (!current.state.power) return
        val on = !current.ecoOn
        val pattern = if (on) {
            MideaR05d.ecoPattern(current.state)
        } else {
            MideaR05d.pattern(MideaR05d.frame(current.state))
        }
        apply(current.copy(ecoOn = on), pattern)
    }

    /**
     * 长按电源：**不改状态**，把当前帧连发几次。
     *
     * 红外是单向的，面板记的状态可能与空调实际不一致；长按只解决「离得远 / 电池低、
     * 单次发不中」这一个问题，所以不碰状态、也不翻转开关。ECO 开着时发 ECO 序列，
     * 免得重发普通帧把经济模式取消。
     */
    fun repeatCurrent(bursts: Int = HOLD_BURSTS) {
        val snapshot = _snapshot.value
        val pattern = if (snapshot.ecoOn && snapshot.state.power) {
            MideaR05d.ecoPattern(snapshot.state)
        } else {
            MideaR05d.pattern(MideaR05d.frame(snapshot.state))
        }
        viewModelScope.launch(Dispatchers.Default) {
            sendLock.withLock { emit(pattern, bursts) }
        }
    }

    /**
     * 普通状态更新：任何普通帧都会取消 ECO（说明书：取消经济 = 只发普通帧），
     * 所以这里顺手把 `ecoOn` 清掉。
     */
    private fun updateState(transform: (MideaR05d.State) -> MideaR05d.State) {
        val current = _snapshot.value
        val state = transform(current.state)
        if (state == current.state) return
        val next = current.copy(state = state, ecoOn = false)
        apply(next, MideaR05d.pattern(MideaR05d.frame(state)))
    }

    /** 统一出口：界面先行 → 后台发射 → 落盘。 */
    private fun apply(
        next: AcRemoteSnapshot,
        pattern: IntArray,
        bursts: Int = DEFAULT_BURSTS,
    ) {
        val current = _snapshot.value
        if (next == current) return
        touched = true
        _snapshot.value = next
        viewModelScope.launch(Dispatchers.Default) {
            sendLock.withLock { emit(pattern, bursts) }
        }
        viewModelScope.launch { store.save(next) }
    }

    /** 发 [bursts] 次同一组波形；任一次失败就停并把原因抬到界面。 */
    private suspend fun emit(pattern: IntArray, bursts: Int) {
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
        /** 普通操作发几次：一组 R05D ≈192 ms；1 次即一声蜂鸣。 */
        const val DEFAULT_BURSTS = 1

        /** 长按电源的连发次数。 */
        const val HOLD_BURSTS = 4

        /** 两次发射之间的间隔（毫秒）；波形自己尾部还带 8.8 ms 静默。 */
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
