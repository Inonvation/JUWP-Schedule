package edu.jxslu.schedule.ui.life

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.power.PowerBill
import edu.jxslu.schedule.data.power.PowerBillMonth
import edu.jxslu.schedule.data.power.PowerException
import edu.jxslu.schedule.data.power.PowerReadingSource
import edu.jxslu.schedule.data.power.PowerReadingStore
import edu.jxslu.schedule.data.power.PowerRepository
import edu.jxslu.schedule.data.power.PowerTurnover
import edu.jxslu.schedule.data.ykt.YktCredentialStore
import edu.jxslu.schedule.domain.PowerReading
import edu.jxslu.schedule.domain.PowerRechargePoint
import edu.jxslu.schedule.domain.PowerUsage
import edu.jxslu.schedule.domain.PowerUsageRange
import edu.jxslu.schedule.domain.PowerUsageSummary
import edu.jxslu.schedule.ui.common.NoticeTone
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 账单页的月份窗口（进页算一次，页面存活期间不变）。 */
private val BILL_MONTH_KEYS: List<String> = PowerBill.recentMonthKeys(YearMonth.now())

/** 用电统计的档位状态。 */
data class PowerUsageUiState(
    val range: PowerUsageRange = PowerUsageRange.Day,
    /** 统计结果；本机没有读数时为 null（页面给「还没攒到读数」的空态）。 */
    val summary: PowerUsageSummary? = null,
)

/** 缴费账单页状态（DESIGN §3.13）。 */
data class PowerBillUiState(
    /** 请求进行中。 */
    val loading: Boolean = false,
    /** 近 12 个月的键（升序，最后一个是当月）。 */
    val monthKeys: List<String> = BILL_MONTH_KEYS,
    /** 当前查看的月份；默认当月。 */
    val monthKey: String = BILL_MONTH_KEYS.last(),
    /** 全部记录（升序）。 */
    val rows: List<PowerTurnover> = emptyList(),
    /** 月度聚合（降序）。 */
    val months: List<PowerBillMonth> = emptyList(),
    val error: String? = null,
    val noCredentials: Boolean = false,
    /** 至少跑完过一次取数（含失败）；false = 首屏还在加载。 */
    val loaded: Boolean = false,
    /** 用电统计（同一页第二个分页，DESIGN §3.13「用电统计」）。 */
    val usage: PowerUsageUiState = PowerUsageUiState(),
) {
    /** 当前月的汇总；该月没有记录时为 null。 */
    val month: PowerBillMonth? get() = months.firstOrNull { it.key == monthKey }

    /** 当前月的明细（时间倒序）。 */
    val visibleRows: List<PowerTurnover> get() = PowerBill.rowsOf(rows, monthKey)

    /** 月切换边界：窗口两端禁用，不允许滑到窗口之外。 */
    val canPrev: Boolean get() = monthKeys.indexOf(monthKey) > 0
    val canNext: Boolean get() = monthKeys.indexOf(monthKey) in 0 until monthKeys.lastIndex

    /** 柱状图数据：近 12 个月的充值合计（分）。 */
    val monthlyRechargeFen: Map<String, Long>
        get() = months.filter { it.key != PowerBill.UNKNOWN_MONTH }
            .associate { it.key to it.rechargeFen }
}

/** 一次性事件（打开平台页失败等）。 */
sealed interface PowerBillEvent {
    data class Notice(val text: String, val tone: NoticeTone) : PowerBillEvent
}

/**
 * 缴费账单页（DESIGN §3.13「缴费账单页」）。
 *
 * 数据来自 `PowerRepository.history`（与生活页「最近流水」同一份，仓库内存缓存 2 分钟）：
 * 从生活页点进来时通常**一次请求都不发**。月切换、汇总、柱状全在本地算，零网络。
 *
 * 第二个分页「用电统计」的数据源完全不同：本机的电表读数（`power_readings`，Room 响应式）
 * + 同一份流水（用来把充值加进去的电扣掉）。读数落库、流水不落库——流水条数少、
 * 平台随时可查，本地存一份反而要处理增量与冲突（一卡通流水落库是因为它同时是
 * 「消费流水」页的数据源，电费没有这个需求）。
 */
