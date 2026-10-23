package edu.jxslu.schedule.data.qiekj

import kotlinx.serialization.json.JsonElement
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST

/**
 * 胖乖生活 Retrofit 接口：开水链路与参考实现 light-life DeviceApi.kt 一致（DESIGN 4.5）；
 * 签到端点取自 1.123.0 反编译（2026-10-23）。全部 Form POST；
 * 调用顺序约束见 QiekjRepository.unlockDevice / runDailySignIn 注释，禁止调整。
 */
interface QiekjApi {
    @FormUrlEncoded
    @POST("common/sms/sendCode")
    suspend fun sendCode(
        @Field("phone") phone: String,
        @Field("template") template: String = "reg",
    ): ApiEnvelope<EmptyData>

    @FormUrlEncoded
    @POST("user/reg")
    suspend fun login(
        @Field("channel") channel: String = QiekjApiConfig.LOGIN_CHANNEL,
        @Field("phone") phone: String,
        @Field("verify") verify: String,
    ): ApiEnvelope<LoginData>

    @FormUrlEncoded
    @POST("user/balance")
    suspend fun queryBalance(@Field("token") token: String): ApiEnvelope<BalanceData>

    // ── 每日签到（2026-10-23）──
    // 这些端点走 pointsApi（Version=1.123.0 + 官方 & 拼接），不开水链路共用。

    @FormUrlEncoded
    @POST("signin/todaySignIn")
    suspend fun todaySignIn(@Field("token") token: String): ApiEnvelope<JsonElement>

    @FormUrlEncoded
    @POST("signin/signInAcList")
    suspend fun signInActivities(@Field("token") token: String): ApiEnvelope<JsonElement>

    @FormUrlEncoded
    @POST("signin/doUserSignIn")
    suspend fun doUserSignIn(
        @Field("token") token: String,
        @Field("activityId") activityId: String,
    ): ApiEnvelope<JsonElement>

    @FormUrlEncoded
    @POST("goods/latestUsed")
    suspend fun getLatestUsed(
        @Field("categoryCode") categoryCode: String = "5",
        @Field("token") token: String,
    ): ApiEnvelope<List<DeviceItem>>

    @FormUrlEncoded
    @POST("goods/normal/skus")
    suspend fun goodsid2sku(
        @Field("goodsId") goodsId: String,
        @Field("token") token: String,
    ): ApiEnvelope<List<SkuData>>

    /**
     * 设备信息：拿 imei（位置风控要传）与机型能力 `supportAppUserFinish`
     * （能否在 App 内结束用水，2026-10-16 起读）。
     */
    @FormUrlEncoded
    @POST("goods/normal/details")
    suspend fun getGoodsDetails(
        @Field("goodsId") goodsId: String,
        @Field("token") token: String,
    ): ApiEnvelope<GoodsDetailsData>

    @FormUrlEncoded
    @POST("userIntegral/checkUserIsRisk")
    suspend fun checkUserIsRisk(@Field("token") token: String): ApiEnvelope<EmptyData>

    @FormUrlEncoded
    @POST("payChannelRoute/addUserAfterPayChannel")
    suspend fun addUserAfterPayChannel(
        @Field("method") method: String = "15",
        @Field("token") token: String,
    ): ApiEnvelope<EmptyData>

    @FormUrlEncoded
    @POST("orderRisk/isCheckLocation")
    suspend fun isCheckLocation(
        @Field("categoryCode") categoryCode: String = "04",
        @Field("imei") imei: String,
        @Field("token") token: String,
    ): ApiEnvelope<EmptyData>

    @FormUrlEncoded
    @POST("goods/water/unlock")
    suspend fun unlockWater(
        @Field("skuId") skuId: String,
        @Field("promotions") promotions: String,
        @Field("token") token: String,
    ): ApiEnvelope<UnlockData>

    @FormUrlEncoded
    @POST("goods/water/sync")
    suspend fun syncWater(
        @Field("skuId") skuId: String,
        @Field("token") token: String,
    ): ApiEnvelope<SyncData>

    /**
     * 远程结束用水（2026-10-14 从胖乖 1.123.0 反编译补出）：`{ goodsId }` → 布尔。
     * **官方 App 只对 `goods/normal/details` 里 `supportAppUserFinish=true` 的机型调用它**；
     * 实测的直饮水机该字段为 false，服务端回 4072「结束失败，请在设备端操作结束」
     * （2026-10-16 真机样本）。调用方按 [shouldRequestRemoteEnd] 门控，被拒时退回设备端引导。
     */
    @FormUrlEncoded
    @POST("goods/endWater")
    suspend fun endWater(
        @Field("goodsId") goodsId: String,
        @Field("token") token: String,
    ): ApiEnvelope<EmptyData>

    @FormUrlEncoded
    @POST("order/afterPay/creating")
    suspend fun createAfterPay(
        @Field("orderNo") orderNo: String,
        @Field("token") token: String,
    ): ApiEnvelope<AfterPayCreatingData>

    @FormUrlEncoded
    @POST("order/detail")
    suspend fun orderDetail(
        @Field("orderId") orderId: String,
        @Field("token") token: String,
    ): ApiEnvelope<OrderDetailData>

    /**
     * 服务端账单历史（2026-10-14 反编译补出）：`{ page, pageSize }` → `ItemsList<OrderItemBean>`。
     * 与本地快照（最多 20 笔、只记本机结算）互补，能看到账号下的全部订单。
     */
    @FormUrlEncoded
    @POST("order/list")
    suspend fun orderList(
        @Field("page") page: String,
        @Field("pageSize") pageSize: String,
        @Field("token") token: String,
    ): ApiEnvelope<ItemsList<ServerOrderItem>>
}
