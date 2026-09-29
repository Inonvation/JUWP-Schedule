package edu.jxslu.schedule.ui.ebike

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.prefs.DisplayPrefsStore
import edu.jxslu.schedule.domain.EbikeQr
import edu.jxslu.schedule.domain.EbikeFreeRide
import edu.jxslu.schedule.domain.EbikeUseMode
import edu.jxslu.schedule.domain.ThemeMode
import edu.jxslu.schedule.ui.common.NoticeTone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

data class EbikeUiState(
    /**
     * 输入框里的车号原文（只允许数字进这个字段，UI 层过滤）。
     * 1~3 位 = 校园车队尾部；6~12 位 = 完整车号（地图选中的车是这一形态）。
     */
    val carInput: String = "",
    /** 已生成的二维码（null = 未生成）；与 [generatedBikeId] 成对。 */
    val generatedBitmap: Bitmap? = null,
    /** 已生成的完整车号（`100000669` 形态），存相册命名与提示用。 */
    val generatedBikeId: String? = null,
    /**
     * 当前这张码是否已进相册（自动保存或手动保存成功）。
     * 点「打开微信扫一扫」时据此决定要不要先补存一次——单机用户只能靠微信「相册」选图扫码，
     * 没存就跳过去等于让他白跑一趟。生成新车号时重置。
     */
    val generatedSaved: Boolean = false,
    /** 输入校验行内提示；null = 无。 */
    val inputError: String? = null,
)

/**
 * 页面级偏好快照（自动保存/扫完即焚开关、深浅色、最近车号）。
 *
 * 定位是「界面视图 + 兜底值」：它由 `stateIn` 缓存，在 DataStore 首次发射前是这里的
 * 默认值（冷启动首帧一定命中），**拿它做行为判定会在「进页即出码」这类首帧路径上读错**
 * ——自动保存被静默跳过、深色主题出一张白底码。需要真值的地方
 * （[EbikeViewModel.generate] / [EbikeViewModel.saveCurrent] / [EbikeViewModel.burnPending]）
 * 一律读 `DisplayPrefsStore` 的原始流，快照只在读失败时当兜底。
 */
data class EbikePrefsSnapshot(
    val autoSave: Boolean = false,
    val burnAfterScan: Boolean = true,
    val dark: Boolean = false,
    val recentIds: List<String> = emptyList(),
    /** 免费时长提醒开关（DESIGN §3.9）。 */
    val freeReminderEnabled: Boolean = false,
    /** 免费时长提前量（分钟）。 */
    val freeLeadMinutes: Int = EbikeFreeRide.DEFAULT_LEAD_MINUTES,
    /** 本次骑行计时起点（epoch 毫秒）；0 = 无进行中计时。 */
    val rideStartAt: Long = 0L,
    /** 「精确倒计时」开关（DESIGN §3.9）：识别微信租车成功通知校准起点，默认关。 */
    val preciseCountdownEnabled: Boolean = false,
    /**
     * 使用方式（DESIGN §3.9 / §4.32）：小程序方式 / 账号登录。页面按它隔离能力，
     * 默认 [EbikeUseMode.Default]（小程序方式，与存储默认一致）。
     */
    val useMode: EbikeUseMode = EbikeUseMode.Default,
)

sealed interface EbikeEvent {
    /** 一次性结果提示（保存成功/失败等）；[tone] 决定提示语气。 */
    data class Notice(val text: String, val tone: NoticeTone) : EbikeEvent
}

/**
 * 共享单车出码（DESIGN §3.9 / §4.18）。
 *
 * 生成 = 拼 URL（[EbikeQr.bikeUrl] 校验，非法输入不出码）→ zxing 矩阵 → 位图；
 * 自动保存开关开着时，生成即落相册（后台线程，结果经 [events] 提示）；
 * 扫完即焚开着时，保存成功记录待焚毁 key，删除时机见 [burnPending]（免费时长结束 /
 * 手动结束骑行 / 无计时回 App 兜底）；最近车号历史随生成更新（DataStore，上限 8）。
 * 二维码内容不含个人信息，历史也不出本机。
 */
