package edu.jxslu.schedule.data.kqcx

import edu.jxslu.schedule.data.session.SessionExpiryStore
import edu.jxslu.schedule.data.session.SessionService
import edu.jxslu.schedule.domain.BikeNearby
import edu.jxslu.schedule.domain.CarDetailResult
import edu.jxslu.schedule.domain.KqcxAuth
import edu.jxslu.schedule.domain.KvcBusinessError
import edu.jxslu.schedule.domain.KvcProtocolException
import edu.jxslu.schedule.domain.KvcxZones
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

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
 * - **写操作不再先查在案订单**：开锁/还车直接发写请求，在途订单由服务端 12022 /
 *   12003 兜底（2026-10-18 提速，省掉关键路径上的一个往返）；
 * - **写接口成功即返回**（2026-10-18 提速）：开锁/还车不再阻塞等待 `queryUnderwayOrder`
 *   落地，确认查询交给调用方在后台做；只有临时锁车仍保留有界确认（锁状态是它的结果）；
 * - **调度费不代确认**：`dispatchFlag` 固定发 2（普通还车）；服务端要调度费时抛
 *   [KvcBusinessError]（带 [KvcBusinessError.dispatchMoneyCents]），UI 降级到官方渠道。
 */
class KqcxSessionRepository(
    private val client: KqcxAuthClient,
    private val credentials: KqxCredentialStore,
    /** 「登录状态已过期」留痕（见 [SessionExpiryStore]）：静默重登失败时写、进页消费。 */
    private val expiry: SessionExpiryStore? = null,
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

    /** 重登单飞：并发请求同时发现 token 失效时共享同一次重登结果，不再重复登录。 */
    private val reloginJob = AtomicReference<CompletableDeferred<Boolean>?>(null)

    // ---------- 登录 / 会话 ----------

    /**
     * 账号密码登录：成功后 token 入内存、翻转登录态。
     * 失败原样抛（[KvcBusinessError] 带服务端文案 / [IOException] 网络），调用方提示。
     *
     * [remember] = true（默认，2026-10-21 用户拍板：默认记住密码）时落凭证；
     * false 时**不落凭证**：本次会话有效，进程结束后需要重新登录。
     * token 本身从不落盘（见类注释）。
     */
    suspend fun login(mobile: String, password: String, remember: Boolean = true) {
        val result = client.loginJson(mobile, KqcxAuth.passwordCipher(password))
            .let { KqcxAuth.parseLogin(it) }
        if (remember) credentials.saveCredential(mobile, password) else credentials.clear()
        expiry?.clear(SessionService.Kqcx)
        token = result.token
        currentMobile = result.mobile.ifBlank { mobile }
        _loggedIn.value = true
    }

    /** 退出登录：清凭证与内存 token（常驻倒计时等无关状态不在此处碰）。 */
    fun logout() {
        credentials.clear()
        expiry?.clear(SessionService.Kqcx)
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
            if (!ensureRelogin()) throw error
            queryWithCurrentToken()
        }
    }

    // ---------- 用车动作（B/C 档） ----------

    /**
     * 开锁：`createOrder` → （头盔流程 | `greenCarUnlock`）。
     *
     * **不再先查在案订单**（2026-10-18）：官方也是直接 `createOrder`，有在途订单由服务端
     * 回 12022。少一个往返，也避免查询超时把开锁整个挡住。
     *
     * **成功即返回**：`greenCarUnlock` / `helmetUnLock` 成功就返回 [UnlockOutcome.Unlocked]，
     * 不再等 `queryUnderwayOrder` 确认。调用方先落骑行卡，再在后台做只读补全。
     *
     * **两段式**（[sendUnlock] = false，2026-10-21）：只发 `createOrder` 建单，**不发票**
     * ——车保持已锁，解锁由用户对在案订单点「解锁」（[retryUnlock]）完成。
     * 返回的最小订单按 `locked = true` 落位（车辆没收到开锁指令）。
     * 头盔流程（建单响应要求 / 16015）例外：那套解锁在 `helmetUnLock`，两段式第二步
     * 覆盖不了，退回完整流程照旧。
     *
     * **零自动重试**：`createOrder` 超时抛错，调用方刷新状态后决定；回滚不必要——
     * 订单若真创建成功，刷新能看到，重试开锁走 [retryUnlock]。
     */
    override suspend fun unlockBike(
        carNum: String,
        gcjLat: Double,
        gcjLng: Double,
        locationSource: String,
        sendUnlock: Boolean,
    ): UnlockOutcome {
        ensureSession()
        val token = requireToken()
        val created = server {
            KqcxAuth.parseCreatedOrder(
                client.createOrderJson(token, carNum, gcjLat, gcjLng, locationSource),
            )
        }
        if (!sendUnlock) {
            // 两段式第一段：订单在案、车没解锁（locked = true）。头盔车除外——它在建单
            // 响应里就要求走头盔流程，那套解锁属于完整流程。
            if (created.helmetFlowRequired) return fullUnlock(created, token, gcjLat, gcjLng, locationSource)
            return UnlockOutcome.Unlocked(created.carNum, helmetFlow = false, ride = lockedRide(created))
        }
        if (created.helmetFlowRequired) {
            // 官方头盔流程：此路开头盔锁，车锁随头盔流程放开
            try {
                server { client.helmetUnlockJson(token) }
            } catch (error: IOException) {
                return UnlockOutcome.Unconfirmed(created.carNum, provisionalRide(created))
            }
            return UnlockOutcome.Unlocked(
                created.carNum,
                helmetFlow = true,
                ride = provisionalRide(created),
            )
        }
        // 开锁指令：16015 = 服务端要求先取头盔（官方同款分支）。
        try {
            server { client.unlockJson(token, gcjLat, gcjLng, locationSource) }
            // 先给一张最小骑行卡（车号 + 未锁），时长 / 电量 / 费用由后台补全。
            // 不再在这里等 queryUnderwayOrder 落地——高峰时那正是按钮长时间转圈的原因。
            return UnlockOutcome.Unlocked(
                created.carNum,
                helmetFlow = false,
                ride = provisionalRide(created),
            )
        } catch (error: KvcBusinessError) {
            if (error.errorCode == CODE_HELMET_REQUIRED) {
                server { client.helmetUnlockJson(token) }
                return UnlockOutcome.Unlocked(
                    created.carNum,
                    helmetFlow = true,
                    ride = provisionalRide(created),
                )
            }
            throw error
        } catch (error: IOException) {
            // 建单已经成功，开锁请求超时/断网：订单可能已在案。先把卡片和重试出口给用户，
            // 不要让一次网络抖动把人卡在“没有订单、又开不了新车”的中间态。
            return UnlockOutcome.Unconfirmed(created.carNum, provisionalRide(created))
        }
    }

    /**
     * 两段式建单撞上头盔车时的回退：把完整流程跑完（与 [unlockBike] 的头盔分支同一套，
     * 抽出来只为复用，不是第二份口径）。
     */
    private suspend fun fullUnlock(
        created: KqcxAuth.CreatedOrder,
        token: String,
        gcjLat: Double,
        gcjLng: Double,
        locationSource: String,
    ): UnlockOutcome {
        try {
            server { client.helmetUnlockJson(token) }
        } catch (error: IOException) {
            return UnlockOutcome.Unconfirmed(created.carNum, provisionalRide(created))
        }
        return UnlockOutcome.Unlocked(
            created.carNum,
            helmetFlow = true,
            ride = provisionalRide(created),
        )
    }

    /**
     * 重试开锁（订单已在案、只是车锁没确认打开时用）：只重发 `greenCarUnlock`。
     * 与官方「开锁失败，请在面板点开锁重试」同一路径。
     *
     * 车号由控制器从当前骑行卡带入，不再先查在途订单；服务端没有订单会回 12003。
     * 开锁成功后不再等状态落地，直接返回。
     */
    override suspend fun retryUnlock(
        carNum: String,
        gcjLat: Double,
        gcjLng: Double,
        locationSource: String,
    ): UnlockOutcome {
        ensureSession()
        server { client.unlockJson(requireToken(), gcjLat, gcjLng, locationSource) }
        return UnlockOutcome.Unlocked(
            carNum,
            helmetFlow = false,
            ride = null,
        )
    }

    /**
     * 临时锁车（官方骑行面板「锁车」）：订单与计费继续，仅物理锁车。
     *
     * **已锁也照发（2026-10-08 用户口径）**：原「已锁为幂等 no-op」删掉——系统可能把
     * 锁车失败误判成已锁（`lockStatus` 已是 1、车却没锁上），用户再点一次锁车必须
     * 真能重发指令（手动单次；零自动重试红线不变）。车若确实已锁，重发无害、
     * 确认窗第一次查询即通过。
     *
     * **锁车是唯一保留确认窗的动作（2026-10-18）**：用 [LOCK_CONFIRM_ATTEMPTS]（8 次 ≈ 12 秒）——
     * 车把"已锁"上报服务端是异步的（落锁后 IoT 上报常见 3~15 秒），
     * 尤其**开锁后紧接着锁车**（没骑就走）时，上报还排在解锁上报之后，默认窗口几乎必然
     * 等不到，之前被误报成"锁车失败"（当时再点一次会命中客户端幂等早退、假性"成功"）。
     * 锁车免确认、无新增计费后果，多等几秒换确定性是划算的。
     *
     * 指令响应过 [KqcxAuth.parseSuccess]：服务端明确拒绝（车辆离线 / 指令处理中）时
     * 给出具体文案，而不是把一切吞成笼统的"未确认"。
     * 超窗返回 [LockOutcome.Unconfirmed]（**不抛错**）——那是可自愈的中间态。
     */
    override suspend fun temporaryLock(
        gcjLat: Double,
        gcjLng: Double,
        locationSource: String,
    ): LockOutcome {
        ensureSession()
        queryUnderway() ?: throw KvcBusinessError(null, "没有进行中的骑行")
        server { KqcxAuth.parseSuccess(client.lockJson(requireToken(), gcjLat, gcjLng, locationSource)) }
        val confirmed = awaitRide(attempts = LOCK_CONFIRM_ATTEMPTS) { it?.locked == true }
        return if (confirmed != null) {
            LockOutcome.Locked(confirmed)
        } else {
            LockOutcome.Unconfirmed(lastKnown = awaitRideLastKnown())
        }
    }

    /** 确认窗走完后补拉一次现状（尽力而为）：给 [LockOutcome.Unconfirmed] 带上最新快照。 */
    private suspend fun awaitRideLastKnown(): KqcxAuth.Ride? = runCatching { queryUnderway() }.getOrNull()

    /**
     * 还车：未锁先静默锁（`muteCarLock`，尽力而为）→ `endTheOrder`。
     * 成功返回 [KqcxAuth.EndOutcome.Ended]；被拒抛 [KvcBusinessError]（调度费场景带金额）。
     * `12003`（订单已结束）按成功处理——可能是官方渠道或其他端先结束了。
     *
     * **2026-10-18 提速**：不再先查在途订单，也不等锁状态或订单消失。`wasLocked` 由调用方
     * 用当前骑行快照给出；未知时照发静默锁（多一条幂等指令，比多等 4.5 秒划算）。
     * 静默锁失败也继续结束订单，与官方 `doHandleOnReturnBike` 同口径。
     * `locationSource` / `locationValid` 由调用方按坐标来源传（实时、车辆锚点或 none）。
     */
    override suspend fun returnBike(
        gcjLat: Double,
        gcjLng: Double,
        locationSource: String,
        locationValid: Boolean,
        wasLocked: Boolean?,
    ): KqcxAuth.EndOutcome.Ended {
        ensureSession()
        val token = requireToken()
        if (wasLocked != true) {
            // 静默锁尽力而为：官方即便锁失败也继续走 endTheOrder（订单结束才是关键）
            try {
                server { client.muteLockJson(token, gcjLat, gcjLng, locationSource) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                // 锁失败不阻断还车；服务端以 endTheOrder 为准
            }
        }
        val outcome = KqcxAuth.parseEndOrder(
            client.endOrderJson(token, gcjLat, gcjLng, locationSource, locationValid, nowSeconds()),
        )
        return when (outcome) {
            // 结束订单接口成功就是结束。不再补查“订单消失”，否则高峰期又要白等 4 次。
            is KqcxAuth.EndOutcome.Ended -> outcome
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
        } catch (e: CancellationException) {
            throw e // 取消必须穿透：吞掉会破坏调用方的结构化并发
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
     * 骑行卡券（2026-10-22）：开锁前「有没有可用卡」的判据来源，只读、单接口。
     * 与 [queryAssets] 里的卡券那一支同源（`queryUserCoupon`）；不并行拉余额 / 会员，
     * 因为这条只在开锁前跑一次。
     */
    override suspend fun queryCoupons(): List<KqcxAuth.AssetCoupon> {
        ensureSession()
        return server { KqcxAuth.parseCoupons(client.userCouponJson(requireToken())) }
    }

    /**
     * 按车号查单车（2026-09-30）：附近列表里没有目标车时的兜底（官方 `loadOneCar` 同款）。
     * token 允许空——这个读接口不需要凭证（与 [queryZones] 同口径），小程序方式也能用
     * 「按车号定位」。**不抛错**：服务端业务错误（车已出租 / 维修 / 不存在）与网络失败
     * 都收在 [CarDetailResult] 里，由调用方决定怎么说（2026-10-11 起业务错误要原样转达）。
     */
    suspend fun queryCarDetail(
        carNum: String,
        centerLat: Double,
        centerLng: Double,
    ): CarDetailResult {
        try {
            return BikeNearby.parseCarDetail(
                client.oneCarJson(token.orEmpty(), carNum, centerLat, centerLng),
                centerLat,
                centerLng,
            )
        } catch (e: CancellationException) {
            throw e // 取消必须穿透：吞掉会破坏调用方的结构化并发
        } catch (_: Exception) {
            // 网络 / 超时：与"服务端明确说不可用"区分开，按"没查到"处理
            return CarDetailResult.Malformed
        }
    }

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
        if (!ensureRelogin()) {
            throw KvcBusinessError(null, "快趣未登录")
        }
    }

    /**
     * 用本地凭证重登。返回 false = 无凭证或重登失败（登录态同步翻转）。
     * 在 [ensureRelogin] 的单飞保护下执行。
     */
    private suspend fun reloginFromCredentials(): Boolean {
        val (mobile, password) = credentials.readCredential()
            ?: run {
                _loggedIn.value = false
                return false
            }
        return try {
            // 静默重登必须 remember = true：凭证本来就在盘上，不能因这次重登把它清掉
            //（2026-10-21 修「更新后偶尔掉登录」：旧版走默认 false，每次静默重登都清凭证）
            login(mobile, password, remember = true)
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // 凭证失效（改密/注销）这类业务失败：退出登录态，让 UI 回登录表单。
            // 同时留一条「已过期」标记：下次进页要告诉用户为什么退回登录表单。
            if (error is KvcBusinessError) {
                expiry?.mark(SessionService.Kqcx)
                _loggedIn.value = false
            }
            false
        }
    }

    /**
     * 单飞重登：同一时刻只有一次登录请求，后来的调用方直接等这一次的结果。
     * 旧写法让等待方在锁释放后再执行一遍 `reloginFromCredentials`，并发时会连续登录两次。
     */
    private suspend fun ensureRelogin(): Boolean {
        while (true) {
            reloginJob.get()?.let { return it.await() }
            val job = CompletableDeferred<Boolean>()
            if (!reloginJob.compareAndSet(null, job)) continue
            return try {
                val ok = reloginFromCredentials()
                job.complete(ok)
                ok
            } catch (error: CancellationException) {
                job.complete(false)
                throw error
            } catch (_: Throwable) {
                job.complete(false)
                false
            } finally {
                reloginJob.compareAndSet(job, null)
            }
        }
    }

    /**
     * 有界确认：查询在案订单直到 [predicate] 成立（≤[attempts] 次、
     * [CONFIRM_INTERVAL_MS] 间隔）。**只用于临时锁车的落地确认，不是常驻轮询**；
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

    /**
     * 开锁成功后立刻给卡片的“最小订单”：车号 + 未锁，加上 createOrder 响应里已有的
     * 时长 / 费用 / 电量。缺的字段由后台刷新补全，已有字段不让它先空一截。
     */
    private fun provisionalRide(created: KqcxAuth.CreatedOrder): KqcxAuth.Ride =
        KqcxAuth.Ride(
            carNum = created.carNum,
            bluetoothName = null,
            totalDateSeconds = created.totalDateSeconds,
            payMoneyCents = created.payMoneyCents,
            locked = false,
            batteryPercent = created.batteryPercent,
        )

    /**
     * 两段式（只建单不发票）的最小订单：与 [provisionalRide] 同构，但 `locked = true`
     * ——没发过开锁指令，车保持已锁；解锁由用户点「解锁」对在案订单重发。
     */
    private fun lockedRide(created: KqcxAuth.CreatedOrder): KqcxAuth.Ride =
        provisionalRide(created).copy(locked = true)

    /** 官方 `Math.ceil(ts/1e3)` 同口径的秒级时间戳。 */
    private fun nowSeconds(): Long = (System.currentTimeMillis() + 999) / 1000

    private fun isTokenError(error: Throwable): Boolean = when (error) {
        is KvcBusinessError -> KqcxAuth.looksLikeTokenError(error.message)
        is KvcProtocolException -> false
        is IOException -> false
        else -> false
    }

    private companion object {
        /** 服务端「订单已结束」（官方 case 12003）。 */
        const val CODE_ORDER_ENDED = 12003

        /** 服务端「需先取头盔」（官方 16015 分支）。 */
        const val CODE_HELMET_REQUIRED = 16015

        /** 临时锁车确认的查询间隔（官方 1 秒级节奏取整）。 */
        const val CONFIRM_INTERVAL_MS = 1_500L

        /**
         * 临时锁车的确认次数（× [CONFIRM_INTERVAL_MS] ≈ 12 秒）：车把"已锁"上报服务端
         * 是异步的（3~15 秒常态），短窗口几乎必然等不到（2026-10-08 修
         * 「开锁后临时锁车容易失败」）。开锁/还车不再阻塞等待（2026-10-18）。
         */
        const val LOCK_CONFIRM_ATTEMPTS = 8

        /** 还车后扣款确认的轮询间隔。 */
        const val SETTLE_POLL_INTERVAL_MS = 2_500L
    }
}
