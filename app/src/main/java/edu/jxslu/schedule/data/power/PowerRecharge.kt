package edu.jxslu.schedule.data.power

import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.random.Random
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 电费下单/支付的签名与解析（DESIGN §4.24「电费充值」）。
 *
 * 与 [edu.jxslu.schedule.domain.YktRechargeSign] 同一算法（前端公开常量，两处一致），
 * 单独落一份的原因：电费的 [NONCE] 用 `Math.random().toString(36)` 口径（同款），
 * 但下单字段与响应结构完全不同，放同一对象会把两套协议搅在一起。
 */
object PowerPaySign {

    /** 前端硬编码的应用标识与签名密钥（公开 JS 常量，非逆向所得）。 */
    const val APP_ID = "56321"
    const val SECRET_KEY = "0osTIhce7uPvDKHz6aa67bhCukaKoYl4"

    private const val KEY_SIGN = "SIGN"
    private const val KEY_SECRET = "SECRET_KEY"

    /** 给业务参数补元参数与 SIGN（新 map）。[timestamp]/[nonce] 可注入（单测定死）。 */
    fun signed(
        params: Map<String, String>,
        timestamp: String = buildTimestamp(),
        nonce: String = buildNonce(),
    ): Map<String, String> {
        val merged = LinkedHashMap<String, String>(params.size + 4)
        merged.putAll(params)
        merged["APP_ID"] = APP_ID
        merged["TIMESTAMP"] = timestamp
        merged["NONCE"] = nonce
        merged["SIGN_TYPE"] = "SHA256"
        merged[KEY_SIGN] = signOf(merged)
        return merged
    }

    /** key 字典序、跳过空值与 SIGN/SECRET_KEY，拼 `k=v&` + `SECRET_KEY=…`，SHA256 大写。 */
    fun signOf(params: Map<String, String>): String {
        val keys = params.keys
            .filter { it != KEY_SIGN && it != KEY_SECRET }
            .filter { (params[it] ?: "").isNotEmpty() }
            .sorted()
        val sb = StringBuilder()
        for (k in keys) sb.append(k).append('=').append(params.getValue(k)).append('&')
        sb.append(KEY_SECRET).append('=').append(SECRET_KEY)
        return sha256Hex(sb.toString()).uppercase()
    }

