package edu.jxslu.schedule.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import edu.jxslu.schedule.domain.MideaR05d
import kotlinx.coroutines.flow.first

/** 空调遥控的独立 DataStore（DESIGN §4.48），不与显示偏好互相牵动。 */
private val Context.acRemotePrefs by preferencesDataStore(name = "ac_remote_prefs")

/** 空调遥控的全部持久态：R05D 状态 + 扩展键的界面记忆。 */
data class AcRemoteSnapshot(
    val state: MideaR05d.State = MideaR05d.State(),
    /** 摆风 / 强劲 / ECO 协议里没有状态位，存的只是界面的「最近一次」。 */
    val swingOn: Boolean = false,
    val turboOn: Boolean = false,
    val ecoOn: Boolean = false,
)

/**
 * 虚拟遥控的持久化（独立 DataStore）：开关 / 模式 / 温度 / 风速 + 扩展键记忆。
 *
 * 空调是单向红外设备、收不到回执，界面得替它记状态；重开 App 不能回默认值
 * （面板显示 26℃ 而机器停在 22℃ 就会瞎调）。模式 / 风速存枚举名——协议码表
 * 变了也不影响旧值。2026-10-20 移除 A1 后，旧的 `mode_bits` / `fan_bits`
 * 在读取时按 A1 位值迁移一次，保存时清掉遗留键。
 */
class AcRemotePrefsStore(private val context: Context) {

    private object Keys {
        val power = booleanPreferencesKey("power")
        val mode = stringPreferencesKey("mode")
        val tempC = intPreferencesKey("temp_c")
        val fan = stringPreferencesKey("fan")
        val swingOn = booleanPreferencesKey("r05d_swing_on")
        val turboOn = booleanPreferencesKey("r05d_turbo_on")
        val ecoOn = booleanPreferencesKey("r05d_eco_on")

        // A1 时代的遗留键：只读一次做迁移，保存时清掉。
        val legacyModeBits = intPreferencesKey("mode_bits")
        val legacyFanBits = intPreferencesKey("fan_bits")
        val legacyProtocol = stringPreferencesKey("protocol")
        val legacySleep = booleanPreferencesKey("sleep")
        val legacyOnTimer = intPreferencesKey("on_timer_min")
        val legacyOffTimer = intPreferencesKey("off_timer_min")
        val legacySwingOn = booleanPreferencesKey("swing_on")
        val legacyLightOn = booleanPreferencesKey("light_on")
        val legacyTurboOn = booleanPreferencesKey("turbo_on")
        val legacyEcoOn = booleanPreferencesKey("eco_on")
    }

    suspend fun load(): AcRemoteSnapshot {
        val prefs = context.acRemotePrefs.data.first()
        val mode = prefs[Keys.mode]
            ?.let { name -> MideaR05d.Mode.entries.firstOrNull { it.name == name } }
            ?: prefs[Keys.legacyModeBits]?.let(::legacyMode)
            ?: MideaR05d.Mode.Cool
        val fan = prefs[Keys.fan]
            ?.let { name -> MideaR05d.Fan.entries.firstOrNull { it.name == name } }
            ?: prefs[Keys.legacyFanBits]?.let(::legacyFan)
            ?: MideaR05d.Fan.Auto
        return AcRemoteSnapshot(
            state = MideaR05d.State(
                power = prefs[Keys.power] ?: false,
                mode = mode,
                tempC = (prefs[Keys.tempC] ?: 26).coerceIn(MideaR05d.MIN_TEMP_C, MideaR05d.MAX_TEMP_C),
                fan = fan,
            ),
            swingOn = prefs[Keys.swingOn] ?: false,
            turboOn = prefs[Keys.turboOn] ?: false,
            ecoOn = prefs[Keys.ecoOn] ?: false,
        )
    }

    suspend fun save(snapshot: AcRemoteSnapshot) {
        val state = snapshot.state
        context.acRemotePrefs.edit { prefs ->
            prefs[Keys.power] = state.power
            prefs[Keys.mode] = state.mode.name
            prefs[Keys.tempC] = state.tempC
            prefs[Keys.fan] = state.fan.name
            prefs[Keys.swingOn] = snapshot.swingOn
            prefs[Keys.turboOn] = snapshot.turboOn
            prefs[Keys.ecoOn] = snapshot.ecoOn
            prefs.remove(Keys.legacyModeBits)
            prefs.remove(Keys.legacyFanBits)
            prefs.remove(Keys.legacyProtocol)
            prefs.remove(Keys.legacySleep)
            prefs.remove(Keys.legacyOnTimer)
            prefs.remove(Keys.legacyOffTimer)
            prefs.remove(Keys.legacySwingOn)
            prefs.remove(Keys.legacyLightOn)
            prefs.remove(Keys.legacyTurboOn)
            prefs.remove(Keys.legacyEcoOn)
        }
    }

    /** A1 时代存的是位值：Cool=0 / Dry=1 / Auto=2 / Heat=3 / Fan=4。 */
    private fun legacyMode(bits: Int): MideaR05d.Mode = when (bits) {
        1 -> MideaR05d.Mode.Dry
        2 -> MideaR05d.Mode.Auto
        3 -> MideaR05d.Mode.Heat
        4 -> MideaR05d.Mode.Fan
        else -> MideaR05d.Mode.Cool
    }

    /** A1 时代存的是位值：Auto=0 / Low=1 / Medium=2 / High=3。 */
    private fun legacyFan(bits: Int): MideaR05d.Fan = when (bits) {
        1 -> MideaR05d.Fan.Low
        2 -> MideaR05d.Fan.Medium
        3 -> MideaR05d.Fan.High
        else -> MideaR05d.Fan.Auto
    }
}
