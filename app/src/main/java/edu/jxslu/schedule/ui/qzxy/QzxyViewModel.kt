package edu.jxslu.schedule.ui.qzxy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import android.content.Context
import android.os.SystemClock
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.qzxy.QzxyBalance
import edu.jxslu.schedule.data.qzxy.QzxyBill
import edu.jxslu.schedule.data.qzxy.QzxyBluetoothScanner
import edu.jxslu.schedule.data.qzxy.QzxyBoundDevice
import edu.jxslu.schedule.data.qzxy.QzxyClearStore
import edu.jxslu.schedule.data.qzxy.QzxyDebugStore
import edu.jxslu.schedule.data.qzxy.QzxyDeviceStore
import edu.jxslu.schedule.data.qzxy.QzxyDeviceInfo
import edu.jxslu.schedule.data.qzxy.QzxyGattCharacteristicInfo
import edu.jxslu.schedule.data.qzxy.QzxyGattLink
import edu.jxslu.schedule.data.qzxy.QzxyRateOrderRequest
import edu.jxslu.schedule.data.qzxy.QzxyRepository
import edu.jxslu.schedule.data.qzxy.QzxyScannedDevice
import edu.jxslu.schedule.data.qzxy.QzxySessionExpiredException
import edu.jxslu.schedule.data.qzxy.QzxyWateringStore
import edu.jxslu.schedule.domain.QzxyClData
import edu.jxslu.schedule.domain.QzxyFrame
import edu.jxslu.schedule.domain.QzxyProtocol
import edu.jxslu.schedule.domain.QzxySessionLink
import edu.jxslu.schedule.domain.QzxySign
import edu.jxslu.schedule.domain.QzxyWatering
import edu.jxslu.schedule.domain.QzxyWateringFormat
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
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.time.YearMonth

/**
 * 趣智校园开热水页状态（DESIGN §4.30）。
 *
 * 与胖乖开水页（[edu.jxslu.schedule.ui.water.WaterViewModel]）同构：
 * 登录态 + 设备选择 + 一条带计时/超时的开阀流程。差别在开阀这一段：
 * 胖乖全程 HTTP，这里要走「蓝牙读设备 → HTTP 下单 → 蓝牙写 downData」三步，
 * 任何一步的原始数据都留在界面上，方便对着抓包结果比。
 */
data class QzxyUiState(
    // ── 登录 ──
    val loggedIn: Boolean = false,
    val phone: String = "",
    val password: String = "",
    val code: String = "",
    val useSmsLogin: Boolean = false,
    val sendingCode: Boolean = false,
    val loggingIn: Boolean = false,
    val phoneError: String? = null,
    /** 手机号是否展开。默认遮蔽，点账号一行的眼睛才给完整的。 */
    val showPhone: Boolean = false,
    /** 是否展开「会话串登录」。默认收起，与胖乖的 Token 登录同一个交互。 */
    val showSessionLogin: Boolean = false,
    val sessionLoginInput: String = "",
    val sessionLoggingIn: Boolean = false,
    // ── 账号 ──
    val schoolName: String = "",
    /**
     * 登录会话里的手机号（充值入口要说明钱进哪个账户）。
     *
     * 单独放一个字段，不拿登录输入框的 [phone] 顶替：用户登录后可能又改了输入框，
     * 那个值不再代表当前账户。
     */
    val accountPhone: String = "",
    val balance: QzxyBalance? = null,
    val balanceLoaded: Boolean = false,
    /** 余额刷新中。刷新按钮要能给出反馈，不然点了像没反应。 */
    val refreshingBalance: Boolean = false,
    // ── 设备 ──
    val scanning: Boolean = false,
    val devices: List<QzxyScannedDevice> = emptyList(),
    /** 已绑定设备（DESIGN §4.30）：不扫蓝牙也能直接连，省得每次重新找。 */
    val boundDevices: List<QzxyBoundDevice> = emptyList(),
    /** 上次用过的设备：今日页卡片副行显示它，诊断页单独打开时也拿它兜底。 */
    val lastUsedDevice: QzxyBoundDevice? = null,
    /** 设备地址（见 [QzxyScannedDevice.addressKey]）→ 服务端设备信息。查不到的不会出现在这里。 */
    val deviceInfos: Map<String, QzxyDeviceInfo> = emptyMap(),
    val selected: QzxyScannedDevice? = null,
    val serverDevice: QzxyDeviceInfo? = null,
    val deviceState: QzxyProtocol.DeviceState? = null,
    /** 最近一次连接设备读回的服务表（探测与开阀都会填）。 */
    val gattTable: List<QzxyGattCharacteristicInfo> = emptyList(),
    /** 试签名的逐条结果。 */
    val signatureTrials: List<SignatureTrial> = emptyList(),
    /** 清除命令的试错结果（功能码 + 参数组合，逐条试）。 */
    val clearTrials: List<SignatureTrial> = emptyList(),
    /** 结束用水时从设备读回的消费数据（十六进制原文），结算失败时报出来供对表。 */
    val consumeRaw: String? = null,
    /** 消费数据那次交互的完整回包（含帧头帧尾），用于核对设备到底发了什么。 */
    val consumeFrameRaw: String? = null,
    /** 消费记录按字段解析出来的要点（时间序号 / 消费 / 预扣），比裸十六进制好读。 */
    val consumeSummary: String? = null,
    /**
     * 结算响应里的 `clData` 原文。服务端签发的清除凭据，见
     * [edu.jxslu.schedule.domain.QzxyClData]。
     */
    val clDataRaw: String? = null,
    /** `clData` 解密后的明文（十六进制）；解不出来为 null。 */
    val clDataPlainHex: String? = null,
    /** `clData` 解密明文的可读形式，进诊断文本。 */
    val clDataPlainText: String? = null,
    /** 清除命令最后一条尝试的结果说明。 */
    val clearResult: String? = null,
    /** 当前这次流程各阶段的耗时（`连接 1200ms` 这样），用来判断慢在哪一段。 */
    val timings: List<String> = emptyList(),
    /** 最近一次开阀下发的 downData 十六进制与其字节数，用来核对是否被分包截断。 */
    val lastDownDataHex: String? = null,
    /** 是否记录调试日志（DESIGN §4.30）。**默认关**：日志含设备地址与接口原文，日常不必留。 */
    val debugLogEnabled: Boolean = false,
    /** 调试日志条目，最新在后。开关关掉即清空。 */
    val debugLog: List<String> = emptyList(),
    // ── 消费记录 ──
    /** 当前查看的月份，`yyyy-MM`。 */
    val billMonth: String = "",
    val bills: List<QzxyBill> = emptyList(),
    val loadingBills: Boolean = false,
    /** 是否至少拉过一次（成功失败都算），用来区分「加载中」与「这个月没有消费」。 */
    val billLoaded: Boolean = false,
    // ── 流程 ──
    val flow: QzxyFlowState = QzxyFlowState.Idle,
    // ── 用水 ──
    /**
     * 进行中的用水（DESIGN §3.18）。非空 = 设备在放水，或上次开阀后一直没结算。
     *
     * 真相源是 [QzxyWateringStore] 的那条流，本字段是它的投影——今日页卡片与
     * 趣智校园页是两个独立 ViewModel 实例，状态落在两者之外才同步得起来。
     */
    val watering: QzxyWatering? = null,
    /** 最近一次结算结果。展示后由用户点「完成」清掉，不自动消失（金额值得多看一眼）。 */
    val lastSettlement: QzxySettlement? = null,
) {
    /**
     * 能不能开阀：主动选过设备、用过上次那台、或至少绑过一台。
     *
     * 界面上「开始用水」的可用性看它，而不是只看 `selected`——进页面时设备通常
     * 还没选（自动连蓝牙不合适），但上次用过的那台就在手边，按钮不该是灰的。
     */
    val hasUsableDevice: Boolean
        get() = selected != null || lastUsedDevice != null || boundDevices.isNotEmpty()
}

/**
 * 一次结算的结果。
 *
 * [consumeMoneyMilli] 是服务端结算接口返回的**厘**；[durationMillis] 为 null 表示
 * 本地没有起点（比如开阀那次进程被杀过），此时不显示时长，不拿 0 顶。
 */
data class QzxySettlement(
    val consumeMoneyMilli: String?,
    val durationMillis: Long?,
    /** 例外情况：设备记录没清掉、结算被拒但记录已清之类，需要让用户看到的那句。 */
    val note: String? = null,
)

/** 一次签名试错的结果。 */
data class SignatureTrial(val label: String, val result: String, val ok: Boolean)

/** 一帧回包的完整信息：设备原文 + 解析结果。 */
private data class QzxyReply(val raw: String, val response: QzxyProtocol.DeviceResponse)

sealed interface QzxyFlowState {
    data object Idle : QzxyFlowState

    /** 进行中。[step] 是当前步骤文案，直接显示给用户。 */
    data class Working(val step: String) : QzxyFlowState
    data class Success(val text: String) : QzxyFlowState

    /** 失败。[detail] 放原始响应或异常文本，诊断卡里展示。 */
    data class Failed(val reason: String, val detail: String?) : QzxyFlowState
}

sealed interface QzxyEvent {
    data class Notice(val text: String, val tone: NoticeTone) : QzxyEvent
}

