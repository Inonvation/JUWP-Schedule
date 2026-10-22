package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 问渠蹲点 / 预报名（DESIGN §4.45）：从 WenquRulesTest 拆出。 */
class WenquGrabRulesTest {



    @Test
    fun `蹲点窗口_开始早于发布_锚到发布后十分钟`() {
        // 2026-10-16：取消「11:50 后开始就待机到 11:59:50」之后，窗口要自己把发布点兜住
        val publish = at(2026, 10, 11, 12, 0)
        // 11:50 开蹲：窗口不是 12:00 整结束，而是覆盖到 12:10
        assertEquals(
            publish + 10 * 60 * 1000L,
            WenquRules.grabWindowEndAt(at(2026, 10, 11, 11, 50), publish),
        )
        // 12:05 开蹲（发布后）：自开蹲起算
        val late = at(2026, 10, 11, 12, 5)
        assertEquals(late + 10 * 60 * 1000L, WenquRules.grabWindowEndAt(late, publish))
        // 非周日（没有发布锚点）：自开蹲起算
        assertEquals(
            at(2026, 10, 10, 11, 55) + 10 * 60 * 1000L,
            WenquRules.grabWindowEndAt(at(2026, 10, 10, 11, 55), 0L),
        )
    }

    @Test
    fun `蹲点退避_成功三秒_失败逐档退避到十二秒封顶`() {
        assertEquals(3_000L, WenquRules.grabNextDelayMs(0))
        assertEquals(6_000L, WenquRules.grabNextDelayMs(1))
        assertEquals(9_000L, WenquRules.grabNextDelayMs(2))
        assertEquals(12_000L, WenquRules.grabNextDelayMs(3))
        assertEquals(12_000L, WenquRules.grabNextDelayMs(9))
        // 负数（理论上到不了）按成功处理，别把间隔算成负的
        assertEquals(3_000L, WenquRules.grabNextDelayMs(-1))
    }

    @Test
    fun `蹲点发布点_周日12点_非周日为零`() {
        // 周日（2026-10-11 是周日）→ 当天 12:00
        assertEquals(at(2026, 10, 11, 12, 0), WenquRules.grabPublishAt(at(2026, 10, 11, 11, 55), zone))
        // 非周日（2026-10-10 周六）→ 0（没有已知发布点）
        assertEquals(0L, WenquRules.grabPublishAt(at(2026, 10, 10, 11, 55), zone))
        // 盯了活动时用它的实际报名开始时刻：周二 12:00 也要能触发加密窗口
        assertEquals(
            at(2026, 10, 20, 12, 0),
            WenquRules.grabPublishAt(
                at(2026, 10, 19, 23, 0),
                zone,
                pinnedOpenAt = at(2026, 10, 20, 12, 0),
            ),
        )
        // 已经过了的锚点不采用，退回原口径（非周日 = 0）
        assertEquals(
            0L,
            WenquRules.grabPublishAt(
                at(2026, 10, 20, 13, 0),
                zone,
                pinnedOpenAt = at(2026, 10, 20, 12, 0),
            ),
        )
    }