    /** `yyyyMMddHHmmssSSS`（本地时区；前端 `new Date()` 口径）。 */
    fun buildTimestamp(now: Long = System.currentTimeMillis()): String {
        val ldt = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(now), ZoneId.systemDefault())
        return ldt.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"))
    }

    /** 11 位小写字母数字（前端 `Math.random().toString(36).substring(2)` 口径，2026-09-23 实测两种都通过）。 */
    fun buildNonce(random: Random = Random): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        return buildString { repeat(11) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    private fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

/** 一次下单的返回（`POST /blade-pay/pay` `paystep=0`）。 */
data class PowerOrder(
    val orderId: String,
    /** 支付有效期（服务端 `payExpDate`，原文）。 */
    val payExpDate: String?,
)

/** 支付渠道（下单响应的 `payList` 元素）。 */
data class PowerPayChannel(val payId: String, val name: String, val code: String, val noPassword: Boolean)

/**
 * `paystep=2` 拿到的支付参数（DESIGN §4.24，2026-09-23 实测口径）。
 *
 * - [passwordMap]：键 = uuid，值 = **乱序数字字符表**（10 位）。用户按数字 `d` 时，
 *   提交的密文字符是 `table[d]`（不是键位下标！）——与一卡通登录键盘同一协议，
 *   `cipherOf` 是唯一换算点。
 * - [accountBalanceFen]：电子账户余额（`ccctype[0].balance`，单位分，平台侧实时值）。
 */
data class PowerPayChallenge(
    val orderId: String,
    val passwordMap: Map<String, String>,
    /** 电子账户类型值（`000`，来自 `ccctype[0].ccctype`；支付回传要用）。 */
    val accountType: String?,
    /** 电子账户余额（分；`ccctype[0].balance`）。 */
    val accountBalanceFen: Long?,
) {
    /** 提交用的 uuid（passwordMap 唯一键；服务端还有顶层 uuid 字段但实测为 null）。 */
    val uuid: String? get() = passwordMap.keys.firstOrNull()

    /** 用户输入的数字串 → 提交密文。表缺失/数字越界返回 null（上层拦截，绝不瞎猜）。 */
    fun cipherOf(digits: String): String? {
        val table = passwordMap[uuid] ?: return null
        if (digits.length !in 1..6) return null
        val sb = StringBuilder()
        for (c in digits) {
            if (!c.isDigit()) return null
            sb.append(table[c - '0'])
        }
        return sb.toString()
    }
}

/** 支付结果（`POST /blade-pay/pay` `paystep=2` 带 password）。 */
sealed interface PowerPayResult {
    /** 服务端受理成功（之后由查单确认 status=1）。 */
    data object Accepted : PowerPayResult

    /** 密码错误等业务失败（可重试）。 */
    data class Rejected(val message: String) : PowerPayResult
}

object PowerPayModels {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun codeOf(raw: String): Int? =
        runCatching {
            val el = json.parseToJsonElement(raw).jsonObject["code"]
            (el as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
                ?: (el as? JsonPrimitive)?.content?.toIntOrNull()
        }.getOrNull()

    fun messageOf(raw: String): String? = runCatching {
        val obj = json.parseToJsonElement(raw).jsonObject
        (obj["msg"] as? JsonPrimitive)?.contentOrNull
            ?: (obj["message"] as? JsonPrimitive)?.contentOrNull
    }.getOrNull()

    fun orderFrom(raw: String): PowerOrder? = runCatching {
        val data = dataOf(raw) ?: return@runCatching null
        val orderId = (data["orderid"] as? JsonPrimitive)?.contentOrNull
            ?: (data["orderId"] as? JsonPrimitive)?.contentOrNull
            ?: return@runCatching null
        PowerOrder(
            orderId = orderId,
            payExpDate = (data["payExpDate"] as? JsonPrimitive)?.contentOrNull,
        )
    }.getOrNull()

    fun channelsFrom(raw: String): List<PowerPayChannel> = runCatching {
        val data = dataOf(raw) ?: return@runCatching emptyList()
        val arr = data["payList"] as? kotlinx.serialization.json.JsonArray ?: return@runCatching emptyList()
        arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            PowerPayChannel(
                payId = (o["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null,
                name = (o["pay_type_name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                code = (o["pay_type_code"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                noPassword = ((o["nopassword"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0) == 1,
            )
        }
    }.getOrDefault(emptyList())

    fun challengeFrom(raw: String): PowerPayChallenge? = runCatching {
        val data = dataOf(raw) ?: return@runCatching null
        val mapObj = data["passwordMap"] as? JsonObject ?: return@runCatching null
        val map = mapObj.entries.associate { (k, v) -> k to (v as? JsonPrimitive)?.contentOrNull.orEmpty() }
        val orderId = (data["orderid"] as? JsonPrimitive)?.contentOrNull
            ?: (data["orderId"] as? JsonPrimitive)?.contentOrNull
            ?: mapObj.keys.firstOrNull()
            ?: return@runCatching null
        // ccctype 实测是数组 [{balance:0, ccctype:"000"}]（balance 单位分）；
        // 兼容旧字符串形态
        val cccEl = data["ccctype"]
        var accountType: String? = null
        var balanceFen: Long? = null
        when (cccEl) {
            is kotlinx.serialization.json.JsonArray -> {
                val first = cccEl.firstOrNull() as? JsonObject
                accountType = (first?.get("ccctype") as? JsonPrimitive)?.contentOrNull
                balanceFen = (first?.get("balance") as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
                    ?: (first?.get("balance") as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong()
            }

            is JsonPrimitive -> accountType = cccEl.contentOrNull
            else -> {}
        }
        PowerPayChallenge(
            orderId = orderId,
            passwordMap = map,
            accountType = accountType,
            accountBalanceFen = balanceFen,
        )
    }.getOrNull()

    /** 订单状态（`getpayinfo` 的 `order.status`；0 待支付 / 1 已完成）。 */
    fun orderStatusFrom(raw: String): Int? = runCatching {
        val obj = json.parseToJsonElement(raw).jsonObject
        val order = obj["order"] as? JsonObject ?: return@runCatching null
        (order["status"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
    }.getOrNull()

    private fun dataOf(raw: String): JsonObject? = runCatching {
        (json.parseToJsonElement(raw).jsonObject["data"] as? JsonObject)
    }.getOrNull()
}
