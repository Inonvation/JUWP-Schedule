package edu.jxslu.schedule

import edu.jxslu.schedule.data.ykt.YktSyncGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 一卡通流水同步的频率闸门（DESIGN §4.24「请求节流」）。
 *
 * 闸门是进程级单例，所以每条用例先 [YktSyncGate.reset]：不然用例之间会互相干扰，
 * 失败信息也会指向错误的用例。
 */
class YktSyncGateTest {

    @Test
    fun `没同步过时进页允许同步`() {
        YktSyncGate.reset()
        assertTrue(YktSyncGate.shouldSync(force = false))
    }

    @Test
    fun `同步过之后进页被闸住，强制同步不受影响`() {
        YktSyncGate.reset()
        YktSyncGate.markSynced()
        assertFalse("刚同步过就不该再拉", YktSyncGate.shouldSync(force = false))
        assertTrue("用户主动刷新必须穿透", YktSyncGate.shouldSync(force = true))
    }

    @Test
    fun `闸门间隔在十分钟量级`() {
        // 钉住量级：写成 10 秒等于没闸，写成 10 小时等于进页永远看不到新流水
        assertTrue(YktSyncGate.MIN_INTERVAL_MS in 60_000L..(60L * 60 * 1000))
    }
}
