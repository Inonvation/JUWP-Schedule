package edu.jxslu.schedule.ui.ebike

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.unit.dp
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.kqcx.KvcxRideSession
import edu.jxslu.schedule.startActivityOutsideApp
import edu.jxslu.schedule.data.kqcx.UnlockOutcome
import edu.jxslu.schedule.domain.KqcxAuth
import edu.jxslu.schedule.domain.KvcBusinessError
import edu.jxslu.schedule.domain.KvcProtocolException
import edu.jxslu.schedule.domain.RideRecord
import edu.jxslu.schedule.ui.common.NoticeTone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 快趣用车动作（DESIGN §4.32，B/C 档）：同一时刻只允许一个在跑。 */
enum class KvcxAction {
    UNLOCK,
    RETRY_UNLOCK,
    /** 临时锁车后继续骑（与 [RETRY_UNLOCK] 是同一条调用，只是文案不同）。 */
    RESUME,
    LOCK,
    RETURN,
    /** 响铃寻车（2026-09-30）：让在案订单的车鸣笛。无计费后果，不弹确认。 */
    RING,
    /** 锁状态查询（2026-09-30）：按权威来源（在案订单）现查一次锁没锁上。 */
    QUERY_LOCK,
}

/**
 * 快趣骑行状态 + 写动作的共享状态（DESIGN §4.32）。
 *
 * [ride] 由「进页 / 回页 / 动作后 / 手动刷新」驱动，**不轮询**；
 * [unlockPending] = 开锁指令已发但未确认（「重试开锁」的显示条件）；
 * [scoreAuthRequired] = 本次会话命中过 11035（支付分免押账号），只影响文案与出路；
 * [returnSummary] 非空即弹还车结果卡（关闭走 [KvcxRideController.dismissReturnSummary]）。
 */
data class KvcxRideState(
    val ride: KqcxAuth.Ride? = null,
    /** 最近一次骑行状态的获取时刻（本地秒表走时的基准；0 = 还没查过）。 */
    val fetchedAt: Long = 0L,
    /** 正在跑的用车动作（按钮禁用 + 进度显示）；null = 无。 */
    val busy: KvcxAction? = null,
    /** 开锁指令已发但未确认；确认开锁/还车/刷新到已开时清掉。 */
    val unlockPending: Boolean = false,
    /**
     * 该账号为微信支付分免押、开锁需在微信内授权（本次会话内命中过 11035）。
     * **只是会话内标记**（进程重启后允许再试）：`wxpayScoreUse` 是微信客户端专属 API，
     * App 代调不了；标记用于把「本机用车」区从失败提示换成出路指引，避免反复白点。
     */
    val scoreAuthRequired: Boolean = false,
    /** 还车结果（非空即弹结果卡）。 */
    val returnSummary: KvcxReturnSummary? = null,
)

/**
 * 还车结果卡的内容（DESIGN §3.9，2026-09-28）：还车是"花钱那一刻"，只发一句 Snackbar
 * 信息量不够——本次骑了多久、花了多少、结清没有，一张卡说完。
 *
 * 金额与结算**以快趣为准**：我们只有进行中订单接口、没有最终账单接口，所以费用取还车前
 * 最后一次拉到的 `payMoney`，卡上注明这一点（结算卡见 `RidePanels.kt` 的 `RideSettledBar`）。
 */
