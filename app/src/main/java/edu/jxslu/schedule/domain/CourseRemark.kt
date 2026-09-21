package edu.jxslu.schedule.domain

/**
 * 课程备注的搬运（DESIGN §4.3「备注的存活口径」，2026-09-21）。
 *
 * 覆盖导入（`replaceAllCourses` 清表重建）与调课检测应用（`applyDetectGroups` 整组重建）
 * 都会把课程行换成新行——新行来自教务数据，**不带备注**。若不管，用户写过的备注会在
 * 一次"覆盖导入"后凭空消失。这里按 [mergeKey]（与导入去重同一把钥匙）把旧行的备注搬到新行：
 *
 * - 新行自己带了备注（合并导入、本地编辑）→ **不覆盖**；
 * - 旧表里没有同 key 的行（真·新课）→ 备注留空；
 * - 多行同 key（同一门课按周/按块拆成多行）→ 每行都拿到同一条备注。
 *
 * 纯函数，测试见 `CourseRemarkTest`。
 */
fun courseRemarksCarriedOver(existing: List<Course>, incoming: List<Course>): List<Course> {
    if (existing.isEmpty() || incoming.isEmpty()) return incoming
    val remarks = existing
        .filter { it.remark.isNotBlank() }
        .associate { it.mergeKey() to it.remark }
    if (remarks.isEmpty()) return incoming
    return incoming.map { course ->
        when {
            course.remark.isNotBlank() -> course
            else -> remarks[course.mergeKey()]?.let { course.copy(remark = it) } ?: course
        }
    }
}
