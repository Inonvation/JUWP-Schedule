package edu.jxslu.schedule.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Base64

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
     * 列表筛选（两个维度都在**本地**做——服务端只认关键词）：
     * [statusFilter] null = 全部；[hideFull] = 隐藏已满。
     */
    fun matchesFilter(
        status: Int,
        peopleNum: Int,
        balanceNum: Int,
        statusFilter: Int?,
        hideFull: Boolean,
    ): Boolean {
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
     * 名额文案：`已报 24/24`；招募人数没填（0）时退化成 `已报 24`，两边都没有则空串。
     * 「已满」的判定不在这里——它还要看活动状态（见 [isFull]）。
     */
    fun quotaText(joinNum: Int, peopleNum: Int): String = when {
        peopleNum > 0 -> "已报 $joinNum/$peopleNum"
        joinNum > 0 -> "已报 $joinNum"
        else -> ""
    }

    /** 存储值兜底：不在档位表里归到默认档（旧数据 / 手改数据防线）。 */
    fun coerceActivityIntervalHours(value: Int): Int =
        if (value in ACTIVITY_ALERT_INTERVAL_CHOICES) value else ACTIVITY_ALERT_INTERVAL_DEFAULT

    /** 存储值 → 选项下标；不在档位表内的值归到默认档。 */
    fun activityIntervalChoiceIndex(value: Int): Int =
        ACTIVITY_ALERT_INTERVAL_CHOICES.indexOf(coerceActivityIntervalHours(value)).coerceAtLeast(0)

    /** 存储值 → 展示文案（用自己的兜底档，别借 `ScoreAlertDefaults.intervalLabel` 的 6 小时兜底）。 */
    fun activityIntervalLabel(value: Int): String = "每 ${coerceActivityIntervalHours(value)} 小时"
}
