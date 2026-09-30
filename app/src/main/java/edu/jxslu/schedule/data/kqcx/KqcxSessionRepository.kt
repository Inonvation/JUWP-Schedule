package edu.jxslu.schedule.data.kqcx

import edu.jxslu.schedule.domain.BikeNearby
import edu.jxslu.schedule.domain.KqcxAuth
import edu.jxslu.schedule.domain.KvcBusinessError
import edu.jxslu.schedule.domain.KvcProtocolException
import edu.jxslu.schedule.domain.KvcxZones
import edu.jxslu.schedule.domain.NearbyBike
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 快趣出行会话仓库（DESIGN §4.32，2026-09-28）：登录、骑行状态与**用车动作**
 * （开锁 / 临时锁车 / 还车，B/C 档）。
 *
 * - **token 仅内存**（`@Volatile`）：不落盘、不进日志——与一卡通（`data/ykt`）、
 *   电费（`data/power`）同一红线；进程被杀后靠本地凭证静默重登恢复会话；
 * - 凭证（手机号 + 密码）落 [KqxCredentialStore]（加密 prefs、已排除备份）；
 * - **写操作零自动重试**：`createOrder` / `endTheOrder` 超时绝不重发（防重复订单、
 *   重复结算）；调用方超时后应**先刷新状态**再决定是否让用户重试——重试开锁
 *   （订单已在案、只重发 `greenCarUnlock`）是安全特例，官方也是这么做的；
 * - **动作前先查在案订单**：有骑行禁止再开锁、无骑行禁止还车（服务端另有 12022 兜底）；
 * - **确认节奏官方同款**：每个动作后有界查询 `queryUnderwayOrder`（≤4 次、1.5 秒一次）
 *   确认落地，不是常驻轮询；
 * - **调度费不代确认**：`dispatchFlag` 固定发 2（普通还车）；服务端要调度费时抛
 *   [KvcBusinessError]（带 [KvcBusinessError.dispatchMoneyCents]），UI 降级到官方渠道。
 */
