package edu.jxslu.schedule.ui.water

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import edu.jxslu.schedule.data.qiekj.BalanceData
import edu.jxslu.schedule.data.qiekj.DeviceItem
import edu.jxslu.schedule.data.qiekj.OrderHistoryItem
import edu.jxslu.schedule.data.qiekj.QiekjRepository
import edu.jxslu.schedule.data.qiekj.TokenExpiredException
import edu.jxslu.schedule.data.qiekj.UnlockException
import edu.jxslu.schedule.domain.UnlockFlowState
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.ui.common.NoticeTone
import edu.jxslu.schedule.ui.widget.WaterWidgetSync
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

data class WaterUiState(
    // ── 登录 ──
    val loggedIn: Boolean = false,
    val phone: String = "",
    val code: String = "",
    val phoneError: String? = null,
    val sendingCode: Boolean = false,
    val loggingIn: Boolean = false,
    val showTokenLogin: Boolean = false,
    val tokenLoginInput: String = "",
    val tokenLoggingIn: Boolean = false,
    // ── 资产 / 设备 ──
    val balance: BalanceData? = null,
    val devices: List<DeviceItem> = emptyList(),
    val selectedDevice: DeviceItem? = null,
    val loadingDevices: Boolean = false,
    val loadingBalance: Boolean = false,
    /** 余额是否至少拉过一次（成功失败都算）：卡片副行区分「读取中」与「拉过但没有」用。 */
    val balanceLoaded: Boolean = false,
    /** 下拉刷新进行中（余额 + 设备两轮都算）；进页首载不算，别拿它当 loading 用。 */
    val refreshing: Boolean = false,
    /**
     * 账户手机号：取自仓库记住的登录手机号（短信登录成功时落盘）。Token 登录不更新它，
     * 沿用记住的值；没有就不显示。与登录输入框的 [phone] 是两回事——那个值用户随时在改。
     */
    val accountPhone: String = "",
    val showPhone: Boolean = false,
    // ── 开水流程 ──
    val flow: UnlockFlowState = UnlockFlowState.Idle,
    val usePoints: Boolean = true,
    // ── 订单 ──
    val orderHistory: List<OrderHistoryItem> = emptyList(),
)

sealed interface WaterEvent {
    /** 一次性结果提示（登录/验证码/出水超时/查询失败…）；[tone] 决定页面提示卡的语气。 */
    data class Notice(val text: String, val tone: NoticeTone) : WaterEvent
}

/**
 * 胖乖生活页状态（DESIGN §4.10）。
 *
 * 开水流程生命周期与参考实现对齐：
 * - Mutex 防重入，进行中再点直接忽略；
 * - Working 阶段计时；165s（AUTO_SETTLE_SECONDS）仍在 Working → 视为服务端超时自动关阀，
 *   状态归 Idle 并刷新余额（此时轮询协程仍在后台收尾，完成后 Success 会重新落卡）；
 * - TokenExpiredException 统一清登录态并弹 Toast，不进 Failed 状态机。
 */
class WaterViewModel(private val repo: QiekjRepository) : ViewModel() {

    private val unlockMutex = Mutex()
    private var unlockJob: Job? = null
    private var timerJob: Job? = null
    private var timeoutJob: Job? = null
    private var codeSentAt = 0L

    private val _uiState = MutableStateFlow(
        WaterUiState(
            // 初始值取自仓库那条流，[syncLoginState] 的相等判断才有意义（两处口径一致）
            loggedIn = repo.loggedIn.value,
            phone = repo.readPhone() ?: "",
            accountPhone = repo.readPhone().orEmpty(),
            orderHistory = if (repo.loggedIn.value) repo.orderHistory() else emptyList(),
        ),
    )
    val uiState: StateFlow<WaterUiState> = _uiState.asStateFlow()

    private val _events = Channel<WaterEvent>(Channel.BUFFERED)

    /**
     * 一次性提示事件。页面（开水页 / 今日页开水卡）负责收集并展示——
     * 两边都必须有收集者，否则事件会留在缓冲里直到下一次有人收，
     * 或随 ViewModel 一起消失（今日页此前就不收，出水超时与登录失效在那里是静默的）。
     */
    val events = _events.receiveAsFlow()

    /** 提示（默认中性语气）。失败路径一律给 [NoticeTone.Error]，见各调用点。 */
    private fun notice(text: String, tone: NoticeTone = NoticeTone.Info) =
        _events.trySend(WaterEvent.Notice(text, tone))

