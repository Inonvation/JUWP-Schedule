package edu.jxslu.schedule

import edu.jxslu.schedule.data.kqcx.KvcxRideSession
import edu.jxslu.schedule.domain.EbikeAutoReturn
import edu.jxslu.schedule.domain.EbikeFreeRide
import edu.jxslu.schedule.domain.KqcxAuth
import edu.jxslu.schedule.domain.KvcBusinessError
import edu.jxslu.schedule.ui.ebike.AutoReturnResult
import edu.jxslu.schedule.ui.ebike.runAutoReturn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * 用车满 15 分钟自动还车（DESIGN §3.9，2026-10-10 用户口径）。
 *
 * 两块：纯计算的时刻 / 钳制 / 文案（domain），与尝试循环的时序（试几次、隔多久、
 * 哪些失败不重试、每次失败都上报）。循环用假会话在 JVM 里跑，不碰 Android。
 */
class EbikeAutoReturnTest {

    // ---------- 时刻与钳制 ----------

    @Test
    fun `提前量吸附区间与步长`() {
        assertEquals(10, EbikeAutoReturn.coerceLeadSeconds(0))
        assertEquals(10, EbikeAutoReturn.coerceLeadSeconds(9))
        assertEquals(10, EbikeAutoReturn.coerceLeadSeconds(12))
        assertEquals(15, EbikeAutoReturn.coerceLeadSeconds(13))
        assertEquals(30, EbikeAutoReturn.coerceLeadSeconds(30))
        assertEquals(35, EbikeAutoReturn.coerceLeadSeconds(37))
        assertEquals(40, EbikeAutoReturn.coerceLeadSeconds(38))
        assertEquals(55, EbikeAutoReturn.coerceLeadSeconds(57))
        assertEquals(60, EbikeAutoReturn.coerceLeadSeconds(58))
        assertEquals(60, EbikeAutoReturn.coerceLeadSeconds(999))
    }

    @Test
    fun `触发点恒在免费结束之前`() {
        val start = 1_760_000_000_000L
        val end = EbikeFreeRide.freeEndMillis(start)
        // 默认 30 秒：14:30 触发，15:00 免费结束
        assertEquals(end - 30_000L, EbikeAutoReturn.triggerAtMillis(start, 30))
        // 全区间都严格早于免费结束（用户口径：不许卡在 15 分钟或之后）
        for (lead in EbikeAutoReturn.LEAD_MIN_SECONDS..EbikeAutoReturn.LEAD_MAX_SECONDS step 5) {
            assertTrue(EbikeAutoReturn.triggerAtMillis(start, lead) < end)
        }
    }

    @Test
    fun `总尝试次数等于重试次数加一`() {
        assertEquals(2, EbikeAutoReturn.totalAttempts(1))
        assertEquals(3, EbikeAutoReturn.totalAttempts(2))
        assertEquals(4, EbikeAutoReturn.totalAttempts(3))
        // 脏数据吸附：0 → 1（共 2 次），9 → 3（共 4 次）
        assertEquals(2, EbikeAutoReturn.totalAttempts(0))
        assertEquals(4, EbikeAutoReturn.totalAttempts(9))
    }

    @Test
    fun `执行窗口从触发点到免费结束后五分钟`() {
        val start = 1_760_000_000_000L
        val end = EbikeFreeRide.freeEndMillis(start)
        val trigger = EbikeAutoReturn.triggerAtMillis(start, EbikeAutoReturn.DEFAULT_LEAD_SECONDS)
        assertTrue(!EbikeAutoReturn.isDue(start, 30, trigger - 1))
        assertTrue(EbikeAutoReturn.isDue(start, 30, trigger))
        assertTrue(EbikeAutoReturn.isDue(start, 30, end))
        assertTrue(EbikeAutoReturn.isDue(start, 30, end + EbikeFreeRide.END_WINDOW_MS - 1))
        // 拖过迟到窗口就不再追（订单已经计费，交给失败通知与手动处理）
        assertTrue(!EbikeAutoReturn.isDue(start, 30, end + EbikeFreeRide.END_WINDOW_MS))
        assertTrue(!EbikeAutoReturn.isDue(0L, 30, end))
    }

