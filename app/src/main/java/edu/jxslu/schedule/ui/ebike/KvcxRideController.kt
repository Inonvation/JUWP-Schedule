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
import edu.jxslu.schedule.data.kqcx.LockOutcome
import edu.jxslu.schedule.startActivityOutsideApp
import edu.jxslu.schedule.data.kqcx.UnlockOutcome
import edu.jxslu.schedule.domain.KqcxAuth
import edu.jxslu.schedule.domain.KvcBusinessError
import edu.jxslu.schedule.domain.KvcProtocolException
import edu.jxslu.schedule.domain.RideRecord
import edu.jxslu.schedule.ui.common.rememberResumedNow
import edu.jxslu.schedule.ui.common.NoticeTone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 开锁成功后的「锁车 / 还车」冷却时长。
 *
 * 车把「已解锁」上报服务端是**异步**的（开锁指令发出 ≠ 车侧已上报完成，常见 3~15 秒，
 * 放宽锁车确认窗就是为同一段延迟），紧接着发锁车 / 还车指令既容易撞上还在走的
 * 上报链、也容易把状态搅成谁都对不上的局面。冷却只挡锁车与还车这两个**物理写动作**
 * （UI 置灰），开锁位（重发解锁 / 解锁继续骑）不挡——那是把车打开的动作，没有新增计费后果。
 */
internal const val UNLOCK_COOLDOWN_MS = 3_000L

/**
 * 冷却剩余秒数（纯函数，单测锁定）：`until - now` **向上取整**到整秒——还剩 2.1 秒
 * 显示 3s，数字宁可多显示一下、不提前跳 0；≤ 0（无冷却 / 已结束 / 时钟回拨）一律回 0。
 */
internal fun unlockCooldownSecondsLeft(until: Long, now: Long): Int {
    val remain = until - now
    if (remain <= 0L) return 0
    return ((remain + 999L) / 1000L).toInt()
}

/**
 * 开锁成功后的**对账 / 补全**次数与间隔（见 [KvcxRideController.syncAfterUnlock]）。
 *
 * 第一拍立即发（就是原来的"动作后刷新"），之后每 [UNLOCK_SYNC_INTERVAL_MS] 一拍；
 * 快照同时带回已骑时长与电量就提前收工。间隔取官方同款 1.5 秒节奏（与临时锁车确认窗同源），
 * **不是常驻轮询**：只在开锁动作后跑、有界，且在后台不占动作按钮。单测注入 0 让
 * Unconfined 同步跑完。
 */
internal const val UNLOCK_SYNC_ATTEMPTS = 5
internal const val UNLOCK_SYNC_INTERVAL_MS = 1_500L

