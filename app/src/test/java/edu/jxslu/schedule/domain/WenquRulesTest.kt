package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Base64

/**
 * 问渠纯逻辑（DESIGN §4.45）：续期时机、验证码冷却、输入校验、展示口径。
 *
 * 常量来源都是实测：token 3 天、短信约 5 分钟一条、平台手机号正则 `^1[3-9]\d{9}$`。
 */
class WenquRulesTest {

    private val hour = 60 * 60 * 1000L

    /** 固定时区：问渠给的都是本地时间，测试钉死时区，别跟着 CI 机器的默认时区走。 */
    private val zone = ZoneId.of("Asia/Shanghai")

    /** 本地时间 → epoch millis（测试里读起来是「几点几分」而不是一串数字）。 */
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int): Long =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `token 剩余不足 12 小时就要续期`() {
        val now = 1_000_000_000L
        assertTrue(WenquRules.needsRefresh(now + 11 * hour, now))
        assertTrue(WenquRules.needsRefresh(now + 12 * hour, now))
        assertFalse(WenquRules.needsRefresh(now + 13 * hour, now))
    }

    @Test
    fun `到期时间未知按需要续期处理`() {
        assertTrue(WenquRules.needsRefresh(0L, 1_000_000_000L))
    }

    @Test
    fun `验证码五分钟冷却`() {
        val sent = 1_000_000_000L
        assertEquals(5 * 60 * 1000L, WenquRules.resendRemainingMs(sent, sent))
        assertEquals(60_000L, WenquRules.resendRemainingMs(sent, sent + 4 * 60 * 1000L))
        assertEquals(0L, WenquRules.resendRemainingMs(sent, sent + 5 * 60 * 1000L))
        assertEquals(0L, WenquRules.resendRemainingMs(sent, sent + 10 * 60 * 1000L))
    }

    @Test
    fun `工时影子半小时算过期_从没取过也算`() {
        val now = 1_000_000_000L
        assertTrue(WenquRules.hoursStale(0L, now))
        assertTrue(WenquRules.hoursStale(now - 31 * 60 * 1000L, now))
        assertFalse(WenquRules.hoursStale(now - 29 * 60 * 1000L, now))
    }

    @Test
    fun `手机号校验与平台同款`() {
        assertTrue(WenquRules.isValidPhone("13800000000"))
        assertTrue(WenquRules.isValidPhone("19912345678"))
        assertFalse(WenquRules.isValidPhone("10000000000")) // 第二位 0，平台回「手机号格式错误」
        assertFalse(WenquRules.isValidPhone("1380000000"))   // 10 位
        assertFalse(WenquRules.isValidPhone("138000000000"))
        assertFalse(WenquRules.isValidPhone(""))
    }

    @Test
    fun `密码非空即可_平台没有格式限制`() {
        // 夹具一律虚构：6 位纯数字带前导 0 只是「平台不挑格式」的样例，别拿真密码当例子
        assertTrue(WenquRules.isValidPassword("012345"))
        assertTrue(WenquRules.isValidPassword("a"))
        assertFalse(WenquRules.isValidPassword(""))
        assertFalse(WenquRules.isValidPassword("   "))
    }

    @Test
    fun `从 JWT 取到期时间`() {
        val payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"iss":"wenqu.juwp.edu.cn","exp":1791717777}""".toByteArray())
        val token = "eyJhbGciOiJIUzI1NiJ9.$payload.sig"
        assertEquals(1791717777_000L, WenquRules.jwtExpiryMillis(token))
    }

    @Test
    fun `取不到到期时间回 0 而不是崩`() {
        assertEquals(0L, WenquRules.jwtExpiryMillis(""))
        assertEquals(0L, WenquRules.jwtExpiryMillis("not-a-jwt"))
        assertEquals(0L, WenquRules.jwtExpiryMillis("a.b.c"))
    }

    @Test
    fun `星级文案越界回空串`() {
        assertEquals("一星", WenquRules.starLabel(1))
        assertEquals("二星", WenquRules.starLabel(2))
        assertEquals("十星", WenquRules.starLabel(10))
        assertEquals("", WenquRules.starLabel(0))
        assertEquals("", WenquRules.starLabel(-1))
        assertEquals("", WenquRules.starLabel(99))
    }

    @Test
    fun `工时展示去掉多余小数点`() {
        assertEquals("100", WenquRules.formatHours(100.0))
        assertEquals("100.5", WenquRules.formatHours(100.5))
        assertEquals("0", WenquRules.formatHours(0.0))
        assertEquals("12.3", WenquRules.formatHours(12.34))
    }

    @Test
    fun `工时宽容解析_数字与字符串都认`() {
        assertEquals(100.0, WenquRules.hoursOf("100"), 0.0)
        assertEquals(100.0, WenquRules.hoursOf(" 100 "), 0.0)
        assertEquals(0.0, WenquRules.hoursOf(null), 0.0)
        assertEquals(0.0, WenquRules.hoursOf(""), 0.0)
        assertEquals(0.0, WenquRules.hoursOf("暂无"), 0.0)
    }

    @Test
    fun `活动状态文案照小程序那张表`() {
        // 取值与小程序 volunteer_list / volunteer_detail 的 {0:已取消,1:招募中,2:进行中,3:已结束} 一致
        assertEquals("已取消", WenquRules.statusLabel(0))
        assertEquals("招募中", WenquRules.statusLabel(1))
        assertEquals("进行中", WenquRules.statusLabel(2))
        assertEquals("已结束", WenquRules.statusLabel(3))
    }

    @Test
    fun `认不出的状态回空串_不编一个状态出来`() {
        assertEquals("", WenquRules.statusLabel(9))
        assertEquals("", WenquRules.statusLabel(-1))
    }

    @Test
    fun `时间戳去掉两位年份`() {
        assertEquals("10-13 12:30", WenquRules.compactStamp("26-10-13 12:30"))
        assertEquals("09-06 18:30", WenquRules.compactStamp("27-09-06 18:30"))
        assertEquals("10-13", WenquRules.compactStamp("26-10-13"))
    }

    @Test
    fun `时间戳认不出就原样返回`() {
        assertEquals("", WenquRules.compactStamp(""))
        assertEquals("待定", WenquRules.compactStamp("待定"))
        // 前后空白先 trim 再判，别把空格带进界面
        assertEquals("待定", WenquRules.compactStamp("  待定  "))
    }

    @Test
    fun `同一段时间只写一次日期`() {
        assertEquals(
            "10-13 12:30–16:30",
            WenquRules.timeRange("26-10-13 12:30", "26-10-13 16:30"),
        )
    }

    @Test
    fun `跨天的时间段两端都写全`() {
        assertEquals(
            "10-05 00:00 – 10-06 18:30",
            WenquRules.timeRange("26-10-05 00:00", "26-10-06 18:30"),
        )
    }

    @Test
    fun `时间段缺一端就只给另一端`() {
        assertEquals("10-13 12:30", WenquRules.timeRange("26-10-13 12:30", ""))
        assertEquals("10-13 16:30", WenquRules.timeRange("", "26-10-13 16:30"))
        assertEquals("", WenquRules.timeRange("", ""))
    }

    @Test
    fun `列表卡时刻_今天明天一眼看懂_其余原样`() {
        val now = at(2026, 10, 11, 9, 0)
        assertEquals(
            "今天 14:00–17:00",
            WenquRules.cardTimeLabel("26-10-11 14:00", "26-10-11 17:00", now, zone),
        )
        assertEquals("明天 09:00", WenquRules.cardTimeLabel("26-10-12 09:00", "", now, zone))
        // 后天及更远：走 timeRange 原样（不带前缀）
        assertEquals(
            "10-13 14:00–16:30",
            WenquRules.cardTimeLabel("26-10-13 14:00", "26-10-13 16:30", now, zone),
        )
        // 只有日期没有时刻：只给「今天」，别拼出「今天 10-11」
        assertEquals("今天", WenquRules.cardTimeLabel("26-10-11", "", now, zone))
        // 空 / 认不出：不猜
        assertEquals("", WenquRules.cardTimeLabel("", "", now, zone))
        assertEquals("待定", WenquRules.cardTimeLabel("待定", "", now, zone))
    }

    @Test
    fun `名额文案_招募人数没填就只给已报`() {
        assertEquals("已报 24/24", WenquRules.quotaText(24, 24))
        assertEquals("已报 1/20", WenquRules.quotaText(1, 20))
        assertEquals("已报 7", WenquRules.quotaText(7, 0))
        assertEquals("", WenquRules.quotaText(0, 0))
    }

    @Test
    fun `满了没_招募中且剩余名额为零才算`() {
        // 招募中 + 名额报满（实测 24/24、balance 0）
        assertTrue(WenquRules.isFull(status = 1, peopleNum = 24, balanceNum = 0))
        // 还剩名额（实测 29/30、balance 1）
        assertFalse(WenquRules.isFull(status = 1, peopleNum = 30, balanceNum = 1))
        // 招募人数平台没填（0）→ 一律不算满，否则会凭空藏掉一堆活动
        assertFalse(WenquRules.isFull(status = 1, peopleNum = 0, balanceNum = 0))
        // 已结束 / 进行中 / 已取消 都不在「满」的语义里
        assertFalse(WenquRules.isFull(status = 3, peopleNum = 40, balanceNum = 0))
        assertFalse(WenquRules.isFull(status = 2, peopleNum = 41, balanceNum = 0))
        assertFalse(WenquRules.isFull(status = 0, peopleNum = 10, balanceNum = 0))
    }

    @Test
    fun `本地筛选_状态与隐藏已满叠加`() {
        // 全部、不隐藏 → 什么都留
        assertTrue(WenquRules.matchesFilter(1, 24, 0, statusFilter = null, hideFull = false))
        // 只看招募中
        assertTrue(WenquRules.matchesFilter(1, 24, 0, statusFilter = 1, hideFull = false))
        assertFalse(WenquRules.matchesFilter(3, 24, 0, statusFilter = 1, hideFull = false))
        // 隐藏已满：把满了的招募中滤掉，但别把已结束的一起滤掉（它们本来就不是「满」）
        assertFalse(WenquRules.matchesFilter(1, 24, 0, statusFilter = null, hideFull = true))
        assertTrue(WenquRules.matchesFilter(1, 30, 1, statusFilter = null, hideFull = true))
        assertTrue(WenquRules.matchesFilter(3, 40, 0, statusFilter = null, hideFull = true))
        // 两个维度叠加
        assertTrue(WenquRules.matchesFilter(1, 30, 1, statusFilter = 1, hideFull = true))
        assertFalse(WenquRules.matchesFilter(1, 24, 0, statusFilter = 1, hideFull = true))
    }

    @Test
    fun `新增活动判据_只认招募中_首跑不算`() {
        // 基线没建（lastSeen ≤ 0）：第一次抓到的全量是基线，一律不算新增
        assertFalse(WenquRules.isNewRecruiting(id = 999, status = 1, lastSeenId = 0))
        // 比基线新的招募中 → 算
        assertTrue(WenquRules.isNewRecruiting(id = 37481, status = 1, lastSeenId = 37480))
        // 比基线旧的 → 不算
        assertFalse(WenquRules.isNewRecruiting(id = 37479, status = 1, lastSeenId = 37480))
        // 新 id 但已结束（平台会把补录的老活动插进来，实测 id 最大的那条就是补录）→ 不算
        assertFalse(WenquRules.isNewRecruiting(id = 37481, status = 3, lastSeenId = 37480))
    }

    @Test
    fun `手机号脱敏_只留头三尾四`() {
        assertEquals("138****0000", WenquRules.maskPhone("13800000000"))
        // 认不出长度的原样返回，不硬套模板
        assertEquals("123", WenquRules.maskPhone("123"))
        assertEquals("", WenquRules.maskPhone(""))
    }

    @Test
    fun `活动提醒间隔档位与兜底`() {
        assertEquals(24, WenquRules.ACTIVITY_ALERT_INTERVAL_DEFAULT)
        assertTrue(WenquRules.ACTIVITY_ALERT_INTERVAL_CHOICES.contains(24))
        // 合法档位原样保留
        assertEquals(1, WenquRules.coerceActivityIntervalHours(1))
        assertEquals(24, WenquRules.coerceActivityIntervalHours(24))
        // 脏值归到**问渠自己的**默认档（24），不是成绩那链的 6
        assertEquals(24, WenquRules.coerceActivityIntervalHours(5))
        assertEquals(24, WenquRules.coerceActivityIntervalHours(0))
        assertEquals("每 24 小时", WenquRules.activityIntervalLabel(5))
        assertEquals("每 1 小时", WenquRules.activityIntervalLabel(1))
        assertEquals(4, WenquRules.activityIntervalChoiceIndex(24))
        assertEquals(4, WenquRules.activityIntervalChoiceIndex(7))
    }

    @Test
    fun `展示排序_招募中优先_其余压到最后`() {
        // 平台的顺序既不是 id 也不是 add_time，本地按这个权重排
        assertEquals(0, WenquRules.displayRank(1))
        assertEquals(1, WenquRules.displayRank(2))
        assertEquals(2, WenquRules.displayRank(3))
        assertEquals(3, WenquRules.displayRank(0))
        // 认不出的值也压到最后，不插队
        assertEquals(3, WenquRules.displayRank(9))
        // 排出来确实是「招募中 → 进行中 → 已结束 → 已取消」
        val sorted = listOf(3, 0, 1, 2).sortedBy { WenquRules.displayRank(it) }
        assertEquals(listOf(1, 2, 3, 0), sorted)
    }

    @Test
    fun `报名限制文案_平台原话优先`() {
        assertEquals("", WenquRules.applyBlockedText(blacklisted = false, stopApplyReason = ""))
        // 没被拉黑时即便带了原因也不显示（平台有整格不填的时候）
        assertEquals("", WenquRules.applyBlockedText(blacklisted = false, stopApplyReason = "缺席 2 次"))
        assertEquals("缺席 2 次", WenquRules.applyBlockedText(blacklisted = true, stopApplyReason = "缺席 2 次"))
        // 被拉黑但平台没给原因：用平台小程序里那句兜底，不留空白
        assertEquals(
            "已被拉入黑名单，若有疑问请联系指导老师",
            WenquRules.applyBlockedText(blacklisted = true, stopApplyReason = ""),
        )
    }

    @Test
    fun `筛选全部用 -1 当哨兵_不与平台状态值相撞`() {
        assertEquals(-1, WenquRules.FILTER_ALL_STORED)
        // 平台的状态只有 0-3，哨兵不能落在里面
        assertFalse(WenquRules.FILTER_ALL_STORED in 0..3)
    }

    @Test
    fun `本地关键词_四个可见字段任一命中就算`() {
        // 活动名 / 项目名 / 单位 / 地点，任一含这段字就命中——这就是「列表里看得见的搜得到」
        assertTrue(WenquRules.matchesKeyword("爱心家教", "2026年爱心家教志愿服务", "", "", ""))
        assertTrue(WenquRules.matchesKeyword("爱心家教", "", "爱心家教项目", "", ""))
        assertTrue(WenquRules.matchesKeyword("爱心家教", "", "", "某某学院爱心家教服务队", ""))
        assertTrue(WenquRules.matchesKeyword("爱心家教", "", "", "", "爱心家教实践基地"))
        // 四个字段都没有 → 不命中
        assertFalse(WenquRules.matchesKeyword("爱心家教", "支教服务", "支教项目", "某学院", "某小学"))
    }

    @Test
    fun `本地关键词_子串匹配_忽略大小写与前后空白`() {
        // 子串而不是全等：关键词只是活动名的一部分
        assertTrue(WenquRules.matchesKeyword("家教", "爱心家教志愿服务"))
        // 前后空白先 trim；ASCII 大小写不敏感
        assertTrue(WenquRules.matchesKeyword("  zhijiao  ", "ZhiJiao 支教服务"))
        assertTrue(WenquRules.matchesKeyword("zhijiao", "ZHIJIAO"))
    }

    @Test
    fun `本地关键词_空串命中一切`() {
        assertTrue(WenquRules.matchesKeyword("", "任意活动"))
        assertTrue(WenquRules.matchesKeyword("   ", "任意活动"))
    }

    @Test
    fun `索引翻页_有新东西且没触顶就继续`() {
        assertTrue(
            WenquRules.shouldContinueIndexing(
                addedNew = 200, itemsSoFar = 200, pagesFetched = 1, elapsedMs = 500,
            ),
        )
        // 这一页没带来任何新 id（服务端翻到头 / 忽略 page 参数）→ 停，且索引算「完整」
        assertFalse(
            WenquRules.shouldContinueIndexing(
                addedNew = 0, itemsSoFar = 800, pagesFetched = 4, elapsedMs = 2_000,
            ),
        )
        // 累计条数触顶
        assertFalse(
            WenquRules.shouldContinueIndexing(
                addedNew = 200,
                itemsSoFar = WenquRules.ACTIVITY_INDEX_MAX_ITEMS,
                pagesFetched = 3,
                elapsedMs = 1_000,
            ),
        )
        // 页数触顶
        assertFalse(
            WenquRules.shouldContinueIndexing(
                addedNew = 200,
                itemsSoFar = 500,
                pagesFetched = WenquRules.ACTIVITY_INDEX_MAX_PAGES,
                elapsedMs = 1_000,
            ),
        )
        // 时间预算用完
        assertFalse(
            WenquRules.shouldContinueIndexing(
                addedNew = 200,
                itemsSoFar = 500,
                pagesFetched = 3,
                elapsedMs = WenquRules.ACTIVITY_INDEX_BUDGET_MS,
            ),
        )
    }

    @Test
    fun `索引每页比浏览页大一个量级`() {
        // 索引要一次抓完全量，照浏览那样 20 条一页就是几十个请求
        assertTrue(WenquRules.ACTIVITY_INDEX_PAGE_SIZE > WenquRules.ACTIVITY_PAGE_SIZE)
    }

    // ---- 排序档位 / 名额文案 / 只看我报名的（2026-10-11）----------------------------

    @Test
    fun `排序档位_存储值兜底`() {
        assertEquals(3, WenquRules.SORT_CHOICES.size)
        assertEquals(WenquRules.Sort.RecruitingFirst, WenquRules.coerceSort(null))
        assertEquals(WenquRules.Sort.RecruitingFirst, WenquRules.coerceSort("Nope"))
        assertEquals(WenquRules.Sort.ClosingSoon, WenquRules.coerceSort("ClosingSoon"))
        assertEquals("报名截止临近", WenquRules.Sort.ClosingSoon.label)
    }

    @Test
    fun `平台时间戳解析_只给排序用_认不出回 null`() {
        assertEquals(at(2026, 10, 12, 18, 0), WenquRules.stampMillis("26-10-12 18:00", zone))
        assertEquals(at(2026, 10, 12, 0, 0), WenquRules.stampMillis("26-10-12", zone))
        assertEquals(null, WenquRules.stampMillis("", zone))
        assertEquals(null, WenquRules.stampMillis("待定", zone))
    }

    @Test
    fun `列表卡名额文案_只对招募中给数字`() {
        // 招募中：直接说还剩几个（平台权威余量）
        assertEquals("还剩 3", WenquRules.cardQuotaText(1, joinNum = 21, peopleNum = 24, balanceNum = 3))
        assertEquals("已满", WenquRules.cardQuotaText(1, joinNum = 24, peopleNum = 24, balanceNum = 0))
        // 招募人数没填（0）→ 没有上限可比，不能说还剩，退化成「已报 N」
        assertEquals("已报 7", WenquRules.cardQuotaText(1, joinNum = 7, peopleNum = 0, balanceNum = 0))
        // 其余状态不给名额（它们不在可报名的语义里）
        assertEquals("", WenquRules.cardQuotaText(3, joinNum = 40, peopleNum = 40, balanceNum = 0))
        assertEquals("", WenquRules.cardQuotaText(2, joinNum = 3, peopleNum = 10, balanceNum = 7))
        assertEquals("", WenquRules.cardQuotaText(0, joinNum = 3, peopleNum = 10, balanceNum = 7))
    }

    @Test
    fun `本地筛选_只看我报名的`() {
        assertFalse(WenquRules.matchesFilter(1, 24, 3, null, false, appliedOnly = true, applied = false))
        assertTrue(WenquRules.matchesFilter(1, 24, 3, null, false, appliedOnly = true, applied = true))
        // 三个维度叠加
        assertFalse(WenquRules.matchesFilter(1, 24, 0, null, true, appliedOnly = true, applied = true))
        // 默认不开 = 与旧行为逐字一致
        assertTrue(WenquRules.matchesFilter(1, 24, 0, null, false))
    }

    // ---- 每周日 12:00「抢活动」提醒（2026-10-11）------------------------------------

    @Test
    fun `每周提醒_下一个提醒点是本周日 12 点减提前量`() {
        // 2026-10-07 是周三 → 下一个发布 = 2026-10-11（周日）12:00
        val next = WenquRules.nextWeeklyAlertPoint(at(2026, 10, 7, 10, 0), 5, zone)
        assertEquals(at(2026, 10, 11, 12, 0), next.publishAt)
        assertEquals(at(2026, 10, 11, 11, 55), next.remindAt)
    }

    @Test
    fun `每周提醒_当天已过提醒点就排下周`() {
        // 周日 12:10（今天的 11:55 已过）→ 下一个是 10-18
        assertEquals(
            at(2026, 10, 18, 11, 55),
            WenquRules.nextWeeklyAlertPoint(at(2026, 10, 11, 12, 10), 5, zone).remindAt,
        )
        // 周日 11:00 → 下一个就是今天 11:55
        assertEquals(
            at(2026, 10, 11, 11, 55),
            WenquRules.nextWeeklyAlertPoint(at(2026, 10, 11, 11, 0), 5, zone).remindAt,
        )
        // 正好站在提醒点上 → 要求严格晚于 now，排下周
        assertEquals(
            at(2026, 10, 18, 11, 55),
            WenquRules.nextWeeklyAlertPoint(at(2026, 10, 11, 11, 55), 5, zone).remindAt,
        )
    }

    @Test
    fun `每周提醒_最近一个到点的是本周或上周`() {
        assertEquals(
            at(2026, 10, 11, 11, 55),
            WenquRules.lastWeeklyAlertPoint(at(2026, 10, 11, 12, 10), 5, zone).remindAt,
        )
        // 周日 11:00 还没到点 → 回上周
        assertEquals(
            at(2026, 10, 4, 11, 55),
            WenquRules.lastWeeklyAlertPoint(at(2026, 10, 11, 11, 0), 5, zone).remindAt,
        )
    }

    @Test
    fun `每周提醒_提前量可调_改提前量不改发布键`() {
        assertEquals(5, WenquRules.WEEKLY_ALERT_OFFSET_DEFAULT_MIN)
        assertEquals(1, WenquRules.WEEKLY_ALERT_OFFSET_MIN)
        assertEquals(10, WenquRules.WEEKLY_ALERT_OFFSET_MAX)
        assertEquals(10, WenquRules.coerceWeeklyOffsetMinutes(10))
        assertEquals(1, WenquRules.coerceWeeklyOffsetMinutes(1))
        assertEquals(5, WenquRules.coerceWeeklyOffsetMinutes(0))
        assertEquals(5, WenquRules.coerceWeeklyOffsetMinutes(99))
        assertEquals("提前 5 分钟", WenquRules.weeklyOffsetLabel(5))
        // 脏值归**默认档**（同 `coerceActivityIntervalHours` 的口径），不是钳到边界
        assertEquals("提前 5 分钟", WenquRules.weeklyOffsetLabel(99))
        assertEquals("提前 5 分钟", WenquRules.weeklyOffsetLabel(0))
        assertEquals(0, WenquRules.weeklyOffsetChoiceIndex(1))
        assertEquals(9, WenquRules.weeklyOffsetChoiceIndex(10))
        // 提前量真的挪动了闹钟时刻，但**发布键不变**——同一周不该因为改设置再报一次
        val five = WenquRules.nextWeeklyAlertPoint(at(2026, 10, 7, 10, 0), 5, zone)
        val ten = WenquRules.nextWeeklyAlertPoint(at(2026, 10, 7, 10, 0), 10, zone)
        assertEquals(five.publishAt, ten.publishAt)
        assertEquals(at(2026, 10, 11, 11, 50), ten.remindAt)
    }

    @Test
    fun `命中高亮_位置与匹配判据同源`() {
        // 命中的字在卡上标出来（搜「单位」时用户才知道为什么这条被搜出来）
        assertEquals(listOf(4 until 8), WenquRules.keywordRanges("某某学院爱心家教志愿服务队", "爱心家教"))
        // 大小写不敏感 + 前后空白先 trim（与 matchesKeyword 同一份归一化）
        assertEquals(listOf(0 until 7), WenquRules.keywordRanges("ZhiJiao 支教服务", "  zhijiao "))
        // 逐段跳过已命中区间：aaaa 里搜 aa 得两段，不是三段重叠
        assertEquals(listOf(0 until 2, 2 until 4), WenquRules.keywordRanges("aaaa", "aa"))
        // 没命中 / 空关键词 → 空
        assertEquals(emptyList<IntRange>(), WenquRules.keywordRanges("支教服务", "爱心家教"))
        assertEquals(emptyList<IntRange>(), WenquRules.keywordRanges("支教服务", "  "))
        assertEquals(emptyList<IntRange>(), WenquRules.keywordRanges("", "支教"))
    }

    @Test
    fun `报名截止排序_没截止的排前面_已截止与没填的排后面`() {
        val now = at(2026, 10, 11, 10, 0)
        // 还没截止 → 直接用截止时刻当键（升序 = 越快截止的越靠前）
        assertEquals(at(2026, 10, 12, 18, 0), WenquRules.closingSortKey("26-10-12 18:00", now, zone))
        // 已截止 / 没填 / 解析不了 → 一律排最后
        assertEquals(Long.MAX_VALUE, WenquRules.closingSortKey("26-10-10 18:00", now, zone))
        assertEquals(Long.MAX_VALUE, WenquRules.closingSortKey("", now, zone))
        assertEquals(Long.MAX_VALUE, WenquRules.closingSortKey("待定", now, zone))
        // 两档的先后：没截止的一定排在已截止前面
        val a = WenquRules.closingSortKey("26-10-20 09:00", now, zone)
        val b = WenquRules.closingSortKey("26-10-01 09:00", now, zone)
        assertTrue(a < b)
    }

    @Test
    fun `记录年份_认不出回 null`() {
        assertEquals(2026, WenquRules.stampYear("26-10-13 14:00", zone))
        assertEquals(2025, WenquRules.stampYear("25-12-05 09:00", zone))
        assertEquals(null, WenquRules.stampYear("", zone))
        assertEquals(null, WenquRules.stampYear("待定", zone))
    }

    @Test
    fun `快照装载计划_新鲜走缓存_过期走增量_太久走整份重抓`() {
        val now = 1_000_000_000L
        val min = 60_000L
        // 刚同步过 + 快照完整 + 整份重抓还在有效期内 → 一个请求都不发
        assertEquals(
            WenquRules.IndexLoad.UseCache,
            WenquRules.indexLoadPlan(
                hasCache = true, cacheComplete = true,
                fullCrawledAt = now - min, syncedAt = now - min, now = now, force = false,
            ),
        )
        // 距上次同步超过 30 分钟 → 只抓第一页（增量）
        assertEquals(
            WenquRules.IndexLoad.SyncNewest,
            WenquRules.indexLoadPlan(
                hasCache = true, cacheComplete = true,
                fullCrawledAt = now - min, syncedAt = now - 31 * min, now = now, force = false,
            ),
        )
        // 下拉刷新（force）跳过闸门，也走增量——**不会**因为下拉就整份重抓
        assertEquals(
            WenquRules.IndexLoad.SyncNewest,
            WenquRules.indexLoadPlan(
                hasCache = true, cacheComplete = true,
                fullCrawledAt = now, syncedAt = now, now = now, force = true,
            ),
        )
        // 没有快照 / 快照不完整 / 上次整份重抓超过 24 小时 → 整份重抓
        assertEquals(
            WenquRules.IndexLoad.Rebuild,
            WenquRules.indexLoadPlan(false, false, 0L, 0L, now, force = false),
        )
        assertEquals(
            WenquRules.IndexLoad.Rebuild,
            WenquRules.indexLoadPlan(true, false, now, now, now, force = false),
        )
        assertEquals(
            WenquRules.IndexLoad.Rebuild,
            WenquRules.indexLoadPlan(
                true, true, now - WenquRules.INDEX_MAX_AGE_MS, now, now, force = false,
            ),
        )
        // 漂移封顶优先于「快」：超过 24 小时，连下拉刷新也整份重抓
        assertEquals(
            WenquRules.IndexLoad.Rebuild,
            WenquRules.indexLoadPlan(
                true, true, now - WenquRules.INDEX_MAX_AGE_MS, now, now, force = true,
            ),
        )
    }

    @Test
    fun `状态校正_活动结束时间已过就当已结束`() {
        val now = at(2026, 10, 11, 12, 0)
        // 结束时间已过 → 已结束（不管缓存里写的是招募中还是进行中）
        assertEquals(3, WenquRules.effectiveStatus(1, "26-10-10 18:00", now, zone))
        assertEquals(3, WenquRules.effectiveStatus(2, "26-10-11 08:00", now, zone))
        // 还没结束 → 保持原值
        assertEquals(1, WenquRules.effectiveStatus(1, "26-10-12 18:00", now, zone))
        assertEquals(2, WenquRules.effectiveStatus(2, "26-10-11 20:00", now, zone))
        // 时间认不出 / 没填 → 不猜，保持原值（宁可不改，也不编一个状态）
        assertEquals(1, WenquRules.effectiveStatus(1, "", now, zone))
        assertEquals(1, WenquRules.effectiveStatus(1, "待定", now, zone))
        assertEquals(3, WenquRules.effectiveStatus(3, "", now, zone))
    }

    @Test
    fun `每周提醒_发布时刻文案`() {
        val now = at(2026, 10, 11, 11, 55)
        assertEquals("今天 12:00", WenquRules.weeklyPublishLabel(at(2026, 10, 11, 12, 0), now, zone))
        assertEquals(
            "10 月 18 日 12:00",
            WenquRules.weeklyPublishLabel(at(2026, 10, 18, 12, 0), now, zone),
        )
    }

    // ---- 报名闸门（2026-10-11，本应用可写后新增）-----------------------------------

    /** 可报名的基线：招募中 + 未满 + 没报过 + 在报名窗口内 + 没被限制。 */
    private fun blockReason(
        status: Int = 1,
        applied: Boolean = false,
        peopleNum: Int = 20,
        balanceNum: Int = 5,
        applyStart: String = "26-10-01 00:00",
        applyEnd: String = "26-10-20 00:00",
        blacklisted: Boolean = false,
        now: Long = at(2026, 10, 11, 12, 0),
    ): String = WenquRules.applyBlockReason(
        status = status,
        applied = applied,
        peopleNum = peopleNum,
        balanceNum = balanceNum,
        applyStartTime = applyStart,
        applyEndTime = applyEnd,
        blacklisted = blacklisted,
        now = now,
        zone = zone,
    )

    @Test
    fun `报名闸门_能报名时回空串`() {
        assertEquals("", blockReason())
        assertTrue(WenquRules.canApply(blockReason()))
        // 招募人数没填（0）时名额不参与判断——平台有整格不填的时候
        assertEquals("", blockReason(peopleNum = 0, balanceNum = 0))
    }

    @Test
    fun `报名闸门_账号被限制与已报名先说`() {
        // 被限制优先于一切（账号级原因最该先说）
        assertEquals("当前账号被限制报名", blockReason(blacklisted = true, status = 3, applied = true))
        // 已报名优先于活动状态：报过的活动点进去不该看到「活动已结束」再让点报名
        assertEquals("已报名", blockReason(applied = true, status = 3))
    }

    @Test
    fun `报名闸门_活动状态各档文案`() {
        assertEquals("活动已取消", blockReason(status = 0))
        assertEquals("活动进行中，报名已截止", blockReason(status = 2))
        assertEquals("活动已结束", blockReason(status = 3))
        // 认不出的状态：不猜，给一句兜底
        assertEquals("当前不可报名", blockReason(status = 9))
    }

    @Test
    fun `报名闸门_时间窗口`() {
        val now = at(2026, 10, 11, 12, 0)
        assertEquals("报名已截止", blockReason(applyEnd = "26-10-10 23:59", now = now))
        assertEquals("报名尚未开始", blockReason(applyStart = "26-10-12 00:00", now = now))
        // 时间认不出 → 不拦（宁可让平台拒，也不拿猜出来的时刻关掉入口）
        assertEquals("", blockReason(applyStart = "", applyEnd = ""))
        assertEquals("", blockReason(applyStart = "待定", applyEnd = "待定"))
    }

    @Test
    fun `报名闸门_名额已满排在最后`() {
        // 既满员又在窗口内 → 名额已满
        assertEquals("名额已满", blockReason(peopleNum = 20, balanceNum = 0))
        // 已满但活动也结束了 → 说「活动已结束」（更说明问题）
        assertEquals("活动已结束", blockReason(status = 3, peopleNum = 20, balanceNum = 0))
        // 招募人数没填（0）时不算满
        assertEquals("", blockReason(peopleNum = 0, balanceNum = 0))
    }

    @Test
    fun `活动视角的取消入口_只给已报名且招募中`() {
        assertTrue(WenquRules.canCancelFromActivity(status = 1, applied = true))
        // 没报名不给
        assertFalse(WenquRules.canCancelFromActivity(status = 1, applied = false))
        // 已结束 / 进行中不给——活动视角拿不到平台的 canCancel，给个必然被拒的按钮只会白点
        assertFalse(WenquRules.canCancelFromActivity(status = 3, applied = true))
        assertFalse(WenquRules.canCancelFromActivity(status = 2, applied = true))
    }
}
