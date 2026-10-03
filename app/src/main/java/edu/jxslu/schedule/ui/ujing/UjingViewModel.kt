package edu.jxslu.schedule.ui.ujing

import android.app.Activity
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import edu.jxslu.schedule.data.prefs.DisplayPrefsStore
import edu.jxslu.schedule.data.ujing.UjingAlipay
import edu.jxslu.schedule.data.ujing.UjingApiException
import edu.jxslu.schedule.data.ujing.UjingDraftStore
import edu.jxslu.schedule.data.ujing.UjingHistoryStore
import edu.jxslu.schedule.data.ujing.UjingHouse
import edu.jxslu.schedule.data.ujing.UjingHouseStore
import edu.jxslu.schedule.data.ujing.UjingOrderSnapshot
import edu.jxslu.schedule.data.ujing.UjingRepository
import edu.jxslu.schedule.data.ujing.UjingSessionExpiredException
import edu.jxslu.schedule.data.ujing.UjingStore
import edu.jxslu.schedule.data.ujing.UjingWashModel
import edu.jxslu.schedule.domain.UjingState
import edu.jxslu.schedule.ui.reminder.UjingDoneReminder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** 看板一行。[line] 为 null 且 [failed] 为 false 时表示该店没有洗衣机统计。 */
data class UjingBoardRow(
    val house: UjingHouse,
    val line: UjingState.BoardLine?,
    val failed: Boolean,
)

/**
 * 扫码识别状态（主页只管「扫没扫成」）：空闲 → 识别中 → 失败。
 * 成功不留主页——开 [UjingDraftStore] 草稿并跳独立下单页（UJING_ORDER）。
 */
sealed interface UjingScanState {
    data object Idle : UjingScanState
    data object Parsing : UjingScanState
    data class Failed(val message: String) : UjingScanState
}

/** U净 页状态（DESIGN §3.23 / §4.37）。主页：登录 / 看板 / 扫码入口 / 订单卡 / 历史账单入口。 */
data class UjingUiState(
    // ── 登录 ──
    val loggedIn: Boolean = false,
    val mobile: String = "",
    val captcha: String = "",
    val codeSending: Boolean = false,
    val loggingIn: Boolean = false,
    /** 最近一次发送验证码的时刻（0 = 没发过）；60 秒冷却由 UI 按它算。 */
    val codeSentAt: Long = 0L,
    // ── 看板 ──
    val houses: List<UjingHouse> = emptyList(),
    val board: List<UjingBoardRow> = emptyList(),
    val boardRefreshing: Boolean = false,
    // ── 扫码 ──
    /** 主页只展示识别中 / 失败 / 「继续上次识别」；档位与投放选择在独立下单页。 */
    val scan: UjingScanState = UjingScanState.Idle,
    /** 历史账单笔数（入口行角标；0 = 还没有账单）。 */
    val historyCount: Int = 0,
    // ── 订单（P2） ──
    /** 在案订单（下单后出现；跨进程恢复）。null = 没有在案订单。 */
    val order: UjingOrderSnapshot? = null,
    val orderBusy: Boolean = false,
    /**
     * 支付宝 SDK 拉起期间为 true：收银台在那边、SDK 调用阻塞，这段时间订单卡动作
     * （取消/启动/重试支付）不该照常可点。与 [orderBusy] 分开——轮询静默刷新不碰它。
     */
    val paying: Boolean = false,
    // ── 附近洗衣房弹层 ──
    val pickerOpen: Boolean = false,
    val pickerLoading: Boolean = false,
    val pickerStores: List<UjingStore> = emptyList(),
    // ── 使用须知 ──
    val noticeVisible: Boolean = false,
)