    @Test
    fun `失败文案带原因与次数`() {
        val retrying = EbikeAutoReturn.failureText(
            "当前位置不在还车区域内，请移动到还车点后再还车",
            attempt = 1,
            total = 3,
            state = EbikeAutoReturn.RetryState.RETRYING,
        )
        assertTrue(retrying.contains("1/3"))
        assertTrue(retrying.contains("当前位置不在还车区域内"))
        assertTrue(retrying.contains("3 秒后自动重试"))

        val exhausted = EbikeAutoReturn.failureText(
            "网络不可用",
            attempt = 3,
            total = 3,
            state = EbikeAutoReturn.RetryState.EXHAUSTED,
        )
        assertTrue(exhausted.contains("已用完重试次数"))

        val aborted = EbikeAutoReturn.failureText(
            "该位置还车会产生调度费",
            attempt = 1,
            total = 3,
            state = EbikeAutoReturn.RetryState.ABORTED,
        )
        assertTrue(aborted.contains("重试无用"))
    }

    @Test
    fun `成功文案带时长与费用`() {
        assertEquals("订单已结束，车辆已上锁", EbikeAutoReturn.successText(null, null))
        val full = EbikeAutoReturn.successText("14 分钟", "¥0.00")
        assertTrue(full.contains("14 分钟"))
        assertTrue(full.contains("¥0.00"))
    }

    @Test
    fun `调度费与支付分授权不重试`() {
        assertTrue(EbikeAutoReturn.isRetryable(IOException("断网")))
        assertTrue(EbikeAutoReturn.isRetryable(KvcBusinessError(50011, "不在还车区")))
        assertTrue(!EbikeAutoReturn.isRetryable(KvcBusinessError(50010, "调度费", dispatchMoneyCents = 200)))
        assertTrue(
            !EbikeAutoReturn.isRetryable(
                KvcBusinessError(KqcxAuth.CODE_SCORE_AUTH_REQUIRED, "支付分"),
            ),
        )
    }

    @Test
    fun `结果通知 id 与既有提醒错开`() {
        // 上课 1001/1004、作业 1002/1003、免费提醒 1000/1005/1006、其余 1007~1013
        assertEquals(1014, EbikeAutoReturn.NotificationIds.RESULT)
        assertEquals("ebike_auto_return", EbikeAutoReturn.NotificationIds.CHANNEL)
    }

    // ---------- 尝试循环 ----------

    private class FakeSession(
        override val loggedIn: StateFlow<Boolean> = MutableStateFlow(true),
    ) : KvcxRideSession {

        var underway: KqcxAuth.Ride? = ride()

        /** 前 [failFirst] 次还车抛 [failWith]，之后成功。 */
        var failFirst = 0
        var failWith: Throwable = IOException("断网")

        var returnCalls = 0
        var queryCalls = 0

        override suspend fun queryUnderway(): KqcxAuth.Ride? {
            queryCalls++
            return underway
        }

        override suspend fun unlockBike(
            carNum: String,
            gcjLat: Double,
            gcjLng: Double,
        ) = throw UnsupportedOperationException("自动还车用不到")

        override suspend fun retryUnlock(gcjLat: Double, gcjLng: Double) =
            throw UnsupportedOperationException("自动还车用不到")

        override suspend fun temporaryLock(gcjLat: Double, gcjLng: Double) =
            throw UnsupportedOperationException("自动还车用不到")

        override suspend fun returnBike(gcjLat: Double, gcjLng: Double): KqcxAuth.EndOutcome.Ended {
            returnCalls++
            if (returnCalls <= failFirst) throw failWith
            return KqcxAuth.EndOutcome.Ended(needPay = false, wechatScore = null)
        }

        override suspend fun confirmUnpaidSettled(attempts: Int): KqcxAuth.UnpayState =
            KqcxAuth.UnpayState.Settled

        override suspend fun ringFindCar() = Unit
    }

    private data class Attempt(
        val attempt: Int,
        val total: Int,
        val state: EbikeAutoReturn.RetryState,
    )

    private class RunOutcome(
        val result: AutoReturnResult,
        val attempts: List<Attempt>,
        /** 订单结束善后（收计时 / 焚码）被调起的次数。 */
        val endedCalls: Int,
    )

