package edu.jxslu.schedule.data.ujing

import edu.jxslu.schedule.data.qiekj.LenientStringSerializer
import edu.jxslu.schedule.data.qiekj.QiekjJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * U净接口的响应包、DTO 与业务异常（DESIGN §4.37）。
 *
 * 响应壳是 `{code, message, data}`（`code == 0` 成功，成功时 `data` 才是负载）。
 * 该服务端**不用手写加密或签名**（客户端只带指纹头 + Bearer token），但脏数据容错
 * 仍复用胖乖那套宽容序列化（[LenientStringSerializer] / [QiekjJson]）：设备统计、
 * 价格、时长这些数值字段以字符串形态出现不稀奇，一律宽容读。
 */

@Serializable
data class UjingEnvelope(
    val code: Int = -1,
    val message: String? = null,
    val data: JsonElement? = null,
) {
    /** 失败即抛；会话失效抛 [UjingSessionExpiredException]，其余抛 [UjingApiException]。 */
    fun requireSuccess() {
        if (code == 0) return
        val text = message?.takeIf { it.isNotBlank() } ?: "请求失败（$code）"
        if (UjingSessionExpiredException.matches(code, message)) {
            throw UjingSessionExpiredException(text)
        }
        throw UjingApiException(text, code)
    }
}

/** 宽容解码 `data` 为具体 DTO；结构对不上返回 null（是否当错误由调用方定）。 */
inline fun <reified T> UjingEnvelope.decodeData(): T? {
    val payload = data ?: return null
    return try {
        QiekjJson.json.decodeFromJsonElement<T>(payload)
    } catch (_: SerializationException) {
        null
    }
}

class UjingApiException(
    message: String,
    val code: Int = -1,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * 登录态失效。判定：业务码 `401` / `-99`（社区实测的会话过期返回），
 * 外加文案兜底（未登录 / 登录过期）。
 */
class UjingSessionExpiredException(
    message: String = "登录已过期，请重新登录",
    cause: Throwable? = null,
) : Exception(message, cause) {
    companion object {
        fun matches(code: Int?, message: String?): Boolean {
            if (code == 401 || code == -99) return true
            val msg = message ?: return false
            if (msg.contains("未登录") || msg.contains("未登陆")) return true
            if (msg.contains("请先登录") || msg.contains("请先登陆")) return true
            if (msg.contains("登录") && (msg.contains("过期") || msg.contains("失效"))) return true
            return false
        }
    }
}

/** 数值宽容：字符串 / 数字都读 Int（`"2"`、`2`、`"2.0"` 均可），读不出给 0。 */
object LenientIntSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("LenientInt", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder): Int {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeInt()
        val element = jsonDecoder.decodeJsonElement()
        val text = (element as? JsonPrimitive)?.content ?: return 0
        return text.toIntOrNull() ?: text.toDoubleOrNull()?.toInt() ?: 0
    }

    override fun serialize(encoder: Encoder, value: Int) = encoder.encodeInt(value)
}

/** 布尔宽容：`true` / `1` / `"true"` / `"1"` 为真，其余为假。 */
object LenientBoolSerializer : KSerializer<Boolean> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("LenientBool", PrimitiveKind.BOOLEAN)

    override fun deserialize(decoder: Decoder): Boolean {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeBoolean()
        val element = jsonDecoder.decodeJsonElement()
        val text = (element as? JsonPrimitive)?.content ?: return false
        return text.equals("true", ignoreCase = true) || text == "1"
    }

    override fun serialize(encoder: Encoder, value: Boolean) = encoder.encodeBoolean(value)
}

// ── 登录 ──

@Serializable
data class UjingLoginData(
    @Serializable(LenientStringSerializer::class) val token: String? = null,
    @Serializable(LenientStringSerializer::class) val userId: String? = null,
)

// ── 扫码识别 ──

/** `devices/scanWasherCode` 的 data：设备信息包在 `result` 下。 */
@Serializable
data class UjingScanData(val result: UjingScanResult? = null)

@Serializable
data class UjingScanResult(
    @Serializable(LenientStringSerializer::class) val deviceId: String? = null,
    @Serializable(LenientIntSerializer::class) val deviceTypeId: Int = 0,
    @Serializable(LenientIntSerializer::class) val moduleType: Int = -1,
    @Serializable(LenientStringSerializer::class) val macAddress: String? = null,
    @Serializable(LenientBoolSerializer::class) val createOrderEnabled: Boolean = false,
    @Serializable(LenientStringSerializer::class) val status: String? = null,
    @Serializable(LenientStringSerializer::class) val reason: String? = null,
)

// ── 设备套餐 ──

