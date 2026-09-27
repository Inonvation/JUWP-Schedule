package edu.jxslu.schedule.data.qzxy

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 已绑定的设备（DESIGN §4.30）。[name] 是上次看到的广播名或服务端设备名。 */
data class QzxyBoundDevice(
    val address: String,
    val name: String,
)

/** 设备快照：已绑定的那几台 + 上次用过的那台。 */
data class QzxyDeviceState(
    val bound: List<QzxyBoundDevice> = emptyList(),
    val lastUsed: QzxyBoundDevice? = null,
)

/**
 * 趣智校园已绑定设备（DESIGN §4.30）。
 *
 * 存普通 `SharedPreferences`，不上加密：设备地址与名字不是凭证，
 * 加密只会让「换机后恢复绑定」这类排查变难。凭证仍在 `secure_qzxy` 里。
 *
 * 同一台设备按地址去重，绑定过的再点一次是更新名字，不产生第二条。
 *
 * 落盘之外还挂一条 [state]：绑定与「上次使用」都发生在趣智校园页，而今日页卡片是
 * 另一份 ViewModel 实例，只读一次 `SharedPreferences` 的写法会让卡片一直显示「未绑定」。
 */
class QzxyDeviceStore(context: Context) {
    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(readState())

    /** 设备快照，供多份 ViewModel 实例订阅。 */
    val state: StateFlow<QzxyDeviceState> = _state.asStateFlow()

    fun list(): List<QzxyBoundDevice> = _state.value.bound

    fun add(device: QzxyBoundDevice) {
        val current = prefs.getStringSet(KEY_DEVICES, emptySet()).orEmpty()
            .filterNot { it.substringBefore(SEPARATOR).equals(device.address, ignoreCase = true) }
            .toMutableSet()
        current += device.address + SEPARATOR + device.name
        prefs.edit().putStringSet(KEY_DEVICES, current).apply()
        refresh()
    }

    fun remove(address: String) {
        val current = prefs.getStringSet(KEY_DEVICES, emptySet()).orEmpty()
            .filterNot { it.substringBefore(SEPARATOR).equals(address, ignoreCase = true) }
            .toSet()
        prefs.edit().putStringSet(KEY_DEVICES, current).apply()
        refresh()
    }

    /**
     * 上次用过的设备（DESIGN §3.18）。
     *
     * 今日页卡片只有半行宽，绑了多台时得挑一台显示；挑「上次用的那台」比挑字母序
     * 第一台有用。它同时是重进页面时的默认选中项。
     */
    fun lastUsed(): QzxyBoundDevice? = _state.value.lastUsed

    fun setLastUsed(device: QzxyBoundDevice) {
        prefs.edit()
            .putString(KEY_LAST_ADDRESS, device.address)
            .putString(KEY_LAST_NAME, device.name)
            .apply()
        refresh()
    }

    private fun refresh() {
        _state.value = readState()
    }

    private fun readState(): QzxyDeviceState = QzxyDeviceState(
        bound = prefs.getStringSet(KEY_DEVICES, emptySet())
            .orEmpty()
            .mapNotNull { raw ->
                val separator = raw.indexOf(SEPARATOR)
                if (separator <= 0) {
                    null
                } else {
                    QzxyBoundDevice(raw.substring(0, separator), raw.substring(separator + 1))
                }
            }
            .sortedBy { it.name },
        lastUsed = prefs.getString(KEY_LAST_ADDRESS, null)
            ?.takeIf { it.isNotBlank() }
            ?.let { address ->
                QzxyBoundDevice(address, prefs.getString(KEY_LAST_NAME, null).orEmpty())
            },
    )

    private companion object {
        const val FILE_NAME = "qzxy_devices"
        const val KEY_DEVICES = "bound_devices"
        const val KEY_LAST_ADDRESS = "last_address"
        const val KEY_LAST_NAME = "last_name"
        const val SEPARATOR = "|"
    }
}
