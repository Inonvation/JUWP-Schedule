package edu.jxslu.schedule.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 快趣出行账号登录、骑行查询与用车动作的响应口径（DESIGN §4.32，2026-09-28）。
 *
 * 协议要点（真机/解包确认）：
 * - 后端 `https://api.kvcoogo.com/ManagerApi/api`，请求 form-encoded，**无签名**；
 * - 统一信封 `{resultCode, errorCode, resultMsg, result}`，`resultCode==1 && errorCode==0`
 *   才算业务成功，`result` 是业务数据；
 * - 登录 `POST /v1.0.0/userLoginByPassword`，参数 `{mobile, password: MD5(密码)}`（小写 hex）；
 * - 骑行中订单 `POST /v1.0.0/queryUnderwayOrder`（{}）；无订单时 `result` 为 null/空串/空对象，
 *   或 `errorCode == 12003`（「订单已结束」）。
 *
 * **字段解析一律走宽容取值**（2026-09-28 真机教训）：快趣后端的字段类型不稳定——数值可能给
 * 字符串（`"3665"`）或带小数点的 number（`85.0`）、布尔可能给 `0/1`。用严格 DTO 反序列化时，
 * 小程序开车后的真实订单会直接抛「骑行订单字段解析失败」；改成逐字段按 JsonPrimitive 取
 * content 再宽松转数，未知字段天然忽略。**不要再退回 `@Serializable` DTO 硬解**。
 *
 * 隐私红线：token 只在内存流转，不落盘不进日志（调用方约定，与一卡通/电费同口径）。
 */
object KqcxAuth {

    /** 信封判定：小程序 client 的口径逐字对齐（`resultCode===1 && errorCode===0`）。 */
    fun isSuccess(resultCode: Int?, errorCode: Int?): Boolean =
        resultCode == 1 && errorCode == 0

    /**
     * 这条业务错误像不像「token 失效/未登录」。
     *
     * 快趣未公开错误码表，解包里只见 30015（微信未绑定）一个硬编码；这里按
     * 「非成功 + 提示词含登录态字样」做保守启发式，宁可漏判（走正常错误提示）
     * 也不能把业务错误（如「车辆不存在」）误判成过期反复重登。
     */
    fun looksLikeTokenError(resultMsg: String?): Boolean {
        val msg = resultMsg ?: return false
        return listOf("登录", "token", "Token", "TOKEN", "鉴权", "未注册", "身份")
            .any { msg.contains(it) }
    }

    /** 密码的传输形态：标准 MD5 小写 hex（小程序 spark-md5 `hashStr` 同口径）。 */
    fun passwordCipher(plain: String): String = QzxyCredential.md5Hex(plain)

    // ---------- JSON 解析 ----------

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * 统一信封。`result` 用 [JsonElement] 原样接住：各接口的 result 形态不同
     * （对象 / null / 空串），先整体收下再各解析器自行判型——与胖乖 `EmptyData`
     * 的容错思路一致，但不在反序列化层做，保持解析口径集中在这里。
     */
    @Serializable
    private data class Envelope(
        @SerialName("resultCode") val resultCode: Int? = null,
        @SerialName("errorCode") val errorCode: Int? = null,
        @SerialName("resultMsg") val resultMsg: String? = null,
        @SerialName("result") val result: JsonElement? = null,
    )

    /** 只解码信封、不判成功失败（还车/未支付查询要自己处理业务错误码）。 */
    private fun decodeEnvelope(jsonText: String): Envelope = runCatching {
        json.decodeFromString<Envelope>(jsonText)
    }.getOrElse {
        throw KvcProtocolException("响应不是快趣的信封格式", it)
    }

    /** 信封解析的统一出口：非成功直接抛 [KvcBusinessError]（带 errorCode 与原文）。 */
    private fun parseEnvelope(jsonText: String): Envelope = decodeEnvelope(jsonText).also { env ->
        if (!isSuccess(env.resultCode, env.errorCode)) {
            throw KvcBusinessError(env.errorCode, env.resultMsg ?: "请求错误")
        }
    }

