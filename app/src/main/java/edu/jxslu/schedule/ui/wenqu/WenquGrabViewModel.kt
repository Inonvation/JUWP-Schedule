package edu.jxslu.schedule.ui.wenqu

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import edu.jxslu.schedule.data.prefs.DisplayPrefsStore
import edu.jxslu.schedule.data.wenqu.WenquActivity
import edu.jxslu.schedule.data.wenqu.WenquGrabSpeed
import edu.jxslu.schedule.data.wenqu.WenquRepository
import edu.jxslu.schedule.domain.WenquRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * 蹲点抢活动页状态机（DESIGN §3.28 / §4.45，2026-10-11；用户口径）。
 *
 * 学校很多志愿活动周日中午 12:00 发布、当场开抢，服务器一到点就卡。这个页面把
 * 「手动刷新 → 找活动 → 点详情 → 报名 → 确认」压成「到点自动检测 → 点一下确认报名」：
 *
 * - **只发一种请求**：每轮 1 个 `volunteer/v2` 第一页探测（[WenquRepository.grabProbe]），
 *   不拉工时 / 星级、不整份重抓；探测结果顺手并进快照，列表跟着刷新。
 *   盯了指定活动时**并发**拉它一发的实时详情（2026-10-16：改并发 + 短超时——原先
 *   串行在后、还挂 30 秒超时，高峰期一个人就能把一轮拖到 30 秒以上）；
 * - **串行 + 退避（2026-10-16 改）**：上一轮不回来不发下一轮；发布窗口内失败也走
 *   1 秒快重试（12:00 的拥堵恰好表现为超时），窗口外才退避（6/9/12 秒 + ±20% 抖动）；
 * - **命中即弹、不自动提交**：`grabFirstMatch`（名称匹配 + 现在能报名）命中就弹
 *   「确认报名」框——提交仍然只由用户那一下点击触发（同 `writeOnce` 的写路径纪律）。
 *   同一条只弹一次，**弹过之后检测继续**（先弹到旧一批、12:00 新品出现还会再弹）；
 *   报名成功 / 手动停止 / 窗口到点才收工。自动提交只发生在用户单独开启的「预报名」里；
 * - **窗口（2026-10-16 改）**：点「开始蹲点」立即检测，不再待机到 11:59:50；
 *   检测窗口自开蹲起算 10 分钟、开始早于发布时锚到「发布后 10 分钟」；
 *   离发布超过 5 分钟走 10 秒慢档，加密窗口前缘放宽到 20 秒（平台可能提前放行）。
 *
 * 蹲点循环挂在 [viewModelScope] 上：**离开本页（VM 销毁）= 停止蹲点**，这是设计前提
 * （用户口径：手动开、人盯着）。屏幕常亮、命中震动由页面层负责。
 */
