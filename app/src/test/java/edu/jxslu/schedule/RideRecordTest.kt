package edu.jxslu.schedule

import edu.jxslu.schedule.domain.RideRecord
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 本机骑行记录（DESIGN §3.9「最近骑行」）：时长文案是纯逻辑，口径钉在这里。
 *
 * 为什么不用骑行卡那一套 `formatRideDuration`（`00:13`）：那是官方小程序的展示口径，
 * 放在"上周骑了几次、每次多久"的回顾里只会被读成 13 秒。
 */
class RideRecordTest {

    @Test
    fun `时长文案按分钟与小时`() {
        assertEquals("不到 1 分钟", RideRecord.durationText(0))
        assertEquals("不到 1 分钟", RideRecord.durationText(59))
        assertEquals("1 分钟", RideRecord.durationText(60))
        assertEquals("13 分钟", RideRecord.durationText(13 * 60))
        assertEquals("59 分钟", RideRecord.durationText(59 * 60))
        assertEquals("1 小时", RideRecord.durationText(60 * 60))
        assertEquals("1 小时 5 分钟", RideRecord.durationText(65 * 60))
        // 秒级零头不进位：120 分 30 秒 = 2 小时整
        assertEquals("2 小时", RideRecord.durationText(2 * 3600 + 30))
    }

    @Test
    fun `负数当零处理`() {
        assertEquals("不到 1 分钟", RideRecord.durationText(-5))
    }
}
