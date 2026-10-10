package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「使用前须知」周期声明的弹窗判定与加粗标记解析（`domain/LegalNotice`）。
 */
class LegalNoticeTest {

    private val now = 1_790_700_000_000L

    // ── shouldShow ──

    @Test
    fun neverShownAlwaysShows() {
        assertTrue(LegalNotice.shouldShow(null, now))
        assertTrue(LegalNotice.shouldShow(0L, now))
        // 负数（脏数据）也按"从没弹过"处理
        assertTrue(LegalNotice.shouldShow(-1L, now))
    }

    @Test
    fun beforeIntervalDoesNotShow() {
        assertFalse(LegalNotice.shouldShow(now - 2 * 24 * 60 * 60 * 1000L, now))
        // 差 1 毫秒没到 3 天 = 不弹
        assertFalse(LegalNotice.shouldShow(now - LegalNotice.INTERVAL_MS + 1, now))
    }

    @Test
    fun atBoundaryShows() {
        // 正好满 3 天即弹（>=）
        assertTrue(LegalNotice.shouldShow(now - LegalNotice.INTERVAL_MS, now))
    }

    @Test
    fun afterIntervalShows() {
        assertTrue(LegalNotice.shouldShow(now - 4 * 24 * 60 * 60 * 1000L, now))
    }

    @Test
    fun intervalIsThreeDays() {
        assertEquals(3 * 24L * 60 * 60 * 1000, LegalNotice.INTERVAL_MS)
    }

    // ── nextShownAt ──

    @Test
    fun nextShownAtFromNeverShownIsZero() {
        assertEquals(0L, LegalNotice.nextShownAt(0L))
        assertEquals(0L, LegalNotice.nextShownAt(-5L))
    }

    @Test
    fun nextShownAtAddsInterval() {
        assertEquals(now + LegalNotice.INTERVAL_MS, LegalNotice.nextShownAt(now))
    }

    // ── parseBold ──

    @Test
    fun parseBoldWithoutMarkersIsAllNormal() {
        assertEquals(
            listOf(BoldSegment("abc", bold = false)),
            LegalNotice.parseBold("abc"),
        )
    }

    @Test
    fun parseBoldSingleSegment() {
        assertEquals(
            listOf(BoldSegment("a", false), BoldSegment("b", true), BoldSegment("c", false)),
            LegalNotice.parseBold("a**b**c"),
        )
    }

    @Test
    fun parseBoldLeadingMarker() {
        assertEquals(
            listOf(BoldSegment("a", true), BoldSegment("b", false)),
            LegalNotice.parseBold("**a**b"),
        )
    }

    @Test
    fun parseBoldTrailingMarkerOnly() {
        assertEquals(
            listOf(BoldSegment("a", true)),
            LegalNotice.parseBold("**a**"),
        )
    }

    @Test
    fun parseBoldUnclosedMarkerBoldsTheRest() {
        // 未闭合的尾部标记照加粗处理（内容自维护、始终成对；这里只保证不吞字）
        assertEquals(
            listOf(BoldSegment("a", false), BoldSegment("b", true)),
            LegalNotice.parseBold("a**b"),
        )
    }

    @Test
    fun parseBoldEmptyLine() {
        assertTrue(LegalNotice.parseBold("").isEmpty())
    }

    @Test
    fun parseBoldMultipleSegments() {
        assertEquals(
            listOf(
                BoldSegment("a", false),
                BoldSegment("b", true),
                BoldSegment("c", false),
                BoldSegment("d", true),
            ),
            LegalNotice.parseBold("a**b**c**d"),
        )
    }

    /** 正文解析后不能丢字：去掉标记后应与原文一致。 */
    @Test
    fun itemsRoundTripWithoutLosingText() {
        LegalNotice.ITEMS.forEach { item ->
            val plain = LegalNotice.parseBold(item).joinToString("") { it.text }
            assertEquals(item.replace("**", ""), plain)
        }
    }
}
