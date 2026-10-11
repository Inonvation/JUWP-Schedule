package edu.jxslu.schedule.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Base64
import java.util.Locale

/**
 * 问渠实践教育平台的纯逻辑（DESIGN §4.45）：续期时机、验证码冷却、输入校验、展示口径。
 * 全部无 Android 依赖，JVM 可测。
 *
 * 三条实测口径是这些常数的来源：
 * - token 是 **3 天**的 JWT（`exp - iat = 259200s`），提前 [REFRESH_MARGIN_MS] 续，避免用着用着掉线；
 * - 短信**约 5 分钟一条**（实测 19:29:44 失败 → 19:34:46 成功），所以重发按钮要有倒计时；
 * - 手机号校验与平台一致（`^1[3-9]\d{9}$`，平台对非法号码回 `手机号格式错误`）。
 */
object WenquRules {

    /** token 剩余寿命低于这个值就提前重登（12 小时）。 */
    const val REFRESH_MARGIN_MS: Long = 12 * 60 * 60 * 1000L

    /** 验证码重发闸门（5 分钟，对齐平台实测的限流窗口）。 */
    const val RESEND_COOLDOWN_MS: Long = 5 * 60 * 1000L

    /** 工时影子多久算过期（「我的」页进页才决定要不要后台刷一次）。 */
    const val HOURS_TTL_MS: Long = 30 * 60 * 1000L

    /**
     * 活动列表 / 活动详情的进程内缓存寿命（30 分钟）。
     * 与 [HOURS_TTL_MS] 同档：一次进页最多一轮请求，来回切页不重复打第三方系统。
     */
    const val ACTIVITY_TTL_MS: Long = 30 * 60 * 1000L

    /** 活动列表每页条数（对齐小程序 `volunteer_list` 的 `limit:20`）。 */
    const val ACTIVITY_PAGE_SIZE: Int = 20

    /**
     * 本地搜索索引的每页条数。
     *
     * 比浏览页大一个数量级——索引要一次抓完全量（平台上千条），照浏览那样 20 条一页
     * 就是几十个请求。**200 是实测过的**（服务端对 `limit` 不设限，200 照收）；
     * 万一服务端哪天开始压小 `limit`，索引会退化成「部分索引」而不是出错
     * （见 [shouldContinueIndexing] 与 `WenquRepository.activityIndex`）。
     */
    const val ACTIVITY_INDEX_PAGE_SIZE: Int = 200

    /** 索引最多留多少条（触顶即判「部分索引」，搜索另走服务端兜底）。 */
    const val ACTIVITY_INDEX_MAX_ITEMS: Int = 2000

    /** 索引最多抓多少页（与条数上限双保险，防服务端分页异常时无限翻）。 */
    const val ACTIVITY_INDEX_MAX_PAGES: Int = 15

    /**
     * 索引抓取的总时间预算（毫秒）。超了就带着已抓到的部分收工——
     * 搜索是前台动作，不能为了一份「完整」的索引让用户干等。
     */
    const val ACTIVITY_INDEX_BUDGET_MS: Long = 8_000L

    /**
     * 增量同步抓多少条（= 平台的第一页）。
     *
     * 平台列表**开头按 id 倒序**（实测前 21 条严格降序、之后跳回旧 id），所以
     * **新发布的活动一定落在这几页里**——增量只抓它、按 id 合并进已有快照就够了。
     */
    const val INDEX_SYNC_PAGE_SIZE: Int = 20

    /**
     * 增量同步的间隔（毫秒）：距上次同步不足这个就不打网络（进页零请求）。
     * 与旧的 `ACTIVITY_TTL_MS` 同档（30 分钟）——口径不变，只是从「整份重抓」变成「抓第 1 页」。
     */
    const val INDEX_SYNC_INTERVAL_MS: Long = 30 * 60 * 1000L