    @Test
    fun `蹲点轮询_窗口内一秒_近发布三秒_远发布十秒_失败在窗口内也快试`() {
        val publish = at(2026, 10, 11, 12, 0)
        // 发布窗口内（前 20 秒 ~ 后 15 秒，含边界）→ 1 秒
        assertEquals(1_000L, WenquRules.grabNextDelayMs(0, now = publish, publishAt = publish))
        assertEquals(1_000L, WenquRules.grabNextDelayMs(0, now = publish - 20_000L, publishAt = publish))
        assertEquals(1_000L, WenquRules.grabNextDelayMs(0, now = publish + 15_000L, publishAt = publish))
        // 失败在窗口内也走 1 秒快重试（2026-10-16：12:00 的拥堵恰好表现为超时）
        assertEquals(1_000L, WenquRules.grabNextDelayMs(1, now = publish, publishAt = publish))
        assertEquals(1_000L, WenquRules.grabNextDelayMs(3, now = publish + 10_000L, publishAt = publish))
        // 离发布还远（5 分钟以外）→ 10 秒慢档；进了 5 分钟 → 基础 3 秒
        assertEquals(10_000L, WenquRules.grabNextDelayMs(0, now = publish - 6 * 60_000L, publishAt = publish))
        assertEquals(3_000L, WenquRules.grabNextDelayMs(0, now = publish - 60_000L, publishAt = publish))
        // 窗口外失败 → 照旧 6/9/12 秒退避（抖动由调用方叠）
        assertEquals(6_000L, WenquRules.grabNextDelayMs(1, now = publish - 60_000L, publishAt = publish))
        assertEquals(12_000L, WenquRules.grabNextDelayMs(3, now = publish + 15_001L, publishAt = publish))
        // 没有发布锚点（非周日 / 老调用）→ 基础 3 秒与退避
        assertEquals(3_000L, WenquRules.grabNextDelayMs(0, now = publish, publishAt = 0L))
        assertEquals(6_000L, WenquRules.grabNextDelayMs(1, now = publish, publishAt = 0L))
    }

    @Test
    fun `蹲点收工_次数触顶或窗口到点`() {
        val start = at(2026, 10, 11, 12, 0)
        assertFalse(WenquRules.grabShouldStop(checks = 0, detectStartedAt = start, now = start + 60_000L))
        assertFalse(
            WenquRules.grabShouldStop(
                checks = 899,
                detectStartedAt = start,
                now = start + 9 * 60 * 1000L,
            ),
        )
        assertTrue(WenquRules.grabShouldStop(checks = 900, detectStartedAt = start, now = start))
        assertTrue(
            WenquRules.grabShouldStop(
                checks = 0,
                detectStartedAt = start,
                now = start + WenquRules.GRAB_WINDOW_MS,
            ),
        )
    }

    @Test
    fun `蹲点命中_名称匹配且现在能报名`() {
        val now = at(2026, 10, 11, 12, 0)
        val items = listOf(
            activity(id = 3, title = "支教服务"),
            activity(id = 2, title = "爱心家教志愿服务"),
            activity(id = 1, title = "爱心家教（补录）", status = 3),
        )
        val hit = WenquRules.grabFirstMatch(items, "爱心家教", blacklisted = false, now = now, zone = zone)
        assertEquals(2L, hit?.id)
    }

    @Test
    fun `蹲点命中_盯指定活动_不看名字_名额空出才弹`() {
        val now = at(2026, 10, 11, 12, 0)
        // 名字完全不匹配、关键词也空着，但盯了 id=7：已满 → 不弹
        val full = listOf(activity(id = 7, title = "某活动", peopleNum = 220, balanceNum = 0))
        assertEquals(
            null,
            WenquRules.grabFirstMatch(full, "", blacklisted = false, now = now, zone = zone, pinnedId = 7L),
        )
        // 有人退了名额（balance 转正）→ 弹，且不看名字
        val freed = listOf(activity(id = 7, title = "某活动", peopleNum = 220, balanceNum = 1))
        assertEquals(
            7L,
            WenquRules.grabFirstMatch(freed, "", blacklisted = false, now = now, zone = zone, pinnedId = 7L)?.id,
        )
    }

    @Test
    fun `蹲点命中_盯的那条优先于名字匹配`() {
        val now = at(2026, 10, 11, 12, 0)
        val items = listOf(
            // 名字匹配、排在更前
            activity(id = 9, title = "爱心家教"),
            // 盯的那条
            activity(id = 7, title = "别的活动"),
        )
        assertEquals(
            7L,
            WenquRules.grabFirstMatch(items, "爱心家教", blacklisted = false, now = now, zone = zone, pinnedId = 7L)?.id,
        )
        // 没盯时还是走名字匹配
        assertEquals(
            9L,
            WenquRules.grabFirstMatch(items, "爱心家教", blacklisted = false, now = now, zone = zone)?.id,
        )
    }