@Serializable
data class UjingProgramData(
    @Serializable(LenientStringSerializer::class) val deviceId: String? = null,
    @Serializable(LenientStringSerializer::class) val deviceNo: String? = null,
    @Serializable(LenientIntSerializer::class) val deviceTypeId: Int = 0,
    val deviceTypeName: String = "",
    @Serializable(LenientStringSerializer::class) val storeId: String? = null,
    val storeName: String = "",
    @Serializable(LenientIntSerializer::class) val moduleType: Int = -1,
    /** 设备业务类型（洗衣机 1 / 烘干机 2，下单 body 的 `type` 字段）。 */
    @Serializable(LenientIntSerializer::class) val type: Int = 1,
    /** 该机型是否开放水温选择（true 时下单 body 要带 `washTemperatureId`）。 */
    @Serializable(LenientBoolSerializer::class) val isWashTemperatureEnable: Boolean = false,
    /** 机型强制投放洗涤剂（true 时下单 body 要带 `wp_detergentGearId`）。 */
    @Serializable(LenientBoolSerializer::class) val isForceDetergent: Boolean = false,
    /** 机型强制投放消毒液（true 时下单 body 要带 `wp_disinfectantGearId`）。 */
    @Serializable(LenientBoolSerializer::class) val isForceDisinfectant: Boolean = false,
    val deviceWashModel: List<UjingWashModel> = emptyList(),
)

/** 一条洗涤模式（价格单位分、时长单位分钟；服务端动态下发，**禁止在客户端写死**）。 */
@Serializable
data class UjingWashModel(
    @Serializable(LenientIntSerializer::class) val workModelId: Int = 0,
    val workModelName: String = "",
    @Serializable(LenientIntSerializer::class) val basePrice: Int = 0,
    @Serializable(LenientIntSerializer::class) val time: Int = 0,
    @Serializable(LenientBoolSerializer::class) val hide: Boolean = false,
)

// ── 订单（P2） ──

/** `orders/create` 的 data：下单成功返回订单 id（2 分钟独占期自此开始）。 */
@Serializable
data class UjingOrderCreateData(
    @Serializable(LenientStringSerializer::class) val orderId: String? = null,
    @Serializable(LenientStringSerializer::class) val orderNo: String? = null,
)

/**
 * `payment/arguments?channel=alipay` 的 data：`payInfo.orderInfo` 是支付宝 SDK 的
 * orderInfo 串（服务端签发，App 不经手资金）。
 */
@Serializable
data class UjingPayArgsData(
    val payInfo: UjingPayInfo? = null,
)

@Serializable
data class UjingPayInfo(
    @Serializable(LenientStringSerializer::class) val orderInfo: String? = null,
)

/** `orders/{id}/detail` 的 data（宽容解析：数值字段可能是字符串）。 */
@Serializable
data class UjingOrderDetailData(
    @Serializable(LenientStringSerializer::class) val orderId: String? = null,
    @Serializable(LenientStringSerializer::class) val orderNo: String? = null,
    @Serializable(LenientStringSerializer::class) val deviceId: String? = null,
    @Serializable(LenientStringSerializer::class) val deviceNo: String? = null,
    @Serializable(LenientStringSerializer::class) val deviceTypeName: String? = null,
    /** 状态是**字符串**（"40"）。 */
    @Serializable(LenientStringSerializer::class) val status: String? = null,
    val statusRemark: String? = null,
    @Serializable(LenientStringSerializer::class) val payPrice: String? = null,
    /** 剩余秒数（运行中的订单才有意义）。 */
    @Serializable(LenientIntSerializer::class) val remainTime: Int = 0,
    /** 支付标记（0 未付 / 1 已付）。 */
    @Serializable(LenientIntSerializer::class) val payFlag: Int = 0,
    @Serializable(LenientStringSerializer::class) val cycle: String? = null,
    @Serializable(LenientStringSerializer::class) val storeName: String? = null,
)

/** `app/payment/{id}/lastPayStatus` 的 data：支付回跳后的确认查询。 */
@Serializable
data class UjingPayStatusData(
    @Serializable(LenientIntSerializer::class) val payFlag: Int = 0,
    @Serializable(LenientStringSerializer::class) val status: String? = null,
)

/** 云端控制（`orders/{id}/control/start`）的受理响应：内层 `errorCode` 决定成败。 */
@Serializable
data class UjingControlData(
    @Serializable(LenientIntSerializer::class) val errorCode: Int = -1,
    val errorMessage: String? = null,
)

// ── 附近洗衣房 ──

@Serializable
data class UjingStoreListData(val storeList: List<UjingStore> = emptyList())

@Serializable
data class UjingStore(
    @Serializable(LenientStringSerializer::class) val id: String? = null,
    val name: String = "",
) {
    /** 完整可用的一条门店（id 与名称都有）。 */
    val isComplete: Boolean get() = !id.isNullOrBlank() && name.isNotBlank()
}

// ── 空闲看板 ──

@Serializable
data class UjingReserveData(val devices: List<UjingReserveDevice> = emptyList())

@Serializable
data class UjingReserveDevice(val device: UjingDeviceGroup? = null)

@Serializable
data class UjingDeviceGroup(
    val deviceTypeName: String = "",
    @Serializable(LenientIntSerializer::class) val free: Int = 0,
    @Serializable(LenientIntSerializer::class) val total: Int = 0,
    @Serializable(LenientIntSerializer::class) val waitTime: Int = 0,
)