    /**
     * 快照最长存活（毫秒）：距上次**整份重抓**超过它就再整份抓一次。
     *
     * 为什么需要它：增量只刷最新那几页，深层条目的可变字段（名额 / 状态）会冻在抓取那一刻。
     * 一天一次整份重抓把漂移封顶，同时把平台侧删掉 / 改过的条目清出去。
     */
    const val INDEX_MAX_AGE_MS: Long = 24 * 60 * 60 * 1000L

    /**
     * 搜索输入的防抖窗口（毫秒）。有本地索引时搜索是纯内存过滤，
     * 所以「即输即搜」不会打服务端；这个窗口只是别让每次按键都重排整个列表。
     */
    const val SEARCH_DEBOUNCE_MS: Long = 300L

    /**
     * 「状态筛选 = 全部」在 DataStore 里的存储值。
     * DataStore 没有可空 Int，用这个哨兵替 `statusFilter == null`；平台的状态只有 0–3，
     * 不会撞。
     */
    const val FILTER_ALL_STORED: Int = -1

    /**
     * 新增志愿活动提醒的默认检查间隔（小时）。
     * 一天一次就够——问渠是第三方系统，打太勤只是白撞人家的风控。
     */
    const val ACTIVITY_ALERT_INTERVAL_DEFAULT: Int = 24

    /**
     * 提醒间隔档位（小时）。**与成绩 / 考试 / 选课 / 调课那四链共用同一套档位**
     * （[ScoreAlertDefaults.INTERVAL_CHOICES]）——同一个 App 里两个「每 N 小时」的下拉
     * 给不同的档位会让人以为哪个是坏的。
     */
    val ACTIVITY_ALERT_INTERVAL_CHOICES: List<Int> = ScoreAlertDefaults.INTERVAL_CHOICES

    private val PHONE_REGEX = Regex("^1[3-9]\\d{9}$")
    private val json = Json { ignoreUnknownKeys = true }

    private val STAR_LABELS = arrayOf(
        "", "一星", "二星", "三星", "四星", "五星", "六星", "七星", "八星", "九星", "十星",
    )

    /**
     * 活动状态文案。取值来自小程序 `volunteer_list` / `volunteer_detail` 里的同一张表
     * （`{0:"已取消",1:"招募中",2:"进行中",3:"已结束"}`），别自己另立一套。
     * 认不出的值回空串——宁可不显示徽章，也别显示一个编出来的状态。
     */
    private val STATUS_LABELS = mapOf(
        0 to "已取消",
        1 to "招募中",
        2 to "进行中",
        3 to "已结束",
    )

    fun statusLabel(status: Int): String = STATUS_LABELS[status].orEmpty()

    /**
     * 这条活动「满了没」——招募中且名额已满。
     *
     * 判据用**剩余名额** `balance_num <= 0`（平台权威口径），但**招募人数没填（0）时一律不算满**：
     * 那种格子平台有整块不填的时候，把 0 当满会凭空藏掉一堆活动。
     * 已结束 / 进行中 / 已取消的活动不算「满」——它们本来就不在可报名的语义里。
     */
    fun isFull(status: Int, peopleNum: Int, balanceNum: Int): Boolean =
        status == 1 && peopleNum > 0 && balanceNum <= 0

    /**
     * 这条算不算「**新出现的、可报名的**活动」（新增活动提醒的判据）。
     *
     * - `id > lastSeenId`：列表按 id 大致倒序（实测前 21 条严格降序、之后会跳回旧 id），
     *   新建的活动拿的是最大 id，扫第一页就能圈到；
     * - `status == 1`（招募中）：**只认招募中**——平台会把补录的老活动插进来
     *   （实测 id 最大的一条就是「补录」且已结束），按 id 无差别报会把已经结束的活动
     *   报成「新活动」，纯噪音；
     * - [lastSeenId] ≤ 0 = 还没建基线（首跑）→ 一律不算：第一次抓到的全量是基线不是「新增」，
     *   否则用户一开开关就被几十条历史活动轰一遍。
     */
    fun isNewRecruiting(id: Long, status: Int, lastSeenId: Long): Boolean =
        lastSeenId > 0L && id > lastSeenId && status == STATUS_RECRUITING

