package edu.jxslu.schedule.data.qiekj

import android.content.Context
import kotlinx.serialization.builtins.ListSerializer

/**
 * 本地订单快照存储（DESIGN §4.10）。
 *
 * 为什么不进 Room：订单快照是胖乖模块私有的追加型小数据（上限 50 条），无查询/关联需求，
 * SharedPreferences + JSON 与参考实现一致且足够；接口侧没有可靠的订单列表端点，
 * 快照是订单历史的唯一来源，随 token 清理而清空。
 */
class QiekjOrderHistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("qiekj_order_history", Context.MODE_PRIVATE)
    private val serializer = ListSerializer(OrderHistoryItem.serializer())

    fun list(): List<OrderHistoryItem> {
        val raw = prefs.getString(KEY_ORDERS, null) ?: return emptyList()
        return runCatching { QiekjJson.json.decodeFromString(serializer, raw) }
            .getOrDefault(emptyList())
    }

    fun add(item: OrderHistoryItem) {
        val next = (list().filterNot { it.orderNo == item.orderNo } + item)
            .sortedByDescending { it.completedAt }
            .take(MAX_HISTORY)
        prefs.edit().putString(KEY_ORDERS, QiekjJson.json.encodeToString(serializer, next)).apply()
    }

    fun clearAll() {
        prefs.edit().remove(KEY_ORDERS).apply()
    }

    private companion object {
        const val KEY_ORDERS = "orders"
        const val MAX_HISTORY = 50
    }
}
