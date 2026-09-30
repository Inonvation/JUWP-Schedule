package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 校历镜像规则（DESIGN §4.34）：解析校验 / 文件名白名单 / URL 拼装 / 刷新闸门。
 */
class CampusCalendarRulesTest {

    // ── parseIndex:合法输入 ──

    @Test
    fun `parse valid index`() {
        val index = CampusCalendarRules.parseIndex("""{"year":"2026-2027","file":"calendar_2026-2027.png"}""")
        assertEquals(CampusCalendarIndex("2026-2027", "calendar_2026-2027.png"), index)
    }

    @Test
    fun `parse ignores unknown fields`() {
        val index = CampusCalendarRules.parseIndex(
            """{"year":"2026-2027","file":"calendar_2026-2027.png","note":"x"}""",
        )
        assertEquals(CampusCalendarIndex("2026-2027", "calendar_2026-2027.png"), index)
    }

    // ── parseIndex:脏输入拒收 ──

    @Test
    fun `reject broken json`() {
        assertNull(CampusCalendarRules.parseIndex("not json"))
        assertNull(CampusCalendarRules.parseIndex(""))
        assertNull(CampusCalendarRules.parseIndex("""{"year":"2026-2027"}""")) // 缺 file
    }

    @Test
    fun `reject bad year format`() {
        assertNull(CampusCalendarRules.parseIndex("""{"year":"2026/2027","file":"a.png"}"""))
        assertNull(CampusCalendarRules.parseIndex("""{"year":"2026-2027-1","file":"a.png"}"""))
        assertNull(CampusCalendarRules.parseIndex("""{"year":"26-27","file":"a.png"}"""))
    }

    @Test
    fun `reject year where end is not start plus one`() {
        assertNull(CampusCalendarRules.parseIndex("""{"year":"2026-2026","file":"a.png"}"""))
        assertNull(CampusCalendarRules.parseIndex("""{"year":"2026-2028","file":"a.png"}"""))
    }

    @Test
    fun `reject file outside whitelist`() {
        // 路径穿越（`..` 与 `/` 都不在字符类里）
        assertNull(CampusCalendarRules.parseIndex("""{"year":"2026-2027","file":"../secret.png"}"""))
        assertNull(CampusCalendarRules.parseIndex("""{"year":"2026-2027","file":"a/b.png"}"""))
        // 大写与空格（白名单只收小写字母数字下划线连字符）
        assertNull(CampusCalendarRules.parseIndex("""{"year":"2026-2027","file":"Calendar.png"}"""))
        assertNull(CampusCalendarRules.parseIndex("""{"year":"2026-2027","file":"a b.png"}"""))
        // 非图片后缀
        assertNull(CampusCalendarRules.parseIndex("""{"year":"2026-2027","file":"a.json"}"""))
        assertNull(CampusCalendarRules.parseIndex("""{"year":"2026-2027","file":"a.html"}"""))
    }

    @Test
    fun `reject oversized index body`() {
        val junk = "x".repeat(CampusCalendarRules.MIRROR_BASE.length + 8 * 1024)
        assertNull(CampusCalendarRules.parseIndex("""{"year":"2026-2027","file":"a.png","junk":"$junk"}"""))
    }

    // ── URL 拼装 ──

    @Test
    fun `urls assemble from whitelist-passed values`() {
        val index = CampusCalendarIndex("2026-2027", "calendar_2026-2027.png")
        assertEquals(
            "https://raw.githubusercontent.com/Inonvation/JUWP-Schedule/master/docs-public/calendar/index.json",
            CampusCalendarRules.indexUrl(),
        )
        assertEquals(
            "https://raw.githubusercontent.com/Inonvation/JUWP-Schedule/master/docs-public/calendar/calendar_2026-2027.png",
            CampusCalendarRules.imageUrl(index),
        )
        assertEquals("calendar_2026-2027.png", CampusCalendarRules.localFileName(index))
    }

    @Test
    fun `local file name follows image extension`() {
        val index = CampusCalendarIndex("2027-2028", "calendar_2027-2028.jpg")
        assertEquals("calendar_2027-2028.jpg", CampusCalendarRules.localFileName(index))
    }

    // ── 刷新闸门 ──

    @Test
    fun `gate refreshes when never succeeded`() {
        assertTrue(CampusCalendarRules.shouldRefresh(null, nowMillis = 1000L))
        assertTrue(CampusCalendarRules.shouldRefresh(0L, nowMillis = 1000L))
    }

    @Test
    fun `gate skips inside interval and refreshes after`() {
        val now = 1_000_000_000L
        val week = CampusCalendarRules.REFRESH_INTERVAL_HOURS * 60L * 60L * 1000L
        assertFalse(CampusCalendarRules.shouldRefresh(now - week + 60_000L, now))
        assertTrue(CampusCalendarRules.shouldRefresh(now - week - 60_000L, now))
    }

    @Test
    fun `gate treats future last success as fresh`() {
        val now = 1_000_000_000L
        // 时钟被回拨：last 在未来 → 不抓（与 AutoSyncRules 同口径）
        assertFalse(CampusCalendarRules.shouldRefresh(now + 1, now))
    }
}