    /** 活动状态「招募中」——唯一可报名的状态（见 [isFull] / [isNewRecruiting]）。 */
    const val STATUS_RECRUITING: Int = 1

    /**
     * 「报名截止临近」的排序键：**还没截止的排前面**（按截止时间升序），
     * 已截止 / 没填截止 / 解析不了的排后面（同 [stampMillis] 的不猜时间纪律）。
     *
     * 返回单个 Long 而不是 `Pair(档位, 时间)`：`Pair` 的 `compareTo` 是**扩展函数**、
     * 没实现 `Comparable` 接口，`sortedBy` 用不了（实测编译不过）。`Long.MAX_VALUE`
     * 天然比任何真实时刻都大，两档用同一个数轴表达就够了。
     */
    fun closingSortKey(
        applyEndTime: String,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long {
        val end = stampMillis(applyEndTime, zone)
        return if (end != null && end >= now) end else Long.MAX_VALUE
    }

    /** 平台时间戳的年份（记录按年份分组用）；认不出回 null。 */
    fun stampYear(raw: String, zone: ZoneId = ZoneId.systemDefault()): Int? =
        stampMillis(raw, zone)?.let { Instant.ofEpochMilli(it).atZone(zone).year }

    /**
     * 命中关键词在 [text] 里的所有位置（给卡片做**命中高亮**）。
     *
     * 归一化与 [matchesKeyword] **同一份**（trim + `lowercase()` 子串），
     * 否则会出现「搜到了但高亮不出来」或反过来的错位。
     * 逐段跳过已命中的区间，`aaaa` 里搜 `aa` 得两段而不是三段重叠。
     */
    fun keywordRanges(text: String, keyword: String): List<IntRange> {
        val needle = keyword.trim().lowercase()
        if (needle.isEmpty() || text.isEmpty()) return emptyList()
        val haystack = text.lowercase()
        val out = mutableListOf<IntRange>()
        var from = 0
        while (from <= haystack.length - needle.length) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) break
            out += at until (at + needle.length)
            from = at + needle.length
        }
        return out
    }

    /**
     * 列表筛选（三个维度都在**本地**做——服务端只认关键词）：
     * [statusFilter] null = 全部；[hideFull] = 隐藏已满；[appliedOnly] = 只看我报名的。
     *
     * [appliedOnly] 是 2026-10-11 补的：平台一直在回 `isApply`，卡上早有「已报名」徽章，
     * 却没有对应的筛选——「我报过哪些」是用户最常问的一句话。
     */
    fun matchesFilter(
        status: Int,
        peopleNum: Int,
        balanceNum: Int,
        statusFilter: Int?,
        hideFull: Boolean,
        appliedOnly: Boolean = false,
        applied: Boolean = false,
    ): Boolean {
        if (appliedOnly && !applied) return false
        if (statusFilter != null && status != statusFilter) return false
        if (hideFull && isFull(status, peopleNum, balanceNum)) return false
        return true
    }

    /**
     * 展示排序权重：招募中 → 进行中 → 已结束 → 其余（已取消 / 认不出的值）。
     *
     * 平台的顺序**既不是 id 也不是 add_time**（实测前 21 条严格降序、之后跳回旧 id），
     * 默认「全部」打开就是满屏已结束的活动。本地按这个权重**稳定排序**（组内保持平台原序），
     * 能报名的自然浮到最上面——**只重排不隐藏**。
     */
    fun displayRank(status: Int): Int = when (status) {
        STATUS_RECRUITING -> 0
        2 -> 1
        3 -> 2
        else -> 3
    }

    /**
     * 列表排序档位（DESIGN §3.28「排序显式化」，2026-10-11）。
     *
     * - [RecruitingFirst]：招募中 → 进行中 → 已结束 → 已取消（[displayRank]），
     *   组内保持平台原序（平台的顺序既不是 id 也不是时间）；
     * - [ClosingSoon]：按**报名截止**升序（[stampMillis] 解析不了 / 没有截止的排最后）——
     *   志愿场景里最该浮上来的就是快截止的，此前组内是平台原序等于没有时间序；
     * - [Newest]：按活动 id 降序（平台 id 单调增，等于发布时间倒序）。
     */
    enum class Sort(val label: String) {
        RecruitingFirst("招募中优先"),
        ClosingSoon("报名截止临近"),
        Newest("最新发布"),
    }

    /** 排序档位的展示顺序（设置页与筛选弹层共用，别各写一份）。 */
    val SORT_CHOICES: List<Sort> = Sort.entries.toList()

    /** 存储值 → 档位；认不出的回默认档（旧数据 / 手改数据防线）。 */
    fun coerceSort(stored: String?): Sort =
        Sort.entries.firstOrNull { it.name == stored } ?: Sort.RecruitingFirst

    /**
     * 平台时间戳 → epoch millis（**只给排序用**）。
     *
     * 格式是两位年份的 `yy-MM-dd HH:mm`（与 [compactStamp] 同一份实测样本），
     * 只有日期时按当天 00:00。**认不出回 null**——调用方按「排最后」处理，不猜时间
     * （同 `SelectionRounds.parseTimeRange` 的纪律）。
     */
    fun stampMillis(raw: String, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        return runCatching {
            // 只有日期时补 00:00——`LocalDateTime.parse` 吃不下没有时刻的串（实测抛
            // `Unable to obtain LocalDateTime`），所以两条路分开走
            val withTime = text.length > 10
            val pattern = if (withTime) "yy-MM-dd HH:mm" else "yy-MM-dd"
            val formatter = DateTimeFormatter.ofPattern(pattern, Locale.ROOT)
            val local = if (withTime) {
                LocalDateTime.parse(text, formatter)
            } else {
                LocalDate.parse(text, formatter).atStartOfDay()
            }
            local.atZone(zone).toInstant().toEpochMilli()
        }.getOrNull()
    }

    /**
     * 本地关键词匹配（搜索走本地全量索引的唯一判据）。
     *
     * 规则故意做到最笨：**trim + 小写后的子串包含**。理由——
     * - 服务端 `keyword` 到底匹配哪些字段没人能验证（无 token 回 `410000 请登录`），
     *   所以本地索引必须比服务端**宽松**：只要一个字段里出现过这段字，就算命中；
     * - [fields] 只传**界面上看得见的字段**（活动名 / 项目名 / 单位 / 地点），
     *   不传 `content`——搜出来的卡片上看不到那个词，用户会当成乱命中；
     * - 关键词为空 = 命中一切（浏览模式不经过这里，但保持这条语义便于组合）。
     *
     * `lowercase()` 是 Kotlin 的无参重载，等价于 `lowercase(Locale.ROOT)`，
     * 不受系统 Locale 影响（别换成 `toLowerCase()`，土耳其语环境下 `I` 会变成 `ı`）。
     */
    fun matchesKeyword(keyword: String, vararg fields: String): Boolean {
        val needle = keyword.trim().lowercase()
        if (needle.isEmpty()) return true
        return fields.any { it.lowercase().contains(needle) }
    }

    /**
     * 索引抓完这一页还要不要下一页。
     *
     * **「停」的四种情形**：这一页没带来任何新 id（服务端翻到头 / 忽略 page 参数）、
     * 累计条数触顶、页数触顶、时间预算用完。前一种 = 索引**完整**，
     * 后三种 = 索引**部分**（搜索要另走服务端兜底，见 `WenquRepository.activityIndex`）。
     *
     * 纯函数，JVM 可测；真实循环里 `elapsedMs` 由调用方用注入的时钟算。
     */
    fun shouldContinueIndexing(
        addedNew: Int,
        itemsSoFar: Int,
        pagesFetched: Int,
        elapsedMs: Long,
    ): Boolean = addedNew > 0 &&
        itemsSoFar < ACTIVITY_INDEX_MAX_ITEMS &&
        pagesFetched < ACTIVITY_INDEX_MAX_PAGES &&
        elapsedMs < ACTIVITY_INDEX_BUDGET_MS

    /**
     * 进页（或下拉刷新）时快照该走哪条路。
     *
     * - [Rebuild]：没有快照 / 上次**整份重抓**超过 [INDEX_MAX_AGE_MS] / 快照不完整
     *   → 整份重抓（几秒，页面给进度提示）；
     * - [SyncNewest]：只抓第一页（[INDEX_SYNC_PAGE_SIZE] 条）按 id 合并——新活动一定在
     *   列表开头，所以增量够用；[force]（下拉刷新）跳过间隔闸门，用户动作立即生效；
     * - [UseCache]：距上次同步不足 [INDEX_SYNC_INTERVAL_MS] → **一个请求都不发**。
     */
    enum class IndexLoad { UseCache, SyncNewest, Rebuild }

    /** 纯函数，JVM 可测（`WenquRepository.activityIndex` 按它的结果分派）。 */
    fun indexLoadPlan(
        hasCache: Boolean,
        cacheComplete: Boolean,
        fullCrawledAt: Long,
        syncedAt: Long,
        now: Long,
        force: Boolean,
    ): IndexLoad = when {
        !hasCache || !cacheComplete -> IndexLoad.Rebuild
        now - fullCrawledAt >= INDEX_MAX_AGE_MS -> IndexLoad.Rebuild
        force -> IndexLoad.SyncNewest
        now - syncedAt >= INDEX_SYNC_INTERVAL_MS -> IndexLoad.SyncNewest
        else -> IndexLoad.UseCache
    }

    /**
     * 用**不可变的时间字段**校正缓存里的状态。
     *
     * 为什么需要：增量同步只刷最新那几页，深层条目的 `status` 会冻在抓取那一刻
     * （一天一次整份重抓，所以最长冻一天）。冻住的「招募中」会被本地排序顶到最上面，
     * 而它其实早就结束了——这条校正是为了堵这个。
     *
     * **只做无歧义的一条**：活动**结束时间已过 → 已结束**。
     * 「报名截止已过但活动还没结束」算「已结束」还是「进行中」平台没实测过，**不猜**。
     * 时间认不出 / 没填 → 保持平台原值（宁可不改，也不编一个状态）。
     */
    fun effectiveStatus(
        status: Int,
        endTime: String,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Int {
        if (status == 3) return status
        val end = stampMillis(endTime, zone) ?: return status
        return if (end < now) 3 else status
    }

    /**
     * 报名限制的展示文案：平台原话优先，空则用平台小程序里那句兜底；
     * 没被拉黑回空串（调用方按空处理，不占行）。
     */
    fun applyBlockedText(blacklisted: Boolean, stopApplyReason: String): String = when {
        !blacklisted -> ""
        stopApplyReason.isNotBlank() -> stopApplyReason
        else -> "已被拉入黑名单，若有疑问请联系指导老师"
    }

    /** 该不该重新登录：到期时间未知（0）也算需要。 */
    fun needsRefresh(expiresAt: Long, now: Long): Boolean = expiresAt - now <= REFRESH_MARGIN_MS

    /** 距下次可重发还有多少毫秒；可以发了回 0。 */
    fun resendRemainingMs(sentAt: Long, now: Long): Long =
        (RESEND_COOLDOWN_MS - (now - sentAt)).coerceAtLeast(0L)

    /** 工时影子过期没（从没取过也算过期）。 */
    fun hoursStale(fetchedAt: Long, now: Long): Boolean =
        fetchedAt <= 0L || now - fetchedAt >= HOURS_TTL_MS

    /** 密码非空即可——平台没有格式限制（实测 6 位纯数字带前导 0 通过）。 */
    fun isValidPassword(password: String): Boolean = password.isNotBlank()

    /** 手机号格式（与平台同款正则）。 */
    fun isValidPhone(phone: String): Boolean = PHONE_REGEX.matches(phone)

    /**
     * 手机号脱敏：`138****0000`（设置页展示登录账号用）。
     * 不是 11 位就原样返回——认不出的东西不硬套模板。
     */
    fun maskPhone(phone: String): String =
        if (phone.length == 11) phone.take(3) + "****" + phone.takeLast(4) else phone

    /**
     * 从 JWT 里取 `exp` 并转毫秒。取不到回 0——调用方按「需要刷新」处理（宁多重登一次）。
     * 只读 payload 段，**不验签**（签名是服务端的事，App 不需要）。
     */
    fun jwtExpiryMillis(token: String): Long {
        val payload = token.split('.').getOrNull(1)?.takeIf { it.isNotBlank() } ?: return 0L
        return runCatching {
            val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
            val text = String(Base64.getUrlDecoder().decode(padded), Charsets.UTF_8)
            json.parseToJsonElement(text).jsonObject["exp"]?.jsonPrimitive?.longOrNull?.times(1000L)
        }.getOrNull() ?: 0L
    }

    /** 星级文案：1 → 「一星」，越界回空串（宁缺勿错，别显示「0 星」）。 */
    fun starLabel(level: Int): String = STAR_LABELS.getOrElse(level) { "" }

    /** 工时展示：整数不带小数点（100 而不是 100.0）。 */
    fun formatHours(hours: Double): String {
        val rounded = Math.round(hours * 10.0) / 10.0
        return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
    }

    /** 宽容串 → Double（平台有时回数字、有时回字符串，解析不出按 0）。 */
    fun hoursOf(raw: String?): Double = raw?.trim()?.toDoubleOrNull() ?: 0.0

    /**
     * 平台时间戳去重成紧凑写法：`26-10-13 12:30` → `10-13 12:30`。
     *
     * 平台给的是**两位年份**（`26` / `27`），一屏里十几个「26-」纯占地方；月日与时刻是
     * 真正要看的。认不出格式就原样返回，不做猜测。
     */
    fun compactStamp(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) return ""
        val date = text.substringBefore(' ')
        val time = text.substringAfter(' ', "")
        val segments = date.split('-')
        val shortDate = if (segments.size >= 2) segments.takeLast(2).joinToString("-") else date
        return if (time.isEmpty()) shortDate else "$shortDate $time"
    }

    /**
     * 一段时间的展示：同一天只写一次日期（`10-13 12:30–16:30`），跨天写全
     * （`10-05 00:00 – 10-06 18:30`）。任一端为空就只给另一端，两端都空回空串。
     */
    fun timeRange(start: String, end: String): String {
        val s = compactStamp(start)
        val e = compactStamp(end)
        if (s.isEmpty()) return e
        if (e.isEmpty()) return s
        val startDate = s.substringBefore(' ')
        val endDate = e.substringBefore(' ')
        val startTime = s.substringAfter(' ', "")
        val endTime = e.substringAfter(' ', "")
        return if (startDate == endDate && startTime.isNotEmpty() && endTime.isNotEmpty()) {
            "$startDate $startTime–$endTime"
        } else {
            "$s – $e"
        }
    }

    /**
     * **列表卡**右侧的时刻文案：`今天 14:00–17:00` / `明天 09:00` / `10-13 14:00–16:30`。
     *
     * 今天 / 明天（抢活动的常态）一眼就看得懂，不用先算日期；其余走 [timeRange] 原样。
     * 只有日期没有时刻时只给「今天 / 明天」——别拼出「今天 10-13」那种东西。
     */
    fun cardTimeLabel(
        start: String,
        end: String,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val range = timeRange(start, end)
        if (range.isEmpty()) return ""
        val startDate = stampMillis(start, zone)
            ?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
            ?: return range
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val prefix = when (startDate) {
            today -> "今天"
            today.plusDays(1) -> "明天"
            else -> return range
        }
        // timeRange 的输出以日期开头（同一天 `MM-dd HH:mm–HH:mm`、跨天 `MM-dd HH:mm – MM-dd HH:mm`），
        // 有前缀时把那段日期去掉
        val rest = range.substringAfter(' ', "")
        return if (rest.isEmpty()) prefix else "$prefix $rest"
    }

    /**
     * 名额文案：`已报 24/24`；招募人数没填（0）时退化成 `已报 24`，两边都没有则空串。
     * 「已满」的判定不在这里——它还要看活动状态（见 [isFull]）。
     */
    fun quotaText(joinNum: Int, peopleNum: Int): String = when {
        peopleNum > 0 -> "已报 $joinNum/$peopleNum"
        joinNum > 0 -> "已报 $joinNum"
        else -> ""
    }

    /**
     * **列表卡**右侧的名额文案（2026-10-11 改口径）。
     *
     * 卡面只回答一个问题：「还能不能报」。所以
     * - 只有**招募中**给数字：「还剩 N」（平台权威余量 `balance_num`）/「已满」；
     * - 招募人数没填（0）时退化成「已报 N」（没有上限可比，不能说还剩）；
     * - 进行中 / 已结束 / 已取消**不给**——它们本来就不在可报名的语义里，挂个名额只占位。
     *
     * 详情弹层里的完整口径（`已报 21 / 24（还剩 3）`）仍走 [quotaText]，别拿这个替。
     */
    fun cardQuotaText(status: Int, joinNum: Int, peopleNum: Int, balanceNum: Int): String = when {
        status != STATUS_RECRUITING -> ""
        peopleNum > 0 && balanceNum <= 0 -> "已满"
        peopleNum > 0 -> "还剩 $balanceNum"
        else -> quotaText(joinNum, peopleNum)
    }

    /** 存储值兜底：不在档位表里归到默认档（旧数据 / 手改数据防线）。 */
    fun coerceActivityIntervalHours(value: Int): Int =
        if (value in ACTIVITY_ALERT_INTERVAL_CHOICES) value else ACTIVITY_ALERT_INTERVAL_DEFAULT

    /** 存储值 → 选项下标；不在档位表内的值归到默认档。 */
    fun activityIntervalChoiceIndex(value: Int): Int =
        ACTIVITY_ALERT_INTERVAL_CHOICES.indexOf(coerceActivityIntervalHours(value)).coerceAtLeast(0)

    /** 存储值 → 展示文案（用自己的兜底档，别借 `ScoreAlertDefaults.intervalLabel` 的 6 小时兜底）。 */
    fun activityIntervalLabel(value: Int): String = "每 ${coerceActivityIntervalHours(value)} 小时"

    // ---- 每周日 12:00「抢活动」提醒（DESIGN §4.45，2026-10-11）-------------------------
    //
    // 用户口径：**学校很多志愿活动都是周日中午 12:00 发布、当场开抢**，所以要一条
    // 「提前 N 分钟」的固定提醒。与 [isNewRecruiting] 那条「检测到新活动就报」是两件事：
    // 那条是事后发现，这条是**事前蹲点**（发布时刻还没到，扫也扫不到新东西）。

    /** 发布时刻：周日 12:00（本地时区）。 */
    const val WEEKLY_ALERT_HOUR: Int = 12
    const val WEEKLY_ALERT_MINUTE: Int = 0

    /** 提前提醒量的默认值（分钟）——用户拍板 5 分钟。 */
    const val WEEKLY_ALERT_OFFSET_DEFAULT_MIN: Int = 5

    /** 提前提醒量的可调范围（分钟，用户拍板 1–10）。 */
    const val WEEKLY_ALERT_OFFSET_MIN: Int = 1
    const val WEEKLY_ALERT_OFFSET_MAX: Int = 10

    /**
     * 迟到窗口：提醒点过去超过这么久就**不再补发**（对齐免扰时段对时刻型提醒的过期口径，
     * 见 `.agents/rules/wenqu.md`「免扰」）。过了这个窗口的提醒没有意义——
     * 用户要的是「12:00 前就位」，12:30 才响只是噪音。
     */
    const val WEEKLY_ALERT_LATE_MS: Long = 10 * 60 * 1000L

    /** 提前量选项（设置页滚轮用）。 */
    val WEEKLY_ALERT_OFFSET_CHOICES: List<Int> =
        (WEEKLY_ALERT_OFFSET_MIN..WEEKLY_ALERT_OFFSET_MAX).toList()

    /** 存储值 → 提前量；越界 / 脏值归默认档（旧数据 / 手改数据防线）。 */
    fun coerceWeeklyOffsetMinutes(value: Int): Int =
        if (value in WEEKLY_ALERT_OFFSET_MIN..WEEKLY_ALERT_OFFSET_MAX) {
            value
        } else {
            WEEKLY_ALERT_OFFSET_DEFAULT_MIN
        }

    /** 提前量的展示文案（`提前 5 分钟`）。 */
    fun weeklyOffsetLabel(minutes: Int): String = "提前 ${coerceWeeklyOffsetMinutes(minutes)} 分钟"

    /** 提前量 → 滚轮选项下标。 */
    fun weeklyOffsetChoiceIndex(minutes: Int): Int =
        WEEKLY_ALERT_OFFSET_CHOICES.indexOf(coerceWeeklyOffsetMinutes(minutes)).coerceAtLeast(0)

    /**
     * 一次「每周日 12:00 抢活动」提醒点。
     *
     * [publishAt] = 本周日的发布时刻（周日 12:00）——它是**「这周报过没」的键**，
     * **与提前量无关**：用户中途把提前量从 5 分钟改成 10 分钟，同一周不该再报一次。
     * [remindAt] 才是闹钟真正响的时刻（= [publishAt] − 提前量）。
     */
    data class WeeklyAlertPoint(val publishAt: Long, val remindAt: Long)

    /** 下一个提醒点（[WeeklyAlertPoint.remindAt] **严格晚于** now）。 */
    fun nextWeeklyAlertPoint(
        now: Long,
        offsetMinutes: Int,
        zone: ZoneId = ZoneId.systemDefault(),
    ): WeeklyAlertPoint {
        val offset = coerceWeeklyOffsetMinutes(offsetMinutes)
        val sunday = nextOrSameSunday(now, zone)
        val point = weeklyPointOn(sunday, offset, zone)
        return if (point.remindAt <= now) weeklyPointOn(sunday.plusWeeks(1), offset, zone) else point
    }

    /** 最近一个**已经到点**的提醒点（[WeeklyAlertPoint.remindAt] ≤ now）。 */
    fun lastWeeklyAlertPoint(
        now: Long,
        offsetMinutes: Int,
        zone: ZoneId = ZoneId.systemDefault(),
    ): WeeklyAlertPoint {
        val offset = coerceWeeklyOffsetMinutes(offsetMinutes)
        val sunday = nextOrSameSunday(now, zone)
        val point = weeklyPointOn(sunday, offset, zone)
        return if (point.remindAt > now) weeklyPointOn(sunday.minusWeeks(1), offset, zone) else point
    }

    /** 该提醒点属于哪一周的哪次发布（给通知正文用：`今天 12:00`）。 */
    fun weeklyPublishLabel(publishAt: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val publish = Instant.ofEpochMilli(publishAt).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val time = publish.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT))
        val day = if (publish.toLocalDate() == today) {
            "今天"
        } else {
            "${publish.monthValue} 月 ${publish.dayOfMonth} 日"
        }
        return "$day $time"
    }

    /** 今天（含）之后的第一个周日。 */
    private fun nextOrSameSunday(now: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            .with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))

    private fun weeklyPointOn(sunday: LocalDate, offsetMinutes: Int, zone: ZoneId): WeeklyAlertPoint {
        val publishAt = sunday.atTime(WEEKLY_ALERT_HOUR, WEEKLY_ALERT_MINUTE)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
        return WeeklyAlertPoint(
            publishAt = publishAt,
            remindAt = publishAt - offsetMinutes * 60_000L,
        )
    }
}
