package edu.jxslu.schedule.ui.life

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.local.JuwDatabase
import edu.jxslu.schedule.data.local.YktTurnoverEntity
import edu.jxslu.schedule.data.power.PowerException
import edu.jxslu.schedule.data.power.PowerPayChallenge
import edu.jxslu.schedule.data.power.PowerPayModels
import edu.jxslu.schedule.data.power.PowerPayResult
import edu.jxslu.schedule.data.power.PowerModels
import edu.jxslu.schedule.data.power.PowerRepository
import edu.jxslu.schedule.data.power.PowerSnapshot
import edu.jxslu.schedule.data.power.PowerTurnover
import edu.jxslu.schedule.data.ykt.YktCredentialStore
import edu.jxslu.schedule.data.ykt.YktRepository
import edu.jxslu.schedule.data.ykt.YktTurnoverSyncer
import edu.jxslu.schedule.domain.LifeFeed
import edu.jxslu.schedule.domain.LifeFeedItem
import edu.jxslu.schedule.domain.LifeFeedKind
import edu.jxslu.schedule.ui.common.NoticeTone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 生活页状态（DESIGN §3.13）。
 *
 * 电费卡片的数据全在这里；一卡通余额与付款码复用 [edu.jxslu.schedule.ui.campus] 的
 * ViewModel（余额快照、取码、充值、到账判定都在那边，本页只做入口与展示）。
 */
data class PowerCardState(
    val loading: Boolean = false,
    /**
     * 上次成功读数。刷新失败时**保留**它，卡上照旧显示上次的电量与时刻，
     * 另起一行报错——不给一个看不出新旧的数字（DESIGN §3.13）。
     */
    val snapshot: PowerSnapshot? = null,
    val error: String? = null,
    /** 凭证没配置（或已随一卡通关闭被清除）：指引去「我的 → 校园卡」开启。 */
    val noCredentials: Boolean = false,
)

/** 生活页 UI 状态。 */
data class LifeUiState(
    val power: PowerCardState = PowerCardState(),
    val feed: List<LifeFeedItem> = emptyList(),
)

/** 一次性事件。 */
sealed interface LifeEvent {
    data class Notice(val text: String, val tone: NoticeTone) : LifeEvent
}

