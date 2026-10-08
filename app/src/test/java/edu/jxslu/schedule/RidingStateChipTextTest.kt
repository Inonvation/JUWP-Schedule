package edu.jxslu.schedule

import edu.jxslu.schedule.ui.ebike.ridingStateChipText
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 骑行卡状态胶囊的文案（DESIGN §3.9，2026-10-08 二轮）。
 *
 * 与 `ridingCardTone` 同一条判据链：现查中优先 → 开锁悬置 → 锁车确认中（已锁快照除外）→
 * 已锁 / 已解锁 → 锁状态缺失。**四态各有文案、没有空档**——旧版锁状态缺失时干脆不摆胶囊，
 * 同一张卡在两种状态下少一个元素，用户看到的是"卡片不一致"。
 */
class RidingStateChipTextTest {

    @Test
    fun `现查中优先于一切`() {
        listOf(true, false, null).forEach { locked ->
            assertEquals(
                "查询中…",
                ridingStateChipText(
                    querying = true,
                    locked = locked,
                    unlockPending = true,
                    lockPending = true,
                ),
            )
        }
    }

    @Test
    fun `开锁未确认与锁车确认中分别成文案`() {
        assertEquals(
            "开锁未确认",
            ridingStateChipText(
                querying = false,
                locked = false,
                unlockPending = true,
                lockPending = false,
            ),
        )
        assertEquals(
            "锁车确认中…",
            ridingStateChipText(
                querying = false,
                locked = false,
                unlockPending = false,
                lockPending = true,
            ),
        )
        assertEquals(
            "锁车确认中…",
            ridingStateChipText(
                querying = false,
                locked = null,
                unlockPending = false,
                lockPending = true,
            ),
        )
    }

    @Test
    fun `已锁快照压过锁车悬置的残留`() {
        assertEquals(
            "已锁",
            ridingStateChipText(
                querying = false,
                locked = true,
                unlockPending = false,
                lockPending = true,
            ),
        )
    }

    @Test
    fun `已锁与已解锁各自的文案`() {
        assertEquals(
            "已锁",
            ridingStateChipText(
                querying = false,
                locked = true,
                unlockPending = false,
                lockPending = false,
            ),
        )
        assertEquals(
            "已解锁",
            ridingStateChipText(
                querying = false,
                locked = false,
                unlockPending = false,
                lockPending = false,
            ),
        )
    }

    @Test
    fun `锁状态缺失时给出可点的未知文案（不再空着）`() {
        assertEquals(
            "锁状态未知",
            ridingStateChipText(
                querying = false,
                locked = null,
                unlockPending = false,
                lockPending = false,
            ),
        )
    }
}
