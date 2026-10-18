package edu.jxslu.schedule.ui.ebike

import edu.jxslu.schedule.data.kqcx.KvcxRideSession
import edu.jxslu.schedule.domain.EbikeAutoReturn
import edu.jxslu.schedule.domain.KqcxAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * 自动还车的尝试循环与还车结算口径（DESIGN §3.9，2026-10-10）。
 *
 * **为什么单独一层**：`CoroutineWorker` 带着 Android 依赖，JVM 单测跑不了；而「试几次、
 * 隔几秒、哪些失败不重试、每次结果都上报」恰恰是这条链路的责任边界。这里只依赖
 * [KvcxRideSession] 接口与回调，于是可以在单测里用假会话把整条时序钉住
 * （口径同 `KvcxRideController` 的依赖收窄）。
 *
 * **与「写操作零自动重试」的关系**：仓库层的零重试红线（用户手动动作只发一次）不变；
 * 这里的重试是**用户显式开启的自动还车**自己的行为——次数由设置封顶
 * （[EbikeAutoReturn.RETRY_MIN]~[EbikeAutoReturn.RETRY_MAX]），且只在免费时段内跑。
 * 别把它当成"顺手给手动还车也加上重试"的依据。
 */

/** 一次自动还车过程的最终结果。 */
internal sealed interface AutoReturnResult {

    /** 还车成功（订单结束）。[summary] 与手动还车共用同一份结算口径。 */
    data class Returned(val summary: KvcxReturnSummary) : AutoReturnResult

    /** 还车前现查到订单已不在（其他端已结束 / 已还）：按成功口径收干净，不打扰。 */
    data object AlreadyEnded : AutoReturnResult

    /** 未登录（凭证失效）：重试无意义，直接停。 */
    data object NotLoggedIn : AutoReturnResult

    /** 重试用尽或不该重试的失败。[attempts] = 实际尝试次数。 */
    data class Failed(val lastError: Throwable, val attempts: Int) : AutoReturnResult
}

/**
 * 自动还车主循环：最多 [retries] + 1 次尝试，每次失败后隔 [retryDelayMs] 重试。
 *
 * 每一拍都**现查一次在案订单**：一是确认订单还在（其他端还了就不再动作），二是拿车辆
 * 最新上报的坐标——还车判区只看车辆位置（用户口径），快照里的 `lat`/`lng` 就是它。
 *
 * [onOrderEnded] 在还车请求成功、**扣款确认之前**调用（本地走时要在订单结束那一刻截停，
 * 口径见 [performReturnBike]）；[onAttemptFailed] 每次失败都调（每次尝试结果都发通知，
 * 2026-10-10 用户口径）。
 *
 * **取消穿透**：工作被取消时原样抛出，绝不把取消报成"还车失败"并继续重试。
 */
internal suspend fun runAutoReturn(
    session: KvcxRideSession,
    retries: Int,
    onOrderEnded: suspend () -> Unit,
    onAttemptFailed: suspend (
        error: Throwable,
        attempt: Int,
        total: Int,
        state: EbikeAutoReturn.RetryState,
    ) -> Unit,
    retryDelayMs: Long = EbikeAutoReturn.RETRY_INTERVAL_MS,
): AutoReturnResult {
    if (!session.loggedIn.value) return AutoReturnResult.NotLoggedIn
    val total = EbikeAutoReturn.totalAttempts(retries)
    var attempt = 0
    var lastError: Throwable? = null
    while (attempt < total) {
        attempt++
        val ride = try {
            session.queryUnderway()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            lastError = error
            val state = retryState(error, attempt, total)
            onAttemptFailed(error, attempt, total, state)
            if (state != EbikeAutoReturn.RetryState.RETRYING) {
                return AutoReturnResult.Failed(error, attempt)
            }
            delay(retryDelayMs)
            continue
        }
        if (ride == null) return AutoReturnResult.AlreadyEnded
        try {
            val lat = ride.lat
            val lng = ride.lng
            val coordsValid = lat != null && lng != null && isValidReturnCoord(lat, lng)
            val summary = performReturnBike(
                session = session,
                before = ride,
                fetchedAt = System.currentTimeMillis(),
                // 还车判区只看车辆自己上传的位置；快照缺坐标时给 0（服务端以车辆位置为准，
                // 这两个字段只是随请求带上的记录）——不因缺坐标拦下还车
                gcjLat = lat ?: 0.0,
                gcjLng = lng ?: 0.0,
                locationSource = if (coordsValid) "vehicle" else "none",
                locationValid = coordsValid,
                onOrderEnded = onOrderEnded,
            )
            return AutoReturnResult.Returned(summary)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            lastError = error
            val state = retryState(error, attempt, total)
            onAttemptFailed(error, attempt, total, state)
            if (state != EbikeAutoReturn.RetryState.RETRYING) {
                return AutoReturnResult.Failed(error, attempt)
            }
            delay(retryDelayMs)
        }
    }
    return AutoReturnResult.Failed(
        lastError ?: IllegalStateException("自动还车未执行"),
        attempt,
    )
}