class LifeViewModel(
    private val powerRepo: PowerRepository,
    private val yktRepo: YktRepository,
    private val credentialStore: YktCredentialStore,
    private val db: JuwDatabase,
) : ViewModel() {

    private val syncer = YktTurnoverSyncer(yktRepo, db)

    private val _power = MutableStateFlow(PowerCardState())

    private val _powerTurnovers = MutableStateFlow<List<PowerTurnover>>(emptyList())

    private val _events = Channel<LifeEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** 本地一卡通流水（Room 响应式，同步后自动刷新）。 */
    private val campusFeed = db.yktTurnoverDao().observeRecent(RECENT_ROOM_ROWS)
        .map { rows -> rows.map { it.toFeedItem() } }

    val uiState: StateFlow<LifeUiState> =
        combine(_power, _powerTurnovers, campusFeed) { power, powerRows, campus ->
            LifeUiState(
                power = power,
                feed = LifeFeed.merge(campus, powerRows.map { it.toFeedItem() }),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LifeUiState())

    /**
     * 进页刷新：电费读数 + 一卡通流水增量同步（互不牵连，各自失败各自提示）。
     *
     * [force] = false 是**进页**那条路：电费走仓库内存缓存，一卡通流水走 10 分钟闸门，
     * 切 Tab 来回不重复打平台（DESIGN §4.24「请求节流」）。
     * 下拉刷新传 true（用户主动要看最新）。
     */
    fun refreshAll(force: Boolean = true) {
        refreshPower(force = force)
        syncTurnovers(force = force)
    }

    /**
     * 电费读数 + 电费流水（点卡片、右上刷新、充值成功后都走它）。
     *
     * [force] = true 时绕过仓库内存缓存并重取；进页那条路传 false。
     */
    fun refreshPower(force: Boolean = true) {
        val credentials = credentialStore.read()
        if (credentials == null) {
            _power.value = PowerCardState(noCredentials = true)
            _powerTurnovers.value = emptyList()
            return
        }
        _power.update { it.copy(loading = true, error = null, noCredentials = false) }
        viewModelScope.launch {
            try {
                val snapshot = powerRepo.snapshot(credentials.username, credentials.password, force = force)
                _power.update { it.copy(loading = false, snapshot = snapshot, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: PowerException.Credential) {
                _power.update {
                    it.copy(loading = false, error = "${e.message}。若密码已改，请在「我的 → 校园卡」重新验证")
                }
            } catch (e: PowerException) {
                _power.update { it.copy(loading = false, error = e.message ?: "电费读取失败") }
            } catch (e: Exception) {
                _power.update { it.copy(loading = false, error = "电费读取失败：${e.message ?: "未知错误"}") }
            }
            // 流水是附加信息：取不到不影响读数，也不额外打扰用户
            runCatching { powerRepo.history(credentials.username, credentials.password, force = force) }
                .onSuccess { _powerTurnovers.value = it }
        }
    }

    /**
     * 一卡通流水增量同步（同步成功即由 Room 流刷新列表）。
     *
     * [force] = false 是**进页**那条路，受 `YktSyncGate` 的 10 分钟闸门管，
     * 切 Tab 来回不会重复拉；用户要看最新就点刷新或下拉（那条路传 true）。
     */
    fun syncTurnovers(force: Boolean = false) {
        val credentials = credentialStore.read() ?: return
        viewModelScope.launch {
            try {
                syncer.sync(credentials.username, credentials.password, maxPages = 1, force = force)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 本地已有流水照常展示，网络问题不打扰（DESIGN §4.19 L3 同款口径）
            }
        }
    }

    /**
     * 「电费充值」：把带登录态的缴费页深链交给调用方打开（一期口径：支付在网页里完成）。
     */
    fun openPowerPayPage(onReady: (String) -> Unit) = openPlatformPage(
        what = "电费充值",
        url = { user, pwd -> powerRepo.payPageUrl(user, pwd) },
        onReady = onReady,
    )

    // ------------------------------------------------------------------
    // 电费充值（DESIGN §4.24：App 内下单 + 安全键盘密码，2026-09-23 打通）
    // ------------------------------------------------------------------

    /** 一次充值流程的状态。 */
    data class PowerRechargeUi(
        val step: Step = Step.Amount,
        /** 下单成功的订单。 */
        val orderId: String? = null,
        /** 密码键盘挑战（paystep=2 的 passwordMap + ccctype）。 */
        val challenge: PowerPayChallenge? = null,
        /** 本次充值金额（元，原样字符串）：密码步展示「本次充值 ¥X」用。 */
        val amountYuan: String? = null,
        /** 已受理之后的查单结果：1 = 平台确认已记账；null = 还没查到 / 查不到。 */
        val paidStatus: Int? = null,
        /**
         * 被服务端拒绝的次数，每次拒绝自增。
         *
         * 给 UI 当「清空已输密码」的信号用。**不能用 error 文案当信号**：两次失败若
         * 服务端给的是同一句话，`error` 前后相等、StateFlow 不发射，密码格就不会清空。
         */
        val rejectedCount: Int = 0,
        val busy: Boolean = false,
        val error: String? = null,
        /**
         * 打开弹层时清掉的未支付订单数（>0 才展示）。
         *
         * 平台不自动清过期单、堆积会让新下单 500，所以每次点开都清一次；清了几笔
         * 直接写在弹层里（这条提示落在弹层这个独立窗口内，Snackbar 会被弹层盖住）。
         */
        val cleanedOrders: Int = 0,
    ) {
        enum class Step { Amount, Password, Accepted }
    }

    private val _powerRecharge = MutableStateFlow(PowerRechargeUi())
    val powerRecharge: StateFlow<PowerRechargeUi> = _powerRecharge.asStateFlow()

    /**
     * 「点开充值弹层」时挂的清理任务（见 [preparePowerRecharge]）。
     * [placePowerOrder] 下单前会 `join()` 它——清理与下单抢跑就白清了：平台侧未支付单
     * 还没删掉，新下单照样回 500「未知异常」。
     */
    private var pendingOrderCleanup: Job? = null

    /**
     * 流程会话号：每次打开弹层（[preparePowerRecharge]）自增。
     *
     * 异步结果回来时若会话已变（用户关掉弹层又重开，或点了两次「电费充值」），整批丢弃——
     * 否则上一轮的结果会写进新一轮界面：最典型的是「支付成功后马上重开弹层，新弹层直接
     * 显示支付成功」。钱相关的流程，状态串台比多写几行判断更糟。
     */
    private var flowSession = 0

    /** 结果是否仍属于当前这一轮流程。 */
    private fun isCurrentSession(session: Int) = session == flowSession

    /**
     * 打开电费充值弹层时调用（DESIGN §4.24）：重置流程状态 + 清一遍未支付订单。
     *
     * 为什么在这里清：未支付单只可能由本流程产生（下单后没付完就退出），而堆积会让
     * 新下单 500。放在这个点，既是用户主动动作，又正好在真正需要之前——生活页刷新
     * 不必再为它多打 1 + N 条请求。失败静默：清不掉不该挡用户看弹层，下单失败时
     * 服务端原话会照实显示。
     */
    fun preparePowerRecharge() {
        flowSession++
        _powerRecharge.value = PowerRechargeUi()
        val credentials = credentialStore.read() ?: return
        pendingOrderCleanup?.cancel()
        pendingOrderCleanup = viewModelScope.launch {
            val cleaned = try {
                powerRepo.cancelAllPendingOrders(credentials.username, credentials.password)
            } catch (e: CancellationException) {
                // 连点两次「电费充值」会取消上一条，取消不算失败——照项目口径重新抛出
                throw e
            } catch (e: Exception) {
                0
            }
            if (cleaned > 0) _powerRecharge.update { it.copy(cleanedOrders = cleaned) }
        }
    }

    /** 下单（金额已由弹层校验过）。 */
    fun placePowerOrder(yuan: String) {
        val credentials = credentialStore.read() ?: run {
            _events.trySend(LifeEvent.Notice("请先在「我的 → 校园卡」开启一卡通", NoticeTone.Warning))
            return
        }
        // 保留 cleanedOrders（那条提示在弹层里，下单时不该被抹掉），其余字段回到干净起点
        val session = flowSession
        _powerRecharge.update {
            it.copy(
                step = PowerRechargeUi.Step.Amount,
                orderId = null,
                challenge = null,
                amountYuan = yuan,
                busy = true,
                error = null,
            )
        }
        viewModelScope.launch {
            // 等弹层打开时那次清理跑完再下单（见 pendingOrderCleanup）
            pendingOrderCleanup?.join()
            if (!isCurrentSession(session)) return@launch
            try {
                val order = powerRepo.createOrder(credentials.username, credentials.password, yuan)
                if (!isCurrentSession(session)) return@launch
                _powerRecharge.value = _powerRecharge.value.copy(orderId = order.orderId, busy = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: PowerException) {
                if (!isCurrentSession(session)) return@launch
                _powerRecharge.value = _powerRecharge.value.copy(busy = false, error = e.message)
            } catch (e: Exception) {
                if (!isCurrentSession(session)) return@launch
                _powerRecharge.value = _powerRecharge.value.copy(
                    busy = false,
                    error = "下单失败：${e.message ?: "未知错误"}",
                )
            }
        }
    }

    /** 拿密码键盘挑战（进入密码步骤时调用）。 */
    fun loadPayChallenge() {
        val credentials = credentialStore.read() ?: return
        val orderId = _powerRecharge.value.orderId ?: return
        val session = flowSession
        _powerRecharge.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val challenge = powerRepo.payChallenge(credentials.username, credentials.password, orderId)
                if (!isCurrentSession(session)) return@launch
                _powerRecharge.value = _powerRecharge.value.copy(
                    step = PowerRechargeUi.Step.Password,
                    challenge = challenge,
                    busy = false,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isCurrentSession(session)) return@launch
                _powerRecharge.value = _powerRecharge.value.copy(
                    busy = false,
                    error = "发起支付失败：${e.message ?: "未知错误"}",
                )
            }
        }
    }

    /**
     * 提交 6 位数字密码：先按 [PowerPayChallenge.cipherOf] 换算成乱序密文再发。
     * **任何失败（密码错/余额不足/网络）都停在密码步并显示服务端原话**——
     * 绝不进入「已受理」（2026-09-23 实机纠错：此前盲报成功）。
     */
    fun submitPowerPassword(digits: String) {
        val credentials = credentialStore.read() ?: return
        val challenge = _powerRecharge.value.challenge
        if (challenge == null) {
            _powerRecharge.update { it.copy(error = "支付会话已失效，请重新下单") }
            return
        }
        val cipher = challenge.cipherOf(digits)
        if (cipher == null) {
            _powerRecharge.update { it.copy(error = "键盘协议异常，请取消后重试") }
            return
        }
        val session = flowSession
        _powerRecharge.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            // orderId 传 VM 手上那个：它是下单时服务端给的，比 challenge 里的可信
            val orderId = _powerRecharge.value.orderId
            val result = try {
                powerRepo.payConfirm(
                    credentials.username,
                    credentials.password,
                    challenge,
                    cipher,
                    orderId = orderId,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 网络类失败先查单：钱可能已经扣了、只是响应没回来。不查就报错，
                // 用户以为没成功又付一次，就是重复扣款。
                val paid = e is PowerException.Network && !orderId.isNullOrBlank() &&
                    powerOrderStatus(credentials.username, credentials.password, orderId) == 1
                if (paid) PowerPayResult.Accepted else PowerPayResult.Rejected(e.message ?: "支付失败")
            }
            if (!isCurrentSession(session)) return@launch
            when (result) {
                is PowerPayResult.Accepted -> {
                    _powerRecharge.update { it.copy(step = PowerRechargeUi.Step.Accepted, busy = false) }
                    refreshPower()
                    // 查一次单把「已受理」升级成「已扣款」；查不到就维持原话（见 confirmPowerPaid）
                    confirmPowerPaid(credentials.username, credentials.password, orderId, session)
                }

                is PowerPayResult.Rejected -> {
                    val message = result.message ?: "支付失败，请重试"
                    if (PowerPayModels.isOrderGone(message)) {
                        // 订单没了（过期 / 已被清）：回金额步重新下单。
                        // 停在密码步让用户反复重输没有意义——单子已经不在服务端了。
                        _powerRecharge.update {
                            it.copy(
                                step = PowerRechargeUi.Step.Amount,
                                orderId = null,
                                challenge = null,
                                paidStatus = null,
                                busy = false,
                                error = "$message（请重新下单）",
                                rejectedCount = it.rejectedCount + 1,
                            )
                        }
                    } else {
                        _powerRecharge.update {
                            it.copy(busy = false, error = message, rejectedCount = it.rejectedCount + 1)
                        }
                    }
                }
            }
        }
    }

    /**
     * 受理后的一次查单（不轮询）：`order.status = 1` 才是平台真把这笔记账了。
     *
     * 只把文案从「已受理」升级成「已扣款」，查不到（网络/平台改版）就保持「已受理」——
     * 这一步是锦上添花，失败不该让用户看到任何异常。
     */
    private fun confirmPowerPaid(username: String, password: String, orderId: String?, session: Int) {
        if (orderId.isNullOrBlank()) return
        viewModelScope.launch {
            delay(PAID_CONFIRM_DELAY_MS)
            if (powerOrderStatus(username, password, orderId) == 1 && isCurrentSession(session)) {
                _powerRecharge.update { it.copy(paidStatus = 1) }
            }
        }
    }

    /** 查单：`status == 1` = 平台已记账。失败按「不知道」返回 null（调用方不做任何推断）。 */
    private suspend fun powerOrderStatus(username: String, password: String, orderId: String): Int? =
        try {
            powerRepo.orderStatus(username, password, orderId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    /** 关闭/取消充值弹层（未支付订单 30 分钟自动失效，DESIGN §4.24）。 */
    fun dismissPowerRecharge() {
        _powerRecharge.value = PowerRechargeUi()
    }

    /** 打开平台页面：先拿深链（顺带保证有 token），失败给一次性提示。 */
    private fun openPlatformPage(
        what: String,
        url: suspend (String, String) -> String,
        onReady: (String) -> Unit,
    ) {
        val credentials = credentialStore.read()
        if (credentials == null) {
            _events.trySend(LifeEvent.Notice("请先在「我的 → 校园卡」开启一卡通，再用${what}", NoticeTone.Warning))
            return
        }
        viewModelScope.launch {
            try {
                onReady(url(credentials.username, credentials.password))
            } catch (e: CancellationException) {
                throw e
            } catch (e: PowerException) {
                _events.send(LifeEvent.Notice(e.message ?: "${what}打不开", NoticeTone.Error))
            } catch (e: Exception) {
                _events.send(LifeEvent.Notice("${what}打不开：${e.message ?: "未知错误"}", NoticeTone.Error))
            }
        }
    }

    private fun YktTurnoverEntity.toFeedItem(): LifeFeedItem {
        val time = jndatetimeStr.take(16)
        val title = if (income) {
            turnoverType.ifBlank { "充值" }
        } else {
            locationName?.takeIf { it.isNotBlank() } ?: turnoverType.ifBlank { "消费" }
        }
        return LifeFeedItem(
            kind = LifeFeedKind.CampusCard,
            epochMs = jndatetime,
            timeText = time,
            title = title,
            subtitle = if (time.isBlank()) "一卡通" else "一卡通 · $time",
            amountFen = tranamtFen,
            income = income,
        )
    }

    private fun PowerTurnover.toFeedItem(): LifeFeedItem {
        val time = dateText.take(16)
        val room = PowerModels.roomLabelOf(room)
        return LifeFeedItem(
            kind = LifeFeedKind.Power,
            epochMs = epochMs,
            timeText = time,
            title = if (refund) "电费退款" else "电费充值",
            subtitle = listOfNotNull(room, time.takeIf { it.isNotBlank() }).joinToString(" · ")
                .ifBlank { "寝室电费" },
            amountFen = amountFen,
            // 方向看 refund（`tranamt` 符号），不看金额大小——金额已统一取绝对值
            income = !refund,
        )
    }

    companion object {
        /** 本地取几条用于混排：多取一条，防止电费与一卡通时间交织时最新一条被截掉。 */
        private const val RECENT_ROOM_ROWS = LifeFeed.DEFAULT_LIMIT + 1

        /**
         * 受理后等多久查一次单。记账是平台侧同步完成的，留一点余量避免查得太早拿到
         * 还没落库的 `status=0`（查到 0 也不报错，只是文案停在「已受理」）。
         */
        private const val PAID_CONFIRM_DELAY_MS = 1_200L

        fun Factory(context: Context) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = LifeViewModel(
                Graph.powerRepository(context.applicationContext),
                Graph.yktRepository(context.applicationContext),
                Graph.yktCredentialStore(context.applicationContext),
                JuwDatabase.get(context.applicationContext),
            ) as T
        }
    }
}
