package edu.jxslu.schedule.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import edu.jxslu.schedule.domain.MideaAc
import kotlinx.coroutines.flow.first

/** 空调遥控的独立 DataStore（DESIGN §4.48），不与显示偏好互相牵动。 */
private val Context.acRemotePrefs by preferencesDataStore(name = "ac_remote_prefs")

/** 空调遥控的全部持久态：协议状态 + 切换记忆。 */
data class AcRemoteSnapshot(
    val state: MideaAc.State = MideaAc.State(),
    val toggles: MideaAc.Toggles = MideaAc.Toggles(),
)

/**
 * 虚拟遥控的持久化（独立 DataStore）：开关 / 模式 / 温度 / 风速 / 睡眠 / 定时 +
 * 切换记忆。
 *
 * 空调是单向红外设备、收不到回执，界面得替它记状态；重开 App 不能回默认值
 * （面板显示 26℃ 而机器停在 22℃ 就会瞎调）。模式 / 风速存协议的位值而不是
 * ordinal——枚举顺序将来若有调整，位值仍是协议口径，旧值照常认得。
 * 切换类功能（摆风 / 屏显 / 强力 / ECO）协议里没有状态位，存的只是界面的
 * 「最近一次」记忆（[MideaAc.Toggles]）。
 */
class AcRemotePrefsStore(private val context: Context) {

    private object Keys {
        val power = booleanPreferencesKey("power")
        val modeBits = intPreferencesKey("mode_bits")
        val tempC = intPreferencesKey("temp_c")
        val fanBits = intPreferencesKey("fan_bits")
        val sleep = booleanPreferencesKey("sleep")
        val onTimer = intPreferencesKey("on_timer_min")
        val offTimer = intPreferencesKey("off_timer_min")
        val swingOn = booleanPreferencesKey("swing_on")
        val lightOn = booleanPreferencesKey("light_on")
        val turboOn = booleanPreferencesKey("turbo_on")
        val ecoOn = booleanPreferencesKey("eco_on")
    }

    suspend fun load(): AcRemoteSnapshot {
        val prefs = context.acRemotePrefs.data.first()
        val mode = prefs[Keys.modeBits]
            ?.let { bits -> MideaAc.Mode.entries.firstOrNull { it.bits == bits } }
            ?: MideaAc.Mode.Cool
        val fan = prefs[Keys.fanBits]
            ?.let { bits -> MideaAc.Fan.entries.firstOrNull { it.bits == bits } }
            ?: MideaAc.Fan.Auto
        return AcRemoteSnapshot(
            state = MideaAc.State(
                power = prefs[Keys.power] ?: false,
                mode = mode,
                tempC = (prefs[Keys.tempC] ?: 26).coerceIn(MideaAc.MIN_TEMP_C, MideaAc.MAX_TEMP_C),
                fan = fan,
                sleep = prefs[Keys.sleep] ?: false,
                onTimerMinutes = prefs[Keys.onTimer]?.takeIf { it >= 30 },
                offTimerMinutes = prefs[Keys.offTimer]?.takeIf { it >= 30 },
            ),
            toggles = MideaAc.Toggles(
                swingOn = prefs[Keys.swingOn] ?: false,
                lightOn = prefs[Keys.lightOn] ?: false,
                turboOn = prefs[Keys.turboOn] ?: false,
                ecoOn = prefs[Keys.ecoOn] ?: false,
            ),
        )
    }

    suspend fun save(snapshot: AcRemoteSnapshot) {
        val state = snapshot.state
        context.acRemotePrefs.edit { prefs ->
            prefs[Keys.power] = state.power
            prefs[Keys.modeBits] = state.mode.bits
            prefs[Keys.tempC] = state.tempC
            prefs[Keys.fanBits] = state.fan.bits
            prefs[Keys.sleep] = state.sleep
            prefs[Keys.onTimer] = state.onTimerMinutes ?: 0
            prefs[Keys.offTimer] = state.offTimerMinutes ?: 0
            prefs[Keys.swingOn] = snapshot.toggles.swingOn
            prefs[Keys.lightOn] = snapshot.toggles.lightOn
            prefs[Keys.turboOn] = snapshot.toggles.turboOn
            prefs[Keys.ecoOn] = snapshot.toggles.ecoOn
        }
    }
}
