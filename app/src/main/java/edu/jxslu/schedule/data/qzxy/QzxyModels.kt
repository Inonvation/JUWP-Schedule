package edu.jxslu.schedule.data.qzxy

import edu.jxslu.schedule.data.qiekj.LenientStringSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * 趣智校园接口的响应包、线上 DTO 与业务异常（DESIGN §4.30）。
 *
 * 脏数据容错直接复用胖乖那套 [LenientStringSerializer]（同一工程里第二个会返回
 * 「数字当字符串」的第三方接口）。`data` 统一留成 [JsonElement]，由仓库层二次解码，
 * 这样单条接口返回结构与失败结构不一致时也不会把整个 envelope 解崩。
 */

@Serializable
data class QzxyEnvelope(
    val success: Boolean = false,
    val errorCode: Int = -1,
    val errorMessage: String? = null,
    val message: String? = null,
    val data: JsonElement? = null,
) {
    /** 服务端错误文案，两个字段名都可能出现。 */
    val text: String? get() = message ?: errorMessage

    /** 失败即抛；会话失效抛 [QzxySessionExpiredException]，其余抛 [QzxyApiException]。 */
    fun requireSuccess() {
        if (success && errorCode == 0) return
        val message = text ?: "请求失败（$errorCode）"
        if (QzxySessionExpiredException.matches(errorCode, message)) {
            throw QzxySessionExpiredException(message)
        }
        throw QzxyApiException(message, errorCode)
    }
}