    @Test
    fun `蹲点命中_不能报名的跳过_继续找下一条`() {
        val now = at(2026, 10, 11, 12, 0)
        val items = listOf(
            // 已报名 → 跳
            activity(id = 5, title = "爱心家教 A", applied = true),
            // 已满 → 跳
            activity(id = 4, title = "爱心家教 B", peopleNum = 20, balanceNum = 0),
            // 报名还没开始 → 跳
            activity(id = 3, title = "爱心家教 C", applyStart = "26-10-12 00:00"),
            // 可报名 → 命中
            activity(id = 2, title = "爱心家教 D"),
        )
        val hit = WenquRules.grabFirstMatch(items, "爱心家教", blacklisted = false, now = now, zone = zone)
        assertEquals(2L, hit?.id)
    }

    @Test
    fun `蹲点命中_空关键词不猜`() {
        val now = at(2026, 10, 11, 12, 0)
        val items = listOf(activity(id = 1, title = "爱心家教"))
        assertEquals(
            null,
            WenquRules.grabFirstMatch(items, "   ", blacklisted = false, now = now, zone = zone),
        )
    }

    @Test
    fun `蹲点命中_账号被限制时不弹`() {
        val now = at(2026, 10, 11, 12, 0)
        val items = listOf(activity(id = 1, title = "爱心家教"))
        assertEquals(
            null,
            WenquRules.grabFirstMatch(items, "爱心家教", blacklisted = true, now = now, zone = zone),
        )
    }

    @Test
    fun `蹲点退避抖动_正负两成且不为负`() {
        assertEquals(6_000L, WenquRules.jitteredDelayMs(6_000L, 0.5))
        assertEquals(4_800L, WenquRules.jitteredDelayMs(6_000L, 0.0))
        assertEquals(7_200L, WenquRules.jitteredDelayMs(6_000L, 1.0))
        // 越界入参夹回 [0,1]
        assertEquals(4_800L, WenquRules.jitteredDelayMs(6_000L, -1.0))
        assertEquals(7_200L, WenquRules.jitteredDelayMs(6_000L, 2.0))
    }

    // ---- 预报名（2026-10-16；用户拍板）--------------------------------------------

    @Test
    fun `预报名_闹钟与试发时刻按服务器钟对齐`() {
        val open = at(2026, 10, 20, 12, 0)
        assertEquals(open - 60_000L, WenquRules.preregAlarmAt(open))
        // 手机比服务器慢 2 秒（offset = +2000）→ 手机钟要更早动手
        assertEquals(open - 2_000L - 15_000L, WenquRules.preregTryStartAt(open, 2_000L))
        // 没测到偏移（0）→ 按手机钟
        assertEquals(open - 15_000L, WenquRules.preregTryStartAt(open, 0L))
    }

    @Test
    fun `预报名_收工判据_次数_总时长_开始后超窗`() {
        val open = at(2026, 10, 20, 12, 0)
        val started = open - 60_000L
        assertFalse(WenquRules.preregShouldStop(0, started, open + 1_000L, open, 0L))
        // 次数触顶
        assertTrue(
            WenquRules.preregShouldStop(
                WenquRules.PREREG_MAX_ATTEMPTS,
                started,
                started + 1_000L,
                open,
                0L,
            ),
        )
        // 总时长用尽
        assertTrue(
            WenquRules.preregShouldStop(
                0,
                started,
                started + WenquRules.PREREG_RUN_MS,
                open,
                0L,
            ),
        )
        // 开始后超过 90 秒（迟到启动 / 时间被改早）
        assertTrue(
            WenquRules.preregShouldStop(
                0,
                started,
                open + WenquRules.PREREG_END_AFTER_MS,
                open,
                0L,
            ),
        )
        // 服务器钟口径：手机慢 2 秒时，手机钟走到 open+92s 才对应服务器 open+90s。
        // 这条特意晚 10 秒才启动，别让「总时长」那道闸先触发（本用例只验超窗那一档）。
        val lateStart = open - 10_000L
        assertTrue(WenquRules.preregShouldStop(0, lateStart, open + 92_000L, open, 2_000L))
        assertFalse(WenquRules.preregShouldStop(0, lateStart, open + 91_000L, open, 2_000L))
    }