    init {
        // 登录态以仓库的 [QiekjRepository.loggedIn] 为准：今日页开水卡与开水页各持一份
        // WaterViewModel，本 VM 自己登录/退出会就地改 uiState，另一份只能从这条流得知。
        viewModelScope.launch {
            repo.loggedIn.collect { syncLoginState(it) }
        }
        if (_uiState.value.loggedIn) {
            refreshBalance()
            refreshDevices()
        }
    }

    /**
     * 登录态翻转的统一落点。相等直接跳过——本 VM 自己走的登录/退出路径已经就地改过
     * uiState，不设这道闸会把余额与设备各多发一轮。
     */
    private fun syncLoginState(loggedIn: Boolean) {
        if (loggedIn == _uiState.value.loggedIn) return
        if (loggedIn) {
            _uiState.update {
                it.copy(loggedIn = true, accountPhone = repo.readPhone().orEmpty(), orderHistory = repo.orderHistory())
            }
            refreshBalance()
            refreshDevices()
        } else {
            cancelFlowJobs()
            _uiState.update { WaterUiState() }
            // 桌面胖乖开水卡（DESIGN §3.6「开水两卡」）跟着切回未登录形态；
            // 登录方向的推送由 refreshBalance 成功接管，这里不重复取数
            viewModelScope.launch {
                runCatching { WaterWidgetSync.refreshQiekjWidget(Graph.appContext) }
            }
        }
    }

    // ── 登录 ──

    fun updatePhone(value: String) = _uiState.update {
        val digits = value.filter(Char::isDigit).take(11)
        it.copy(phone = digits, phoneError = if (digits.isNotEmpty() && digits.length < 11) "请输入 11 位手机号" else null)
    }

    fun updateCode(value: String) = _uiState.update { it.copy(code = value.filter(Char::isDigit).take(6)) }

    fun toggleTokenLogin() = _uiState.update {
        it.copy(showTokenLogin = !it.showTokenLogin, tokenLoginInput = "")
    }

    fun updateTokenLoginInput(value: String) = _uiState.update { it.copy(tokenLoginInput = value) }

    fun sendCode() = viewModelScope.launch {
        val phone = _uiState.value.phone.trim()
        val elapsed = System.currentTimeMillis() - codeSentAt
        if (elapsed < 60_000) {
            notice("验证码已发送，请 ${(60 - elapsed / 1000).toInt()} 秒后再试", NoticeTone.Warning)
            return@launch
        }
        if (phone.length != 11) {
            _uiState.update { it.copy(phoneError = "请输入 11 位手机号") }
            return@launch
        }
        runCatching {
            _uiState.update { it.copy(sendingCode = true) }
            repo.sendCode(phone)
        }.onSuccess {
            codeSentAt = System.currentTimeMillis()
            notice("验证码已发送", NoticeTone.Success)
        }.onFailure {
            notice(it.message ?: "验证码发送失败", NoticeTone.Error)
        }
        _uiState.update { it.copy(sendingCode = false) }
    }

    fun login() = viewModelScope.launch {
        val s = _uiState.value
        if (s.phone.length != 11) {
            _uiState.update { it.copy(phoneError = "请输入 11 位手机号") }
            return@launch
        }
        if (s.code.isBlank()) {
            notice("请输入验证码", NoticeTone.Warning)
            return@launch
        }
        runCatching {
            _uiState.update { it.copy(loggingIn = true) }
            repo.login(s.phone, s.code)
        }.onSuccess {
            repo.savePhone(s.phone)
            // syncLoginState(true) 可能在 savePhone 前就被流翻转触发、读到旧手机号，这里以刚登录的为准
            _uiState.update { it.copy(accountPhone = s.phone) }
            onLoginSuccess("登录成功")
        }.onFailure {
            _uiState.update { it.copy(loggingIn = false) }
            notice(it.message ?: "登录失败", NoticeTone.Error)
        }
    }

    fun loginWithToken() = viewModelScope.launch {
        val token = _uiState.value.tokenLoginInput.trim()
        if (token.isBlank()) {
            notice("请输入 Token", NoticeTone.Warning)
            return@launch
        }
        runCatching {
            _uiState.update { it.copy(tokenLoggingIn = true) }
            // 先验后存：存了就会翻转共享登录态（今日页开水卡立刻切形态），验失败再回滚是可见闪跳
            repo.validateToken(token)
            repo.saveToken(token)
        }.onSuccess {
            onLoginSuccess("登录成功")
        }.onFailure {
            repo.logout()
            _uiState.update { it.copy(tokenLoggingIn = false) }
            notice(it.message ?: "Token 无效或已过期", NoticeTone.Error)
        }
    }