    // ---------- 宽容取值（见文件头说明） ----------

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    /** 数值宽容：`3665` / `"3665"` / `3665.0` / `"3665.7"` 都收。 */
    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toLong()

    private fun JsonObject.double(key: String): Double? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()

    private fun JsonObject.int(key: String): Int? = long(key)?.toInt()

    /** 布尔宽容：`true` / `"true"` / `1` / `"1"` 都收。 */
    private fun JsonObject.bool(key: String): Boolean? =
        when ((this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()) {
            "1", "true" -> true
            "0", "false" -> false
            else -> null
        }

    // ---------- 登录 ----------

    /** 登录成功后的会话材料。 */
    data class LoginResult(val token: String, val mobile: String)

    /**
     * 解析 `userLoginByPassword` 响应。token 为空（信封成功但 result 缺 token）按业务错误处理。
     */
    fun parseLogin(jsonText: String): LoginResult {
        val env = parseEnvelope(jsonText)
        val obj = env.result as? JsonObject
        val token = obj?.text("token")
        if (token.isNullOrBlank()) throw KvcBusinessError(env.errorCode, "登录成功但未返回会话")
        return LoginResult(token = token, mobile = obj.text("mobile").orEmpty())
    }

    // ---------- 骑行中订单 ----------

    /**
     * 骑行中订单（也是开锁成功后的状态载体）。字段名来自小程序骑行页的响应消费代码
     * （`queryRuningOrder().then`）：`carNum` / `bluetoothName` / `totalDate`（**已骑秒数**）/
     * `payMoney`（**当前费用，分**）/ `lat` / `lng` / `lockStatus`（1 = 已锁）/
     * `currentPercent`（电量）。
     */
    data class Ride(
        val carNum: String,
        val bluetoothName: String?,
        /** 已骑时长（秒）；响应缺该字段时为 null（旧口径兼容）。 */
        val totalDateSeconds: Long? = null,
        /** 当前费用（分）；免费时段内为 0。 */
        val payMoneyCents: Long? = null,
        /** 车辆当前纬度（GCJ-02，快趣坐标系与地图瓦片同源——若用于地图绘制不要过 Gcj02）。 */
        val lat: Double? = null,
        /** 车辆当前经度。 */
        val lng: Double? = null,
        /** 是否已锁车（1 = 锁上，锁上不等于订单结束）。 */
        val locked: Boolean? = null,
        /** 车辆电量百分比。 */
        val batteryPercent: Int? = null,
    )

    /** 解包确认的无进行中订单错误码（小程序 `case 12003: processEndOrder()`）。 */
    private const val NO_ORDER_ERROR_CODE = 12003

    /** 解包确认的无未支付订单错误码（小程序扣款确认循环 `12004==x.code` 即已结清）。 */
    private const val NO_UNPAY_ORDER_ERROR_CODE = 12004

    /**
     * 微信支付分「下单授权」错误码（2026-09-28 真机 + 解包双重确认）：
     * 服务端在需要支付分授权时返回它 + `result.package`，小程序跳 preview 页调
     * `wx.openBusinessView({businessType:"wxpayScoreUse"})`——**微信客户端专属 API，
     * 第三方 App 无法代调**（App SDK 需商户侧配置，水贝贝不是快趣的 App）。
     * 账号走「支付分免押」时每笔订单都会命中；出路只有两条：微信内用车，
     * 或（用户自行）缴纳诚信金切换保证金模式。
     */
    const val CODE_SCORE_AUTH_REQUIRED = 11035

    /**
     * 解析 `queryUnderwayOrder` 响应。无骑行订单的两种形态都返回 null：
     * ① `result` 为 null / 空串 / 空对象（宽容处理）；② **errorCode == 12003**。
     * `result` 缺 `carNum` 也不当骑行在案（可能是别的形态占位对象）。
     */
    fun parseUnderway(jsonText: String): Ride? = try {
        parseUnderwayResult(jsonText)
    } catch (error: KvcBusinessError) {
        if (error.errorCode == NO_ORDER_ERROR_CODE) null else throw error
    }

    private fun parseUnderwayResult(jsonText: String): Ride? {
        val env = parseEnvelope(jsonText)
        val obj = env.result as? JsonObject ?: return null
        if (obj.isEmpty()) return null
        val carNum = obj.text("carNum") ?: return null
        return Ride(
            carNum = carNum,
            bluetoothName = obj.text("bluetoothName"),
            totalDateSeconds = obj.long("totalDate"),
            payMoneyCents = obj.long("payMoney"),
            lat = obj.double("lat"),
            lng = obj.double("lng"),
            locked = obj.bool("lockStatus"),
            batteryPercent = obj.int("currentPercent"),
        )
    }

    // ---------- 创建订单（开锁第一步） ----------

    /** 创建订单的结果：车号 + 是否走头盔流程（官方 `1==helmet && 1==helmetConfig`）。 */
    data class CreatedOrder(val carNum: String, val helmetFlowRequired: Boolean)

    /**
     * 解析 `createOrder` 响应。车号与头盔标志是 App 需要的全部——
     * 其余字段（蓝牙名/电量/订阅消息 subList 等）官方在骑行页刷新时会再查，不必在此消费。
     */
    fun parseCreatedOrder(jsonText: String): CreatedOrder {
        val env = parseEnvelope(jsonText)
        val obj = env.result as? JsonObject ?: throw KvcProtocolException("创建订单响应缺 result")
        val carNum = obj.text("carNum") ?: throw KvcProtocolException("创建订单响应缺车号")
        return CreatedOrder(
            carNum = carNum,
            helmetFlowRequired = obj.long("helmet") == 1L && obj.long("helmetConfig") == 1L,
        )
    }

    // ---------- 还车 ----------

    /** 还车结果：成功（含支付状态）或被拒（含原因与可能的调度费）。 */
    sealed interface EndOutcome {
        /**
         * 订单已结束。[needPay] / [wechatScore] 为 null = 官方未告知
         * （如 12003 已在别处结束），调用方不要据此断言「无需支付」。
         */
        data class Ended(val needPay: Boolean?, val wechatScore: Boolean?) : EndOutcome

        /**
         * 被服务端拒绝。[code] 见 [errorMessage]；[dispatchMoneyCents] 非空 =
         * 官方要求支付调度费（小程序走二次确认面板，App 不代用户接受，降级到官方渠道）。
         */
        data class Rejected(
            val code: Int?,
            val message: String,
            val dispatchMoneyCents: Long?,
        ) : EndOutcome
    }

    /**
     * 解析 `endTheOrder` 响应。**不抛业务异常**：拒绝也是一种合法结果
     * （调用方要按 code/调度费分流：50011 出围栏、dispatchMoney>0 调度费）。
     */
    fun parseEndOrder(jsonText: String): EndOutcome {
        val env = decodeEnvelope(jsonText)
        if (isSuccess(env.resultCode, env.errorCode)) {
            val obj = env.result as? JsonObject
            return EndOutcome.Ended(
                needPay = obj?.bool("needPay"),
                wechatScore = obj?.bool("wechatScore"),
            )
        }
        val data = env.result as? JsonObject
        return EndOutcome.Rejected(
            code = env.errorCode,
            message = env.resultMsg ?: "还车失败",
            dispatchMoneyCents = data?.long("dispatchMoney"),
        )
    }

    // ---------- 未支付订单（还车后的扣款确认） ----------

    /** `queryUnPayOrder` 的结果。 */
    sealed interface UnpayState {
        /** 已结清（12004 = 无未支付订单，或金额为 0）。 */
        data object Settled : UnpayState

        /** 仍有未支付金额（分）。 */
        data class Owed(val amountCents: Long) : UnpayState
    }

    fun parseUnpayState(jsonText: String): UnpayState {
        val env = decodeEnvelope(jsonText)
        if (isSuccess(env.resultCode, env.errorCode)) {
            val owed = (env.result as? JsonObject)?.long("unPayMoney") ?: 0L
            return if (owed > 0) UnpayState.Owed(owed) else UnpayState.Settled
        }
        if (env.errorCode == NO_UNPAY_ORDER_ERROR_CODE) return UnpayState.Settled
        throw KvcBusinessError(env.errorCode, env.resultMsg ?: "查询未支付订单失败")
    }

    // ---------- 错误码文案 ----------

    /**
     * 官方错误码 → 用户可读文案（逐条对齐小程序的分支文案；表外的给 [fallback]）。
     * 只收「用户能采取行动」的码；未知码不编解释，交给服务端原文。
     */
    fun errorMessage(code: Int?, fallback: String): String = when (code) {
        11003 -> "账号已冻结，请联系快趣客服"
        11004 -> "请先在快趣完成实名认证"
        11005 -> "实名认证审核中，请耐心等待"
        11006 -> "实名认证未通过，请重新提交"
        11007 -> "请先缴纳诚信金（在快趣官方渠道）"
        11008 -> "请先撤销诚信金退款申请"
        11010 -> "请先撤销余额退款申请"
        11035 -> "该账号为微信支付分免押：开锁需在微信内完成支付分授权（微信限制，App 无法代做）" +
            "——可联系快趣客服关闭该授权后本机直接开锁，或用「去微信扫一扫」在微信内用车"
        12001 -> "有一笔未完成的订单，请在快趣官方渠道处理"
        12002 -> "有未支付的订单，请先在快趣官方渠道支付"
        12022 -> "已有进行中的订单"
        12009, 11034 -> "订单创建失败，请重试"
        11009, 12013, 12014 -> "账户余额不足，请先充值（快趣官方渠道）"
        20001, 20002 -> "登录已失效，请重新登录"
        16008 -> "暂无开锁权限"
        16011 -> "禁行区内不允许开锁"
        16015 -> "请先取下头盔（官方面板开启头盔锁）"
        50011 -> "当前位置不在还车区域内，请移动到还车点后再还车"
        999, 1000 -> "网络异常，请稍后重试"
        else -> fallback
    }

    /**
     * 已骑时长文案，**逐字对齐**小程序 `simplehumantime`：输入秒 → `ceil` 成分钟数 →
     * 分钟数直接填进「分」段（`00:mm`），超 1 小时变成 `00:hh:mm`——首段恒为 00，这是
     * 小程序的怪渲染（骑 1 分钟显示 `00:01`、骑 65 分钟显示 `00:01:05`），照抄它保证
     * 与快趣官方展示一致。null 透传 null（响应缺字段时 UI 显示占位）。
     */
    fun formatRideDuration(totalSeconds: Long?): String? {
        if (totalSeconds == null) return null
        val minutes = Math.ceil(totalSeconds.coerceAtLeast(0) / 60.0).toLong()
        val h = minutes / 60
        val m = minutes % 60
        return if (h > 0) "00:%02d:%02d".format(h, m) else "00:%02d".format(m)
    }
}

/**
 * 快趣后端返回的业务错误（信封非成功）。
 *
 * [dispatchMoneyCents] 是还车接口专用：官方要求支付调度费时非空（小程序走确认面板），
 * App 不代用户接受，由调用方降级到官方渠道。
 */
class KvcBusinessError(
    val errorCode: Int?,
    message: String,
    val dispatchMoneyCents: Long? = null,
) : Exception(message)

/** 响应形态与预期不符（信封/字段缺失）——属于协议变更或脏数据。 */
class KvcProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)
