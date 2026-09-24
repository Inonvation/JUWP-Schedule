package edu.jxslu.schedule.data.power

import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 缴费平台 HTTP 客户端（DESIGN §4.24）。裸 OkHttp，只做请求与状态码归一。
 *
 * 红线：**无任何日志拦截器**（凭证/token 不进 logcat）；不做自动重试（失败即停，防撞风控）。
 * 登录端点在**根域** `/blade-auth/oauth/token`，业务接口在 `/charge/`——两者都在本类的
 * [BASE] 之下，路径由调用方给全。
 */
class PowerClient private constructor(private val http: OkHttpClient) {

    companion object {
        const val BASE = "https://charge.juwp.edu.cn"

        /** 前端 bundle 里的 OAuth2 客户端凭据（所有人可见，非机密）。 */
        private const val BASIC = "Basic Y2hhcmdlOmNoYXJnZV9zZWNyZXQ="

        /** 该学校的登录方式（`GET /charge/logintype` 取值）：学号 + 查询密码。 */
        private const val LOGIN_TYPE = "student-sno-queryPassword"

        private const val UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36"

        /** 移动服务平台（ydfwpt）打开本平台的口径：token 走 URL，收银台回跳走 paymentUrl。 */
        private const val APPSOURSE = "ydfwpt"
        private const val PAYMENT_URL = "https://yktwx.juwp.edu.cn/plat"

        fun create(): PowerClient = PowerClient(
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build(),
        )

        /**
         * 平台页面深链（DESIGN §3.13）：把 App 现登的 token 放进 URL，前端按 `token` 参数
         * 直接登录（与微信小程序同一口径，2026-09-23 实测可用）。
         *
         * [route] 是前端路由（`#` 之后那段）：`/pays?id=181` = 房间电费缴费页，
         * `/bill` = 缴费账单（按月总支出）。无效路由会被前端打回首页，所以只给实测过的值。
         *
         * token 有效期 3599 秒，只在这一跳里出现，不落盘、不进日志。
         */
        fun pageUrl(token: String, route: String): String {
            val payment = URLEncoder.encode(PAYMENT_URL, "UTF-8")
            return "$BASE/?appsourse=$APPSOURSE&paymentUrl=$payment&token=$token#$route"
        }

        /** 房间电费缴费页（电费充值）。 */
        fun payPageUrl(token: String, feeItemId: Int): String = pageUrl(token, "/pays?id=$feeItemId")

        /** 缴费账单页（按月总支出，2026-09-23 实测）。 */
        fun billPageUrl(token: String): String = pageUrl(token, "/bill")
    }

    /** 一次请求的原始结果（HTTP 码 + 响应文本；平台业务码在文本里的 `code` 字段）。 */
    data class Raw(val httpCode: Int, val text: String)

    /** 登录：学号 + 查询密码 → access_token（有效期 3599 秒）。 */
    suspend fun login(username: String, password: String): Raw = withContext(Dispatchers.IO) {
        val body = formBody(
            mapOf(
                "username" to username,
                "password" to password,
                "grant_type" to "password",
                "scope" to "all",
                "logintype" to LOGIN_TYPE,
            ),
        )
        execute(
            Request.Builder()
                .url("$BASE/blade-auth/oauth/token")
                .header("User-Agent", UA)
                .header("Authorization", BASIC)
                .post(body),
        )
    }

    /** GET（`/charge/` 的业务查询）。 */
    suspend fun get(path: String, token: String, params: Map<String, String> = emptyMap()): Raw =
        withContext(Dispatchers.IO) {
            val query = params.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
            val url = BASE + path + if (query.isEmpty()) "" else "?$query"
            execute(
                Request.Builder()
                    .url(url)
                    .header("User-Agent", UA)
                    .header("Authorization", BASIC)
                    .header("synjones-auth", "bearer $token"),
            )
        }

    /** POST 表单（`/charge/`）。 */
    suspend fun postForm(path: String, token: String, fields: Map<String, String>): Raw =
        withContext(Dispatchers.IO) {
            execute(
                Request.Builder()
                    .url(BASE + path)
                    .header("User-Agent", UA)
                    .header("Authorization", BASIC)
                    .header("synjones-auth", "bearer $token")
                    .post(formBody(fields)),
            )
        }

    /**
     * POST JSON body（`/charge/order/deleteOrder` 专用）。
     *
     * 2026-09-24 实测：删单端点**只认 JSON body**——表单（无论带不带 SIGN）一律
     * `500 未知异常`；`Content-Type: application/json` + `{"orderid":…}` 即成功。
     * 这是全项目唯一一处 JSON body 请求，单独开方法不放 [postForm]。
     */
    suspend fun postJson(path: String, token: String, body: JsonObject): Raw =
        withContext(Dispatchers.IO) {
            execute(
                Request.Builder()
                    .url(BASE + path)
                    .header("User-Agent", UA)
                    .header("Authorization", BASIC)
                    .header("synjones-auth", "bearer $token")
                    .post(body.toString().toRequestBody("application/json".toMediaType())),
            )
        }

    /**
     * POST 签名表单（`/blade-pay/pay` 下单与支付）。鉴权走 **header**
     * （2026-09-23 实测：表单字段形式报「未知异常」），与 [login] 的 Basic 头不同。
     */
    suspend fun postSigned(path: String, token: String, fields: Map<String, String>): Raw =
        withContext(Dispatchers.IO) {
            execute(
                Request.Builder()
                    .url(BASE + path)
                    .header("User-Agent", UA)
                    .header("Authorization", BASIC)
                    .header("synjones-auth", "bearer $token")
                    .header("synAccessSource", "h5")
                    .post(formBody(fields)),
            )
        }


    private fun formBody(fields: Map<String, String>) = fields.entries
        .joinToString("&") { (k, v) -> "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}" }
        .toRequestBody("application/x-www-form-urlencoded".toMediaType())

    private fun execute(builder: Request.Builder): Raw = try {
        http.newCall(builder.build()).execute().use { resp ->
            Raw(resp.code, resp.body?.string().orEmpty().removePrefix("\uFEFF"))
        }
    } catch (e: java.io.IOException) {
        throw PowerException.Network(e)
    }
}
