package edu.jxslu.schedule.data.wenqu

import edu.jxslu.schedule.data.session.AutoSyncRules
import edu.jxslu.schedule.domain.WenquRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * `volunteer/v2` 的**一页响应**（仓库层模型）。
 *
 * 2026-10-11 起页面不再按页取（列表 = 全量快照 [WenquActivityIndex]），这个模型只剩一个
 * 用处：**搜索兜底**——快照不完整时补一次服务端关键词查询，只取它的 [items]。
 * 所以 `page` / `hasMore` 两个分页字段已经删掉（没人读了，留着是死代码）。
 *
 * [blacklisted] / [stopApplyReason] 是**账号级**的报名限制（平台的 `isBlack` /
 * `stop_apply_reason`，每页都一样），不是某条活动的属性——页面据此在列表上方挂一条
 * 「你被限制报名」的说明，把平台原话带上来，别让它静静丢掉。
 */
data class WenquActivityPage(
    val items: List<WenquActivity>,
    val blacklisted: Boolean = false,
    val stopApplyReason: String = "",
)

/**
 * **本地全量快照**（仓库层模型）：平台上**全部**活动的一份索引。
 *
 * 2026-10-11 起它同时是**活动列表的数据源**——不只是搜索。原因：列表原先只拿服务端
 * 第一页（20 条），而本地筛选 / 排序都作用在这 20 条上，于是「平台里明明有招募中的活动，
 * 选『招募中』却显示没有、选『招募中优先』却把一堆旧的已结束排在前面」——
 * 招募中只有个位数且不在第一页，筛选与排序**必须**覆盖全量才有意义。
 *
 * 它也是搜索的数据源：搜索原先完全依赖服务端 `keyword`，而那个参数到底匹配哪些字段
 * 无法验证（无 token 回 `410000 请登录`），于是「列表里看得见的活动搜不到」。
 *
 * [complete] 表示这份快照**抓全了**：
 * - `true`：抓到服务端翻到头（某页没带来任何新 id），筛选 / 排序 / 搜索都是纯本地、零请求；
 * - `false`：触到条数 / 页数 / 时间上限，或中途某页失败——搜索再补一次服务端关键词查询
 *   （只取第一页）兜住深层活动，结果按 id 去重后并入。
 *
 * [blacklisted] / [stopApplyReason] 是**账号级**的报名限制（平台的 `isBlack` /
 * `stop_apply_reason`，每页都一样）——页面据此在列表上方挂「报名受限」说明。
 * 它们跟着快照一起带出来：列表不再有「浏览页」那个载体了。
 *
 * [fullCrawledAt] / [syncedAt] 是增量同步的两个时刻（2026-10-11）：
 * - [fullCrawledAt] = 上次**整份重抓**的时刻，超过 [WenquRules.INDEX_MAX_AGE_MS] 就再整份抓一次；
 * - [syncedAt] = 上次**同步**（整份或增量）的时刻，不足
 *   [WenquRules.INDEX_SYNC_INTERVAL_MS] 就一个请求都不发。
 *
 * 分派逻辑是纯函数 [WenquRules.indexLoadPlan]（JVM 可测）。
 */
data class WenquActivityIndex(
    val items: List<WenquActivity>,
    val complete: Boolean,
    val blacklisted: Boolean = false,
    val stopApplyReason: String = "",
    val fullCrawledAt: Long = 0L,
    val syncedAt: Long = 0L,
)

/**
 * **增量同步的合并**（2026-10-11）：把刚抓到的第一页并进已有快照。
 *
 * 平台列表**开头按 id 倒序**，新活动一定在最前面，所以：
 * - [fresh] 整页放在最前（它本来就在最前，顺序基本不变）；
 * - [old] 里**不在** [fresh] 的条目按原序接在后面（同 id 的以 [fresh] 那份为准——名额 /
 *   状态 / 已报名这些可变字段要新值）；
 * - 合并后超过 [cap] 就砍尾巴（尾巴是最旧的；上限只为兜住内存，正常远到不了）。
 *
 * 纯函数，JVM 可测。
 */
internal fun mergeNewest(
    old: List<WenquActivity>,
    fresh: List<WenquActivity>,
    cap: Int,
): List<WenquActivity> {
    if (fresh.isEmpty()) return old
    val freshIds = HashSet<Long>()
    for (item in fresh) if (item.id > 0L) freshIds.add(item.id)
    val merged = ArrayList<WenquActivity>(fresh.size + old.size)
    merged += fresh
    for (item in old) if (item.id <= 0L || item.id !in freshIds) merged += item
    return if (merged.size <= cap) merged else merged.take(cap)
}