class QzxyApiException(
    message: String,
    val errorCode: Int = -1,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * 登录态失效。判定沿用胖乖同款启发式（HTTP 401/403 + 文案关键词），
 * **但显式排除含「签名」的文案**：蓝牙接口签名不通过时的措辞也带「过期」，
 * 那不是会话问题，不能把用户踢去重新登录。
 */
class QzxySessionExpiredException(
    message: String = "登录已失效，请重新登录",
    cause: Throwable? = null,
) : Exception(message, cause) {
    companion object {
        fun matches(code: Int?, message: String?): Boolean {
            if (code == 401 || code == 403) return true
            val msg = message ?: return false
            if (msg.contains("签名")) return false
            if (msg.contains("未登录") || msg.contains("未登陆")) return true
            if (msg.contains("请先登录") || msg.contains("请先登陆")) return true
            if (msg.contains("登录") && (msg.contains("过期") || msg.contains("失效"))) return true
            if (msg.contains("loginCode") && msg.contains("失效")) return true
            return false
        }
    }
}

// ── 登录 / 账号 ──

/**
 * 登录响应。两个形态并存：顶层直接给 `projectId/accountId`（旧），
 * 或包一层 `userAccount`（新）。仓库层 [QzxySession.from] 统一收敛。
 */
@Serializable
data class QzxyLoginData(
    @Serializable(LenientStringSerializer::class) val loginCode: String? = null,
    @Serializable(LenientStringSerializer::class) val v3LoginCode: String? = null,
    @Serializable(LenientStringSerializer::class) val telephone: String? = null,
    @Serializable(LenientStringSerializer::class) val telPhone: String? = null,
    @Serializable(LenientStringSerializer::class) val userId: String? = null,
    @Serializable(LenientStringSerializer::class) val v3UserId: String? = null,
    @Serializable(LenientStringSerializer::class) val accountId: String? = null,
    @Serializable(LenientStringSerializer::class) val projectId: String? = null,
    @Serializable(LenientStringSerializer::class) val name: String? = null,
    val userAccount: QzxyLoginData? = null,
)

@Serializable
data class QzxyBalance(
    @Serializable(LenientStringSerializer::class) val money: String? = null,
    @Serializable(LenientStringSerializer::class) val accountRealMoney: String? = null,
    @Serializable(LenientStringSerializer::class) val accountGivenMoney: String? = null,
) {
    val text: String get() = money ?: accountRealMoney ?: "-"
}

@Serializable
data class QzxyProjectInfo(
    @Serializable(LenientStringSerializer::class) val projectId: String? = null,
    @Serializable(LenientStringSerializer::class) val projectName: String? = null,
    @Serializable(LenientStringSerializer::class) val projectDescription: String? = null,
)

// ── 设备 ──

/**
 * `device/info/mac` 的响应。字段名取自看雪实测；
 * `onlineStatusId` 与 `communicationTypeId` 用来判断设备是不是联网型。
 */
@Serializable
data class QzxyDeviceInfo(
    @Serializable(LenientStringSerializer::class) val deviceId: String? = null,
    @Serializable(LenientStringSerializer::class) val deviceName: String? = null,
    @Serializable(LenientStringSerializer::class) val deviceDesc: String? = null,
    @Serializable(LenientStringSerializer::class) val snCode: String? = null,
    @Serializable(LenientStringSerializer::class) val macAddress: String? = null,
    @Serializable(LenientStringSerializer::class) val bigTypeId: String? = null,
    @Serializable(LenientStringSerializer::class) val smallTypeId: String? = null,
    @Serializable(LenientStringSerializer::class) val bigTypeName: String? = null,
    @Serializable(LenientStringSerializer::class) val onlineStatusId: String? = null,
    @Serializable(LenientStringSerializer::class) val communicationTypeId: String? = null,
    @Serializable(LenientStringSerializer::class) val isMigrated: String? = null,
    @Serializable(LenientStringSerializer::class) val buildingName: String? = null,
    @Serializable(LenientStringSerializer::class) val floorName: String? = null,
    @Serializable(LenientStringSerializer::class) val roomName: String? = null,
    @Serializable(LenientStringSerializer::class) val projectName: String? = null,
    @Serializable(LenientStringSerializer::class) val withholdMoney: String? = null,
)

// ── 订单 ──

/**
 * 蓝牙下单响应。真正的开阀数据是 [downData]（十六进制文本，原样写进设备），
 * [randomNumber] 是服务端回签的随机数，[autoDisConTime] 是自动关停秒数。
 */
@Serializable
data class QzxyRateOrderData(
    @Serializable(LenientStringSerializer::class) val downData: String? = null,
    @Serializable(LenientStringSerializer::class) val randomNumber: String? = null,
    @Serializable(LenientStringSerializer::class) val autoDisConTime: String? = null,
    @Serializable(LenientStringSerializer::class) val preDeductMoney: String? = null,
    @Serializable(LenientStringSerializer::class) val preDeductMoneySend: String? = null,
    @Serializable(LenientStringSerializer::class) val orderNo: String? = null,
    @Serializable(LenientStringSerializer::class) val rate: String? = null,
    @Serializable(LenientStringSerializer::class) val chargeMethod: String? = null,
    @Serializable(LenientStringSerializer::class) val consumeDate: String? = null,
    @Serializable(LenientStringSerializer::class) val useCount: String? = null,
)

/** 消费数据上报的结算响应。`consumeMoney` 单位是**厘**，展示前除 1000。 */
@Serializable
data class QzxyConsumeResult(
    @Serializable(LenientStringSerializer::class) val consumeMoney: String? = null,
    @Serializable(LenientStringSerializer::class) val preDeductMoney: String? = null,
    @Serializable(LenientStringSerializer::class) val preDeductMoneyAfter: String? = null,
    @Serializable(LenientStringSerializer::class) val orderNo: String? = null,
    @Serializable(LenientStringSerializer::class) val deviceSnCode: String? = null,
    @Serializable(LenientStringSerializer::class) val consumeTime: String? = null,
    @Serializable(LenientStringSerializer::class) val clData: String? = null,
)

@Serializable
data class QzxyBillList(
    val consumeBillDTO: QzxyBill? = null,
)

@Serializable
data class QzxyBill(
    @Serializable(LenientStringSerializer::class) val orderNo: String? = null,
    @Serializable(LenientStringSerializer::class) val consumeDate: String? = null,
    @Serializable(LenientStringSerializer::class) val consumeMoney: String? = null,
    @Serializable(LenientStringSerializer::class) val description: String? = null,
    @Serializable(LenientStringSerializer::class) val deviceDescription: String? = null,
)