    @Test
    fun `预报名_开始时刻文案`() {
        val now = at(2026, 10, 20, 9, 0)
        assertEquals("今天 12:00", WenquRules.preregOpenLabel(at(2026, 10, 20, 12, 0), now, zone))
        assertEquals("明天 12:00", WenquRules.preregOpenLabel(at(2026, 10, 21, 12, 0), now, zone))
        assertEquals(
            "10 月 25 日 12:00",
            WenquRules.preregOpenLabel(at(2026, 10, 25, 12, 0), now, zone),
        )
    }

    @Test
    fun `预报名_平台拒绝话术分类`() {
        assertEquals(
            WenquRules.PreregRejectKind.NotStarted,
            WenquRules.classifyPreregReject("报名尚未开始"),
        )
        assertEquals(
            WenquRules.PreregRejectKind.AlreadyApplied,
            WenquRules.classifyPreregReject("您已报名该活动"),
        )
        // 2026-10-21 扩面：重复报名的实测话术（用户手动报名时平台回的原文）
        assertEquals(
            WenquRules.PreregRejectKind.AlreadyApplied,
            WenquRules.classifyPreregReject("已报名该活动，无法重复报名"),
        )
        assertEquals(
            WenquRules.PreregRejectKind.AlreadyApplied,
            WenquRules.classifyPreregReject("请勿重复报名"),
        )
        assertEquals(
            WenquRules.PreregRejectKind.NotStarted,
            WenquRules.classifyPreregReject("报名未到时间，未到报名时刻"),
        )
        assertEquals(WenquRules.PreregRejectKind.Full, WenquRules.classifyPreregReject("名额已满"))
        assertEquals(
            WenquRules.PreregRejectKind.Blocked,
            WenquRules.classifyPreregReject("当前账号被限制报名"),
        )
        assertEquals(
            WenquRules.PreregRejectKind.Removed,
            WenquRules.classifyPreregReject("服务项目不存在或已下架"),
        )
        // 认不出的（含空串）一律 Unknown：继续试、靠次数封顶，不硬认成终结
        assertEquals(
            WenquRules.PreregRejectKind.Unknown,
            WenquRules.classifyPreregReject("未知异常，请联系管理员"),
        )
        assertEquals(WenquRules.PreregRejectKind.Unknown, WenquRules.classifyPreregReject(""))
        assertEquals(WenquRules.PreregRejectKind.Unknown, WenquRules.classifyPreregReject(null))
        // 终结 / 已报上 的判据
        assertTrue(WenquRules.preregRejectIsTerminal(WenquRules.PreregRejectKind.Full))
        assertTrue(WenquRules.preregRejectIsTerminal(WenquRules.PreregRejectKind.Removed))
        assertFalse(WenquRules.preregRejectIsTerminal(WenquRules.PreregRejectKind.NotStarted))
        assertFalse(WenquRules.preregRejectIsTerminal(WenquRules.PreregRejectKind.Unknown))
        assertTrue(WenquRules.preregRejectIsApplied(WenquRules.PreregRejectKind.AlreadyApplied))
        assertFalse(WenquRules.preregRejectIsApplied(WenquRules.PreregRejectKind.Full))
        // 「尚未开始」确定没落库，不补只读核对；认不出的仍要核对
        assertFalse(WenquRules.preregNeedsReadBack(WenquRules.PreregRejectKind.NotStarted))
        assertTrue(WenquRules.preregNeedsReadBack(WenquRules.PreregRejectKind.Unknown))
    }

