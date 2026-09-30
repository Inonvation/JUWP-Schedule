package edu.jxslu.schedule

import edu.jxslu.schedule.data.kqcx.KvcxRideSession
import edu.jxslu.schedule.data.kqcx.UnlockOutcome
import edu.jxslu.schedule.domain.KqcxAuth
import edu.jxslu.schedule.domain.KvcBusinessError
import edu.jxslu.schedule.ui.common.NoticeTone
import edu.jxslu.schedule.ui.ebike.KvcxAction
import edu.jxslu.schedule.ui.ebike.KvcxReturnSummary
import edu.jxslu.schedule.ui.ebike.KvcxRideController
import edu.jxslu.schedule.ui.ebike.KvcxSideEffects
import edu.jxslu.schedule.ui.ebike.LocateResult
import edu.jxslu.schedule.ui.ebike.kvcxConfirmDialog
import edu.jxslu.schedule.ui.ebike.rideElapsedSeconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

/**
 * 本机用车编排（DESIGN §4.32 B/C 档）：责任边界用**假的会话与副作用**钉住。
 *
 * 真实依赖（`KqcxSessionRepository` / `BikeLocator` / 提醒调度 / Room）都要网络、Context 与
 * DataStore，JVM 单测里跑不了；而"开锁成功要顺手打开提醒开关""还车要出结果卡并记账"
 * 这类编排恰恰是四道闸的落点，值得用测试锁住。
 *
 * 协程用 `Dispatchers.Unconfined`：假件都是立即返回，`launch` 会同步跑完，
 * 于是断言不需要等——也**不用**引入 `kotlinx-coroutines-test`（离线构建里没这个依赖）。
 */
class KvcxRideControllerTest {

    // ---------- 假件 ----------

    private class FakeSession(
        override val loggedIn: StateFlow<Boolean> = MutableStateFlow(true),
    ) : KvcxRideSession {

        /** 查到的进行中订单；还车前调用方会把它置成 null（模拟服务端已结束）。 */
        var underway: KqcxAuth.Ride? = ride(carNum = "100000669")

        var unlockResult: suspend () -> UnlockOutcome = {
            UnlockOutcome.Unlocked("100000669", helmetFlow = false)
        }

        var returnResult: KqcxAuth.EndOutcome.Ended =
            KqcxAuth.EndOutcome.Ended(needPay = false, wechatScore = null)

        var settled: suspend () -> KqcxAuth.UnpayState? = { KqcxAuth.UnpayState.Settled }

        var unlockCalls = 0
        var retryCalls = 0
        var lockCalls = 0
        var returnCalls = 0
        var ringCalls = 0

        override suspend fun queryUnderway(): KqcxAuth.Ride? = underway

        override suspend fun unlockBike(
            carNum: String,
            gcjLat: Double,
            gcjLng: Double,
        ): UnlockOutcome {
            unlockCalls++
            return unlockResult()
        }

        override suspend fun retryUnlock(gcjLat: Double, gcjLng: Double): UnlockOutcome {
            retryCalls++
            return unlockResult()
        }

        override suspend fun temporaryLock(gcjLat: Double, gcjLng: Double): KqcxAuth.Ride {
            lockCalls++
            return underway ?: error("测试里没有在案订单")
        }

        override suspend fun returnBike(
            gcjLat: Double,
            gcjLng: Double,
        ): KqcxAuth.EndOutcome.Ended {
            returnCalls++
            return returnResult
        }

        override suspend fun confirmUnpaidSettled(attempts: Int): KqcxAuth.UnpayState? = settled()

        override suspend fun ringFindCar() {
            ringCalls++
        }
    }

    private class FakeEffects(private val reminderWasOff: Boolean = false) : KvcxSideEffects {
        var startedTimer = 0
        var endedRide = 0
        val recorded = mutableListOf<KvcxReturnSummary>()

        override suspend fun startRideTimer(): Pair<String, NoticeTone> {
            startedTimer++
            return "已开始计时，通知栏已显示倒计时" to NoticeTone.Success
        }

        override suspend fun ensureReminderEnabled(): Boolean = reminderWasOff

        override suspend fun endRideAndBurn() {
            endedRide++
        }

        override suspend fun recordRide(summary: KvcxReturnSummary) {
            recorded += summary
        }
    }

    private val notices = mutableListOf<Pair<String, NoticeTone>>()
    private var unlockedSignals = 0

    private fun controller(
        session: KvcxRideSession?,
        effects: KvcxSideEffects = FakeEffects(),
        located: LocateResult = LocateResult.Ok(28.68, 115.85),
    ): KvcxRideController = KvcxRideController(
        session = session,
        scope = CoroutineScope(Dispatchers.Unconfined),
        onNotice = { text, tone -> notices += text to tone },
        onUnlocked = { unlockedSignals++ },
        locate = { located },
        effects = effects,
    )

