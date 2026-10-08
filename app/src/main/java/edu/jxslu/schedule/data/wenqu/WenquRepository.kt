package edu.jxslu.schedule.data.wenqu

import edu.jxslu.schedule.domain.WenquRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * 活动列表的一页（**仓库层模型，不是接口模型**）。
 *
 * [items] 是**累计**结果（翻到第 3 页就是 3 页拼起来的），页面直接整份渲染；
 * [hasMore] 由「上一页是否回满」推断——`volunteer/v2` **不返回总数**，
 * 只能靠「回满即可能还有」这条经验判据（宁可多给一次「加载更多」按钮，也不要漏数据）。
 *
 * [blacklisted] / [stopApplyReason] 是**账号级**的报名限制（平台的 `isBlack` /
 * `stop_apply_reason`，每个活动都一样），不是某条活动的属性——页面据此在列表上方挂一条
 * 「你被限制报名」的说明，把平台原话带上来，别让它静静丢掉。
 */
data class WenquActivityPage(
    val items: List<WenquActivity>,
    /** 已经取到第几页（下一页 = [page] + 1）。 */
    val page: Int,
    val hasMore: Boolean,
    val blacklisted: Boolean = false,
    val stopApplyReason: String = "",
)

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

    fun localSession(): WenquSession? = store.read()

    /**
     * 只读缓存快照（**不发请求**）：页面进页先把它摆上，避免「明明有缓存还闪一下骨架」。
     * 过期或没取过回 null，调用方按空处理。
     */
    fun cachedActivities(keyword: String): WenquActivityPage? =
        activityCache.get(keyword.trim(), now())

    /** 同上：我的记录 / 工时。 */
    fun cachedVolunteer(): WenquVolunteerData? = volunteerCache.get(SINGLE_KEY, now())

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
     * 活动列表**第一页**。命中缓存（且未过期）直接返回，不发请求——
     * 这是「每次进页都重新获取」的正面修复点。
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
     * 追加下一页。缓存已被清掉（比如刚退登）或本来就取完了，就退回 [activities]，
     * 不让「加载更多」变成一次没有前文的裸请求。
     */
    suspend fun loadMoreActivities(keyword: String): WenquActivityPage = withContext(Dispatchers.IO) {
        val key = keyword.trim()
        val current = activityCache.get(key, now()) ?: return@withContext activities(key)
        if (!current.hasMore) return@withContext current
        val next = fetchPage(key, page = current.page + 1)
        // 以**新那一页**为基底（账号级的报名限制标记取最新），只把累计的 items 拼上去
        val merged = next.copy(items = current.items + next.items)
        activityCache.put(key, merged, now())
        merged
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
     * 与 [activities] 分开是因为语义不同：那是「浏览一页」（20 条、进缓存），
     * 这是「看有没有新活动」（一次取够）。接口**不保证按 id 排序**
     * （实测前 21 条严格降序、之后跳回旧 id），扫描范围太小会漏掉夹在中间的新活动。
     *
     * **扫完顺手把「浏览页」的缓存刷成刚抓到的这一页**（2026-10-08）：提醒刚说「有新活动」，
     * 用户点通知进来却看到 30 分钟前的旧列表——这个坑就是这么来的。只放前
     * [WenquRules.ACTIVITY_PAGE_SIZE] 条且 `page = 1`：与 [activities] 的分页口径一致，
     * 「加载更多」接着取 page 2 不会与这次多抓的 80 条重叠。
     */
    suspend fun scanActivities(limit: Int): List<WenquActivity> = withContext(Dispatchers.IO) {
        val payload = authed { token ->
            api.volunteerList(token = token, page = 1, limit = limit, keyword = "")
        }
        val data = payload.decodeData<WenquActivityData>()
        val items = data?.list.orEmpty()
        if (items.isNotEmpty()) {
            activityCache.put(
                "",
                WenquActivityPage(
                    items = items.take(WenquRules.ACTIVITY_PAGE_SIZE),
                    page = 1,
                    hasMore = items.size >= WenquRules.ACTIVITY_PAGE_SIZE,
                    blacklisted = data?.blacklisted == true,
                    stopApplyReason = data?.stopApplyReason.orEmpty(),
                ),
                now(),
            )
        }
        items
    }

    private suspend fun fetchPage(keyword: String, page: Int): WenquActivityPage {
        val payload = authed { token ->
            api.volunteerList(token, page, WenquRules.ACTIVITY_PAGE_SIZE, keyword)
        }
        val data = payload.decodeData<WenquActivityData>()
        val items = data?.list.orEmpty()
        return WenquActivityPage(
            items = items,
            page = page,
            // 接口不给总数，只能按「回满即可能还有」判（多给一次按钮好过漏数据）
            hasMore = items.size >= WenquRules.ACTIVITY_PAGE_SIZE,
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
    }
}
