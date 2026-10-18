package edu.jxslu.schedule.data.kqcx

import edu.jxslu.schedule.domain.KqcxAuth
import edu.jxslu.schedule.domain.KvcxZones
import kotlinx.coroutines.flow.StateFlow

/** 开锁结果（车锁确认是异步的：指令发出 ≠ 车开了）。 */
sealed interface UnlockOutcome {
    /**
     * 写接口已接受开锁。[helmetFlow] = 走的是头盔流程（UI 提示「取下车盔」）。
     *
     * [ride] 可能是**最小订单**（车号 + 未锁）：2026-10-18 起写成功就返回，不再等
     * `queryUnderwayOrder` 落地；`KvcxRideController.unlock` 先采纳它把骑行卡摆上，
     * 时长 / 电量等字段由后台 `syncAfterUnlock` 补全。`retryUnlock` / `resumeRide`
     * 走各自的乐观翻转口径，不消费它。
     */
    data class Unlocked(
        val carNum: String,
        val helmetFlow: Boolean,
        val ride: KqcxAuth.Ride? = null,
    ) : UnlockOutcome

    /**
     * 订单已创建，但车锁未确认打开——调用方提示并提供「重试开锁」出口（官方同款）。
     * 网络超时/断网时会带 [ride] 最小订单，先把骑行卡摆出来，避免用户卡在“有订单但看不到”。
     */
    data class Unconfirmed(
        val carNum: String,
        /** 订单已创建时的最小快照；网络超时路径用它先把骑行卡摆出来。 */
        val ride: KqcxAuth.Ride? = null,
    ) : UnlockOutcome
}

/**
 * 临时锁车结果（2026-10-08）。**形态镜像 [UnlockOutcome]**：车把"已锁"上报服务端是异步的
 * （落锁后 IoT 上报常见 3~15 秒），确认窗口内没等到 ≠ 锁车失败——锁车无新增计费后果、
 * 再点一次锁车即重发指令（手动单次），"未确认"应该是一个**可自愈的中间态**而不是错误：
 * 调用方置「锁车确认中」，后续刷新到已锁即消解。
 */
sealed interface LockOutcome {
    /** 已确认车辆锁上（拿到确认后的订单状态）。 */
    data class Locked(val ride: KqcxAuth.Ride) : LockOutcome

    /**
     * 窗口内未确认到"已锁"。[lastKnown] = 窗口内最后一次拉到的订单快照（无则 null），
     * 供调用方刷新 UI；**不要**把它当"锁车失败"的事实。
     */
    data class Unconfirmed(val lastKnown: KqcxAuth.Ride?) : LockOutcome
}

/**
 * 还车点 / 禁停区图层的数据源（DESIGN §3.9，2026-09-28）：地图页专用，只读。
 * 与 [KvcxRideSession] 分开是因为用途不同（一个是骑行状态与写动作，一个是地图图层）。
 */
interface KvcxZoneSource {
    /**
     * 拉一次图层。返回 null = 不可用（未登录 / 没车号做上下文 / 请求失败）——
     * 图层是装饰，调用方静默保留上一层。
     *
     * [carNum] 是"以哪辆车为上下文"（官方在还车点页传选中车、在地图页传最近一辆车）。
     */
    suspend fun queryZones(lat: Double, lng: Double, carNum: String?): KvcxZones?
}

/**
 * 本机用车需要的快趣能力（DESIGN §4.32）：**收窄成接口是为了能在单测里假造**。
 *
 * [KqcxSessionRepository] 直接依赖网络与 DataStore，测不了编排；而「开锁成功要顺手打开
 * 提醒开关」「还车要出结果卡并记账」这类编排恰恰是责任边界（写操作四道闸），值得用测试钉住。
 * 实现方只有 [KqcxSessionRepository] 一个——**别在别处再实现一遍**，那样又变成两份口径。
 */
interface KvcxRideSession {

    /** 登录态（会话不可用时由持有方给一个恒 false 的流，不在这里表达）。 */
    val loggedIn: StateFlow<Boolean>

    /** 查进行中订单；无订单返回 null（含服务端 12003「订单已结束」）。 */
    suspend fun queryUnderway(): KqcxAuth.Ride?

    /**
     * 开锁：`createOrder` → （头盔流程 | `greenCarUnlock`）。
     * 写接口成功即返回 [UnlockOutcome.Unlocked]；**零自动重试**由实现方保证
     * （超时抛错，调用方刷新状态后决定）。
     */
    suspend fun unlockBike(
        carNum: String,
        gcjLat: Double,
        gcjLng: Double,
        locationSource: String = "realtime",
    ): UnlockOutcome

    /**
     * 对**在案订单**重发解锁指令（临时锁车后继续骑 / 开锁未确认时重试，同一调用两种文案）。
     * 车号由调用方从当前快照带入，不再先查在途订单；指令成功即返回，不再等锁状态落地。
     */
    suspend fun retryUnlock(
        carNum: String,
        gcjLat: Double,
        gcjLng: Double,
        locationSource: String = "realtime",
    ): UnlockOutcome

    /**
     * 临时锁车（订单与计费继续，仅物理锁车）。**已锁也照发**（用户重按 = 重发指令，
     * 防「误判已锁」后锁不上；见实现注释）。
     * 返回 [LockOutcome.Locked] = 已确认锁上；[LockOutcome.Unconfirmed] = 指令已发但
     * 窗口内未确认到（**不是失败**——上报慢是常态，见 [LockOutcome] 的说明）。
     */
    suspend fun temporaryLock(
        gcjLat: Double,
        gcjLng: Double,
        locationSource: String = "realtime",
    ): LockOutcome

    /**
     * 还车：静默锁 + 结束订单。调度费/出围栏由实现方抛错降级官方渠道。
     *
     * [wasLocked] 是调用方当前快照里的锁状态：`true` 可跳过静默锁；`null` 按未知处理，
     * 仍发一次静默锁。`locationSource` 可为 `realtime` / `session` / `vehicle` / `none`，
     * `locationValid` 只在有有效坐标时为 true。`endTheOrder` 成功即返回，不再等订单消失。
     */
    suspend fun returnBike(
        gcjLat: Double,
        gcjLng: Double,
        locationSource: String = "realtime",
        locationValid: Boolean = true,
        wasLocked: Boolean? = null,
    ): KqcxAuth.EndOutcome.Ended

    /**
     * 还车后的结算确认（先享后付自动扣，短轮询看是否结清）；**不做支付本身**。
     * 返回 [KqcxAuth.UnpayState.Settled] = 已结清；[KqcxAuth.UnpayState.Owed] = 还欠着
     * （带金额，UI 显示出来）；null = 查询失败（交给用户去小程序核对）。
     */
    suspend fun confirmUnpaidSettled(attempts: Int = 3): KqcxAuth.UnpayState?

    /**
     * 响铃寻车（2026-09-30）：让**在案订单**的车鸣笛，帮用户从车堆里认出它。
     * 无参数（服务端按当前订单定位车辆）、无计费后果——官方骑行页「响铃寻车」同款，
     * 不属于写操作四道闸的对象，但仍走动作的 single-flight。
     */
    suspend fun ringFindCar()
}