data class KvcxReturnSummary(
    val carNum: String?,
    val durationSeconds: Long?,
    val feeCents: Long?,
    val needPay: Boolean?,
    val settled: Boolean?,
    /** 未结清时的欠费金额（分）；null = 不知道（查询失败或官方没给）。 */
    val owedCents: Long? = null,
) {
    /** 时长文案用人话（「13 分钟」），与历史列表同一口径（`RideRecord.durationText`）。 */
    val durationText: String? get() = durationSeconds?.let(RideRecord::durationText)

    val feeText: String? get() = feeCents?.let { "¥%.2f".format(it / 100.0) }

    /**
     * 结算状态一句话（单测锁定）。**别把"没确认到"说成"欠费"**：扣款可能比我们那 7.5 秒
     * 的轮询窗口慢，所以金额未知时只说"结算中或未结清"。
     *
     * 欠费那行把**能直达付款页的两条路**写出来（2026-09-30，从小程序解包确认）：
     * 主页的待支付横幅（关过一次会冷却 5 分钟）、历史订单里的待支付记录——
     * 光说"去小程序结清"用户进了小程序也找不到支付入口。
     */
    val settleText: String
        get() = when {
            needPay == null -> "订单已在其他端结束"
            !needPay -> "本次无需支付"
            settled == true -> "费用已自动结清"
            owedCents != null ->
                "有未结算费用 ¥%.2f；进快趣小程序点主页「待支付」横幅，或在历史订单里点那笔待支付"
                    .format(owedCents / 100.0)
            else -> "费用结算中或未结清，可在快趣小程序核对"
        }

    /** 结算那行要不要用警告色（欠费才用）。 */
    val settleWarning: Boolean get() = needPay == true && settled != true

    /** 需不需要给「去微信结清」的出路（订单欠费时才给）。 */
    val needsSettle: Boolean get() = needPay == true && settled != true
}

/**
 * 用车动作的外部副作用（计时 / 提醒开关 / 还车善后 / 记账）——**抽出来是为了能在单测里换掉**：
 * 它们都要 Context 与 DataStore，JVM 单测里拿不到，而"开锁成功要顺手打开提醒开关"这类编排
 * 恰恰是责任边界，值得钉住（`KvcxRideControllerTest`）。
 */
internal interface KvcxSideEffects {
    /** 起免费时长计时；返回提示文案与语气（见 [startFreeRideNotice]）。 */
    suspend fun startRideTimer(): Pair<String, NoticeTone>

    /** 提醒开关关着就打开（返回 true = 本次打开了）；开锁是"确定在骑行"的信号。 */
    suspend fun ensureReminderEnabled(): Boolean

    /** 还车善后：收计时（撤闹钟/停服务/清通知）+ 焚毁相册里的码。 */
    suspend fun endRideAndBurn()

    /** 记一条本机骑行记录（只有本机用车这条链路数据是齐的）。 */
    suspend fun recordRide(summary: KvcxReturnSummary)
}

/** 生产用的副作用实现：全部落到 Graph + 提醒调度 + 本机记录。 */
internal object AppKvcxSideEffects : KvcxSideEffects {

    override suspend fun startRideTimer(): Pair<String, NoticeTone> = startFreeRideNotice()

    override suspend fun ensureReminderEnabled(): Boolean {
        val prefs = Graph.displayPrefs(Graph.appContext)
        val on = runCatching { prefs.ebikeFreeReminderEnabled.first() }.getOrDefault(true)
        if (on) return false
        runCatching { prefs.setEbikeFreeReminderEnabled(true) }
        return true
    }

    override suspend fun endRideAndBurn() {
        EbikeFreeRideReminder.endRide(Graph.appContext)
        burnPendingCodes(Graph.appContext, Graph.displayPrefs(Graph.appContext), force = true)
    }

    override suspend fun recordRide(summary: KvcxReturnSummary) {
        val carNum = summary.carNum ?: return
        runCatching {
            Graph.rideRecordStore(Graph.appContext).add(
                carNum = carNum,
                endAt = System.currentTimeMillis(),
                durationSeconds = summary.durationSeconds ?: 0L,
                feeCents = summary.feeCents,
                // 拿不到结算结果按未结清记：宁可多提醒一次去核对，也不把不确定写成"已结清"
                settled = summary.settled ?: (summary.needPay == false),
            )
        }
    }
}

/**
 * 本机用车动作的编排（DESIGN §4.32 B/C 档）：**出码页与地图页共用同一份**。
 *
 * 为什么单独抽出来：写操作的四道闸（二次确认、定位前置、零自动重试、降级官方渠道）
 * 是责任边界，散成两份副本早晚会漂移。两个页面各自只负责：确认弹窗、定位权限申请
 * （都需要 Activity/Compose 上下文）、以及怎么展示 [state]。
 *
 * 依赖收窄成 [KvcxRideSession]（业务）与 [KvcxSideEffects]（外部副作用），于是编排本身
 * 可以在 JVM 单测里用假实现跑。
 *
 * 覆盖关系的每一条都在 `KqcxSessionRepository` 里兜底：写操作**零自动重试**由仓库层保证，
 * 这里只按用户手势发一次；失败后用户可刷新状态、按提示重试或转官方渠道。
 */
