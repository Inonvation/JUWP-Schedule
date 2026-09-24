package edu.jxslu.schedule.ui.ebike

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.prefs.DisplayPrefsStore
import edu.jxslu.schedule.domain.EbikeQr
import edu.jxslu.schedule.domain.EbikeFreeRide
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
    /** 输入校验行内提示；null = 无。 */
    val inputError: String? = null,
)

/** 页面级偏好快照（自动保存/扫完即焚开关、深浅色、最近车号）。 */
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
 * 扫完即焚开着时，保存成功记录待焚毁 key，回到 App（页面 ON_RESUME）后
 * 由 [burnPending] 从相册删除；最近车号历史随生成更新（DataStore，上限 8）。
 * 二维码内容不含个人信息，历史也不出本机。
 */
class EbikeViewModel(private val prefs: DisplayPrefsStore) : ViewModel() {

    private val _uiState = MutableStateFlow(EbikeUiState())
    val uiState: StateFlow<EbikeUiState> = _uiState.asStateFlow()

    private val _events = Channel<EbikeEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

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
        EbikePrefsSnapshot(
            autoSave = autoSave,
            burnAfterScan = burnAfterScan,
            dark = themeMode == ThemeMode.Dark,
            recentIds = recent,
            freeReminderEnabled = freeEnabled,
            freeLeadMinutes = freeLead,
            rideStartAt = rideStartAt,
            preciseCountdownEnabled = preciseEnabled,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, EbikePrefsSnapshot())

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

    /** 点「打开微信扫一扫」：记起点 + 起常驻倒计时 + 排两个精确提醒（换车再点 = 重新计时）。 */
    fun onWechatScanClicked() {
        viewModelScope.launch {
            val outcome = EbikeFreeRideReminder.startRide(
                Graph.appContext,
                System.currentTimeMillis(),
            )
            _events.send(
                when (outcome) {
                    EbikeFreeRideReminder.Outcome.Started ->
                        EbikeEvent.Notice("已开始计时，通知栏已显示倒计时", NoticeTone.Success)
                    EbikeFreeRideReminder.Outcome.StartedNoNotification ->
                        EbikeEvent.Notice("已开始计时；通知被关闭，提醒发不出来，请到系统设置打开", NoticeTone.Warning)
                    EbikeFreeRideReminder.Outcome.StartedSilent ->
                        EbikeEvent.Notice("已开始计时（免费时长提醒未开启）", NoticeTone.Info)
                    is EbikeFreeRideReminder.Outcome.Failed ->
                        EbikeEvent.Notice("已开始计时；提醒排程失败：${outcome.message}", NoticeTone.Warning)
                    else ->
                        EbikeEvent.Notice("已开始计时", NoticeTone.Info)
                },
            )
        }
    }

    /** 结束骑行：清起点、撤闹钟、停常驻倒计时、清通知栏上的提醒。 */
    fun onEndRide() {
        viewModelScope.launch {
            val outcome = EbikeFreeRideReminder.endRide(Graph.appContext)
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

    /** 点击最近车号 chip 回填完整车号。 */
    fun onPickRecent(carNum: String) {
        if (EbikeQr.bikeUrl(carNum) != null) onCarInput(carNum)
    }

    /**
     * 地图页选中的车（DESIGN §3.9）：回填完整车号并立即出码。
     * 车号来自运营方接口，仍走一遍 [EbikeQr.bikeUrl] 校验，脏数据不出一张扫不开的码。
     */
    fun onPickCarNum(carNum: String) {
        if (EbikeQr.bikeUrl(carNum) == null) return
        _uiState.update { it.copy(carInput = carNum, inputError = null) }
        generate()
    }

    /** 一键清空最近车号（DESIGN §3.9）。历史只是回填便利项，清了不弹二次确认，直接提示。 */
    fun clearRecent() {
        viewModelScope.launch {
            prefs.updateEbikeRecentIds { emptyList() }
            _events.send(EbikeEvent.Notice("已清空最近车号", NoticeTone.Info))
        }
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
        val prefsSnapshot = ebikePrefs.value
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.Default) {
                EbikeQrBitmaps.render(EbikeQr.qrMatrix(url), prefsSnapshot.dark)
            }
            _uiState.update {
                it.copy(generatedBitmap = bitmap, generatedBikeId = carNum)
            }
            if (prefsSnapshot.autoSave) saveCurrent()
            viewModelScope.launch {
                prefs.updateEbikeRecentIds { EbikeQr.mergeRecent(it, carNum) }
            }
        }
    }

    /**
     * 手动把当前展示的码存相册（自动保存关闭时的兜底动作）。
     * 保存成功且扫完即焚开着时，记录待焚毁 key——回来时由 [burnPending] 清除。
     */
    fun saveCurrent() {
        val state = _uiState.value
        val bitmap = state.generatedBitmap ?: return
        val bikeId = state.generatedBikeId ?: return
        viewModelScope.launch {
            val appContext = Graph.appContext
            val result = withContext(Dispatchers.IO) {
                EbikeQrBitmaps.saveToGallery(appContext, bitmap, bikeId)
            }
            when (result) {
                is EbikeQrBitmaps.SaveResult.Saved -> {
                    if (ebikePrefs.value.burnAfterScan) {
                        prefs.updateEbikePendingDelete {
                            EbikeQr.mergePendingDelete(it, result.pendingKey)
                        }
                    }
                    _events.send(EbikeEvent.Notice("已保存到相册「水贝贝」", NoticeTone.Success))
                }
                is EbikeQrBitmaps.SaveResult.Failed ->
                    _events.send(EbikeEvent.Notice(result.message, NoticeTone.Error))
            }
        }
    }

    /**
     * 扫完即焚（DESIGN §3.9）：删除所有记录在案的待焚毁二维码，成功才移出记录。
     * 由页面 ON_RESUME 触发（从微信/桌面回到 App 时）；开关关闭时不删不清——
     * 关掉 = 完全回到旧语义。防重入：进行中的焚毁不叠跑。
     */
    @Volatile
    private var burning = false

    fun burnPending() {
        if (burning) return
        burning = true
        viewModelScope.launch {
            try {
                // 开关真值必须读原始流：ebikePrefs 的 stateIn 快照在 DataStore
                // 首次发射前是默认值（true），冷启动恢复的首帧竞态下会误删
                // 「用户已关闭焚毁」时留下的记录。
                if (!prefs.ebikeBurnAfterScan.first()) return@launch
                val appContext = Graph.appContext
                while (true) {
                    val pending = prefs.ebikePendingDelete.first()
                    if (pending.isEmpty()) break
                    val deleted = pending.filter { key ->
                        withContext(Dispatchers.IO) { EbikeQrBitmaps.deletePending(appContext, key) }
                    }
                    prefs.updateEbikePendingDelete { it - deleted.toSet() }
                    if (deleted.isEmpty()) break // 全部失败（如文件已不在），保留记录别空转
                    _events.send(
                        EbikeEvent.Notice("已清除存入相册的二维码（扫完即焚）", NoticeTone.Success),
                    )
                    if (deleted.size == pending.size) break
                    // 有失败项：下轮重试剩余的；连续失败会在下一轮走 break
                }
            } finally {
                burning = false
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
        override fun <T : ViewModel> create(modelClass: Class<T>): T = EbikeViewModel(prefs) as T
    }
}
