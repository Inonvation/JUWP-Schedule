package edu.jxslu.schedule.ui.life

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.ir.AcIrBlaster
import edu.jxslu.schedule.data.prefs.AcRemoteSnapshot
import edu.jxslu.schedule.data.prefs.AcRemotePrefsStore
import edu.jxslu.schedule.domain.MideaAc
import kotlinx.coroutines.Dispatchers
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
 * 没有「只发改动位」的说法；切换类功能（摆风 / 屏显 / 强力 / ECO）在主帧之后
 * 再补一条切换帧。发完把新状态写回本机。发射不占 UI 线程：一组波形约 260ms，
 * `transmit` 在部分机型上会同步等它发完。
 */
class AcRemoteViewModel(
    private val store: AcRemotePrefsStore,
    private val blaster: AcIrBlaster,
) : ViewModel() {

    /** 本机没有红外发射器时为 false，页面改显示提示。 */
    val irSupported: Boolean = blaster.supported

    private val _snapshot = MutableStateFlow(AcRemoteSnapshot())
    val snapshot: StateFlow<AcRemoteSnapshot> = _snapshot.asStateFlow()

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

    fun togglePower() = update { it.copy(state = it.state.copy(power = !it.state.power)) }

    fun setMode(mode: MideaAc.Mode) = update { it.copy(state = it.state.copy(mode = mode)) }

    fun setFan(fan: MideaAc.Fan) = update { it.copy(state = it.state.copy(fan = fan)) }

    fun bumpTemp(delta: Int) = update {
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
     * 统一出口：改状态 → 界面先行 → 后台发射 → 落盘。
     * [special] 非空时在其后补发一条切换帧（先主帧、后切换帧，与上游一致）。
     *
     * [special] 带默认值且排在 [transform] 前，`update { … }` 的尾随 lambda 才会
     * 落到 [transform] 上。
     */
    private fun update(
        special: MideaAc.Toggle? = null,
        transform: (AcRemoteSnapshot) -> AcRemoteSnapshot,
    ) {
        val current = _snapshot.value
        val next = transform(current)
        if (next == current) return
        touched = true
        _snapshot.value = next
        viewModelScope.launch(Dispatchers.Default) {
            val main = MideaAc.pattern(MideaAc.frame(next.state))
            val full = if (special == null) main else main + MideaAc.pattern(special.frameBytes)
            sendLock.withLock { blaster.transmit(full) }
        }
        viewModelScope.launch { store.save(next) }
    }

    companion object {
        fun Factory(context: Context) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = AcRemoteViewModel(
                Graph.acRemotePrefsStore(context.applicationContext),
                Graph.acIrBlaster(context.applicationContext),
            ) as T
        }
    }
}
