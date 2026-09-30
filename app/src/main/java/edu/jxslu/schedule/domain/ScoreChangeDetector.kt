package edu.jxslu.schedule.domain

/**
 * 成绩变更检测（DESIGN §4.33）：出分提醒的纯逻辑。
 *
 * 输入是「替换前的库里全量」与「本次抓到的全量」，输出值得打扰用户的变更。只认两件事：
 * - **出分**：旧库无分（或根本没有这门课）而新数据有分——期末成绩一门一门出的场景，
 *   这正是提醒存在的原因；
 * - **改分**：两侧都有分但值不同（复查改分、更正录入）。
 *
 * 待评教（`pendingReview`）的记录分数被教务锁定不展示，旧侧锁着、新侧解锁算出分。
 * 「旧有新无」不出通知：成绩不会平白消失，多半是教务侧调整，宁可少打扰。
 */
object ScoreChangeDetector {

    /** 新出分 / 分数变更。 */
    enum class Kind { PUBLISHED, UPDATED }

    data class Change(
        val kind: Kind,
        val term: String,
        val name: String,
        /** 展示口径分数（"84" / "优"），取新值。 */
        val scoreStr: String,
    )

    fun detect(old: List<ScoreRecord>, new: List<ScoreRecord>): List<Change> {
        val oldByKey = old.associateBy { it.key }
        val changes = mutableListOf<Change>()
        for (record in new) {
            if (record.pendingReview || !record.hasScore) continue
            val before = oldByKey[record.key]
            val change = when {
                before == null || before.pendingReview || !before.hasScore ->
                    Change(Kind.PUBLISHED, record.term, record.name, record.scoreStr)
                before.score != record.score || before.scoreStr != record.scoreStr ->
                    Change(Kind.UPDATED, record.term, record.name, record.scoreStr)
                else -> null
            }
            change?.let(changes::add)
        }
        return changes.sortedWith(compareBy({ it.term }, { it.name }))
    }

    private val ScoreRecord.hasScore: Boolean
        get() = score != null || scoreStr.isNotBlank()

    private val ScoreRecord.key: String
        get() = "$term|$courseNo|$name"
}