/** 快趣用车动作（DESIGN §4.32，B/C 档）：同一时刻只允许一个在跑。 */
enum class KvcxAction {
    UNLOCK,
    RETRY_UNLOCK,
    /** 临时锁车后继续骑（与 [RETRY_UNLOCK] 是同一条调用，只是文案不同）。 */
    RESUME,
    LOCK,
    RETURN,
    /** 响铃寻车：让在案订单的车鸣笛。无计费后果，不弹确认。 */
    RING,
    /** 锁状态查询：按权威来源（在案订单）现查一次锁没锁上。 */
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
    /**
     * [busy] 的起始时刻：开锁/还车链路最坏可挂一两分钟（定位 + 多次查询各自超时），
     * 按钮只写「开锁中…」用户不知道是卡死还是在等。UI 按「已等 N 秒」走秒，
     * 超 [BUSY_LONG_WAIT_SECONDS] 补「仍未确认，可刷新状态或去小程序核对」。
     */
    val busyAt: Long = 0L,
    /** 开锁指令已发但未确认；确认开锁/还车/刷新到已开时清掉。 */
    val unlockPending: Boolean = false,
    /**
     * 临时锁车指令已发但窗口内未确认到"已锁"：**不是失败**——车辆把
     * "已锁"上报服务端是异步的（3~15 秒常态，开锁后立刻锁车更慢）。UI 显示
     * 「锁车确认中」并给刷新/再点一次锁车的出路；快照刷新到已锁即消解
     * （裁决在 [withRideSnapshot]，与 `unlockPending` 同一份）。
     *
     * 与 [unlockPending] 物理态互斥（已锁 ↔ 未开锁），但**标志位可短暂共存**（两边都
     * 未确认时，UI 里 unlockPending 优先）；解锁类动作（[resumeRide]/[retryUnlock]）与
     * 订单边界动作（开锁成功、还车）会把它一并作废——清悬置时别只清一边。
     */
    val lockPending: Boolean = false,
    /**
     * 开锁成功后的「锁车 / 还车」冷却截止时刻（epoch 毫秒；0 = 无冷却）。
     *
     * 开锁指令发出后车侧上报是异步的（见 [UNLOCK_COOLDOWN_MS]），冷却期内这两个物理
     * 写动作在 UI 上置灰。开锁类动作成功（[unlock] / [retryUnlock] / [resumeRide]）都会
     * 刷新它；**不是失败态**，只是一道节流闸：页面按 [unlockCooldownSecondsLeft] 逐秒
     * 显示倒计时，过期即失效（不写回 0 也没关系）。
     */
    val unlockCooldownUntil: Long = 0L,
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
 * 还车结果卡的内容（DESIGN §3.9）：还车是"花钱那一刻"，只发一句 Snackbar
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
    /** true = 还车已成功，扣款结果还在后台查；此时不显示「去微信结清」。 */
    val settling: Boolean = false,
) {
    /** 时长文案用人话（「13 分钟」），与历史列表同一口径（`RideRecord.durationText`）。 */
    val durationText: String? get() = durationSeconds?.let(RideRecord::durationText)

    val feeText: String? get() = feeCents?.let { "¥%.2f".format(it / 100.0) }

    /**
     * 结算状态一句话（单测锁定）。**别把"没确认到"说成"欠费"**：手动还车的结果卡先出，
     * 扣款在后台查；金额未知时只说"结算中或未结清"。
     *
     * 欠费那行把**能直达付款页的两条路**写出来：
     * 主页的待支付横幅（关过一次会冷却 5 分钟）、历史订单里的待支付记录——
     * 光说"去小程序结清"用户进了小程序也找不到支付入口。
     */
    val settleText: String
        get() = when {
            settling -> "费用结算中，稍后自动更新"
            needPay == null -> "订单已在其他端结束"
            !needPay -> "本次无需支付"
            settled == true -> "费用已自动结清"
            owedCents != null ->
                "有未结算费用 ¥%.2f；进快趣小程序点主页「待支付」横幅，或在历史订单里点那笔待支付"
                    .format(owedCents / 100.0)
            else -> "费用结算中或未结清，可在快趣小程序核对"
        }

    /** 结算那行要不要用警告色（欠费才用）。 */
    val settleWarning: Boolean get() = !settling && needPay == true && settled != true

    /** 需不需要给「去微信结清」的出路（订单欠费时才给）。 */
    val needsSettle: Boolean get() = !settling && needPay == true && settled != true
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

    /**
     * 开锁成功（新一轮骑行开始）：按「自动还车」设置排精确闹钟。
     * 与 [startRideTimer] 同一刻调用——自动还车的触发点是「计时起点 + 15 分钟 − 提前量」，
     * 必须先有计时起点。开关关着时是一次空操作。
     */
    suspend fun scheduleAutoReturn()

    /** 还车善后：收计时（撤闹钟/停服务/清通知）+ 焚毁相册里的码 + 撤销自动还车闹钟。 */
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

    override suspend fun scheduleAutoReturn() {
        // 上一轮的结果通知先撤掉（「已自动还车」不能与新一轮骑行并存），再按设置收口：
        // 排闹钟 / 到点补跑 / 开关关着时撤销
        EbikeAutoReturnNotifier.cancel(Graph.appContext)
        EbikeAutoReturnReminder.reconcile(Graph.appContext)
    }

    override suspend fun endRideAndBurn() {
        EbikeFreeRideReminder.endRide(Graph.appContext)
        // 订单已经结束：自动还车再排着也没有对象可还
        EbikeAutoReturnReminder.cancel(Graph.appContext)
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
    private val locate: suspend () -> LocateResult = {
        // 写动作不能像地图页那样等 8 秒；5 秒拿不到就走车辆锚点 / 明确提示。
        BikeLocator.currentLocation(Graph.appContext, timeoutMs = 5_000L)
    },
    private val effects: KvcxSideEffects = AppKvcxSideEffects,
    /**
     * 开锁后补全重拉的间隔（毫秒，见 [syncAfterUnlock]）。单测注入 0 让 Unconfined
     * 同步跑完；生产用 [UNLOCK_SYNC_INTERVAL_MS]（官方同款 1.5 秒节奏）。
     */
    private val unlockSyncIntervalMs: Long = UNLOCK_SYNC_INTERVAL_MS,
) {

    private val _state = MutableStateFlow(KvcxRideState())
    val state: StateFlow<KvcxRideState> = _state.asStateFlow()

    /** 登录态（会话不可用时恒 false）。UI 据此决定要不要露出「开锁 / 锁车 / 还车」。 */
    val loggedIn: StateFlow<Boolean> = session?.loggedIn ?: MutableStateFlow(false)

    /**
     * 快照写入的时序序号：每次写动作（[run]）与锁状态现查（[queryLockState]）
     * 开始即 +1。查询在发起前记下它，落地时序号不一致就**丢弃结果**——那条请求比动作更早
     * 发出，回来时可能比动作后的状态旧（晚到的旧包把新状态盖掉，正是「还车后立即再开锁，
     * 骑行信息被旧快照抹掉 / 显示成未知」一类的来路）。
     *
     * 只保护 [query] 这条"被动刷新"路径；动作内部自己的对账不走这里（它们本来就新）。
     */
    private var snapshotSeq = 0L

    /**
     * 查询先后序号：每次**读**（[query] / [refreshInternal] / [queryLockState] /
     * [syncAfterUnlock]）开始即 +1。只改快照不写动作，用来防止两条并发查询里**先发后到**
     * 的旧包覆盖新包——那会让已骑时长回退、电量跳回旧值。
     */
    private var readSeq = 0L

    /**
     * 查一次骑行状态。[quiet] = 失败完全静默（进页 / 回页那条路：页面主职责不是它，
     * 查询出问题不该打扰用户）；手动刷新传 false，失败给提示。
     *
     * **不轮询**：调用点只有进页、回页、用户动作、动作完成、手动刷新。
     * 快照落库统一走 [withRideSnapshot]——「点刷新核实」要能消解锁车/开锁悬置态；
     * **所有**刷新路径（本方法 / 动作后刷新 / 胶囊现查 [queryLockState]）共用同一份裁决。
     */
    fun query(quiet: Boolean = true) {
        val session = session ?: return
        if (!session.loggedIn.value) return
        // 写动作在跑（或刚挂在它上面）时查询直接跳过：它的请求早于动作，结果可能比动作后
        // 的状态旧；动作完成时会自己对账，这次不查没有损失。回页（ON_RESUME）撞上动作
        // 中途正是这条要挡的场景。
        if (_state.value.busy != null) return
        // 发起前记序号，落地时对账（见 [snapshotSeq] 的说明）
        val seq = snapshotSeq
        val read = ++readSeq
        scope.launch {
            runCatching { session.queryUnderway() }
                .onSuccess { ride ->
                    if (seq != snapshotSeq || read != readSeq) return@onSuccess
                    _state.update { it.withRideSnapshot(ride) }
                }
                .onFailure {
                    if (seq == snapshotSeq && read == readSeq && !quiet) {
                        onNotice(kvcxErrorText(it), NoticeTone.Error)
                    }
                }
        }
    }

    /**
     * 直接开锁：`createOrder` + 开锁。仓库层写接口成功即返回，不再等状态落地。
     * 成功后：免费时长起点钉在**真正开锁的时刻**、顺手打开提醒开关（[KvcxSideEffects]）、
     * 并通过 [onUnlocked] 发一次一次性信号（页面补成功触感）。
     * 未确认时置 [KvcxRideState.unlockPending]，UI 给「重试开锁」出口。
     *
     * [carNum] 必须是**完整车号**（调用方用 `EbikeQr.resolveCarNum` 规范化过）。
     */
    fun unlock(carNum: String) = run(KvcxAction.UNLOCK) { session ->
        val point = locateOrNotice() ?: return@run
        val outcome = try {
            session.unlockBike(carNum, point.lat, point.lng, point.source)
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
                // **界面先行**：确认快照先落 UI——响应回来的第一帧就切骑行卡，不等下面那串善后
                // （开关读写 / 计时落盘 / 排闹钟 / 起通知服务，几百毫秒起，低端机更久）。
                val confirmedAt = System.currentTimeMillis()
                val confirmedRide = (outcome.ride ?: KqcxAuth.Ride(
                    carNum = outcome.carNum,
                    bluetoothName = null,
                    locked = false,
                )).adoptConfirmedUnlock()
                _state.update {
                    it.copy(
                        // 确认那一刻的快照直接采纳：新订单在服务端异步落账，确认快照可能
                        // 缺字段、后续那次刷新还可能整份落空——先把车号与已有的字段摆上，
                        // 卡片不必空等（缺的部分由 [syncAfterUnlock] 补）。
                        ride = confirmedRide,
                        fetchedAt = confirmedAt,
                        unlockPending = false,
                        lockPending = false,
                        scoreAuthRequired = false,
                        unlockCooldownUntil = confirmedAt + UNLOCK_COOLDOWN_MS,
                        // 重新开锁成功即离开结算态（结算卡上的「重新使用该车」）：「已还车」的
                        // 卡片只在"还完车待决定"这段时间成立。失败路径不经过这里，卡片保留，
                        // 用户可以原地再点一次。
                        returnSummary = null,
                    )
                }
                // 成功触感也立即发：它属于"开锁成功"这一刻，不该被善后拖后
                onUnlocked()
                // 免费计时与开锁**同步**：本机开锁是服务端确认的骑行
                // 开始（比"点扫一扫"这个准备动作强得多），提醒开关关着就顺手打开——
                // 倒计时通知因此跟着开锁一起来。开关在「快趣出行设置」里可见、可随时关掉。
                // **顺序纪律**：这段善后在界面更新之后跑，但**不许**挪到 [syncAfterUnlock]
                // 后面——补全在后台跑，挪过去会让通知栏倒计时白等。
                val reminderOpened = effects.ensureReminderEnabled()
                val (timerText, timerTone) = effects.startRideTimer()
                // 自动还车与计时同刻排（触发点 = 计时起点 + 15 分钟 − 提前量；见
                // EbikeAutoReturnReminder）。开关关着时是一次空操作
                effects.scheduleAutoReturn()
                val head = if (outcome.helmetFlow) "已创建订单，请取下车盔" else "已开锁"
                onNotice(
                    head + "；" + timerText +
                        if (reminderOpened) "（已打开免费时长提醒）" else "",
                    timerTone,
                )
                // 对账 + 补全放到后台：动作的 busy 在这一拍就结束，不让最多 5 次查询
                // 继续占住按钮。**不走**共享的 refreshInternal（见 [syncAfterUnlock]）：
                // 那份在服务端抽风回空时会把刚确认的订单整个抹掉。
                val syncSeq = snapshotSeq
                scope.launch { syncAfterUnlock(outcome.carNum, syncSeq) }
            }
            is UnlockOutcome.Unconfirmed -> {
                // 订单已创建（只是没确认到锁）：同样离开结算态——「已还车」的卡片
                // 不能与在案订单并存。建单成功但开锁请求断网时，先把最小订单摆出来，
                // 用户才有「重试开锁」的对象；后台补全再确认锁状态。
                val pendingRide = outcome.ride ?: KqcxAuth.Ride(
                    carNum = outcome.carNum,
                    bluetoothName = null,
                    locked = null,
                )
                _state.update {
                    it.copy(
                        ride = pendingRide,
                        fetchedAt = System.currentTimeMillis(),
                        unlockPending = true,
                        returnSummary = null,
                    )
                }
                onNotice("订单已创建但未确认开锁；可点「重试开锁」，或到快趣小程序核对", NoticeTone.Warning)
                val syncSeq = snapshotSeq
                scope.launch { syncAfterUnlock(outcome.carNum, syncSeq) }
            }
        }
    }

    /** 重试开锁（订单已在案、车锁未确认打开时；只重发开锁指令，不重建订单）。 */
    fun retryUnlock() = run(KvcxAction.RETRY_UNLOCK) { session ->
        val ride = _state.value.ride
        if (ride == null) {
            onNotice("没有在案订单，请重新开锁", NoticeTone.Warning)
            return@run
        }
        val point = locateOrNotice(ride) ?: return@run
        when (session.retryUnlock(ride.carNum, point.lat, point.lng, point.source)) {
            is UnlockOutcome.Unlocked -> {
                _state.update {
                    // 乐观采纳确认结果（本地翻「未锁」），卡片不必等下一次刷新才翻面
                    it.copy(
                        ride = it.ride?.copy(locked = false),
                        unlockPending = false,
                        lockPending = false,
                        // 刚刚开锁 = 刚"开车"：锁车 / 还车同样进冷却（与 [unlock] 同一口径）
                        unlockCooldownUntil = System.currentTimeMillis() + UNLOCK_COOLDOWN_MS,
                    )
                }
                onNotice("已确认开锁", NoticeTone.Success)
            }
            is UnlockOutcome.Unconfirmed -> {
                // 解锁意图发出即作废锁车悬置（与 resumeRide 同一口径）：否则快照回报
                // 未锁时裁决先消解 unlockPending、把残留的 lockPending 暴露成
                // 「锁车确认中…」——和用户刚做的开锁动作打架
                _state.update { it.copy(lockPending = false) }
                onNotice("仍未确认开锁；请到快趣小程序核对", NoticeTone.Warning)
            }
        }
        refreshInternal()
    }

    /**
     * 临时锁车后继续骑：与 [retryUnlock] 是同一条调用（查在案订单 → 重发
     * `greenCarUnlock`），只是文案不一样——**本机锁的车要能在本机解锁**，不该逼用户
     * 回小程序。
     *
     * 骑行卡左位「开锁」（已解锁状态）也走这里——成功文案按调用前锁状态分流；
     * **解锁意图发出即作废 [KvcxRideState.lockPending]**（成功/未确认都清，防
     * 「锁车确认中…」和刚做的解锁操作打架），成功还乐观把本地快照翻成「未锁」
     * （不等下一次刷新才翻面）。
     */
    fun resumeRide() = run(KvcxAction.RESUME) { session ->
        val ride = _state.value.ride
        if (ride == null) {
            onNotice("没有在案订单，请重新开锁", NoticeTone.Warning)
            return@run
        }
        val point = locateOrNotice(ride) ?: return@run
        val wasLocked = ride.locked == true
        when (session.retryUnlock(ride.carNum, point.lat, point.lng, point.source)) {
            is UnlockOutcome.Unlocked -> {
                _state.update {
                    it.copy(
                        ride = it.ride?.copy(locked = false),
                        unlockPending = false,
                        lockPending = false,
                        // 解锁继续骑也是"刚开锁"：锁车 / 还车同样进冷却（与 [unlock] 同一口径）
                        unlockCooldownUntil = System.currentTimeMillis() + UNLOCK_COOLDOWN_MS,
                    )
                }
                onNotice(
                    if (wasLocked) "已解锁，继续骑行" else "已确认开锁",
                    NoticeTone.Success,
                )
            }
            is UnlockOutcome.Unconfirmed -> {
                _state.update { it.copy(lockPending = false) }
                onNotice("未确认解锁；请到快趣小程序核对", NoticeTone.Warning)
            }
        }
        refreshInternal()
    }

    /**
     * 临时锁车（订单与计费继续，仅物理锁车）。
     *
     * 确认到已锁 → 常规成功提示；窗口内未确认 → 置 [KvcxRideState.lockPending] 并给
     * **Warning**（不是 Error）：上报慢是常态、锁车免确认无计费后果，"失败"这个词
     * 会把用户推去反复点。出口两条：点刷新核实状态、车真没锁时再点一次锁车
     * （对在案订单重发指令，仓库层不再早退）。
     */
    fun tempLock() = run(KvcxAction.LOCK) { session ->
        val point = locateOrNotice(_state.value.ride) ?: return@run
        when (val outcome = session.temporaryLock(point.lat, point.lng, point.source)) {
            is LockOutcome.Locked -> {
                // 车已锁，开锁未确认的悬置状态自然消解；顺手采纳确认快照——
                // 卡片不必等下一次刷新才翻成「已锁」
                _state.update {
                    it.copy(
                        ride = outcome.ride,
                        fetchedAt = System.currentTimeMillis(),
                        unlockPending = false,
                        lockPending = false,
                    )
                }
                onNotice("已临时锁车；订单与计费继续，用完记得还车", NoticeTone.Info)
            }
            is LockOutcome.Unconfirmed -> {
                // 窗口内最后一份快照一并采纳（[LockOutcome.lastKnown] 的设计用途）
                val lastKnown = outcome.lastKnown
                _state.update {
                    it.copy(
                        ride = lastKnown ?: it.ride,
                        fetchedAt = if (lastKnown != null) System.currentTimeMillis() else it.fetchedAt,
                        lockPending = true,
                    )
                }
                onNotice(
                    "锁车指令已发送，车辆回报稍慢；可点刷新核实，车没锁上就再点一次锁车",
                    NoticeTone.Warning,
                )
            }
        }
        refreshInternal()
    }

    /**
     * 还车：静默锁 + 结束订单；成功后收计时、焚码、记一条本机骑行记录，并弹**结果卡**
     * （[KvcxRideState.returnSummary]）——还车是花钱那一刻，信息量不能只有一句话。
     *
     * 结算口径（本地走时在订单结束那一刻截停、结果卡字段）统一在 [performReturnBike] 里，
     * 与自动还车共用——**别在这里另写一份**。手动还车不在关键路径上等扣款结果：结果卡
     * 先显示「结算中」，后台查询回来再改写同一张卡。
     */
    fun returnBike() = run(KvcxAction.RETURN) { session ->
        val before = _state.value.ride
        if (before == null) {
            onNotice("没有进行中的骑行", NoticeTone.Warning)
            return@run
        }
        val point = locateOrNotice(before, allowInvalidFallback = true)
        if (point == null) return@run
        val summary = performReturnBike(
            session = session,
            before = before,
            fetchedAt = _state.value.fetchedAt,
            gcjLat = point.lat,
            gcjLng = point.lng,
            locationSource = point.source,
            locationValid = point.source != "none",
            pollSettlement = false,
        ) {
            effects.endRideAndBurn()
        }
        // 结束订单成功即落结果卡：订单状态已经在本地结束，不再等 queryUnderwayOrder
        // 的“订单消失”回包，也不让扣款轮询占住主动作。
        _state.update {
            it.copy(
                ride = null,
                unlockPending = false,
                lockPending = false,
                returnSummary = summary,
            )
        }
        effects.recordRide(summary)
        if (summary.settling) {
            // 欠费/自动扣款结果在后台补；结果卡先显示“结算中”。只回写同一张结果卡，
            // 用户已经关掉卡或开始下一单时不复活旧内容。
            scope.launch {
                val payState = try {
                    session.confirmUnpaidSettled()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    null
                }
                _state.update { state ->
                    val shown = state.returnSummary
                    if (shown !== summary) return@update state
                    state.copy(
                        returnSummary = shown.copy(
                            settling = false,
                            settled = when (payState) {
                                is KqcxAuth.UnpayState.Settled -> true
                                is KqcxAuth.UnpayState.Owed -> false
                                null -> null
                            },
                            owedCents = (payState as? KqcxAuth.UnpayState.Owed)?.amountCents,
                        ),
                    )
                }
            }
        }
    }

    /** 关掉还车结果卡（用户点「知道了」）。 */
    fun dismissReturnSummary() {
        _state.update { it.copy(returnSummary = null) }
    }

    /**
     * 响铃寻车：车停在车堆里认不出时，让在案订单的车鸣笛。
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
     * 锁状态查询：骑行卡上的**状态胶囊点了就现查一次**，结果用提示说出答案。
     *
     * 权威来源是在案订单（`queryUnderwayOrder` 的 `lockStatus`）——解包确认小程序 V6 里
     * `carLockFlag` 只有定义没有调用（参数与响应无参考，死导出），**不走它**；胶囊上
     * 显示的是最近一次拉取的快照，所以「查」= 重新拉 + 把答案说出来。
     *
     * 落库与 [withRideSnapshot] 共用同一份悬置裁决——现查到
     * 未锁即消解「开锁未确认」，查到订单消失悬置全清，别绕过它直接改 `ride`。
     */
    fun queryLockState() {
        val session = session ?: return
        if (_state.value.busy != null) return
        // 这次现查的答案是最新的：把在飞的旧查询一并作废（落地时序号对不上即丢弃）
        val seq = ++snapshotSeq
        val read = ++readSeq
        scope.launch {
            _state.update { it.copy(busy = KvcxAction.QUERY_LOCK) }
            try {
                val ride = session.queryUnderway()
                if (seq == snapshotSeq && read == readSeq) {
                    _state.update { it.withRideSnapshot(ride) }
                    onNotice(
                        when {
                            ride == null -> "没有进行中的订单"
                            ride.locked == true -> "车辆已锁上；订单还在，计费继续"
                            else -> "车辆未锁，正在计费"
                        },
                        NoticeTone.Info,
                    )
                }
            } catch (error: CancellationException) {
                throw error // 取消穿透：不能把页面/作用域撤销报成「操作异常」
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
            // 双重检查：外面那次读的是调用当刻，调度器不是 Main.immediate
            // 时两次调用可能都通过外闸——这里在置 busy 前再确认一次，两枚动作不会并发
            if (_state.value.busy != null) return@launch
            // 动作开始即推进序号：在飞的旧查询落地时会被丢弃（见 [snapshotSeq] / [readSeq]）
            snapshotSeq += 1
            readSeq += 1
            _state.update { it.copy(busy = action, busyAt = System.currentTimeMillis()) }
            try {
                block(session)
            } catch (error: CancellationException) {
                throw error // 取消穿透：不能把页面/作用域撤销报成「操作异常」
            } catch (error: Throwable) {
                onNotice(kvcxErrorText(error), NoticeTone.Error)
            } finally {
                _state.update { it.copy(busy = null, busyAt = 0L) }
            }
        }
    }

    /**
     * 定位前置（写操作硬闸）：实时定位失败时，[fallback] 是当前车辆锚点（还车 / 重试开锁 /
     * 临时锁车）就用车辆坐标继续；没有锚点才提示并放弃。开锁首次动作没有订单锚点，只能靠
     * 五分钟内的缓存定位，拿不到就明确失败。只有还车允许 [allowInvalidFallback]（官方
     * 允许 locationValid=0 结束订单）；开锁类动作锚点无效仍中止。
     */
    private suspend fun locateOrNotice(
        fallback: KqcxAuth.Ride? = null,
        allowInvalidFallback: Boolean = false,
    ): LocateResult.Ok? {
        val result = locate()
        if (result is LocateResult.Ok) return result

        val lat = fallback?.lat
        val lng = fallback?.lng
        if (lat != null && lng != null && isValidActionCoord(lat, lng)) {
            onNotice("定位不到你，使用车辆位置继续", NoticeTone.Info)
            return LocateResult.Ok(lat, lng, source = "vehicle")
        }
        if (lat != null && lng != null) {
            if (allowInvalidFallback) {
                onNotice("车辆没有有效位置，按无定位还车", NoticeTone.Warning)
                return LocateResult.Ok(0.0, 0.0, source = "none")
            }
            onNotice("车辆没有有效位置，暂时无法操作", NoticeTone.Warning)
            return null
        }
        onNotice((result as LocateResult.Failed).message, NoticeTone.Warning)
        return null
    }

    private fun isValidActionCoord(lat: Double, lng: Double): Boolean =
        lat.isFinite() && lng.isFinite() &&
            lat in -90.0..90.0 && lng in -180.0..180.0 &&
            !(lat == 0.0 && lng == 0.0)

    /**
     * 骑行状态快照落库 + 悬置态裁决（[query] / [refreshInternal] / [queryLockState] 共用，
     * 别各写一份）：
     * - 开锁未确认：刷新到「未锁」才消解（锁状态缺失 / 订单消失同样按消解处理——
     *   无信息不保持悬置）——上报慢时"已锁快照"不代表失败，保持悬置等下一次快照；
     * - 锁车确认中：刷新到「已锁」即确认成功（锁状态缺失 / 订单消失同样按消解处理）。
     */
    private fun KvcxRideState.withRideSnapshot(ride: KqcxAuth.Ride?): KvcxRideState = copy(
        // 同车快照缺字段时沿用上一份：时长 / 费用 / 电量一律保留；锁状态只在没有悬置
        // 待核实时沿用（见 [carryKnownRideFields]）——悬置期间未知比旧值诚实，裁决仍按
        // 这一份快照的原文判。
        ride = ride?.let { fresh ->
            carryKnownRideFields(
                previous = this.ride,
                fresh = fresh,
                carryLock = !unlockPending && !lockPending,
            )
        },
        fetchedAt = System.currentTimeMillis(),
        unlockPending = unlockPending && ride?.locked == true,
        lockPending = lockPending && ride != null && ride.locked == false,
    )

    /** 动作成功后的状态刷新：失败不动旧值（动作本身已成功，刷新失败不打扰）。 */
    private suspend fun refreshInternal() {
        val session = session ?: return
        val seq = snapshotSeq
        val read = ++readSeq
        runCatching { session.queryUnderway() }.onSuccess { ride ->
            if (seq != snapshotSeq || read != readSeq) return@onSuccess
            _state.update { it.withRideSnapshot(ride) }
        }
    }

    /**
     * 开锁成功后的**对账 + 有界补全**。
     *
     * 为什么不能只做一次刷新：刚创建的订单在服务端是**异步落账**的——确认快照可能缺
     * 已骑时长 / 锁状态，紧随的那次刷新也可能整份落空（同一辆车刚还完就再开，服务端
     * 要先后走完结束与新建两条账）。本 App 不轮询，一次刷新落空/缺字段就会一直挂在
     * 骑行卡上（「已骑 --」「锁状态未知」），直到用户手动刷新。
     *
     * 口径（不是常驻轮询）：
     * - 只在开锁动作后跑，最多 [UNLOCK_SYNC_ATTEMPTS] 拍；时长和电量都到手才提前收工
     *   （常见路径 = 采纳的快照就完整 = 0 次查询）；
     * - 一拍为空**不写状态**（订单刚刚确认在案，这一拍的空是脏回包，写下去正是"信息丢失"），
     *   等下一拍；
     * - 报回「已锁」与刚确认的开锁打架：照实采纳（服务端的说法是权威），但停止补全。
     */
    private suspend fun syncAfterUnlock(carNum: String, seq: Long) {
        val session = session ?: return
        val read = ++readSeq
        repeat(UNLOCK_SYNC_ATTEMPTS) { index ->
            // 动作/读序号变了（用户又点了锁车/还车/查询）或骑行卡已经换车：这轮补全作废，
            // 别拿旧订单的慢回包覆盖新状态。
            if (seq != snapshotSeq || read != readSeq) return
            if (_state.value.ride?.carNum != carNum) return
            if (_state.value.ride?.hasRideMetrics() == true) return
            if (index > 0) delay(unlockSyncIntervalMs)
            val fresh = try {
                session.queryUnderway()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                null
            } ?: return@repeat
            // 查询期间用户可能已经还车 / 换了动作：慢回包不能把旧骑行卡写回来。
            if (seq != snapshotSeq || read != readSeq) return
            if (_state.value.ride?.carNum != carNum) return
            _state.update { it.withRideSnapshot(fresh) }
            if (fresh.locked == true) return
        }
    }
}

/**
 * 用车动作确认弹窗的规格（标题 / 要点 / 确认键 / 能不能免掉），**两页共用同一份**：
 * 这是用户唯一一次看清「开锁起计费」「还车可能产生调度费」的机会，不许各写一份。
 *
 * 正文是**要点列表**而不是一整段：弹窗里那几件事（计费、核对车号、支付分出路）
 * 各是一条独立责任，糊成一段谁都不看。
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
 * 计费中，两者都没有新增的计费后果，误触代价低于多一次确认的打扰。
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
            // 唯一能让「本机直接开锁」端到端可用的出路是找客服关掉授权
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
    is InterruptedIOException -> "请求超时，请稍后重试"
    is IOException -> "网络不可用，请检查网络后重试"
    else -> "操作异常：${error.message ?: error.javaClass.simpleName}"
}

/**
 * 同车快照缺字段时的**沿用规则**：快趣后端的字段类型不稳定 + 新订单异步落账，
 * 同一辆车的后续快照经常只带车号，`totalDate` / `payMoney` / `currentPercent` /
 * `lockStatus` 说没就没。拿不到新事实，不该把已经知道的事实降级成 `null`。
 *
 * - 时长 / 费用 / 电量：`fresh` 缺就沿用 `previous`；
 * - 锁状态：只在 [carryLock] 为 true 时沿用；悬置待核实期间（开锁未确认 / 锁车确认中）
 *   保持 `fresh` 原文，未知比旧值诚实；
 * - 换了车（carNum 不同）或没有上一份：原样返回 `fresh`。
 *
 * 还车成功时控制器会把 `ride` 清空，所以这里不会把上一单的时长带到下一单。
 */
internal fun carryKnownRideFields(
    previous: KqcxAuth.Ride?,
    fresh: KqcxAuth.Ride,
    carryLock: Boolean,
): KqcxAuth.Ride {
    if (previous == null || previous.carNum != fresh.carNum) return fresh
    return fresh.copy(
        totalDateSeconds = fresh.totalDateSeconds ?: previous.totalDateSeconds,
        payMoneyCents = fresh.payMoneyCents ?: previous.payMoneyCents,
        batteryPercent = fresh.batteryPercent ?: previous.batteryPercent,
        locked = if (carryLock) fresh.locked ?: previous.locked else fresh.locked,
    )
}

/** 骑行卡的核心指标是否到齐：时长 + 电量。后台补全据此决定是否还要再查。 */
internal fun KqcxAuth.Ride.hasRideMetrics(): Boolean =
    totalDateSeconds != null && batteryPercent != null

/**
 * 确认开锁的那一份快照按「已解锁」落位：命令已发且服务端没报「已锁」，
 * 本地就按骑行的自然态显示（与 `retryUnlock` / `resumeRide` 的乐观翻转同一口径）；
 * 报回「已锁」的例外（头盔流程会先后到）照实收，不覆盖。
 */
internal fun KqcxAuth.Ride.adoptConfirmedUnlock(): KqcxAuth.Ride =
    if (locked == true) this else copy(locked = false)

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
    val now by rememberResumedNow(1_000L, key = ride.carNum to fetchedAt)
    return rideElapsedSeconds(ride, fetchedAt, now)?.let(KqcxAuth::formatRideDuration)
}