class PowerBillViewModel(
    private val repo: PowerRepository,
    private val credentialStore: YktCredentialStore,
    private val readingStore: PowerReadingStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PowerBillUiState())
    val uiState: StateFlow<PowerBillUiState> = _uiState.asStateFlow()

    private val _events = Channel<PowerBillEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** 本机记录的电表读数（Room 响应式：生活页记下一条，这里立刻重算）。 */
    private val readings = MutableStateFlow<List<PowerReading>>(emptyList())

    init {
        // 进页走缓存：从生活页过来时那份数据刚取过，不重复打平台（DESIGN §4.24「请求节流」）
        load(force = false)
        viewModelScope.launch {
            readingStore.observeAll().collect { rows ->
                readings.value = rows
                recomputeUsage()
            }
        }
    }

    /** 取一次流水。[force] = true 绕过仓库缓存（下拉刷新用）。 */
    fun load(force: Boolean = false) {
        val credentials = credentialStore.read()
        if (credentials == null) {
            _uiState.update {
                it.copy(loading = false, loaded = true, noCredentials = true, error = null)
            }
            // 取数没跑，但本机攒的读数照样能出曲线（只是没有充值流水可扣）
            recomputeUsage()
            return
        }
        _uiState.update { it.copy(loading = true, error = null, noCredentials = false) }
        viewModelScope.launch {
            try {
                val rows = repo.history(credentials.username, credentials.password, force = force)
                _uiState.update {
                    it.copy(
                        loading = false,
                        loaded = true,
                        rows = rows,
                        months = PowerBill.monthsOf(rows),
                        error = null,
                    )
                }
                recomputeUsage()
            } catch (e: CancellationException) {
                throw e
            } catch (e: PowerException.Credential) {
                _uiState.update {
                    it.copy(
                        loading = false,
                        loaded = true,
                        error = "${e.message}。若密码已改，请在「我的 → 校园卡」重新验证",
                    )
                }
                recomputeUsage()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        loading = false,
                        loaded = true,
                        error = e.message ?: "账单读取失败",
                    )
                }
                recomputeUsage()
            }
        }
    }

    /**
     * 下拉刷新 = 绕过缓存重取。
     *
     * 顺带**真的读一次电表**（三个来源里唯一由用户主动触发的那个）：读数要落库才有
     * 用电统计，而统计的密度就等于读数的密度，所以下拉刷新这个动作顺便记一笔；
     * 读表失败不影响账单（用量少一段而已，下一次读数会补上）。
     */
    fun refresh() {
        load(force = true)
        val credentials = credentialStore.read() ?: return
        viewModelScope.launch {
            runCatching {
                repo.snapshot(
                    credentials.username,
                    credentials.password,
                    force = true,
                    source = PowerReadingSource.BILL,
                )
            }
        }
    }

    /** 切统计粒度（日 / 周 / 月）；窗口长度在 `PowerUsage.windowOf`，本地重算零网络。 */
    fun selectUsageRange(range: PowerUsageRange) {
        if (_uiState.value.usage.range == range) return
        _uiState.update { it.copy(usage = it.usage.copy(range = range)) }
        recomputeUsage()
    }

    /**
     * 重算用电统计：本机读数（`PowerUsage`）+ 本次取到的充值流水。
     *
     * 流水里**退款按负数**喂给 [PowerRechargePoint]：退款会把度数从电表里扣回去，
     * 与充值同一个公式。买入的度数按单价折算，单价缺失的那一段 `PowerUsage` 会自己跳过。
     */
    private fun recomputeUsage() {
        val state = _uiState.value
        val recharges = state.rows.mapNotNull { row ->
            if (row.epochMs <= 0L) {
                null
            } else {
                PowerRechargePoint(
                    epochMs = row.epochMs,
                    amountFen = if (row.refund) -row.amountFen else row.amountFen,
                )
            }
        }
        val summary = if (readings.value.isEmpty()) {
            null
        } else {
            PowerUsage.summarize(
                readings = readings.value,
                recharges = recharges,
                range = state.usage.range,
                nowMs = System.currentTimeMillis(),
                zone = ZoneId.systemDefault(),
            )
        }
        _uiState.update { it.copy(usage = it.usage.copy(summary = summary)) }
    }

    /** 切到窗口内的某个月（窗口外的键直接忽略）。 */
    fun selectMonth(key: String) {
        if (key !in _uiState.value.monthKeys) return
        _uiState.update { it.copy(monthKey = key) }
    }

    fun prevMonth() {
        val state = _uiState.value
        val index = state.monthKeys.indexOf(state.monthKey)
        if (index > 0) selectMonth(state.monthKeys[index - 1])
    }

    fun nextMonth() {
        val state = _uiState.value
        val index = state.monthKeys.indexOf(state.monthKey)
        if (index in 0 until state.monthKeys.lastIndex) selectMonth(state.monthKeys[index + 1])
    }

    /**
     * 页脚兜底入口：把带登录态的账单页深链交给调用方打开（平台改版或要看别的项目时用）。
     * 与生活页那条路同一份实现口径——先拿深链，失败给一次性提示。
     */
    fun openPlatformPage(onReady: (String) -> Unit) {
        val credentials = credentialStore.read()
        if (credentials == null) {
            _events.trySend(
                PowerBillEvent.Notice("请先在「我的 → 校园卡」开启一卡通，再打开缴费平台", NoticeTone.Warning),
            )
            return
        }
        viewModelScope.launch {
            try {
                onReady(repo.billPageUrl(credentials.username, credentials.password))
            } catch (e: CancellationException) {
                throw e
            } catch (e: PowerException) {
                _events.send(PowerBillEvent.Notice(e.message ?: "缴费平台打不开", NoticeTone.Error))
            } catch (e: Exception) {
                _events.send(
                    PowerBillEvent.Notice("缴费平台打不开：${e.message ?: "未知错误"}", NoticeTone.Error),
                )
            }
        }
    }

    companion object {
        fun Factory(context: Context) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = PowerBillViewModel(
                Graph.powerRepository(context.applicationContext),
                Graph.yktCredentialStore(context.applicationContext),
                Graph.powerReadingStore(context.applicationContext),
            ) as T
        }
    }
}
