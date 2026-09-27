package edu.jxslu.schedule.data.qzxy

import retrofit2.http.Field
import retrofit2.http.FieldMap
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.QueryMap

/**
 * 趣智校园 Retrofit 接口（DESIGN §4.30）。
 *
 * 端点取自 linyu 的 `QzxyService` 与看雪分析，两类泾渭分明：
 *
 * - **不签名**：登录、项目（学校名）、余额、设备信息、账单。认证参数以 query/form 明文传递。
 *   `/account/info`（姓名与学号）**不调用**：学校没同步学籍数据时整条返回 null，页面也用不上。
 * - **要签名**：[rateOrder] 与 [uploadConsume]，认证参数之外还要 `signature`，
 *   算法见 [edu.jxslu.schedule.domain.QzxySign]。
 *
 * `tcpDevice` 系列端点（联网型设备）本批不实现：用户学校是蓝牙型，
 * 照抄那套只会重复「HTTP 通了但设备没动」的结果。
 */
interface QzxyApi {

    // ── 登录 ──

    @FormUrlEncoded
    @POST("user/login")
    suspend fun login(
        @Field("telephone") telephone: String,
        @Field("password") password: String,
        @Field("type") type: String = "0",
        @Field("identifier") identifier: String = "",
        @Field("phoneSystem") phoneSystem: String = QzxyApiConfig.PHONE_SYSTEM,
        @Field("version") version: String = QzxyApiConfig.VERSION,
    ): QzxyEnvelope

    @GET("user/verification/code/get")
    suspend fun sendCode(
        @Query("telephone") telephone: String,
        @Query("secret") secret: String,
        @Query("typeId") typeId: String = "3",
        @Query("platform") platform: String = "1",
        @Query("phoneSystem") phoneSystem: String = QzxyApiConfig.PHONE_SYSTEM,
        @Query("version") version: String = QzxyApiConfig.VERSION,
    ): QzxyEnvelope

    @FormUrlEncoded
    @POST("user/registerAndLogin")
    suspend fun loginBySms(
        @Field("telephone") telephone: String,
        @Field("smsCode") smsCode: String,
        @Field("type") type: String = "5",
        @Field("phoneSystem") phoneSystem: String = QzxyApiConfig.PHONE_SYSTEM,
        @Field("version") version: String = QzxyApiConfig.VERSION,
    ): QzxyEnvelope

    // ── 账号 / 资产 ──

    @GET("project/info/triple")
    suspend fun projectInfo(@QueryMap auth: Map<String, String>): QzxyEnvelope

    @GET("account/wallet")
    suspend fun wallet(@QueryMap auth: Map<String, String>): QzxyEnvelope

    // ── 设备 / 账单 ──

    @GET("device/info/mac")
    suspend fun deviceInfo(@QueryMap params: Map<String, String>): QzxyEnvelope

    @GET("order/query/account/bill/list")
    suspend fun billList(@QueryMap params: Map<String, String>): QzxyEnvelope

    // ── 蓝牙开阀（要签名）──

    @FormUrlEncoded
    @POST("order/downRate/bluetooth/rateOrder")
    suspend fun rateOrder(@FieldMap params: Map<String, String>): QzxyEnvelope

    @FormUrlEncoded
    @POST("order/upload/bluetooth/data")
    suspend fun uploadConsume(@FieldMap params: Map<String, String>): QzxyEnvelope
}
