package edu.jxslu.schedule.domain

/**
 * 用车满 15 分钟自动还车（DESIGN §3.9 / §4.32，2026-10-10 用户口径）。
 *
 * 账号登录方式下，App 在免费时段结束前自动发起还车：
 * - **触发点 = 开锁 + [EbikeFreeRide.FREE_MINUTES] 分钟 − 提前量**（默认
 *   [DEFAULT_LEAD_SECONDS] 秒，可设 [LEAD_MIN_SECONDS]~[LEAD_MAX_SECONDS] 步长
 *   [LEAD_STEP_SECONDS]）。**不许卡在免费结束那一刻或之后**——那之后的还车落在
 *   免费时段外、订单开始计费（2026-10-10 用户明确）。
 * - 失败**每次尝试结果都发通知**（原因逐条写清，如「当前位置不在还车区域内」），
 *   并在上一次失败后 [RETRY_INTERVAL_MS] 内重试，最多再试 [RETRY_MIN]~[RETRY_MAX]
 *   次（默认 [DEFAULT_RETRIES] 次）。
 * - 还车坐标**优先用骑行页留下的最近用户定位**（[USER_FIX_MAX_AGE_MS] 内，来源 `session`），
 *   缺失/过期才用车辆坐标（`KqcxAuth.Ride.lat` / `.lng`，来源 `vehicle`）——手动还车走的是
 *   同一条路径，能避开车辆位置上报滞后造成的「明明在还车点却报 50011」。遇到 50011 换另一个
 *   来源再试。**不主动请求手机定位**，只读页面的进程内缓存。
 * - 开关**默认关**：它替用户执行有计费后果的写动作，只能显式开启。
 *
 * 本文件是纯 JVM 的时刻计算、钳制与文案（与 [EbikeFreeRide] 同款），不含 Android API。
 */
object EbikeAutoReturn {

    /** 提前量下限（秒）。 */
    const val LEAD_MIN_SECONDS = 10

    /** 提前量上限（秒）。 */
    const val LEAD_MAX_SECONDS = 60

    /** 提前量步长（秒）。 */
    const val LEAD_STEP_SECONDS = 5

    /** 提前量默认值（秒）——用户拍板 2026-10-10。 */
    const val DEFAULT_LEAD_SECONDS = 30

    /** 重试次数下限（次）。 */
    const val RETRY_MIN = 1

    /** 重试次数上限（次）。 */
    const val RETRY_MAX = 3

    /** 重试次数默认值（次）——用户拍板 2026-10-10。 */
    const val DEFAULT_RETRIES = 2

    /**
     * 两次尝试之间的间隔：上一次失败后 3 秒内重试（2026-10-10 用户口径）。
     * 取 3 秒这个上限——失败多为「不在还车点 / 网络抖动」，快速重试、不占后台太久；
     * 也保证整串尝试落在触发点与免费结束之间的窗口里。
     */
    const val RETRY_INTERVAL_MS = 3_000L

    /** 自动还车结果通知的 id 与 channel（与上课 / 作业 / 免费提醒的 id 错开，单测钉住）。 */
    object NotificationIds {
        /** 结果通知（每次尝试一条，同 id 覆盖；点它回「骑行」页）。 */
        const val RESULT = 1014

        /**
         * 结果通知 channel。**新 id**：channel 建出来后 importance 与震动都改不动
         * （与免费提醒 `_v2` 同一踩坑，见 EbikeFreeRide.NotificationIds 的 KDoc）。
         */
        const val CHANNEL = "ebike_auto_return"
    }

    /** 结果通知标题。 */
    const val TITLE_SUCCESS = "已自动还车"
    const val TITLE_FAILURE = "自动还车失败"

    /** 未登录（凭证失效）时的失败文案：重试救不了，只能让用户手动处理。 */
    const val NOT_LOGGED_IN_TEXT = "快趣登录已失效，请在「骑行」页重新登录后手动还车"

    /** 用户定位最多用 5 分钟（与 `BikeLocator` 的缓存定位同口径）。 */
    const val USER_FIX_MAX_AGE_MS = 5 * 60_000L

    /** 重试次数吸附到 [RETRY_MIN]..[RETRY_MAX]（防线脏数据）。 */
    fun coerceRetries(retries: Int): Int = retries.coerceIn(RETRY_MIN, RETRY_MAX)

    /** 提前量吸附到 [LEAD_MIN_SECONDS]..[LEAD_MAX_SECONDS] 并对齐 [LEAD_STEP_SECONDS] 步长。 */
    fun coerceLeadSeconds(seconds: Int): Int {
        val clamped = seconds.coerceIn(LEAD_MIN_SECONDS, LEAD_MAX_SECONDS)
        val snapped = ((clamped + LEAD_STEP_SECONDS / 2) / LEAD_STEP_SECONDS) * LEAD_STEP_SECONDS
        return snapped.coerceIn(LEAD_MIN_SECONDS, LEAD_MAX_SECONDS)
    }

    /** 总尝试次数 = 1 次首发 + 重试次数。 */
    fun totalAttempts(retries: Int): Int = coerceRetries(retries) + 1

    /**
     * 触发时刻（epoch 毫秒）= 免费结束（[EbikeFreeRide.freeEndMillis]）− 提前量。
     * 提前量下限 10 秒保证它**恒在免费结束之前**——这正是「还车要落在免费时段内」的口径。
     */
    fun triggerAtMillis(startAtMillis: Long, leadSeconds: Int): Long =
        EbikeFreeRide.freeEndMillis(startAtMillis) - coerceLeadSeconds(leadSeconds) * 1_000L

