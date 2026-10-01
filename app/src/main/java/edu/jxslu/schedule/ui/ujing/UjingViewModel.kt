package edu.jxslu.schedule.ui.ujing

import android.app.Activity
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import edu.jxslu.schedule.data.prefs.DisplayPrefsStore
import edu.jxslu.schedule.data.ujing.UjingAlipay
import edu.jxslu.schedule.data.ujing.UjingApiException
import edu.jxslu.schedule.data.ujing.UjingHouse
import edu.jxslu.schedule.data.ujing.UjingHouseStore
import edu.jxslu.schedule.data.ujing.UjingOrderSnapshot
import edu.jxslu.schedule.data.ujing.UjingProgramData
import edu.jxslu.schedule.data.ujing.UjingRepository
import edu.jxslu.schedule.data.ujing.UjingScanResult
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

/** 扫码识别状态：空闲 → 识别中 → 结果（设备 + 套餐）或失败。 */
sealed interface UjingScanState {
    data object Idle : UjingScanState
    data object Parsing : UjingScanState
    data class Ready(
        val scan: UjingScanResult,
        val program: UjingProgramData,
        val badge: UjingState.ScanBadge,
    ) : UjingScanState

    data class Failed(val message: String) : UjingScanState
}

/** U净 页状态（DESIGN §3.23 / §4.37）。P2：登录 / 看板 / 扫码识别 / 下单支付与订单卡。 */
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
    val scan: UjingScanState = UjingScanState.Idle,
    /**
     * 已选水温档（协议枚举 1/2/3/4，见 [UjingState.temperatureLabel]）。
     * null = 当前设备没有水温选项（机型未开放或烘干机）。
     */
    val selectedTemperatureId: Int? = null,
    // ── 订单（P2） ──
    /** 在案订单（下单后出现；跨进程恢复）。null = 没有在案订单。 */
    val order: UjingOrderSnapshot? = null,
    val orderBusy: Boolean = false,
    // ── 下单确认弹层 ──
    val confirmingModel: UjingWashModel? = null,
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
 * P2：在 P1 只读之上加订单链——选模式 → 二次确认 → 下单 → 支付宝 SDK 拉起 →
 * 支付确认 → 订单卡（本地倒计时 + 页面可见时 15 秒轮询）→ 取消 / 云端启动。
 * 会话过期的统一处理在 [handleFailure]：清 token、让登录态流把 UI 带回登录卡，
 * **不做任何自动重登**。写操作（下单 / 取消 / 启动）零自动重试——失败后先查
 * 在案订单，不盲目重发。
 */