class QzxyViewModel(
    private val repo: QzxyRepository,
    private val scanner: QzxyBluetoothScanner,
    private val link: QzxyGattLink,
    /** 进程级的「一次只跑一条流程」锁，见 [valveMutex]。 */
    private val flowLock: Mutex,
    private val deviceStore: QzxyDeviceStore,
    private val debugStore: QzxyDebugStore,
    private val clearStore: QzxyClearStore,
    private val wateringStore: QzxyWateringStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        QzxyUiState(
            loggedIn = repo.localSession() != null,
            accountPhone = repo.localSession()?.telephone.orEmpty(),
            // 会话失效后重新登录少打 11 位数字；退出登录也留着（手机号不是凭证）
            phone = debugStore.rememberedPhone,
            boundDevices = deviceStore.list(),
            lastUsedDevice = deviceStore.lastUsed(),
            debugLogEnabled = debugStore.logEnabled,
        ),
    )
    val uiState: StateFlow<QzxyUiState> = _uiState.asStateFlow()

    private val _events = Channel<QzxyEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /**
     * 开阀互斥：一次只跑一条链路，中途再点直接忽略。
     *
     * 用 [Graph.qzxyFlowLock] 这把**进程级**的锁，不是自己 new 一个：今日页那份
     * （面板开阀）与页面、诊断页各持一份 ViewModel，各自的锁互不相识，而它们共用
     * 同一条 GATT 链路（[Graph.qzxyLink]），两个窗口的流程会同时去动设备。
     */
    private val valveMutex: Mutex = flowLock

    /**
     * 建链锁，与 [valveMutex] 分开：预连接不该占住「开阀/结束用水」那把锁，
     * 否则用户刚点完设备就点开阀会被告知「上一步还没结束」。
     */
    private val connectMutex = Mutex()

    /**
     * 拿操作锁。同一时刻只允许一条流程在跑（开阀、结束用水、试签名、清除都算），
     * 否则两次开阀会各下一单、各写一次设备。
     *
     * 拿不到时给一句提示：静默返回会让用户以为点了没反应，然后连点。
     */
    private fun beginFlow(): Boolean {
        if (valveMutex.tryLock()) return true
        notice("上一个操作还没结束，稍等一下再点", NoticeTone.Warning)
        return false
    }

    /** 设备信息查询串行化：扫描一分钟能扫出十几台，并发打过去既没必要也不礼貌。 */
    private val infoMutex = Mutex()
    private val queriedAddresses = mutableSetOf<String>()

    /** 空闲断连的计时任务，每发一条命令就重排，见 [armIdleDisconnect]。 */
    private var idleDisconnectJob: Job? = null

    /** 试签名试出来的可用签名；本次会话内开阀直接用它，成功后应固化进 [QzxySign]。 */
    private var workingSignature: String? = null

    private var codeSentAt = 0L

    private fun notice(text: String, tone: NoticeTone = NoticeTone.Info) =
        _events.trySend(QzxyEvent.Notice(text, tone))

    /**
     * 桌面趣智开水卡（DESIGN §3.6「开水两卡」）跟着本地事实走：用水状态翻转、
     * 余额取到、登录态变化后各推一次。快照构建全程零网络（本地 store + DataStore）。
     */
    private fun pushWaterWidget() {
        viewModelScope.launch {
            runCatching { WaterWidgetSync.refreshQzxyWidget(Graph.appContext) }
        }
    }

    init {
        // 登录态以仓库的 [QzxyRepository.loggedIn] 为准：今日页卡片那份实例与页面各持
        // 一份，首启引导里登录（或页面里退出）只能从这条流得知。与胖乖开水那边同构。
        viewModelScope.launch {
            repo.loggedIn.collect { syncLoginState(it) }
        }
        // 只拉学校名与余额：今日页那张半行卡要用。
        // 消费记录不在这里拉——Activity 作用域这份实例只为卡片服务，账单它从来不显示，
        // 拉一次纯属白花；进趣智校园页时由页面调 [loadBillsOnce]。
        if (_uiState.value.loggedIn) {
            refreshAccount()
        }
        // 用水状态是唯一真相源：开阀、结算、手动标记都写 store，页面与今日页卡片
        // 都从这条流回流。两个 ViewModel 实例各持一份状态的话，页面里开阀、
        // 今日页卡片不会跟着变。
        viewModelScope.launch {
            wateringStore.watering.collect { watering -> applyWatering(watering) }
        }
        // 已绑定设备与「上次使用」也走一条流：绑定发生在趣智校园页，而今日页卡片是
        // 另一份 ViewModel 实例，只读一次 SharedPreferences 的写法会让卡片一直
        // 显示「未绑定」
        viewModelScope.launch {
            deviceStore.state.collect { deviceState ->
                _uiState.update {
                    it.copy(
                        boundDevices = deviceState.bound,
                        lastUsedDevice = deviceState.lastUsed,
                    )
                }
            }
        }
    }

    /**
     * 登录态变化的收尾。
     *
     * 相等就返回：这条流订阅时会立即发一次当前值，不设这道闸会把余额与账单各多发一轮。
     */
    private fun syncLoginState(loggedIn: Boolean) {
        if (loggedIn == _uiState.value.loggedIn) return
        if (loggedIn) {
            _uiState.update { it.copy(loggedIn = true, phoneError = null) }
            refreshAccount()
        } else {
            link.close()
            // 扫描也要停：登出后回调还会继续往设备列表里塞东西，而且白占着无线电
            scanner.stopScan()
            _uiState.update { loggedOutState(it) }
            // 桌面趣智开水卡（DESIGN §3.6「开水两卡」）跟着切回未登录形态；
            // 登录方向的推送由 refreshAccount 成功接管，这里不重复取数
            viewModelScope.launch {
                runCatching { WaterWidgetSync.refreshQzxyWidget(Graph.appContext) }
            }
        }
    }

    /**
     * 退出登录后的界面状态：只保留与账号无关的本地事实。
     *
     * 用水状态、已绑定设备、调试开关都不随登录态走——水可能还在流，绑定是本机记录。
     */
    private fun loggedOutState(state: QzxyUiState): QzxyUiState = QzxyUiState(
        watering = state.watering,
        lastUsedDevice = state.lastUsedDevice,
        // 手机号不是凭证，退出后留在输入框里，重新登录少打 11 位
        phone = debugStore.rememberedPhone,
        boundDevices = deviceStore.list(),
        debugLogEnabled = state.debugLogEnabled,
        debugLog = state.debugLog,
    )

    /**
     * 把 store 里的用水状态投影进界面状态。
     *
     * 出水期间把选中设备锁到那一台：用户半路换了别的设备，界面会出现「用水中」
     * 配着另一台设备的名字，结束用水的按钮也会连错设备。想换设备先结束用水。
     */
    private fun applyWatering(watering: QzxyWatering?) {
        // 过期清理：记账是离线的，设备真实状态只有问了才知道。超过 1 小时的
        // 「用水中」几乎必然是残留（开完没结算/进程被杀），继续显示进行中的计时
        // 只会误导。这里就地清掉并给一条提示；设备侧若真还有记录，用户点
        // 「结束用水」或「清除设备记录」都能解。
        if (watering != null && watering.isExpired(System.currentTimeMillis())) {
            wateringStore.clear()
            notice(
                "上次 ${QzxyWateringFormat.clockText(watering.startedAtMillis)} 的用水已超过 1 小时，" +
                    "本地提醒已清除。若设备上还有记录，点「结束用水」结算",
                NoticeTone.Warning,
            )
            pushWaterWidget()
            return
        }
        _uiState.update { state ->
            when {
                watering == null -> state.copy(watering = null)
                state.selected?.address.equals(watering.deviceAddress, ignoreCase = true) ->
                    state.copy(watering = watering)
                else -> state.copy(
                    watering = watering,
                    selected = QzxyScannedDevice(
                        name = watering.deviceName,
                        address = watering.deviceAddress,
                        rssi = 0,
                        deviceKey = QzxyBluetoothScanner.extractDeviceKey(
                            watering.deviceName,
                            watering.deviceAddress,
                        ),
                    ),
                    serverDevice = null,
                    deviceState = null,
                    gattTable = emptyList(),
                    flow = QzxyFlowState.Idle,
                )
            }
        }
        // 卡片副行与重进页面的默认选中都读它，名字用当次记账的那份
        watering?.let { rememberLastUsed(QzxyBoundDevice(it.deviceAddress, it.deviceName)) }
    }

    /** 记住这台设备：落盘 + 同步进界面状态（今日页卡片副行读的就是它）。 */
    private fun rememberLastUsed(device: QzxyBoundDevice) {
        deviceStore.setLastUsed(device)
        _uiState.update { it.copy(lastUsedDevice = device) }
    }

    // ── 登录 ──

    fun updatePhone(value: String) = _uiState.update {
        val digits = value.filter(Char::isDigit).take(11)
        it.copy(
            phone = digits,
            phoneError = if (digits.isNotEmpty() && digits.length < 11) "请输入 11 位手机号" else null,
        )
    }

    fun updatePassword(value: String) = _uiState.update { it.copy(password = value) }

    fun updateCode(value: String) = _uiState.update { it.copy(code = value.filter(Char::isDigit).take(6)) }

    /** 切换登录方式：false = 密码，true = 短信验证码。默认密码，验证码要等短信。 */
    fun toggleUseSmsLogin() = _uiState.update { it.copy(useSmsLogin = !it.useSmsLogin) }

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
        val state = _uiState.value
        if (state.phone.length != 11) {
            _uiState.update { it.copy(phoneError = "请输入 11 位手机号") }
            return@launch
        }
        if (!state.useSmsLogin && state.password.isBlank()) {
            notice("请输入密码", NoticeTone.Warning)
            return@launch
        }
        if (state.useSmsLogin && state.code.isBlank()) {
            notice("请输入验证码", NoticeTone.Warning)
            return@launch
        }
        runCatching {
            _uiState.update { it.copy(loggingIn = true) }
            if (state.useSmsLogin) {
                repo.loginBySms(state.phone, state.code)
            } else {
                repo.loginByPassword(state.phone, state.password)
            }
        }.onSuccess { session ->
            debugStore.rememberedPhone = session.telephone
            _uiState.update {
                it.copy(
                    loggedIn = true,
                    accountPhone = session.telephone,
                    loggingIn = false,
                    password = "",
                    code = "",
                    phoneError = null,
                )
            }
            notice("登录成功", NoticeTone.Success)
            refreshAccount()
            // 账单原先在 init 里拉，现在改由页面触发；登录前那次因为未登录直接返回了，
            // 这里补一次，否则登录后消费记录区一直空着
            loadBills()
        }.onFailure {
            _uiState.update { it.copy(loggingIn = false) }
            notice(it.message ?: "登录失败", NoticeTone.Error)
        }
    }

    fun logout() {
        repo.logout()
        link.close()
        // 只保留与账号无关的本地事实：水可能还在流、预扣还挂着，退登录不能把它一起抹掉，
        // 否则用户重新登录后看不到「上次开阀没结算」，绑定过的设备也会凭空消失
        _uiState.update { loggedOutState(it) }
        notice("已退出趣智校园登录")
    }

    /** 切换手机号显示：默认遮蔽，点一次展开。 */
    fun toggleShowPhone() = _uiState.update { it.copy(showPhone = !it.showPhone) }

    /** 展开/收起「会话串登录」。收起时清空输入，别把粘过的凭据留在界面上。 */
    fun toggleSessionLogin() = _uiState.update {
        it.copy(showSessionLogin = !it.showSessionLogin, sessionLoginInput = "")
    }

    fun updateSessionLoginInput(value: String) =
        _uiState.update { it.copy(sessionLoginInput = value) }

    /**
     * 用粘贴的会话串登录。
     *
     * 与胖乖的 Token 粘贴登录同构，差别只在凭据形态：那边服务端签发的 token 单独可用，
     * 这边得凑齐 `loginCode` + `projectId`（外加 `accountId` / `userId` / `telephone`）。
     *
     * **先验后存**：先拿候选会话调一次只读接口（学校名），通了才落盘。否则会出现
     * 「已登录但什么都查不到」，用户不知道是会话错还是网络问题。
     */
    fun loginWithSession() = viewModelScope.launch {
        val text = _uiState.value.sessionLoginInput.trim()
        if (text.isEmpty()) {
            notice("请先粘贴会话串", NoticeTone.Warning)
            return@launch
        }
        val candidate = QzxySessionLink.parse(text)
        if (candidate == null) {
            val missing = QzxySessionLink.missingField(text) ?: "loginCode"
            notice(
                "没认出会话串：缺少 $missing。把登录响应里的 loginCode、projectId、" +
                    "accountId、userId、telephone 一起复制过来",
                NoticeTone.Error,
            )
            return@launch
        }
        runCatching {
            _uiState.update { it.copy(sessionLoggingIn = true) }
            repo.validateSession(candidate)
        }.onSuccess { project ->
            repo.adoptSession(candidate)
            candidate.telephone.takeIf { it.isNotBlank() }?.let { debugStore.rememberedPhone = it }
            _uiState.update {
                it.copy(
                    loggedIn = true,
                    accountPhone = candidate.telephone,
                    sessionLoggingIn = false,
                    showSessionLogin = false,
                    sessionLoginInput = "",
                )
            }
            notice("会话登录成功", NoticeTone.Success)
            // 学校名刚刚验证时已经拿到，不再多打一次 /project/info/triple
            refreshAccount(knownProjectName = project.projectName.orEmpty())
            loadBills()
        }.onFailure {
            _uiState.update { it.copy(sessionLoggingIn = false) }
            notice(it.message ?: "会话无效或已过期", NoticeTone.Error)
        }
    }

    /**
     * 导出当前会话串，供换机或另一台设备粘贴登录。
     *
     * 入口只放在调试工具里：这串东西等于账号通行证，不该摆在日常界面上。
     */
    fun exportSession(): String? = repo.localSession()?.let(QzxySessionLink::format)

    /**
     * 拉学校名与余额。
     *
     * **不再调 `/account/info`**：那个接口只为拿姓名与学号，而学校没把学籍数据同步过来时
     * 它整条返回 null（本校实测如此），页面早就用不上，白多一次请求和一条失败路径。
     * 学校名仍要留着——它用来确认登录确实落在本校项目上，落错了后面全盘皆输。
     */
    fun refreshAccount(knownProjectName: String? = null) = viewModelScope.launch {
        if (!_uiState.value.loggedIn) return@launch
        // 连点「刷新」不该变成两轮请求（学校名 + 余额各两次）
        if (_uiState.value.refreshingBalance) return@launch
        // 账户手机号取自会话，用来在充值入口说清钱进哪个账户（登录输入框的值可能已被改过）
        val sessionPhone = repo.localSession()?.telephone.orEmpty()
        _uiState.update { it.copy(refreshingBalance = true, accountPhone = sessionPhone) }
        if (knownProjectName.isNullOrBlank()) {
            runCatching { repo.projectInfo() }
                .onSuccess { project ->
                    _uiState.update { it.copy(schoolName = project.projectName.orEmpty()) }
                }
                .onFailure { error ->
                    if (error is QzxySessionExpiredException) {
                        handleFailure(error, "登录已失效")
                        return@launch
                    }
                }
        } else {
            _uiState.update { it.copy(schoolName = knownProjectName) }
        }
        runCatching { repo.balance() }
            .onSuccess { balance ->
                _uiState.update { it.copy(balance = balance, balanceLoaded = true) }
                // 桌面趣智开水卡（DESIGN §3.6「开水两卡」）：余额已经在手上，顺手推一次
                runCatching { WaterWidgetSync.pushQzxyAccount(Graph.appContext, balance) }
            }
            .onFailure { error ->
                _uiState.update { it.copy(balanceLoaded = true) }
                if (error is QzxySessionExpiredException) handleFailure(error, "登录已失效")
            }
        _uiState.update { it.copy(refreshingBalance = false) }
    }

    // ── 设备 ──

    /** 开始扫描。蓝牙未开或没权限时 [QzxyBluetoothScanner.startScan] 会直接回报。 */
    fun startScan() {
        if (_uiState.value.scanning) return
        // 设备信息与「已查过的地址」**不跟着扫描清掉**：服务端设备名（含房间号）基本不变，
        // 重扫只查这次新出现的设备。一次扫描能扫出十几台、每台一次 `device/info/mac`，
        // 连扫三次就是四十多个请求，服务端那侧看着就是异常流量。
        _uiState.update {
            it.copy(
                scanning = true,
                devices = emptyList(),
                selected = null,
                serverDevice = null,
                deviceState = null,
                flow = QzxyFlowState.Idle,
            )
        }
        val started = scanner.startScan(
            onDevice = { device ->
                _uiState.update { state ->
                    if (state.devices.any { it.address == device.address }) {
                        state
                    } else {
                        state.copy(devices = (state.devices + device).sortedByDescending { d -> d.rssi })
                    }
                }
                queryDeviceInfo(device)
            },
            onFinished = { ok ->
                _uiState.update { it.copy(scanning = false) }
                if (!ok) notice("扫描没有正常结束", NoticeTone.Warning)
            },
            onError = { message ->
                _uiState.update { it.copy(scanning = false) }
                notice(message, NoticeTone.Error)
            },
        )
        if (!started) _uiState.update { it.copy(scanning = false) }
    }

    fun stopScan() {
        scanner.stopScan()
        _uiState.update { it.copy(scanning = false) }
    }

    /**
     * 每扫到一台就顺手查一次服务端设备信息，把「热水器-学生公寓-1号楼-3层-301」
     * 这样的名字填到列表上，而不是只给一个广播名。
     *
     * 失败不打扰用户：查不到就继续显示广播名，列表本身不受影响。
     */
    private fun queryDeviceInfo(device: QzxyScannedDevice) {
        val key = device.addressKey
        synchronized(queriedAddresses) { if (!queriedAddresses.add(key)) return }
        viewModelScope.launch {
            infoMutex.withLock {
                val info = runCatching { repo.deviceInfo(device.address) }
                    .onFailure { error ->
                        if (error is QzxySessionExpiredException) handleFailure(error, "登录已失效")
                    }
                    .getOrNull() ?: return@withLock
                _uiState.update { it.copy(deviceInfos = it.deviceInfos + (key to info)) }
                refreshBoundName(device.address, info.deviceName)
            }
        }
    }

    /**
     * 拿到服务端设备名后，把已绑定的那条也更新掉。
     *
     * 绑定那一刻服务端信息可能还没回来，存下的是广播名（`KLCXKJ-…`），用户退出再进来
     * 看到的就是这串认不出的东西。凡是拿到服务端名字的路径（扫描查询、选中查询）都调它。
     */
    private fun refreshBoundName(address: String, serverName: String?) {
        val name = serverName?.takeIf { it.isNotBlank() } ?: return
        val bound = deviceStore.list().firstOrNull { it.address.equals(address, ignoreCase = true) }
        if (bound != null && bound.name != name) {
            deviceStore.add(QzxyBoundDevice(address, name))
        }
    }

    // ── 绑定设备 ──

    /**
     * 绑定一台设备。名字优先用服务端登记的那个（带楼栋楼层房间），没有才用广播名。
     *
     * **手上没有服务端名字就补一次查询再存**：扫描时那一次查询可能还没回来，直接存
     * 广播名（`KLCXKJ-…`）的话，用户退出再进来看到的就是这串认不出的东西，只能重新
     * 绑一次才变正常。
     */
    fun bindDevice(device: QzxyScannedDevice) = viewModelScope.launch {
        val known = _uiState.value.deviceInfos[device.addressKey]
            ?.deviceName
            ?.takeIf { it.isNotBlank() }
        val name = known
            ?: runCatching { repo.deviceInfo(device.address) }
                .getOrNull()
                ?.deviceName
                ?.takeIf { it.isNotBlank() }
            ?: device.name
        deviceStore.add(QzxyBoundDevice(device.address, name))
        notice("已绑定「$name」", NoticeTone.Success)
    }

    fun unbindDevice(address: String) {
        deviceStore.remove(address)
        notice("已解除绑定")
    }

    /** 选中一台已绑定设备。rssi 留 0（没扫过，没有信号值），编号按名字推。 */
    fun selectBoundDevice(bound: QzxyBoundDevice) {
        selectDevice(
            QzxyScannedDevice(
                name = bound.name,
                address = bound.address,
                rssi = 0,
                deviceKey = QzxyBluetoothScanner.extractDeviceKey(bound.name, bound.address),
            ),
        )
    }

    /** 选中设备并发一次 HTTP 设备信息查询，把服务端的说法与蓝牙现场对照着看。 */
    fun selectDevice(device: QzxyScannedDevice) {
        // 扫描与连接抢同一个无线电，扫描不停建链会慢好几倍。用户点选设备时
        // 扫描通常还在跑（默认扫 10 秒），所以先停掉再连。
        scanner.stopScan()
        // 扫描时已经查过的直接用缓存，不再打一次同样的请求
        val cached = _uiState.value.deviceInfos[device.addressKey]
        _uiState.update {
            it.copy(
                scanning = false,
                selected = device,
                serverDevice = cached,
                deviceState = null,
                gattTable = emptyList(),
                timings = emptyList(),
                flow = QzxyFlowState.Idle,
            )
        }
        // 建链提前做掉：开阀真正慢的是这一段，而它与「下单」没有依赖关系
        preconnect(device)
        // 记下这台：今日页卡片副行与重进页面的默认选中都读它。
        // 名字先用广播名，下面服务端信息回来会换成带楼栋楼层的那份
        rememberLastUsed(QzxyBoundDevice(device.address, device.name))
        if (cached != null) return
        viewModelScope.launch {
            runCatching { repo.deviceInfo(device.address) }
                .onSuccess { info ->
                    _uiState.update { state ->
                        state.copy(
                            serverDevice = info,
                            deviceInfos = if (info == null) {
                                state.deviceInfos
                            } else {
                                state.deviceInfos + (device.addressKey to info)
                            },
                        )
                    }
                    info?.deviceName?.takeIf { it.isNotBlank() }?.let { name ->
                        rememberLastUsed(QzxyBoundDevice(device.address, name))
                    }
                    // 选中的这台若是已绑定的，名字也一并修正（历史数据里可能是广播名）
                    refreshBoundName(device.address, info?.deviceName)
                }
                .onFailure { notice(it.message ?: "查询设备信息失败", NoticeTone.Warning) }
        }
    }

    // ── 开阀 ──

    /**
     * 只连设备、读服务表，不下发任何指令。
     *
     * 存在的理由很实际：官方 SDK 只暴露了特征值的**常量名**，UUID 在闭源二进制里。
     * 与其猜，不如让用户点一下，把设备自己声明的服务表读出来。
     */
    fun probeServices() {
        val device = _uiState.value.selected
        if (device == null) {
            notice("请先扫描并选择设备", NoticeTone.Warning)
            return
        }
        viewModelScope.launch {
            if (!beginFlow()) return@launch
            try {
                _uiState.update { it.copy(timings = emptyList()) }
                step("连接设备并读取服务表")
                // 探测要的是「这次连上时设备声明的服务表」，所以强制重连一次。
                // 走 reconnect 而不是直接 link.connect：预连接可能正在建链。
                val table = timed("连接") { reconnect(device) }.getOrElse { error ->
                    fail("连接设备失败", error.message)
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        gattTable = table,
                        flow = QzxyFlowState.Success("读到 ${table.size} 项服务/特征值，见下方列表"),
                    )
                }
            } finally {
                valveMutex.unlock()
            }
        }
    }

    /**
     * 逐个试签名变体。
     *
     * 为什么值得做：签名是这条链路里唯一没有公开实现的环节，官方 APK 又加固过
     * （DEX 里连 `getSign` 都搜不到），静态读不出来。而服务端对签名错误给的是
     * 明确拒绝、不创建订单，所以把有限几种拼接与哈希组合挨个发一次，
     * 是拿到答案代价最低的办法。
     */
    fun probeSignatures() {
        val device = _uiState.value.selected
        if (device == null) {
            notice("请先扫描并选择设备", NoticeTone.Warning)
            return
        }
        viewModelScope.launch {
            if (!beginFlow()) return@launch
            try {
                _uiState.update { it.copy(timings = emptyList()) }
                // 试签名要 randomNumber，所以复用同一套「连设备 → 读状态」前置步骤；
                // 已经读过的就不再连一次
                val state = ensureDeviceState(device) ?: return@launch

                val request = buildOrderRequest(device, state)
                val candidates = repo.rateOrderSignCandidates(request)
                if (candidates.isEmpty()) {
                    fail("拿不到登录信息", "请重新登录后再试")
                    return@launch
                }

                _uiState.update { it.copy(signatureTrials = emptyList()) }
                val trials = mutableListOf<SignatureTrial>()
                var passed: String? = null
                candidates.forEachIndexed { index, candidate ->
                    if (passed != null) return@forEachIndexed
                    step("试签名 ${index + 1}/${candidates.size}")
                    val outcome = runCatching {
                        repo.rateOrder(request, signatureOverride = candidate.signature)
                    }
                    val ok = outcome.isSuccess
                    trials += SignatureTrial(
                        label = candidate.label,
                        result = outcome.fold(
                            onSuccess = { "通过" },
                            onFailure = { it.message ?: "被拒绝" },
                        ),
                        ok = ok,
                    )
                    _uiState.update { it.copy(signatureTrials = trials.toList()) }
                    if (ok) {
                        passed = candidate.label
                        workingSignature = candidate.signature
                    }
                }
                _uiState.update {
                    it.copy(
                        flow = if (passed != null) {
                            QzxyFlowState.Success("找到可用签名：$passed")
                        } else {
                            QzxyFlowState.Failed(
                                "所有候选都被拒绝",
                                "把上面的回话发出来，按服务端的提示再补新的组合",
                            )
                        },
                    )
                }
            } finally {
                valveMutex.unlock()
            }
        }
    }

    /**
     * 完整链路的尝试：连设备 → 读设备状态 → HTTP 下单 → 把 downData 写回设备。
     *
     * 链路在选中设备时已经预连接过，这里多半只剩「读状态 → 下单 → 写 downData」三段。
     * 每段耗时记进诊断文本，用户觉得慢时能直接看出卡在哪。
     */
    fun openValve() {
        val device = _uiState.value.selected
        if (device == null) {
            notice("请先扫描并选择设备", NoticeTone.Warning)
            return
        }
        viewModelScope.launch {
            if (!beginFlow()) return@launch
            try {
                _uiState.update { it.copy(timings = emptyList()) }

                // 下单要用服务端登记的 MAC，缺了它订单会挂错设备、结算报「未找到订单」。
                // 选中设备时已经查过，正常走到这里是空转。
                if (ensureServerDevice(device) == null) {
                    fail(
                        "拿不到设备登记信息",
                        "下单要用服务端登记的 MAC，查不到就没法保证订单挂对设备。检查网络后重试",
                    )
                    return@launch
                }

                step("连接设备（${device.address}）")
                if (!timed("连接") { ensureLink(device, announce = true) }) return@launch

                step("读取设备状态")
                val deviceState = timed("读状态") {
                    ensureDeviceState(device, forceRefresh = true)
                } ?: return@launch
                if (deviceState.deviceState != QzxyProtocol.STATE_IDLE) {
                    // 状态 3 是最常见的一种：上次用完没结算，设备还扣着那条记录。
                    // 这时候发开阀没用，得先把记录结掉。
                    val hint = when (deviceState.deviceState) {
                        QzxyProtocol.STATE_FINISHED_UNCOLLECTED ->
                            "设备上有一条未结算的用水记录。点「结束用水」走一遍结算并清除，" +
                                "或直接点「清除设备记录」"
                        QzxyProtocol.STATE_SETTLING ->
                            "上一条用水记录还在写入（状态 6），等几秒再点「开阀」"
                        else -> QzxyProtocol.stateText(deviceState.deviceState)
                    }
                    fail("设备当前不可开", hint)
                    return@launch
                }

                step("向服务端下单")
                val order = timed("下单") {
                    runCatching {
                        repo.rateOrder(
                            buildOrderRequest(device, deviceState),
                            signatureOverride = workingSignature,
                        )
                    }
                }
                    .getOrElse { error ->
                        fail("下单失败", error.message)
                        return@launch
                    }
                val downData = order.downData?.let { QzxyFrame.hexToBytes(it) }
                if (downData == null) {
                    fail("服务端返回的开阀数据不可解析", order.downData)
                    return@launch
                }
                _uiState.update { it.copy(lastDownDataHex = order.downData) }

                step("下发开阀数据（${downData.size} 字节）")
                val opened = timed("写开阀") {
                    sendFrame(QzxyProtocol.DOWN_RATE, downData)
                }.getOrElse { error ->
                    fail("开阀数据没写进设备", preDeductDetail(error.message))
                    return@launch
                }
                if (opened == null) {
                    fail("设备没有回应开阀数据", preDeductDetail("等待回包超时"))
                    return@launch
                }
                if (!opened.response.success) {
                    fail("设备拒绝了开阀数据", preDeductDetail(opened.response.summary))
                    return@launch
                }
                log("开阀成功：downData ${downData.size} 字节，MTU ${link.negotiatedMtu}")
                // 开阀成功 = 进入「用水中」：状态落到 store，页面与今日页卡片同时切换。
                // 不再给一句一次性成功文案——那一刻起用户要盯的是计时与结束按钮。
                wateringStore.begin(
                    QzxyWatering(
                        startedAtMillis = System.currentTimeMillis(),
                        deviceAddress = device.address,
                        deviceName = deviceDisplayName(device),
                        preDeductMilli = order.preDeductMoney,
                    ),
                )
                // 桌面趣智开水卡立刻切「用水中」（本地镜像，零网络）
                pushWaterWidget()
                _uiState.update { it.copy(flow = QzxyFlowState.Idle) }
                notice(
                    "已开阀，服务端预扣 ${milliYuanText(order.preDeductMoney)}，用完点「结束用水」",
                    NoticeTone.Success,
                )
                // 预扣已经发生，余额不刷新的话卡上还是开阀前的数
                refreshAccount()
            } finally {
                valveMutex.unlock()
            }
        }
    }

    fun clearFlow() = _uiState.update { it.copy(flow = QzxyFlowState.Idle) }

    /**
     * 今日页面板里的「开始用水」。
     *
     * 面板是半行卡点开的，用户没经过「选设备」这一步，所以这里用上次那台兜底
     * （没有上次就用唯一一台已绑定设备）。一台都没有才让他回页面选——
     * 直接禁用按钮的话，用户看到面板上写着设备名却点不动，只能猜为什么。
     */
    fun openValveFromCard() {
        if (_uiState.value.selected == null) {
            val target = deviceStore.lastUsed()
                ?: _uiState.value.boundDevices.firstOrNull()
            if (target == null) {
                notice("还没绑定热水器，先到趣智校园页选一台", NoticeTone.Warning)
                return
            }
            selectBoundDevice(target)
        }
        openValve()
    }

    /**
     * 小组件「去开水」直达（DESIGN §3.6 开水两卡，2026-09-28 用户拍板）：进页即开阀。
     *
     * 设备口径与 [openValveFromCard] 一致（上次那台 → 唯一绑定那台 → 提示去选）。
     * 已在用水 / 流程进行中 / 未登录都静默跳过；「在用水」直接问真相源
     * [wateringStore]——init 的收集器还没发首帧时，界面状态可能落后于本地记账。
     */
    fun requestAutoOpen() {
        if (autoOpenRequested) return
        autoOpenRequested = true
        viewModelScope.launch {
            if (!_uiState.value.loggedIn) return@launch
            if (wateringStore.watering.value != null) return@launch
            if (_uiState.value.flow !is QzxyFlowState.Idle) return@launch
            openValveFromCard()
        }
    }

    private var autoOpenRequested = false

    /** 收起结算结果卡。 */
    fun dismissSettlement() = _uiState.update { it.copy(lastSettlement = null) }

    /**
     * 上次用过的设备。诊断页从开热水页进来时带着地址，单独打开（比如从「校园服务」）
     * 时没有地址可带，用它兜底——诊断的对象永远是「当前这台」。
     */
    fun lastUsedDevice(): QzxyBoundDevice? = deviceStore.lastUsed()

    /**
     * 把本地用水状态标记为已结束，不动设备也不动服务端。
     *
     * 出口的必要性：结算走不通时（设备上没记录、蓝牙连不上、官方 App 已经结过），
     * 本地那条「用水中」会一直挂着，每次进页面都提示去结算。用户需要一个
     * 「我知道，别提醒了」的开关。
     */
    fun abandonWatering() {
        wateringStore.clear()
        _uiState.update { it.copy(flow = QzxyFlowState.Idle, lastSettlement = null) }
        notice("已标记为结束，本地不再提示")
        pushWaterWidget()
    }

    /**
     * 设备显示名：服务端登记的名字（带楼栋楼层房间）优先，没有才退回广播名。
     *
     * 卡片副行、用水记账、页面标题都走这一处，免得同一个设备在三处叫三个名字。
     */
    private fun deviceDisplayName(device: QzxyScannedDevice): String =
        _uiState.value.deviceInfos[device.addressKey]?.deviceName?.takeIf { it.isNotBlank() }
            ?: _uiState.value.serverDevice?.deviceName?.takeIf { it.isNotBlank() }
            ?: device.name

    /**
     * 手动清掉设备上那条没清干净的消费记录。
     *
     * 记录留着，设备就卡在「消费完成，数据待采集」，之后每次开阀都会被挡。清除命令即
     * 官方 `CmdBtUtils.fanhuicunchu`（功能码 0x86），参数该带什么没有公开资料，
     * 所以按 [QzxyProtocol.clearCandidates] 的候选表依次试，优先用服务端上次签发的
     * `clData`（若那次结算拿到过）。
     */
    fun clearDeviceRecord() {
        val device = _uiState.value.selected ?: run {
            notice("请先选择设备", NoticeTone.Warning)
            return
        }
        viewModelScope.launch {
            if (!beginFlow()) return@launch
            try {
                _uiState.update { it.copy(timings = emptyList()) }
                // 先看状态：只有「消费完成，数据待采集」才有记录可清，
                // 否则发 0x85 设备只会回一句「没有数据」，用户看不出所以然
                val state = timed("读状态") { ensureDeviceState(device, forceRefresh = true) }
                    ?: return@launch
                if (state.deviceState != QzxyProtocol.STATE_FINISHED_UNCOLLECTED) {
                    fail(
                        "设备上没有待清除的记录",
                        "当前状态：${QzxyProtocol.stateText(state.deviceState)}",
                    )
                    return@launch
                }
                val record = timed("读记录") {
                    loadConsumeRecord(device, forceRefresh = true)
                } ?: return@launch
                val clDataPlain = _uiState.value.clDataPlainHex?.let { QzxyFrame.hexToBytes(it) }
                val outcome = timed("清除") {
                    runClearTrials(
                        device = device,
                        record = record,
                        clDataPlain = clDataPlain,
                        maxAttempts = CLEAR_TRIAL_LIMIT,
                    )
                }
                _uiState.update {
                    it.copy(
                        deviceState = if (outcome.cleared) null else it.deviceState,
                        flow = if (outcome.cleared) {
                            QzxyFlowState.Success("设备记录已清除（${outcome.label}），可以重新开阀了")
                        } else {
                            QzxyFlowState.Failed(
                                "清除命令没有被设备接受",
                                "${outcome.detail}。把诊断文本发出来，按设备回的错误码继续找参数",
                            )
                        },
                    )
                }
            } finally {
                valveMutex.unlock()
            }
        }
    }

    /**
     * 结束用水：从设备读回本次消费记录，再上报服务端结算。
     *
     * 步骤依据官方 `CmdBtUtils`：`caijishuju`（功能码 0x85）取回消费数据，
     * 把它的十六进制原文当 `xfData` 调 `order/upload/bluetooth/data`，
     * 最后 `fanhuicunchu`（0x86）清掉设备上的记录——不清的话下次开阀会提示
     * 「消费数据未采集」。
     *
     * 消费记录的字段偏移已经有两份独立来源印证（见 [QzxyProtocol.parseConsumption]），
     * 清除命令的参数则没有：官方那句注释只说「由采集到的消费记录对象生成」。
     * 所以清除走候选表（[QzxyProtocol.clearCandidates]），并**两层判定**——
     * 设备回包成功 + 回读状态离开「消费完成」——试通哪条就固定哪条。
     */
    fun stopWater() {
        val device = _uiState.value.selected ?: run {
            notice("请先选择设备", NoticeTone.Warning)
            return
        }
        viewModelScope.launch {
            if (!beginFlow()) return@launch
            try {
                _uiState.update { it.copy(timings = emptyList()) }
                var ready = timed("读状态") {
                    ensureDeviceState(device, forceRefresh = true)
                } ?: return@launch
                /** 轮询里连续读不到状态的次数，用来区分「设备还在结算」与「设备不理人了」。 */
                var pollFailures = 0

                if (ready.deviceState == QzxyProtocol.STATE_IDLE) {
                    // 空闲态既没有在放水也没有待结算的记录，发 0x22 只会被设备拒。
                    // 此时本地那条「用水中」是残留（上一次结算走通但本地没清，或者用户
                    // 在官方 App 里结了），一并清掉——不清的话卡片会一直显示在用水，
                    // 而设备这边什么都做不了
                    val stale = _uiState.value.watering != null
                    if (stale) {
                        wateringStore.clear()
                        pushWaterWidget()
                    }
                    fail(
                        "设备当前空闲",
                        if (stale) {
                            "没有在放水，也没有待结算的记录，本地的「用水中」已一并清除"
                        } else {
                            "没有在放水，也没有待结算的记录。要开热水点「开阀」"
                        },
                    )
                    return@launch
                }

                // 设备已经在「消费完成，数据待采集」时不能再发停止指令：实测设备回
                // `81 01`（错误码 1 包头错误），也就是「这个状态下不接受这条命令」。
                // 官方 App 的状态派发同样如此——状态 3 只走 caijishuju，不发 0x22。
                // 少了这个分支，卡着旧记录的用户一点「结束用水」就被挡在这里，
                // 永远走不到后面的结算与清除。
                if (ready.deviceState != QzxyProtocol.STATE_FINISHED_UNCOLLECTED) {
                    // 停阀。官方 CmdBtUtils.jieshufeilv 用的就是这条（功能码 0x22）。
                    // 少了它水不会停、设备也不会生成消费记录——之前直接去读消费数据，
                    // 设备还开着或已空闲都没有数据可回，表现成「等待回包超时」。
                    step("向设备发送停止指令")
                    val stop = timed("停阀") { sendFrame(QzxyProtocol.END_RATE) }
                        .getOrElse { error ->
                            fail("向设备发指令失败", error.message)
                            return@launch
                        }
                    if (stop != null && !stop.response.success) {
                        // 被拒不当场退出：设备可能已经结算完，0x22 自然不被接受。
                        // 记下来，由下面的轮询结果决定是继续还是报错。
                        log("停止指令被拒：${stop.response.summary}")
                    }

                    // 设备结算要几百毫秒到十几秒：实测停阀后先落到状态 6（结算中），
                    // 五秒以上才变 3。轮询到「消费完成，数据待采集」再往下走。
                    //
                    // 轮询走 readStateQuiet 而不是 ensureDeviceState：后者每轮都会把流程
                    // 文案改回「读取设备状态」，与「等待设备结算」来回切，界面看着像坏了。
                    // 这里只在状态真的变了的时候改一次文案。
                    step("等待设备结算")
                    timed("等结算") {
                        val deadline = SystemClock.elapsedRealtime() + STOP_POLL_TIMEOUT_MILLIS
                        var lastState = ready.deviceState
                        while (SystemClock.elapsedRealtime() < deadline) {
                            // 先问再等：设备往往在停阀回包到达时就已经结算完
                            val polled = readStateQuiet(device)
                            if (polled == null) {
                                if (++pollFailures >= STOP_POLL_MAX_FAILURES) break
                            } else {
                                pollFailures = 0
                                ready = polled
                                if (polled.deviceState == QzxyProtocol.STATE_FINISHED_UNCOLLECTED) {
                                    break
                                }
                                if (polled.deviceState != lastState) {
                                    lastState = polled.deviceState
                                    step("等待设备结算（${QzxyProtocol.stateText(polled.deviceState)}）")
                                }
                            }
                            delay(STOP_POLL_INTERVAL_MILLIS)
                        }
                    }
                }

                if (ready.deviceState != QzxyProtocol.STATE_FINISHED_UNCOLLECTED) {
                    if (pollFailures >= STOP_POLL_MAX_FAILURES) {
                        fail(
                            "读不到设备状态",
                            "蓝牙连着但设备连续 $pollFailures 次不回包，可能被官方 App 抢占或已断开。" +
                                "等几秒再点一次「结束用水」",
                        )
                        return@launch
                    }
                    val stillRunning = ready.deviceState == QzxyProtocol.STATE_IN_ORDER ||
                        ready.deviceState == QzxyProtocol.STATE_CARD_CONSUMING
                    fail(
                        if (stillRunning) "停阀指令没被接受" else "设备还没结算完",
                        if (stillRunning) {
                            "当前状态：${QzxyProtocol.stateText(ready.deviceState)}，水可能还在流。" +
                                "再点一次「结束用水」，还不行就把诊断发出来"
                        } else {
                            "当前状态：${QzxyProtocol.stateText(ready.deviceState)}。" +
                                "水已停，设备通常几秒内写完记录，过几秒再点一次「结束用水」"
                        },
                    )
                    return@launch
                }

                // 3) 采集 → 上报 → 清除
                val payload = timed("读记录") {
                    loadConsumeRecord(device, forceRefresh = true)
                } ?: return@launch
                val xfData = QzxyFrame.bytesToHex(payload)

                step("上报消费数据结算")
                val upload = timed("上报") {
                    runCatching {
                        repo.uploadConsume(
                            xfData = xfData,
                            // 用刚轮询到的那份：结算签名要当前交易的随机数，开阀前那份已经过期
                            randomNumber = ready.randomNumber,
                            protocolType = ready.protocolType.orEmpty(),
                        )
                    }
                }

                // 结算被拒也照样往下清记录：记录多半已经结算过（重复上报会被服务端拒），
                // 留着它会让后续开阀全被挡——这是「结束用水了还是不可开」的由来
                val clDataRaw = upload.getOrNull()?.clData
                val clDataPlain = QzxyClData.decrypt(clDataRaw)
                if (clDataRaw != null) {
                    log("clData ${clDataRaw.length} 字符 → ${QzxyClData.describe(clDataPlain)}")
                }
                _uiState.update {
                    it.copy(
                        clDataRaw = clDataRaw,
                        clDataPlainHex = clDataPlain?.let { plain -> QzxyFrame.bytesToHex(plain) },
                        clDataPlainText = clDataRaw?.let { QzxyClData.describe(clDataPlain) },
                    )
                }

                // 设备端记录要清掉，否则下次开阀会说「消费数据未采集」。
                // 参数按候选表依次试，试到哪条由 runClearTrials 自己改流程文案。
                val cleared = timed("清除") {
                    runClearTrials(
                        device = device,
                        record = payload,
                        clDataPlain = clDataPlain,
                        maxAttempts = CLEAR_TRIAL_LIMIT,
                    )
                }

                val result = upload.getOrNull()
                if (result == null) {
                    fail(
                        "结算失败",
                        "${upload.exceptionOrNull()?.message ?: "服务端没有返回结算结果"}；" +
                            "设备记录清除：${cleared.detail}",
                    )
                    return@launch
                }
                log("结算成功：consumeMoney=${result.consumeMoney} 厘，xfData ${payload.size} 字节")
                // 记账要在 clear 之前取：clear 会把 store 里的起点一并清掉
                val startedAt = _uiState.value.watering?.startedAtMillis
                wateringStore.clear()
                // 桌面趣智开水卡退出「用水中」
                pushWaterWidget()
                _uiState.update {
                    it.copy(
                        // 记录清掉后设备回到空闲；置空强制下次开阀重读，别用旧状态
                        deviceState = if (cleared.cleared) null else it.deviceState,
                        flow = QzxyFlowState.Idle,
                        lastSettlement = QzxySettlement(
                            consumeMoneyMilli = result.consumeMoney,
                            durationMillis = startedAt?.let { start ->
                                System.currentTimeMillis() - start
                            },
                            note = if (cleared.cleared) null else "设备记录没清掉：${cleared.detail}",
                        ),
                    )
                }
                notice(
                    "已结束用水，本次消费 ${milliYuanText(result.consumeMoney)}",
                    NoticeTone.Success,
                )
                // 结算改变了余额，也新增了一笔账单：两处都刷新，用户不用手动拉
                refreshAccount()
                loadBills()
            } finally {
                valveMutex.unlock()
            }
        }
    }

    /** 结算接口的 `consumeMoney` 单位是**厘**（0.04 元返回 40），换算口径在 domain 一处。 */
    private fun milliYuanText(raw: String?): String = QzxyWateringFormat.money(raw)

    /**
     * 拼一份诊断文本，供用户直接复制粘贴出来。
     *
     * 十六进制串太长，截图会被换行、还可能漏字符——排查协议时精确到一个字符的差异
     * 就能反转结论，所以留一个复制出口。
     */
    fun diagnosticText(): String {
        val state = _uiState.value
        return buildString {
            appendLine("趣智校园诊断")
            appendLine("设备: ${state.selected?.name ?: "-"} / ${state.selected?.address ?: "-"}")
            appendLine("服务端设备名: ${state.serverDevice?.deviceName ?: "-"}")
            appendLine("登记 MAC: ${state.serverDevice?.macAddress ?: "-"}（蓝牙地址 ${state.selected?.address ?: "-"}）")
            appendLine(
                "deviceId=${state.serverDevice?.deviceId} " +
                    "bigTypeId=${state.serverDevice?.bigTypeId} " +
                    "smallTypeId=${state.serverDevice?.smallTypeId} " +
                    "online=${state.serverDevice?.onlineStatusId} " +
                    "comm=${state.serverDevice?.communicationTypeId} " +
                    "migrated=${state.serverDevice?.isMigrated}",
            )
            appendLine(
                "蓝牙状态: ${state.deviceState?.deviceState}" +
                    " (${state.deviceState?.let { QzxyProtocol.stateText(it.deviceState) } ?: "-"})",
            )
            appendLine("随机数: ${state.deviceState?.randomNumber ?: "-"}")
            appendLine("协议版本: ${state.deviceState?.protocolType ?: "-"}")
            appendLine("状态包数据体: ${state.deviceState?.rawHex ?: "-"}")
            appendLine("消费数据数据体: ${state.consumeRaw ?: "-"}")
            appendLine("消费数据完整回包: ${state.consumeFrameRaw ?: "-"}")
            appendLine("消费记录要点: ${state.consumeSummary ?: "-"}")
            appendLine("清除命令结果: ${state.clearResult ?: "-"}")
            appendLine("clData 原文: ${state.clDataRaw ?: "-"}")
            appendLine("clData 解密: ${state.clDataPlainText ?: "-"}")
            if (state.clearTrials.isNotEmpty()) {
                appendLine("清除试错:")
                state.clearTrials.forEach { appendLine("  ${it.label} → ${it.result}") }
            }
            appendLine("MTU: ${link.negotiatedMtu}")
            appendLine(
                "downData: ${state.lastDownDataHex?.length?.div(2) ?: 0} 字节 / " +
                    (state.lastDownDataHex ?: "-"),
            )
            if (state.timings.isNotEmpty()) {
                appendLine("耗时: ${state.timings.joinToString("，")}")
            }
            appendLine("流程: ${state.flow}")
        }
    }

    /**
     * 组装下单参数。类型与协议版本优先取蓝牙读到的那份，HTTP 设备信息只作回退——
     * 官方走的是蓝牙查询结果，服务端校验的也是这一份。
     */
    private fun buildOrderRequest(
        device: QzxyScannedDevice,
        state: QzxyProtocol.DeviceState,
    ): QzxyRateOrderRequest {
        val info = _uiState.value.serverDevice
        val mainType = state.mainType ?: info?.bigTypeId?.toIntOrNull() ?: 0
        val subType = state.subType ?: info?.smallTypeId?.toIntOrNull() ?: 0
        // MAC 优先用服务端登记的那个：Android 上报的蓝牙地址可能以 C0 开头，
        // 而设备在服务端登记的是 00 开头。混用会让订单挂到「另一台设备」上，
        // 结算时就报「未找到订单」——见 device/info/mac 响应里的 macAddress 字段。
        val mac = (info?.macAddress?.takeIf { it.isNotBlank() } ?: device.address)
            .replace(":", "")
            .replace("-", "")
            .uppercase(java.util.Locale.ROOT)
        return QzxyRateOrderRequest(
            macAddress = mac,
            macTypeHex = "%02x%02x".format(mainType, subType),
            protocolType = state.protocolType.orEmpty(),
            deviceId = state.deviceId.toString(),
            bigTypeId = info?.bigTypeId ?: mainType.toString(),
            smallTypeId = info?.smallTypeId ?: subType.toString(),
            randomNumber = state.randomNumber,
        )
    }

    private fun step(text: String) {
        _uiState.update { it.copy(flow = QzxyFlowState.Working(text)) }
    }

    /**
     * 保证手上有设备现场状态：读过就直接用，否则连一次设备并读回。
     * 读不到返回 null，失败信息已经写进界面。
     *
     * 开阀不走这条缓存——开阀前必须重新读，设备状态与随机数都可能是旧的。
     */
    private suspend fun ensureDeviceState(
        device: QzxyScannedDevice,
        forceRefresh: Boolean = false,
    ): QzxyProtocol.DeviceState? {
        if (!forceRefresh) {
            _uiState.value.deviceState?.let { return it }
        }
        if (!ensureLink(device, announce = true)) return null
        step("读取设备状态")
        val reply = sendFrame(
            QzxyProtocol.QUERY_DEVICE,
            timeoutMillis = QUERY_TIMEOUT_MILLIS,
        ).getOrElse { error ->
            fail("向设备发指令失败", error.message)
            return null
        }
        if (reply == null) {
            fail("设备没有回应可解析的数据", "等待回包超时")
            return null
        }
        if (!reply.response.success) {
            fail("设备拒绝了查询命令", reply.response.summary)
            return null
        }
        val parsed = QzxyProtocol.parseDeviceState(reply.response.payload)
        if (parsed == null) {
            fail("设备没有回应可解析的数据", reply.response.summary)
            return null
        }
        _uiState.update { it.copy(deviceState = parsed) }
        return parsed
    }

    /**
     * 发一帧并解析回包，同时把收发原文写进调试日志。
     *
     * 为什么不再各处自己 `link.request` + `QzxyFrame.parse`：设备回包里带着
     * 成败与错误码，绕过 [QzxyProtocol.decodeResponse] 就等于把这些信息扔掉。
     * 清除命令连着失败三轮却始终看不到理由，问题就出在这里。
     *
     * 返回 `success(null)` 表示超时，`failure` 表示发送本身出错。
     */
    private suspend fun sendFrame(
        functionCode: Int,
        payload: ByteArray = ByteArray(1),
        timeoutMillis: Int = RESPONSE_TIMEOUT_MILLIS,
    ): Result<QzxyReply?> {
        val frame = QzxyFrame.encode(functionCode, payload)
        log("→ %02x %d 字节 ${QzxyFrame.bytesToHex(frame)}".format(functionCode, frame.size))
        val raw = link.request(frame, timeoutMillis).getOrElse { error ->
            log("← 发送失败：${error.message}")
            return Result.failure(error)
        }
        if (raw == null) {
            log("← 超时，设备没有回包")
            return Result.success(null)
        }
        val rawHex = QzxyFrame.bytesToHex(raw)
        log("← 原文 $rawHex")
        val response = QzxyProtocol.decodeResponse(QzxyFrame.unwrap(raw) ?: raw)
        if (response == null) {
            log("← 回包解不出帧结构")
            return Result.success(null)
        }
        if (response.functionCode != functionCode) {
            // 回包功能码对不上：多半是设备异步推的别的帧插到了前面。
            // 不能把它当这次命令的结果——那会把「上一帧的成功」读成「这次的成败」，
            // 清除命令尤其危险。当成没收到，由调用方按「设备没有回包」处理。
            log("← 功能码不符：期望 %02x，收到 %02x，这一帧丢掉".format(functionCode, response.functionCode))
            return Result.success(null)
        }
        log("← ${response.summary}")
        armIdleDisconnect()
        return Result.success(QzxyReply(raw = rawHex, response = response))
    }

    /**
     * 保证 GATT 链路可用。断过就重连一次——设备侧超时、被官方 App 抢占都会断，
     * 而 [QzxyGattLink.request] 在彻底没回应时会主动 `close()`，下一次必须重建。
     *
     * [announce] 为 false 时不写流程状态，只安静地失败，供试错循环用。
     */
    private suspend fun ensureLink(device: QzxyScannedDevice, announce: Boolean): Boolean {
        // 建链是整条链路最慢的一段（一到两秒），选中设备时已经在后台建过一次，
        // 这里多半直接复用。加锁是为了让「预连接」与用户操作不会同时去连同一个 GATT。
        return connectMutex.withLock {
            // 必须按地址判：连着另一台设备时 isConnected 也是 true，
            // 不复用会一路把指令发到别的设备上
            if (link.isConnectedTo(device.address)) {
                // 复用链路时把缓存的服务表也填进界面：跨窗口复用（面板里开阀连上、
                // 再进页面或诊断页）时这份表来自上一次连接，本来就该显示它
                if (_uiState.value.gattTable.isEmpty() && link.serviceTable.isNotEmpty()) {
                    _uiState.update { it.copy(gattTable = link.serviceTable) }
                }
                return@withLock true
            }
            link.close()
            if (announce) step("连接设备")
            val table = link.connect(device.address).getOrElse { error ->
                if (announce) fail("连接设备失败", error.message) else log("重连失败：${error.message}")
                return@withLock false
            }
            _uiState.update { it.copy(gattTable = table) }
            // 建链成功后同样要排空闲断连：BLE 外设被连着就不再广播，我们一直占着会让
            // 这台水表在别人（和官方 App）的扫描结果里消失。预连接那条路径本来就有，
            // 用户直接点开阀走的是这一条，之前漏了
            armIdleDisconnect()
            true
        }
    }

    /**
     * 后台先把 GATT 链路建起来。
     *
     * 开阀真正慢的是建链与服务发现，它跟「下单」没有依赖关系，可以提前做。
     * 官方 App 也是选中设备就连，点「开阀」时只剩一次 HTTP 加一次写入。
     *
     * 不动流程状态、失败不提示：用户点开阀时会走 [ensureLink] 正常重连并报错。
     */
    private fun preconnect(device: QzxyScannedDevice) {
        viewModelScope.launch {
            connectMutex.withLock {
                if (link.isConnectedTo(device.address)) return@withLock
                val startedAt = SystemClock.elapsedRealtime()
                link.connect(device.address)
                    .onSuccess { table ->
                        _uiState.update { it.copy(gattTable = table) }
                        log("预连接完成，${table.size} 项服务/特征值")
                        armIdleDisconnect()
                    }
                    .onFailure { error -> log("预连接失败：${error.message}") }
                log("预连接耗时 ${SystemClock.elapsedRealtime() - startedAt}ms")
            }
        }
    }

    /**
     * 强制重新建链，用于探测服务表。
     *
     * 与 [preconnect] 共用 [connectMutex]：预连接可能正在建链，两个 `connect` 撞在
     * 同一个 `BluetoothGatt` 上会互相拆台。
     */
    private suspend fun reconnect(device: QzxyScannedDevice): Result<List<QzxyGattCharacteristicInfo>> =
        connectMutex.withLock {
            link.close()
            link.connect(device.address)
        }

    /**
     * 重排空闲断连计时：一段时间没有命令就主动断开，把设备让出来。
     *
     * 为什么要断开：BLE 外设被连上后通常就停止广播了，我们一直占着链路，
     * 同一台水表在别人的扫描结果里会直接消失（官方 App 也扫不到）。设备是宿舍公用的，
     * 用完就该放回去。
     *
     * **放水中不断**：设备侧可能把断连当成「用户走了」，而且这段时间本来就该占着。
     * 代价是「开阀后一直没结算」会让链路留到进程结束，这一点与官方 App 在用水页
     * 期间持连一致。
     */
    private fun armIdleDisconnect() {
        idleDisconnectJob?.cancel()
        idleDisconnectJob = viewModelScope.launch {
            delay(IDLE_DISCONNECT_MILLIS)
            if (_uiState.value.watering != null) return@launch
            if (!link.isConnected) return@launch
            link.close()
            log("空闲 ${IDLE_DISCONNECT_MILLIS}ms，主动断开蓝牙，把设备让出来")
        }
    }

    /** 安静地读一次设备状态：不写流程、不弹提示，供试错循环判定用。读不到返回 null。 */
    private suspend fun readStateQuiet(device: QzxyScannedDevice): QzxyProtocol.DeviceState? {
        if (!ensureLink(device, announce = false)) return null
        val reply = sendFrame(
            QzxyProtocol.QUERY_DEVICE,
            timeoutMillis = QUERY_TIMEOUT_MILLIS,
        ).getOrNull() ?: return null
        if (!reply.response.success) return null
        return QzxyProtocol.parseDeviceState(reply.response.payload)?.also { parsed ->
            _uiState.update { it.copy(deviceState = parsed) }
        }
    }

    /**
     * 拿到设备上的消费记录。默认先看手上有没有上次读回的那条，没有就连设备发 0x85 读一次。
     *
     * 为什么清除入口要能自己读：记录只在设备处于「消费完成，数据待采集」时才有，
     * 而设备卡在那个状态恰恰是最需要清除的时候。让用户先去点一遍「结束用水」才肯清，
     * 等于把解套的办法藏起来。
     */
    private suspend fun loadConsumeRecord(
        device: QzxyScannedDevice,
        forceRefresh: Boolean = false,
    ): ByteArray? {
        if (!forceRefresh) {
            _uiState.value.consumeRaw
                ?.let { raw -> QzxyFrame.hexToBytes(raw) }
                ?.let { return it }
        }
        if (!ensureLink(device, announce = true)) return null
        step("向设备读取消费数据")
        val reply = sendFrame(QzxyProtocol.COLLECT_CONSUME).getOrElse { error ->
            fail("读取消费数据失败", error.message)
            return null
        }
        if (reply == null || !reply.response.success) {
            fail(
                "设备没有返回可解析的消费数据",
                reply?.response?.summary ?: "等待回包超时",
            )
            return null
        }
        val payload = reply.response.payload
        val record = QzxyProtocol.parseConsumption(payload)
        _uiState.update {
            it.copy(
                consumeRaw = QzxyFrame.bytesToHex(payload),
                consumeFrameRaw = reply.raw,
                consumeSummary = record?.let { parsed -> describeRecord(parsed) },
            )
        }
        log("消费记录：${record?.let { parsed -> describeRecord(parsed) } ?: "字段解析不出来，只有原始数据"}")
        return payload
    }

    private fun fail(reason: String, detail: String?) {
        log("失败：$reason / ${detail ?: "-"}")
        _uiState.update { it.copy(flow = QzxyFlowState.Failed(reason, detail)) }
        notice(reason, NoticeTone.Error)
    }

    // ── 调试日志 ──

    fun setDebugLogEnabled(value: Boolean) {
        debugStore.logEnabled = value
        _uiState.update {
            it.copy(debugLogEnabled = value, debugLog = if (value) it.debugLog else emptyList())
        }
    }

    fun clearDebugLog() = _uiState.update { it.copy(debugLog = emptyList()) }

    /**
     * 清除命令试错：把候选参数**全部**试一遍，逐条记录设备回包与回读状态。
     *
     * 功能码 0x86 已经由两份独立来源确认，所以只试参数。判定要同时看两件事：
     * 设备回包是不是成功（首字节 0x80），以及回读状态有没有离开「消费完成，数据待采集」。
     * 只看后者的话，「设备拒收」与「收下了但没清」两种失败分不出来。
     */
    fun probeClearCommands() {
        val device = _uiState.value.selected ?: run {
            notice("请先选择设备", NoticeTone.Warning)
            return
        }
        viewModelScope.launch {
            if (!beginFlow()) return@launch
            try {
                _uiState.update { it.copy(timings = emptyList()) }
                val state = timed("读状态") { ensureDeviceState(device, forceRefresh = true) }
                    ?: return@launch
                if (state.deviceState != QzxyProtocol.STATE_FINISHED_UNCOLLECTED) {
                    fail(
                        "设备上没有待清除的记录",
                        "当前状态：${QzxyProtocol.stateText(state.deviceState)}",
                    )
                    return@launch
                }
                val record = timed("读记录") {
                    loadConsumeRecord(device, forceRefresh = true)
                } ?: return@launch
                val clDataPlain = _uiState.value.clDataPlainHex?.let { QzxyFrame.hexToBytes(it) }
                val outcome = timed("清除试错") {
                    runClearTrials(
                        device = device,
                        record = record,
                        clDataPlain = clDataPlain,
                        maxAttempts = Int.MAX_VALUE,
                    )
                }
                _uiState.update {
                    it.copy(
                        deviceState = if (outcome.cleared) null else it.deviceState,
                        flow = if (outcome.cleared) {
                            QzxyFlowState.Success("可用清除命令：${outcome.label}")
                        } else {
                            QzxyFlowState.Failed(
                                "这些命令都没能清掉记录",
                                "${outcome.detail}。把诊断文本发出来，按设备回的错误码继续找参数",
                            )
                        },
                    )
                }
            } finally {
                valveMutex.unlock()
            }
        }
    }

    /** 一次清除流程的最终结果。 */
    private data class ClearOutcome(val cleared: Boolean, val label: String?, val detail: String)

    /**
     * 按候选表依次试清除命令，逐条写进 `clearTrials`，返回第一条真正生效的。
     *
     * 判定分两层：先看设备对 0x86 的回包，再回读状态确认记录真的没了。
     * 只回读状态的话，「设备拒收」和「收下了但没清」长得一模一样，
     * 这也是前几轮排查一直没进展的原因。
     */
    private suspend fun runClearTrials(
        device: QzxyScannedDevice,
        record: ByteArray,
        clDataPlain: ByteArray?,
        maxAttempts: Int,
    ): ClearOutcome {
        // 上次试通的那条排最前：每条候选要一次往返加一次回读，命中排在后面就是白等
        val candidates = QzxyProtocol.clearCandidates(
            record = record,
            clDataPlain = clDataPlain,
            preferredKey = clearStore.preferredKey,
        ).take(maxAttempts)
        val trials = mutableListOf<SignatureTrial>()
        _uiState.update { it.copy(clearTrials = emptyList(), clearResult = null) }
        var last = "没有可试的参数"

        for ((index, candidate) in candidates.withIndex()) {
            step("试清除 ${index + 1}/${candidates.size}：${candidate.label}")

            // 设备没回应时 link 会自己断开，下一轮必须先重连，否则一路报「蓝牙未连接」
            if (!ensureLink(device, announce = false)) {
                last = "${candidate.label}：蓝牙重连失败"
                trials += SignatureTrial(candidate.label, last, false)
                _uiState.update { it.copy(clearTrials = trials.toList(), clearResult = last) }
                continue
            }

            val reply = sendFrame(
                candidate.functionCode,
                candidate.payload,
                CLEAR_TIMEOUT_MILLIS,
            ).getOrNull()
            if (reply == null) {
                last = "${candidate.label}：设备没有回包"
            } else if (!reply.response.success) {
                last = "${candidate.label}：设备拒收，" +
                    "错误码 ${reply.response.errorCode}（${reply.response.errorText}）"
            } else {
                delay(CLEAR_VERIFY_DELAY_MILLIS)
                val after = readStateQuiet(device)
                val cleared = after != null &&
                    after.deviceState != QzxyProtocol.STATE_FINISHED_UNCOLLECTED
                last = if (cleared) {
                    "${candidate.label}：设备已回到空闲"
                } else {
                    "${candidate.label}：设备回了成功，但记录还在（" +
                        (after?.let { QzxyProtocol.stateText(it.deviceState) } ?: "状态读不到") + "）"
                }
                trials += SignatureTrial(candidate.label, last, cleared)
                _uiState.update { it.copy(clearTrials = trials.toList(), clearResult = last) }
                if (cleared) {
                    clearStore.preferredKey = candidate.key
                    _uiState.update { it.copy(deviceState = null) }
                    return ClearOutcome(true, candidate.label, last)
                }
                continue
            }

            trials += SignatureTrial(candidate.label, last, false)
            _uiState.update { it.copy(clearTrials = trials.toList(), clearResult = last) }
        }
        return ClearOutcome(false, null, last)
    }

    /** 消费记录的要点，诊断文本与日志共用。 */
    private fun describeRecord(record: QzxyProtocol.ConsumptionRecord): String = buildString {
        append("时间序号 ").append(record.timeId)
        append("，消费 ").append(record.consumeMoney).append(" 厘")
        append("，预扣 ").append(record.preDeductMoney).append(" 厘")
        append("，账号 ").append(record.accountId)
        append("，MAC ").append(record.macAddress)
        append("，").append(record.rawHex.length / 2).append(" 字节")
        record.tac?.let { append("，校验码 ").append(it) }
    }

    /**
     * 下单成功、`downData` 却没写进设备时的提示。
     *
     * 这一刻服务端已经建单并预扣，设备没出水。用户重试会再下一单，而服务端对
     * 「同一台设备还有进行中的订单」会拒（错误码 307），那一笔预扣却已经挂在账上。
     * 所以要把话说清楚：别连点，先去账单核对。
     */
    private fun preDeductDetail(cause: String?): String =
        "${cause ?: "原因未知"}。服务端那边已经下单并预扣，设备却没出水。" +
            "别连点重试——重试可能被服务端以「设备正在使用中」拒绝，而预扣还挂着。" +
            "先在「消费记录」里核对有没有这一单，再用官方 App 处理"

    // ── 计时与前置检查 ──

    /** 记一段耗时。诊断文本里列出来，用来判断慢在建链、蓝牙往返还是 HTTP。 */
    private fun recordTiming(label: String, millis: Long) {
        _uiState.update { it.copy(timings = it.timings + "$label ${millis}ms") }
    }

    /** 包一段耗时。抛异常也记：失败的那一段同样是排查线索。 */
    private suspend fun <T> timed(label: String, block: suspend () -> T): T {
        val startedAt = SystemClock.elapsedRealtime()
        try {
            return block()
        } finally {
            recordTiming(label, SystemClock.elapsedRealtime() - startedAt)
        }
    }

    /**
     * 确保手上有服务端登记的设备信息。
     *
     * 下单要用**登记 MAC** 而不是蓝牙广播地址，缺了它订单会挂到「另一台设备」上，
     * 结算时报「未找到订单」。选中设备时已经查过一次，这里只是兜底：
     * 查不到或没有登记地址就返回 null，由调用方报错，别拿广播地址凑数。
     */
    private suspend fun ensureServerDevice(device: QzxyScannedDevice): QzxyDeviceInfo? {
        _uiState.value.serverDevice
            ?.takeIf { !it.macAddress.isNullOrBlank() }
            ?.let { return it }
        val info = runCatching { repo.deviceInfo(device.address) }.getOrNull() ?: return null
        if (info.macAddress.isNullOrBlank()) return null
        _uiState.update {
            it.copy(
                serverDevice = info,
                deviceInfos = it.deviceInfos + (device.addressKey to info),
            )
        }
        return info
    }

    // ── 消费记录 ──

    /** 拉某个月的消费记录，月份格式 `yyyy-MM`。默认当月。 */
    fun loadBills(month: String = currentMonth()) {
        if (!_uiState.value.loggedIn) return
        viewModelScope.launch {
            runCatching {
                _uiState.update { it.copy(loadingBills = true, billMonth = month) }
                repo.billList(month)
            }.onSuccess { list ->
                _uiState.update {
                    it.copy(bills = list, loadingBills = false, billLoaded = true)
                }
            }.onFailure { error ->
                _uiState.update { it.copy(loadingBills = false, billLoaded = true) }
                if (error is QzxySessionExpiredException) {
                    handleFailure(error, "登录已失效")
                } else {
                    notice(error.message ?: "查询消费记录失败", NoticeTone.Error)
                }
            }
        }
    }

    /**
     * 进页面时拉一次当月消费记录。
     *
     * 已经有数据或正在拉就不重复请求：页面每次重建都会调它，不去重的话
     * 「进页 → 退出 → 再进」就是一次月账单请求，纯浪费。
     */
    fun loadBillsOnce() {
        if (_uiState.value.billLoaded || _uiState.value.loadingBills) return
        loadBills()
    }

    /** 翻月份：-1 上一月，+1 下一月。 */
    fun shiftBillMonth(delta: Long) {
        val base = _uiState.value.billMonth.ifBlank { currentMonth() }
        val next = runCatching { YearMonth.parse(base).plusMonths(delta).toString() }.getOrNull()
            ?: return
        loadBills(next)
    }

    /** 开关开着才记。内容够定位问题即可，不写流水账。 */
    private fun log(message: String) {
        if (!_uiState.value.debugLogEnabled) return
        val stamp = DEBUG_TIME_FORMAT.format(Date())
        _uiState.update {
            it.copy(debugLog = (it.debugLog + "[$stamp] $message").takeLast(DEBUG_LOG_LIMIT))
        }
    }

    /** 会话失效统一回登录态；其余错误只提示，不把用户踢下线。 */
    private fun handleFailure(error: Throwable, fallback: String) {
        if (error is QzxySessionExpiredException) {
            repo.logout()
            link.close()
            scanner.stopScan()
            // 用 loggedOutState 而不是空状态：会话失效不等于「这台机器上什么都没发生过」，
            // 用水中、已绑定设备、调试开关都得留着（与 syncLoginState 的登出分支同一口径）。
            // 用空状态还有个副作用：loggedIn 立刻变成 false，随后那条登录态流比较后直接
            // 返回，loggedOutState 永远轮不到执行
            _uiState.update { loggedOutState(it) }
            notice("登录已失效，请重新登录", NoticeTone.Warning)
        } else {
            notice(error.message ?: fallback, NoticeTone.Error)
        }
    }

    override fun onCleared() {
        scanner.stopScan()
        link.close()
        super.onCleared()
    }

    class Factory(
        private val repo: QzxyRepository,
        private val scanner: QzxyBluetoothScanner,
        /** 由 [edu.jxslu.schedule.Graph.qzxyLink] 提供，进程内一份，见那边的说明。 */
        private val link: QzxyGattLink,
        private val flowLock: Mutex,
        private val deviceStore: QzxyDeviceStore,
        private val debugStore: QzxyDebugStore,
        private val clearStore: QzxyClearStore,
        private val wateringStore: QzxyWateringStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            QzxyViewModel(
                repo,
                scanner,
                link,
                flowLock,
                deviceStore,
                debugStore,
                clearStore,
                wateringStore,
            ) as T
    }

    private companion object {
        /**
         * 停阀后等设备结算的总时长与轮询间隔。
         *
         * 预算给到 12 秒是因为实测出现过状态 6（结算中）持续五秒以上的情况
         * （2026-09-27）：按老代码 5 秒就放弃，用户得手动再点一次「结束用水」。
         *
         * 循环是「先查再等」：停阀回包到达时设备往往已经结算完，原来固定先睡 600ms
         * 等于每次白等一档。间隔 300ms 也是同一个道理，查询本身只要两三百毫秒。
         */
        const val STOP_POLL_TIMEOUT_MILLIS = 12_000L
        const val STOP_POLL_INTERVAL_MILLIS = 300L

        /**
         * 轮询里连续读不到状态的次数上限。到了就认定设备不理人（被抢占或断开），
         * 提前收手并照实报，别把 12 秒预算耗完再给一句「设备还没结算完」。
         */
        const val STOP_POLL_MAX_FAILURES = 4

        /** 查询设备帧的回包通常几百毫秒就到，给 1.5 秒足够，不必按默认的 5 秒干等。 */
        const val QUERY_TIMEOUT_MILLIS = 1_500

        /** 一般命令的回包超时，与 [QzxyGattLink] 的默认值一致。 */
        const val RESPONSE_TIMEOUT_MILLIS = 5_000

        /**
         * 日常流程里清除命令最多试几条。候选表把服务端凭据排在前面，正常一两条就中；
         * 试错入口不受这个限制，它要跑完整张表。
         */
        const val CLEAR_TRIAL_LIMIT = 3

        /** 发完清除命令后等设备处理的时间，再回读状态校验。 */
        const val CLEAR_VERIFY_DELAY_MILLIS = 300L

        /** 清除命令的回包超时。候选表最多九条，超时给长了会把试错拖成好几分钟。 */
        const val CLEAR_TIMEOUT_MILLIS = 2_000

        /**
         * 空闲多久主动断开蓝牙。给 60 秒：选设备到点开阀通常几秒内完成，
         * 预连接的收益还在；而一直占着链路会让这台水表在别人（和官方 App）的
         * 扫描结果里消失。
         */
        const val IDLE_DISCONNECT_MILLIS = 60_000L

        /** 调试日志上限，够翻一次现场即可。 */
        const val DEBUG_LOG_LIMIT = 200
        val DEBUG_TIME_FORMAT = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)

        /** 当前月份，`yyyy-MM`。 */
        fun currentMonth(): String = YearMonth.now().toString()
    }
}