    @Test
    fun `报名尚未开始_是预报名入口的判据常量`() {
        assertEquals("报名尚未开始", WenquRules.APPLY_NOT_STARTED)
        assertEquals(WenquRules.APPLY_NOT_STARTED, blockReason(applyStart = "26-10-12 00:00"))
    }

    @Test
    fun `服务器时钟_Date 头解析与偏移`() {
        // GMT 03:20:05 对应 GMT+8 的 11:20:05（2026-10-16 是周五）
        assertEquals(
            at(2026, 10, 16, 11, 20) + 5_000L,
            WenquRules.parseHttpDate("Fri, 16 Oct 2026 03:20:05 GMT"),
        )
        assertEquals(null, WenquRules.parseHttpDate("not-a-date"))
        assertEquals(null, WenquRules.parseHttpDate(""))
        assertEquals(null, WenquRules.parseHttpDate(null))
        // 偏移 = 服务器 −（发 + 收）/ 2：单程 500ms 的中点被抵消，剩 123ms 偏差
        val sent = 1_000_000L
        assertEquals(123L, WenquRules.clockOffsetMs(sent + 623L, sent, sent + 1_000L))
    }

    @Test
    fun `盯这条_只在还会再放名额的状态出现`() {
        // 等开放 / 等空位 → 值得盯
        assertTrue(WenquRules.canPin(WenquRules.APPLY_NOT_STARTED))
        assertTrue(WenquRules.canPin(WenquRules.APPLY_FULL))
        // 与闸门函数实测同一份判据（满员 / 还没开始 → 给；其余 → 不给）
        assertTrue(WenquRules.canPin(blockReason(peopleNum = 20, balanceNum = 0)))
        assertTrue(WenquRules.canPin(blockReason(applyStart = "26-10-12 00:00")))
        assertFalse(WenquRules.canPin(blockReason()))
        assertFalse(WenquRules.canPin(blockReason(applied = true)))
        assertFalse(WenquRules.canPin(blockReason(blacklisted = true)))
        assertFalse(WenquRules.canPin(blockReason(status = 3)))
        assertFalse(WenquRules.canPin(blockReason(status = 2)))
        assertFalse(WenquRules.canPin(blockReason(status = 0)))
        assertFalse(WenquRules.canPin(blockReason(applyEnd = "26-10-10 23:59")))
        // 「名额已满」的常量与闸门输出同源
        assertEquals("名额已满", WenquRules.APPLY_FULL)
    }

    @Test
    fun `盯的活动永远等不到就判为无效`() {
        val now = at(2026, 10, 11, 12, 0)
        // 已报名 / 已取消 / 进行中 / 已结束 → 无效
        assertTrue(WenquRules.pinFutile(1, applied = true, applyEndTime = "", now = now))
        assertTrue(WenquRules.pinFutile(0, applied = false, applyEndTime = "", now = now))
        assertTrue(WenquRules.pinFutile(2, applied = false, applyEndTime = "", now = now))
        assertTrue(WenquRules.pinFutile(3, applied = false, applyEndTime = "", now = now))
        // 招募中但报名截止已过 → 无效
        assertTrue(WenquRules.pinFutile(1, applied = false, applyEndTime = "26-10-10 12:00", now = now))
        // 招募中、截止未到 / 没填截止 / 认不出 → 不判定，继续等
        assertFalse(WenquRules.pinFutile(1, applied = false, applyEndTime = "26-10-12 12:00", now = now))
        assertFalse(WenquRules.pinFutile(1, applied = false, applyEndTime = "", now = now))
        assertFalse(WenquRules.pinFutile(1, applied = false, applyEndTime = "待定", now = now))
        // 认不出的状态：不猜，保守留着
        assertFalse(WenquRules.pinFutile(9, applied = false, applyEndTime = "", now = now))
    }
}