    /**
     * 登录成功的收尾（清表单 + 提示）。**不在这里改 `loggedIn`、也不拉余额/设备**：
     * token 一落盘 [syncLoginState] 就接手了，两处都做会让每次登录多发两轮请求。
     */
    private fun onLoginSuccess(message: String) {
        _uiState.update {
            it.copy(
                loggingIn = false,
                tokenLoggingIn = false,
                showTokenLogin = false,
                tokenLoginInput = "",
                code = "",
                phoneError = null,
            )
        }
        notice(message, NoticeTone.Success)
    }

    fun logout() {
        cancelFlowJobs()
        repo.logout()
        _uiState.update { WaterUiState() }
        notice("已退出胖乖登录")
    }

    private fun handleTokenExpired() {
        cancelFlowJobs()
        repo.logout()
        _uiState.update { WaterUiState() }
        notice("登录已失效，请重新登录", NoticeTone.Warning)
    }

    // ── 资产 / 设备 ──

    fun refreshBalance() = viewModelScope.launch {
        if (!_uiState.value.loggedIn) return@launch
        runCatching {
            _uiState.update { it.copy(loadingBalance = true) }
            repo.queryBalance()
        }.onSuccess { balance ->
            _uiState.update { it.copy(balance = balance, loadingBalance = false, balanceLoaded = true) }
            // 桌面胖乖开水卡（DESIGN §3.6「开水两卡」）：余额已经在手上，顺手推一次
            // （零额外请求；后台另有 2 小时闸门兜底）
            runCatching { WaterWidgetSync.pushQiekjBalance(Graph.appContext, balance) }
        }.onFailure {
            _uiState.update { it.copy(loadingBalance = false, balanceLoaded = true) }
            if (it is TokenExpiredException) {
                handleTokenExpired()
            } else {
                notice(it.message ?: "查询余额失败", NoticeTone.Error)
            }
        }
    }

    /** 最近一次设备列表请求的 Job：直达开水要等它回来才知道选哪台，不补发重复请求。 */
    private var devicesJob: Job? = null

    fun refreshDevices() = (viewModelScope.launch {
        if (!_uiState.value.loggedIn) return@launch
        runCatching {
            _uiState.update { it.copy(loadingDevices = true) }
            repo.latestDevices()
        }.onSuccess { devices ->
            _uiState.update { s ->
                // 默认选第一台；原选中的设备若还在列表里则保持
                val keep = s.selectedDevice?.let { cur -> devices.firstOrNull { it.effectiveGoodsId == cur.effectiveGoodsId } }
                s.copy(devices = devices, loadingDevices = false, selectedDevice = keep ?: devices.firstOrNull())
            }
        }.onFailure {
            _uiState.update { it.copy(loadingDevices = false) }
            if (it is TokenExpiredException) {
                handleTokenExpired()
            } else {
                notice(it.message ?: "查询历史设备失败", NoticeTone.Error)
            }
        }
    }).also { devicesJob = it }

    fun selectDevice(device: DeviceItem) = _uiState.update { it.copy(selectedDevice = device) }

    /**
     * 小组件「去开水」直达（DESIGN §3.6 开水两卡，2026-09-28 用户拍板）：进页即开水。
     *
     * 一次进页只触发一次；未登录 / 流程进行中静默跳过（页面自己会把状态摆出来）。
     * 设备沿用「最近使用」口径：init 已在拉列表，等它回来选默认那台；拉完仍没有
     * （账号下没有历史设备）就交还用户手动处理。**绕过双击确认设置**——那颗开关
     * 管的是页面里的大按钮，桌面胶囊是更明确的主动手势。
     */
    fun requestAutoUnlock() {
        if (autoUnlockRequested) return
        autoUnlockRequested = true
        viewModelScope.launch {
            if (!_uiState.value.loggedIn) return@launch
            if (_uiState.value.flow !is UnlockFlowState.Idle) return@launch
            if (_uiState.value.selectedDevice == null) devicesJob?.join()
            if (_uiState.value.selectedDevice == null) {
                notice("没有可用设备，请手动选择", NoticeTone.Warning)
                return@launch
            }
            unlock()
        }
    }

    private var autoUnlockRequested = false

    /** 已保存的登录 token，给「复制 Token」用（另一台设备粘贴 Token 登录）；未登录返回 null。 */
    fun exportToken(): String? = repo.readToken()

    fun toggleShowPhone() = _uiState.update { it.copy(showPhone = !it.showPhone) }

