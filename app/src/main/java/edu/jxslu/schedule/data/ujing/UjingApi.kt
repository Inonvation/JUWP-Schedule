package edu.jxslu.schedule.data.ujing

import edu.jxslu.schedule.data.qiekj.QiekjJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * U净 裸 OkHttp 客户端（DESIGN §4.37）。
 *
 * 形态与 [edu.jxslu.schedule.data.kqcx.KqcxAuthClient] 同款：私有构造 + `create()`、
 * 手写指纹头、解析交宽容序列化。**不用 Retrofit**：请求头分两组（鉴权前 ZI / 鉴权后
 * BI），按调用点显式切换比在拦截器里猜 URL 直白。
 *
 * 红线：
 * - **无日志拦截器**——token 绝不进 Logcat（对齐一卡通 / 电费 / 快趣）；
 * - 写操作零自动重试由仓库层把关，本类只负责发一次、如实抛错；
 * - token 不驻留本类（持久化归 [UjingSessionStore]，本类只见调用方传入的串）。
 */
class UjingApi private constructor(private val http: OkHttpClient) {

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        fun create(): UjingApi = UjingApi(
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build(),
        )
    }

    /** 短信验证码（60 秒冷却由调用方控制）。 */
    suspend fun requestCaptcha(mobile: String): UjingEnvelope = request(
        method = "GET",
        path = "captcha",
        identity = Identity.Account,
        query = mapOf(
            "mobile" to mobile,
            "type" to "1",
            "sessionId" to "AFS_SWITCH_OFF",
            "token" to "AFS_SWITCH_OFF",
            "sig" to "AFS_SWITCH_OFF",
        ),
    )

    /** 短信验证码登录 → `data.token`（JWT）。 */
    suspend fun login(mobile: String, captcha: String): UjingEnvelope = request(
        method = "POST",
        path = "login",
        identity = Identity.Account,
        body = buildJsonObject {
            put("mobile", mobile)
            put("captcha", captcha)
        },
    )

    /** 扫码识别设备。`qrCode` 必须传**二维码原始内容**（服务端自行解析，勿预提取）。 */
    suspend fun scanWasher(token: String, qrCode: String): UjingEnvelope = request(
        method = "POST",
        path = "devices/scanWasherCode",
        identity = Identity.Business,
        token = token,
        body = buildJsonObject { put("qrCode", qrCode) },
    )

    /** 设备套餐（洗涤模式 + 价格 + 时长 + storeId）。 */
    suspend fun programInfo(token: String, deviceId: String): UjingEnvelope = request(
        method = "GET",
        path = "app/washer/devices/program/info",
        identity = Identity.Business,
        token = token,
        query = mapOf("deviceId" to deviceId),
    )

    /** 附近洗衣房（注意平台把经度拼成 `lont`，照抄协议事实）。 */
    suspend fun storesNear(token: String, lat: Double, lng: Double): UjingEnvelope = request(
        method = "GET",
        path = "stores/near",
        identity = Identity.Business,
        token = token,
        query = mapOf(
            "lat" to lat.toString(),
            "lont" to lng.toString(),
            "scope" to "2000",
            "page" to "1",
            "size" to "100",
            "mode" to "BA",
        ),
    )

    /** 店内设备统计（看板一行一次调用）。 */
    suspend fun devicesReserve(token: String, storeId: String): UjingEnvelope = request(
        method = "GET",
        path = "devices/reserve",
        identity = Identity.Business,
        token = token,
        query = mapOf("storeId" to storeId),
    )

    // ── 订单链（P2） ──

    /**
     * 创建订单。body 字段全部来自扫码与套餐结果；机器必须用户亲手扫，不做
     * "不传 deviceId 由服务端自动匹配"的路径。
     *
     * 可选字段按「服务端声明需要才发」落（实机验证：五基础字段即可过）：
     * - `washTemperatureId`：仅洗衣机业务（type != 2）且机型开放水温时带；
     * - `wp_detergentGearId` / `wp_disinfectantGearId`：强制投放机型带标准档
     *   （档位枚举 1 = 洗涤剂标准、4 = 消毒液标准，社区逆向口径）；
     * - `dryTime`：仅烘干机（type == 2），协议事实 = 模式时长 / 10。
     */
    suspend fun createOrder(
        token: String,
        deviceId: String,
        deviceTypeId: Int,
        storeId: String,
        washModelId: Int,
        type: Int,
        washTemperatureId: Int? = null,
        detergentGearId: Int? = null,
        disinfectantGearId: Int? = null,
        dryTime: Int? = null,
    ): UjingEnvelope = request(
        method = "POST",
        path = "orders/create",
        identity = Identity.Business,
        token = token,
        body = buildJsonObject {
            put("type", type)
            put("deviceTypeId", deviceTypeId)
            put("deviceId", deviceId)
            put("deviceWashModelId", washModelId)
            put("storeId", storeId)
            // 温度仅洗衣机业务有；烘干机不传
            if (type != 2 && washTemperatureId != null) {
                put("washTemperatureId", washTemperatureId)
            }
            if (type != 2 && detergentGearId != null) {
                put("wp_detergentGearId", detergentGearId)
            }
            if (type != 2 && disinfectantGearId != null) {
                put("wp_disinfectantGearId", disinfectantGearId)
            }
            if (type == 2 && dryTime != null) {
                put("dryTime", dryTime)
            }
        },
    )

    /** 支付参数（channel=alipay；查询串字段照抄社区实测的完整形态）。 */
    suspend fun paymentArguments(token: String, orderId: String): UjingEnvelope = request(
        method = "GET",
        path = "payment/arguments",
        identity = Identity.Business,
        token = token,
        query = mapOf(
            "channel" to "alipay",
            "orderId" to orderId,
            "couponId" to "",
            "isUseRedPacket" to "false",
            "redPacketId" to "0",
            "alipayF2FNoAds" to "false",
            "branchType" to "0",
            "jumpToAliMini" to "false",
            "payVersion" to "1",
        ),
    )

    /**
     * 订单详情。`additional=price` 照抄官方抓包形态（社区实测用它取 `payPrice`）。
     */
    suspend fun orderDetail(token: String, orderId: String): UjingEnvelope = request(
        method = "GET",
        path = "orders/$orderId/detail",
        identity = Identity.Business,
        token = token,
        query = mapOf("additional" to "price"),
    )

    /** 取消订单（释放 2 分钟独占）。 */
    suspend fun cancelOrder(token: String, orderId: String): UjingEnvelope = request(
        method = "POST",
        path = "orders/$orderId/cancel",
        identity = Identity.Business,
        token = token,
        body = buildJsonObject { put("orderId", orderId) },
    )

    /** 最近一次支付状态（支付回跳后确认）。 */
    suspend fun lastPayStatus(token: String, orderId: String): UjingEnvelope = request(
        method = "GET",
        path = "app/payment/$orderId/lastPayStatus",
        identity = Identity.Business,
        token = token,
    )

    /** 云端控制：`orders/{id}/control/{action}`，action = start / stop / continue / restart。 */
    suspend fun control(token: String, orderId: String, action: String): UjingEnvelope = request(
        method = "GET",
        path = "orders/$orderId/control/$action",
        identity = Identity.Business,
        token = token,
    )

    private enum class Identity { Account, Business }

    private suspend fun request(
        method: String,
        path: String,
        identity: Identity,
        token: String? = null,
        query: Map<String, String> = emptyMap(),
        body: JsonObject? = null,
    ): UjingEnvelope = withContext(Dispatchers.IO) {
        val url = buildString {
            append(UjingApiConfig.BASE_URL).append('/').append(path)
            if (query.isNotEmpty()) {
                append('?')
                query.entries.forEachIndexed { index, (key, value) ->
                    if (index > 0) append('&')
                    append(key).append('=').append(URLEncoder.encode(value, "UTF-8"))
                }
            }
        }
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "*/*")
            .header("Accept-Language", "zh-Hans-CN;q=1")
            .header("x-user-geo", UjingApiConfig.USER_GEO_UNKNOWN)
            .header("x-mobile-brand", UjingApiConfig.MOBILE_BRAND)
            .header("x-mobile-model", UjingApiConfig.MOBILE_MODEL)
        when (identity) {
            Identity.Account -> builder
                .header("x-app-code", UjingApiConfig.APP_CODE_ACCOUNT)
                .header("x-app-version", UjingApiConfig.APP_VERSION_ACCOUNT)
                .header("User-Agent", UjingApiConfig.UA_ACCOUNT)

            Identity.Business -> builder
                .header("x-app-code", UjingApiConfig.APP_CODE_BUSINESS)
                .header("x-app-version", UjingApiConfig.APP_VERSION_BUSINESS)
                .header("weex-version", UjingApiConfig.WEEX_VERSION)
                .header("User-Agent", UjingApiConfig.UA_BUSINESS)
        }
        if (!token.isNullOrBlank()) builder.header("Authorization", "Bearer $token")
        when (method) {
            "POST" -> builder.post((body?.toString() ?: "{}").toRequestBody(JSON_MEDIA))
            else -> builder.get()
        }
        val (httpCode, text) = http.newCall(builder.build()).execute().use { response ->
            response.code to response.body?.string().orEmpty()
        }
        try {
            QiekjJson.json.decodeFromString(UjingEnvelope.serializer(), text)
        } catch (e: SerializationException) {
            throw UjingApiException("响应格式异常（HTTP $httpCode）", -1, e)
        }
    }
}
