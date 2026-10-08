package edu.jxslu.schedule

import edu.jxslu.schedule.ui.ebike.RidingCardTone
import edu.jxslu.schedule.ui.ebike.ridingCardTone
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 骑行卡状态色调（DESIGN §3.9，2026-10-08）：整卡底色/描边与头行状态胶囊共用这一份
 * 判据。悬置（开锁未确认 / 锁车确认中）优先于锁状态；已锁快照是权威结果（时序瞬态里
 * 悬置位还有残留也按「已锁」展示）；锁状态缺失归 [RidingCardTone.Unknown]——**它不是
 * "没有态"**：卡与胶囊照常摆出来（中性底色 + 「锁状态未知」），两态之间不许变形。
 */
class RidingCardToneTest {

    @Test
    fun `开锁未确认优先于一切`() {
        assertEquals(
            RidingCardTone.Pending,
            ridingCardTone(locked = true, unlockPending = true, lockPending = false),
        )
        assertEquals(
            RidingCardTone.Pending,
            ridingCardTone(locked = false, unlockPending = true, lockPending = false),
        )
        assertEquals(
            RidingCardTone.Pending,
            ridingCardTone(locked = null, unlockPending = true, lockPending = false),
        )
    }

    @Test
    fun `锁车确认中在未锁态给警示色`() {
        assertEquals(
            RidingCardTone.Pending,
            ridingCardTone(locked = false, unlockPending = false, lockPending = true),
        )
        // 已锁快照是权威结果：即使悬置位还有残留（时序瞬态），也按已锁展示
        assertEquals(
            RidingCardTone.Locked,
            ridingCardTone(locked = true, unlockPending = false, lockPending = true),
        )
    }

    @Test
    fun `已锁与已解锁各自成态`() {
        assertEquals(
            RidingCardTone.Locked,
            ridingCardTone(locked = true, unlockPending = false, lockPending = false),
        )
        assertEquals(
            RidingCardTone.Unlocked,
            ridingCardTone(locked = false, unlockPending = false, lockPending = false),
        )
    }

    @Test
    fun `锁状态缺失且无悬置时归未知态（不再返回 null）`() {
        // 旧版这里返回 null，调用方据此去掉内边距与胶囊——同一张卡在「已锁」与「锁状态未知」
        // 之间整体平移、尾部文字被切（用户报「两张信息卡片都不一致」）。判据改成全函数后，
        // 卡片容器与内边距恒定，只有颜色与文案变。
        assertEquals(
            RidingCardTone.Unknown,
            ridingCardTone(locked = null, unlockPending = false, lockPending = false),
        )
    }

    /** 十二个组合一个不落：任何输入都落在四态里，不存在"没态"的空档。 */
    @Test
    fun `穷举全部输入组合都有态`() {
        val cases = listOf(
            Case(locked = true, unlockPending = false, lockPending = false, tone = RidingCardTone.Locked),
            Case(locked = false, unlockPending = false, lockPending = false, tone = RidingCardTone.Unlocked),
            Case(locked = null, unlockPending = false, lockPending = false, tone = RidingCardTone.Unknown),
            Case(locked = true, unlockPending = false, lockPending = true, tone = RidingCardTone.Locked),
            Case(locked = false, unlockPending = false, lockPending = true, tone = RidingCardTone.Pending),
            Case(locked = null, unlockPending = false, lockPending = true, tone = RidingCardTone.Pending),
            Case(locked = true, unlockPending = true, lockPending = false, tone = RidingCardTone.Pending),
            Case(locked = false, unlockPending = true, lockPending = false, tone = RidingCardTone.Pending),
            Case(locked = null, unlockPending = true, lockPending = false, tone = RidingCardTone.Pending),
            Case(locked = true, unlockPending = true, lockPending = true, tone = RidingCardTone.Pending),
            Case(locked = false, unlockPending = true, lockPending = true, tone = RidingCardTone.Pending),
            Case(locked = null, unlockPending = true, lockPending = true, tone = RidingCardTone.Pending),
        )
        cases.forEach { case ->
            assertEquals(
                "locked=${case.locked} unlockPending=${case.unlockPending} " +
                    "lockPending=${case.lockPending}",
                case.tone,
                ridingCardTone(case.locked, case.unlockPending, case.lockPending),
            )
        }
    }

    private data class Case(
        val locked: Boolean?,
        val unlockPending: Boolean,
        val lockPending: Boolean,
        val tone: RidingCardTone,
    )
}