    /**
     * 下拉刷新：余额与设备各拉一轮，任一在途就不重复发起（对齐趣智页 refreshAccount 的
     * 防重入口径）。refreshBalance / refreshDevices 各自返回 Job，这里 join 两轮都结束后
     * 才收 [WaterUiState.refreshing]——指示器不能在设备还没回来时就停。
     */
    fun refresh() = viewModelScope.launch {
        if (_uiState.value.refreshing) return@launch
        _uiState.update { it.copy(refreshing = true) }
        val balanceJob = refreshBalance()
        val devicesJob = refreshDevices()
        balanceJob.join()
        devicesJob.join()
        _uiState.update { it.copy(refreshing = false) }
    }

    fun toggleUsePoints() = _uiState.update { it.copy(usePoints = !it.usePoints) }

    // ── 开水 ──

    fun unlock() {
        val device = _uiState.value.selectedDevice ?: run {
            notice("请先选择设备", NoticeTone.Warning)
            return
        }
        unlockJob = viewModelScope.launch {
            if (!unlockMutex.tryLock()) return@launch
            try {
                _uiState.update {
                    it.copy(flow = UnlockFlowState.PreChecking("准备开水"))
                }
                startTimer()
                startTimeout()
                runCatching {
                    repo.unlockDevice(device, usePoints = _uiState.value.usePoints) { step ->
                        val working = step.contains("等待") || step.contains("设备工作")
                        _uiState.update { s ->
                            s.copy(
                                flow = if (working) {
                                    UnlockFlowState.Working(step, (s.flow as? UnlockFlowState.Working)?.elapsedSeconds ?: 0)
                                } else {
                                    UnlockFlowState.PreChecking(step)
                                },
                            )
                        }
                    }
                }.onSuccess { result ->
                    cancelFlowJobs()
                    _uiState.update {
                        it.copy(
                            flow = UnlockFlowState.Success(result),
                            orderHistory = repo.orderHistory(),
                        )
                    }
                    refreshBalance()
                }.onFailure { e ->
                    cancelFlowJobs()
                    if (e is TokenExpiredException) {
                        handleTokenExpired()
                        return@launch
                    }
                    val failed = if (e is UnlockException) {
                        UnlockFlowState.Failed(e.message ?: "开水失败", e.diagnosis.step, e.diagnosis.rawError, e.diagnosis.suggestions)
                    } else {
                        UnlockFlowState.Failed(e.message ?: "开水失败", "未知", e.message ?: "")
                    }
                    _uiState.update { it.copy(flow = failed) }
                }
            } finally {
                unlockMutex.unlock()
            }
        }
    }

    fun dismissFlow() {
        cancelFlowJobs()
        _uiState.update { it.copy(flow = UnlockFlowState.Idle) }
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (isActive) {
                delay(1_000)
                val flow = _uiState.value.flow
                if (flow is UnlockFlowState.Working) {
                    _uiState.update { it.copy(flow = flow.copy(elapsedSeconds = flow.elapsedSeconds + 1)) }
                } else if (flow !is UnlockFlowState.PreChecking) {
                    return@launch
                }
            }
        }
    }

    /**
     * 165s 兜底：对齐参考实现——服务端超时会自动关阀结算，本地把状态收回 Idle 并刷新余额；
     * 仓库层轮询协程不取消，收尾完成后 Success 会重新落卡（订单快照不丢）。
     */
    private fun startTimeout() {
        timeoutJob?.cancel()
        timeoutJob = viewModelScope.launch {
            delay(AUTO_SETTLE_SECONDS * 1_000L)
            if (_uiState.value.flow is UnlockFlowState.Working) {
                cancelTimerOnly()
                _uiState.update { it.copy(flow = UnlockFlowState.Idle) }
                notice("出水超时，饮水机已自动关闭并结算", NoticeTone.Warning)
                refreshBalance()
                refreshDevices()
            }
        }
    }

    private fun cancelTimerOnly() {
        timerJob?.cancel()
        timeoutJob?.cancel()
        timerJob = null
        timeoutJob = null
    }

    private fun cancelFlowJobs() {
        timerJob?.cancel()
        timeoutJob?.cancel()
        timerJob = null
        timeoutJob = null
    }

    override fun onCleared() {
        cancelFlowJobs()
        super.onCleared()
    }

    companion object {
        const val AUTO_SETTLE_SECONDS = 165
    }

    class Factory(private val repo: QiekjRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = WaterViewModel(repo) as T
    }
}
