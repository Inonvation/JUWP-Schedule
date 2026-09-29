package edu.jxslu.schedule.data.kqcx

import edu.jxslu.schedule.domain.KqcxAuth
import edu.jxslu.schedule.domain.KvcxZones
import kotlinx.coroutines.flow.StateFlow

/** 开锁结果（车锁确认是异步的：指令发出 ≠ 车开了）。 */
sealed interface UnlockOutcome {
    /** 已确认开锁。[helmetFlow] = 走的是头盔流程（UI 提示「取下车盔」）。 */
    data class Unlocked(val carNum: String, val helmetFlow: Boolean) : UnlockOutcome

    /** 订单已创建，但车锁未确认打开——调用方提示并提供「重试开锁」出口（官方同款）。 */
    data class Unconfirmed(val carNum: String) : UnlockOutcome
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
     * **零自动重试**由实现方保证（超时抛错，调用方刷新状态后决定）。
     */
    suspend fun unlockBike(carNum: String, gcjLat: Double, gcjLng: Double): UnlockOutcome

    /** 对**在案订单**重发解锁指令（临时锁车后继续骑 / 开锁未确认时重试，同一调用两种文案）。 */
    suspend fun retryUnlock(gcjLat: Double, gcjLng: Double): UnlockOutcome

    /** 临时锁车（订单与计费继续，仅物理锁车）。 */
    suspend fun temporaryLock(gcjLat: Double, gcjLng: Double): KqcxAuth.Ride

    /** 还车：静默锁 + 结束订单。调度费/出围栏由实现方抛错降级官方渠道。 */
    suspend fun returnBike(gcjLat: Double, gcjLng: Double): KqcxAuth.EndOutcome.Ended

    /**
     * 还车后的结算确认（先享后付自动扣，短轮询看是否结清）；**不做支付本身**。
     * 返回 [KqcxAuth.UnpayState.Settled] = 已结清；[KqcxAuth.UnpayState.Owed] = 还欠着
     * （带金额，UI 显示出来）；null = 查询失败（交给用户去小程序核对）。
     */
    suspend fun confirmUnpaidSettled(attempts: Int = 3): KqcxAuth.UnpayState?
}