/**
 * U净 页状态（DESIGN §4.37）。
 *
 * 主页只管：登录、看板、扫码入口（识别成功即开草稿并跳独立下单页）、在案订单卡与
 * 历史账单入口。档位 / 水温 / 投放的选择与确认在 UJING_ORDER 页，选择结果经
 * [UjingDraftStore.submit] 交回这里执行——**写操作仍由主页单点发起**（下单 → 支付宝
 * SDK 拉起 → 支付确认），不因窗口切换换执行者。
 *
 * 会话过期的统一处理在 [handleFailure]：清 token、让登录态流把 UI 带回登录卡，
 * **不做任何自动重登**。写操作（下单 / 取消 / 启动）零自动重试——失败后先查
 * 在案订单，不盲目重发。
 */
class UjingViewModel(
    private val repo: UjingRepository,
    private val houseStore: UjingHouseStore,
    private val historyStore: UjingHistoryStore,
    private val draftStore: UjingDraftStore,
    private val prefs: DisplayPrefsStore,
    /** 应用上下文：排 / 撤洗衣完成提醒的闹钟用（提醒不依赖任何窗口存活）。 */
    private val appContext: Context,
    /** 拉起支付宝的活动来源（支付只能在 Activity 上发起）。 */
    private val payActivityProvider: () -> Activity?,
) : ViewModel() {

    private val _uiState = MutableStateFlow(UjingUiState())
    val uiState: StateFlow<UjingUiState> = _uiState.asStateFlow()

    /** 一次性提示（Snackbar）。 */
    private val _events = Channel<String>(Channel.BUFFERED)
    val events: Flow<String> = _events.receiveAsFlow()

    /** 一次性导航：扫码识别成功 → 跳独立下单页。 */
    private val _openOrder = Channel<Unit>(Channel.BUFFERED)
    val openOrder: Flow<Unit> = _openOrder.receiveAsFlow()

    /**
     * 「今日页扫到机身码，但当时还没登录」留下的原文：登录成功后自动接着识别
     * （见 `repo.loggedIn` 收集器）。只放内存、只留一次，超 [PENDING_SCAN_TTL_MILLIS] 作废
     * —— 与草稿（[UjingDraftStore]）同一条口径：几秒到几分钟内的一次性交接，不落盘。
     */
    private var pendingScan: Pair<String, Long>? = null

    init {
        _uiState.update {
            it.copy(
                loggedIn = repo.loggedIn.value,
                mobile = repo.lastMobile(),
                houses = houseStore.list(),
                order = repo.currentOrder(),
            )
        }
        // 登录态跨窗口实时反映（对齐趣智 / 快趣的口径）。任何 false→true 的转变
        // （本页登录、别的窗口重登、会话恢复）都顺带补一次数据：
        // 在案订单对一次服务端、看板重拉——UI 不用再各写一遍。
        viewModelScope.launch {
            repo.loggedIn.collect { loggedIn ->
                _uiState.update {
                    it.copy(
                        loggedIn = loggedIn,
                        board = if (loggedIn) it.board else emptyList(),
                    )
                }
                if (loggedIn) {
                    repo.currentOrder()?.takeIf { order -> !order.isTerminal }
                        ?.let { order -> refreshOrder(order.orderId) }
                    refreshBoard()
                    // 扫到机身码时还没登录：登录成功后接着把那次识别跑完，用户不用重扫
                    pendingScan?.let { (raw, at) ->
                        pendingScan = null
                        if (System.currentTimeMillis() - at <= PENDING_SCAN_TTL_MILLIS) {
                            onQrScanned(raw)
                        }
                    }
                } else {
                    UjingDoneReminder.cancel(appContext)
                }
            }
        }
        // 在案订单跨窗口实时反映（快照存 Graph 单例的 OrderStore）
        viewModelScope.launch {
            repo.orderState.collect { order -> _uiState.update { it.copy(order = order) } }
        }
        // 历史账单笔数（入口行角标）
        viewModelScope.launch {
            historyStore.state.collect { list ->
                _uiState.update { it.copy(historyCount = list.size) }
            }
        }
        // 下单页交回的选择 → 立即执行下单链（确认动作已在下单页的弹层里完成）
        viewModelScope.launch {
            draftStore.submitted.collect { selection ->
                if (selection != null) {
                    draftStore.consumeSubmitted()
                    confirmPreparedOrder(selection)
                }
            }
        }
        // 恢复在案订单 / 进页拉看板不再单独写：上面的 loggedIn 收集器订阅 StateFlow
        // 会立即发射当前值，已登录时的订单对表与看板首拉都从那里走（单一来源）。
        // 使用须知：首次（或距上次确认超过一周）进页弹一次
        viewModelScope.launch {
            val seenAt = prefs.ujingNoticeSeenAt.first()
            val stale = System.currentTimeMillis() - seenAt > NOTICE_RESHOW_INTERVAL_MILLIS
            if (seenAt <= 0L || stale) {
                _uiState.update { it.copy(noticeVisible = true) }
            }
        }
    }

    // ── 登录 ──

    fun onMobileChange(value: String) {
        _uiState.update { it.copy(mobile = value.filter(Char::isDigit).take(11)) }
    }

    fun onCaptchaChange(value: String) {
        _uiState.update { it.copy(captcha = value.filter(Char::isDigit).take(8)) }
    }

    fun sendCode() {
        val state = _uiState.value
        if (!MOBILE_PATTERN.matches(state.mobile)) {
            notify("请输入 11 位手机号")
            return
        }
        if (state.codeSending) return
        // 60 秒冷却：UI 按钮已按 codeSentAt 置灰，这里再兜一道边界
        if (state.codeSentAt > 0 && System.currentTimeMillis() - state.codeSentAt < CODE_COOLDOWN_MILLIS) {
            return
        }
        _uiState.update { it.copy(codeSending = true) }
        viewModelScope.launch {
            runCatching { repo.requestCaptcha(state.mobile) }
                .onSuccess {
                    _uiState.update {
                        it.copy(codeSending = false, codeSentAt = System.currentTimeMillis())
                    }
                    notify("验证码已发送")
                }
                .onFailure { error ->
                    _uiState.update { it.copy(codeSending = false) }
                    notify(error.message ?: "验证码发送失败，请稍后再试")
                }
        }
    }

    fun login() {
        val state = _uiState.value
        if (!MOBILE_PATTERN.matches(state.mobile)) {
            notify("请输入 11 位手机号")
            return
        }
        if (state.captcha.isBlank()) {
            notify("请输入短信验证码")
            return
        }
        if (state.loggingIn) return
        _uiState.update { it.copy(loggingIn = true) }
        viewModelScope.launch {
            runCatching { repo.login(state.mobile, state.captcha) }
                .onSuccess {
                    // 看板重拉与订单对表由 loggedIn 收集器统一接（登录态一变就发）
                    _uiState.update { it.copy(loggingIn = false, captcha = "") }
                    notify("登录成功")
                }
                .onFailure { error ->
                    _uiState.update { it.copy(loggingIn = false) }
                    notify(error.message ?: "登录失败，请检查验证码")
                }
        }
    }

    fun logout() {
        repo.logout()
        UjingDoneReminder.cancel(appContext)
        notify("已退出登录")
    }

    // ── 空闲看板 ──

    /**
     * 拉取全部收藏店的空闲数据（并发，上限 [BOARD_CONCURRENCY] 收在 OkHttp 连接池
     * 可承受的范围）。单店失败只让那一行显示失败，不打断整板。
     */
    fun refreshBoard() {
        val houses = houseStore.list()
        _uiState.update { it.copy(houses = houses) }
        if (houses.isEmpty()) {
            _uiState.update { it.copy(board = emptyList(), boardRefreshing = false) }
            return
        }
        _uiState.update { it.copy(boardRefreshing = true) }
        viewModelScope.launch {
            val limiter = Semaphore(BOARD_CONCURRENCY)
            val rows = coroutineScope {
                houses.map { house ->
                    async {
                        runCatching { limiter.withPermit { repo.devicesReserve(house.storeId) } }
                            .fold(
                                onSuccess = { groups ->
                                    UjingBoardRow(house, repo.boardLine(groups), failed = false)
                                },
                                onFailure = { error ->
                                    handleFailure(error)
                                    UjingBoardRow(house, line = null, failed = true)
                                },
                            )
                    }
                }.awaitAll()
            }
            _uiState.update { it.copy(board = rows, boardRefreshing = false) }
        }
    }

    fun removeHouse(house: UjingHouse) {
        houseStore.remove(house.storeId)
        _uiState.update { it.copy(houses = houseStore.list()) }
        refreshBoard()
    }

    // ── 附近洗衣房（配置收藏） ──

    /** 打开弹层进入加载态（定位由页面取好后调 [loadNearby] 填充）。 */
    fun openPicker() {
        _uiState.update {
            it.copy(pickerOpen = true, pickerLoading = true, pickerStores = emptyList())
        }
    }

    fun loadNearby(lat: Double, lng: Double) {
        viewModelScope.launch {
            runCatching { repo.nearbyStores(lat, lng) }
                .onSuccess { stores ->
                    _uiState.update { it.copy(pickerLoading = false, pickerStores = stores) }
                }
                .onFailure { error ->
                    handleFailure(error)
                    _uiState.update { it.copy(pickerLoading = false) }
                    notify(error.message ?: "附近洗衣房获取失败，请稍后再试")
                }
        }
    }

    fun closePicker() {
        _uiState.update { it.copy(pickerOpen = false) }
    }

    fun addHouse(store: UjingStore) {
        val id = store.id ?: return
        houseStore.add(UjingHouse(storeId = id, name = store.name))
        _uiState.update { it.copy(houses = houseStore.list()) }
        notify("已添加「${store.name}」")
        refreshBoard()
    }

    // ── 扫码识别 ──

    /**
     * 二维码原文（相机或相册）→ 设备信息 + 套餐。成功即开草稿并请 UI 跳独立下单页
     * （档位 / 水温 / 投放的选择都在那一页，主页不内联展示）。
     */
    fun onQrScanned(raw: String) {
        if (raw.isBlank() || _uiState.value.scan is UjingScanState.Parsing) return
        // 未登录先拦一道：机身码的识别接口要 token（匿名回 401），直接发出去只会得到
        // 「登录已过期」这种驴唇不对马嘴的提示——今日页的扫一扫把它带进来时用户
        // 可能压根没用过 U净，让人回页面上登录才是出路
        if (!repo.loggedIn.value) {
            // 留着原文：登录成功后自动接着识别（见 init 里的 loggedIn 收集器）
            pendingScan = raw to System.currentTimeMillis()
            notify("识别洗衣机需要先登录 U净（手机号 + 短信验证码）")
            return
        }
        pendingScan = null
        _uiState.update { it.copy(scan = UjingScanState.Parsing) }
        viewModelScope.launch {
            runCatching {
                val scan = repo.scanWasher(raw)
                val deviceId = scan.deviceId?.takeIf { it.isNotBlank() }
                    ?: throw UjingApiException("未识别到设备信息，请确认扫的是洗衣机机身码")
                val program = repo.programInfo(deviceId)
                scan to program
            }
                .onSuccess { (scan, program) ->
                    draftStore.open(UjingDraftStore.Draft(scan, program))
                    _uiState.update { it.copy(scan = UjingScanState.Idle) }
                    _openOrder.trySend(Unit)
                }
                .onFailure { error ->
                    handleFailure(error)
                    _uiState.update {
                        it.copy(scan = UjingScanState.Failed(error.message ?: "识别失败，请重试"))
                    }
                }
        }
    }

    // ── 订单链（P2） ──

    /**
     * 订单链的统一忙态：请求在跑（[UjingUiState.orderBusy]）**或支付宝 SDK 阻塞中**
     * （[UjingUiState.paying]）。所有写入口的守卫都看它——paying 只禁了 UI 按钮、
     * 守卫不认的话，草稿收集器这类非按钮调用点会在支付窗口内绕过禁用发起写操作。
     */
    private val orderChainBusy: Boolean
        get() = _uiState.value.orderBusy || _uiState.value.paying

    /**
     * 下单页交回选择后的执行入口（二次确认弹层在下单页里，用户点「去支付」才走到这）。
     * 守卫与旧主页弹层路径一致：在案订单不允许叠单（写操作红线）、忙态拒发。
     */
    fun confirmPreparedOrder(selection: UjingDraftStore.OrderSelection) {
        if (orderChainBusy) return
        if (_uiState.value.order != null) {
            notify("已有进行中的订单，请先完成或取消")
            return
        }
        createAndPay(
            model = selection.model,
            washTemperatureId = selection.temperatureId,
            addDetergent = selection.addDetergent,
            addDisinfectant = selection.addDisinfectant,
        )
    }

    /** orders/create → 立即请求支付参数并拉起支付宝。 */
    private fun createAndPay(
        model: UjingWashModel,
        washTemperatureId: Int?,
        addDetergent: Boolean,
        addDisinfectant: Boolean,
    ) {
        val draft = draftStore.draft.value ?: return
        _uiState.update { it.copy(orderBusy = true) }
        viewModelScope.launch {
            // 下单与取支付参数分两段 try：createOrder 受理成功后订单已建、机器已锁
            // 2 分钟、快照与账单已落（repo.createOrder 内部），此时取参数再失败绝不能
            // 报「下单失败」诱导重发——把订单交给订单卡，让用户从「重试支付」续上。
            val created = runCatching {
                repo.createOrder(
                    draft.scan,
                    draft.program,
                    model,
                    washTemperatureId,
                    addDetergent,
                    addDisinfectant,
                )
            }
            val snapshot = created.getOrElse { error ->
                handleFailure(error)
                _uiState.update { it.copy(orderBusy = false) }
                notify(error.message ?: "下单失败，请稍后重试")
                return@launch
            }
            val paid = runCatching {
                // 下单即拉支付参数（2 分钟独占期宝贵，少一次用户等待）
                repo.paymentOrderInfo(snapshot.orderId)
            }
            paid.fold(
                onSuccess = { orderInfo ->
                    _uiState.update { it.copy(orderBusy = false, order = snapshot) }
                    draftStore.close()
                    notify("下单成功，请在 2 分钟内完成支付")
                    launchPay(snapshot.orderId, orderInfo)
                },
                onFailure = { error ->
                    handleFailure(error)
                    _uiState.update { it.copy(orderBusy = false, order = snapshot) }
                    draftStore.close()
                    notify(error.message ?: "下单成功，但支付参数获取失败，可在订单卡重试支付")
                },
            )
        }
    }

    /** 支付宝 SDK 拉起（后台线程阻塞等结果），结束后回查支付状态。 */
    private fun launchPay(orderId: String, orderInfo: String) {
        val activity = payActivityProvider()
        if (activity == null) {
            notify("无法拉起支付宝，请在订单卡上重试支付")
            return
        }
        // paying 态：SDK 阻塞在支付宝界面/收银台的整个窗口里，订单卡动作不该照常可点
        // （此前 busy 在拉起前就复位，支付窗口内「取消/启动」全程可点，点取消会和支付结果赛跑）
        _uiState.update { it.copy(paying = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val (outcome, memo) = UjingAlipay.pay(activity, orderInfo)
            _uiState.update { it.copy(paying = false) }
            when (outcome) {
                UjingAlipay.Outcome.Success -> {
                    notify("支付成功")
                    runCatching { repo.confirmPayment(orderId) }
                        .onFailure { handleFailure(it) }
                }
                UjingAlipay.Outcome.Pending -> {
                    // 8000 = 结果确认中：钱可能已扣，按失败报会诱导用户重付造成重复扣款。
                    // 先双查订单状态（confirmPayment = lastPayStatus + detail），以服务端为准。
                    val settled = runCatching { repo.confirmPayment(orderId) }
                    settled.fold(
                        onSuccess = { snapshot ->
                            if (snapshot.paid) notify("支付成功")
                            else notify("支付确认中，请稍后在订单卡刷新查看")
                        },
                        onFailure = { error ->
                            handleFailure(error)
                            // 双查也失败：快照停在 paid=false，订单卡停在「去支付」且没有
                            // 自动恢复（轮询只在页面可见时跑）——补一次对表兜底
                            runCatching { repo.refreshOrder(orderId) }.onFailure { handleFailure(it) }
                            notify("支付确认中，请稍后在订单卡刷新查看")
                        },
                    )
                }
                UjingAlipay.Outcome.Canceled -> {
                    notify("已取消支付，订单保留 2 分钟，可在订单卡重试")
                    runCatching { repo.refreshOrder(orderId) }.onFailure { handleFailure(it) }
                }
                UjingAlipay.Outcome.Failed -> {
                    notify("支付失败（$memo），可在订单卡重试")
                    runCatching { repo.refreshOrder(orderId) }.onFailure { handleFailure(it) }
                }
            }
        }
    }

    /** 订单卡「重试支付」：重新取支付参数并拉起。 */
    fun retryPay() {
        val order = _uiState.value.order ?: return
        if (orderChainBusy) return
        _uiState.update { it.copy(orderBusy = true) }
        viewModelScope.launch {
            runCatching { repo.paymentOrderInfo(order.orderId) }
                .onSuccess { orderInfo ->
                    _uiState.update { it.copy(orderBusy = false) }
                    launchPay(order.orderId, orderInfo)
                }
                .onFailure { error ->
                    handleFailure(error)
                    _uiState.update { it.copy(orderBusy = false) }
                    notify(error.message ?: "支付参数获取失败")
                }
        }
    }

    /**
     * 刷新在案订单（订单卡「刷新」= [silent] = false 置 busy；**15 秒轮询走 silent**，
     * 不置 busy——不然后台轮询每 15 秒把「去支付/启动」闪一次加载圈、周期性禁用）。
     */
    fun refreshOrder(orderId: String? = null, silent: Boolean = false) {
        val id = orderId ?: _uiState.value.order?.orderId ?: return
        if (orderChainBusy) return
        if (!silent) _uiState.update { it.copy(orderBusy = true) }
        viewModelScope.launch {
            runCatching { repo.refreshOrder(id) }
                .onSuccess { snapshot ->
                    _uiState.update { it.copy(orderBusy = false) }
                    // 洗衣完成提醒：
                    // - 洗涤中 → 按「快照时刻 + 剩余」排精确闹钟（每次刷新校准落点）；
                    // - 已支付未运行（20/21/22/35）→ 按「快照时刻 + 模式总时长」排兜底闹钟：
                    //   用户付完/点完启动就可能离开页面，21→40 的转变没人看着；兜底闹钟
                    //   常常早于真实结束，到点 check 会按最新剩余重排，早响比不响好；
                    // - 终结态 → 就地撤销（完成那条由闹钟落点的 check 自己发）。
                    when {
                        snapshot.isTerminal -> UjingDoneReminder.cancel(appContext)
                        snapshot.status == "40" && snapshot.remainSeconds > 0 ->
                            UjingDoneReminder.schedule(
                                appContext,
                                snapshot.orderId,
                                snapshot.snapshotAt + snapshot.remainSeconds * 1000L,
                            )
                        snapshot.paid && snapshot.durationSeconds > 0 ->
                            UjingDoneReminder.schedule(
                                appContext,
                                snapshot.orderId,
                                snapshot.snapshotAt + snapshot.durationSeconds * 1000L,
                            )
                    }
                }
                .onFailure { error ->
                    handleFailure(error)
                    _uiState.update { it.copy(orderBusy = false) }
                    // 轮询失败静默（单次失败不值得打扰，下一轮 15 秒就重试）；手动刷新才报错
                    if (!silent) notify(error.message ?: "订单状态获取失败")
                }
        }
    }

    /** 取消订单（二次确认在 UI；这里只拦忙态）。 */
    fun cancelOrder() {
        val order = _uiState.value.order ?: return
        if (orderChainBusy) return
        if (!UjingState.canCancel(order.status)) {
            notify("当前状态不可取消（机器已锁定，等它跑完即可）")
            return
        }
        _uiState.update { it.copy(orderBusy = true) }
        viewModelScope.launch {
            runCatching { repo.cancelOrder(order.orderId) }
                .onSuccess {
                    _uiState.update { it.copy(orderBusy = false) }
                    UjingDoneReminder.cancel(appContext)
                    notify("订单已取消，机器已释放")
                }
                .onFailure { error ->
                    handleFailure(error)
                    _uiState.update { it.copy(orderBusy = false) }
                    notify(error.message ?: "取消失败，请刷新订单状态后重试")
                }
        }
    }

    /** 云端启动（订单窗口内可点；蓝牙机型由 UI 提示靠近机器后再按）。 */
    fun startOrder() {
        val order = _uiState.value.order ?: return
        if (orderChainBusy) return
        if (!UjingState.canStart(order.status)) {
            notify("当前状态不可启动")
            return
        }
        _uiState.update { it.copy(orderBusy = true) }
        viewModelScope.launch {
            runCatching { repo.startOrder(order.orderId) }
                .onSuccess {
                    _uiState.update { it.copy(orderBusy = false) }
                    notify("启动指令已受理")
                    refreshOrder(order.orderId)
                }
                .onFailure { error ->
                    handleFailure(error)
                    _uiState.update { it.copy(orderBusy = false) }
                    notify(error.message ?: "启动失败，请刷新订单状态后重试")
                }
        }
    }

    /** 终结态订单卡上的「知道了」：清掉在案快照。 */
    fun dismissOrder() {
        repo.dismissOrder()
        UjingDoneReminder.cancel(appContext)
        _uiState.update { it.copy(order = null) }
    }

    // ── 使用须知 ──

    fun onNoticeConfirmed() {
        _uiState.update { it.copy(noticeVisible = false) }
        viewModelScope.launch { prefs.setUjingNoticeSeenAt(System.currentTimeMillis()) }
    }

    // ── 内部 ──

    private fun handleFailure(error: Throwable) {
        if (error is UjingSessionExpiredException) {
            repo.markExpired()
            notify("登录已过期，请重新登录")
        }
    }

    private fun notify(message: String) {
        _events.trySend(message)
    }

    class Factory(
        private val repo: UjingRepository,
        private val houseStore: UjingHouseStore,
        private val historyStore: UjingHistoryStore,
        private val draftStore: UjingDraftStore,
        private val prefs: DisplayPrefsStore,
        private val appContext: Context,
        private val payActivityProvider: () -> Activity?,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            UjingViewModel(
                repo,
                houseStore,
                historyStore,
                draftStore,
                prefs,
                appContext,
                payActivityProvider,
            ) as T
    }

    private companion object {
        val MOBILE_PATTERN = Regex("^1\\d{10}$")

        /** 发码冷却（服务端同样有 60 秒限制，两边口径一致）。 */
        const val CODE_COOLDOWN_MILLIS = 60_000L

        /** 使用须知静默期：确认后一周内不再弹。 */
        const val NOTICE_RESHOW_INTERVAL_MILLIS = 7L * 24 * 60 * 60 * 1000

        /**
         * 「扫到机身码时还没登录」留住的原文有效期：超过就作废。
         * 登录流程正常在几分钟内走完；留太久的话，用户在别处登录也会被推进一次识别。
         */
        const val PENDING_SCAN_TTL_MILLIS = 10L * 60 * 1000

        /** 看板并发上限。 */
        const val BOARD_CONCURRENCY = 4

        /** 订单进行中且页面可见时的轮询间隔（离开页面即停，无后台轮询）。 */
        const val ORDER_POLL_INTERVAL_MILLIS = 15_000L
    }
}