    // ---------- 开锁 ----------

    @Test
    fun `开锁成功同步计时与提醒开关`() {
        val session = FakeSession()
        val effects = FakeEffects(reminderWasOff = true)
        val kvcx = controller(session, effects)

        kvcx.unlock("100000669")

        assertEquals(1, session.unlockCalls)
        assertEquals(1, effects.startedTimer)
        assertEquals(
            "已开锁；已开始计时，通知栏已显示倒计时（已打开免费时长提醒）",
            notices.single().first,
        )
        assertEquals(NoticeTone.Success, notices.single().second)
        assertEquals(1, unlockedSignals)
        assertNull(kvcx.state.value.busy)
        assertFalse(kvcx.state.value.unlockPending)
    }

    @Test
    fun `提醒开关本来就开着时不加括号说明`() {
        val session = FakeSession()
        val effects = FakeEffects(reminderWasOff = false)
        val kvcx = controller(session, effects)

        kvcx.unlock("100000669")

        assertEquals(1, effects.startedTimer)
        assertEquals("已开锁；已开始计时，通知栏已显示倒计时", notices.single().first)
    }

    @Test
    fun `头盔流程的提示带上取盔`() {
        val session = FakeSession()
        session.unlockResult = { UnlockOutcome.Unlocked("100000669", helmetFlow = true) }
        val kvcx = controller(session)

        kvcx.unlock("100000669")

        assertTrue(notices.single().first.startsWith("已创建订单，请取下车盔；"))
    }

    @Test
    fun `开锁未确认给重试出口且不计成功`() {
        val session = FakeSession()
        val effects = FakeEffects(reminderWasOff = true)
        session.unlockResult = { UnlockOutcome.Unconfirmed("100000669") }
        // 车没开：订单在案但锁仍是合上的状态（刷新到未锁才说明真开了）
        session.underway = ride(locked = true)
        val kvcx = controller(session, effects)

        kvcx.unlock("100000669")

        assertTrue(kvcx.state.value.unlockPending)
        assertEquals(0, unlockedSignals)
        assertEquals(0, effects.startedTimer)
        assertEquals(NoticeTone.Warning, notices.single().second)
        assertTrue(notices.single().first.contains("重试开锁"))
    }

    @Test
    fun `支付分授权错误记会话标记`() {
        val session = FakeSession()
        session.unlockResult = { throw KvcBusinessError(11035, "该车需先完成免押授权") }
        val kvcx = controller(session)

        kvcx.unlock("100000669")

        assertTrue(kvcx.state.value.scoreAuthRequired)
        assertEquals(NoticeTone.Error, notices.single().second)
        assertEquals("该车需先完成免押授权", notices.single().first)
    }

    @Test
    fun `开锁超时只发一次请求（零自动重试）`() {
        val session = FakeSession()
        session.unlockResult = { throw SocketTimeoutException("timeout") }
        val kvcx = controller(session)

        kvcx.unlock("100000669")

        assertEquals(1, session.unlockCalls)
        assertNull(kvcx.state.value.busy)
        assertEquals("网络超时，请稍后重试", notices.single().first)
    }

    @Test
    fun `定位失败不发开锁请求`() {
        val session = FakeSession()
        val effects = FakeEffects()
        val kvcx = controller(session, effects, located = LocateResult.Failed("没有定位权限"))

        kvcx.unlock("100000669")

        assertEquals(0, session.unlockCalls)
        assertEquals(0, effects.startedTimer)
        assertEquals("没有定位权限", notices.single().first)
    }

    @Test
    fun `动作进行中不重入`() {
        val session = FakeSession()
        val gate = CompletableDeferred<UnlockOutcome>()
        session.unlockResult = { gate.await() }
        val kvcx = controller(session)

        kvcx.unlock("100000669")
        assertEquals(KvcxAction.UNLOCK, kvcx.state.value.busy)
        kvcx.unlock("100000669") // busy 挡掉，不应再发一次
        assertEquals(1, session.unlockCalls)

        gate.complete(UnlockOutcome.Unlocked("100000669", helmetFlow = false))
        assertNull(kvcx.state.value.busy)
    }

    @Test
    fun `会话不可用时只提示`() {
        val kvcx = controller(session = null)

        kvcx.unlock("100000669")

        assertEquals("快趣会话不可用", notices.single().first)
    }

    // ---------- 锁车 / 解锁继续骑 ----------

    @Test
    fun `临时锁车清悬置并提示计费继续`() {
        val session = FakeSession()
        val kvcx = controller(session)

        kvcx.tempLock()

        assertEquals(1, session.lockCalls)
        assertFalse(kvcx.state.value.unlockPending)
        assertTrue(notices.single().first.contains("计费继续"))
    }

