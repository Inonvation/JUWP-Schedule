package edu.jxslu.schedule.ui.wenqu

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import edu.jxslu.schedule.data.prefs.DisplayPrefsStore
import edu.jxslu.schedule.data.wenqu.WenquActivity
import edu.jxslu.schedule.data.wenqu.WenquRepository
import edu.jxslu.schedule.data.wenqu.WenquVolunteerRecord
import edu.jxslu.schedule.domain.WenquRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 问渠页状态机（DESIGN §3.28 / §4.45）。
 *
 * 登录链两条：手机号 + 密码（日常），手机号 + 验证码 + 新密码（首次 / 忘记密码）。
 *
 * 取数分两路、都走仓库的进程内缓存（[WenquRepository.activities] 等）：
 * - **我的**：工时 / 星级 / 记录（进页一次，之后 30 分钟内复用）；
 * - **活动**：平台上的志愿服务活动列表（同样 30 分钟内复用），支持关键词检索与翻页。
 *
 * **筛选与排序都在本地**：状态 / 隐藏已满（[WenquRules.matchesFilter]）+ 招募中优先
 * （[WenquRules.displayRank]）。两个筛选**落 DataStore**（用户专门要过「隐藏已满」，
 * 每次进页重置一遍会烦）。
 *
 * 失败只报文案、**不清旧数据**（宁旧勿空：列表空掉比留着旧数据更糟）。
 */
