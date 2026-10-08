package edu.jxslu.schedule

import edu.jxslu.schedule.ui.ebike.RidingCardTone
import edu.jxslu.schedule.ui.ebike.ridingCardTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 骑行卡状态色调（DESIGN §3.9，2026-10-08）：整卡底色/描边与头行状态胶囊共用这一份
 * 判据。悬置（开锁未确认 / 锁车确认中）优先于锁状态；已锁快照是权威结果（时序瞬态里
 * 悬置位还有残留也按「已锁」展示）；判不出（无订单 / 锁状态缺失且无悬置）不给态
 * （不套色、不摆胶囊——小程序计时态与老数据保持原样）。
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
    fun `锁状态未知且无悬置时不给态`() {
        assertNull(ridingCardTone(locked = null, unlockPending = false, lockPending = false))
    }

    @Test
    fun `组合穷举——开锁悬置无条件优先，其余按序短路`() {
        assertEquals(
            RidingCardTone.Pending,
            ridingCardTone(locked = null, unlockPending = false, lockPending = true),
        )
        assertEquals(
            RidingCardTone.Pending,
            ridingCardTone(locked = true, unlockPending = true, lockPending = true),
        )
        assertEquals(
            RidingCardTone.Pending,
            ridingCardTone(locked = false, unlockPending = true, lockPending = true),
        )
        assertEquals(
            RidingCardTone.Pending,
            ridingCardTone(locked = null, unlockPending = true, lockPending = true),
        )
    }
}