class EbikeViewModel(
    private val prefs: DisplayPrefsStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(EbikeUiState())
    val uiState: StateFlow<EbikeUiState> = _uiState.asStateFlow()

    private val _events = Channel<EbikeEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** 一次性提示的统一出口（失败路径给 [NoticeTone.Error]/[NoticeTone.Warning]）。 */
    private fun notice(text: String, tone: NoticeTone = NoticeTone.Info) {
        _events.trySend(EbikeEvent.Notice(text, tone))
    }

    /**
     * 使用方式的**首帧真值**（DESIGN §3.9 / §4.32）：页面按它决定露出哪一套能力
     * （小程序方式的「打开微信扫一扫」还是账号方式的「直接开锁」），首帧给错会让按钮
     * 先按另一档画一帧再翻过来——那是看得见的闪。
     *
     * 阻塞读一次，与 `MeViewModel.initialPrefs` / 设置页 `runBlocking { ... .first() }`
     * 同一模式：DataStore 读过一次后常驻内存，代价是一次内存读。
     * **它只用于首帧渲染**：行为判定一律读 DataStore 原始流（`BikeMapViewModel.accountMode`
     * 那一套），不读这个快照。
     */
    private val initialUseMode: EbikeUseMode = runBlocking {
        runCatching { prefs.ebikeUseMode.first() }.getOrDefault(EbikeUseMode.Default)
    }

    /** 骑行相关偏好（卡开关不归本页管，其余在本页用）。免费提醒四个流合进快照。 */
    val ebikePrefs: StateFlow<EbikePrefsSnapshot> = combine(
        prefs.ebikeAutoSave,
        prefs.ebikeBurnAfterScan,
        prefs.themeMode,
        prefs.ebikeRecentIds,
        prefs.ebikeFreeReminderEnabled,
        prefs.ebikeFreeLeadMinutes,
        prefs.ebikeRideStartAt,
        prefs.ebikePreciseCountdownEnabled,
        prefs.ebikeUseMode,
    ) { array ->
        val autoSave = array[0] as Boolean
        val burnAfterScan = array[1] as Boolean
        val themeMode = array[2] as ThemeMode
        @Suppress("UNCHECKED_CAST")
        val recent = array[3] as List<String>
        val freeEnabled = array[4] as Boolean
        val freeLead = array[5] as Int
        val rideStartAt = array[6] as Long
        val preciseEnabled = array[7] as Boolean
        val useMode = array[8] as EbikeUseMode
        EbikePrefsSnapshot(
            autoSave = autoSave,
            burnAfterScan = burnAfterScan,
            dark = themeMode == ThemeMode.Dark,
            recentIds = recent,
            freeReminderEnabled = freeEnabled,
            freeLeadMinutes = freeLead,
            rideStartAt = rideStartAt,
            preciseCountdownEnabled = preciseEnabled,
            useMode = useMode,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        EbikePrefsSnapshot(useMode = initialUseMode),
    )

    /** 免费提醒设置变更（开关/提前量）→ 重算：补发该发的、重排闹钟、起停服务。 */
    fun onFreeReminderChanged() {
        viewModelScope.launch {
            emitReminderNotice(EbikeFreeRideReminder.check(Graph.appContext))
        }
    }

    /** 通知权限授予后的续跑：把进行中的计时的常驻倒计时与提醒补上（开关开着才做）。 */
    fun onReminderPermissionGranted() {
        viewModelScope.launch {
            emitReminderNotice(EbikeFreeRideReminder.check(Graph.appContext))
        }
    }

    /**
     * 点「打开微信扫一扫」：**先把码补存进相册**（除非已经存过），再记起点、起常驻倒计时、
     * 排两个精确提醒（换车再点 = 重新计时）。
     *
     * 补存是 2026-09-28 加的动线补全：单机用户到微信只能走「扫一扫 → 相册」选图，
     * 没存就跳过去等于让他白跑（旧版只在「生成后自动保存」开着时才存）。
     */
    fun onWechatScanClicked() {
        viewModelScope.launch {
            var saveText: String? = null
            var saveOk = true
            if (!_uiState.value.generatedSaved) {
                when (val saved = saveCurrentInternal()) {
                    is EbikeQrBitmaps.SaveResult.Saved ->
                        saveText = "已存入相册，在微信里点「相册」选图"
                    is EbikeQrBitmaps.SaveResult.Failed -> {
                        saveText = saved.message
                        saveOk = false
                    }
                    null -> Unit
                }
            }
            val (timerText, timerTone) = startFreeRideNotice()
            _events.send(
                EbikeEvent.Notice(
                    text = if (saveText == null) timerText else "$saveText；$timerText",
                    tone = if (saveOk) timerTone else NoticeTone.Warning,
                ),
            )
        }
    }

    /** 结束骑行：清起点、撤闹钟、停常驻倒计时、清通知栏上的提醒，并焚毁已保存的二维码。 */
    fun onEndRide() {
        viewModelScope.launch {
            val outcome = EbikeFreeRideReminder.endRide(Graph.appContext)
            burnPending(force = true)
            _events.send(
                when (outcome) {
                    is EbikeFreeRideReminder.Outcome.Failed ->
                        EbikeEvent.Notice("已结束骑行；提醒清理失败：${outcome.message}", NoticeTone.Warning)
                    else -> EbikeEvent.Notice("已结束骑行", NoticeTone.Info)
                },
            )
        }
    }

    /** 提醒类动作的结果 → 一次性提示；[EbikeFreeRideReminder.Outcome.Nothing] 不出声。 */
    private suspend fun emitReminderNotice(outcome: EbikeFreeRideReminder.Outcome) {
        val notice = when (outcome) {
            EbikeFreeRideReminder.Outcome.Started ->
                EbikeEvent.Notice("免费时长提醒已生效", NoticeTone.Success)
            EbikeFreeRideReminder.Outcome.StartedNoNotification ->
                EbikeEvent.Notice("通知被关闭，免费时长提醒发不出来", NoticeTone.Warning)
            EbikeFreeRideReminder.Outcome.StartedSilent ->
                EbikeEvent.Notice("免费时长提醒已关闭", NoticeTone.Info)
            EbikeFreeRideReminder.Outcome.Ended ->
                EbikeEvent.Notice("已清空免费时长提醒", NoticeTone.Info)
            is EbikeFreeRideReminder.Outcome.Failed ->
                EbikeEvent.Notice("提醒排程失败：${outcome.message}", NoticeTone.Warning)
            EbikeFreeRideReminder.Outcome.Nothing -> null
        }
        notice?.let { _events.send(it) }
    }

    /**
     * 输入车号：只留数字、限长，口径在 [EbikeQr.normalizeCarInput]（纯 JVM 可测）。
     */
    fun onCarInput(value: String) {
        val filtered = EbikeQr.normalizeCarInput(value)
        _uiState.update { it.copy(carInput = filtered, inputError = null) }
    }

    /**
     * 点击最近车号 chip：回填**并直接出码**（2026-09-28 用户拍板）。
     * chip 的意图就是"再出一张上次那张码"，旧版只回填、还要再点一次生成，
     * 而且回填发生在页面下半部、输入框在上方，用户看不见任何反馈。
     */
    fun onPickRecent(carNum: String) = fillAndGenerate(carNum)

    /**
     * 地图页选中的车（DESIGN §3.9）：回填完整车号并立即出码。
     * 车号来自运营方接口，仍走一遍 [EbikeQr.bikeUrl] 校验，脏数据不出一张扫不开的码。
     */
    fun onPickCarNum(carNum: String) = fillAndGenerate(carNum)

    /** 回填车号并立即出码（地图选车与最近 chip 共用；非法车号静默忽略）。 */
    private fun fillAndGenerate(carNum: String) {
        if (EbikeQr.bikeUrl(carNum) == null) return
        _uiState.update { it.copy(carInput = carNum, inputError = null) }
        generate()
    }

    /**
     * 生成二维码。非法车号只给行内提示，不发事件；合法则出码、
     * 视自动保存开关落相册、并写最近历史。
     */
    fun generate() {
        val carNum = EbikeQr.resolveCarNum(_uiState.value.carInput)
        val url = carNum?.let { EbikeQr.bikeUrl(it) }
        if (carNum == null || url == null) {
            _uiState.update {
                it.copy(inputError = EbikeQr.INPUT_HINT)
            }
            return
        }
        viewModelScope.launch {
            // 开关真值一律读 DataStore 原始流，**不要**读 [ebikePrefs] 的 stateIn 快照：
            // 快照在 DataStore 首次发射前是默认值（autoSave=false、dark=false），而
            // 「进页即出码」（今日页地图选车带 carNum 进页、进程被回收后恢复出码页）
            // 会在首帧就调到这里——那时快照还没发射，自动保存会被静默跳过（用户看不到
            // 码没进相册，也没有任何提示），深色主题还会出一张白底码。与 [burnPending]
            // 读原始流是同一个理由。读失败退回快照兜底：出码是主操作，不能被偏好存储牵连。
            val snapshot = ebikePrefs.value
            val autoSave = runCatching { prefs.ebikeAutoSave.first() }
                .getOrDefault(snapshot.autoSave)
            val dark = runCatching { prefs.themeMode.first() == ThemeMode.Dark }
                .getOrDefault(snapshot.dark)
            val bitmap = withContext(Dispatchers.Default) {
                EbikeQrBitmaps.render(EbikeQr.qrMatrix(url), dark)
            }
            _uiState.update {
                it.copy(generatedBitmap = bitmap, generatedBikeId = carNum, generatedSaved = false)
            }
            if (autoSave) saveCurrent()
            viewModelScope.launch {
                prefs.updateEbikeRecentIds { EbikeQr.mergeRecent(it, carNum) }
            }
        }
    }

    /**
     * 手动把当前展示的码存相册（自动保存关闭时的兜底动作，也是「扫一扫」前的补存入口）。
     * 保存成功即标记 [EbikeUiState.generatedSaved]；扫完即焚开着时记录待焚毁 key。
     */
    fun saveCurrent() {
        viewModelScope.launch {
            when (val result = saveCurrentInternal()) {
                is EbikeQrBitmaps.SaveResult.Saved ->
                    _events.send(EbikeEvent.Notice("已保存到相册「水贝贝」", NoticeTone.Success))
                is EbikeQrBitmaps.SaveResult.Failed ->
                    _events.send(EbikeEvent.Notice(result.message, NoticeTone.Error))
                null -> Unit
            }
        }
    }

    /**
     * 保存当前码；返回 null = 当前没有可保存的码。不发声，由调用方决定提示文案
     * （[saveCurrent] 用「已保存到相册」，[onWechatScanClicked] 用「已存入相册…选图」）。
     */
    private suspend fun saveCurrentInternal(): EbikeQrBitmaps.SaveResult? {
        val state = _uiState.value
        val bitmap = state.generatedBitmap ?: return null
        val bikeId = state.generatedBikeId ?: return null
        val appContext = Graph.appContext
        val result = withContext(Dispatchers.IO) {
            EbikeQrBitmaps.saveToGallery(appContext, bitmap, bikeId)
        }
        if (result is EbikeQrBitmaps.SaveResult.Saved) {
            // 同 [generate]：开关真值读原始流。快照在冷启动首帧还是默认值（true），
            // 用户明明关掉了焚毁也会被记上待焚毁 key；读失败按「不记焚毁」兜底
            // （宁可在相册留一张码，也不做没把握的删除）
            val burn = runCatching { prefs.ebikeBurnAfterScan.first() }.getOrDefault(false)
            if (burn) {
                prefs.updateEbikePendingDelete {
                    EbikeQr.mergePendingDelete(it, result.pendingKey)
                }
            }
            // 只在「保存的还是当前这张」时记已存：保存期间用户换了车号就不算
            _uiState.update { if (it.generatedBikeId == bikeId) it.copy(generatedSaved = true) else it }
        }
        return result
    }

    /**
     * 扫完即焚（DESIGN §3.9）：删除记录在案的待焚毁二维码——见 [burnPendingCodes]。
     * 触发时机：免费时长结束（`EbikeFreeRideReminder.check`）、手动结束骑行（[onEndRide]）、
     * 无计时在案时回 App（页面 ON_RESUME 兜底）。
     */
    fun burnPending(force: Boolean = false) {
        viewModelScope.launch {
            val deleted = burnPendingCodes(Graph.appContext, prefs, force)
            if (deleted > 0) {
                _events.send(
                    EbikeEvent.Notice("已清除存入相册的二维码（扫完即焚）", NoticeTone.Success),
                )
            }
        }
    }

    /** 离开页面时清掉大位图引用，别等 GC 兜底。 */
    override fun onCleared() {
        _uiState.value.generatedBitmap?.recycle()
        super.onCleared()
    }

    class Factory(private val prefs: DisplayPrefsStore) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            // 本机用车（查骑行状态 / 开锁 / 锁车 / 还车）归**地图页那一个** `KvcxRideController`
            // （`BikeMapViewModel.kvcx`）：快趣出行页是它唯一的消费者，这里不再养第二个实例
            EbikeViewModel(prefs) as T
    }
}