private fun isValidReturnCoord(lat: Double, lng: Double): Boolean =
    lat.isFinite() && lng.isFinite() &&
        lat in -90.0..90.0 && lng in -180.0..180.0 &&
        !(lat == 0.0 && lng == 0.0)

/**
 * 还车 + 结算口径的**唯一实现**（手动还车 [KvcxRideController.returnBike] 与自动还车共用，
 * 结算卡字段与本地记录不能两处各算各的）。
 *
 * 顺序纪律：还车请求成功 → 立刻记 [onOrderEnded]（收计时 / 焚码；本地走时也在这一刻
 * 截停，见 `endedAt`）→ 扣款确认（自动还车等最多 3 次，不能算进本次骑行时长；手动还车
 * 传 `pollSettlement=false`，结果卡先出）→ 组装 [KvcxReturnSummary]。
 * **别把 `onOrderEnded` 挪到轮询之后**——那会把时长多算一截。
 */
internal suspend fun performReturnBike(
    session: KvcxRideSession,
    before: KqcxAuth.Ride?,
    fetchedAt: Long,
    gcjLat: Double,
    gcjLng: Double,
    locationSource: String = "realtime",
    locationValid: Boolean = true,
    /**
     * true = 等待扣款确认再返回（自动还车要拿准确结果记账）；
     * false = 结果卡先出，调用方在后台查（手动还车的提速路径）。
     */
    pollSettlement: Boolean = true,
    onOrderEnded: suspend () -> Unit,
): KvcxReturnSummary {
    val outcome = session.returnBike(
        gcjLat = gcjLat,
        gcjLng = gcjLng,
        locationSource = locationSource,
        locationValid = locationValid,
        wasLocked = before?.locked,
    )
    // 本地走时在订单结束那一刻截停（2026-10-09 口径，原样搬自 KvcxRideController.returnBike）
    val endedAt = System.currentTimeMillis()
    onOrderEnded()
    // 官方扣款确认节奏：先享后付自动扣，短轮询一次看是否结清（不做支付本身）。
    // 欠费金额一并带回来——结果卡上显示「¥3.50」比"有未结算费用"有用得多
    val payState = if (pollSettlement && outcome.needPay == true) {
        session.confirmUnpaidSettled()
    } else {
        null
    }
    val settling = !pollSettlement && outcome.needPay == true
    return KvcxReturnSummary(
        carNum = before?.carNum,
        durationSeconds = before?.let {
            rideElapsedSeconds(it, fetchedAt, endedAt)
        },
        feeCents = before?.payMoneyCents,
        needPay = outcome.needPay,
        settled = when {
            outcome.needPay == false -> true
            payState is KqcxAuth.UnpayState.Settled -> true
            outcome.needPay == null -> null
            else -> false // Owed 或查询失败：都按"未结清"提示，让用户去核对
        },
        owedCents = (payState as? KqcxAuth.UnpayState.Owed)?.amountCents,
        settling = settling,
    )
}

/** 这一拍失败之后往哪走：不可重试 / 还有额度接着试 / 额度已尽。 */
private fun retryState(
    error: Throwable,
    attempt: Int,
    total: Int,
): EbikeAutoReturn.RetryState = when {
    !EbikeAutoReturn.isRetryable(error) -> EbikeAutoReturn.RetryState.ABORTED
    attempt >= total -> EbikeAutoReturn.RetryState.EXHAUSTED
    else -> EbikeAutoReturn.RetryState.RETRYING
}
