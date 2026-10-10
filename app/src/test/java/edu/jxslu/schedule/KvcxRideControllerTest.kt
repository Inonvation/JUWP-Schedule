package edu.jxslu.schedule

import edu.jxslu.schedule.data.kqcx.KvcxRideSession
import edu.jxslu.schedule.data.kqcx.LockOutcome
import edu.jxslu.schedule.data.kqcx.UnlockOutcome
import edu.jxslu.schedule.domain.KqcxAuth
import edu.jxslu.schedule.domain.KvcBusinessError
import edu.jxslu.schedule.ui.common.NoticeTone
import edu.jxslu.schedule.ui.ebike.KvcxAction
import edu.jxslu.schedule.ui.ebike.KvcxReturnSummary
import edu.jxslu.schedule.ui.ebike.KvcxRideController
import edu.jxslu.schedule.ui.ebike.KvcxSideEffects
import edu.jxslu.schedule.ui.ebike.LocateResult
import edu.jxslu.schedule.ui.ebike.UNLOCK_COOLDOWN_MS
import edu.jxslu.schedule.ui.ebike.kvcxConfirmDialog
import edu.jxslu.schedule.ui.ebike.rideElapsedSeconds
import edu.jxslu.schedule.ui.ebike.unlockCooldownSecondsLeft
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
import java.io.IOException
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

        /** 查询脚本：非空时逐次从队首取（模拟服务端前后返回不同快照，如"先缺字段后补齐"）；取空后回 [underway]。 */
        var queryScript: ArrayDeque<KqcxAuth.Ride?>? = null

        /** 一次性查询闸门：挂住某次查询、稍后放行（测「旧包晚到」与「动作期不发查询」）。 */
        var queryGate: CompletableDeferred<Unit>? = null

        var queryCalls = 0

        var unlockResult: suspend () -> UnlockOutcome = {
            UnlockOutcome.Unlocked("100000669", helmetFlow = false)
        }

        var returnResult: KqcxAuth.EndOutcome.Ended =
            KqcxAuth.EndOutcome.Ended(needPay = false, wechatScore = null)

        var settled: suspend () -> KqcxAuth.UnpayState? = { KqcxAuth.UnpayState.Settled }

        /** 临时锁车的返回（默认确认成功）；测「未确认」时改成 Unconfirmed。 */
        var lockResult: LockOutcome = LockOutcome.Locked(ride(locked = true))

        var unlockCalls = 0
        var retryCalls = 0
        var lockCalls = 0
        var returnCalls = 0
        var ringCalls = 0

        /** 置非空则查询直接抛错（模拟断网）：动作后刷新的「失败不裁决」路径用。 */
        var queryError: Throwable? = null

        override suspend fun queryUnderway(): KqcxAuth.Ride? {
            queryCalls++
            queryGate?.let { gate ->
                queryGate = null
                gate.await()
            }
            queryError?.let { throw it }
            queryScript?.let { script -> if (script.isNotEmpty()) return script.removeFirst() }
            return underway
        }

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

        override suspend fun temporaryLock(gcjLat: Double, gcjLng: Double): LockOutcome {
            lockCalls++
            return lockResult
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
        var scheduledAutoReturn = 0
        var endedRide = 0
        val recorded = mutableListOf<KvcxReturnSummary>()

        /** 计时善后被调起时的钩子：测「界面先行」顺序（此刻骑行卡应已落 UI）。 */
        var onStartTimer: (() -> Unit)? = null

        override suspend fun startRideTimer(): Pair<String, NoticeTone> {
            startedTimer++
            onStartTimer?.invoke()
            return "已开始计时，通知栏已显示倒计时" to NoticeTone.Success
        }

        override suspend fun ensureReminderEnabled(): Boolean = reminderWasOff

        override suspend fun scheduleAutoReturn() {
            scheduledAutoReturn++
        }

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
        // 补全重拉的间隔在单测里取 0：Unconfined 下 delay(0) 不挂起，断言不需要等真时间
        unlockSyncIntervalMs = 0L,
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
    fun `开锁成功排自动还车`() {
        // 自动还车的触发点 = 计时起点 + 15 分钟 − 提前量：必须和计时同刻排（见
        // EbikeAutoReturnReminder）。开关关着时副作用实现里是空操作，但编排层要保证
        // 每次都调用——漏掉就是"开了开关也不生效"
        val session = FakeSession()
        val effects = FakeEffects()
        val kvcx = controller(session, effects)

        kvcx.unlock("100000669")

        assertEquals(1, effects.scheduledAutoReturn)
        assertEquals(1, effects.startedTimer)
    }

    @Test
    fun `开锁未确认不排自动还车`() {
        // 没确认开锁 = 定时器都没起（见上面的用例）：自动还车没有起点可依，也不能排
        val session = FakeSession()
        val effects = FakeEffects()
        session.unlockResult = { UnlockOutcome.Unconfirmed("100000669") }
        val kvcx = controller(session, effects)

        kvcx.unlock("100000669")

        assertEquals(0, effects.scheduledAutoReturn)
        assertEquals(0, effects.startedTimer)
    }

    @Test
    fun `开锁成功先落界面再做善后`() {
        // 2026-10-10 用户口径「响应完后停滞一会才弹骑行卡」：确认快照必须先落 UI，
        // 善后（开关 / 计时 / 通知）跟在后面——把 update 挪回善后之后就是那段可见的停顿
        val session = FakeSession()
        session.unlockResult = {
            UnlockOutcome.Unlocked("100000669", helmetFlow = false, ride = ride(locked = false))
        }
        val effects = FakeEffects()
        val kvcx = controller(session, effects)
        var rideVisibleWhenTimerStarted = false
        effects.onStartTimer = { rideVisibleWhenTimerStarted = kvcx.state.value.ride != null }

        kvcx.unlock("100000669")

        assertTrue(rideVisibleWhenTimerStarted)
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
        // 未确认不算"开车"：不进冷却，用户仍可锁车 / 还车兜底
        assertEquals(0L, kvcx.state.value.unlockCooldownUntil)
    }

    @Test
    fun `开锁成功采纳确认快照并进入三秒冷却`() {
        val session = FakeSession()
        // 确认那一刻就带数据：卡片不必等下一次刷新，补全一轮也不发（首拍直接收工）
        session.unlockResult = {
            UnlockOutcome.Unlocked(
                "100000669",
                helmetFlow = false,
                ride = ride(totalDateSeconds = 12, locked = false),
            )
        }
        val kvcx = controller(session)
        val before = System.currentTimeMillis()

        kvcx.unlock("100000669")

        val state = kvcx.state.value
        assertEquals(12L, state.ride?.totalDateSeconds)
        assertEquals(false, state.ride?.locked)
        assertTrue(state.fetchedAt >= before)
        // 冷却截止时刻 = 开锁确认时刻 + 3 秒（页面据此把锁车 / 还车置灰）
        assertTrue(state.unlockCooldownUntil >= before + UNLOCK_COOLDOWN_MS)
        assertEquals(0, session.queryCalls)
    }

    @Test
    fun `开锁确认快照缺字段时有界补全`() {
        // 新订单在服务端异步落账：确认快照可能缺已骑时长 / 锁状态——不轮询的前提下，
        // 一次刷新落空就会一直挂在骑行卡上（「已骑 --」「锁状态未知」），所以有界补拍
        val session = FakeSession()
        session.unlockResult = {
            UnlockOutcome.Unlocked(
                "100000669",
                helmetFlow = false,
                ride = ride(totalDateSeconds = null, locked = null),
            )
        }
        session.queryScript = ArrayDeque(listOf(ride(totalDateSeconds = 5, locked = false)))
        val kvcx = controller(session)

        kvcx.unlock("100000669")

        val state = kvcx.state.value
        assertEquals(5L, state.ride?.totalDateSeconds)
        assertEquals(false, state.ride?.locked)
        assertEquals(1, session.queryCalls)
    }

    @Test
    fun `开锁后刷新落空不清掉刚确认的订单`() {
        // 同一辆车刚还完就再开：服务端要先后走完结束与新建两条账，那几拍里查询可能落空——
        // 空回包不许写状态（写下去正是「骑行时长等信息丢失」），恢复后照常落位
        val session = FakeSession()
        session.unlockResult = {
            UnlockOutcome.Unlocked(
                "100000669",
                helmetFlow = false,
                ride = ride(totalDateSeconds = null, locked = false),
            )
        }
        session.queryScript = ArrayDeque(
            listOf(null, ride(totalDateSeconds = 8, locked = false)),
        )
        val kvcx = controller(session)

        kvcx.unlock("100000669")

        assertEquals(8L, kvcx.state.value.ride?.totalDateSeconds)
        assertEquals(2, session.queryCalls)
    }

    @Test
    fun `解锁类动作成功都进三秒冷却`() {
        // resume（临时锁车后继续骑）
        val resumeSession = FakeSession()
        val kvcxResume = controller(resumeSession)
        resumeSession.underway = ride(carNum = "100000669", locked = true)
        kvcxResume.query(quiet = true)
        kvcxResume.resumeRide()
        assertTrue(kvcxResume.state.value.unlockCooldownUntil > 0L)

        // retryUnlock（开锁未确认后的重试）
        val retrySession = FakeSession()
        val kvcxRetry = controller(retrySession)
        retrySession.underway = ride(carNum = "100000669", locked = true)
        kvcxRetry.query(quiet = true)
        kvcxRetry.retryUnlock()
        assertTrue(kvcxRetry.state.value.unlockCooldownUntil > 0L)
    }

    @Test
    fun `冷却剩余秒数向上取整且到点归零`() {
        // 还剩 3 秒整：显示 3（页面据此把「锁车 / 还车」写成「锁车 3s」）
        assertEquals(3, unlockCooldownSecondsLeft(until = 3_000L, now = 0L))
        // 2.1 秒：向上取整到 3，不提前跳 0（数字宁可多显示一下）
        assertEquals(3, unlockCooldownSecondsLeft(until = 3_000L, now = 900L))
        assertEquals(2, unlockCooldownSecondsLeft(until = 3_000L, now = 1_000L))
        assertEquals(1, unlockCooldownSecondsLeft(until = 3_000L, now = 2_999L))
        // 到点 / 无冷却 / 时钟回拨：一律 0
        assertEquals(0, unlockCooldownSecondsLeft(until = 3_000L, now = 3_000L))
        assertEquals(0, unlockCooldownSecondsLeft(until = 0L, now = 5_000L))
        assertEquals(0, unlockCooldownSecondsLeft(until = 1_000L, now = 9_000L))
    }

    @Test
    fun `同车快照缺锁字段沿用上一份`() {
        // 2026-10-09 修「骑行中却显示锁状态未知」：快趣字段类型不稳定 + 新订单异步落账，
        // 缺 lockStatus 的快照是常态——拿不到新事实不把已知事实降级成未知
        val session = FakeSession()
        val kvcx = controller(session)
        session.underway = ride(locked = false)
        kvcx.query(quiet = true)
        assertEquals(false, kvcx.state.value.ride?.locked)

        session.underway = ride(locked = null)
        kvcx.query(quiet = true)
        assertEquals(false, kvcx.state.value.ride?.locked)

        // 新事实照旧覆盖沿用的值
        session.underway = ride(locked = true)
        kvcx.query(quiet = true)
        assertEquals(true, kvcx.state.value.ride?.locked)

        // 换了车：无从沿用，仍按未知
        session.underway = ride(carNum = "100000999", locked = null)
        kvcx.query(quiet = true)
        assertNull(kvcx.state.value.ride?.locked)
    }

    @Test
    fun `锁车确认中缺锁字段不沿用`() {
        // 悬置待核实期间正是"还没对上账"的时刻：未知比旧值诚实，别拿旧快照充数
        val session = FakeSession()
        val kvcx = controller(session)
        session.underway = ride(carNum = "100000669", locked = false)
        kvcx.query(quiet = true)

        session.lockResult = LockOutcome.Unconfirmed(lastKnown = null)
        kvcx.tempLock()
        assertTrue(kvcx.state.value.lockPending)

        session.underway = ride(carNum = "100000669", locked = null)
        kvcx.query(quiet = true)
        assertNull(kvcx.state.value.ride?.locked)
    }

    @Test
    fun `动作前发出的查询晚到不覆盖动作后的状态`() {
        val session = FakeSession()
        val kvcx = controller(session)
        // 一条查询先发出、挂在闸门上；它拿到的会是一条「还车之前」的旧订单快照
        val gate = CompletableDeferred<Unit>()
        session.queryGate = gate
        kvcx.query(quiet = true)

        // 动作先完成：还车（脚本首项 = 订单结束，供还车自己的对账消费）
        session.queryScript = ArrayDeque(
            listOf(null, ride(carNum = "100000669", locked = true)),
        )
        kvcx.returnBike()
        assertNull(kvcx.state.value.ride)
        assertNotNull(kvcx.state.value.returnSummary)

        // 旧查询此刻才落地：结果必须被丢弃（否则会把已结束的订单画回骑行卡）
        gate.complete(Unit)

        assertNull(kvcx.state.value.ride)
    }

    @Test
    fun `写动作进行中查询直接跳过`() {
        val session = FakeSession()
        val unlockGate = CompletableDeferred<UnlockOutcome>()
        session.unlockResult = { unlockGate.await() }
        val kvcx = controller(session)

        kvcx.unlock("100000669")
        assertEquals(KvcxAction.UNLOCK, kvcx.state.value.busy)

        val before = session.queryCalls
        kvcx.query(quiet = false)
        assertEquals(before, session.queryCalls)

        unlockGate.complete(UnlockOutcome.Unlocked("100000669", helmetFlow = false))
        assertNull(kvcx.state.value.busy)
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
    fun `临时锁车确认成功清悬置并提示计费继续`() {
        val session = FakeSession()
        val kvcx = controller(session)

        kvcx.tempLock()

        assertEquals(1, session.lockCalls)
        assertFalse(kvcx.state.value.unlockPending)
        assertFalse(kvcx.state.value.lockPending)
        assertTrue(notices.single().first.contains("计费继续"))
        assertEquals(NoticeTone.Info, notices.single().second)
    }

    @Test
    fun `锁车未确认不是错误而是确认中`() {
        // 2026-10-08 修「开锁后临时锁车容易失败」：上报慢是常态，超窗不该报 Error——
        // 那会把用户推去反复点（客户端幂等早退已同日删除：再点一次锁车=真重发指令）。
        // 置 lockPending + Warning。
        val session = FakeSession()
        session.lockResult = LockOutcome.Unconfirmed(lastKnown = ride(locked = false))
        val kvcx = controller(session)

        kvcx.tempLock()

        assertEquals(1, session.lockCalls)
        assertTrue(kvcx.state.value.lockPending)
        assertEquals(NoticeTone.Warning, notices.single().second)
        assertTrue(notices.single().first.contains("车辆回报稍慢"))
        assertTrue(notices.single().first.contains("再点一次锁车"))
    }

    @Test
    fun `锁车确认中刷新到已锁即消解`() {
        val session = FakeSession()
        session.lockResult = LockOutcome.Unconfirmed(lastKnown = ride(locked = false))
        val kvcx = controller(session)
        kvcx.tempLock()
        assertTrue(kvcx.state.value.lockPending)

        // 车辆上报到达：刷新看到已锁，悬置解除
        session.underway = ride(locked = true)
        kvcx.query(quiet = true)

        assertFalse(kvcx.state.value.lockPending)
    }

    @Test
    fun `锁车确认中刷新到未锁保持悬置等上报`() {
        // 语义与 unlockPending 一致（刷新到目标态才消解）：上报慢时"未锁快照"不代表
        // 锁车失败——那正是本次修复的立论；保持悬置，出口是再点一次锁车（真重发指令，
        // 客户端早退已同日删除）
        val session = FakeSession()
        session.lockResult = LockOutcome.Unconfirmed(lastKnown = null)
        val kvcx = controller(session)
        kvcx.tempLock()
        assertTrue(kvcx.state.value.lockPending)

        session.underway = ride(locked = false)
        kvcx.query(quiet = true)

        assertTrue(kvcx.state.value.lockPending)
        // 「点刷新核实」走公共 query——三条刷新路径（query / 动作后刷新 / 胶囊现查）
        // 共用 withRideSnapshot 同一份裁决；本用例覆盖 query 这条
        assertEquals(1, session.lockCalls)
    }

    @Test
    fun `已锁时解锁继续骑走 retryUnlock 并给继续骑行文案`() {
        val session = FakeSession()
        session.underway = ride(carNum = "100000669", locked = true)
        val kvcx = controller(session)
        kvcx.query(quiet = true)

        kvcx.resumeRide()

        assertEquals(1, session.retryCalls)
        assertEquals(0, session.unlockCalls)
        assertEquals("已解锁，继续骑行", notices.single().first)
        assertEquals(NoticeTone.Success, notices.single().second)
    }

    @Test
    fun `已解锁时点开锁=重发解锁指令并给确认文案`() {
        // 2026-10-08 用户口径「即使车辆已解锁，也保留开启车辆的按钮和功能」：
        // 骑行卡左位「开锁」在未锁态 = 对在案订单重发 greenCarUnlock（不重建订单）
        val session = FakeSession()
        session.underway = ride(carNum = "100000669", locked = false)
        val kvcx = controller(session)
        kvcx.query(quiet = true)

        kvcx.resumeRide()

        assertEquals(1, session.retryCalls)
        assertEquals(0, session.unlockCalls)
        assertEquals("已确认开锁", notices.single().first)
        assertEquals(NoticeTone.Success, notices.single().second)
    }

    @Test
    fun `解锁成功同时清掉锁车确认中的悬置`() {
        val session = FakeSession()
        session.lockResult = LockOutcome.Unconfirmed(lastKnown = ride(locked = false))
        val kvcx = controller(session)
        kvcx.tempLock()
        assertTrue(kvcx.state.value.lockPending)

        // 解锁是新的用户意图：成功即覆盖掉「锁车确认中」的悬置
        kvcx.resumeRide()

        assertFalse(kvcx.state.value.lockPending)
        assertEquals("已确认开锁", notices.last().first)
    }

    @Test
    fun `解锁未确认也作废锁车悬置`() {
        val session = FakeSession()
        session.lockResult = LockOutcome.Unconfirmed(lastKnown = ride(locked = false))
        val kvcx = controller(session)
        kvcx.tempLock()
        assertTrue(kvcx.state.value.lockPending)

        // 解锁意图已发出（即便结果未确认）：锁车悬置同样作废，
        // 别让胶囊停在「锁车确认中…」和用户刚做的操作打架
        session.unlockResult = { UnlockOutcome.Unconfirmed("100000669") }
        kvcx.resumeRide()

        assertFalse(kvcx.state.value.lockPending)
        assertEquals("未确认解锁；请到快趣小程序核对", notices.last().first)
    }

    @Test
    fun `重试开锁未确认也作废锁车悬置`() {
        // 共存场景（unlockPending + lockPending，只在"动作后刷新也失败"的断网窗口里
        // 两悬置能同时挂住——刷新成功的裁决必然按快照消解其一）点「重试开锁」：
        // 解锁意图已发出，锁车悬置一并作废——否则快照回报未锁时裁决先消解
        // unlockPending、把残留的 lockPending 暴露成「锁车确认中…」，
        // 与刚做的开锁动作打架
        val session = FakeSession()
        session.underway = ride(carNum = "100000669", locked = true)
        session.unlockResult = { UnlockOutcome.Unconfirmed("100000669") }
        val kvcx = controller(session)
        kvcx.unlock("100000669") // 开锁未确认（车辆仍报已锁，悬置保持）
        assertTrue(kvcx.state.value.unlockPending)

        // 断网：临时锁车未确认，动作后的刷新也失败——两悬置同时挂住
        session.queryError = IOException("boom")
        session.lockResult = LockOutcome.Unconfirmed(lastKnown = null)
        kvcx.tempLock()
        assertTrue(kvcx.state.value.lockPending)

        // 网络恢复、车辆上报还没到，用户点「重试开锁」（重发解锁指令、仍未确认）
        session.queryError = null
        session.underway = ride(carNum = "100000669", locked = false)
        kvcx.retryUnlock()

        assertFalse(kvcx.state.value.lockPending)
        assertEquals("仍未确认开锁；请到快趣小程序核对", notices.last().first)
    }

    @Test
    fun `锁状态查询现查也消解悬置`() {
        // 胶囊「核实」路径（queryLockState）与 query / 动作后刷新共用同一份悬置裁决：
        // 查到未锁即消解「开锁未确认」（2026-10-08 复核对齐）
        val session = FakeSession()
        session.underway = ride(carNum = "100000669", locked = true)
        session.unlockResult = { UnlockOutcome.Unconfirmed("100000669") }
        val kvcx = controller(session)
        kvcx.unlock("100000669")
        assertTrue(kvcx.state.value.unlockPending)

        session.underway = ride(carNum = "100000669", locked = false)
        kvcx.queryLockState()

        assertFalse(kvcx.state.value.unlockPending)
        assertEquals("车辆未锁，正在计费", notices.last().first)
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
    fun `重新开锁成功清掉还车结算卡`() {
        // 结算卡上的「重新使用该车」（2026-10-10）：开锁成功 = 离开结算态，否则
        // 「已还车」的卡片会和在案订单并存
        val session = FakeSession()
        val kvcx = controller(session)
        kvcx.query(quiet = true)
        session.underway = null
        kvcx.returnBike()
        assertNotNull(kvcx.state.value.returnSummary)

        session.unlockResult = {
            UnlockOutcome.Unlocked("100000669", helmetFlow = false, ride = ride(locked = false))
        }
        kvcx.unlock("100000669")

        assertNull(kvcx.state.value.returnSummary)
        assertNotNull(kvcx.state.value.ride)
        assertTrue(kvcx.state.value.unlockCooldownUntil > 0L)
    }

    @Test
    fun `重新开锁未确认也清掉还车结算卡`() {
        // 订单已创建（只是没确认到锁）：同样离开结算态——「已还车」的卡片不能与在案订单并存
        val session = FakeSession()
        val kvcx = controller(session)
        kvcx.query(quiet = true)
        session.underway = null
        kvcx.returnBike()
        assertNotNull(kvcx.state.value.returnSummary)

        // 新订单在案、锁未确认（刷新到「已锁」快照，悬置保持）
        session.underway = ride(locked = true)
        session.unlockResult = { UnlockOutcome.Unconfirmed("100000669") }
        kvcx.unlock("100000669")

        assertNull(kvcx.state.value.returnSummary)
        assertTrue(kvcx.state.value.unlockPending)
    }

    @Test
    fun `重新开锁失败保留还车结算卡`() {
        // 失败可原地重试：结算卡不许被清（清卡只在成功 / 未确认两条路径上）
        val session = FakeSession()
        val kvcx = controller(session)
        kvcx.query(quiet = true)
        session.underway = null
        kvcx.returnBike()
        assertNotNull(kvcx.state.value.returnSummary)

        session.unlockResult = { throw IOException("断网") }
        kvcx.unlock("100000669")

        assertNotNull(kvcx.state.value.returnSummary)
        assertEquals(NoticeTone.Error, notices.last().second)
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
        // 已锁：状态胶囊是快照，点它 = 现查一次；答案里说清"订单还在、计费继续"
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
