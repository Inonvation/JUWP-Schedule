package edu.jxslu.schedule.ui.campus

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.jw.JwVpnDetector
import edu.jxslu.schedule.data.local.JuwDatabase
import edu.jxslu.schedule.data.prefs.PendingRecharge
import edu.jxslu.schedule.data.session.NetworkHint
import edu.jxslu.schedule.data.ykt.YktCard
import edu.jxslu.schedule.data.ykt.YktException
import edu.jxslu.schedule.data.ykt.YktRechargeOrder
import edu.jxslu.schedule.data.ykt.YktRechargeStart
import edu.jxslu.schedule.data.ykt.YktRepository
import edu.jxslu.schedule.data.ykt.YktTurnoverSyncer
import edu.jxslu.schedule.domain.YktArrival
import edu.jxslu.schedule.domain.BalanceAlertSource
import edu.jxslu.schedule.domain.InboxKind
import edu.jxslu.schedule.domain.InboxKeys
import edu.jxslu.schedule.SubpageScreen
import edu.jxslu.schedule.ui.common.NoticeFeedback
import edu.jxslu.schedule.ui.common.NoticeTone
import edu.jxslu.schedule.ui.reminder.BalanceAlertReminder
import edu.jxslu.schedule.ui.widget.LifeWidgetSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 校园卡状态与动作：凭证启停、余额/到账、充值编排、两个余额提醒。
 *
 * 页面（[CampusCardSettingsScreen]）只做展示与收集输入；侧栏钱包、付款码页、充值宿主
 * 复用本类时用 `autoFetch = false`，避免冷启动多打一轮余额请求。
 */