/** 结算卡的实现在 `RidePanels.kt` 的 `RideSettledBar`；本文件只留数据口径。 */

/**
 * 起免费时长计时并把结果映射成提示文案与语气（点扫一扫 / 本机开锁成功共用）。
 * 起点由调用方给：点「打开微信扫一扫」传 `now + [EbikeFreeRide.SCAN_DELAY_MS]`
 * （切微信、选图、扫码、开车要时间，倒计时从点击 5 秒后才开始走）；本机开锁成功传
 * 当下（服务端确认的开锁时刻，不用缓冲）。
 */
internal suspend fun startFreeRideNotice(
    startAtMillis: Long = System.currentTimeMillis(),
): Pair<String, NoticeTone> {
    val outcome = EbikeFreeRideReminder.startRide(Graph.appContext, startAtMillis)
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
 * 到了微信之后用户为什么"找不到支付入口"：官方的待支付页不挂菜单，
 * 只有两条路能到——**主页的「待支付」横幅**（onShow 查一次、60 秒节流、关过一次冷却 5 分钟）
 * 和**历史订单里的待支付记录**（点进去就是支付页）。提示把这两条写出来，别让用户白进一趟。
 */
internal fun openWechatForSettle(context: android.content.Context, onHint: (String) -> Unit) {
    val launch = try {
        context.packageManager.getLaunchIntentForPackage("com.tencent.mm")
    } catch (e: CancellationException) {
        throw e // 取消穿透
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
    } catch (e: CancellationException) {
        throw e // 取消穿透
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
