package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 成绩变更检测（DESIGN §4.33）：钉住「什么算出分、什么算改分、什么不打扰」。
 */
class ScoreChangeDetectorTest {

    private fun record(
        term: String = "2025-2026-1",
        courseNo: String = "MATH101",
        name: String = "高等数学",
        score: Double? = null,
        scoreStr: String = "",
        pendingReview: Boolean = false,
    ) = ScoreRecord(
        term = term,
        courseNo = courseNo,
        name = name,
        score = score,
        scoreStr = scoreStr,
        pendingReview = pendingReview,
    )

    @Test
    fun `出分被识别`() {
        val changes = ScoreChangeDetector.detect(
            old = listOf(record()),
            new = listOf(record(score = 84.0, scoreStr = "84")),
        )
        assertEquals(1, changes.size)
        assertEquals(ScoreChangeDetector.Kind.PUBLISHED, changes[0].kind)
        assertEquals("2025-2026-1", changes[0].term)
        assertEquals("高等数学", changes[0].name)
        assertEquals("84", changes[0].scoreStr)
    }

    @Test
    fun `新出现的带分课程也算出分`() {
        // 教务后补录一门课（重修、缓考），旧库根本没这条
        val changes = ScoreChangeDetector.detect(
            old = emptyList(),
            new = listOf(record(score = 91.0, scoreStr = "91")),
        )
        assertEquals(1, changes.size)
        assertEquals(ScoreChangeDetector.Kind.PUBLISHED, changes[0].kind)
    }

    @Test
    fun `待评教解锁算出分`() {
        val changes = ScoreChangeDetector.detect(
            old = listOf(record(pendingReview = true, score = 84.0, scoreStr = "84")),
            new = listOf(record(score = 84.0, scoreStr = "84")),
        )
        assertEquals(1, changes.size)
        assertEquals(ScoreChangeDetector.Kind.PUBLISHED, changes[0].kind)
    }

    @Test
    fun `等级制成绩算出分`() {
        // 等级制 score 为 null，只有 scoreStr
        val changes = ScoreChangeDetector.detect(
            old = listOf(record(name = "体育")),
            new = listOf(record(name = "体育", scoreStr = "优")),
        )
        assertEquals(1, changes.size)
        assertEquals("优", changes[0].scoreStr)
    }

    @Test
    fun `改分被识别`() {
        val changes = ScoreChangeDetector.detect(
            old = listOf(record(score = 84.0, scoreStr = "84")),
            new = listOf(record(score = 91.0, scoreStr = "91")),
        )
        assertEquals(1, changes.size)
        assertEquals(ScoreChangeDetector.Kind.UPDATED, changes[0].kind)
        assertEquals("91", changes[0].scoreStr)
    }

    @Test
    fun `两侧都没分不算变更`() {
        val changes = ScoreChangeDetector.detect(
            old = listOf(record()),
            new = listOf(record()),
        )
        assertEquals(0, changes.size)
    }

    @Test
    fun `新侧待评教不出通知`() {
        val changes = ScoreChangeDetector.detect(
            old = listOf(record()),
            new = listOf(record(score = 84.0, scoreStr = "84", pendingReview = true)),
        )
        assertEquals(0, changes.size)
    }

    @Test
    fun `完全没变化返回空`() {
        val both = listOf(
            record(score = 84.0, scoreStr = "84"),
            record(courseNo = "ENG101", name = "大学英语", scoreStr = "优"),
        )
        assertEquals(0, ScoreChangeDetector.detect(both, both).size)
    }

    @Test
    fun `旧有新无的记录忽略`() {
        val changes = ScoreChangeDetector.detect(
            old = listOf(
                record(score = 84.0, scoreStr = "84"),
                record(courseNo = "GONE1", name = "退掉的课", score = 70.0, scoreStr = "70"),
            ),
            new = listOf(record(score = 84.0, scoreStr = "84")),
        )
        assertEquals(0, changes.size)
    }

    @Test
    fun `结果按学期与课程名排序`() {
        val changes = ScoreChangeDetector.detect(
            old = emptyList(),
            new = listOf(
                record(term = "2026-2027-1", courseNo = "B", name = "大学英语", score = 88.0, scoreStr = "88"),
                record(term = "2025-2026-1", courseNo = "Z", name = "体育", scoreStr = "优"),
                record(term = "2025-2026-1", courseNo = "A", name = "高等数学", score = 84.0, scoreStr = "84"),
            ),
        )
        // 课程名按码点序（体 U+4F53 < 高 U+9AD8），跨学期按学期串升序
        assertEquals(
            listOf("2025-2026-1|体育", "2025-2026-1|高等数学", "2026-2027-1|大学英语"),
            changes.map { "${it.term}|${it.name}" },
        )
    }
}
