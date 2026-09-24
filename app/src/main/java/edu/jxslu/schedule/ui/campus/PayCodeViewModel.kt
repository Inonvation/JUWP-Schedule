package edu.jxslu.schedule.ui.campus

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.local.YktTurnoverEntity
import edu.jxslu.schedule.data.prefs.DisplayPrefsStore
import edu.jxslu.schedule.data.ykt.YktTurnoverSyncer
import edu.jxslu.schedule.data.ykt.YktBarcodeData
import edu.jxslu.schedule.data.ykt.YktCard
import edu.jxslu.schedule.data.ykt.YktCredentialStore
import edu.jxslu.schedule.data.ykt.YktException
import edu.jxslu.schedule.data.ykt.YktRepository
import edu.jxslu.schedule.domain.ThemeMode
import edu.jxslu.schedule.domain.YktPayWatch
import edu.jxslu.schedule.domain.YktPayCode
import edu.jxslu.schedule.domain.YktPayment
import edu.jxslu.schedule.domain.YktTurnoverRow
import edu.jxslu.schedule.ui.common.CodeBitmaps
import edu.jxslu.schedule.ui.common.NoticeTone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** 付款码页三态。 */
sealed class PayCodeUiState {

    /**
     * 未取码（生活页内嵌用法，DESIGN §3.13）：占位态，点了才登录取码。
     * 付款码页（§3.10）自己不用这个态——它进页就 [PayCodeViewModel.load]。
     */
    data object Idle : PayCodeUiState()

    /** 取码中（进页自动触发）。 */
    data object Loading : PayCodeUiState()

    /** 取码失败：[message] 用户可读原因；[canRetry] = 允许重试（凭证类错误引导去设置页）。 */
    data class Error(val message: String, val canRetry: Boolean = true) : PayCodeUiState()

    /**
     * 取码成功。[codes] 为本批全部码（含当前），[index] 当前展示位；
     * 位图由 ViewModel 异步渲染在 [PayCodeViewModel.bitmaps]，不进状态类。
     */
    data class Success(
        val codes: List<String>,
        val index: Int,
        val expiresSeconds: Long,
        val accountMasked: String,
    ) : PayCodeUiState() {
        val current: String get() = codes[index]
        val hasMore: Boolean get() = index < codes.lastIndex
    }
}

/** 当前展示码的渲染位图（null = 渲染中）。 */
data class PayCodeBitmaps(val qr: Bitmap, val barcode: Bitmap)

/** 一次性事件（换批失败等）。 */
sealed interface PayCodeEvent {
    data class Notice(val text: String, val tone: NoticeTone) : PayCodeEvent
}

/**
 * 校园卡付款码页（DESIGN §3.10 / §4.19）。
 *
 * 进页自动取码：内存 token → 401/无 token 自动重登 → CARD 账户 → 一批 10 码。
 * 「下一个」只在**批内**递增；耗尽才重新取一批（防反复申请攒码）。
 * 码矩阵在 Default 线程渲染，位图随 ViewModel 生命周期回收。
 */
