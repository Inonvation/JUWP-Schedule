package edu.jxslu.schedule.domain

/**
 * 一条课程成绩（DESIGN §4.15）。成绩不是课程（没有排课语义），独立存储、按学期替换。
 *
 * 成绩双口径：[score] 是教务给的数值分（等级制成绩为 null），[scoreStr] 是展示口径
 * （"84" / "优" / "及格"）。统计一律以 [score] 非空为准，展示一律用 [scoreStr]。
 */
data class ScoreRecord(
    val id: Long = 0,
    /** 学年学期，如 2025-2026-2。 */
    val term: String,
    val courseNo: String = "",
    val name: String,
    val unit: String = "",
    val credit: Double = 0.0,
    val hours: Double = 0.0,
    /** 考试方式：考试 / 考查。 */
    val examForm: String = "",
    /** 必修 / 选修。 */
    val courseAttr: String = "",
    /** 课程性质（通识必修课、学科基础课…）。 */
    val category: String = "",
    val score: Double? = null,
    val scoreStr: String = "",
    val gradePoint: Double? = null,
    /** 考试性质：正常考试 / 补考…。 */
    val status: String = "",
    /** 评教未完成被教务锁定：分数不展示，也不进统计。 */
    val pendingReview: Boolean = false,
)

/** 一个学期的成绩汇总。 */
data class TermSummary(
    val term: String,
    /** 参与统计的课程数（已认定、有数值分的）。 */
    val courseCount: Int,
    /** 加权平均分：Σ(分数×学分) / Σ学分；无比计分时 null。 */
    val weightedAverage: Double? = null,
    /** 平均绩点：Σ(绩点×学分) / Σ学分；无比计分时 null。 */
    val gpa: Double? = null,
    /** 评教未完成被锁定的课程数。 */
    val lockedCount: Int = 0,
)

object ScoreCalculator {

    /** 汇总一个学期。学期为空或全部被锁定时返回 null（UI 直接显示空态）。 */
    fun summarize(term: String, records: List<ScoreRecord>): TermSummary? {
        if (records.isEmpty()) return null
        val locked = records.count { it.pendingReview }
        val counted = records.filter { !it.pendingReview && it.score != null && it.credit > 0.0 }
        val creditSum = counted.sumOf { it.credit }
        val weightedAvg = if (creditSum > 0.0) {
            counted.sumOf { it.score!! * it.credit } / creditSum
        } else {
            null
        }
        val gpaRecords = records.filter { !it.pendingReview && it.gradePoint != null && it.credit > 0.0 }
        val gpaCredit = gpaRecords.sumOf { it.credit }
        val gpa = if (gpaCredit > 0.0) {
            gpaRecords.sumOf { it.gradePoint!! * it.credit } / gpaCredit
        } else {
            null
        }
        return TermSummary(
            term = term,
            courseCount = records.size - locked,
            weightedAverage = weightedAvg?.let { round2(it) },
            gpa = gpa?.let { round2(it) },
            lockedCount = locked,
        )
    }

    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
}
