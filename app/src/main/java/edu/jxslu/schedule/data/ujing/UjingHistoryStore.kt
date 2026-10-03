package edu.jxslu.schedule.data.ujing

import android.content.Context
import edu.jxslu.schedule.domain.UjingState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * U净 历史账单（DESIGN §4.37，2026-10-02）。
 *
 * 口径：记**本 App 发起**的每一笔订单——下单即入账，刷新随状态更新，取消也算一笔
 * （状态标注「已取消」）。服务端的账单接口没有可用的协议样本（`docs/ujing-plan.md`
 * 的端点表里只有 `orders/running`），不猜端点，先落本地；等拿到官方抓包样本再评估
 * 换成服务端账单源。
 *
 * 选型与 `UjingOrderStore` / `UjingHouseStore` 同一理由：普通 prefs 存 JSON 数组
 * （最多 [CAP] 条，新的在前），外挂一条进程内可订阅的流给两个页面（主页入口行的
 * 笔数、历史账单页的列表）。不加密：订单号 / 状态 / 金额不是凭证（凭证在 `secure_ujing`）。
 */
class UjingHistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<List<UjingHistoryEntry>> = _state.asStateFlow()

    /** 新增或更新一笔（同 orderId 去重，最新在前），超量截断。 */
    fun upsert(entry: UjingHistoryEntry) {
        val updated = upsertInto(_state.value, entry)
        persist(updated)
        _state.value = updated
    }

    /** 清空账单（历史页的「清空」，二次确认在 UI）。 */
    fun clear() {
        prefs.edit().remove(KEY_ENTRIES).apply()
        _state.value = emptyList()
    }

    private fun persist(list: List<UjingHistoryEntry>) {
        prefs.edit().putString(KEY_ENTRIES, encode(list)).apply()
    }

    private fun read(): List<UjingHistoryEntry> {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        return runCatching { decode(raw) }.getOrDefault(emptyList())
    }

    private fun encode(list: List<UjingHistoryEntry>): String = JSONArray().apply {
        list.forEach { e ->
            put(
                JSONObject().apply {
                    put("orderId", e.orderId)
                    put("orderNo", e.orderNo)
                    put("deviceName", e.deviceName)
                    put("modelName", e.modelName)
                    put("priceFen", e.priceFen)
                    put("status", e.status)
                    put("paid", e.paid)
                    put("createdAt", e.createdAt)
                    put("updatedAt", e.updatedAt)
                },
            )
        }
    }.toString()

    private fun decode(raw: String): List<UjingHistoryEntry> {
        val array = JSONArray(raw)
        return (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            UjingHistoryEntry(
                orderId = obj.optString("orderId"),
                orderNo = obj.optString("orderNo"),
                deviceName = obj.optString("deviceName"),
                modelName = obj.optString("modelName"),
                priceFen = obj.optInt("priceFen"),
                status = obj.optString("status"),
                paid = obj.optBoolean("paid"),
                createdAt = obj.optLong("createdAt"),
                updatedAt = obj.optLong("updatedAt"),
            )
        }.filter { it.orderId.isNotBlank() }
    }

    companion object {
        private const val FILE_NAME = "ujing_history"
        private const val KEY_ENTRIES = "entries"

        /** 账单容量上限：本机消费记录，50 笔远超一学期的洗衣频次。 */
        const val CAP = 50

        /**
         * 纯列表逻辑（JVM 可测）：新条目顶到最前，同 [UjingHistoryEntry.orderId]
         * 去重（刷新更新原条目而不是叠一笔），超 [CAP] 截断。
         */
        fun upsertInto(
            current: List<UjingHistoryEntry>,
            entry: UjingHistoryEntry,
        ): List<UjingHistoryEntry> =
            (listOf(entry) + current.filterNot { it.orderId == entry.orderId }).take(CAP)
    }
}

/**
 * 一笔历史账单。`status` 是最近一次已知的**服务端订单状态**（枚举见
 * [UjingState.statusText]，取消在仓库里直接标注 `60`）；`paid` 与之一致地反映支付与否。
 */
data class UjingHistoryEntry(
    val orderId: String,
    val orderNo: String = "",
    val deviceName: String = "",
    val modelName: String = "",
    /** 应付金额（分）。 */
    val priceFen: Int = 0,
    val status: String = "",
    val paid: Boolean = false,
    /** 下单时刻（毫秒；来自订单快照，旧版本快照可能为 0，展示时退回 [updatedAt]）。 */
    val createdAt: Long = 0,
    /** 本条最近一次落定时刻（毫秒）。 */
    val updatedAt: Long = 0,
) {
    /** 列表展示用时间基准：下单时刻优先，缺失退回最近更新。 */
    val displayAt: Long get() = if (createdAt > 0) createdAt else updatedAt

    companion object {
        /** 订单快照 → 账单条目（[statusOverride] 给取消留痕用，非空时覆盖状态）。 */
        fun fromSnapshot(
            snapshot: UjingOrderSnapshot,
            statusOverride: String? = null,
            nowMillis: Long = System.currentTimeMillis(),
        ): UjingHistoryEntry = UjingHistoryEntry(
            orderId = snapshot.orderId,
            orderNo = snapshot.orderNo,
            deviceName = snapshot.deviceName,
            modelName = snapshot.modelName,
            priceFen = snapshot.priceFen,
            status = statusOverride ?: snapshot.status,
            paid = snapshot.paid,
            createdAt = snapshot.createdAt,
            updatedAt = nowMillis,
        )
    }
}