class KqcxSessionRepository(
    private val client: KqcxAuthClient,
    private val credentials: KqxCredentialStore,
) : KvcxRideSession, KvcxZoneSource {

    /** 会话流：UI（hub 登录态、快趣页）统一订阅这条。 */
    private val _loggedIn = MutableStateFlow(credentials.readCredential() != null)
    override val loggedIn: StateFlow<Boolean> = _loggedIn

    /** token 仅内存；volatile 保证 IO 线程写入 / UI 线程读取的可见性。 */
    @Volatile
    private var token: String? = null

    /** 当前登录账号（内存），供页面显示「已登录：138****xxxx」。 */
    @Volatile
    var currentMobile: String? = null
        private set

    /** 重登互斥：并发请求同时发现 token 失效时只放一个进去重登。 */
    private val relogging = AtomicBoolean(false)

    // ---------- 登录 / 会话 ----------

    /**
     * 账号密码登录：成功后凭证入加密存储、token 入内存、翻转登录态。
     * 失败原样抛（[KvcBusinessError] 带服务端文案 / [IOException] 网络），调用方提示。
     */
    suspend fun login(mobile: String, password: String) {
        val result = client.loginJson(mobile, KqcxAuth.passwordCipher(password))
            .let { KqcxAuth.parseLogin(it) }
        credentials.saveCredential(mobile, password)
        token = result.token
        currentMobile = result.mobile.ifBlank { mobile }
        _loggedIn.value = true
    }

    /** 退出登录：清凭证与内存 token（常驻倒计时等无关状态不在此处碰）。 */
    fun logout() {
        credentials.clear()
        token = null
        currentMobile = null
        _loggedIn.value = false
    }

    /**
     * 查询骑行中订单。无骑行返回 null；登录缺失/失败抛错由调用方分类提示。
     *
     * 流程：无 token 先静默登录 → 查询 → 疑似 token 失效则重登一次重试一次。
     */
    override suspend fun queryUnderway(): KqcxAuth.Ride? {
        ensureSession()
        return try {
            queryWithCurrentToken()
        } catch (error: Throwable) {
            if (!isTokenError(error)) throw error
            if (withRelogin { reloginFromCredentials() } != true) throw error
            queryWithCurrentToken()
        }
    }

    // ---------- 用车动作（B/C 档） ----------

    /**
     * 开锁：`createOrder` → （头盔流程 | `greenCarUnlock`）。
     *
     * 闸：登录 + **无在案订单**（本地先挡；服务端 12022 兜底）。
     * **零自动重试**：`createOrder` 超时抛错，调用方刷新状态后决定；回滚不必要——
     * 订单若真创建成功，刷新能看到，重试开锁走 [retryUnlock]。
     */
    override suspend fun unlockBike(carNum: String, gcjLat: Double, gcjLng: Double): UnlockOutcome {
        ensureSession()
        queryUnderway()?.let { existing ->
            throw KvcBusinessError(null, "已有进行中的订单（车 ${existing.carNum}），请先还车")
        }
        val token = requireToken()
        val created = server {
            KqcxAuth.parseCreatedOrder(
                client.createOrderJson(token, carNum, gcjLat, gcjLng, LOCATION_SOURCE),
            )
        }
        if (created.helmetFlowRequired) {
            // 官方头盔流程：此路开头盔锁，车锁随头盔流程放开
            server { client.helmetUnlockJson(token) }
            awaitRide { it != null }
            return UnlockOutcome.Unlocked(created.carNum, helmetFlow = true)
        }
        // 开锁指令：16015 = 服务端要求先取头盔（官方同款分支）
        try {
            server { client.unlockJson(token, gcjLat, gcjLng, LOCATION_SOURCE) }
        } catch (error: KvcBusinessError) {
            if (error.errorCode == CODE_HELMET_REQUIRED) {
                server { client.helmetUnlockJson(token) }
                awaitRide { it != null }
                return UnlockOutcome.Unlocked(created.carNum, helmetFlow = true)
            }
            throw error
        }
        return if (awaitRide { it != null && it.locked != true } != null) {
            UnlockOutcome.Unlocked(created.carNum, helmetFlow = false)
        } else {
            UnlockOutcome.Unconfirmed(created.carNum)
        }
    }

    /**
     * 重试开锁（订单已在案、只是车锁没确认打开时用）：只重发 `greenCarUnlock`。
     * 与官方「开锁失败，请在面板点开锁重试」同一路径。
     */
    override suspend fun retryUnlock(gcjLat: Double, gcjLng: Double): UnlockOutcome {
        ensureSession()
        val ride = queryUnderway()
            ?: throw KvcBusinessError(null, "没有在案订单，请重新开锁")
        server { client.unlockJson(requireToken(), gcjLat, gcjLng, LOCATION_SOURCE) }
        return if (awaitRide { it != null && it.locked != true } != null) {
            UnlockOutcome.Unlocked(ride.carNum, helmetFlow = false)
        } else {
            UnlockOutcome.Unconfirmed(ride.carNum)
        }
    }

    /**
     * 临时锁车（官方骑行面板「锁车」）：订单与计费继续，仅物理锁车。
     * 已锁时为幂等 no-op。返回确认后的订单状态。
     */
    override suspend fun temporaryLock(gcjLat: Double, gcjLng: Double): KqcxAuth.Ride {
        ensureSession()
        val before = queryUnderway() ?: throw KvcBusinessError(null, "没有进行中的骑行")
        if (before.locked == true) return before
        server { client.lockJson(requireToken(), gcjLat, gcjLng, LOCATION_SOURCE) }
        return awaitRide { it?.locked == true }
            ?: throw KvcBusinessError(
                null,
                "锁车指令已发送，但未确认车辆已锁；请留意车辆提示音，或点刷新确认",
            )
    }

    /**
     * 还车：未锁先静默锁（`muteCarLock`，尽力而为）→ `endTheOrder`。
     * 成功返回 [KqcxAuth.EndOutcome.Ended]；被拒抛 [KvcBusinessError]（调度费场景带金额）。
     * `12003`（订单已结束）按成功处理——可能是官方渠道或其他端先结束了。
     */
    override suspend fun returnBike(gcjLat: Double, gcjLng: Double): KqcxAuth.EndOutcome.Ended {
        ensureSession()
        val token = requireToken()
        val ride = queryUnderway() ?: throw KvcBusinessError(null, "没有进行中的骑行")
        if (ride.locked != true) {
            // 静默锁尽力而为：官方即便锁失败也继续走 endTheOrder（订单结束才是关键）
            runCatching { server { client.muteLockJson(token, gcjLat, gcjLng, LOCATION_SOURCE) } }
            awaitRide { it == null || it.locked == true }
        }
        val outcome = KqcxAuth.parseEndOrder(
            client.endOrderJson(token, gcjLat, gcjLng, LOCATION_SOURCE, nowSeconds()),
        )
        return when (outcome) {
            is KqcxAuth.EndOutcome.Ended -> {
                // 确认订单真的结束（查询不到即结束）；查不到也别翻车——服务端已确认成功
                awaitRide { it == null }
                outcome
            }
            is KqcxAuth.EndOutcome.Rejected -> {
                if (outcome.code == CODE_ORDER_ENDED) {
                    KqcxAuth.EndOutcome.Ended(needPay = null, wechatScore = null)
                } else {
                    throw KvcBusinessError(
                        outcome.code,
                        rejectedText(outcome),
                        outcome.dispatchMoneyCents,
                    )
                }
            }
        }
    }

    /**
     * 还车后确认扣款：短轮询 `queryUnPayOrder`（≤3 次、约 2.5 秒间隔，官方同款节奏）。
     * true = 已结清；false = 仍有欠费或查询失败（调用方提示到官方渠道核对）。
     */
    /**
     * 还车点 / 禁停区图层（DESIGN §3.9）：只读、失败静默（图层是装饰，调用方保留上一层）。
     * 没有可当上下文的车号时直接回 null，不发请求。
     *
     * **不碰账号**（2026-10-01）：这个接口不需要 token（实测不带 `token` 头也回
     * `resultCode=1`，见 [KqcxAuthClient.zonesJson]），所以不再 `ensureSession()`。
     * 旧写法在本机留着凭证时会**隐式重登一次**：小程序方式（对外承诺"不碰账号、
     * 不打扰第三方接口"）因此也拿到了图层，两台手机表现还不一样；而没有凭证的手机
     * 该图层永远不出现（用户 2026-10-01 报的「停车点没了」就是这个）。
     * 现在有会话就带上 token、没有就空着发，两种使用方式都能看还车点。
     */
    override suspend fun queryZones(lat: Double, lng: Double, carNum: String?): KvcxZones? {
        if (carNum.isNullOrBlank()) return null
        return try {
            KvcxZones.parse(client.zonesJson(token.orEmpty(), lat, lng, carNum))
        } catch (_: Throwable) {
            null
        }
    }

    override suspend fun confirmUnpaidSettled(attempts: Int): KqcxAuth.UnpayState? {
        ensureSession()
        var last: KqcxAuth.UnpayState? = null
        repeat(attempts) { index ->
            val state = runCatching { KqcxAuth.parseUnpayState(client.unpayJson(requireToken())) }
            last = state.getOrNull() ?: return null // 查询失败：不再等，交给用户核对
            if (last is KqcxAuth.UnpayState.Settled) return last
            // Owed：等自动扣款，继续轮
            if (index < attempts - 1) delay(SETTLE_POLL_INTERVAL_MS)
        }
        return last
    }

    /**
     * 响铃寻车（2026-09-30）：让在案订单的车鸣笛。服务端没有在案订单时会报业务错误，
     * 原样抛给调用方提示（控制器在本地已先挡一层）。
     */
    override suspend fun ringFindCar() {
        ensureSession()
        server { KqcxAuth.parseSuccess(client.findCarJson(requireToken())) }
    }

    /**
     * 按车号查单车（2026-09-30）：附近列表里没有目标车时的兜底（官方 `loadOneCar` 同款）。
     * token 允许空——这个读接口不需要凭证（与 [queryZones] 同口径），小程序方式也能用
     * 「按车号定位」。解析不出（车号不存在 / 坐标无效）返回 null，**不抛错**：调用方
     * 把"查不到"当正常结果提示。
     */
    suspend fun queryCarDetail(carNum: String, centerLat: Double, centerLng: Double): NearbyBike? =
        runCatching {
            BikeNearby.parseSingle(
                client.oneCarJson(token.orEmpty(), carNum, centerLat, centerLng),
                centerLat,
                centerLng,
            )
        }.getOrNull()

    /**
     * 账户资产（快趣账号页「快趣资产」区，只读）：余额 / 卡券 / 会员卡，三个接口**并行**拉。
     * 单个接口失败不影响其它块（余额给 null、列表给空）；登录缺失 / 失效照常抛错。
     */
    suspend fun queryAssets(): KqcxAuth.Assets {
        ensureSession()
        val current = requireToken()
        return coroutineScope {
            val balance = async {
                runCatching { KqcxAuth.parseBalance(client.userInfoJson(current)) }.getOrNull()
            }
            val coupons = async {
                runCatching { KqcxAuth.parseCoupons(client.userCouponJson(current)) }
                    .getOrDefault(emptyList())
            }
            val members = async {
                runCatching { KqcxAuth.parseMembers(client.userMemberJson(current)) }
                    .getOrDefault(emptyList())
            }
            KqcxAuth.Assets(balance = balance.await(), coupons = coupons.await(), members = members.await())
        }
    }

    // ---------- 内部 ----------

    private suspend fun queryWithCurrentToken(): KqcxAuth.Ride? {
        val current = token ?: throw IllegalStateException("快趣会话不存在")
        val json = client.underwayJson(current)
        return KqcxAuth.parseUnderway(json)
    }

    /** 有凭证就静默重登；无凭证（未登录）抛业务错误提示。 */
    private suspend fun ensureSession() {
        if (token != null) return
        if (withRelogin { reloginFromCredentials() } != true) {
            throw KvcBusinessError(null, "快趣未登录")
        }
    }

    /**
     * 用本地凭证重登。返回 false = 无凭证或重登失败（登录态同步翻转）。
     * 在 [withRelogin] 的互斥下执行。
     */
    private suspend fun reloginFromCredentials(): Boolean {
        val (mobile, password) = credentials.readCredential()
            ?: run {
                _loggedIn.value = false
                return false
            }
        return runCatching {
            login(mobile, password)
            true
        }.getOrElse { error ->
            // 凭证失效（改密/注销）这类业务失败：退出登录态，让 UI 回登录表单
            if (error is KvcBusinessError) _loggedIn.value = false
            false
        }
    }

    /** 互斥执行 [action]：同一时刻只有一个调用方进入，其余等待后按结果放行。 */
    private suspend fun <T> withRelogin(action: suspend () -> T): T? {
        if (!relogging.compareAndSet(false, true)) {
            // 别人正在重登：自旋等它完成（重登是秒级请求，不值得为此挂起队列）
            while (relogging.get()) {
                delay(50)
            }
            return action()
        }
        return try {
            action()
        } finally {
            relogging.set(false)
        }
    }

    /**
     * 有界确认（官方同款节奏）：查询在案订单直到 [predicate] 成立（≤[attempts] 次、
     * [CONFIRM_INTERVAL_MS] 间隔）。**只用于动作后的落地确认，不是常驻轮询**；
     * 查询本身失败按「还没确认」继续等（最后一次仍不成立即返回 null）。
     */
    private suspend fun awaitRide(
        attempts: Int = 4,
        predicate: (KqcxAuth.Ride?) -> Boolean,
    ): KqcxAuth.Ride? {
        repeat(attempts) { index ->
            if (index > 0) delay(CONFIRM_INTERVAL_MS)
            val ride = runCatching { queryUnderway() }.getOrNull()
            if (predicate(ride)) return ride
        }
        return null
    }

    /**
     * 业务错误统一转用户可读文案（官方错误码表在 [KqcxAuth.errorMessage]）。
     * **只包客户端调用**，调用方自己构造的最终文案（调度费、在案订单）不要经过它。
     */
    private suspend fun <T> server(block: suspend () -> T): T = try {
        block()
    } catch (error: KvcBusinessError) {
        throw KvcBusinessError(
            error.errorCode,
            KqcxAuth.errorMessage(error.errorCode, error.message ?: "操作失败"),
            error.dispatchMoneyCents,
        )
    }

    /** 还车被拒的文案：调度费场景优先（官方走确认面板，App 不代接受）。 */
    private fun rejectedText(rejected: KqcxAuth.EndOutcome.Rejected): String {
        val fee = rejected.dispatchMoneyCents
        return if (fee != null && fee > 0) {
            "该位置还车会产生调度费（约 ¥%.2f）；为免误扣，请在快趣小程序中确认还车"
                .format(fee / 100.0)
        } else {
            KqcxAuth.errorMessage(rejected.code, rejected.message)
        }
    }

    private fun requireToken(): String = token ?: throw IllegalStateException("快趣会话不存在")

    /** 官方 `Math.ceil(ts/1e3)` 同口径的秒级时间戳。 */
    private fun nowSeconds(): Long = (System.currentTimeMillis() + 999) / 1000

    private fun isTokenError(error: Throwable): Boolean = when (error) {
        is KvcBusinessError -> KqcxAuth.looksLikeTokenError(error.message)
        is KvcProtocolException -> false
        is IOException -> false
        else -> false
    }

    private companion object {
        /** 官方 wx.getLocation `type:"gcj02"`，source 取实时定位的 `"realtime"`。 */
        const val LOCATION_SOURCE = "realtime"

        /** 服务端「订单已结束」（官方 case 12003）。 */
        const val CODE_ORDER_ENDED = 12003

        /** 服务端「需先取头盔」（官方 16015 分支）。 */
        const val CODE_HELMET_REQUIRED = 16015

        /** 动作后落地确认的查询间隔（官方 1 秒级节奏取整）。 */
        const val CONFIRM_INTERVAL_MS = 1_500L

        /** 还车后扣款确认的轮询间隔。 */
        const val SETTLE_POLL_INTERVAL_MS = 2_500L
    }
}
