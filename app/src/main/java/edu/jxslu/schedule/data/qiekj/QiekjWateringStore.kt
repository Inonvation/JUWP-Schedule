package edu.jxslu.schedule.data.qiekj

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 一次「正在出水」的本机会话（2026-10-21）。
 *
 * 胖乖官方**没有**可靠的跨会话「进行中」状态：`sync` 的 `workStatus` / `status`
 * 会黏住十几分钟（life-power.md 实测定论），只有设备回报 `status==5 && amount>0`
 * 才是真结束。所以本地只记「这次开阀的时刻与设备」，作为今日页服务卡 / 侧栏高亮的
 * 展示依据——真正的结算仍以流程回报的账单为准，本记录不参与任何金额判断。
 */
data class QiekjWateringSession(
    /** 服务端确认出水（流程首次进入 Working）的时刻。 */
    val startedAtMillis: Long,
    val deviceName: String,
    val goodsId: String?,
) {
    /**
     * 超过这个时长仍没结算 = 流程已经不在了（进程被杀 / 页面被关导致协程取消）。
     * 服务端 165 秒会自动关阀，再留一段结算余量；此后本地记录视为过期，
     * 读的时候直接清掉——不给用户留一条永不消失的「出水中」。
     */
    fun isStale(nowMillis: Long): Boolean = nowMillis - startedAtMillis >= STALE_MILLIS

    /**
     * 出水进度 0..1（2026-10-22，今日页服务卡用）：按服务端 [AUTO_CLOSE_SECONDS] 秒
     * 自动关阀兜底，超出按 1 封顶。与开水页 `WaterViewModel.AUTO_SETTLE_SECONDS` 同一口径。
     */
    fun progressFraction(nowMillis: Long): Float {
        val total = AUTO_CLOSE_SECONDS * 1000L
        val elapsed = (nowMillis - startedAtMillis).coerceAtLeast(0L)
        return (elapsed.toFloat() / total).coerceIn(0f, 1f)
    }

    companion object {
        /** 服务端出水自动关阀的兜底秒数（`WaterViewModel` 的自动结算同一口径）。 */
        const val AUTO_CLOSE_SECONDS = 165

        /** 165 秒服务端超时关阀 + 结算/轮询余量。 */
        const val STALE_MILLIS: Long = 5 * 60 * 1000L
    }
}

/**
 * 进行中的开水状态（DESIGN §3.18 / §4.10，2026-10-21）。
 *
 * 为什么不是普通偏好而是 [StateFlow]：读它的有两处独立 ViewModel——今日页那挂在
 * `MainActivity` 上，开水页是二级页窗口的另一份实例；`UnlockFlowState.Working`
 * 只活在各自的 VM 里，跨窗口互相看不见（从开水页开水，今日页卡片不出现；从侧栏面板
 * 开水，开水页看不到流程态）。把「正在出水」的事实收进一条落盘的进程内流，
 * 两处都订阅它，展示口径才一致（与 [edu.jxslu.schedule.data.qzxy.QzxyWateringStore] 同构）。
 *
 * 普通 `SharedPreferences` 不上加密：开阀时刻与设备名不是凭证。
 */
class QiekjWateringStore(context: Context) {

    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val _watering = MutableStateFlow(readInitial())

    /** 非空 = 本机登记过一次「出水中」（过期残留已在读时清掉）。 */
    val watering: StateFlow<QiekjWateringSession?> = _watering.asStateFlow()

    /** 服务端确认出水时登记（流程首次进入 Working）。重复调用按最新一次覆盖。 */
    fun begin(session: QiekjWateringSession) {
        prefs.edit()
            .putLong(KEY_STARTED_AT, session.startedAtMillis)
            .putString(KEY_NAME, session.deviceName)
            .putString(KEY_GOODS, session.goodsId)
            .apply()
        _watering.value = session
    }

    /**
     * 结算完成 / 流程失败 / 165 秒超时收尾时清掉。
     * 结算晚到（超时后 Success 重新落卡）时再清一次是空操作。
     */
    fun clear() {
        prefs.edit().clear().apply()
        _watering.value = null
    }

    private fun readInitial(): QiekjWateringSession? {
        val started = prefs.getLong(KEY_STARTED_AT, 0L)
        if (started <= 0L) return null
        val session = QiekjWateringSession(
            startedAtMillis = started,
            deviceName = prefs.getString(KEY_NAME, "").orEmpty(),
            goodsId = prefs.getString(KEY_GOODS, null),
        )
        if (session.isStale(System.currentTimeMillis())) {
            // 过期残留：进程死在流程中，服务端早已超时关阀。静默清掉（展示口径，
            // 不涉及金额记录——账单由流程回报，见 QiekjWateringSession KDoc）
            prefs.edit().clear().apply()
            return null
        }
        return session
    }

    private companion object {
        const val FILE_NAME = "qiekj_watering"
        const val KEY_STARTED_AT = "started_at"
        const val KEY_NAME = "device_name"
        const val KEY_GOODS = "goods_id"
    }
}