class CampusCardViewModel(
    private val appContext: Context,
    /**
     * 是否在 init 里自动拉一次余额。侧栏 / 充值宿主那份传 false：冷启动没人看，
     * 等侧栏展开或充值弹层打开时再刷；校园卡设置页保持默认 true，进页行为不变。
     */
    private val autoFetch: Boolean = true,
) : ViewModel() {

    /** 取数失败时的排查提示按它分支：开着代理点名关掉，没开就换一条网络（口径见 `NetworkHint`）。 */
    private val isVpnActive: () -> Boolean = { JwVpnDetector.isVpnActive(appContext) }

    private val prefs = Graph.displayPrefs(appContext)
    private val credentialStore = Graph.yktCredentialStore(appContext)
    private val repo = Graph.yktRepository(appContext)
    private val db = edu.jxslu.schedule.data.local.JuwDatabase.get(appContext)
    private val syncer = edu.jxslu.schedule.data.ykt.YktTurnoverSyncer(repo, db)

    val hasSavedAccount: Boolean get() = credentialStore.read() != null

    /**
     * 已保存的学号（明文回填到「校园卡账号」输入框，2026-09-24 用户拍板）。
     * 普通 getter：只在进页时被读一次（UI 用 `remember` 承接），不做 State 免得每次重组都读加密存储。
     */
    val savedUsername: String? get() = credentialStore.read()?.username

    /**
     * 一卡通开启状态（付款码条、钱包卡与充值入口的总闸）。不再是 UI 上的一个 Switch：
     * 「保存并开启」写凭证时置 true，「关闭并清除」写 false，两者成对。
     *
     * **初值不许写 false**（2026-09-24 修「冷启动首次切到生活页整页跳一下」）：`stateIn` 的初值
     * 就是首帧读到的值，而 DataStore 的第一份数据必然晚于首帧。写 false 的话生活页先按
     * 「未开启」渲染一次（付款码条矮一截、钱包两栏是「— / 未开启凭证」），真值到达后换成
     * 正常版式，下方内容整体下移。实测 Redmi K70 冷启动首次进生活页：首帧那个区块标题在
     * y=1172，真值到达后跳到 y=1840，一次位移 668px（≈243dp）——用户报的「页面跳动」。
     * （668px 量的是改版前的旧版式，数只作量级参考；2026-09-26 生活页已重构，见 DESIGN §3.13。）
     *
     * 初值取**凭证在不在**：开启 / 关闭与存 / 清凭证是同一次流程里的成对写入（设置页
     * `enable` / `disable`、首启引导都是先写凭证再写开关），所以它是首帧能同步拿到的最准的值。
     * 读加密存储在本类 `init` 里本来就有一次，这里多的一次读的是同一份内存缓存，不额外解密。
     */
    val enabled: StateFlow<Boolean> = prefs.campusCardEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), hasSavedAccount)

    // ---- 余额提醒（DESIGN §3.13）：默认关；两个来源各自独立，但共用同一份凭证 ----

    // 余额提醒初值阻塞读真值（同 [MeViewModel] 2026-09-28 的修法）：stateIn 初值若写
    // false/默认阈值，开过提醒的用户进设置页开关会先按关渲染、真值到达后再跳。
    // DataStore 读过一次后常驻内存，这里只是再收一次缓存。
    val powerAlertEnabled: StateFlow<Boolean> = prefs.powerAlertEnabled
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            runBlocking { prefs.powerAlertEnabled.first() },
        )

    val powerAlertYuan: StateFlow<Int> = prefs.powerAlertYuan
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            runBlocking { prefs.powerAlertYuan.first() },
        )

    val yktAlertEnabled: StateFlow<Boolean> = prefs.yktAlertEnabled
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            runBlocking { prefs.yktAlertEnabled.first() },
        )

    val yktAlertYuan: StateFlow<Int> = prefs.yktAlertYuan
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            runBlocking { prefs.yktAlertYuan.first() },
        )

    val powerAlertIntervalHours: StateFlow<Int> = prefs.powerAlertIntervalHours
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            runBlocking { prefs.powerAlertIntervalHours.first() },
        )

    val yktAlertIntervalHours: StateFlow<Int> = prefs.yktAlertIntervalHours
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            runBlocking { prefs.yktAlertIntervalHours.first() },
        )

    fun setPowerAlertEnabled(value: Boolean) {
        viewModelScope.launch {
            prefs.setPowerAlertEnabled(value)
            BalanceAlertReminder.onSettingsChanged(appContext, BalanceAlertSource.Power)
        }
    }

    fun setPowerAlertYuan(value: Int) {
        viewModelScope.launch {
            prefs.setPowerAlertYuan(value)
            BalanceAlertReminder.onSettingsChanged(appContext, BalanceAlertSource.Power)
        }
    }

    fun setYktAlertEnabled(value: Boolean) {
        viewModelScope.launch {
            prefs.setYktAlertEnabled(value)
            BalanceAlertReminder.onSettingsChanged(appContext, BalanceAlertSource.Ykt)
        }
    }

    fun setYktAlertYuan(value: Int) {
        viewModelScope.launch {
            prefs.setYktAlertYuan(value)
            BalanceAlertReminder.onSettingsChanged(appContext, BalanceAlertSource.Ykt)
        }
    }

    fun setPowerAlertIntervalHours(value: Int) {
        viewModelScope.launch {
            prefs.setPowerAlertIntervalHours(value)
            BalanceAlertReminder.onSettingsChanged(appContext, BalanceAlertSource.Power)
        }
    }

    fun setYktAlertIntervalHours(value: Int) {
        viewModelScope.launch {
            prefs.setYktAlertIntervalHours(value)
            BalanceAlertReminder.onSettingsChanged(appContext, BalanceAlertSource.Ykt)
        }
    }

    /** 余额快照（开启后进页静默拉一次；失败静默——设置页只做引导不做主流程）。 */
    private val _balance = MutableStateFlow<PayCodeViewModel.BalanceSnapshot?>(null)
    val balance: StateFlow<PayCodeViewModel.BalanceSnapshot?> = _balance

    private val _balanceLoaded = MutableStateFlow(false)

    /** 余额拉取进行中（今日页下拉刷新聚合指示器用）。 */
    private val _balanceRefreshing = MutableStateFlow(false)
    val balanceRefreshing: StateFlow<Boolean> = _balanceRefreshing

    /**
     * 余额状态是否**已确定**（2026-09-22 加）：尝试过加载（成功或失败）、
     * 或压根没得加载（无凭证 / 开关关）都算。
     *
     * 今日页卡片副行据此区分两种 null：还没确定 → 「余额读取中…」；
     * 确定但没有 → 「点击出示付款码」。少了这个标志，首帧只能先显示后者、
     * 数据到了再换成数字，看着就是一次突变（用户反馈）。
     */
    val balanceLoaded: StateFlow<Boolean> = _balanceLoaded

    /**
 * 刷新余额（生活页进页、点余额卡、生活页下拉刷新都走它）。无凭证/开关关时静默直返；
     * 失败静默（与 init 的口径一致，卡片副行维持旧值或「暂不可用」）。
     *
     * [force] = false 只给**进页**那条路用：吃 `YktRepository` 的 60 秒余额缓存
 * （DESIGN §4.24「请求节流」）。用户点卡片 / 下拉刷新传 true，照旧拿实时值。
     */
    fun refreshBalance(force: Boolean = true) {
        // 取数中忽略重复触发（连点余额卡 / 反复下拉不再叠请求，2026-10-09）
        if (_balanceRefreshing.value) return
        viewModelScope.launch { fetchBalance(notify = false, force = force) }
    }

    /**
     * 电子账户充值目标（`accinfo` 首项 type，`<account>-000` 形态，DESIGN §3.10）。
     * 充值弹层选「电子账户」时由调用方取；取不到（平台不支持）返回 null = 走正式卡口径。
     * suspend：由充值弹层所在协程调用，不自己开作用域。
     */
    suspend fun electricAccountType(): String? {
        val credentials = credentialStore.read() ?: return null
        return runCatching { repo.electricAccountType(credentials.username, credentials.password) }.getOrNull()
    }

    /**
     * 拉取余额快照。[notify] 为真时（init 首拉）同时恢复未确认充值的轮询；
     * 手动刷新不需要重复挂轮询——init 已挂、`watchRechargeArrival` 自行收口。
     */
    private suspend fun fetchBalance(notify: Boolean, force: Boolean = true) {
        _balanceRefreshing.value = true
        try {
            if (credentialStore.read() == null) return
            val enabledNow = prefs.campusCardEnabled.first()
            if (!enabledNow) return
            val saved = credentialStore.read() ?: return
            // 正式卡与电子账户两问并行（2026-09-30）：都只共享 token，没有数据依赖；
            // 串行时下拉刷新指示器要多等一条 queryCard 的时间。电子账户一问先发、
            // 失败照旧当 0（原口径）；正式卡一问决定有没有快照可落。
            coroutineScope {
                val accountFen = async { accountFenOf(saved.username, saved.password, force) }
                val cards = repo.cards(saved.username, saved.password, force = force)
                if (cards.isNotEmpty()) {
                    applySnapshot(snapshotOf(cards, accountFen.await()))
                    // 账号条姓名（DESIGN §3.3）：queryCard 原生带持卡人姓名，
                    // 首张非空即落库；班级仍归教务学籍卡管（一卡通没有这个字段）。
                    prefs.setProfile(cards.firstOrNull { it.ownerName.isNotBlank() }?.ownerName, null)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 静默：余额取不到不影响设置页其余功能
        } finally {
            _balanceLoaded.value = true
            _balanceRefreshing.value = false
        }
    }

    /**
     * 由 queryCard 构造余额快照（DESIGN §3.10 账户口径）：正式卡 = card 表字段；
     * 电子账户 = accinfo[] 首项 balance（独立钱包，2026-09-23 实测 codebarPayinfo
     * 的 ACCOUNT 行是正式卡镜像，不能用作电子账户余额）。accinfo 取不到当 0。
     *
     * 到账轮询等调用点走这条（卡片到手后串行补电子账户一问）；生活页下拉那条路要
     * 两问并行，直接用 [accountFenOf] + [snapshotOf]（见 [fetchBalance]）。
     */
    private suspend fun buildSnapshot(
        cards: List<YktCard>,
        force: Boolean = true,
    ): PayCodeViewModel.BalanceSnapshot {
        val credentials = credentialStore.read()
        val accountFen = if (credentials != null) {
            accountFenOf(credentials.username, credentials.password, force)
        } else {
            0L
        }
        return snapshotOf(cards, accountFen)
    }

    /** 电子账户余额一问（分）。取不到（网络失败 / 平台不支持）当 0，原口径。 */
    private suspend fun accountFenOf(username: String, password: String, force: Boolean): Long =
        runCatching { repo.rechargeAccountDetail(username, password, force = force) }
            .getOrNull()?.second ?: 0L

    /** 快照拼装（纯计算）：正式卡与电子账户两问的汇合点。 */
    private fun snapshotOf(
        cards: List<YktCard>,
        accountFen: Long,
    ): PayCodeViewModel.BalanceSnapshot = PayCodeViewModel.BalanceSnapshot(
        cards = cards,
        totalFen = cards.sumOf { it.cardBalanceFen },
        cardFen = cards.sumOf { it.cardBalanceFen },
        accountFen = accountFen,
    )

    /**
     * 写余额快照 + 顺手推桌面小组件（2026-10-09）。
     *
     * 小组件的口径是「App 内每次成功取到余额都推一次（零额外请求）」（DESIGN §3.6
     * 二条目改版），此前只有付款码页那条链路在推——充值到账、生活页手动刷新拿到的
     * 新数字要等 2 小时兜底闸门才上小组件。四类快照写入（进页/手动刷新、开启验证、
     * 到账判定、电子账户到账补刷）都收口到这里，别再单独写 `_balance.value`。
     */
    private fun applySnapshot(snapshot: PayCodeViewModel.BalanceSnapshot) {
        _balance.value = snapshot
        viewModelScope.launch {
            runCatching { LifeWidgetSync.pushCampusBalance(appContext, snapshot.cardFen) }
        }
    }

    init {
        // 生活页与校园卡设置页各挂一份本 VM（余额快照 + 到账轮询 + 充值）；
        // 开关关时绝不发起任何一卡通网络动作
        if (credentialStore.read() != null) {
            viewModelScope.launch {
                // 进页首拉吃 60 秒缓存：手动刷新与到账核对仍走 force = true。
                // 侧栏 / 充值宿主那份 autoFetch = false，冷启动不在这里发请求。
                if (autoFetch) fetchBalance(notify = true, force = false)
                // 恢复未确认充值（进程被杀场景，DESIGN §4.19「充值」）：窗口内重启轮询，超窗清除。
                // 基线一并从记录里恢复，重启后余额口径照常可用（只靠流水口径会漏判到账）
                val pending = runCatching { prefs.pendingRecharge.first() }.getOrNull()
                if (pending != null) {
                    val saved = credentialStore.read()
                    if (saved == null || System.currentTimeMillis() - pending.startedAt >= ARRIVAL_WATCH_MS) {
                        prefs.clearPendingRecharge()
                    } else {
                        restoreArrivalBaseline(pending)
                        _arrivalState.value = ArrivalState.Watching(
                            orderFen = pending.orderFen,
                            startedAt = pending.startedAt,
                        )
                        startArrivalWatch(saved.username, saved.password, pending.orderFen, pending.startedAt)
                    }
                }
            }
        } else {
            // 没有凭证 = 没有余额可等，直接算「已确定」：卡片副行落「点击出示付款码」
            _balanceLoaded.value = true
        }

        // 付款码消费命中后（宿主弹「支付成功」的同一刻）：余额已由 CampusBalanceSync
        // 在后台强拉并写回仓库缓存，这里按信号重建一次快照——force = false 吃刚写入的
        // 缓存，零额外请求（2026-10-09 用户反馈：付款成功后卡上数字要等手动刷新才变）。
        // 订阅不设前置条件：无凭证时 fetchBalance 自己短路，开启凭证后照常生效。
        viewModelScope.launch {
            CampusBalanceSync.revision.drop(1).collect {
                fetchBalance(notify = false, force = false)
            }
        }
    }

    /** 开启流程：验证（空白密码沿用已存）→ 保存凭证 → 置开关。失败保持关并给原因。 */
    fun saveAndEnable(username: String, password: String, onResult: (NoticeFeedback) -> Unit) {
        viewModelScope.launch {
            val user = username.trim()
            val saved = credentialStore.read()
            val pwd = password.ifBlank { saved?.password.orEmpty() }
            if (user.isEmpty() || pwd.isEmpty()) {
                onResult(NoticeFeedback("请填入学号和密码", NoticeTone.Warning))
                return@launch
            }
            // 整链总超时兜底：键盘 + 登录 + 账户三跳，正常 1–3 秒
            val result = withTimeoutOrNull(LOGIN_TOTAL_TIMEOUT_MS) { verifyAndEnable(user, pwd) }
            if (result == null) {
                onResult(
                    NoticeFeedback(
                        "登录超时（${LOGIN_TOTAL_TIMEOUT_MS / 1000} 秒无响应）：${NetworkHint.briefOf(isVpnActive())}",
                        NoticeTone.Error,
                    ),
                )
                return@launch
            }
            onResult(result)
        }
    }

    private suspend fun verifyAndEnable(user: String, pwd: String): NoticeFeedback {
        try {
            // 真实登录验证一次（顺带校验字形表/协议），成功才落库
            repo.login(user, pwd)
        } catch (e: CancellationException) {
            throw e
        } catch (e: YktException.Network) {
            // 网络类失败：正文换成可操作的两句之一（关代理 / 换网络），别再报「网络不可达」原文
            return NoticeFeedback(NetworkHint.of(isVpnActive()), NoticeTone.Error)
        } catch (e: YktException) {
            return NoticeFeedback(e.message ?: "登录失败，请检查账号密码", NoticeTone.Error)
        } catch (e: Exception) {
            return NoticeFeedback(
                "登录异常：${e.javaClass.simpleName} ${e.message.orEmpty()}".trim(),
                NoticeTone.Error,
            )
        }
        // 开启即验证 CARD 账户（DESIGN §4.19 小优化）：绑多账号/无实体卡在这里暴露，
        // 而不是等第一次取码才发现。查询失败（网络）不阻塞开启——凭证已验证有效。
        val cards = try {
            repo.cards(user, pwd)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (cards != null && cards.isEmpty()) {
            return NoticeFeedback("该账号下没有可用的校园卡账户，无法出示付款码", NoticeTone.Error)
        }
        cards?.let {
            applySnapshot(buildSnapshot(it))
        }
        credentialStore.save(user, pwd)
        prefs.setCampusCardEnabled(true)
        return NoticeFeedback("已开启，左侧栏已可使用付款码、钱包与电费", NoticeTone.Success)
    }

    /**
     * 关闭：清凭证，并**一并关掉两个余额提醒**（DESIGN §3.13）——提醒都要用这份凭证去读
     * 余额，凭证没了还把开关留在「开」只会得到一个亮着但永远不生效的死开关。
     */
    fun disable(onDone: () -> Unit) {
        viewModelScope.launch {
            prefs.setCampusCardEnabled(false)
            prefs.setPowerAlertEnabled(false)
            prefs.setYktAlertEnabled(false)
            credentialStore.clear()
            // 连内存里的 token 缓存一起清：敏感值不留进程内存（2026-09-28 审查补）
            repo.clearTokenCache()
            // 两个提醒都关了 → 内部会撤销每日周期任务，不白唤醒设备
            BalanceAlertReminder.ensurePeriodicWork(appContext)
            onDone()
        }
    }

    /**
     * 充值：下单 → 拉起微信/收银台 → 回流轮询到账（DESIGN §4.19「充值」）。
     *
     * [launchExternal] 由 UI 层注入（[startActivityOutsideApp]：拉起前把二级页窗口的
     * 推入/滑出过渡换成静止——微信是 singleTask、开不进调用方 task，从它返回时本页的
     * 右推入过渡会被重放，2026-09-26 修「充值回来页面跳动」）。返回 false = 无法打开
     * （未装微信等），调用方给文案。
     */
    fun recharge(
        yuan: String,
        /** 电子账户 type（`<account>-000`）；null = 充正式卡。见 [electricAccountType]。 */
        targetAccount: String? = null,
        launchExternal: (android.content.Intent) -> Boolean,
        onResult: (NoticeFeedback) -> Unit,
    ) {
        viewModelScope.launch {
            val saved = credentialStore.read()
            if (saved == null) {
                onResult(NoticeFeedback("凭证已失效，请重新填写学号密码", NoticeTone.Warning))
                return@launch
            }
            val placed = try {
                withTimeoutOrNull(RECHARGE_TIMEOUT_MS) {
                    repo.rechargeCreate(saved.username, saved.password, yuan, targetAccount = targetAccount)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: YktException.NotInServiceTime) {
                // 服务时间外：App 内提示，**不打开浏览器、不清等待态**（订单 30 分钟自动失效）
                prefs.clearPendingRecharge()
                _arrivalState.value = ArrivalState.Idle
                _arrivalBaseFen = null
                _arrivalAccount = null
                _arrivalTarget = null
                _arrivalWalletBaseFen = null
                onResult(NoticeFeedback(e.message ?: "当前不在充值服务时间内", NoticeTone.Warning))
                return@launch
            } catch (e: YktException.Network) {
                onResult(NoticeFeedback(NetworkHint.of(isVpnActive()), NoticeTone.Error))
                return@launch
            } catch (e: YktException) {
                onResult(NoticeFeedback(e.message ?: "下单失败，请稍后重试", NoticeTone.Error))
                return@launch
            } catch (e: Exception) {
                onResult(
                    NoticeFeedback(
                        "下单异常：${e.javaClass.simpleName}（平台可能已改版）",
                        NoticeTone.Error,
                    ),
                )
                return@launch
            }
            if (placed == null) {
                onResult(NoticeFeedback("下单超时：${NetworkHint.briefOf(isVpnActive())}", NoticeTone.Error))
                return@launch
            }
            val order = placed.order
            // 到账检测基线（付款前的该卡余额 + 卡号，随下单一起拿到）+
            // 持久化未确认充值（进程被杀后可恢复，DESIGN §4.19「充值」）
            val orderFen = parsedFenOf(yuan)
            val startedAt = System.currentTimeMillis()
            _arrivalBaseFen = placed.cardBalanceBeforeFen
            _arrivalAccount = placed.cardAccount
            _arrivalTarget = placed.targetAccount
            _arrivalWalletBaseFen = placed.walletBalanceBeforeFen
            prefs.setPendingRecharge(
                fen = orderFen,
                at = startedAt,
                balanceBeforeFen = placed.cardBalanceBeforeFen,
                cardAccount = placed.cardAccount,
                targetAccount = placed.targetAccount,
                walletBalanceBeforeFen = placed.walletBalanceBeforeFen,
            )
            when (order) {
                is YktRechargeOrder.WechatPay -> {
                    // 直拉微信（跳过浏览器与收银台页；weixin://wap/pay 由微信客户端接手）
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(order.wechatUrl),
                    )
                    if (launchExternal(intent)) {
                        onResult(NoticeFeedback("已拉起微信支付，支付完成后回到本页等待到账", NoticeTone.Info))
                    } else {
                        onResult(NoticeFeedback("未找到微信，请确认已安装微信后重试", NoticeTone.Error))
                    }
                }
                is YktRechargeOrder.Cashier -> {
                    // 兜底：打开官方收银台（服务端 302 下发的完整 URL）
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(order.cashierUrl),
                    )
                    if (!launchExternal(intent)) {
                        onResult(NoticeFeedback("无法打开浏览器，请检查设备后重试", NoticeTone.Error))
                        return@launch
                    }
                    onResult(NoticeFeedback("已跳转官方收银台，支付完成后回到本页等待到账", NoticeTone.Info))
                }
            }
            watchRechargeArrivalLaunch(
                username = saved.username,
                password = saved.password,
                orderFen = orderFen,
                startedAt = startedAt,
            )
        }
    }

    /** 下单后启动轮询（基线/持久化已就绪；startedAt=下单时刻，进程重启恢复也用它）。 */
    private fun watchRechargeArrivalLaunch(username: String, password: String, orderFen: Long, startedAt: Long) {
        startArrivalWatch(username, password, orderFen, startedAt)
    }

    /** "12.50" → 1250 分；调用前已经 RechargeSheet 校验过，异常值返回 0（只影响流水判定精度）。 */
    private fun parsedFenOf(yuan: String): Long =
        yuan.toBigDecimalOrNull()
            ?.multiply(java.math.BigDecimal(100))
            ?.toLong()
            ?: 0L

    /**
     * 到账轮询：5 秒一轮余额检测（用户要求的高频口径）+ 每 3 轮（15 秒）一次流水增量。
     * 从微信返回、甚至进程被杀重启后，[onHostResume]/init 都会立即补检一轮（见下）。
     */
    private fun startArrivalWatch(username: String, password: String, orderFen: Long, startedAt: Long) {
        watchJob?.cancel()
        _arrivalState.value = ArrivalState.Watching(orderFen = orderFen, startedAt = startedAt)
        watchJob = viewModelScope.launch {
            val deadline = startedAt + ARRIVAL_WATCH_MS
            var round = 0
            while (isActive && System.currentTimeMillis() < deadline) {
                // 渐进间隔（YktArrival.arrivalPollIntervalMs）：前 2 分钟 5 秒，之后 15 秒、
                // 60 秒——30 分钟窗口约 100 条 vs 固定 5 秒的约 480 条；流水「每 3 轮」
                // 的比例不变，间隔拉长后流水频率自动等比下降
                kotlinx.coroutines.delay(
                    YktArrival.arrivalPollIntervalMs(System.currentTimeMillis() - startedAt),
                )
                round++
                if (checkArrivalOnce(username, password, orderFen, startedAt)) return@launch
                // 流水同步降频：每 3 轮（15s）一次，减轻服务端压力
                if (round % 3 == 0) {
                    if (checkTurnoverArrival(username, password, orderFen, startedAt)) return@launch
                }
            }
            prefs.clearPendingRecharge()
            _pendingConfirmVisible.value = false
            _arrivalState.value = ArrivalState.Timeout(orderFen = orderFen)
            clearArrivalBaseline()
        }
    }

    /**
     * 单轮到账检测（余额口径）。**有基线才判**（基线未知时不能用"余额 > 基线"——会把
     * 未到账误报成到账），且只认**付款那张卡**的增长：判定口径在 [YktArrival.balanceArrival]。
     * 基线/卡号缺失（升级前的旧记录）时本口径直接放弃，交给流水口径。
     */
    private suspend fun checkArrivalOnce(
        username: String,
        password: String,
        orderFen: Long,
        startedAt: Long,
    ): Boolean {
        try {
            if (_arrivalTarget != null) {
                // 电子账户：查目标钱包当前余额，与钱包基线比对（卡余额不动）。
                // force = true：到账判定必须看实时值，吃缓存会把「已到账」判成「还没到」
                val current = repo.rechargeAccountDetail(username, password, force = true)
                val arrivedFen = YktArrival.walletArrival(
                    balanceBeforeFen = _arrivalWalletBaseFen,
                    orderFen = orderFen,
                    currentFen = current?.second,
                )
                if (arrivedFen != null) {
                    markArrived(orderFen = orderFen, newBalanceFen = arrivedFen, startedAt = startedAt)
                    if (current != null) refreshBalanceSilently(username, password)
                    return true
                }
            } else {
                val cards = repo.cards(username, password, force = true)
                if (cards.isNotEmpty()) {
                    applySnapshot(buildSnapshot(cards))
                    val arrivedFen = YktArrival.balanceArrival(
                        account = _arrivalAccount,
                        balanceBeforeFen = _arrivalBaseFen,
                        orderFen = orderFen,
                        cardBalances = cards.associate { it.account to it.cardBalanceFen },
                    )
                    if (arrivedFen != null) {
                        markArrived(orderFen = orderFen, newBalanceFen = arrivedFen, startedAt = startedAt)
                        return true
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 余额失败不中断，流水口径再试
        }
        return false
    }

    /** 到账后刷一次余额快照（静默；电子账户到账时让生活页余额立即更新）。 */
    private fun refreshBalanceSilently(username: String, password: String) {
        viewModelScope.launch {
            runCatching {
                val cards = repo.cards(username, password, force = true)
                if (cards.isNotEmpty()) applySnapshot(buildSnapshot(cards))
            }
        }
    }

    /**
     * 判定到账的三处动作只有这一份：置 Arrived、撤「正在确认」弹窗、清持久化等待记录。
     *
     * [startedAt] = 本次充值流程起点（下单时刻）：收件箱留痕键（`InboxKeys.YKT_RECHARGE_PREFIX`）
     * 由它构成——进程被杀后恢复等待态走的也是同一个值，同一次充值的重查同键落更新。
     */
    private suspend fun markArrived(orderFen: Long, newBalanceFen: Long?, startedAt: Long) {
        // 充值到账留痕收件箱（2026-10-22）：「钱进了卡」是可回查的事实，静默入箱不点红点。
        // post 内部吞错（旁路纪律），留痕失败不影响到账收尾。
        Graph.inboxRepository(appContext).post(
            kind = InboxKind.YKT_RECHARGE,
            dedupeKey = InboxKeys.YKT_RECHARGE_PREFIX + startedAt,
            title = "一卡通充值到账",
            body = buildString {
                append("充值 ¥").append("%.2f".format(orderFen / 100.0))
                newBalanceFen?.let { append(" · 余额 ¥%.2f".format(it / 100.0)) }
            },
            // 落点 = 消费流水页（充值记录以收入行在那里可回查）；静默留痕不点红点
            route = SubpageScreen.CAMPUS_STATEMENT.name,
            silent = true,
        )
        _arrivalState.value = ArrivalState.Arrived(orderFen = orderFen, newBalanceFen = newBalanceFen)
        _pendingConfirmVisible.value = false
        prefs.clearPendingRecharge()
        clearArrivalBaseline()
    }

    private suspend fun checkTurnoverArrival(
        username: String,
        password: String,
        orderFen: Long,
        startedAt: Long,
    ): Boolean {
        try {
            // force = true：这是到账核对，闸门挡掉就等于到账永远发现不了
            syncer.sync(username, password, maxPages = 1, force = true)
            val count = db.yktTurnoverDao().countIncomeSince(startedAt)
            if (count > 0) {
                // 流水可能先于余额接口更新：此时 _balance 里的快照仍是充值前的数字，
                // 直接端给「充值成功」弹窗就会把充值前余额写成「当前卡余额」。
                // 只有按到账口径确认余额已经涨了订单金额才展示，否则宁可不显示。
                val snapshot = _balance.value
                val newBalanceFen = if (_arrivalTarget != null) {
                    YktArrival.walletArrival(
                        balanceBeforeFen = _arrivalWalletBaseFen,
                        orderFen = orderFen,
                        currentFen = snapshot?.accountFen,
                    )
                } else {
                    YktArrival.balanceArrival(
                        account = _arrivalAccount,
                        balanceBeforeFen = _arrivalBaseFen,
                        orderFen = orderFen,
                        cardBalances = snapshot?.cards
                            ?.associate { it.account to it.cardBalanceFen }
                            .orEmpty(),
                    )
                }
                markArrived(orderFen = orderFen, newBalanceFen = newBalanceFen, startedAt = startedAt)
                return true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 下一轮再试
        }
        return false
    }

    /**
     * 页面回到前台（从微信返回等）：立即补检一轮——不等下一个 5 秒周期，
     * 这是「支付完回来马上看到结果」的关键；进程被杀重启时同时恢复等待态。
     *
     * 本回调在**每次**进页/回前台都会触发（生命周期观察者补齐时会补发 ON_RESUME），
     * 所以「正在确认到账」弹窗按**每笔充值一次**弹：弹过就落库标记，之后同一笔充值
     * 不再弹，等待态改由余额卡的「正在等待到账」行承载。用户关掉弹窗不影响轮询。
     */
    fun onHostResume() {
        val st = _arrivalState.value
        if (st is ArrivalState.Arrived || st is ArrivalState.Timeout) return // 已有结论待用户确认
        viewModelScope.launch {
            val saved = credentialStore.read() ?: return@launch
            val pending = prefs.pendingRecharge.first()
            val orderFen: Long
            val startedAt: Long
            when {
                st is ArrivalState.Watching -> {
                    orderFen = st.orderFen
                    startedAt = st.startedAt
                }
                pending != null -> {
                    orderFen = pending.orderFen
                    startedAt = pending.startedAt
                    restoreArrivalBaseline(pending)
                }
                else -> return@launch
            }
            if (_arrivalState.value !is ArrivalState.Watching) {
                _arrivalState.value = ArrivalState.Watching(orderFen = orderFen, startedAt = startedAt)
            }
            if (checkArrivalOnce(saved.username, saved.password, orderFen, startedAt)) return@launch // 成功弹窗已就位
            // 未立即到账：给出「正在确认」反馈弹窗——延迟一拍（等返回动画/窗口稳定），
            // 避免回到前台瞬间弹窗引发顶栏 insets 重算跳动（2026-09-21 实测）
            if (pending != null && !pending.confirmShown) {
                kotlinx.coroutines.delay(PENDING_CONFIRM_DELAY_MS)
                if (_arrivalState.value is ArrivalState.Arrived) return@launch
                _pendingConfirmVisible.value = true
                prefs.markPendingRechargeConfirmShown()
            }
            if (!checkTurnoverArrival(saved.username, saved.password, orderFen, startedAt)) {
                startArrivalWatch(saved.username, saved.password, orderFen, startedAt)
            }
        }
    }

    /**
     * 从持久化记录恢复余额口径的基线（付款前卡余额 + 付款卡号）。
     *
     * 两者缺一就无法判定，只能退到流水口径；**不拿「现在的余额」当基线**——
     * 钱已经到账时那等于把判定门槛抬到自己头上，「确认到账」永远不成立。
     *
     * **以 pending 为准整体覆盖，不是判空补缺**（2026-10-07 修「余额更新了但确认弹窗
     * 不弹」）：本 VM 只在 `_arrivalState` 不是 Watching（= 没有活跃轮询，内存基线必是
     * **上一笔充值残留**）时才走到这里。上一笔基线偏高时，下一笔的判定门槛被抬成
     * 「旧基线 + 新订单额」，钱到了也判不到——一直卡「正在确认」直到超时。典型触发：
     * 两笔充值从不同入口发起（生活页与设置页是两个 Activity 各持一份 VM），另一份
     * VM 恢复 pending 时沿用了自己残留的旧基线。每笔充值的基线唯一来源 = 持久化
     * pending；活跃轮询路径（st is Watching）不经过这里，正在跑的那条链不受影响。
     */
    private fun restoreArrivalBaseline(pending: PendingRecharge) {
        _arrivalBaseFen = pending.balanceBeforeFen
        _arrivalAccount = pending.cardAccount
        _arrivalTarget = pending.targetAccount
        _arrivalWalletBaseFen = pending.walletBalanceBeforeFen
    }

    /** 内存基线四件套一并清空（到账结论落定 / 用户声明未付款时调用）。 */
    private fun clearArrivalBaseline() {
        _arrivalBaseFen = null
        _arrivalAccount = null
        _arrivalTarget = null
        _arrivalWalletBaseFen = null
    }

    /** 「正在确认到账」提示弹窗可见性（回到前台且未到账时显示；到账/超时/用户关闭即撤）。 */
    private val _pendingConfirmVisible = MutableStateFlow(false)
    val pendingConfirmVisible: StateFlow<Boolean> = _pendingConfirmVisible

    fun dismissPendingConfirm() {
        _pendingConfirmVisible.value = false
    }

    /**
     * 用户声明「我没有付款」：停到账轮询、清等待态与持久化记录。
     * 未支付订单由平台 30 分钟自动失效，不扣款（DESIGN §4.19）。
     */
    fun notPaid() {
        watchJob?.cancel()
        watchJob = null
        _pendingConfirmVisible.value = false
        _arrivalState.value = ArrivalState.Idle
        clearArrivalBaseline()
        viewModelScope.launch { prefs.clearPendingRecharge() }
    }

    /**
     * 到账判定的余额基线：付款前该卡余额（分）+ 付款卡号。
     * 下单时从 [edu.jxslu.schedule.data.ykt.YktRechargeStart] 拿到，并随未确认充值落库；
     * 进程重启后由 [restoreArrivalBaseline] 从记录里恢复。取不到就是 null → 只用流水口径。
     */
    @Volatile
    private var _arrivalBaseFen: Long? = null

    @Volatile
    private var _arrivalAccount: String? = null

    /** 充值目标：`<account>-000` = 电子账户（到账判定走钱包口径）；null = 正式卡。 */
    @Volatile
    private var _arrivalTarget: String? = null

    /** 充电子账户时：付款前目标钱包余额（分）。 */
    @Volatile
    private var _arrivalWalletBaseFen: Long? = null

    private var watchJob: kotlinx.coroutines.Job? = null

    /** 充值到账状态（UI 据此渲染等待卡/成功弹窗/超时提示）。 */
    sealed interface ArrivalState {
        data object Idle : ArrivalState

        /** 等待到账：[orderFen] 下单金额（分），[startedAt] 下单时刻。 */
        data class Watching(val orderFen: Long, val startedAt: Long) : ArrivalState

        /** 已到账：[newBalanceFen] 可为 null（流水先出而余额视图未刷新）。 */
        data class Arrived(val orderFen: Long, val newBalanceFen: Long?) : ArrivalState

        /** 窗口内未检测到（多为到账延迟，账单页会自动补显）。 */
        data class Timeout(val orderFen: Long) : ArrivalState
    }

    private val _arrivalState = MutableStateFlow<ArrivalState>(ArrivalState.Idle)
    val arrivalState: StateFlow<ArrivalState> = _arrivalState

    fun dismissArrival() {
        _arrivalState.value = ArrivalState.Idle
    }

    companion object {
        private const val LOGIN_TOTAL_TIMEOUT_MS = 30_000L

        /** 充值下单总超时（queryCard + thirdOrder 两跳，正常 1–3 秒）。 */
        private const val RECHARGE_TIMEOUT_MS = 20_000L

        /** 到账轮询总时长（30 分钟：饭卡充值常见延迟数分钟至数十分钟）。 */
        private const val ARRIVAL_WATCH_MS = 30 * 60_000L

        /** 返回前台后「确认中」弹窗的延迟（ms）：避开返回动画期的窗口/insets 重排。 */
        private const val PENDING_CONFIRM_DELAY_MS = 800L

        fun Factory(context: Context, autoFetch: Boolean = true) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                CampusCardViewModel(context.applicationContext, autoFetch) as T
        }
    }
}
