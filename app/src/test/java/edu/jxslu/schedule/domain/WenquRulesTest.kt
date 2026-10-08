package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * 问渠纯逻辑（DESIGN §4.45）：续期时机、验证码冷却、输入校验、展示口径。
 *
 * 常量来源都是实测：token 3 天、短信约 5 分钟一条、平台手机号正则 `^1[3-9]\d{9}$`。
 */
class WenquRulesTest {

    private val hour = 60 * 60 * 1000L

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
        assertTrue(WenquRules.isValidPassword("050059"))
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
}