    @Test
    fun `解锁继续骑走 retryUnlock 并给继续骑行文案`() {
        val session = FakeSession()
        val kvcx = controller(session)

        kvcx.resumeRide()

        assertEquals(1, session.retryCalls)
        assertEquals(0, session.unlockCalls)
        assertEquals("已解锁，继续骑行", notices.single().first)
        assertEquals(NoticeTone.Success, notices.single().second)
    }

    @Test
    fun `确认弹窗只对开锁与还车弹出`() {
        assertNotNull(kvcxConfirmDialog(KvcxAction.UNLOCK, "车 100000669"))
        assertNotNull(kvcxConfirmDialog(KvcxAction.RETRY_UNLOCK, "车 100000669"))
        assertNotNull(kvcxConfirmDialog(KvcxAction.RETURN, "车 100000669"))
        // 临时锁车与解锁继续骑都没有新增的计费后果：不弹（2026-09-28 用户拍板）
        assertNull(kvcxConfirmDialog(KvcxAction.LOCK, "车 100000669"))
        assertNull(kvcxConfirmDialog(KvcxAction.RESUME, "车 100000669"))
    }

    @Test
    fun `开锁确认弹窗写清支付分授权与客服出路`() {
        // 用户口径（2026-09-29）：账号方式开锁前要交代「可能要跳微信做支付分授权」，
        // 以及唯一能让本机直接开锁端到端可用的出路——联系快趣客服关闭该授权。
        // 这是用户唯一一次看清的机会，文案只在 kvcxConfirmDialog 里
        val dialog = kvcxConfirmDialog(KvcxAction.UNLOCK, "车 100000669")!!
        val text = dialog.points.joinToString("\n")
        assertTrue(text.contains("微信支付分"))
        assertTrue(text.contains("客服"))
        assertTrue(text.contains("车 100000669"))
        // 要点一条一件事（2026-10-01 打磨）：计费、车号核对、支付分各一条
        assertEquals(3, dialog.points.size)
        assertEquals("开锁", dialog.confirmLabel)
    }

    @Test
    fun `只有开锁弹窗允许免确认`() {
        // 「不再提醒」只给开锁（2026-10-01 用户要求）：四道闸里它是唯一"用户对自己账号"的
        // 授权；还车涉及结算与调度费、重试开锁是异常路径，都保留每次确认
        assertTrue(kvcxConfirmDialog(KvcxAction.UNLOCK, "车 100000669")!!.allowSkip)
        assertFalse(kvcxConfirmDialog(KvcxAction.RETRY_UNLOCK, "车 100000669")!!.allowSkip)
        assertFalse(kvcxConfirmDialog(KvcxAction.RETURN, "车 100000669")!!.allowSkip)
    }

    // ---------- 还车 ----------

    @Test
    fun `还车出结果卡并收计时记账`() {
        val session = FakeSession()
        val effects = FakeEffects()
        val kvcx = controller(session, effects)
        kvcx.query(quiet = true)
        assertNotNull(kvcx.state.value.ride)
        session.underway = null // 还车后服务端不再有在案订单

        kvcx.returnBike()

        val summary = kvcx.state.value.returnSummary
        assertNotNull(summary)
        assertEquals("100000669", summary!!.carNum)
        assertEquals("本次无需支付", summary.settleText)
        assertFalse(summary.settleWarning)
        assertEquals(1, effects.endedRide)
        assertEquals(listOf(summary), effects.recorded)
        assertNull(kvcx.state.value.ride)
    }

    @Test
    fun `还车欠费时结果卡标警告`() {
        val session = FakeSession()
        session.returnResult = KqcxAuth.EndOutcome.Ended(needPay = true, wechatScore = null)
        session.settled = { KqcxAuth.UnpayState.Owed(350) }
        val kvcx = controller(session)
        kvcx.query(quiet = true)
        session.underway = null

        kvcx.returnBike()

        val summary = kvcx.state.value.returnSummary!!
        assertTrue(summary.settleWarning)
        assertTrue(summary.needsSettle)
        assertEquals(350L, summary.owedCents)
        assertEquals(
            "有未结算费用 ¥3.50；进快趣小程序点主页「待支付」横幅，或在历史订单里点那笔待支付",
            summary.settleText,
        )
    }

    @Test
    fun `还车结果卡关掉后不再弹`() {
        val session = FakeSession()
        val kvcx = controller(session)
        kvcx.query(quiet = true)
        session.underway = null
        kvcx.returnBike()
        assertNotNull(kvcx.state.value.returnSummary)

        kvcx.dismissReturnSummary()

        assertNull(kvcx.state.value.returnSummary)
    }

