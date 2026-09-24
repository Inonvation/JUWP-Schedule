package edu.jxslu.schedule.data.power

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * 缴费平台（新开普收费平台，DESIGN §4.24）响应模型与解析。
 *
 * 与 [edu.jxslu.schedule.data.ykt.YktModels] 同款：只建模用到的部分，脏数据不抛异常
 * （缺字段退默认、错类型当没有），让上层按「字段缺 = 平台改版」处理。
 */

/** 缴费平台异常分类（UI 按类型给文案）。 */
sealed class PowerException(message: String) : Exception(message) {

    /** 网络不可达 / 超时。 */
    class Network(cause: Throwable) : PowerException("网络不给力，稍后重试")

    /** 凭证类（登录被拒）：学号或查询密码不对。 */
    class Credential(message: String) : PowerException(message)

    /** 协议类：平台返回结构与预期不同，或参数被拒。 */
    class Protocol(message: String) : PowerException(message)
}

/** 场景层级里的一项。`code` 同时是请求参数名（campus / building / room）。 */
data class PowerSceneKey(val code: String, val id: String, val name: String)

/** 收费项目（`GET /charge/feeitem/singleFeeitem` 的 `feeitem` + `sceneinfo`）。 */
data class PowerFeeItem(
    val id: Int,
    val name: String,
    /** 单价（元/单位）。 */
    val priceYuan: Double?,
    /** 计费单位（「度」）。 */
    val unit: String?,
    /** 收费类型名（「生活缴费」）。 */
    val feetypeName: String?,
    /** 绑定房间的场景键（campus → building → room）。 */
    val scene: List<PowerSceneKey>,
) {
    /** 绑定房间那一项。 */
    val room: PowerSceneKey? get() = scene.lastOrNull { it.code == "room" }
}

/** 房间标识（电表读数里回填，比 sceneinfo 新——后者带学校旧名）。 */
data class PowerRoom(
    val campus: String?,
    val building: String?,
    val room: String?,
    val roomId: String?,
)

/**
 * 一次电表读数。
 *
 * [fields] 是 `map.showData` 原文（中文键，如 `当前剩余电量`）：平台加字段时只多键，
 * 所以保留原文、另外单独解析出 [remain] 供展示，不把原始 map 丢掉。
 */
data class PowerMeter(
    val room: PowerRoom,
    val fields: Map<String, String>,
    /** 剩余电量（数值口径）；认不出字段为 null。 */
    val remain: Double?,
    /** 认出的字段名（展示用，如「当前剩余电量」）。 */
    val remainField: String?,
    val fetchedAtMs: Long,
)

/**
 * 电费流水一条（充值/退款；[amountFen] 恒为非负，方向看 [refund]）。
 *
 * **方向只认 `tranamt` 的正负，不认 `refund_flag`**（2026-09-24 实测，见
 * [parseTurnovers] 的 KDoc）。
 */
data class PowerTurnover(
    val turnoverId: Long?,
    val dateText: String,
    val epochMs: Long,
    val month: String?,
    /** 金额绝对值（分）。 */
    val amountFen: Long,
    val room: String?,
    /** true = 退款（`tranamt` 为负）；false = 充值。 */
    val refund: Boolean,
)

/** 一次取数的完整结果（项目配置 + 读数）。 */
data class PowerSnapshot(val feeItem: PowerFeeItem, val meter: PowerMeter)

object PowerModels {

    /** 电费收费项目 id（2026-09-23 实测：平台上只有这一个项目）。 */
    const val RECHARGE_FEE_ITEM_ID = 181

    /** 时间原文格式（服务端 `createdate`）。 */
    private val TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 外壳业务码（`code`）；解析失败为 null。 */
    fun codeOf(raw: String): Int? = root(raw)?.get("code")?.let { asInt(it) }