/** 焚毁的进程级互斥：出码页与地图页可能同时触发（还车 + 回页兜底），进行中的不叠跑。 */
private val burnGate = Mutex()

/**
 * 扫完即焚（DESIGN §3.9）：删除所有记录在案的待焚毁二维码，成功才移出记录，
 * 返回实际删掉的条数（0 = 什么都没删，调用方据此决定要不要出声）。
 * 开关关闭时不删不清——关掉 = 完全回到旧语义。
 *
 * [force] = false 时先看计时：**免费时长还在跑就不删**（图要留着反复扫），删除交给
 * `EbikeFreeRideReminder.check`（免费结束闹钟/周期核对）或手动结束骑行；无计时在案
 * （没点「打开微信扫一扫」）才维持「回 App 即删」——码是开锁耗材，没有计时段落兜着
 * 就不能留在相册。[force] = true 用于手动结束骑行 / 还车，无条件删。
 *
 * 2026-09-28 从 [EbikeViewModel] 抽成顶层函数：地图页还车也要走同一条口径
 * （「骑完车自动删除」不该因为从哪个页面还车而不同）。
 */
internal suspend fun burnPendingCodes(
    appContext: Context,
    prefs: DisplayPrefsStore,
    force: Boolean,
): Int {
    if (!burnGate.tryLock()) return 0
    try {
        // 开关真值必须读原始流：ebikePrefs 的 stateIn 快照在 DataStore
        // 首次发射前是默认值（true），冷启动恢复的首帧竞态下会误删
        // 「用户已关闭焚毁」时留下的记录。
        if (!prefs.ebikeBurnAfterScan.first()) return 0
        if (!force) {
            val startAt = prefs.ebikeRideStartAt.first()
            if (EbikeFreeRide.isActive(startAt, System.currentTimeMillis())) return 0
        }
        var total = 0
        while (true) {
            val pending = prefs.ebikePendingDelete.first()
            if (pending.isEmpty()) break
            val deleted = pending.filter { key ->
                withContext(Dispatchers.IO) { EbikeQrBitmaps.deletePending(appContext, key) }
            }
            prefs.updateEbikePendingDelete { it - deleted.toSet() }
            if (deleted.isEmpty()) break // 全部失败（如文件已不在），保留记录别空转
            total += deleted.size
            if (deleted.size == pending.size) break
            // 有失败项：下轮重试剩余的；连续失败会在下一轮走 break
        }
        return total
    } finally {
        burnGate.unlock()
    }
}