class PayCodeViewModel(
    private val appContext: Context,
    private val repo: YktRepository,
    private val credentialStore: YktCredentialStore,
    private val prefs: DisplayPrefsStore,
    private val db: edu.jxslu.schedule.data.local.JuwDatabase,
) : ViewModel() {

    private val syncer = YktTurnoverSyncer(repo, db)

    // 初值 Idle：付款码页进页就 load()（Loading 立刻接上），生活页内嵌用法则停在占位态不取码
    private val _uiState = MutableStateFlow<PayCodeUiState>(PayCodeUiState.Idle)
    val uiState: StateFlow<PayCodeUiState> = _uiState.asStateFlow()

    private val _bitmaps = MutableStateFlow<PayCodeBitmaps?>(null)
    val bitmaps: StateFlow<PayCodeBitmaps?> = _bitmaps.asStateFlow()

    /**
     * 卡余额快照（DESIGN §4.19 余额展示 + §3.10 账户口径）：null = 未取到 / 取失败
     * （静默，不挡出码主流程）。
     *
     * [cardFen] = 正式卡（CARD，食堂/门禁）；[accountFen] = 电子账户（ACCOUNT，电费等
     * 线上缴费）——**两个独立钱包**，不是同一笔钱的两份视图。[totalFen] = 两者之和
     * （兼容既有「合计」场景）。数据源 `codebarPayinfo`（CARD 与 ACCOUNT 行）。
     */
    data class BalanceSnapshot(
        val cards: List<YktCard>,
        val totalFen: Long,
        /** 正式卡余额（CARD 行 db_balance + unsettle_amount）。 */
        val cardFen: Long,
        /** 电子账户余额（ACCOUNT 行，2026-09-23 实测与 CARD 同接口返回）。 */
        val accountFen: Long,
        /** 兼容字段：此前「水电账户」口径（queryCard 的 elec_accamt），与 accountFen 不同源。 */
        val elecFen: Long,
        /** 快照生成时刻（生活页卡片更新时间行用，2026-09-24）。 */
        val fetchedAtMs: Long = System.currentTimeMillis(),
    )

    private val _balance = MutableStateFlow<BalanceSnapshot?>(null)
    val balance: StateFlow<BalanceSnapshot?> = _balance.asStateFlow()

    /** 深色主题（码配色与页面观感一致）。 */
    val dark: StateFlow<Boolean> = combine(prefs.themeMode) { theme ->
        theme.first() == ThemeMode.Dark
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _events = Channel<PayCodeEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var loaded = false

    /** 进页自动取码；[force] = 手动重试。取码成功后顺带取余额（失败静默不挡主流程）。 */
    fun load(force: Boolean = false) {
        if (loaded && !force) return
        loaded = true
        val credentials = credentialStore.read()
        if (credentials == null) {
            _uiState.value = PayCodeUiState.Error(
                "凭证未配置或已清除，请在「我的 → 校园卡」重新开启",
                canRetry = false,
            )
            return
        }
        _uiState.update { PayCodeUiState.Loading }
        viewModelScope.launch {
            try {
                // 整链总超时兜底（DESIGN §4.19 小优化）：键盘+登录+账户+取码 4 跳，
                // 单跳各有 15/20s，最坏叠加 80s——宁可明确报超时也不无限转圈（口径同 §4.17）
                val data = withTimeoutOrNull(LOGIN_TOTAL_TIMEOUT_MS) {
                    repo.payCodes(credentials.username, credentials.password)
                }
                if (data == null) {
                    _uiState.value = PayCodeUiState.Error(
                        "登录超时（${LOGIN_TOTAL_TIMEOUT_MS / 1000} 秒无响应），请检查网络后重试",
                        canRetry = true,
                    )
                    return@launch
                }
                _uiState.value = PayCodeUiState.Success(
                    codes = data.barcode,
                    index = 0,
                    expiresSeconds = data.expires,
                    accountMasked = maskAccount(data.account),
                )
                renderBitmaps(data.barcode.first())
                loadBalance(credentials.username, credentials.password)
                // 码已经拿在手上，开始盯这笔消费（DESIGN §3.10「扫码后自动退出」）
                startPayWatch(credentials.username, credentials.password)
            } catch (e: CancellationException) {
                throw e
            } catch (e: YktException.NeedCaptcha) {
                _uiState.value = PayCodeUiState.Error(e.message ?: "触发图形验证码", canRetry = false)
            } catch (e: YktException.Credential) {
                _uiState.value = PayCodeUiState.Error(
                    "${e.message}。若密码已修改，请在「我的 → 校园卡」重新验证",
                    canRetry = true,
                )
            } catch (e: YktException) {
                _uiState.value = PayCodeUiState.Error(e.message ?: "取码失败", canRetry = true)
            } catch (e: Exception) {
                _uiState.value = PayCodeUiState.Error("取码异常：${e.message ?: "未知错误"}", canRetry = true)
            }
        }
    }

    /** 余额快照（静默）：失败不提示——出码是主流程，余额只是锦上添花。 */
    private fun loadBalance(username: String, password: String) {
        viewModelScope.launch {
            try {
                val cards = repo.cards(username, password)
                if (cards.isNotEmpty()) {
                loadBalanceSnapshot(cards)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 静默：余额取不到不影响出示付款码
            }
        }
    }

    /** 下拉/手动刷新余额。 */
    fun refreshBalance() {
        val credentials = credentialStore.read() ?: return
        loadBalance(credentials.username, credentials.password)
    }

    /**
     * 余额快照（DESIGN §3.10 账户口径，2026-09-23 实测定源）：
     * - 正式卡（cardFen）= queryCard 的 db_balance + unsettle_amount（单位分）；
     * - **电子账户（accountFen）= queryCard 的 accinfo[] 首项 balance（独立钱包，
     *   实测 codebarPayinfo 的 ACCOUNT 行只是正式卡镜像，不能当电子账户余额）**。
     */
    private suspend fun loadBalanceSnapshot(cards: List<YktCard>) {
        val credentials = credentialStore.read() ?: return
        val detail = runCatching { repo.rechargeAccountDetail(credentials.username, credentials.password) }.getOrNull()
        _balance.value = BalanceSnapshot(
            cards = cards,
            totalFen = cards.sumOf { it.cardBalanceFen },
            cardFen = cards.sumOf { it.cardBalanceFen },
            accountFen = detail?.second ?: 0L,
            elecFen = cards.sumOf { it.elecBalanceFen },
        )
    }

    /**
     * 收起码（生活页内嵌用法，DESIGN §3.13）：丢掉手上这批码、停掉消费检测，回到 [PayCodeUiState.Idle]。
     *
     * 码只在「展开期间」存在——位图立刻回收，下次展开重新取一批，窗口里不留旧码。
     */
    fun collapse() {
        payWatchJob?.cancel()
        payWatchJob = null
        loaded = false
        _bitmaps.value?.qr?.recycle()
        _bitmaps.value?.barcode?.recycle()
        _bitmaps.value = null
        _uiState.value = PayCodeUiState.Idle
    }

    // ------------------------------------------------------------------
    // 扫码消费检测（DESIGN §3.10「扫码后自动退出」）
    // ------------------------------------------------------------------

    /** 检测到的那笔消费；非空即由页面收尾（退出付款码页，交棒给上一页弹窗）。 */
    private val _payment = MutableStateFlow<YktPayment?>(null)
    val detectedPayment: StateFlow<YktPayment?> = _payment.asStateFlow()

    private var payWatchJob: kotlinx.coroutines.Job? = null

    /**
     * 扫码消费检测：取码成功后启动，每 5 秒同步一页流水，出现晚于「进页水位」的**扣款**
     * 即判定为一笔消费，页面据此自动退出并提示（DESIGN §3.10）。
     *
     * 判定口径在 [YktPayWatch]：水位取服务端交易时间，不掺设备时钟。
     *
     * 窗口 15 分钟封顶：码本身有效数小时，但没人会在付款码页停留那么久，无上限轮询只是
     * 白耗电与白打服务端。命中即停（一次停留只认第一笔），超窗也停。
     *
     * 轮询挂在 ViewModel 上，不跟页面可见性开关：扫码那一秒正好锁屏（ON_PAUSE）是常有的事，
     * 挂生命周期会把这笔漏掉。
     */
    private fun startPayWatch(username: String, password: String) {
        if (payWatchJob?.isActive == true) return
        payWatchJob = viewModelScope.launch {
            val dao = db.yktTurnoverDao()
            val watch = YktPayWatch()
            val deadline = System.currentTimeMillis() + PAY_WATCH_MS
            while (isActive && System.currentTimeMillis() < deadline) {
                if (syncTurnovers(username, password)) {
                    if (!watch.established) {
                        // 第一轮只建立水位：这一轮入库的记录全算「进页前就有」，不判定
                        watch.establish(dao.latestJndatetime())
                    } else {
                        val rows = dao.recordsAfter(watch.watermark ?: 0L).map { it.toWatchRow() }
                        val payment = watch.inspect(rows)
                        if (payment != null) {
                            _payment.value = payment
                            PayCodeResultBus.publish(payment)
                            return@launch
                        }
                    }
                }
                delay(PAY_POLL_MS)
            }
        }
    }

    /**
     * 同步一页流水；失败下一轮再试（返回是否成功，不阻断循环）。
     *
     * force = true：这是**扫码消费检测**，每一轮都在问「刚才有没有扣款」，
     * 被 `YktSyncGate` 挡掉就等于「扫码后自动退出」失效。
     */
    private suspend fun syncTurnovers(username: String, password: String): Boolean = try {
        syncer.sync(username, password, maxPages = 1, force = true)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    /** 本地流水实体 → 判定模型（字段足够判定与展示即可）。 */
    private fun YktTurnoverEntity.toWatchRow() = YktTurnoverRow(
        orderId = orderId,
        epochMs = jndatetime,
        timeText = jndatetimeStr,
        amountFen = tranamtFen,
        income = income,
        typeText = turnoverType,
        locationName = locationName,
        balanceAfterFen = balanceAfterFen,
    )

    /** 展示下一个备用码（批内递增 + 本地渲染）；批内耗尽时重新取一批。 */
    fun next() {
        val current = _uiState.value as? PayCodeUiState.Success ?: return
        if (current.hasMore) {
            val newIndex = current.index + 1
            _uiState.update { state ->
                (state as? PayCodeUiState.Success)?.copy(index = newIndex) ?: state
            }
            renderBitmaps(current.codes[newIndex])
            return
        }
        val credentials = credentialStore.read() ?: return
        viewModelScope.launch {
            try {
                val data = repo.payCodes(credentials.username, credentials.password)
                _uiState.value = PayCodeUiState.Success(
                    codes = data.barcode,
                    index = 0,
                    expiresSeconds = data.expires,
                    accountMasked = maskAccount(data.account),
                )
                renderBitmaps(data.barcode.first())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(PayCodeEvent.Notice("换批失败：${e.message ?: "未知错误"}", NoticeTone.Error))
            }
        }
    }

    /** 渲染当前码（Default 线程）；换码时回收旧位图。 */
    private fun renderBitmaps(code: String) {
        val darkNow = dark.value
        viewModelScope.launch {
            val (qr, bc) = withContext(Dispatchers.Default) {
                val matrices = YktPayCode.matrices(code)
                CodeBitmaps.render(matrices.qr, darkNow) to CodeBitmaps.render(matrices.barcode, darkNow)
            }
            val old = _bitmaps.value
            _bitmaps.value = PayCodeBitmaps(qr, bc)
            old?.qr?.recycle()
            old?.barcode?.recycle()
        }
    }

    /** 卡号掩码：前 2 后 2，其余打星。 */
    private fun maskAccount(account: String): String {
        val a = account.trim()
        if (a.length <= 4) return a
        return a.take(2) + "*".repeat(a.length - 4) + a.takeLast(2)
    }

    override fun onCleared() {
        _bitmaps.value?.qr?.recycle()
        _bitmaps.value?.barcode?.recycle()
        _bitmaps.value = null
        super.onCleared()
    }

    companion object {
        /** 取码整链总超时（键盘+登录+账户+取码 4 跳；口径同 §4.17 的总超时兜底）。 */
        private const val LOGIN_TOTAL_TIMEOUT_MS = 30_000L

        /** 扫码消费检测的轮询间隔。 */
        private const val PAY_POLL_MS = 5_000L

        /** 扫码消费检测的窗口上限（15 分钟；付款码页不会停留更久）。 */
        private const val PAY_WATCH_MS = 15 * 60_000L
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = PayCodeViewModel(
            context.applicationContext,
            Graph.yktRepository(context.applicationContext),
            Graph.yktCredentialStore(context.applicationContext),
            Graph.displayPrefs(context.applicationContext),
            edu.jxslu.schedule.data.local.JuwDatabase.get(context.applicationContext),
        ) as T
    }
}
