package edu.jxslu.schedule.ui.ebike

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.kqcx.KqcxSessionRepository
import edu.jxslu.schedule.data.prefs.DisplayPrefsStore
import edu.jxslu.schedule.data.repo.RideRecordStore
import edu.jxslu.schedule.domain.KqcxAuth
import edu.jxslu.schedule.domain.RideRecord
import edu.jxslu.schedule.domain.KvcBusinessError
import edu.jxslu.schedule.domain.capabilities
import edu.jxslu.schedule.ui.common.NoticeTone
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.SocketTimeoutException

/** 一次性提示事件（对齐 WaterViewModel 模式：缓冲 Channel，页面负责收集）。 */
sealed interface KvcxEvent {
    data class Notice(val text: String, val tone: NoticeTone) : KvcxEvent
}

/** 快趣出行页状态。表单值只在未登录时有意义；骑行卡只在已登录时有意义。 */
data class KvcxUiState(
    val loggedIn: Boolean = false,
    val mobile: String = "",
    val mobileError: String? = null,
    val password: String = "",
    val loggingIn: Boolean = false,
    val accountMobile: String = "",
    val querying: Boolean = false,
    /** null = 已查询且无骑行在案；查询失败沿用上次结果不动它。 */
    val ride: KqcxAuth.Ride? = null,
    /**
     * 快趣资产（2026-09-30，只读）：余额 / 卡券 / 会员卡。进页拉一次 + 手动刷新；
     * null = 还没查到过（查询失败沿用 null，页面给"再试一次"的出路）。
     */
    val assets: KqcxAuth.Assets? = null,
    val assetsLoading: Boolean = false,
    /** 最近一次资产查询颗粒无收（三块全空或请求失败）；页面据此给重试出路。 */
    val assetsFailed: Boolean = false,
)