class KvcxRideController internal constructor(
    private val session: KvcxRideSession?,
    private val scope: CoroutineScope,
    private val onNotice: (String, NoticeTone) -> Unit,
    /**
     * 开锁成功的一次性信号（页面补一次成功触感——控制器拿不到 Compose 侧的触感）。
     * 走回调而不是状态计数器：计数器在"离开页面再回来"时会重放一次震动。
     */
    private val onUnlocked: () -> Unit = {},
    /** 定位前置（写操作硬闸）。默认走真实定位；单测里换成假坐标。 */
    private val locate: suspend () -> LocateResult = { BikeLocator.currentLocation(Graph.appContext) },
    private val effects: KvcxSideEffects = AppKvcxSideEffects,
) {

    private val _state = MutableStateFlow(KvcxRideState())
    val state: StateFlow<KvcxRideState> = _state.asStateFlow()

    /** 登录态（会话不可用时恒 false）。UI 据此决定要不要露出「开锁 / 锁车 / 还车」。 */
    val loggedIn: StateFlow<Boolean> = session?.loggedIn ?: MutableStateFlow(false)

    /**
     * 查一次骑行状态。[quiet] = 失败完全静默（进页 / 回页那条路：页面主职责不是它，
     * 查询出问题不该打扰用户）；手动刷新传 false，失败给提示。
     *
     * **不轮询**：调用点只有进页、回页、用户动作、动作完成、手动刷新。
     */
    fun query(quiet: Boolean = true) {
        val session = session ?: return
        if (!session.loggedIn.value) return
        scope.launch {
            runCatching { session.queryUnderway() }
                .onSuccess { ride ->
                    _state.update { it.copy(ride = ride, fetchedAt = System.currentTimeMillis()) }
                }
                .onFailure { if (!quiet) onNotice(kvcxErrorText(it), NoticeTone.Error) }
        }
    }

    /**
     * 直接开锁：`createOrder` + 开锁（仓库层含在案订单闸与落地确认）。
     * 成功后：免费时长起点钉在**真正开锁的时刻**、顺手打开提醒开关（[KvcxSideEffects]）、
     * 并通过 [onUnlocked] 发一次一次性信号（页面补成功触感）。
     * 未确认时置 [KvcxRideState.unlockPending]，UI 给「重试开锁」出口。
     *
     * [carNum] 必须是**完整车号**（调用方用 `EbikeQr.resolveCarNum` 规范化过）。
     */
    fun unlock(carNum: String) = run(KvcxAction.UNLOCK) { session ->
        val point = locateOrNotice() ?: return@run
        val outcome = try {
            session.unlockBike(carNum, point.first, point.second)
        } catch (error: KvcBusinessError) {
            // 11035 = 微信支付分下单授权：只能在微信里完成（App 无法代调 wxpayScoreUse）。
            // 记会话标记，让 UI 给说明与出路，别让用户反复白点
            if (error.errorCode == KqcxAuth.CODE_SCORE_AUTH_REQUIRED) {
                _state.update { it.copy(scoreAuthRequired = true) }
            }
            throw error
        }
        when (outcome) {
            is UnlockOutcome.Unlocked -> {
                // 免费计时与开锁**同步**（2026-09-28 用户口径）：本机开锁是服务端确认的骑行
                // 开始（比"点扫一扫"这个准备动作强得多），提醒开关关着就顺手打开——
                // 倒计时通知因此跟着开锁一起来。开关在「快趣出行设置」里可见、可随时关掉。
                val reminderOpened = effects.ensureReminderEnabled()
                val (timerText, timerTone) = effects.startRideTimer()
                _state.update { it.copy(unlockPending = false, scoreAuthRequired = false) }
                onUnlocked()
                val head = if (outcome.helmetFlow) "已创建订单，请取下车盔" else "已开锁"
                onNotice(
                    head + "；" + timerText +
                        if (reminderOpened) "（已打开免费时长提醒）" else "",
                    timerTone,
                )
            }
            is UnlockOutcome.Unconfirmed -> {
                _state.update { it.copy(unlockPending = true) }
                onNotice("订单已创建但未确认开锁；可点「重试开锁」，或到快趣小程序核对", NoticeTone.Warning)
            }
        }
        refreshInternal()
    }

    /** 重试开锁（订单已在案、车锁未确认打开时；只重发开锁指令，不重建订单）。 */
    fun retryUnlock() = run(KvcxAction.RETRY_UNLOCK) { session ->
        val point = locateOrNotice() ?: return@run
        when (session.retryUnlock(point.first, point.second)) {
            is UnlockOutcome.Unlocked -> {
                _state.update { it.copy(unlockPending = false) }
                onNotice("已确认开锁", NoticeTone.Success)
            }
            is UnlockOutcome.Unconfirmed ->
                onNotice("仍未确认开锁；请到快趣小程序核对", NoticeTone.Warning)
        }
        refreshInternal()
    }

    /**
     * 临时锁车后继续骑（2026-09-28）：与 [retryUnlock] 是同一条调用（查在案订单 →
     * 重发 `greenCarUnlock`），只是文案不一样——**本机锁的车要能在本机解锁**，
     * 不该逼用户回小程序。
     */
    fun resumeRide() = run(KvcxAction.RESUME) { session ->
        val point = locateOrNotice() ?: return@run
        when (session.retryUnlock(point.first, point.second)) {
            is UnlockOutcome.Unlocked -> {
                _state.update { it.copy(unlockPending = false) }
                onNotice("已解锁，继续骑行", NoticeTone.Success)
            }
            is UnlockOutcome.Unconfirmed ->
                onNotice("未确认解锁；请到快趣小程序核对", NoticeTone.Warning)
        }
        refreshInternal()
    }

    /** 临时锁车（订单与计费继续，仅物理锁车）。 */
    fun tempLock() = run(KvcxAction.LOCK) { session ->
        val point = locateOrNotice() ?: return@run
        session.temporaryLock(point.first, point.second)
        // 车已锁，开锁未确认的悬置状态自然消解
        _state.update { it.copy(unlockPending = false) }
        onNotice("已临时锁车；订单与计费继续，用完记得还车", NoticeTone.Info)
        refreshInternal()
    }

    /**
     * 还车：静默锁 + 结束订单；成功后收计时、焚码、记一条本机骑行记录，并弹**结果卡**
     * （[KvcxRideState.returnSummary]）——还车是花钱那一刻，信息量不能只有一句话。
     */
    fun returnBike() = run(KvcxAction.RETURN) { session ->
        val point = locateOrNotice() ?: return@run
        val before = _state.value.ride
        val outcome = session.returnBike(point.first, point.second)
        effects.endRideAndBurn()
        _state.update { it.copy(unlockPending = false) }
        // 官方扣款确认节奏：先享后付自动扣，短轮询一次看是否结清（不做支付本身）。
        // 欠费金额一并带回来——结果卡上显示「¥3.50」比"有未结算费用"有用得多
        val payState = if (outcome.needPay == true) session.confirmUnpaidSettled() else null
        val summary = KvcxReturnSummary(
            carNum = before?.carNum,
            durationSeconds = before?.let {
                rideElapsedSeconds(it, _state.value.fetchedAt, System.currentTimeMillis())
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
        )
        effects.recordRide(summary)
        _state.update { it.copy(returnSummary = summary) }
        refreshInternal()
    }

    /** 关掉还车结果卡（用户点「知道了」）。 */
    fun dismissReturnSummary() {
        _state.update { it.copy(returnSummary = null) }
    }

    /**
     * 响铃寻车（2026-09-30）：车停在车堆里认不出时，让在案订单的车鸣笛。
     * 官方骑行页同款（无确认、无计费后果）；本地先挡一层「没有骑行」，
     * 服务端按当前订单定位车辆。
     */
    fun ringFindCar() {
        if (_state.value.ride == null) {
            onNotice("没有进行中的骑行，响铃寻车用不上", NoticeTone.Warning)
            return
        }
        run(KvcxAction.RING) { session ->
            session.ringFindCar()
            onNotice("已发送响铃，留意身边的提示音", NoticeTone.Success)
        }
    }

    /**
     * 锁状态查询（2026-09-30）：骑行卡上的锁徽标**点了就现查一次**，结果用提示说出答案。
     *
     * 权威来源是在案订单（`queryUnderwayOrder` 的 `lockStatus`）——解包确认小程序 V6 里
     * `carLockFlag` 只有定义没有调用（参数与响应无参考，死导出），**不走它**；徽标上
     * 显示的是最近一次拉取的快照，所以「查」= 重新拉 + 把答案说出来。
     */
    fun queryLockState() {
        val session = session ?: return
        if (_state.value.busy != null) return
        scope.launch {
            _state.update { it.copy(busy = KvcxAction.QUERY_LOCK) }
            try {
                val ride = session.queryUnderway()
                _state.update {
                    it.copy(ride = ride, fetchedAt = System.currentTimeMillis())
                }
                onNotice(
                    when {
                        ride == null -> "没有进行中的订单"
                        ride.locked == true -> "车辆已锁上；订单还在，计费继续"
                        else -> "车辆未锁，正在计费"
                    },
                    NoticeTone.Info,
                )
            } catch (error: Throwable) {
                onNotice(kvcxErrorText(error), NoticeTone.Error)
            } finally {
                _state.update { it.copy(busy = null) }
            }
        }
    }

    /** 写动作的统一壳：同一时刻只允许一个在跑（busy 态禁用按钮），异常统一转提示。 */
    private fun run(action: KvcxAction, block: suspend (KvcxRideSession) -> Unit) {
        val session = session
        if (session == null) {
            onNotice("快趣会话不可用", NoticeTone.Warning)
            return
        }
        if (_state.value.busy != null) return
        scope.launch {
            _state.update { it.copy(busy = action) }
            try {
                block(session)
            } catch (error: Throwable) {
                onNotice(kvcxErrorText(error), NoticeTone.Error)
            } finally {
                _state.update { it.copy(busy = null) }
            }
        }
    }

    /** 定位前置（写操作硬闸）：失败时提示并返回 null，调用方直接放弃本次动作。 */
    private suspend fun locateOrNotice(): Pair<Double, Double>? =
        when (val result = locate()) {
            is LocateResult.Ok -> result.lat to result.lng
            is LocateResult.Failed -> {
                onNotice(result.message, NoticeTone.Warning)
                null
            }
        }

    /** 动作成功后的状态刷新：失败不动旧值（动作本身已成功，刷新失败不打扰）。 */
    private suspend fun refreshInternal() {
        val session = session ?: return
        runCatching { session.queryUnderway() }.onSuccess { ride ->
            _state.update {
                it.copy(
                    ride = ride,
                    fetchedAt = System.currentTimeMillis(),
                    // 刷新到「已开锁」（未锁状态）时，开锁未确认的悬置状态自然消解
                    unlockPending = it.unlockPending && ride?.locked == true,
                )
            }
        }
    }
}

/**
 * 用车动作确认弹窗的规格（标题 / 要点 / 确认键 / 能不能免掉），**两页共用同一份**：
 * 这是用户唯一一次看清「开锁起计费」「还车可能产生调度费」的机会，不许各写一份。
 *
 * 正文是**要点列表**而不是一整段：弹窗里那几件事（计费、核对车号、支付分出路）
 * 各是一条独立责任，糊成一段谁都不看（2026-10-01 打磨）。
 */
internal data class KvcxConfirm(
    val title: String,
    /** 正文要点，逐条渲染（一条一句话，别把几件事塞进一条）。 */
    val points: List<String>,
    val confirmLabel: String,
    /**
     * 要不要在弹窗里给「不再提醒」的勾选（**只有开锁给**）。勾选后开锁直接发指令，
     * 落 `ebike_unlock_confirm`，出路在快趣出行设置 → 开锁与还车。
     *
     * **为什么只放开锁这一项**：四道闸里只有它是"用户对自己账号"的授权，而且开锁之后
     * App 侧一路有进度、成功与失败提示；还车涉及结算与可能的调度费，重试开锁是异常路径，
     * 都保留每次确认。
     */
    val allowSkip: Boolean = false,
)

/**
 * 取某个动作的确认弹窗规格。返回 null = 该动作**不需要二次确认**（调用方直接发）：
 * 当前是**临时锁车**与**解锁继续骑**——前者订单与计费继续、随时可再解锁，后者本来就在
 * 计费中，两者都没有新增的计费后果，误触代价低于多一次确认的打扰（2026-09-28 用户拍板）。
 * 响铃寻车与锁状态查询是只读动作，同样不弹。
 */
internal fun kvcxConfirmDialog(
    action: KvcxAction,
    carLabel: String,
): KvcxConfirm? = when (action) {
    KvcxAction.UNLOCK -> KvcxConfirm(
        title = "直接开锁？",
        points = listOf(
            "将用你的快趣账号为 $carLabel 创建订单并开锁，从开锁起按快趣规则计费。",
            "水贝贝是非官方客户端，请确认车辆与车号一致；开锁失败的善后以快趣为准。",
            // 支付分授权只能跳微信（wxpayScoreUse 是微信客户端专属 API，见 §4.32 的 11035）；
            // 唯一能让「本机直接开锁」端到端可用的出路是找客服关掉授权（2026-09-28 用户实测）
            "若账号为微信支付分免押，开锁时需跳转微信完成支付分授权（微信限制，App 无法代做）；" +
                "可联系快趣客服为账号关闭该授权，之后即可直接开锁。",
        ),
        confirmLabel = "开锁",
        allowSkip = true,
    )
    KvcxAction.RETRY_UNLOCK -> KvcxConfirm(
        title = "重试开锁？",
        points = listOf("将再次向 $carLabel 发送开锁指令（不会重复创建订单）。"),
        confirmLabel = "重试开锁",
    )
    KvcxAction.LOCK, KvcxAction.RESUME -> null
    // 响铃寻车 / 锁状态查询没有新增计费后果，也不经二次确认闸（不走 pendingAction）
    KvcxAction.RING, KvcxAction.QUERY_LOCK -> null
    KvcxAction.RETURN -> KvcxConfirm(
        title = "还车？",
        points = listOf(
            "将先静默锁车再结束订单。请确认：车辆已停好、随身物品已带走。",
            "还车后订单结束；若不在还车区可能产生调度费（App 不会替你确认调度费）。",
        ),
        confirmLabel = "还车",
    )
}

/** 快趣动作的失败文案（业务错误已是用户可读文本，见仓库层 `server {}`）。 */
internal fun kvcxErrorText(error: Throwable): String = when (error) {
    is KvcBusinessError -> error.message ?: "操作失败"
    is KvcProtocolException -> "响应异常：${error.message ?: "格式不符"}"
    is SocketTimeoutException -> "网络超时，请稍后重试"
    is IOException -> "网络不可用，请检查网络后重试"
    else -> "操作异常：${error.message ?: error.javaClass.simpleName}"
}

/**
 * 骑行时长的**本地走时**口径（纯函数，单测锁定）：以最近一次拉取的 `totalDate` 为基准，
 * 加上"拉取至今"的秒数。[fetchedAt] ≤ 0（还没查过）时不再加。无时长数据时返回 null。
 */
internal fun rideElapsedSeconds(
    ride: KqcxAuth.Ride,
    fetchedAt: Long,
    now: Long,
): Long? {
    val base = ride.totalDateSeconds ?: return null
    if (fetchedAt <= 0L) return base
    return base + (now - fetchedAt).coerceAtLeast(0L) / 1_000L
}

/**
 * 骑行秒表的**本地走时**（不联网）：每秒重算并返回 `formatRideDuration` 的展示串
 * （骑行卡那一套 `00:13` 口径，与官方小程序一致）；无订单/无时长数据时返回 null。
 */
@Composable
internal fun rememberRideElapsed(ride: KqcxAuth.Ride?, fetchedAt: Long): String? {
    if (ride == null) return null
    val now by produceState(
        initialValue = System.currentTimeMillis(),
        key1 = ride.carNum,
        key2 = fetchedAt,
    ) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    return rideElapsedSeconds(ride, fetchedAt, now)?.let(KqcxAuth::formatRideDuration)
}

/*
 * 还车结果卡（2026-09-28 首版）在 2026-09-29 的结构重构里**从 AlertDialog 改成动作条里的
 * 结算卡**，实现在 `RidePanels.kt` 的 `RideSettledBar`——还完车用户常常要接着骑下一辆，
 * 弹窗会把流程打断；动作条还能顺手给「继续找车」这条出路。本文件只保留数据口径
 * （[KvcxReturnSummary] 的字段与 [KvcxReturnSummary.settleText]），文案不在这里重复。
 */

/**
 * 起免费时长计时并把结果映射成提示文案与语气（点扫一扫 / 本机开锁成功共用）。
 * 起点一律取**当下**：扫一扫那一刻或真正开锁那一刻。
 */
internal suspend fun startFreeRideNotice(): Pair<String, NoticeTone> {
    val outcome = EbikeFreeRideReminder.startRide(Graph.appContext, System.currentTimeMillis())
    return when (outcome) {
        EbikeFreeRideReminder.Outcome.Started ->
            "已开始计时，通知栏已显示倒计时" to NoticeTone.Success
        EbikeFreeRideReminder.Outcome.StartedNoNotification ->
            "通知被关闭，提醒发不出来，请到系统设置打开" to NoticeTone.Warning
        EbikeFreeRideReminder.Outcome.StartedSilent ->
            "已开始计时（免费时长提醒未开启）" to NoticeTone.Info
        is EbikeFreeRideReminder.Outcome.Failed ->
            "已开始计时；提醒排程失败：${outcome.message}" to NoticeTone.Warning
        else ->
            "已开始计时" to NoticeTone.Info
    }
}

/**
 * 打开微信（结清欠费的唯一去处）：待支付页在快趣小程序里，而**微信不给第三方直达小程序
 * 具体页面**——URL Scheme / 短链必须由小程序自己的服务端生成（要它的 appsecret），
 * 开放平台拉起小程序又要求 App 与小程序在同一开放平台账号下绑定。所以只能把用户送到微信。
 *
 * 到了微信之后用户为什么"找不到支付入口"（2026-09-30 用户实测）：官方的待支付页不挂菜单，
 * 只有两条路能到——**主页的「待支付」横幅**（onShow 查一次、60 秒节流、关过一次冷却 5 分钟）
 * 和**历史订单里的待支付记录**（点进去就是支付页）。提示把这两条写出来，别让用户白进一趟。
 */
internal fun openWechatForSettle(context: android.content.Context, onHint: (String) -> Unit) {
    val launch = try {
        context.packageManager.getLaunchIntentForPackage("com.tencent.mm")
    } catch (_: Exception) {
        null
    }
    if (launch == null) {
        onHint("没装微信；欠费可在快趣小程序（或找快趣客服）结清")
        return
    }
    try {
        context.startActivityOutsideApp(launch)
        onHint(
            "微信已打开：进快趣小程序后，主页会出现「待支付」横幅（之前关过要等 5 分钟），" +
                "或在「历史订单」里点那笔待支付订单直接付",
        )
    } catch (_: Exception) {
        onHint("无法打开微信；请手动进快趣小程序，主页「待支付」横幅或历史订单可直达付款页")
    }
}

/** 时刻文案 `HH:mm`（骑行卡上的「更新于 / 位置」）；两页共用一个格式化器。 */
internal fun clockText(millis: Long): String =
    Instant.ofEpochMilli(millis)
        .atZone(ZoneId.systemDefault())
        .toLocalTime()
        .format(DateTimeFormatter.ofPattern("HH:mm", Locale.US))