class UjingViewModel(
    private val repo: UjingRepository,
    private val houseStore: UjingHouseStore,
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
                } else {
                    UjingDoneReminder.cancel(appContext)
                }
            }
        }
        // 在案订单跨窗口实时反映（快照存 Graph 单例的 OrderStore）
        viewModelScope.launch {
            repo.orderState.collect { order -> _uiState.update { it.copy(order = order) } }
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

    /** 二维码原文（相机或相册）→ 设备信息 + 套餐。 */
    fun onQrScanned(raw: String) {
        if (raw.isBlank() || _uiState.value.scan is UjingScanState.Parsing) return
        _uiState.update { it.copy(scan = UjingScanState.Parsing) }
        viewModelScope.launch {
            runCatching {
                val scan = repo.scanWasher(raw)
                val deviceId = scan.deviceId?.takeIf { it.isNotBlank() }
                    ?: throw UjingApiException("未识别到设备信息，请确认扫的是洗衣机机身码")
                val program = repo.programInfo(deviceId)
                Triple(
                    scan,
                    program,
                    UjingState.scanBadge(scan.createOrderEnabled, scan.status, scan.reason),
                )
            }
                .onSuccess { (scan, program, badge) ->
                    _uiState.update {
                        it.copy(
                            scan = UjingScanState.Ready(scan, program, badge),
                            // 水温默认常温（机型开放水温且非烘干机才给选项）
                            selectedTemperatureId =
                                if (program.isWashTemperatureEnable && program.type != 2) {
                                    TEMPERATURE_DEFAULT_ID
                                } else {
                                    null
                                },
                        )
                    }
                }
                .onFailure { error ->
                    handleFailure(error)
                    _uiState.update {
                        it.copy(scan = UjingScanState.Failed(error.message ?: "识别失败，请重试"))
                    }
                }
        }
    }

    fun clearScan() {
        _uiState.update { it.copy(scan = UjingScanState.Idle, selectedTemperatureId = null) }
    }

    /** 选水温档（仅机型开放水温的洗衣机展示这组 chips）。 */
    fun selectTemperature(id: Int) {
        _uiState.update { it.copy(selectedTemperatureId = id) }
    }

    // ── 订单链（P2） ──

    /** 点模式行 → 弹下单确认（内容在弹层里核对，确认才真正下单）。 */
    fun requestOrder(model: UjingWashModel) {
        val ready = _uiState.value.scan as? UjingScanState.Ready ?: return
        // 已有在案订单：先处理那单，不允许叠单（写操作红线：不批量）
        if (_uiState.value.order != null) {
            notify("已有进行中的订单，请先完成或取消")
            return
        }
        if (!ready.scan.createOrderEnabled) {
            notify("设备当前不可下单")
            return
        }
        _uiState.update { it.copy(confirmingModel = model) }
    }

    fun dismissConfirm() {
        _uiState.update { it.copy(confirmingModel = null) }
    }

    /** 确认下单：orders/create → 立即请求支付参数并拉起支付宝。 */
    fun confirmOrder() {
        val model = _uiState.value.confirmingModel ?: return
        val ready = _uiState.value.scan as? UjingScanState.Ready ?: return
        if (_uiState.value.orderBusy) return
        _uiState.update { it.copy(orderBusy = true, confirmingModel = null) }
        viewModelScope.launch {
            runCatching {
                val snapshot = repo.createOrder(
                    ready.scan,
                    ready.program,
                    model,
                    _uiState.value.selectedTemperatureId,
                )
                // 下单即拉支付参数（2 分钟独占期宝贵，少一次用户等待）
                val orderInfo = repo.paymentOrderInfo(snapshot.orderId)
                snapshot to orderInfo
            }
                .onSuccess { (snapshot, orderInfo) ->
                    _uiState.update { it.copy(orderBusy = false, order = snapshot) }
                    notify("下单成功，请在 2 分钟内完成支付")
                    launchPay(snapshot.orderId, orderInfo)
                }
                .onFailure { error ->
                    handleFailure(error)
                    _uiState.update { it.copy(orderBusy = false) }
                    notify(error.message ?: "下单失败，请稍后重试")
                }
        }
    }

    /** 支付宝 SDK 拉起（后台线程阻塞等结果），结束后回查支付状态。 */
    private fun launchPay(orderId: String, orderInfo: String) {
        val activity = payActivityProvider()
        if (activity == null) {
            notify("无法拉起支付宝，请在订单卡上重试支付")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val (outcome, memo) = UjingAlipay.pay(activity, orderInfo)
            when (outcome) {
                UjingAlipay.Outcome.Success -> {
                    notify("支付成功")
                    runCatching { repo.confirmPayment(orderId) }
                        .onFailure { handleFailure(it) }
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
        if (_uiState.value.orderBusy) return
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

    /** 刷新在案订单（订单卡「刷新」；轮询也走这里）。 */
    fun refreshOrder(orderId: String? = null) {
        val id = orderId ?: _uiState.value.order?.orderId ?: return
        if (_uiState.value.orderBusy) return
        _uiState.update { it.copy(orderBusy = true) }
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
                    notify(error.message ?: "订单状态获取失败")
                }
        }
    }

    /** 取消订单（二次确认在 UI；这里只拦忙态）。 */
    fun cancelOrder() {
        val order = _uiState.value.order ?: return
        if (_uiState.value.orderBusy) return
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
        if (_uiState.value.orderBusy) return
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
        private val prefs: DisplayPrefsStore,
        private val appContext: Context,
        private val payActivityProvider: () -> Activity?,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            UjingViewModel(repo, houseStore, prefs, appContext, payActivityProvider) as T
    }

    private companion object {
        val MOBILE_PATTERN = Regex("^1\\d{10}$")

        /** 水温默认档：常温（协议枚举 1，与 [UjingState.temperatureOptions] 同源）。 */
        const val TEMPERATURE_DEFAULT_ID = 1

        /** 发码冷却（服务端同样有 60 秒限制，两边口径一致）。 */
        const val CODE_COOLDOWN_MILLIS = 60_000L

        /** 使用须知静默期：确认后一周内不再弹。 */
        const val NOTICE_RESHOW_INTERVAL_MILLIS = 7L * 24 * 60 * 60 * 1000

        /** 看板并发上限。 */
        const val BOARD_CONCURRENCY = 4

        /** 订单进行中且页面可见时的轮询间隔（离开页面即停，无后台轮询）。 */
        const val ORDER_POLL_INTERVAL_MILLIS = 15_000L
    }
}