    private fun run(
        session: FakeSession,
        retries: Int = EbikeAutoReturn.DEFAULT_RETRIES,
    ): RunOutcome {
        val attempts = mutableListOf<Attempt>()
        var ended = 0
        val result = runBlocking {
            runAutoReturn(
                session = session,
                retries = retries,
                onOrderEnded = { ended++ },
                onAttemptFailed = { _, attempt, total, state ->
                    attempts += Attempt(attempt, total, state)
                },
                // 单测不真等 3 秒：时序由断言钉住，不测时钟
                retryDelayMs = 0L,
            )
        }
        return RunOutcome(result, attempts, ended)
    }

    @Test
    fun `两次失败后成功且每次失败都上报`() {
        val session = FakeSession()
        session.failFirst = 2

        val outcome = run(session, retries = 2)

        assertEquals(3, session.returnCalls)
        assertEquals(
            listOf(
                Attempt(1, 3, EbikeAutoReturn.RetryState.RETRYING),
                Attempt(2, 3, EbikeAutoReturn.RetryState.RETRYING),
            ),
            outcome.attempts,
        )
        assertEquals(1, outcome.endedCalls)
        assertTrue(outcome.result is AutoReturnResult.Returned)
        assertEquals("100000669", (outcome.result as AutoReturnResult.Returned).summary.carNum)
    }

    @Test
    fun `重试用尽后停止`() {
        val session = FakeSession()
        session.failFirst = 99

        val outcome = run(session, retries = 2)

        assertEquals(3, session.returnCalls)
        assertEquals(
            listOf(
                EbikeAutoReturn.RetryState.RETRYING,
                EbikeAutoReturn.RetryState.RETRYING,
                EbikeAutoReturn.RetryState.EXHAUSTED,
            ),
            outcome.attempts.map { it.state },
        )
        assertEquals(0, outcome.endedCalls)
        assertTrue(outcome.result is AutoReturnResult.Failed)
        assertEquals(3, (outcome.result as AutoReturnResult.Failed).attempts)
    }

    @Test
    fun `调度费失败不重试`() {
        val session = FakeSession()
        session.failFirst = 99
        session.failWith = KvcBusinessError(50010, "该位置还车会产生调度费", dispatchMoneyCents = 200)

        val outcome = run(session, retries = 2)

        assertEquals(1, session.returnCalls)
        assertEquals(listOf(EbikeAutoReturn.RetryState.ABORTED), outcome.attempts.map { it.state })
        assertTrue(outcome.result is AutoReturnResult.Failed)
    }

    @Test
    fun `订单已不在按结束收尾且不发还车`() {
        val session = FakeSession()
        session.underway = null

        val outcome = run(session)

        assertEquals(AutoReturnResult.AlreadyEnded, outcome.result)
        assertEquals(0, session.returnCalls)
        assertEquals(0, outcome.endedCalls)
        assertTrue(outcome.attempts.isEmpty())
    }

    @Test
    fun `未登录不动作`() {
        val session = FakeSession(loggedIn = MutableStateFlow(false))

        val outcome = run(session)

        assertEquals(AutoReturnResult.NotLoggedIn, outcome.result)
        assertEquals(0, session.queryCalls)
        assertTrue(outcome.attempts.isEmpty())
    }

    /** 现查订单每拍一次（车辆坐标要最新那份），不是只查一次。 */
    @Test
    fun `每拍都现查订单`() {
        val session = FakeSession()
        session.failFirst = 1

        run(session, retries = 1)

        assertEquals(2, session.queryCalls)
        assertEquals(2, session.returnCalls)
    }
}

/** 在案订单（与 KvcxRideControllerTest 的构造口径一致）。 */
private fun ride(
    carNum: String = "100000669",
    totalDateSeconds: Long? = 60L,
    payMoneyCents: Long? = 150L,
    locked: Boolean? = false,
): KqcxAuth.Ride = KqcxAuth.Ride(
    carNum = carNum,
    bluetoothName = "BT-669",
    totalDateSeconds = totalDateSeconds,
    payMoneyCents = payMoneyCents,
    lat = 28.68,
    lng = 115.85,
    locked = locked,
    batteryPercent = 85,
)