class WenquGrabViewModel(
    private val repo: WenquRepository,
    private val prefs: DisplayPrefsStore,
) : ViewModel() {

    /** 蹲点阶段。 */
    enum class Phase { Idle, Checking, Stopped }

    data class UiState(
        val loggedIn: Boolean = false,
        /** 目标活动名（预填，落盘；匹配在开始那一刻取它的 trim 副本）。 */
        val keyword: String = "",
        /** 盯着的指定活动 id（0 = 没盯）：每轮单独拉它的实时详情，见 `wenqu_grab_pinned_id`。 */
        val pinnedId: Long = 0L,
        /** 「预报名」瞄准的活动（0 = 没排）：状态行展示 + 取消入口，见 `WenquPreregReminder`。 */
        val preregId: Long = 0L,
        val preregTitle: String = "",
        val preregOpensAt: Long = 0L,
        val phase: Phase = Phase.Idle,
        /** 已发出的探测次数（成功 / 失败都算）。 */
        val checks: Int = 0,
        /** 最近一次探测的完成时刻（0 = 还没查过）。 */
        val lastCheckAt: Long = 0L,
        /** 上一轮从开跑到落地的耗时（毫秒；0 = 还没查过）——给「上轮 X.Xs」诊断行用。 */
        val lastRoundMs: Long = 0L,
        /** 连续失败轮数（成功即清零）。 */
        val failures: Int = 0,
        /** 收工原因（Stopped 时展示）：命中 / 手动停止 / 窗口结束 / 报名受限 / 报名成功。 */
        val stopReason: String = "",
        val items: List<WenquActivity> = emptyList(),
        val indexAvailable: Boolean = false,
        val indexing: Boolean = false,
        val refreshing: Boolean = false,
        /** 账号级报名限制（平台 `isBlack` / `stop_apply_reason`），每轮探测核对。 */
        val blacklisted: Boolean = false,
        val stopApplyReason: String = "",
        /** 「过滤已满」（复用主页那份落盘值，一个 App 里同一个词只有一份口径）。 */
        val hideFull: Boolean = false,
        /** 命中弹窗的活动；null = 不弹。 */
        val hit: WenquActivity? = null,
        val applying: Boolean = false,
        val hitError: String? = null,
        /** 「先看看」/ 点卡片打开的详情弹层。 */
        val detail: WenquActivity? = null,
        val detailLoading: Boolean = false,
        val detailUnavailable: Boolean = false,
        val detailActionError: String? = null,
        val message: String? = null,
    ) {
        /**
         * 列表展示（2026-10-11 按用户实测反馈改）：
         * 1. 本地过滤：只做「过滤已满」（复用主页那份落盘值）；
         * 2. **目标活动名的匹配项置前**（名称 / 项目名 / 单位 / 地点，与搜索同一份
         *    归一化）——名字填下去，相关活动立刻浮到最上面；
         * 3. 组内按 [WenquRules.displayRank] 排（招募中优先，只重排不隐藏）。
         */
        val visibleActivities: List<WenquActivity>
            get() {
                val filtered = items.filter {
                    WenquRules.matchesFilter(
                        status = it.status,
                        peopleNum = it.peopleNum,
                        balanceNum = it.balanceNum,
                        statusFilter = null,
                        hideFull = hideFull,
                        appliedOnly = false,
                        applied = it.applied,
                    )
                }
                if (keyword.isBlank()) {
                    return filtered.sortedBy { WenquRules.displayRank(it.status) }
                }
                val (matched, rest) = filtered.partition {
                    WenquRules.matchesKeyword(keyword, it.title, it.projectName, it.unit, it.address)
                }
                return matched.sortedBy { WenquRules.displayRank(it.status) } +
                    rest.sortedBy { WenquRules.displayRank(it.status) }
            }

        /** 盯着的指定活动（从快照里找）；没盯 / 快照里没有回 null。 */
        val pinnedActivity: WenquActivity?
            get() = if (pinnedId > 0L) items.firstOrNull { it.id == pinnedId } else null
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** 蹲点循环（Waiting → Checking → Stopped）。VM 销毁（离开页面）即取消。 */
    private var grabJob: Job? = null

    /**
     * 已弹过确认框的活动 id（本次蹲点内）。同一条只弹一次；弹过之后检测**继续**——
     * 先弹到的可能是旧一批的活动，12:00 新品出现还会再弹（用户实测口径，2026-10-11）。
     */
    private val shownHitIds = HashSet<Long>()

    private var loaded = false

    init {
        viewModelScope.launch {
            prefs.wenquHideFull.collect { saved -> _state.update { it.copy(hideFull = saved) } }
        }
        viewModelScope.launch {
            prefs.wenquGrabPinnedId.collect { id -> _state.update { it.copy(pinnedId = id) } }
        }
        viewModelScope.launch {
            prefs.wenquPreregId.collect { saved -> _state.update { it.copy(preregId = saved) } }
        }
        viewModelScope.launch {
            prefs.wenquPreregTitle.collect { saved -> _state.update { it.copy(preregTitle = saved) } }
        }
        viewModelScope.launch {
            prefs.wenquPreregOpensAt.collect { saved -> _state.update { it.copy(preregOpensAt = saved) } }
        }
        viewModelScope.launch {
            repo.loggedIn.collect { loggedIn ->
                _state.update { it.copy(loggedIn = loggedIn) }
                if (loggedIn) {
                    ensureLoaded()
                } else {
                    loaded = false
                    stopGrab(cancelJob = true, reason = "")
                    _state.update {
                        it.copy(
                            items = emptyList(),
                            indexAvailable = false,
                            blacklisted = false,
                            stopApplyReason = "",
                            hit = null,
                            hitError = null,
                            detail = null,
                            detailLoading = false,
                            detailUnavailable = false,
                            detailActionError = null,
                        )
                    }
                }
            }
        }
    }

    /**
     * 进页：列表先用**本地快照**铺上（零请求），再按 30 分钟闸门决定要不要增量同步
     * （同主页 `activityIndex` 的口径）。目标活动名从落盘值预填回来。
     */
    fun ensureLoaded() {
        if (!repo.loggedIn.value) return
        val cached = repo.cachedIndex()
        _state.update {
            it.copy(
                items = cached?.let { c -> corrected(c.items) } ?: it.items,
                indexAvailable = cached != null,
                blacklisted = cached?.blacklisted == true,
                stopApplyReason = cached?.stopApplyReason ?: it.stopApplyReason,
            )
        }
        if (loaded) return
        loaded = true
        viewModelScope.launch {
            val saved = prefs.wenquGrabKeyword.first()
            _state.update { it.copy(keyword = saved) }
        }
        viewModelScope.launch { loadIndex() }
    }

    /**
     * **开始蹲点**（用户显式动作）：预热 token → 立即开跑 → 每 3 秒探测一次
     * （发布窗口内 1 秒、离发布还远 10 秒）→ 命中 / 手动停止 / 窗口结束收工。
     * **全程不自动提交**：命中只弹确认框，提交仍由用户点出来
     * （自动提交只发生在用户单独开启的「预报名」里）。
     */
    fun start() {
        if (!repo.loggedIn.value) return
        if (_state.value.phase == Phase.Checking) return
        val keyword = _state.value.keyword.trim()
        if (keyword.isBlank() && _state.value.pinnedId <= 0L) {
            _state.update { it.copy(message = "先填要蹲的活动名，或盯一条活动") }
            return
        }
        // 「被限制报名」不在这里拦：快照里的 isBlack 在缓存期内只升不降，
        // 拿它拦启动会让**已解除限制**的账号在快照过期前开不了蹲点。
        // 探测每一轮都回实时值，真被限制时第一轮就会停并用平台原话说明（见下）。
        grabJob?.cancel()
        shownHitIds.clear()
        val armedAt = System.currentTimeMillis()
        _state.update {
            it.copy(
                phase = Phase.Checking,
                checks = 0,
                lastCheckAt = 0L,
                lastRoundMs = 0L,
                failures = 0,
                stopReason = "",
                hit = null,
                hitError = null,
                detailActionError = null,
            )
        }
        grabJob = viewModelScope.launch {
            // 预热 token：要重登就现在登，别让它挤在 12:00 的第一轮里（失败静默，
            // 探测自己也会走 authed 的续期路径）
            runCatching { repo.prewarmSession() }
            var failures = 0
            while (isActive) {
                // 发布窗口的锚点：盯了活动就用它的**实际报名开始时刻**（周二 / 周三的
                // 定点开放也能吃到 1 秒档与短超时）；没盯才退回「周日 12:00」的旧口径。
                // 每轮重算：列表项可能一开始没带报名时间，盯的详情回来后就会补上。
                val pinnedOpenAt = _state.value.pinnedActivity
                    ?.let { WenquRules.stampMillis(it.applyStartTime) }
                    ?: 0L
                val publishAt = WenquRules.grabPublishAt(
                    armedAt,
                    pinnedOpenAt = pinnedOpenAt,
                )
                if (WenquRules.grabShouldStop(
                        checks = _state.value.checks,
                        detectStartedAt = armedAt,
                        now = System.currentTimeMillis(),
                        publishAt = publishAt,
                    )
                ) {
                    _state.update {
                        it.copy(phase = Phase.Stopped, stopReason = "检测窗口结束，已自动停止")
                    }
                    break
                }
                // 命中弹窗开着：先不探测（等用户处理完），别在弹窗后面继续发请求
                if (_state.value.hit != null) {
                    delay(DIALOG_PAUSE_MS)
                    continue
                }
                // 目标名以**最新输入**为准（页面允许随时改）：改了就改蹲新名字，清空即停
                // （盯了指定活动时不算空——那一路不看名字）
                val keyword = _state.value.keyword.trim()
                if (keyword.isBlank() && _state.value.pinnedId <= 0L) {
                    stopGrab(cancelJob = false, reason = "目标活动名为空，已停止")
                    break
                }
                val inBurst = WenquRules.grabInBurst(System.currentTimeMillis(), publishAt)
                val speed = if (inBurst) WenquGrabSpeed.Burst else WenquGrabSpeed.Fast
                val pinnedId = _state.value.pinnedId
                val roundStartedAt = System.currentTimeMillis()
                var pinnedFresh: WenquActivity? = null
                val probe = coroutineScope {
                    // 盯的那条与列表探测**并发**（2026-10-16）：原先它串行在后、还挂
                    // 30 秒超时，高峰期一个人就能把一轮拖到 30 秒以上。失败静默
                    // （列表探测的失败已经计过退避，别把它算两次）。
                    val pinnedJob = if (pinnedId > 0L) {
                        async {
                            try {
                                repo.refreshIndexItem(pinnedId, speed)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                null
                            }
                        }
                    } else {
                        null
                    }
                    val result = try {
                        repo.grabProbe(speed = speed)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        failures++
                        null
                    }
                    pinnedFresh = pinnedJob?.await()
                    result
                }
                // 盯的活动已经等不到了（结束 / 取消 / 报名截止 / 已报名）：自动收摊，
                // 别每轮再白拉它的详情（2026-10-16；与「盯这条」入口用同一份判据）。
                val freshPinned = pinnedFresh
                if (freshPinned != null && WenquRules.pinFutile(
                        status = freshPinned.status,
                        applied = freshPinned.applied,
                        applyEndTime = freshPinned.applyEndTime,
                        now = System.currentTimeMillis(),
                    )
                ) {
                    unpin()
                    _state.update {
                        it.copy(
                            message = if (freshPinned.applied) {
                                "已报上名，自动取消盯着"
                            } else {
                                "盯的活动不能再报名了，自动取消盯着"
                            },
                        )
                    }
                }
                val checkedAt = System.currentTimeMillis()
                if (probe != null) {
                    failures = 0
                    if (probe.blacklisted) {
                        // 账号级限制：继续蹲没有意义，停 + 用平台原话说清
                        _state.update {
                            it.copy(
                                checks = it.checks + 1,
                                lastCheckAt = checkedAt,
                                lastRoundMs = checkedAt - roundStartedAt,
                                failures = failures,
                                blacklisted = true,
                                stopApplyReason = probe.stopApplyReason.ifBlank { it.stopApplyReason },
                                phase = Phase.Stopped,
                                stopReason = WenquRules.applyBlockedText(
                                    blacklisted = true,
                                    stopApplyReason = probe.stopApplyReason,
                                ),
                            )
                        }
                        break
                    }
                }
                _state.update {
                    it.copy(
                        checks = it.checks + 1,
                        lastCheckAt = checkedAt,
                        lastRoundMs = checkedAt - roundStartedAt,
                        failures = failures,
                    )
                }
                syncFromRepo(fallback = probe?.items.orEmpty())
                // 命中在**整份快照**里找（不只是刚才这一页）：列表里看得见、现在能报名的
                // 匹配项也要弹（2026-10-11 用户实测口径）。同一条只弹一次，
                // 弹过之后检测继续——先弹到旧的、12:00 新品出现还会再弹。
                val hit = WenquRules.grabFirstMatch(
                    items = _state.value.items,
                    keyword = keyword,
                    blacklisted = false,
                    now = System.currentTimeMillis(),
                    pinnedId = pinnedId,
                )
                if (hit != null && hit.id > 0L && shownHitIds.add(hit.id)) {
                    _state.update { it.copy(hit = hit) }
                }
                val nowForDelay = System.currentTimeMillis()
                val baseDelay = WenquRules.grabNextDelayMs(
                    consecutiveFailures = failures,
                    now = nowForDelay,
                    publishAt = publishAt,
                )
                // 窗口外的失败退避加 ±20% 抖动（2026-10-16）；窗口内的加密节奏保持确定，
                // 那里要的是「每一发都落在该落的点上」。
                val delayMs = if (failures > 0 && !WenquRules.grabInBurst(nowForDelay, publishAt)) {
                    WenquRules.jitteredDelayMs(baseDelay, Random.nextDouble())
                } else {
                    baseDelay
                }
                delay(delayMs)
            }
        }
    }

    /** 手动停止（幂等）：只对等待中 / 检测中有意义。 */
    fun stop() {
        stopGrab(cancelJob = true, reason = "已手动停止")
    }

    private fun stopGrab(cancelJob: Boolean, reason: String) {
        val job = grabJob
        grabJob = null
        if (cancelJob) job?.cancel()
        _state.update {
            if (it.phase == Phase.Checking) {
                it.copy(phase = Phase.Stopped, stopReason = reason)
            } else {
                it
            }
        }
    }

    /**
     * 改目标活动名（落盘预填）。页面**随时可改**：正在进行的蹲点每轮都读最新值，
     * 改了就改蹲新名字，清空即停（用户实测反馈，2026-10-11）。
     */
    fun setKeyword(value: String) {
        _state.update { it.copy(keyword = value) }
        viewModelScope.launch { prefs.setWenquGrabKeyword(value) }
    }

    /**
     * **盯一条指定活动**（2026-10-15，DESIGN §4.45「盯指定活动」）：落盘 + 立刻生效。
     * 与目标名**并行**——名字那路照蹲，这一路只盯这一条（不看名字）；已满的活动会一直
     * 不命中，直到有人退名额（`balance_num` 转正）才弹。
     */
    fun pin(activity: WenquActivity) {
        if (activity.id <= 0L) return
        viewModelScope.launch {
            prefs.setWenquGrabPinnedId(activity.id)
            // 换了目标：清「已报过」键，让这条也能报一次「名额空出」
            prefs.setWenquGrabPinnedAlertedId(0L)
        }
        _state.update { it.copy(pinnedId = activity.id) }
    }

    /** 取消盯（落盘回 0）。 */
    fun unpin() {
        viewModelScope.launch {
            prefs.setWenquGrabPinnedId(0L)
            prefs.setWenquGrabPinnedAlertedId(0L)
        }
        _state.update { it.copy(pinnedId = 0L) }
    }

    /** 「过滤已满」（复用主页那份落盘值，一处开关两边一致）。 */
    fun setHideFull(value: Boolean) {
        _state.update { it.copy(hideFull = value) }
        viewModelScope.launch { prefs.setWenquHideFull(value) }
    }

    /** 下拉刷新：强刷快照（用户显式动作，绕过 30 分钟闸门）。 */
    fun refresh() {
        if (!repo.loggedIn.value) return
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true) }
            try {
                val index = repo.activityIndex(force = true)
                _state.update {
                    it.copy(
                        items = index?.let { loaded -> corrected(loaded.items) } ?: it.items,
                        indexAvailable = index != null || it.indexAvailable,
                        blacklisted = index?.blacklisted ?: it.blacklisted,
                        stopApplyReason = index?.stopApplyReason ?: it.stopApplyReason,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 静默：旧列表留着，用户想再试就再拉一次
            } finally {
                _state.update { it.copy(refreshing = false) }
            }
        }
    }

    // ---- 命中弹窗（直接弹「确认报名」，**不自动提交**）---------------------------

    /** 命中弹窗「确认报名」：只发一次（仓库写路径纪律），失败原因留在弹窗里。 */
    fun applyHit() {
        val hit = _state.value.hit ?: return
        if (_state.value.applying || hit.id <= 0L) return
        _state.update { it.copy(applying = true, hitError = null) }
        viewModelScope.launch {
            try {
                repo.applyActivity(hit.id)
                syncFromRepo()
                // 目标已拿下：收工（弹窗关掉 + 检测停止；手动停止 / 窗口到点同样收工）
                stopGrab(cancelJob = true, reason = "报名成功")
                _state.update {
                    it.copy(
                        applying = false,
                        hit = null,
                        message = "报名成功",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(applying = false, hitError = e.message ?: "报名失败") }
            }
        }
    }

    /** 关掉命中弹窗（不报名）：活动已经在列表里，用户随时能再点开。 */
    fun dismissHit() {
        _state.update { it.copy(hit = null, hitError = null) }
    }

    /** 命中弹窗「先看看」：关弹窗、开详情（详情里仍有报名入口）。 */
    fun openHitDetail() {
        val hit = _state.value.hit ?: return
        _state.update { it.copy(hit = null, hitError = null) }
        openDetail(hit)
    }

    // ---- 详情弹层（点列表卡 / 「先看看」）----------------------------------------

    /** 打开活动详情：入口那条先顶上（弹层立刻有内容），详情回来再补齐。 */
    fun openDetail(activity: WenquActivity) {
        _state.update {
            it.copy(
                detail = activity,
                detailLoading = activity.id > 0L,
                detailUnavailable = activity.id <= 0L,
                detailActionError = null,
            )
        }
        if (activity.id <= 0L) return
        viewModelScope.launch {
            try {
                val full = repo.activityDetail(activity.id)
                _state.update {
                    if (it.detail?.id != activity.id) {
                        it
                    } else {
                        it.copy(
                            detail = full ?: it.detail,
                            detailLoading = false,
                            detailUnavailable = full == null,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 静默：弹层里已经有入口那条的信息（同主页口径）
                _state.update {
                    if (it.detail?.id == activity.id) {
                        it.copy(detailLoading = false, detailUnavailable = true)
                    } else {
                        it
                    }
                }
            }
        }
    }

    fun closeDetail() {
        _state.update {
            it.copy(
                detail = null,
                detailLoading = false,
                detailUnavailable = false,
                detailActionError = null,
            )
        }
    }

    /** 详情弹层里报名（纪律同命中弹窗：只发一次、成功关弹层 + 提示、就地刷新列表）。 */
    fun applyActivity(activityId: Long) {
        if (activityId <= 0L || _state.value.applying) return
        _state.update { it.copy(applying = true, detailActionError = null) }
        viewModelScope.launch {
            try {
                repo.applyActivity(activityId)
                syncFromRepo()
                _state.update {
                    it.copy(
                        applying = false,
                        detail = null,
                        detailLoading = false,
                        message = "报名成功",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(applying = false, detailActionError = e.message ?: "报名失败")
                }
            }
        }
    }

    /** 详情弹层里取消报名（纪律同报名）。 */
    fun cancelApply(activityId: Long, applyId: Long = 0L) {
        if (activityId <= 0L || _state.value.applying) return
        _state.update { it.copy(applying = true, detailActionError = null) }
        viewModelScope.launch {
            try {
                repo.cancelApply(activityId, applyId)
                syncFromRepo()
                _state.update {
                    it.copy(
                        applying = false,
                        detail = null,
                        detailLoading = false,
                        message = "已取消报名",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(applying = false, detailActionError = e.message ?: "取消失败")
                }
            }
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    // ---- 内部 -------------------------------------------------------------------

    /**
     * 常规装载（同主页口径：首次整份抓、30 分钟闸门内零请求）。失败静默：
     * 蹲点页宁可显示旧列表也不给错误页——用户来这里是等新活动的。
     */
    private suspend fun loadIndex() {
        if (repo.cachedIndex() == null) _state.update { it.copy(indexing = true) }
        val index = try {
            repo.activityIndex()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        _state.update {
            it.copy(
                indexing = false,
                items = index?.let { loaded -> corrected(loaded.items) } ?: it.items,
                indexAvailable = index != null || it.indexAvailable,
                blacklisted = index?.blacklisted ?: it.blacklisted,
                stopApplyReason = index?.stopApplyReason ?: it.stopApplyReason,
            )
        }
    }

    /**
     * 列表数据源：仓库快照（探测结果 / 写操作后都读它）；快照还没建起来时用探测那页顶着。
     */
    private fun syncFromRepo(fallback: List<WenquActivity> = emptyList()) {
        val cached = repo.cachedIndex()
        _state.update {
            it.copy(
                items = corrected(cached?.items ?: fallback),
                indexAvailable = cached != null || fallback.isNotEmpty() || it.indexAvailable,
                blacklisted = cached?.blacklisted ?: it.blacklisted,
                stopApplyReason = cached?.stopApplyReason ?: it.stopApplyReason,
                indexing = false,
            )
        }
    }

    /** 用不可变的时间字段校正状态（同主页口径，见 `WenquViewModel.corrected`）。 */
    private fun corrected(items: List<WenquActivity>): List<WenquActivity> {
        if (items.isEmpty()) return items
        val now = System.currentTimeMillis()
        return items.map { item ->
            if (item.status == 3) {
                item
            } else {
                item.copy(status = WenquRules.effectiveStatus(item.status, item.endTime, now))
            }
        }
    }

    class Factory(
        private val repo: WenquRepository,
        private val prefs: DisplayPrefsStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            WenquGrabViewModel(repo, prefs) as T
    }
}

/** 命中弹窗开着时的轮询暂停步长：只等用户处理，不发请求。 */
private const val DIALOG_PAUSE_MS = 500L
