package edu.jxslu.schedule.ui.life

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.power.PowerBill
import edu.jxslu.schedule.data.power.PowerBillMonth
import edu.jxslu.schedule.data.power.PowerException
import edu.jxslu.schedule.data.power.PowerRepository
import edu.jxslu.schedule.data.power.PowerTurnover
import edu.jxslu.schedule.data.ykt.YktCredentialStore
import edu.jxslu.schedule.ui.common.NoticeTone
import java.time.YearMonth
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
 * 不落库：电费流水条数少、平台随时可查；落库要处理增量与冲突，而这一页没有离线需求。
 */
class PowerBillViewModel(
    private val repo: PowerRepository,
    private val credentialStore: YktCredentialStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PowerBillUiState())
    val uiState: StateFlow<PowerBillUiState> = _uiState.asStateFlow()

    private val _events = Channel<PowerBillEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        // 进页走缓存：从生活页过来时那份数据刚取过，不重复打平台（DESIGN §4.24「请求节流」）
        load(force = false)
    }

    /** 取一次流水。[force] = true 绕过仓库缓存（下拉刷新用）。 */
    fun load(force: Boolean = false) {
        val credentials = credentialStore.read()
        if (credentials == null) {
            _uiState.update {
                it.copy(loading = false, loaded = true, noCredentials = true, error = null)
            }
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
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        loading = false,
                        loaded = true,
                        error = e.message ?: "账单读取失败",
                    )
                }
            }
        }
    }

    /** 下拉刷新 = 绕过缓存重取。 */
    fun refresh() = load(force = true)

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
            ) as T
        }
    }
}