    /** 还车请求要带的坐标与来源（纯数据，JVM 可测）。 */
    data class ReturnPoint(
        val lat: Double,
        val lng: Double,
        val source: String,
        val valid: Boolean,
    )

    private fun isValidCoord(lat: Double, lng: Double): Boolean =
        lat.isFinite() && lng.isFinite() &&
            lat in -90.0..90.0 && lng in -180.0..180.0 &&
            !(lat == 0.0 && lng == 0.0)

    /**
     * 选自动还车的坐标（2026-10-19）：
     * - [preferUser] 且用户定位新鲜 → 用户坐标（来源 `session`；与手动还车同源）；
     * - 否则车辆坐标有效 → 车辆坐标（来源 `vehicle`）；
     * - 车辆坐标无效但用户定位新鲜 → 用户坐标；
     * - 都没有 → 0,0 + `none`（与手动还车的无效兜底同口径）。
     *
     * 默认先用用户定位：车辆位置上报可能滞后，用户在还车点却报 50011 时换个来源
     * 再试一次；调用方在 50011 后把 [preferUser] 翻到另一侧。车辆已锁（[vehicleLocked] =
     * true）时**不用用户定位**——人可能已经走开，车停在哪就该用哪个坐标。
     */
    fun chooseReturnPoint(
        vehicleLat: Double?,
        vehicleLng: Double?,
        userLat: Double?,
        userLng: Double?,
        userFixAtMillis: Long?,
        nowMillis: Long,
        vehicleLocked: Boolean? = null,
        preferUser: Boolean = true,
    ): ReturnPoint {
        val userFresh = userLat != null && userLng != null && userFixAtMillis != null &&
            isValidCoord(userLat, userLng) &&
            nowMillis - userFixAtMillis in 0..USER_FIX_MAX_AGE_MS
        if (preferUser && userFresh && vehicleLocked != true) {
            return ReturnPoint(userLat!!, userLng!!, source = "session", valid = true)
        }
        if (vehicleLat != null && vehicleLng != null && isValidCoord(vehicleLat, vehicleLng)) {
            return ReturnPoint(vehicleLat, vehicleLng, source = "vehicle", valid = true)
        }
        if (userFresh) {
            return ReturnPoint(userLat!!, userLng!!, source = "session", valid = true)
        }
        return ReturnPoint(0.0, 0.0, source = "none", valid = false)
    }

    /**
     * 该不该在 [nowMillis] 执行本轮自动还车：已到触发点，且没越过**迟到窗口**
     * （免费结束后 [EbikeFreeRide.END_WINDOW_MS]，与免费提醒的补发窗口同口径）。
     *
     * 触发点恒早于免费结束；迟到窗口只兜系统延迟——闹钟 / 任务晚到十几秒仍要还车
     * （否则这一轮白开），拖过窗口就不再追（那时订单已经计费，交给失败通知与手动处理）。
     */
    fun isDue(startAtMillis: Long, leadSeconds: Int, nowMillis: Long): Boolean {
        if (startAtMillis <= 0L) return false
        return nowMillis >= triggerAtMillis(startAtMillis, leadSeconds) &&
            nowMillis < EbikeFreeRide.freeEndMillis(startAtMillis) + EbikeFreeRide.END_WINDOW_MS
    }

    /**
     * 本轮骑行的「已执行」键：自动还车对同一轮骑行只跑一次（成功、重试用尽、
     * 或不该重试的失败都落这个键）。键里带起点——换车后是新键，不会被上一轮吃掉。
     */
    fun doneKey(startAtMillis: Long): String = "auto_return|$startAtMillis"

    /** 失败尝试的走向（决定文案后缀）。 */
    enum class RetryState {
        /** 还会再试。 */
        RETRYING,

        /** 重试次数已用完。 */
        EXHAUSTED,

        /** 此类失败重试无用（调度费 / 支付分授权 / 登录失效）。 */
        ABORTED,
    }

    /**
     * 单次失败的通知文案（每次尝试结果都发通知，2026-10-10 用户口径）。
     * [reason] 用服务端 / 网络异常的用户可读文本（调用方已转译）。
     */
    fun failureText(
        reason: String,
        attempt: Int,
        total: Int,
        state: RetryState,
    ): String = when (state) {
        RetryState.RETRYING ->
            "第 $attempt/$total 次还车失败：$reason；${RETRY_INTERVAL_MS / 1_000} 秒后自动重试"
        RetryState.EXHAUSTED ->
            "第 $attempt/$total 次还车失败：$reason；已用完重试次数，请在「骑行」页手动还车"
        RetryState.ABORTED ->
            "第 $attempt/$total 次还车失败：$reason；此类失败重试无用，请在「骑行」页手动处理"
    }

    /** 成功通知文案：时长 / 费用是可选段（拿不到就不写）。 */
    fun successText(durationText: String?, feeText: String?): String {
        val detail = listOfNotNull(durationText, feeText).joinToString("，")
        return if (detail.isEmpty()) {
            "订单已结束，车辆已上锁"
        } else {
            "订单已结束（$detail），车辆已上锁"
        }
    }

    /**
     * 这次失败值不值得重试：调度费（官方要用户在小程序确认面板里接受）与支付分授权
     * （11035，微信客户端专属流程）都不是「再试一次能好」的——直接停止并通知。
     * 其余（网络 / 超时 / 定位不在还车区 / 协议异常等）一律重试，次数由设置封顶。
     */
    fun isRetryable(error: Throwable): Boolean = when (error) {
        is KvcBusinessError ->
            error.dispatchMoneyCents == null && error.errorCode != KqcxAuth.CODE_SCORE_AUTH_REQUIRED
        else -> true
    }
}