    @Test
    fun `还车结果文案口径`() {
        assertEquals("订单已在其他端结束", summary(needPay = null).settleText)
        assertEquals("本次无需支付", summary(needPay = false).settleText)
        assertEquals("费用已自动结清", summary(needPay = true, settled = true).settleText)
        // 金额未知（轮询没确认到 / 查询失败）：不说成"欠费"，只说结算中或未结清
        assertEquals("费用结算中或未结清，可在快趣小程序核对", summary(needPay = true, settled = null).settleText)
        assertTrue(summary(needPay = true, settled = null).settleWarning)
        // 金额已知：写出来，并把两条能直达付款页的路一并给（2026-09-30 用户实测踩的坑）
        assertEquals(
            "有未结算费用 ¥3.50；进快趣小程序点主页「待支付」横幅，或在历史订单里点那笔待支付",
            summary(needPay = true, settled = false).copy(owedCents = 350).settleText,
        )
        // 无需支付时不给出路
        assertFalse(summary(needPay = false).needsSettle)
        assertTrue(summary(needPay = true, settled = null).needsSettle)
    }

    @Test
    fun `结果卡的费用与时长文案`() {
        val s = summary(needPay = false).copy(durationSeconds = 13 * 60, feeCents = 150)
        assertEquals("13 分钟", s.durationText)
        assertEquals("¥1.50", s.feeText)
        // 快趣没给过时长 / 费用时不编数字
        assertNull(summary(needPay = false).durationText)
        assertNull(summary(needPay = false).feeText)
    }

    // ---------- 查询与秒表 ----------

    @Test
    fun `未登录不查骑行状态`() {
        val session = FakeSession(loggedIn = MutableStateFlow(false))
        val kvcx = controller(session)

        kvcx.query(quiet = true)

        assertNull(kvcx.state.value.ride)
        assertFalse(kvcx.loggedIn.value)
    }

    // ---------- 响铃寻车与锁状态查询（2026-09-30） ----------

    @Test
    fun `响铃寻车有骑行时发送并提示成功`() {
        val session = FakeSession()
        val kvcx = controller(session)
        // 真机路径上响铃只在骑行卡露出来时才点得到（骑行卡 = 有在案订单），
        // 测试里先用 query 把骑行状态落位
        kvcx.query()

        kvcx.ringFindCar()

        assertEquals(1, session.ringCalls)
        assertEquals("已发送响铃，留意身边的提示音", notices.single().first)
        assertEquals(NoticeTone.Success, notices.single().second)
        assertNull(kvcx.state.value.busy)
    }

    @Test
    fun `响铃寻车没有骑行时本地挡下`() {
        // 本地先挡：没有在案订单就不打扰服务端（服务端按当前订单定位车辆）
        val session = FakeSession()
        session.underway = null
        val kvcx = controller(session)

        kvcx.ringFindCar()

        assertEquals(0, session.ringCalls)
        assertEquals(NoticeTone.Warning, notices.single().second)
        assertTrue(notices.single().first.contains("没有进行中的骑行"))
    }

    @Test
    fun `锁状态查询把答案说出来`() {
        // 已锁：徽标是快照，点它 = 现查一次；答案里说清"订单还在、计费继续"
        val session = FakeSession()
        session.underway = ride(locked = true)
        val kvcx = controller(session)
        kvcx.query()

        kvcx.queryLockState()

        assertEquals(ride(locked = true), kvcx.state.value.ride)
        assertEquals("车辆已锁上；订单还在，计费继续", notices.single().first)
        assertNull(kvcx.state.value.busy)
    }

    @Test
    fun `锁状态查询无订单时如实说没有`() {
        val session = FakeSession()
        session.underway = null
        val kvcx = controller(session)

        kvcx.queryLockState()

        assertEquals("没有进行中的订单", notices.single().first)
    }

    @Test
    fun `骑行秒表按本地走时口径重算`() {
        val ride = ride(totalDateSeconds = 60)
        // 还没查过（fetchedAt = 0）：不加走时
        assertEquals(60L, rideElapsedSeconds(ride, fetchedAt = 0L, now = 5_000L))
        // 拉取后过了 15 秒
        assertEquals(75L, rideElapsedSeconds(ride, fetchedAt = 1_000L, now = 16_000L))
        // 时钟回拨不倒扣
        assertEquals(60L, rideElapsedSeconds(ride, fetchedAt = 2_000L, now = 1_000L))
        // 快趣没给时长：不编一个 0 出来
        assertNull(rideElapsedSeconds(ride(totalDateSeconds = null), 0L, 0L))
    }
}

/** 测试用的订单（默认：校园车 + 已骑 1 分钟 + 1.50 元 + 未锁 + 85% 电）。 */
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

private fun summary(needPay: Boolean?, settled: Boolean? = null): KvcxReturnSummary =
    KvcxReturnSummary(
        carNum = "100000669",
        durationSeconds = null,
        feeCents = null,
        needPay = needPay,
        settled = settled,
    )
