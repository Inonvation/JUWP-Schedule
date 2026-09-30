package edu.jxslu.schedule.data.kqcx

import android.util.Log
import edu.jxslu.schedule.domain.KqcxAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 快趣出行「账号 + 用车」接口客户端（DESIGN §4.32，2026-09-28）。
 *
 * 形态与 [KqcxBikeClient] 同款（裸 OkHttp、私有构造 + `create()`、只做请求与失败
 * 分类，解析交 `domain/KqcxAuth`），同域名 `api.kvcoogo.com`。协议口径来自快趣
 * 小程序逆向（`docs/kvcoo-miniprogram-analysis.md`）：form POST、**无签名**、
 * 鉴权只有 `token` 头。
 *
 * 红线：
 * - **无日志拦截器**——token 与密码凭据绝不进 Logcat（对齐一卡通/电费）；
 * - token 不在本类持久化（调用方 `KqcxSessionRepository` 持内存）；
 * - 写操作（开锁/锁车/还车）**不做任何自动重试**——重试与否由仓库层按「先查状态」
 *   判定，本类只负责发一次、如实抛错。
 */
class KqcxAuthClient private constructor(private val http: OkHttpClient) {

    companion object {
        private const val BASE = "https://api.kvcoogo.com/ManagerApi/api"

        /** 账号密码登录（小程序 pages/login/index 同款接口）。 */
        private const val ENDPOINT_LOGIN = "$BASE/v1.0.0/userLoginByPassword"

        /** 骑行中订单查询（只读）。 */
        private const val ENDPOINT_UNDERWAY = "$BASE/v1.0.0/queryUnderwayOrder"

        /** 创建订单（开锁第一步，README：创建即绑定车辆并开始计费流程）。 */
        private const val ENDPOINT_CREATE_ORDER = "$BASE/v4.0.0/createOrder"

        /** GPRS 开锁。 */
        private const val ENDPOINT_UNLOCK = "$BASE/v1.0.0/greenCarUnlock"

        /** 头盔锁（官方在 helmet==1 && helmetConfig==1 时替代车锁开锁）。 */
        private const val ENDPOINT_HELMET_UNLOCK = "$BASE/v1.0.0/helmetUnLock"

        /** 锁车（官方骑行面板「锁车」按钮 = 临时锁车，订单与计费继续）。 */
        private const val ENDPOINT_LOCK = "$BASE/v1.0.0/greenCarLock"

        /** 静默锁车（官方还车流程第一步用的无声锁）。 */
        private const val ENDPOINT_MUTE_LOCK = "$BASE/v1.0.0/muteCarLock"

        /** 还车（结束订单）。 */
        private const val ENDPOINT_END_ORDER = "$BASE/v3.0.0/endTheOrder"

        /** 未支付订单查询（还车后的扣款确认）。 */
        private const val ENDPOINT_UNPAY = "$BASE/v1.0.0/queryUnPayOrder"

        /**
         * 服务区 / 还车点 / 禁停区图层（`queryZoneList`，2026-09-28 从解包产物里找到的）：
         * 官方还车点页 `pages/epark/index` 用它画多边形与还车点标记。只读接口。
         */
        private const val ENDPOINT_ZONES = "$BASE/v1.0.0/queryZoneList"

        /**
         * 响铃寻车（`greenCarFind`，2026-09-30）：让**在案订单**的车鸣笛，官方骑行页
         * 「响铃寻车」同款（无参数、作用于当前订单）。
         */
        private const val ENDPOINT_FIND_CAR = "$BASE/v1.0.0/greenCarFind"

        /**
         * 按车号查单车（`/v2.0.0/queryOneCar`，2026-09-30）：官方扫码后 `loadOneCar`
         * 用它拿单辆车的真实坐标与电量。只读接口。
         */
        private const val ENDPOINT_ONE_CAR = "$BASE/v2.0.0/queryOneCar"

        /** 用户信息（`getUserInfo`）：充值 / 赠送余额都在 `result` 里。只读。 */
        private const val ENDPOINT_USER_INFO = "$BASE/v2.0.0/getUserInfo"

        /** 用户持有卡券（`queryUserCoupon`）：`list[]`，剩余次数与免费时长。只读。 */
        private const val ENDPOINT_USER_COUPON = "$BASE/v2.0.0/queryUserCoupon"

        /** 会员卡（`queryUserMemberCoupon`，官方传 `{page:1,rows:20}`）。只读。 */
        private const val ENDPOINT_USER_MEMBER = "$BASE/v1.0.0/queryUserMemberCoupon"

        /** 小程序请求头的 `client-type: 1`；服务端按 token 认人，此头照抄即可。 */
        private const val CLIENT_TYPE = "1"

        /** 客户端版本（官方 config.Version 的现值）；createOrder 要带。 */
        private const val CLIENT_VERSION = "V6.0.0"

        /** UA 写自己包名，不冒充快趣客户端（与 [KqcxBikeClient] 同口径）。 */
        private const val UA = "edu.jxslu.schedule"

        fun create(): KqcxAuthClient = KqcxAuthClient(
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build(),
        )
    }

