package edu.jxslu.schedule.ui.wenqu

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import edu.jxslu.schedule.data.prefs.DisplayPrefsStore
import edu.jxslu.schedule.data.wenqu.WenquActivity
import edu.jxslu.schedule.data.wenqu.WenquActivityIndex
import edu.jxslu.schedule.data.wenqu.WenquRepository
import edu.jxslu.schedule.data.wenqu.WenquVolunteerRecord
import edu.jxslu.schedule.data.wenqu.mergeActivities
import edu.jxslu.schedule.domain.WenquRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
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
 * 取数分两路、都走仓库的进程内缓存（[WenquRepository.activityIndex] 等）：
 * - **我的**：工时 / 星级 / 记录（进页一次，之后 30 分钟内复用）；
 * - **活动**：一份**全量快照**（`activityIndex`，30 分钟内复用）——它同时是列表与搜索的
 *   数据源。**不是服务端第一页**：本地筛选 / 排序都作用在它上面，只拿 20 条的话
 *   「选招募中显示没有、选招募中优先却把旧的已结束排前面」（2026-10-11 用户报）。
 *
 * **浏览与搜索是两种模式**（同一份快照）：
 * - 浏览（[keyword] 空）：列表 = 快照全量，本地筛选 + 排序；下拉刷新强刷快照；
 * - 搜索（[keyword] 非空）：在快照上按 [WenquRules.matchesKeyword] 过滤（活动名 /
 *   项目名 / 单位 / 地点）。快照抓全了就是纯内存、零请求；不完整 / 建不起来时才补一次
 *   服务端关键词查询兜底（见 [doSearch]）。**清空检索词即刻还原浏览列表**，不重新请求。
 *
 * **筛选与排序都在本地**：状态 / 隐藏已满 / 只看我报名的（[WenquRules.matchesFilter]）
 * + 三档排序（[WenquRules.Sort]）。都**落 DataStore**（用户专门要过「隐藏已满」，
 * 每次进页重置一遍会烦）。筛选对搜索同样生效，所以有数据却被筛掉的条数单独暴露出来
 * （[UiState.hiddenByFilter]），页面据此说清「不是没搜到，是被筛掉了」。
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

        // ---- 活动列表（= 本地全量索引，2026-10-11 起）----
        /** 当前检索词（空 = 浏览模式）。 */
        val keyword: String = "",
        /**
         * 浏览模式的列表 = **全量快照**（`WenquRepository.activityIndex`）。
         *
         * 为什么不是「服务端第一页」：本地筛选 / 排序都作用在这份数据上，只拿 20 条的话
         * 「选招募中显示没有、选招募中优先却把旧的已结束排前面」（2026-10-11 用户报）。
         * 搜索时**不清它**——清空检索词即刻还原，不用再打服务端。
         */
        val indexItems: List<WenquActivity> = emptyList(),
        /** 快照抓到过（哪怕只是部分）——false = 一条都没抓到，空态要说「下拉重试」。 */
        val indexAvailable: Boolean = false,
        /** 本地快照正在构建（进页首次 / 首次搜索要抓一份全量，页面给进度提示）。 */
        val indexing: Boolean = false,
        /**
         * 快照是否抓全了。false = 筛选 / 排序 / 搜索可能不完整（触顶 / 中途失败），
         * 页面据此把空态说得诚实一点。
         */
        val indexComplete: Boolean = false,

        // ---- 搜索（同一份本地快照）----
        /**
         * 搜索命中（**未过本地筛选**）。页面靠它区分三种「空」：
         * 正在建索引 / 命中了但被筛选藏了 / 真没有匹配。
         */
        val searchMatches: List<WenquActivity> = emptyList(),

        /** 本地状态筛选：null = 全部。 */
        val statusFilter: Int? = null,
        /** 本地「隐藏已满」：只看还能报名的（[WenquRules.isFull]）。默认关。 */
        val hideFull: Boolean = false,
        /** 本地「只看我报名的」（平台的 `isApply`）。默认关。 */
        val appliedOnly: Boolean = false,
        /** 排序档位（[WenquRules.Sort]）。默认「招募中优先」。 */
        val sort: WenquRules.Sort = WenquRules.Sort.RecruitingFirst,
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
        /** 报名 / 取消报名进行中（弹层里的主按钮转圈 + 防连点）。 */
        val applying: Boolean = false,
        /**
         * 报名 / 取消失败的原因（平台原话），**留在弹层里**——弹层比页面高一层，
         * 页面级 Snackbar 会被它盖住，报在这里用户才看得见。
         */
        val detailActionError: String? = null,

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

        /** 当前列表的**原始集合**：浏览模式 = 全量快照；搜索模式 = 命中子集。 */
        private val source: List<WenquActivity>
            get() = if (keyword.isBlank()) indexItems else searchMatches

        /**
         * 列表的最终形态：**本地筛**（状态 / 隐藏已满 / 只看我报名的）→ **本地排序**（[sort]）。
         * 平台的顺序既不是 id 也不是 add_time，不排的话默认「全部」打开就是满屏已结束。
         */
        val visibleActivities: List<WenquActivity>
            get() = sorted(
                source.filter {
                    WenquRules.matchesFilter(
                        status = it.status,
                        peopleNum = it.peopleNum,
                        balanceNum = it.balanceNum,
                        statusFilter = statusFilter,
                        hideFull = hideFull,
                        appliedOnly = appliedOnly,
                        applied = it.applied,
                    )
                },
            )

        /**
         * 三档排序都在本地（[WenquRules.Sort]）。
         * `sortedBy` 是**稳定排序**——组内保持平台原序（「只重排不隐藏」的既有口径）。
         */
        private fun sorted(list: List<WenquActivity>): List<WenquActivity> = when (sort) {
            WenquRules.Sort.RecruitingFirst ->
                list.sortedBy { WenquRules.displayRank(it.status) }

            WenquRules.Sort.ClosingSoon -> {
                val now = System.currentTimeMillis()
                list.sortedBy { WenquRules.closingSortKey(it.applyEndTime, now) }
            }

            WenquRules.Sort.Newest -> list.sortedByDescending { it.id }
        }

        /** 当前列表的原始条数（含被本地筛选藏掉的）——条数行与空态都用它。 */
        val sourceTotal: Int get() = source.size

        /**
         * 有数据、却被本地筛选（状态 / 隐藏已满 / 只看我报名的）藏起来的条数。
         * 一条都看不见时页面据此说实话——否则用户会以为「没有 / 搜错了」。
         */
        val hiddenByFilter: Int get() = sourceTotal - visibleActivities.size
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** 是否已经为「当前登录态」拉过浏览第一页（避免登出又登入时重复触发）。 */
    private var loaded = false

    /**
     * 在飞的索引构建任务。**故意挂在 [viewModelScope] 上而不是搜索协程里**：
     * 输入防抖后每次关键词变化都会取消上一个搜索协程，若抓取是它的子协程，
     * 用户一边打字一边就把一次全量抓取反复掐掉，索引永远抓不完。
     */
    private var indexJob: Deferred<WenquActivityIndex?>? = null

    /**
     * 搜索的世代号。搜索结果落地时对不上号就丢掉——否则「前一个词的慢查询」回来
     * 会把「后一个词的结果」顶掉（本地索引命中时用不上，兜底走网络那条路上会撞）。
     */
    private var searchSeq = 0

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
            prefs.wenquAppliedOnly.collect { saved ->
                _state.update { it.copy(appliedOnly = saved) }
            }
        }
        viewModelScope.launch {
            prefs.wenquSort.collect { saved ->
                _state.update { it.copy(sort = WenquRules.coerceSort(saved)) }
            }
        }
        viewModelScope.launch {
            repo.loggedIn.collect { loggedIn ->
                _state.update { it.copy(loggedIn = loggedIn) }
                if (loggedIn) {
                    ensureLoaded()
                } else {
                    loaded = false
                    // 退登：把在飞的索引抓取掐掉（仓库那侧缓存也一起清了），
                    // 并把搜索态清干净——别让下一位登录者看到上一位的搜索结果。
                    indexJob?.cancel()
                    indexJob = null
                    searchSeq++
                    _state.update {
                        it.copy(
                            keyword = "",
                            searchMatches = emptyList(),
                            indexComplete = false,
                            indexing = false,
                        )
                    }
                }
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
        val cachedIndex = repo.cachedIndex()
        _state.update {
            it.copy(
                phone = session?.phone ?: it.phone,
                hours = session?.hours ?: it.hours,
                starLevel = session?.starLevel ?: it.starLevel,
                records = cachedMine?.list ?: it.records,
                indexItems = cachedIndex?.let { c -> corrected(c.items) } ?: it.indexItems,
                indexAvailable = cachedIndex != null,
                indexComplete = cachedIndex?.complete == true,
                applyBlockedReason = cachedIndex?.let { index ->
                    WenquRules.applyBlockedText(index.blacklisted, index.stopApplyReason)
                } ?: it.applyBlockedReason,
            )
        }
        if (loaded) return
        loaded = true
        viewModelScope.launch { loadMine(force = false) }
        viewModelScope.launch { loadIndex(force = false) }
    }

    /**
     * 下拉刷新：我的 + 全量快照一起强刷（绕过缓存），指示器转到都落地。
     *
     * 快照就是列表本身，所以「刷新」必须连它一起重抓——不然筛选 / 排序 / 搜索看到的
     * 还是旧的（2026-10-11 用户报「选招募中没有」之后改的：列表的数据源已换成快照）。
     * 抓一次几秒，下拉指示器会一直转到抓完。
     */
    fun refresh() {
        if (!repo.loggedIn.value) return
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true) }
            try {
                loadMine(force = true)
                loadIndex(force = true)
                val keyword = _state.value.keyword
                if (keyword.isNotBlank()) {
                    // 快照刚重抓完，直接重算命中（别再抓一次索引）
                    doSearch(keyword, forceIndex = false, skipIfSame = false)
                }
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

    /**
     * 用**不可变的时间字段**校正状态（[WenquRules.effectiveStatus]）。
     *
     * 增量同步只刷最新那几页，深层条目的 `status` 会冻在抓取那一刻（最长一天）。
     * 冻住的「招募中」会被本地排序顶到最上面、点进详情又对不上——所以在**数据进状态**
     * 这一步统一校正一次（O(n)，每次装载一次，不是每次重组）。
     */
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

    /**
     * 活动列表 = **全量快照**（进页 / 下拉刷新用）。
     *
     * 复用搜索那套 [ensureIndex]（同一份缓存 + 同一个在飞任务），所以进页与搜索
     * 不会各抓一份；快照抓全了之后筛选 / 排序 / 搜索都是纯本地。
     * 失败**不清旧数据**（宁旧勿空）：有旧快照就留着，页面按 `indexAvailable` 说实话。
     */
    private suspend fun loadIndex(force: Boolean) {
        if (!repo.loggedIn.value) return
        val index = ensureIndex(force)
        _state.update {
            it.copy(
                indexItems = index?.let { loaded -> corrected(loaded.items) } ?: it.indexItems,
                indexAvailable = index != null,
                indexComplete = index?.complete == true,
                applyBlockedReason = index?.let { loaded ->
                    WenquRules.applyBlockedText(loaded.blacklisted, loaded.stopApplyReason)
                } ?: it.applyBlockedReason,
            )
        }
    }

    /**
     * 检索词变化（页面输入框 / IME 搜索键的入口，起协程）。
     * 空串 = 回浏览模式；非空 = 走本地索引搜索（[doSearch]）。
     */
    fun search(keyword: String) {
        viewModelScope.launch { doSearch(keyword) }
    }

    /**
     * 搜索。
     *
     * **纯本地**：命中来自 [WenquRepository.activityIndex]（平台上全部活动的快照），
     * 匹配字段由 [WenquRules.matchesKeyword] 定（活动名 / 项目名 / 单位 / 地点）——
     * 不再依赖服务端 `keyword` 到底认哪些字段，这是「列表里看得见却搜不到」的正面修复。
     *
     * 索引**抓全了**就到此为止（零请求）；索引不完整或根本建不起来时，再补一次服务端
     * 关键词查询（只取第一页）按 id 去重并入，兜住超出索引上限的深层活动。
     * 兜底那一跳失败**不打断本地结果**——本地有命中就照常给。
     *
     * [forceIndex] = 连索引一起重抓（只在确实要「重新来一遍」时给）；
     * [skipIfSame] = 关键词没变就直接返回（顶栏刷新要强制重算，传 false）。
     */
    private suspend fun doSearch(
        keyword: String,
        forceIndex: Boolean = false,
        skipIfSame: Boolean = true,
    ) {
        if (!repo.loggedIn.value) return
        val key = keyword.trim()
        if (skipIfSame && key == _state.value.keyword && !forceIndex) return
        if (key.isBlank()) {
            // 回浏览模式：**一个请求都不发**（浏览那串一直留着，清空即刻还原）。
            // 世代号也推进：在飞的搜索回来时不该再往界面上写东西。
            searchSeq++
            _state.update { it.copy(keyword = "", searchMatches = emptyList(), indexing = false) }
            return
        }
        val seq = ++searchSeq
        // 关键词立刻反映到界面（条数行 / 空态读它）。结果**不无条件清**——
        // 索引已在缓存里时是同步算出来的，清了反而闪一下；要等抓取时（没缓存）
        // 列表区本来就会显示进度提示，那时才把旧结果清掉。
        val cached = if (forceIndex) null else repo.cachedIndex()
        _state.update {
            it.copy(keyword = key, searchMatches = if (cached != null) it.searchMatches else emptyList())
        }

        val index = ensureIndex(forceIndex)
        val local = corrected(index?.items.orEmpty()).filter {
            WenquRules.matchesKeyword(key, it.title, it.projectName, it.unit, it.address)
        }
        var failed = false
        val matches = if (index != null && index.complete) {
            local
        } else {
            try {
                mergeActivities(local, corrected(repo.activities(key, force = forceIndex).items))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 索引也没有 + 兜底也失败 = 真的什么都没搜到，得说一声
                if (index == null) failed = true
                local
            }
        }
        _state.update {
            // 期间用户可能又改了关键词：对不上号的结果直接丢
            if (seq != searchSeq) {
                it
            } else {
                it.copy(
                    searchMatches = matches,
                    indexComplete = index?.complete == true,
                    message = if (failed) "搜索失败，请稍后重试" else it.message,
                )
            }
        }
    }

    /**
     * 拿到本地快照：交给仓储按 [WenquRules.indexLoadPlan] 分派（缓存 / 增量 / 整份重抓）。
     *
     * **不要**在这里「有缓存就直接返回」——那样会把「到点该增量同步了」（30 分钟闸门）
     * 整个跳过，快照只能等 24 小时的 TTL 到期才更新（2026-10-11 自查发现）。
     *
     * 抓取任务由 [indexJob] 持有、**不随搜索协程取消**（见字段注释）；
     * 失败（回 null）就把 [indexJob] 清掉，下次进页重试而不是永远吃那个失败结果。
     */
    private suspend fun ensureIndex(force: Boolean): WenquActivityIndex? {
        val job = if (force) {
            indexJob = viewModelScope.async { repo.activityIndex(force = true) }
            indexJob!!
        } else {
            indexJob ?: viewModelScope.async { repo.activityIndex() }.also { indexJob = it }
        }
        // 进度提示只在**手上一条都没有**时给：已有快照时（哪怕后台在整份重抓）列表照常显示，
        // 不该把内容换成一句「正在获取…」。
        _state.update { it.copy(indexing = it.indexItems.isEmpty()) }
        val index = try {
            job.await()
        } finally {
            _state.update { it.copy(indexing = false) }
        }
        if (index == null) indexJob = null
        return index
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

    /** 切换本地「只看我报名的」（不发请求，但**落盘**记住）。 */
    fun setAppliedOnly(appliedOnly: Boolean) {
        _state.update { it.copy(appliedOnly = appliedOnly) }
        viewModelScope.launch { prefs.setWenquAppliedOnly(appliedOnly) }
    }

    /** 切换排序档位（不发请求，但**落盘**记住）。 */
    fun setSort(sort: WenquRules.Sort) {
        _state.update { it.copy(sort = sort) }
        viewModelScope.launch { prefs.setWenquSort(sort.name) }
    }

    /**
     * 一键清掉三个本地筛选（**不动排序**——排序不是「挡掉东西」的维度，
     * 清筛选不该顺手把用户挑的排序也重置）。空态里的「清除筛选」按钮走这里。
     */
    fun clearFilters() {
        setStatusFilter(null)
        setHideFull(false)
        setAppliedOnly(false)
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
                // 换了一条活动：上一条的报名 / 取消报错不能跟着飘过来
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
        _state.update {
            it.copy(
                detail = null,
                detailLoading = false,
                detailUnavailable = false,
                detailActionError = null,
            )
        }
    }

    /**
     * **报名**（DESIGN §4.45，2026-10-11 起本应用可写）。
     *
     * 写操作纪律：**只发一次**（仓库的 [WenquRepository.applyActivity] 不自动重登重发）；
     * 失败把平台原话留在弹层里（[UiState.detailActionError]），成功则关弹层 + 页面级提示，
     * 并把仓库里就地更新过的快照重新铺回列表（那条立刻变「已报名」）。
     */
    fun applyActivity(activityId: Long) {
        if (activityId <= 0L || _state.value.applying) return
        // **同步扣闸**：`update` 是同步的、VM 方法又在主线程被调，所以连点两下时第二下进不来
        // （若把扣闸放进协程里，两下可能在协程起跑前都通过判断 → 重复提交）
        _state.update { it.copy(applying = true, detailActionError = null) }
        viewModelScope.launch {
            try {
                repo.applyActivity(activityId)
                syncIndexFromRepo()
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

    /**
     * **取消报名**。纪律同 [applyActivity]。
     * [applyId] 是仓库换 id 兜底用的（活动列表 / 详情通常能拿到；拿不到传 0）。
     */
    fun cancelApply(activityId: Long, applyId: Long = 0L) {
        if (activityId <= 0L || _state.value.applying) return
        _state.update { it.copy(applying = true, detailActionError = null) }
        viewModelScope.launch {
            try {
                repo.cancelApply(activityId, applyId)
                syncIndexFromRepo()
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

    /** 写操作成功后，把仓库里就地更新过的快照重新铺到界面（列表那条的「已报名」与名额）。 */
    private fun syncIndexFromRepo() {
        val cached = repo.cachedIndex() ?: return
        _state.update {
            it.copy(indexItems = corrected(cached.items), indexComplete = cached.complete)
        }
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
