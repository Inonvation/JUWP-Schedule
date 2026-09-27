package edu.jxslu.schedule.data.qzxy

import android.content.Context
import edu.jxslu.schedule.domain.QzxyWatering
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 进行中的用水状态（DESIGN §3.18）。
 *
 * 为什么不是普通偏好而是 [StateFlow]：读它的有两处独立 ViewModel——今日页卡片那份
 * 挂在 `MainActivity` 上、趣智校园页与诊断页各自是二级页窗口的实例。开阀发生在页面里，
 * 今日页卡片要立刻变成「用水中 12:35」，靠 `SharedPreferences` 的读时快照做不到，
 * 所以落盘之外再挂一条进程内可订阅的流（Graph 保证同一进程只有一份实例）。
 *
 * 存普通 `SharedPreferences` 不上加密，与 [QzxyDeviceStore] 同口径：开阀时间与设备名
 * 不是凭证，凭证仍在 `secure_qzxy` 里。
 */
class QzxyWateringStore(context: Context) {

    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val _watering = MutableStateFlow(read())

    /** 非空 = 正在用水（或上次开阀后一直没结算）。 */
    val watering: StateFlow<QzxyWatering?> = _watering.asStateFlow()

    /** 开阀成功时登记。重复调用按最新一次覆盖。 */
    fun begin(watering: QzxyWatering) {
        prefs.edit()
            .putLong(KEY_STARTED_AT, watering.startedAtMillis)
            .putString(KEY_ADDRESS, watering.deviceAddress)
            .putString(KEY_NAME, watering.deviceName)
            .putString(KEY_PRE_DEDUCT, watering.preDeductMilli)
            .apply()
        _watering.value = watering
    }

    /**
     * 结束用水结算成功后清掉。
     *
     * 用户也可以在结算走不通时手动清（页面上的「标记为已结束」）——水可能已经被
     * 官方 App 结掉、设备上也没有记录，留着这条只会让他每次进来都看到「用水中」。
     */
    fun clear() {
        prefs.edit().clear().apply()
        _watering.value = null
    }

    private fun read(): QzxyWatering? {
        val startedAt = prefs.getLong(KEY_STARTED_AT, 0L)
        if (startedAt <= 0L) return null
        return QzxyWatering(
            startedAtMillis = startedAt,
            deviceAddress = prefs.getString(KEY_ADDRESS, null).orEmpty(),
            deviceName = prefs.getString(KEY_NAME, null).orEmpty(),
            preDeductMilli = prefs.getString(KEY_PRE_DEDUCT, null),
        )
    }

    private companion object {
        const val FILE_NAME = "qzxy_watering"
        const val KEY_STARTED_AT = "started_at"
        const val KEY_ADDRESS = "device_address"
        const val KEY_NAME = "device_name"
        const val KEY_PRE_DEDUCT = "pre_deduct_milli"
    }
}
