package edu.jxslu.schedule.data.wenqu

import edu.jxslu.schedule.data.net.AppHttp
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

/**
 * 问渠实践教育平台 裸 OkHttp 客户端（DESIGN §4.45）。
 *
 * 形态对齐 [edu.jxslu.schedule.data.ujing.UjingApi]：私有构造 + `create()`、手写请求头、
 * 解析交宽容序列化。**不用 Retrofit**——一共五个端点、无 query 参数，裸 OkHttp 更直白。
 *
 * 红线：
 * - **无日志拦截器**——手机号 / 密码 / token 绝不进 Logcat（对齐一卡通 / 电费 / 快趣 / U净）；
 * - token 不驻留本类（持久化归 [WenquCredentialStore]），本类只见调用方传入的串；
 * - 只发读接口 + 用户显式触发的登录/设密码；**报名 / 签到 / 提交类写接口一律不碰**。
 */
class WenquApi private constructor(private val http: OkHttpClient) {

    companion object {
        /** 平台根（与小程序 `HTTP_REQUEST_URL` 逐字一致）。 */
        const val BASE = "https://wenqu.juwp.edu.cn/api/"

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        fun create(): WenquApi = WenquApi(
            // 共享池派生；登录与取数都短，给 30s 总上限
            AppHttp.withCallTimeout(30).newBuilder().build(),
        )
    }

    /**
     * 发「重置密码」验证码。**成功与失败都是 `status:200`**，结果看 [WenquEnvelope.sendResult]。
     * 平台短信限流实测约 5 分钟一条。
     */
    suspend fun sendResetCode(phone: String): WenquEnvelope = post(
        path = "register/verify",
        body = buildJsonObject {
            put("phone", phone)
            put("type", "reset")
        },
    )

    /**
     * 设置 / 重置密码。账号是**手机号**（学号不行，实测 `账号或密码错误`）。
     * 对「从没设过密码」的账号实测有效（平台语义是重置，无密码即创建）。
     */
    suspend fun resetPassword(account: String, captcha: String, password: String): WenquEnvelope = post(
        path = "register/reset",
        body = buildJsonObject {
            put("account", account)
            put("captcha", captcha)
            put("password", password)
        },
    )

    /** 日常登录（手机号 + 密码）→ `data.token`（JWT，3 天）。 */
    suspend fun login(account: String, password: String): WenquEnvelope = post(
        path = "login",
        body = buildJsonObject {
            put("account", account)
            put("password", password)
        },
    )

    /** 我的志愿 / 劳动教育记录 + 总工时。 */
    suspend fun volunteerMine(token: String): WenquEnvelope = post(
        path = "volunteer/my",
        token = token,
        body = buildJsonObject {
            put("status", "")
            put("start_time", "")
            put("end_time", "")
        },
    )

    /** 星级证书（总工时 + 星级）。 */
    suspend fun starCert(token: String): WenquEnvelope = post(
        path = "volunteer/star_cert",
        token = token,
        body = buildJsonObject {},
    )

    /**
     * 平台上的志愿服务活动列表（小程序「水利文化 → 劳动教育」那张表）。
     *
     * 三个参数都必填：`keyword` 空串 = 不过滤（**与教务选课中心不同，这里空串是合法的**，
     * 实测空关键词回全量前 `limit` 条）。`limit` 服务端不限（实测 200 照收），
     * 但列表是「按 id 倒序的时间流」且总量上千，仍按页取。
     */
    suspend fun volunteerList(
        token: String,
        page: Int,
        limit: Int,
        keyword: String,
    ): WenquEnvelope = post(
        path = "volunteer/v2",
        token = token,
        body = buildJsonObject {
            put("page", page)
            put("limit", limit)
            put("keyword", keyword)
        },
    )

    /** 单个活动详情。**id 走路径**（`volunteer/detail/{id}`），请求体是空对象。 */
    suspend fun volunteerDetail(token: String, id: Long): WenquEnvelope = post(
        path = "volunteer/detail/$id",
        token = token,
        body = buildJsonObject {},
    )

    private suspend fun post(
        path: String,
        body: JsonObject,
        token: String? = null,
    ): WenquEnvelope = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(BASE + path)
            .header("Accept", "application/json, text/plain, */*")
        // CRMEB 特有拼写：**`Authori-zation`**，写成 Authorization 会被判未登录
        if (!token.isNullOrBlank()) builder.header("Authori-zation", "Bearer $token")
        builder.post(body.toString().toRequestBody(JSON_MEDIA))
        val (httpCode, text) = http.newCall(builder.build()).execute().use { response ->
            response.code to response.body?.string().orEmpty()
        }
        try {
            QiekjJson.json.decodeFromString(WenquEnvelope.serializer(), text)
        } catch (e: SerializationException) {
            throw WenquApiException("响应格式异常（HTTP $httpCode）", httpCode, e)
        }
    }
}