/**
 * 本地命中 ∪ 服务端兜底命中（部分索引 / 索引不可用时用）：按 id 去重，[primary] 优先。
 *
 * `id ≤ 0` 的条目（平台没给 id）不去重——它们没有身份，硬按 0 合并会把好几条挤成一条。
 */
internal fun mergeActivities(
    primary: List<WenquActivity>,
    secondary: List<WenquActivity>,
): List<WenquActivity> {
    if (secondary.isEmpty()) return primary
    val seen = HashSet<Long>()
    for (item in primary) if (item.id > 0L) seen.add(item.id)
    return primary + secondary.filter { it.id <= 0L || seen.add(it.id) }
}

/**
 * 问渠实践教育平台仓库（DESIGN §4.45）：登录态、凭证、志愿 / 劳动教育取数。
 *
 * 登录链**只有手机号 + 密码**：
 * - 首次（或忘记密码）：`register/verify` 发码 → `register/reset` 设密码 → 直接登录；
 * - 日常：`login` 换 3 天 JWT，快到期自动重登（用户无感）。
 *
 * 取数**全部过进程内 TTL 缓存**（[WenquTtlCache]，TTL 见 [WenquRules.ACTIVITY_TTL_MS]）：
 * 问渠页每次进页都会新建 ViewModel，没有这层就是「进页 = 三次请求」。
 * `force = true` 是用户显式刷新（顶栏刷新键），一律绕过缓存。
 *
 * 纪律：
 * - **只有 `status:410000`（请登录）算会话失效**；网络 / 5xx / 业务拒绝一律不标失效
 *   （对齐一卡通：把对的密码记成错的会把用户往改密码方向带）；
 * - 写操作只有「用户显式点发码 / 设密码」两处，各自只发一次、失败不自动重试；
 * - 取数失败**不落任何影子**（宁旧勿空：账号卡那一行保留上次工时）。
 */
