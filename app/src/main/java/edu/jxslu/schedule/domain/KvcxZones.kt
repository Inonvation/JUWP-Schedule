package edu.jxslu.schedule.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * 还车点（停车点）图层（DESIGN §3.9，2026-09-28）。
 *
 * 数据来自快趣的 `POST /v1.0.0/queryZoneList {lat, lng, carNum, page, rows}`——官方还车点页
 * `pages/epark/index` 就是拿它画「还车点 / 禁停区」的（从解包产物逐字对齐）：
 *
 * ```
 * result:
 *   givecarList[]:   { lat, lng, scopeArray: [{lat,lng}], device }   ← 还车点（多边形 + 图标位）
 *   nogoZoneList[]:  { scopeArray: [{lat,lng}] }                     ← 禁停区
 *   servicesiteZoneList[]: { servicesiteId, scopeArray }             ← 服务区大围栏（**不画**，见下）
 * ```
 *
 * **服务区那层不画**：我们已有手绘的校园围栏（`BikeNearby.CAMPUS_FENCE`，用户对照官方逐边复核过），
 * 语义相同，再叠一层只会两片蓝糊在一起；校园围栏还兼着「只看本校」的过滤，不能拿它替。
 *
 * 字段一律宽容取值（数值可能给字符串——与 `KqcxAuth` 同一教训）；解析失败回 [EMPTY]：
 * 图层是装饰，少画一层远好过报错打扰。
 */
data class KvcxZones(
    /** 还车点（停车点）：多边形范围 + 图标落点。 */
    val parkSpots: List<KvcxParkSpot>,
    /** 禁停区多边形。 */
    val nogoZones: List<List<GcjPoint>>,
) {
    /** 有没有东西可画（无图层时 UI 少走一遍绘制）。 */
    val isEmpty: Boolean get() = parkSpots.isEmpty() && nogoZones.isEmpty()

    /**
     * 「只看本校」对图层的作用（2026-09-28 用户口径）：**只留围栏内的还车点**——
     * 还车点没有车队归属字段（那是车辆才有的），只能按坐标判。
     *
     * **禁停区不筛**：它是安全提示，"这里不能停车"藏掉比画多余一块更糟；而且围栏与禁停区
     * 是两套多边形，做交并集才能判"要不要"——收益不值这个复杂度。
     */
    fun campusOnly(inFence: (lat: Double, lng: Double) -> Boolean): KvcxZones =
        copy(parkSpots = parkSpots.filter { inFence(it.lat, it.lng) })

    companion object {
        val EMPTY = KvcxZones(emptyList(), emptyList())

        /**
         * 解析 `queryZoneList` 的响应。**不抛异常**：网络层已经兜过，这里只处理结构；
         * 结构不符（服务端改字段 / 空 result）一律回 [EMPTY]，与「这一带没有还车点」表现一致。
         */
        fun parse(jsonText: String): KvcxZones {
            val result = runCatching {
                val root = Json.parseToJsonElement(jsonText).jsonObject
                root["result"]?.jsonObject
            }.getOrNull() ?: return EMPTY

            val spots = result.arr("givecarList").mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val lat = obj.doubleAt("lat") ?: return@mapNotNull null
                val lng = obj.doubleAt("lng") ?: return@mapNotNull null
                KvcxParkSpot(lat = lat, lng = lng, outline = obj.outline())
            }
            val nogo = result.arr("nogoZoneList").mapNotNull { item ->
                val outline = (item as? JsonObject)?.outline() ?: return@mapNotNull null
                outline.takeIf { it.size >= 3 }
            }
            return KvcxZones(parkSpots = spots, nogoZones = nogo)
        }
    }
}

/** 一个还车点：图标落在 [lat] / [lng]，[outline] 是它的范围（不足 3 点 = 只有落点没有范围）。 */
data class KvcxParkSpot(
    val lat: Double,
    val lng: Double,
    val outline: List<GcjPoint>,
)

// ---------- 宽容取值（与 KqcxAuth 同口径，但这里只用得上 Double 与数组） ----------

private fun JsonObject.arr(key: String): List<Any?> =
    runCatching { this[key]?.jsonArray?.toList() }.getOrNull() ?: emptyList()

private fun JsonObject.doubleAt(key: String): Double? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()

/** 多边形顶点：`scopeArray: [{lat, lng}, …]`；缺字段 / 坏点直接丢那一项。 */
private fun JsonObject.outline(): List<GcjPoint> =
    arr("scopeArray").mapNotNull { point ->
        val obj = point as? JsonObject ?: return@mapNotNull null
        val lat = obj.doubleAt("lat") ?: return@mapNotNull null
        val lng = obj.doubleAt("lng") ?: return@mapNotNull null
        GcjPoint(lat, lng)
    }