class KvcxViewModel(
    private val session: KqcxSessionRepository,
    private val records: RideRecordStore,
    /**
     * 使用方式（DESIGN §3.9 / §4.32）：本页只属于**账号登录方式**，小程序方式下页面整片
     * 收起（入口也从「校园服务」隐藏），所以这里的查询也要跟着停——否则一个已经退到
     * 后台栈里的本页实例还会去打扰第三方接口。
     */
    private val prefs: DisplayPrefsStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        KvcxUiState(
            loggedIn = session.loggedIn.value,
            accountMobile = session.currentMobile.orEmpty(),
        ),
    )
    val uiState: StateFlow<KvcxUiState> = _uiState.asStateFlow()

    private val _events = Channel<KvcxEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /**
     * 本机骑行记录（DESIGN §3.9「最近骑行」）：最近的在前。
     * 页面不可见时不订阅（WhileSubscribed），退出登录也照样显示——它是本机数据。
     */
    val rideRecords: StateFlow<List<RideRecord>> = records.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 清空本机骑行记录（纯本地数据，清前由页面确认）。 */
    fun clearRecords() {
        viewModelScope.launch {
            runCatching { records.clear() }
            notice("已清空本机骑行记录", NoticeTone.Info)
        }
    }

    private fun notice(text: String, tone: NoticeTone = NoticeTone.Info) =
        _events.trySend(KvcxEvent.Notice(text, tone))

    init {
        // 登录态跨窗口共享（hub 与本页各持一份 VM）：以仓库那条流为准
        viewModelScope.launch {
            session.loggedIn.collect { loggedIn ->
                _uiState.update {
                    if (loggedIn == it.loggedIn) return@update it
                    if (loggedIn) {
                        it.copy(loggedIn = true, accountMobile = session.currentMobile.orEmpty())
                    } else {
                        KvcxUiState() // 退出：表单与骑行卡一起归零
                    }
                }
            }
        }
        // 进页自动查一次骑行状态：只在账号登录方式下（见 [accountMode]）
        viewModelScope.launch {
            if (session.loggedIn.value && accountMode()) {
                refreshRide()
                refreshAssets()
            }
        }
    }

    /** 当前是否账号登录方式（读原始流；读失败按「不是」处理，宁可不查）。 */
    private suspend fun accountMode(): Boolean =
        runCatching { prefs.ebikeUseMode.first().capabilities().inAppRide }.getOrDefault(false)

    // ── 登录表单 ──

    fun updateMobile(value: String) = _uiState.update {
        val digits = value.filter(Char::isDigit).take(11)
        it.copy(
            mobile = digits,
            mobileError = if (digits.isNotEmpty() && digits.length < 11) "请输入 11 位手机号" else null,
        )
    }

    fun updatePassword(value: String) = _uiState.update { it.copy(password = value.take(64)) }

    /** 登录：失败保留输入（用户改完直接重试），提示经 Snackbar。 */
    fun login() = viewModelScope.launch {
        val s = _uiState.value
        if (s.mobile.length != 11) {
            _uiState.update { it.copy(mobileError = "请输入 11 位手机号") }
            return@launch
        }
        _uiState.update { it.copy(loggingIn = true) }
        runCatching { session.login(s.mobile, s.password) }
            .onSuccess {
                // 登录态由仓库流回灌（init 里的 collect 会置 loggedIn / accountMobile）
                notice("登录成功", NoticeTone.Success)
                _uiState.update { it.copy(loggingIn = false, password = "") }
                refreshRide()
                refreshAssets()
            }
            .onFailure {
                _uiState.update { it.copy(loggingIn = false) }
                notice(loginErrorText(it), NoticeTone.Error)
            }
    }

    fun logout() {
        session.logout()
        // 仓库流会回灌归零；这里不重复改 uiState
    }

    // ── 骑行状态 ──

    /** 进页自动查一次（init）+ 手动刷新共用。查询失败不动上次结果，只出提示。 */
    fun refreshRide() = viewModelScope.launch {
        if (!session.loggedIn.value) return@launch
        if (!accountMode()) return@launch
        _uiState.update { it.copy(querying = true) }
        runCatching { session.queryUnderway() }
            .onSuccess { ride ->
                _uiState.update { it.copy(querying = false, ride = ride) }
                if (ride == null) notice("当前没有进行中的骑行", NoticeTone.Info)
            }
            .onFailure {
                _uiState.update { it.copy(querying = false) }
                notice(queryErrorText(it), NoticeTone.Error)
            }
    }

    // ── 快趣资产（2026-09-30，只读） ──

    /**
     * 查一次账户资产（余额 / 卡券 / 会员卡）：进页、登录成功、手动刷新各一次。
     * **失败沿用上次结果**：资产是展示性数据，查不到不该把用户已看到的清成空白；
     * 三块全空才算"颗粒无收"，页面给重试出路。失败不打扰（不弹提示，区块内给一行说明）。
     */
    fun refreshAssets() = viewModelScope.launch {
        if (!session.loggedIn.value) return@launch
        if (!accountMode()) return@launch
        if (_uiState.value.assetsLoading) return@launch
        _uiState.update { it.copy(assetsLoading = true) }
        runCatching { session.queryAssets() }
            .onSuccess { assets ->
                _uiState.update {
                    if (assets.isEmpty) {
                        it.copy(assetsLoading = false, assetsFailed = true)
                    } else {
                        it.copy(assetsLoading = false, assetsFailed = false, assets = assets)
                    }
                }
            }
            .onFailure {
                _uiState.update { it.copy(assetsLoading = false, assetsFailed = true) }
            }
    }

    /** 登录失败的文案分类：业务错误给服务端原文，网络类给可操作建议。 */
    private fun loginErrorText(error: Throwable): String = when (error) {
        is KvcBusinessError -> error.message ?: "登录失败"
        is SocketTimeoutException -> "登录超时，请检查网络后重试"
        is IOException -> "网络不可用，请检查网络后重试"
        else -> "登录异常：${error.message ?: error.javaClass.simpleName}"
    }

    private fun queryErrorText(error: Throwable): String = when (error) {
        is KvcBusinessError ->
            if (error.message?.contains("未登录") == true) "快趣登录已失效，请重新登录"
            else error.message ?: "查询失败"
        is SocketTimeoutException -> "查询超时，请稍后重试"
        is IOException -> "网络不可用，请检查网络后重试"
        else -> "查询异常：${error.message ?: error.javaClass.simpleName}"
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    KvcxViewModel(
                        session = Graph.kqcx(context.applicationContext),
                        records = Graph.rideRecordStore(context.applicationContext),
                        prefs = Graph.displayPrefs(context.applicationContext),
                    ) as T
            }
    }
}