class WenquViewModel(
    private val repo: WenquRepository,
    private val prefs: DisplayPrefsStore,
) : ViewModel() {

    data class UiState(
        val loggedIn: Boolean = false,
        val phone: String = "",
        val hours: Double = 0.0,
        val starLevel: Int = 0,
        val records: List<WenquVolunteerRecord> = emptyList(),
        /** 首次取数中（页面骨架）。 */
        val loading: Boolean = false,

        // ---- 活动列表 ----
        /** 当前检索词（空 = 浏览全量）。 */
        val keyword: String = "",
        /** 本地状态筛选：null = 全部。服务端只支持关键词，状态筛选在本地做。 */
        val statusFilter: Int? = null,
        /** 本地「隐藏已满」：只看还能报名的（[WenquRules.isFull]）。默认关。 */
        val hideFull: Boolean = false,
        val activities: List<WenquActivity> = emptyList(),
        val activitiesLoading: Boolean = false,
        val hasMore: Boolean = false,
        val loadingMore: Boolean = false,
        /**
         * 成功追加一页后自增。页面据此判断「加载更多这一下到底有没有结果」——
         * 新增的卡片在折叠线以下时，光看列表长度用户是没感觉的（2026-10-08 用户报「没反馈」）。
         */
        val loadMoreSeq: Int = 0,
        /**
         * 账号被平台限制报名时的原话（`isBlack` + `stop_apply_reason`）。
         * 空串 = 没被限制。它是**账号级**的，与具体哪条活动无关。
         */
        val applyBlockedReason: String = "",

        // ---- 活动详情 ----
        /** 弹层里的活动：先用入口那条顶着，详情回来补齐（工时 / 群号只有详情有）。 */
        val detail: WenquActivity? = null,
        val detailLoading: Boolean = false,
        /**
         * 详情**取不到**（平台回 `detail: null`，或网络失败）。
         * 这不是错误——弹层里已经有入口那条的信息了，页面只在弹层内挂一行说明，
         * **不飘白色提醒**（2026-10-08 用户报「点记录弹详情，同时飘一条取不到详情」）。
         */
        val detailUnavailable: Boolean = false,

        /** 登录 / 设密码 / 发码进行中（按钮转圈）。 */
        val busy: Boolean = false,
        /** 顶栏刷新进行中（用户显式强刷；缓存命中时它是唯一能看见的反馈）。 */
        val refreshing: Boolean = false,
        /** 一次性提示（Snackbar 消费）。 */
        val message: String? = null,
        /** 发码成功的时刻（驱动 5 分钟倒计时）；0 = 没发过。 */
        val codeSentAt: Long = 0L,
    ) {
        val starLabel: String get() = WenquRules.starLabel(starLevel)
        val hoursText: String get() = WenquRules.formatHours(hours)

        /**
         * 列表的最终形态：**本地筛**（状态 / 隐藏已满）→ **本地稳定排序**（招募中优先）。
         * 平台的顺序既不是 id 也不是 add_time，不排的话默认「全部」打开就是满屏已结束。
         */
        val visibleActivities: List<WenquActivity>
            get() = activities
                .filter {
                    WenquRules.matchesFilter(
                        status = it.status,
                        peopleNum = it.peopleNum,
                        balanceNum = it.balanceNum,
                        statusFilter = statusFilter,
                        hideFull = hideFull,
                    )
                }
                .sortedBy { WenquRules.displayRank(it.status) }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** 是否已经为「当前登录态」拉过一轮（避免登出又登入时重复触发）。 */
    private var loaded = false

    init {
        // 筛选持久化（DESIGN §4.45）：用户专门要过「隐藏已满」，每次进页重置一遍会烦。
        // 用 collect 而不是 runBlocking 读初值——VM 里不阻塞主线程（同 AI 调课那轮的纪律）。
        viewModelScope.launch {
            prefs.wenquStatusFilter.collect { saved ->
                _state.update { it.copy(statusFilter = saved.takeIf { v -> v >= 0 }) }
            }
        }
        viewModelScope.launch {
            prefs.wenquHideFull.collect { saved -> _state.update { it.copy(hideFull = saved) } }
        }
        viewModelScope.launch {
            repo.loggedIn.collect { loggedIn ->
                _state.update { it.copy(loggedIn = loggedIn) }
                if (loggedIn) ensureLoaded() else loaded = false
            }
        }
    }

    /**
     * 进页：把影子先摆上，再按缓存决定要不要真的发请求。
     * 缓存新鲜时这里**一个请求都不发**（用户报的「每次进页都重新获取」的正解）。
     */
    fun ensureLoaded() {
        if (!repo.loggedIn.value) return
        val session = repo.localSession()
        // 先把缓存里的东西摆上（同步、零请求）：不然有缓存也会先闪一下骨架屏
        val cachedMine = repo.cachedVolunteer()
        val cachedActivities = repo.cachedActivities("")
        _state.update {
            it.copy(
                phone = session?.phone ?: it.phone,
                hours = session?.hours ?: it.hours,
                starLevel = session?.starLevel ?: it.starLevel,
                records = cachedMine?.list ?: it.records,
                activities = cachedActivities?.items ?: it.activities,
                hasMore = cachedActivities?.hasMore ?: it.hasMore,
                applyBlockedReason = cachedActivities?.let { page ->
                    WenquRules.applyBlockedText(page.blacklisted, page.stopApplyReason)
                } ?: it.applyBlockedReason,
            )
        }
        if (loaded) return
        loaded = true
        viewModelScope.launch { loadMine(force = false) }
        viewModelScope.launch { loadActivities(force = false, keyword = "") }
    }

    /** 顶栏刷新：我的 + 活动一起强刷（绕过缓存），转圈直到两路都落地。 */
    fun refresh() {
        if (!repo.loggedIn.value) return
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true) }
            try {
                loadMine(force = true)
                loadActivities(force = true, keyword = _state.value.keyword)
            } catch (e: CancellationException) {
                throw e
            } finally {
                _state.update { it.copy(refreshing = false) }
            }
        }
    }

    /** 我的：工时 / 星级 / 记录。星级是锦上添花，单独 try——它失败不该把工时一起吞掉。 */
    private suspend fun loadMine(force: Boolean) {
        _state.update { it.copy(loading = it.records.isEmpty()) }
        try {
            val volunteer = repo.volunteer(force)
            val cert = try {
                repo.starCert(force)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            _state.update {
                it.copy(
                    loading = false,
                    hours = WenquRules.hoursOf(volunteer.totalScore),
                    records = volunteer.list,
                    starLevel = cert?.starLevel ?: it.starLevel,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, message = e.message ?: "取数失败") }
        }
    }

    /** 活动列表第一页（页面按钮 / IME 搜索键的入口，起协程）。 */
    fun loadActivities(keyword: String) {
        viewModelScope.launch { loadActivities(force = false, keyword = keyword) }
    }

    /**
     * 活动列表第一页。
     * 换关键词（[keyword] 与当前不同）时**先把旧列表清掉**——否则搜索结果出来前，
     * 屏上是上一次的词的结果，看着像「搜了没反应」。
     */
    private suspend fun loadActivities(force: Boolean, keyword: String) {
        if (!repo.loggedIn.value) return
        val key = keyword.trim()
        val changed = key != _state.value.keyword
        _state.update {
            it.copy(
                keyword = key,
                activitiesLoading = it.activities.isEmpty() || changed,
                activities = if (changed) emptyList() else it.activities,
                hasMore = if (changed) false else it.hasMore,
            )
        }
        try {
            val page = repo.activities(key, force)
            _state.update {
                it.copy(
                    activitiesLoading = false,
                    activities = page.items,
                    hasMore = page.hasMore,
                    applyBlockedReason = WenquRules.applyBlockedText(
                        page.blacklisted,
                        page.stopApplyReason,
                    ),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update {
                it.copy(activitiesLoading = false, message = e.message ?: "活动列表获取失败")
            }
        }
    }

    /** 翻下一页并追加。 */
    fun loadMore() {
        val current = _state.value
        if (current.loadingMore || !current.hasMore || current.activitiesLoading) return
        viewModelScope.launch {
            _state.update { it.copy(loadingMore = true) }
            try {
                val page = repo.loadMoreActivities(current.keyword)
                _state.update {
                    it.copy(
                        loadingMore = false,
                        activities = page.items,
                        hasMore = page.hasMore,
                        applyBlockedReason = WenquRules.applyBlockedText(
                            page.blacklisted,
                            page.stopApplyReason,
                        ),
                        // 页面靠这个自增去滚到新增项 / 报「新增了几条」
                        loadMoreSeq = it.loadMoreSeq + 1,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loadingMore = false, message = e.message ?: "加载失败") }
            }
        }
    }

    /** 切换本地状态筛选（不发请求，但**落盘**记住）。 */
    fun setStatusFilter(status: Int?) {
        _state.update { it.copy(statusFilter = status) }
        viewModelScope.launch {
            prefs.setWenquStatusFilter(status ?: WenquRules.FILTER_ALL_STORED)
        }
    }

    /** 切换本地「隐藏已满」（不发请求，但**落盘**记住）。 */
    fun setHideFull(hide: Boolean) {
        _state.update { it.copy(hideFull = hide) }
        viewModelScope.launch { prefs.setWenquHideFull(hide) }
    }

    /**
     * 打开活动详情：入口那条先顶上（弹层立刻有内容），详情回来再补齐。
     * 详情取不到**不报错**——弹层里已经有入口那条，页面只在弹层内挂一行说明。
     *
     * [activity] 也可能是**从记录构造的**（`WenquVolunteerRecord.asActivity()`，记录 id 就是活动 id）。
     * 自主（DIY）类记录没有关联活动（id = 0），本地字段已经够看，不发注定失败的请求。
     */
    fun openDetail(activity: WenquActivity) {
        _state.update {
            it.copy(
                detail = activity,
                detailLoading = activity.id > 0L,
                detailUnavailable = activity.id <= 0L,
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
            } catch (e: Exception) {
                // 静默：弹层里已经有列表那条的信息，不该再飘一个白色提醒
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
        _state.update { it.copy(detail = null, detailLoading = false, detailUnavailable = false) }
    }

    /** 手机号 + 密码登录。 */
    fun login(phone: String, password: String) {
        if (!WenquRules.isValidPhone(phone)) {
            _state.update { it.copy(message = "请输入 11 位手机号") }
            return
        }
        if (!WenquRules.isValidPassword(password)) {
            _state.update { it.copy(message = "请输入密码") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                repo.login(phone.trim(), password)
                _state.update { it.copy(busy = false, message = "登录成功") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, message = e.message ?: "登录失败") }
            }
        }
    }

    /** 发「重置密码」验证码；成功记下时刻驱动倒计时。 */
    fun sendCode(phone: String) {
        if (!WenquRules.isValidPhone(phone)) {
            _state.update { it.copy(message = "请输入 11 位手机号") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                repo.sendResetCode(phone.trim())
                _state.update {
                    it.copy(busy = false, codeSentAt = System.currentTimeMillis(), message = "验证码已发送")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, message = e.message ?: "发送失败") }
            }
        }
    }

    /** 首次设置 / 忘记密码：验证码 + 新密码 → 登录。 */
    fun setupPassword(phone: String, captcha: String, password: String) {
        if (!WenquRules.isValidPhone(phone)) {
            _state.update { it.copy(message = "请输入 11 位手机号") }
            return
        }
        if (captcha.isBlank()) {
            _state.update { it.copy(message = "请输入验证码") }
            return
        }
        if (!WenquRules.isValidPassword(password)) {
            _state.update { it.copy(message = "请输入新密码") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                repo.setupPassword(phone.trim(), captcha.trim(), password)
                _state.update { it.copy(busy = false, message = "设置成功，已登录") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, message = e.message ?: "设置失败") }
            }
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    class Factory(
        private val repo: WenquRepository,
        private val prefs: DisplayPrefsStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            WenquViewModel(repo, prefs) as T
    }
}
