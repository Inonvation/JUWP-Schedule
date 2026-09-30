package edu.jxslu.schedule.data.session

import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动导入闸门（DESIGN §4.29）：学业完成情况与成绩共用。
 *
 * 钉的是「什么时候不去打教务」——那才是这个类存在的意义。
 */
class AutoSyncRulesTest {

    private val today = LocalDate.of(2026, 9, 27)

    @Test
    fun `库里没数据立即抓`() {
        assertTrue(AutoSyncRules.shouldAttempt(hasData = false, lastSuccessDate = null, today = today))
        assertTrue(
            AutoSyncRules.shouldAttempt(
                hasData = false,
                lastSuccessDate = today.toString(),
                today = today,
            ),
        )
    }

    @Test
    fun `有数据但没有日期时抓一次补上`() {
        // 老版本升上来的库有数据、没有这个键
        assertTrue(AutoSyncRules.shouldAttempt(hasData = true, lastSuccessDate = null, today = today))
        assertTrue(AutoSyncRules.shouldAttempt(hasData = true, lastSuccessDate = "", today = today))
    }

    @Test
    fun `间隔内不抓`() {
        assertFalse(
            AutoSyncRules.shouldAttempt(
                hasData = true,
                lastSuccessDate = today.minusDays(6).toString(),
                today = today,
            ),
        )
        assertFalse(
            AutoSyncRules.shouldAttempt(
                hasData = true,
                lastSuccessDate = today.toString(),
                today = today,
            ),
        )
    }

    @Test
    fun `刚好到间隔就抓`() {
        assertTrue(
            AutoSyncRules.shouldAttempt(
                hasData = true,
                lastSuccessDate = today.minusDays(AutoSyncRules.REFRESH_INTERVAL_DAYS).toString(),
                today = today,
            ),
        )
        assertTrue(
            AutoSyncRules.shouldAttempt(
                hasData = true,
                lastSuccessDate = today.minusDays(30).toString(),
                today = today,
            ),
        )
    }

    @Test
    fun `脏日期当作没记录`() {
        assertTrue(AutoSyncRules.shouldAttempt(hasData = true, lastSuccessDate = "昨天", today = today))
        assertTrue(AutoSyncRules.shouldAttempt(hasData = true, lastSuccessDate = "2026/09/27", today = today))
    }

    @Test
    fun `日期在未来的时钟回拨不抓`() {
        // 设备时间被调回去时不要一直重抓，等它自然走到区间内
        assertFalse(
            AutoSyncRules.shouldAttempt(
                hasData = true,
                lastSuccessDate = today.plusDays(3).toString(),
                today = today,
            ),
        )
    }

    // ---- 毫秒级间隔闸门（成绩/考试变动提醒，DESIGN §4.33）----

    private val now = 1_800_000_000_000L

    @Test
    fun `毫秒闸门_没成功过立即抓`() {
        assertTrue(AutoSyncRules.shouldAttemptAt(null, intervalHours = 6, nowMillis = now))
        assertTrue(AutoSyncRules.shouldAttemptAt(0L, intervalHours = 6, nowMillis = now))
        assertTrue(AutoSyncRules.shouldAttemptAt(-5L, intervalHours = 6, nowMillis = now))
    }

    @Test
    fun `毫秒闸门_间隔内不抓`() {
        val last = now - 5 * 60 * 60 * 1000L
        assertFalse(AutoSyncRules.shouldAttemptAt(last, intervalHours = 6, nowMillis = now))
    }

    @Test
    fun `毫秒闸门_刚好到间隔就抓`() {
        val last = now - 6 * 60 * 60 * 1000L
        assertTrue(AutoSyncRules.shouldAttemptAt(last, intervalHours = 6, nowMillis = now))
        assertTrue(
            AutoSyncRules.shouldAttemptAt(
                now - 25 * 60 * 60 * 1000L,
                intervalHours = 6,
                nowMillis = now,
            ),
        )
    }

    @Test
    fun `毫秒闸门_时钟回拨不抓`() {
        val last = now + 60 * 60 * 1000L
        assertFalse(AutoSyncRules.shouldAttemptAt(last, intervalHours = 6, nowMillis = now))
    }

    @Test
    fun `毫秒闸门_脏间隔按1小时兜底`() {
        // 间隔是 DataStore 里的脏值（0/负数）时按 1 小时兜底，不能退化成「每次都抓」
        assertFalse(
            AutoSyncRules.shouldAttemptAt(now - 30 * 60 * 1000L, intervalHours = 0, nowMillis = now),
        )
        assertTrue(
            AutoSyncRules.shouldAttemptAt(now - 2 * 60 * 60 * 1000L, intervalHours = -3, nowMillis = now),
        )
    }
}