class WenquRepository(
    private val store: WenquCredentialStore,
    private val api: WenquApi = WenquApi.create(),
    /** 可注入的时钟（测试用假时钟，生产走系统钟）。 */
    private val now: () -> Long = System::currentTimeMillis,
) {

    /** 登录态流（对齐胖乖 / 趣智 / U净）：页面订阅它，登录 / 退出在任何窗口都即时生效。 */
    private val _loggedIn = MutableStateFlow(store.read()?.isComplete == true)
    val loggedIn: StateFlow<Boolean> = _loggedIn.asStateFlow()

    /**
     * 当前会话（含工时影子）。「我的」页账号卡那一行订阅它——
     * 工时是后台刷新的，没有这条流卡片就停在进页那一刻的旧值。
     */
    private val _session = MutableStateFlow(store.read())
    val session: StateFlow<WenquSession?> = _session.asStateFlow()

    /** 登录串行化：并发登录会让两次响应互相覆盖凭证。 */
    private val loginMutex = Mutex()

    // ---- 进程内缓存（DESIGN §4.45「进页不重复取数」）------------------------------
    // 「我的记录 / 星级」只有一个资源，键用常量；活动列表按关键词缓存（换关键词 = 换一份）。

    private val volunteerCache =
        WenquTtlCache<String, WenquVolunteerData>(WenquRules.HOURS_TTL_MS, maxEntries = 1)
    private val certCache =
        WenquTtlCache<String, WenquStarCertData>(WenquRules.HOURS_TTL_MS, maxEntries = 1)
    private val activityCache =
        WenquTtlCache<String, WenquActivityPage>(WenquRules.ACTIVITY_TTL_MS)
    private val detailCache =
        WenquTtlCache<Long, WenquActivity>(WenquRules.ACTIVITY_TTL_MS)

    /**
     * 全量快照（整份活动列表，只有一个资源，键用常量）。
     *
     * TTL 是 [WenquRules.INDEX_MAX_AGE_MS]（24 小时）——**不是 30 分钟**：
     * 快照要活得比一次同步久，否则每次进页都退化成整份重抓。30 分钟那档闸门改由
     * [WenquActivityIndex.syncedAt] 判（到点只抓第一页做增量，见 [syncNewest]）。
     */
    private val indexCache =
        WenquTtlCache<String, WenquActivityIndex>(WenquRules.INDEX_MAX_AGE_MS, maxEntries = 1)

    /** 索引构建串行化：两个入口同时要索引时，只允许一次抓取，另一个等结果。 */
    private val indexMutex = Mutex()

    fun localSession(): WenquSession? = store.read()

    /** 同上：我的记录 / 工时。 */
    fun cachedVolunteer(): WenquVolunteerData? = volunteerCache.get(SINGLE_KEY, now())

    /**
     * 只读快照（**不发请求**）：页面进来先看有没有现成的，有就先摆上（本地筛 / 排 / 搜），
     * 再按 [WenquRules.indexLoadPlan] 决定要不要增量同步。过期或没抓过回 null。
     */
    fun cachedIndex(): WenquActivityIndex? = indexCache.get(INDEX_KEY, now())

    /** 作废快照（退登等）：下次进页整份重抓。 */
    fun invalidateIndex() {
        indexCache.clear()
    }

    /**
     * 让**下次进页立刻同步一次**（不整份重抓）。
     *
     * 调用方是「每周日抢活动」提醒：它响的时候（12:00 前几分钟）用户多半正要进页看新活动，
     * 若还吃着 30 分钟的同步闸门，进页会**零请求**地显示发布前的列表。
     * 只把同步时刻清零、**不发请求**——进页照旧是用户动作，网络请求不提前。
     */
    fun invalidateActivities() {
        val cached = indexCache.get(INDEX_KEY, now()) ?: return
        indexCache.put(INDEX_KEY, cached.copy(syncedAt = 0L), now())
    }

    fun logout() {
        store.clear()
        clearCaches()
        _loggedIn.value = false
        _session.value = null
    }

    private fun clearCaches() {
        volunteerCache.clear()
        certCache.clear()
        activityCache.clear()
        detailCache.clear()
        indexCache.clear()
    }

    private fun persist(session: WenquSession) {
        store.save(session)
        _session.value = session
    }

    /**
     * 发「重置密码」验证码。返回 true = 短信已发出。
     * 失败抛 [WenquApiException]，文案已把平台的 `暂无使用次数` 翻成人话。
     */
    suspend fun sendResetCode(phone: String): Boolean = withContext(Dispatchers.IO) {
        val envelope = api.sendResetCode(phone)
        if (envelope.sendResult()) return@withContext true
        throw WenquApiException(friendlySendError(envelope.msg))
    }

    /** 首次设置 / 忘记密码：验证码 + 新密码 → 落盘并进入登录态。 */
    suspend fun setupPassword(phone: String, captcha: String, password: String): WenquSession =
        loginMutex.withLock {
            withContext(Dispatchers.IO) {
                api.resetPassword(phone, captcha, password).requireSuccess()
                persistLogin(phone, password)
            }
        }

    /** 手机号 + 密码登录。 */
    suspend fun login(phone: String, password: String): WenquSession = loginMutex.withLock {
        withContext(Dispatchers.IO) { persistLogin(phone, password) }
    }

    private suspend fun persistLogin(phone: String, password: String): WenquSession {
        // 换人登录：上一位的缓存一条都不能留（否则新用户会看到别人的记录）
        val previousPhone = store.read()?.phone
        val envelope = api.login(phone, password)
        envelope.requireSuccess()
        val data = envelope.data.decodeData<WenquLoginData>()
            ?: throw WenquApiException("登录响应异常，请稍后再试")
        if (data.token.isBlank()) throw WenquApiException("登录响应缺少凭证，请稍后再试")
        if (previousPhone != null && previousPhone != phone) clearCaches()
        val session = WenquSession(
            phone = phone,
            password = password,
            token = data.token,
            expiresAt = WenquRules.jwtExpiryMillis(data.token),
        )
        persist(session)
        _loggedIn.value = true
        return session
    }

    /**
     * 我的志愿 / 劳动教育：总工时 + 记录。成功才把工时影子写回凭证
     * （失败保留上次的值，账号卡那一行不会突然空掉）。
     */
    suspend fun volunteer(force: Boolean = false): WenquVolunteerData = withContext(Dispatchers.IO) {
        if (!force) volunteerCache.get(SINGLE_KEY, now())?.let { return@withContext it }
        val payload = authed { token -> api.volunteerMine(token) }
        val parsed = payload.decodeData<WenquVolunteerData>() ?: WenquVolunteerData()
        volunteerCache.put(SINGLE_KEY, parsed, now())
        store.read()?.let { session ->
            persist(
                session.copy(
                    hours = WenquRules.hoursOf(parsed.totalScore),
                    hoursFetchedAt = now(),
                ),
            )
        }
        parsed
    }

    /** 星级证书（总工时 + 星级）。 */
    suspend fun starCert(force: Boolean = false): WenquStarCertData = withContext(Dispatchers.IO) {
        if (!force) certCache.get(SINGLE_KEY, now())?.let { return@withContext it }
        val payload = authed { token -> api.starCert(token) }
        val parsed = payload.decodeData<WenquStarCertData>() ?: WenquStarCertData()
        certCache.put(SINGLE_KEY, parsed, now())
        store.read()?.let { session ->
            persist(
                session.copy(
                    starLevel = parsed.starLevel,
                    hours = WenquRules.hoursOf(parsed.scoreTotal).takeIf { it > 0.0 } ?: session.hours,
                ),
            )
        }
        parsed
    }

    /**
     * 取**一页**活动（只给搜索兜底用：快照不完整时补一次服务端关键词查询）。
     * 命中缓存（且未过期）直接返回，不发请求。
     *
     * 页面本身不用它——列表与搜索的数据源都是 [activityIndex]（2026-10-11 起）。
     */
    suspend fun activities(keyword: String, force: Boolean = false): WenquActivityPage =
        withContext(Dispatchers.IO) {
            val key = keyword.trim()
            if (!force) activityCache.get(key, now())?.let { return@withContext it }
            val page = fetchPage(key, page = 1)
            activityCache.put(key, page, now())
            page
        }

    /**
     * 活动快照（[WenquActivityIndex]）：列表与搜索共用的数据源。
     *
     * **三条路，由纯函数 [WenquRules.indexLoadPlan] 分派**（JVM 可测）：
     * - [WenquRules.IndexLoad.UseCache]：距上次同步不足
     *   [WenquRules.INDEX_SYNC_INTERVAL_MS] → 直接用缓存，**零请求**；
     * - [WenquRules.IndexLoad.SyncNewest]：**增量**——只抓第一页（[syncNewest]）按 id 合并。
     *   平台列表开头按 id 倒序，新活动一定在最前面，所以增量够用（1 个请求、几百毫秒）；
     * - [WenquRules.IndexLoad.Rebuild]：没有快照 / 快照不完整 / 上次整份重抓超过
     *   [WenquRules.INDEX_MAX_AGE_MS] → [buildIndex] 整份重抓（几秒，页面给进度提示）。
     *
     * [force] = 用户显式动作（下拉刷新）：跳过 30 分钟闸门立刻增量同步一次，
     * **但不会因为「想看看新的」就整份重抓**——那是几秒的事，不该由一次下拉触发。
     *
     * 并发的两次调用由 [indexMutex] 串行化——第二个等第一个的结果，不会各抓一份。
     */
    suspend fun activityIndex(force: Boolean = false): WenquActivityIndex? =
        withContext(Dispatchers.IO) {
            val cached = indexCache.get(INDEX_KEY, now())
            when (
                WenquRules.indexLoadPlan(
                    hasCache = cached != null,
                    cacheComplete = cached?.complete == true,
                    fullCrawledAt = cached?.fullCrawledAt ?: 0L,
                    syncedAt = cached?.syncedAt ?: 0L,
                    now = now(),
                    force = force,
                )
            ) {
                WenquRules.IndexLoad.UseCache -> cached
                WenquRules.IndexLoad.SyncNewest -> indexMutex.withLock {
                    // 双检：等锁期间另一个调用可能已经同步过了
                    val latest = indexCache.get(INDEX_KEY, now()) ?: return@withLock buildIndex()
                    if (!force && now() - latest.syncedAt < WenquRules.INDEX_SYNC_INTERVAL_MS) {
                        latest
                    } else {
                        syncNewest(latest)
                    }
                }

                WenquRules.IndexLoad.Rebuild -> indexMutex.withLock {
                    // 双检：等锁期间另一个调用可能已经抓好了
                    val latest = indexCache.get(INDEX_KEY, now())
                    if (latest != null && !force) {
                        latest
                    } else {
                        buildIndex() ?: latest
                    }
                }
            }
        }

    /**
     * 增量同步：只抓第一页，按 id 合并进快照（[mergeNewest]）。
     *
     * 抓失败**也算同步过了**（推进 [WenquActivityIndex.syncedAt]）：不这样的话网络一抖，
     * 每次进页都要重试一遍。用户想立刻再试就下拉刷新（[force] 跳过闸门）。
     */
    private suspend fun syncNewest(current: WenquActivityIndex): WenquActivityIndex {
        val syncedAt = now()
        val page = try {
            fetchPage(keyword = "", page = 1, limit = WenquRules.INDEX_SYNC_PAGE_SIZE)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            val stamped = current.copy(syncedAt = syncedAt)
            indexCache.put(INDEX_KEY, stamped, syncedAt)
            return stamped
        }
        val updated = current.copy(
            items = mergeNewest(current.items, page.items, WenquRules.ACTIVITY_INDEX_MAX_ITEMS),
            blacklisted = current.blacklisted || page.blacklisted,
            stopApplyReason = page.stopApplyReason.ifBlank { current.stopApplyReason },
            syncedAt = syncedAt,
        )
        indexCache.put(INDEX_KEY, updated, syncedAt)
        return updated
    }

    /**
     * **整份重抓**（[WenquRules.IndexLoad.Rebuild] 那条路）：按页翻到服务端翻不动为止。
     *
     * 抓取**一次一页**（[WenquRules.ACTIVITY_INDEX_PAGE_SIZE] 条），页间按
     * `AutoSyncRules.interRequestDelayMs()` 放慢，别把第三方系统打疼。
     *
     * 收工判据全部交给 [WenquRules.shouldContinueIndexing]（纯函数，JVM 可测）：
     * 这一页没带来新 id / 累计触顶 / 页数触顶 / 超时间预算，任一命中就停。
     * **只有「没带来新 id」算抓全**（`complete = true`）；其余都是部分快照。
     *
     * 失败处理：首页就失败 → 回 null（调用方保留旧快照 / 页面给「没抓到」的空态）；
     * 中途失败 → 带着已抓到的部分收工并**缓存**（`complete = false`），
     * 否则网络一抖就要每次进页重爬一遍。
     */
    private suspend fun buildIndex(): WenquActivityIndex? {
        val startedAt = now()
        val items = ArrayList<WenquActivity>()
        val seenIds = HashSet<Long>()
        var page = 1
        var complete = false
        // 账号级的报名限制：每页都一样，取到非空的那份就行（列表要挂「报名受限」说明）
        var blacklisted = false
        var stopApplyReason = ""
        while (true) {
            val current = try {
                fetchPage(keyword = "", page = page, limit = WenquRules.ACTIVITY_INDEX_PAGE_SIZE)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 首页失败 = 索引不可用；中途失败 = 带着已有部分收工
                if (items.isEmpty()) return null
                break
            }
            if (current.blacklisted) blacklisted = true
            if (current.stopApplyReason.isNotBlank()) stopApplyReason = current.stopApplyReason
            var added = 0
            for (item in current.items) {
                // 按 id 去重；id 认不出（0）的照收，别让它们互相吞掉
                if (item.id > 0L && !seenIds.add(item.id)) continue
                items.add(item)
                added++
            }
            page++
            if (added == 0) {
                // 服务端翻到头（或忽略 page 参数）：索引到此为止且**是完整的**
                complete = true
                break
            }
            if (!WenquRules.shouldContinueIndexing(
                    addedNew = added,
                    itemsSoFar = items.size,
                    pagesFetched = page - 1,
                    elapsedMs = now() - startedAt,
                )
            ) {
                break
            }
            delay(AutoSyncRules.interRequestDelayMs())
        }
        val index = WenquActivityIndex(
            items = items,
            complete = complete,
            blacklisted = blacklisted,
            stopApplyReason = stopApplyReason,
            // 整份重抓：两个时刻一起推（它同时是一次同步）
            fullCrawledAt = now(),
            syncedAt = now(),
        )
        indexCache.put(INDEX_KEY, index, now())
        return index
    }

    /**
     * 单个活动详情。列表项先给页面顶着用，详情回来再补齐（工时 / 群号只有详情有）。
     *
     * **回 null = 平台说「这条没有详情」**（活动已删 / 不可见），不是错误——见下面的注释。
     * 没有 `force`：详情只在用户点开某条时取，点一次算一次，不需要「强制绕过缓存」的入口。
     */
    suspend fun activityDetail(id: Long): WenquActivity? =
        withContext(Dispatchers.IO) {
            detailCache.get(id, now())?.let { return@withContext it }
            val payload = authed { token -> api.volunteerDetail(token, id) }
            // `detail: null` 是平台的**正常回答**（活动已删 / 不可见），不是错误：
            // 调用方用列表或记录里已有的字段继续展示，别弹「取不到详情」吓用户
            // （2026-10-08 用户报「点记录弹详情，同时飘一条取不到详情」）。
            val parsed = payload.decodeData<WenquActivityDetailData>()?.detail
                ?: return@withContext null
            detailCache.put(id, parsed, now())
            parsed
        }

    /**
     * **扫一遍**第一页 `limit` 条（新增活动提醒用，DESIGN §4.45）。
     *
     * 与 [activities] 分开是因为语义不同：那是「取一页」（进缓存），
     * 这是「看有没有新活动」（一次取够）。接口**不保证按 id 排序**
     * （实测前 21 条严格降序、之后跳回旧 id），扫描范围太小会漏掉夹在中间的新活动。
     *
     * 它**不写任何缓存**（2026-10-11）：列表与搜索的数据源都是 [activityIndex]，
     * 原先那句「顺手把浏览页缓存刷成刚抓到的前 20 条」已经没有读的人了。
     * 提醒响过之后要拿新数据，走 [invalidateActivities] + 下一次进页重抓快照。
     */
    suspend fun scanActivities(limit: Int): List<WenquActivity> = withContext(Dispatchers.IO) {
        val payload = authed { token ->
            api.volunteerList(token = token, page = 1, limit = limit, keyword = "")
        }
        val data = payload.decodeData<WenquActivityData>()
        data?.list.orEmpty()
    }

    private suspend fun fetchPage(
        keyword: String,
        page: Int,
        limit: Int = WenquRules.ACTIVITY_PAGE_SIZE,
    ): WenquActivityPage {
        val payload = authed { token -> api.volunteerList(token, page, limit, keyword) }
        val data = payload.decodeData<WenquActivityData>()
        return WenquActivityPage(
            items = data?.list.orEmpty(),
            blacklisted = data?.blacklisted == true,
            stopApplyReason = data?.stopApplyReason.orEmpty(),
        )
    }

    /**
     * 「我的」页专用：工时影子过期才刷一次（TTL 见 [WenquRules.hoursStale]）。
     * 失败**静默**——账号卡那一行保留上次的值，不该因为一次网络抖动变成错误态。
     */
    suspend fun refreshHoursIfStale() {
        val session = store.read()?.takeIf { it.isComplete } ?: return
        if (!WenquRules.hoursStale(session.hoursFetchedAt, now())) return
        try {
            // 闸门已经判过期，这里显式 force：别让缓存把「该刷了」又挡回去
            volunteer(force = true)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 静默：卡片保留旧值
        }
    }

    /**
     * 带会话自愈的调用：token 快到期先重登；真撞上 `请登录` 再重登一次并重试一次。
     * **只重试一次**——反复登会撞平台风控，且密码错时重试也是白搭。
     */
    private suspend fun authed(call: suspend (String) -> WenquEnvelope): JsonElement {
        val session = ensureToken()
        val first = call(session.token)
        if (first.status != WenquEnvelope.STATUS_NEED_LOGIN) {
            first.requireSuccess()
            return first.data ?: JsonNull
        }
        val renewed = login(session.phone, session.password)
        val second = call(renewed.token)
        second.requireSuccess()
        return second.data ?: JsonNull
    }

    /** 有凭证就返回（快到期先换新的），没有直接抛 [WenquSessionExpiredException]。 */
    private suspend fun ensureToken(): WenquSession {
        val session = store.read()?.takeIf { it.isComplete }
            ?: throw WenquSessionExpiredException("未登录")
        if (!WenquRules.needsRefresh(session.expiresAt, now())) return session
        return login(session.phone, session.password)
    }

    /** 平台发码失败是 `status:200` + 这句 msg，翻成人话再抛。 */
    private fun friendlySendError(msg: String?): String {
        val raw = msg?.takeIf { it.isNotBlank() } ?: "短信发送失败，请稍后再试"
        return if (raw.contains("暂无使用次数")) "短信发送过于频繁，请过几分钟再试" else raw
    }

    private companion object {
        /** 单资源缓存的键（我的记录 / 星级各只有一份）。 */
        const val SINGLE_KEY = "self"

        /** 搜索索引只有一个资源，键用常量。 */
        const val INDEX_KEY = "all"
    }
}
