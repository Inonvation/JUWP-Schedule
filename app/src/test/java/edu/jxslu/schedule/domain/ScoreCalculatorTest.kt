package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 学期成绩汇总口径（DESIGN §4.15）：锁定课程不进统计，加权用学分。 */
class ScoreCalculatorTest {

    private val records = listOf(
        ScoreRecord(term = "2025-2026-2", name = "高数", credit = 4.0, score = 90.0, scoreStr = "90", gradePoint = 4.0),
        ScoreRecord(term = "2025-2026-2", name = "英语", credit = 2.0, score = 80.0, scoreStr = "80", gradePoint = 3.0),
        // 等级制课程：无数值分，不进平均分；有绩点仍进 GPA
        ScoreRecord(term = "2025-2026-2", name = "劳动", credit = 1.0, score = null, scoreStr = "优", gradePoint = 4.0),
        // 评教锁定：两者都不进
        ScoreRecord(term = "2025-2026-2", name = "体育", credit = 1.0, score = 70.0, scoreStr = "70", gradePoint = 2.0, pendingReview = true),
    )

    @Test
    fun weightedAverageExcludesLockedAndGradeOnly() {
        val s = ScoreCalculator.summarize("2025-2026-2", records)!!
        // 加权平均分 = (90×4 + 80×2) / 6 = 86.67（劳动无数值分、体育锁定，都不进）
        assertEquals(86.67, s.weightedAverage!!, 1e-9)
        // GPA = (4×4 + 3×2 + 4×1) / 7 = 26/7 = 3.71
        assertEquals(3.71, s.gpa!!, 1e-9)
        assertEquals(3, s.courseCount)      // 4 门 − 1 门锁定
        assertEquals(1, s.lockedCount)
    }

    @Test
    fun emptyTermReturnsNull() {
        assertNull(ScoreCalculator.summarize("2025-2026-2", emptyList()))
    }

    @Test
    fun allLockedReturnsNullAverages() {
        val locked = listOf(
            ScoreRecord(term = "t", name = "a", credit = 1.0, score = 60.0, pendingReview = true),
        )
        val s = ScoreCalculator.summarize("t", locked)!!
        assertNull(s.weightedAverage)
        assertNull(s.gpa)
        assertEquals(0, s.courseCount)
        assertEquals(1, s.lockedCount)
    }

    @Test
    fun zeroCreditRecordsDoNotDivideByZero() {
        val s = ScoreCalculator.summarize(
            "t",
            listOf(ScoreRecord(term = "t", name = "a", credit = 0.0, score = 60.0, gradePoint = 1.0)),
        )!!
        assertNull(s.weightedAverage)
        assertNull(s.gpa)
    }
}