    /**
     * 账号密码登录，返回响应原文（信封 + token 字段）。
     * [passwordMd5] 由调用方经 [KqcxAuth.passwordCipher] 现算，本类不存密码。
     */
    suspend fun loginJson(mobile: String, passwordMd5: String): String =
        withContext(Dispatchers.IO) {
            val body = FormBody.Builder()
                .add("mobile", mobile)
                .add("password", passwordMd5)
                .build()
            execute(baseRequest(ENDPOINT_LOGIN).post(body).build(), "登录")
        }

    /** 骑行中订单查询（只读），返回响应原文。 */
    suspend fun underwayJson(token: String): String = withContext(Dispatchers.IO) {
        execute(authed(ENDPOINT_UNDERWAY, token).post(FormBody.Builder().build()).build(), "骑行查询")
    }

    /** 创建订单（开锁第一步）。[locationSource] 官方口径 `"realtime"`。 */
    suspend fun createOrderJson(
        token: String,
        carNum: String,
        lat: Double,
        lng: Double,
        locationSource: String,
    ): String = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("carNum", carNum)
            .add("lat", lat.toString())
            .add("lng", lng.toString())
            .add("clientType", "1")
            .add("clientVersion", CLIENT_VERSION)
            .add("locationSource", locationSource)
            .build()
        execute(authed(ENDPOINT_CREATE_ORDER, token).post(body).build(), "创建订单")
    }

    /** GPRS 开锁。 */
    suspend fun unlockJson(
        token: String,
        lat: Double,
        lng: Double,
        locationSource: String,
    ): String = withContext(Dispatchers.IO) {
        execute(authed(ENDPOINT_UNLOCK, token).post(lockBody(lat, lng, locationSource)).build(), "开锁")
    }

    /** 头盔锁开锁（官方头盔流程）。 */
    suspend fun helmetUnlockJson(token: String): String = withContext(Dispatchers.IO) {
        execute(authed(ENDPOINT_HELMET_UNLOCK, token).post(FormBody.Builder().build()).build(), "头盔锁")
    }

    /** 临时锁车（有声）。 */
    suspend fun lockJson(
        token: String,
        lat: Double,
        lng: Double,
        locationSource: String,
    ): String = withContext(Dispatchers.IO) {
        execute(authed(ENDPOINT_LOCK, token).post(lockBody(lat, lng, locationSource)).build(), "锁车")
    }

    /** 静默锁车（还车流程第一步）。 */
    suspend fun muteLockJson(
        token: String,
        lat: Double,
        lng: Double,
        locationSource: String,
    ): String = withContext(Dispatchers.IO) {
        execute(authed(ENDPOINT_MUTE_LOCK, token).post(lockBody(lat, lng, locationSource)).build(), "锁车")
    }

    /**
     * 还车（结束订单）。载荷逐字对齐官方 `doHandleOnReturnBike`：
     * `locationValid:1`、`bdStatus/bdPileStatus:0`、`bluetoothName:""`、
     * `dispatchFlag:2`（普通还车；官方在用户已确认调度费时改发 1——App 不代确认）。
     */
    suspend fun endOrderJson(
        token: String,
        lat: Double,
        lng: Double,
        locationSource: String,
        locationTimeSeconds: Long,
    ): String = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("lat", lat.toString())
            .add("lng", lng.toString())
            .add("locationValid", "1")
            .add("locationSource", locationSource)
            .add("locationTime", locationTimeSeconds.toString())
            .add("bdStatus", "0")
            .add("bdPileStatus", "0")
            .add("bluetoothName", "")
            .add("dispatchFlag", "2")
            .build()
        execute(authed(ENDPOINT_END_ORDER, token).post(body).build(), "还车")
    }

    /** 未支付订单查询（只读）。 */
    suspend fun unpayJson(token: String): String = withContext(Dispatchers.IO) {
        execute(authed(ENDPOINT_UNPAY, token).post(FormBody.Builder().build()).build(), "欠费查询")
    }

    /**
     * 还车点 / 禁停区图层。参数逐字对齐官方：`{lat, lng, carNum, page:1, rows:15}`
     * （`carNum` 是"以哪辆车为上下文"，官方在还车点页传选中车、在地图页传最近一辆车）。
     */
    /**
     * 还车点 / 禁停区图层。`token` 允许空串（2026-10-01 实测：这个接口**不需要凭证**，
     * 不带 `token` 头也回 `resultCode=1`）——小程序方式没有会话，就空着发，
     * 不要为了它按需登一次账号（见 `KvcxRideSession.queryZones` 的注释）。
     */
    suspend fun zonesJson(token: String, lat: Double, lng: Double, carNum: String): String =
        withContext(Dispatchers.IO) {
            val body = FormBody.Builder()
                .add("lat", lat.toString())
                .add("lng", lng.toString())
                .add("carNum", carNum)
                .add("page", "1")
                .add("rows", "15")
                .build()
            val request = if (token.isBlank()) {
                baseRequest(ENDPOINT_ZONES)
            } else {
                authed(ENDPOINT_ZONES, token)
            }
            execute(request.post(body).build(), "还车点查询")
        }

    /** 响铃寻车：无参数（作用于在案订单），成功即车已响。 */
    suspend fun findCarJson(token: String): String = withContext(Dispatchers.IO) {
        execute(authed(ENDPOINT_FIND_CAR, token).post(FormBody.Builder().build()).build(), "响铃寻车")
    }

    /**
     * 按车号查单车。参数逐字对齐官方 `loadOneCar`：`{carNum, lat, lng}`（坐标是查询上下文，
     * 官方传扫当时的定位）。token 允许空串——与 [zonesJson] 同口径的只读接口，
     * 小程序方式（无会话）也要能用「按车号定位」。
     */
    suspend fun oneCarJson(token: String, carNum: String, lat: Double, lng: Double): String =
        withContext(Dispatchers.IO) {
            val body = FormBody.Builder()
                .add("carNum", carNum)
                .add("lat", lat.toString())
                .add("lng", lng.toString())
                .build()
            val request = if (token.isBlank()) {
                baseRequest(ENDPOINT_ONE_CAR)
            } else {
                authed(ENDPOINT_ONE_CAR, token)
            }
            execute(request.post(body).build(), "单车查询")
        }

    /** 账户资产三件套（只读）：余额 / 卡券 / 会员卡，参数都是空表单（会员卡带分页）。 */
    suspend fun userInfoJson(token: String): String = withContext(Dispatchers.IO) {
        execute(authed(ENDPOINT_USER_INFO, token).post(FormBody.Builder().build()).build(), "资产查询")
    }

    suspend fun userCouponJson(token: String): String = withContext(Dispatchers.IO) {
        execute(authed(ENDPOINT_USER_COUPON, token).post(FormBody.Builder().build()).build(), "卡券查询")
    }

    suspend fun userMemberJson(token: String): String = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("page", "1")
            .add("rows", "20")
            .build()
        execute(authed(ENDPOINT_USER_MEMBER, token).post(body).build(), "会员卡查询")
    }

    /** 公共头：form + `client-type: 1` + UA（对齐小程序 client 构造）。 */
    private fun baseRequest(url: String): Request.Builder = Request.Builder()
        .url(url)
        .header("client-type", CLIENT_TYPE)
        .header("User-Agent", UA)

    private fun authed(url: String, token: String): Request.Builder =
        baseRequest(url).header("token", token)

    /** 锁类接口的公共载荷：`{lng, lat, locationSource}`（官方三处同构）。 */
    private fun lockBody(lat: Double, lng: Double, locationSource: String): FormBody =
        FormBody.Builder()
            .add("lng", lng.toString())
            .add("lat", lat.toString())
            .add("locationSource", locationSource)
            .build()

    private fun execute(request: Request, what: String): String =
        runCatching {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw ServiceHttpException(response.code, what)
                response.body?.string().orEmpty()
            }
        }.onFailure {
            // 只打异常类名与动作名，不打 body/头——token 与凭据不许进日志
            Log.w("KqcxAuthClient", "$what failed: ${it.javaClass.simpleName}")
        }.getOrThrow()

    /** 服务端返回非 2xx。属于 [IOException]，调用方只用 try/catch 一层。 */
    class ServiceHttpException(val httpCode: Int, what: String) :
        IOException("快趣$what 接口返回 $httpCode")
}