    /** 外壳消息（`msg` / `message` 取第一个非空）。 */
    fun messageOf(raw: String): String? {
        val obj = root(raw) ?: return null
        return (asString(obj["msg"]) ?: asString(obj["message"]))?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** 登录响应的 access_token（不落盘，只在仓库内存里）。 */
    fun parseToken(raw: String): String? =
        root(raw)?.get("access_token")?.let { asString(it) }?.takeIf { it.isNotBlank() }

    /** 收费项目详情（`/charge/feeitem/singleFeeitem`）。 */
    fun parseFeeItem(raw: String): PowerFeeItem {
        val obj = root(raw) ?: throw PowerException.Protocol("电费项目详情不是 JSON")
        val fee = obj["feeitem"]?.jsonObjectOrNull()
            ?: throw PowerException.Protocol("电费项目详情缺 feeitem")
        return PowerFeeItem(
            id = fee["feeitemid"]?.let { asInt(it) } ?: RECHARGE_FEE_ITEM_ID,
            name = asString(fee["name"]).orEmpty(),
            priceYuan = fee["price"]?.let { asDouble(it) },
            unit = asString(fee["billing_unit"]),
            feetypeName = asString(fee["feetypeBean"]?.jsonObjectOrNull()?.get("name")),
            scene = parseSceneInfo(asString(obj["sceneinfo"])),
        )
    }

    /** 电表读数（`POST /charge/feeitem/getThirdData` 的 `map`）。 */
    fun parseMeter(raw: String, nowMs: Long): PowerMeter {
        val map = root(raw)?.get("map")?.jsonObjectOrNull()
            ?: throw PowerException.Protocol("读电表返回里没有 map（参数不全或平台改版）")
        val data = map["data"]?.jsonObjectOrNull().orEmpty()
        val fields = map["showData"]?.jsonObjectOrNull()
            ?.mapValues { (_, v) -> asString(v).orEmpty() }
            .orEmpty()
        val (remain, field) = pickRemain(fields)
        return PowerMeter(
            room = PowerRoom(
                campus = asString(data["campus"]),
                building = asString(data["building"]),
                room = asString(data["room"]),
                roomId = asString(data["roomid"]),
            ),
            fields = fields,
            remain = remain,
            remainField = field,
            fetchedAtMs = nowMs,
        )
    }

    /**
     * 电费流水（`GET /charge/turnover/personal_data` 的 `list`），按时间升序。
     *
     * **退款判据是 `tranamt` 的符号，不是 `refund_flag`**（2026-09-24 实测）：
     * `refund_flag` 在该账号 11 条记录上**恒为 1**，其中 9 条是明显的充值
     * （2026-08-25 农行支付 20 元、2026-08-01 农行支付 50 元…），把它当退款判据
     * 会让整页充值都显示成「电费退款」。平台自己的 H5 也从不读这个字段——
     * 它判充值用的是 `list.filter(tranamt > 0)`，退款是另一条订单流程
     * （`POST /order/addRefundOrder`，申请入口在「退费申请」页），不在这份列表里。
     * 所以：`tranamt < 0` 才算退款，金额统一取绝对值存。
     */
    fun parseTurnovers(raw: String): List<PowerTurnover> {
        val list = root(raw)?.get("list")?.jsonArrayOrNull().orEmpty()
        return list.mapNotNull { element ->
            val obj = element.jsonObjectOrNull() ?: return@mapNotNull null
            val date = asString(obj["createdate"]).orEmpty()
            val amountYuan = obj["tranamt"]?.let { asDouble(it) } ?: 0.0
            PowerTurnover(
                turnoverId = obj["turnoverid"]?.let { asLong(it) },
                dateText = date,
                epochMs = parseTimeMs(date),
                month = asString(obj["feerange"]),
                amountFen = fen(abs(amountYuan)),
                room = asString(obj["abstracts"]),
                refund = amountYuan < 0,
            )
        }.sortedBy { it.epochMs }
    }

    /**
     * 未支付订单（`GET /charge/order/personal_data?paystatus=0` 的 `orderList`，
     * 2026-09-24 实测）→ 订单号列表。只留删除所需字段；脏数据静默跳过。
     */
    fun parsePendingOrderIds(raw: String): List<String> {
        val list = root(raw)?.get("orderList")?.jsonArrayOrNull().orEmpty()
        return list.mapNotNull { element ->
            val obj = element.jsonObjectOrNull() ?: return@mapNotNull null
            val id = asString(obj["orderid"]) ?: return@mapNotNull null
            val status = obj["status"]?.let { asInt(it) } ?: return@mapNotNull null
            if (status == 0) id else null
        }
    }

    /**
     * `sceneinfo` 解析：`campus:0#南昌工程学院;building:0#9A;room:14600#9A101`。
     * 名字里的校区是学校旧名（南昌工程学院），房间名以读数返回的 `data` 为准。
     */
    fun parseSceneInfo(raw: String?): List<PowerSceneKey> {
        val text = raw.orEmpty()
        if (text.isBlank()) return emptyList()
        return text.split(';').mapNotNull { part ->
            val trimmed = part.trim()
            if (trimmed.isEmpty()) return@mapNotNull null
            val code = trimmed.substringBefore(':', missingDelimiterValue = "").trim()
            val rest = trimmed.substringAfter(':', missingDelimiterValue = "")
            val id = rest.substringBefore('#').trim()
            val name = rest.substringAfter('#', missingDelimiterValue = "").trim()
            if (code.isEmpty() || id.isEmpty()) null else PowerSceneKey(code, id, name)
        }
    }

    /**
     * 从 `showData` 里认剩余电量：先找键含「剩余电量」的，再退到含「电量」或「余额」的。
     * 返回（数值, 字段名）；认不出为（null, null）。
     */
    fun pickRemain(fields: Map<String, String>): Pair<Double?, String?> {
        val candidates = listOf(
            fields.keys.firstOrNull { it.contains("剩余电量") },
            fields.keys.firstOrNull { it.contains("电量") || it.contains("余额") },
        )
        for (key in candidates) {
            if (key == null) continue
            val value = fields[key]?.trim()?.toDoubleOrNull()
            if (value != null) return value to key
        }
        return null to null
    }

    /** 服务端时间原文 → epoch 毫秒；解析不了给 0（排序沉底）。 */
    fun parseTimeMs(text: String): Long {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return 0L
        return runCatching { LocalDateTime.parse(trimmed, TIME_FORMAT) }
            .map { it.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }
            .getOrDefault(0L)
    }

    /**
     * 流水里的房间标签：`abstracts` 形如
     * `校区-江西水利电力大学;楼栋-9A;房间-9A101`，取「房间-」那一段的值。
     */
    fun roomLabelOf(abstracts: String?): String? {
        val text = abstracts.orEmpty()
        if (text.isBlank()) return null
        return text.split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("房间-") }
            ?.substringAfter('-')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    /** 元 → 分。 */
    fun fen(yuan: Double): Long = (yuan * 100).roundToLong()

    // ------------------------------------------------------------------
    // JSON 取值容错：类型不对一律当没有，不让解析抛异常
    // ------------------------------------------------------------------

    private fun root(raw: String): JsonObject? =
        runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()

    private fun JsonElement?.jsonObjectOrNull(): JsonObject? =
        runCatching { this?.jsonObject }.getOrNull()

    private fun JsonElement?.jsonArrayOrNull(): List<JsonElement>? =
        runCatching { this?.jsonArray }.getOrNull()

    /** 字符串化：数字与字符串都接受（平台两种都用过）。 */
    private fun asString(element: JsonElement?): String? = when {
        element == null -> null
        element is JsonPrimitive -> element.contentOrNull
        else -> null
    }

    private fun asInt(element: JsonElement): Int? =
        asString(element)?.trim()?.toIntOrNull() ?: (element as? JsonPrimitive)?.intOrNull

    private fun asLong(element: JsonElement): Long? =
        asString(element)?.trim()?.toLongOrNull() ?: (element as? JsonPrimitive)?.longOrNull

    private fun asDouble(element: JsonElement): Double? =
        asString(element)?.trim()?.toDoubleOrNull() ?: (element as? JsonPrimitive)?.doubleOrNull
}
