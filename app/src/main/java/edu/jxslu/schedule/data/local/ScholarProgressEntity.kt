package edu.jxslu.schedule.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import edu.jxslu.schedule.domain.ScholarCourse
import edu.jxslu.schedule.domain.ScholarCourseStatus
import edu.jxslu.schedule.domain.ScholarGroup

/**
 * 学业完成情况：分组头（DESIGN §4.29，Room v12 → v13）。
 *
 * 全局归属学生、**不挂 timetableId**：培养方案跟人走，与当前在看哪张课表无关
 * （同 [ScoreEntity] 的口径，别照抄 courses 那套过滤纪律）。
 *
 * 写入口径是**整体替换**：一次导入 = 清空两张表再插。四个维度一次抓齐，
 * 不做单维度增量——它们来自同一次教务快照，分开更新反而会出现「课程体系是新的、
 * 课程性质是旧的」这种自相矛盾的中间态。
 */
@Entity(
    tableName = "scholar_groups",
    indices = [Index("dimension")],
)
data class ScholarGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 维度 id，见 [edu.jxslu.schedule.domain.ScholarDimension]。 */
    val dimension: String,
    val name: String,
    val sortOrder: Int,
    val requiredCredit: Double?,
    val earnedCredit: Double?,
    val ongoingCredit: Double?,
    val remainingCredit: Double?,
    val passed: Boolean?,
    val percent: String,
) {
    fun toDomain(): ScholarGroup = ScholarGroup(
        dimension = dimension,
        name = name,
        sortOrder = sortOrder,
        requiredCredit = requiredCredit,
        earnedCredit = earnedCredit,
        ongoingCredit = ongoingCredit,
        remainingCredit = remainingCredit,
        passed = passed,
        percent = percent,
    )

    companion object {
        fun fromDomain(group: ScholarGroup): ScholarGroupEntity = ScholarGroupEntity(
            dimension = group.dimension,
            name = group.name,
            sortOrder = group.sortOrder,
            requiredCredit = group.requiredCredit,
            earnedCredit = group.earnedCredit,
            ongoingCredit = group.ongoingCredit,
            remainingCredit = group.remainingCredit,
            passed = group.passed,
            percent = group.percent,
        )
    }
}

/** 学业完成情况：课程明细（DESIGN §4.29）。归属键是 `dimension + groupName`。 */
@Entity(
    tableName = "scholar_courses",
    indices = [Index("dimension")],
)
data class ScholarCourseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dimension: String,
    val groupName: String,
    val sortOrder: Int,
    val term: String,
    val courseNo: String,
    val name: String,
    val credit: Double,
    val planned: Boolean?,
    val category: String,
    val attribute: String,
    val nature: String,
    val status: String,
    val scoreText: String,
    val remark: String,
    val degreeCourse: Boolean?,
) {
    fun toDomain(): ScholarCourse = ScholarCourse(
        dimension = dimension,
        groupName = groupName,
        sortOrder = sortOrder,
        term = term,
        courseNo = courseNo,
        name = name,
        credit = credit,
        planned = planned,
        category = category,
        attribute = attribute,
        nature = nature,
        // 库里存的是 label；脏值退回「未修读」，与解析时的兜底一致
        status = ScholarCourseStatus.entries.firstOrNull { it.label == status }?.label
            ?: ScholarCourseStatus.Pending.label,
        scoreText = scoreText,
        remark = remark,
        degreeCourse = degreeCourse,
    )

    companion object {
        fun fromDomain(course: ScholarCourse): ScholarCourseEntity = ScholarCourseEntity(
            dimension = course.dimension,
            groupName = course.groupName,
            sortOrder = course.sortOrder,
            term = course.term,
            courseNo = course.courseNo,
            name = course.name,
            credit = course.credit,
            planned = course.planned,
            category = course.category,
            attribute = course.attribute,
            nature = course.nature,
            status = course.status,
            scoreText = course.scoreText,
            remark = course.remark,
            degreeCourse = course.degreeCourse,
        )
    }
}
