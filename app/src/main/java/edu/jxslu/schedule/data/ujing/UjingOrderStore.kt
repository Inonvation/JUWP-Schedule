package edu.jxslu.schedule.data.ujing

import android.content.Context
import edu.jxslu.schedule.domain.UjingState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * 进行中的 U净 订单快照（DESIGN §4.37 P2）。
 *
 * 选型与 `QzxyWateringStore` 同一理由：读它的有两处独立 ViewModel 实例（页面与本页
 * 未来的今日页卡片 / 通知落点），普通 SharedPreferences 的读时快照无法推送变化；
 * 落盘之外再挂一条进程内可订阅的流，`Graph` 保证同一进程一份实例。
 *
 * **为什么必须落盘**（社区实测教训）：App 进程重启后内存态 `orderId` 丢失 → 上报 /
 * 查询无单可查 → 用户在"看起来正常"的界面里反复点一个永远不会生效的按钮。订单号
 * 与机器绑定，必须跨进程存活。
 *
 * 不加密：订单号 / 状态 / 机器名不是凭证（凭证在 `secure_ujing`）。
 */
class UjingOrderStore(context: Context) {
    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<UjingOrderSnapshot?> = _state.asStateFlow()

    /** 当前在案订单（无则 null）。 */
    fun current(): UjingOrderSnapshot? = _state.value

    fun save(snapshot: UjingOrderSnapshot) {
        prefs.edit().putString(KEY_ORDER, encode(snapshot)).apply()
        _state.value = snapshot
    }

    /** 订单终结（完成 / 取消 / 失败）或用户主动放弃时清掉。 */
    fun clear() {
        prefs.edit().remove(KEY_ORDER).apply()
        _state.value = null
    }

    private fun read(): UjingOrderSnapshot? {
        val raw = prefs.getString(KEY_ORDER, null) ?: return null
        return runCatching { decode(raw) }.getOrNull()
    }

    private fun encode(s: UjingOrderSnapshot): String = JSONObject().apply {
        put("orderId", s.orderId)
        put("orderNo", s.orderNo)
        put("deviceId", s.deviceId)
        put("deviceName", s.deviceName)
        put("modelId", s.modelId)
        put("modelName", s.modelName)
        put("priceFen", s.priceFen)
        put("status", s.status)
        put("remainSeconds", s.remainSeconds)
        put("snapshotAt", s.snapshotAt)
        put("paid", s.paid)
        put("createdAt", s.createdAt)
        put("durationSeconds", s.durationSeconds)
    }.toString()

    private fun decode(raw: String): UjingOrderSnapshot {
        val obj = JSONObject(raw)
        return UjingOrderSnapshot(
            orderId = obj.getString("orderId"),
            orderNo = obj.optString("orderNo"),
            deviceId = obj.optString("deviceId"),
            deviceName = obj.optString("deviceName"),
            modelId = obj.optInt("modelId"),
            modelName = obj.optString("modelName"),
            priceFen = obj.optInt("priceFen"),
            status = obj.optString("status"),
            remainSeconds = obj.optInt("remainSeconds"),
            snapshotAt = obj.optLong("snapshotAt"),
            paid = obj.optBoolean("paid"),
            createdAt = obj.optLong("createdAt"),
            durationSeconds = obj.optInt("durationSeconds"),
        )
    }

    private companion object {
        const val FILE_NAME = "ujing_order"
        const val KEY_ORDER = "order"
    }
}

/**
 * 在案订单的快照。`status` / `remainSeconds` 是**最近一次服务端回包**的值，
 * 本地倒计时以 [snapshotAt] 为基准换算（不每秒请求）。
 */
data class UjingOrderSnapshot(
    val orderId: String,
    val orderNo: String = "",
    val deviceId: String = "",
    val deviceName: String = "",
    /** 用户选的模式（下单请求原样保存，重启后卡片还能说明"点的是什么"）。 */
    val modelId: Int = 0,
    val modelName: String = "",
    /** 应付金额（分）。 */
    val priceFen: Int = 0,
    val status: String = "",
    /** 服务端报告的剩余秒数；未开始的订单为 0。 */
    val remainSeconds: Int = 0,
    /** 快照落定时刻（毫秒），本地倒计时基准。 */
    val snapshotAt: Long = 0,
    /** 支付是否已完成（detail.payFlag / lastPayStatus 的归一结果）。 */
    val paid: Boolean = false,
    /**
     * 下单时刻（毫秒）。**只在 createOrder 时写、刷新时原样保留**——
     * 服务端给未支付订单 2 分钟独占期，倒计时以此为准（snapshotAt 每次刷新都会动，
     * 拿它当窗口基准会越刷越长）。旧版本快照没有此字段（0），此时不显示窗口倒计时。
     */
    val createdAt: Long = 0,
    /**
     * 模式总时长（秒），下单时取所选模式的 `time`。洗涤进度条用它当分母；
     * 旧版本快照为 0，此时不显示进度条。
     */
    val durationSeconds: Int = 0,
) {
    val isTerminal: Boolean get() = UjingState.isTerminal(status)
}
