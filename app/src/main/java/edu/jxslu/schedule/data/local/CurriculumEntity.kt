package edu.jxslu.schedule.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import edu.jxslu.schedule.domain.CurriculumCourse

/**
 * 培养方案课程设置（DESIGN §4.41，Room v19 → v20）。
 *
 * 全局归属学生、**不挂 timetableId**：培养方案跟人走，与当前在看哪张课表无关
 * （同 [ScholarCourseEntity] 的口径，别照抄 courses 那套过滤纪律）。
 *
 * 写入口径是**整体替换**：一次导入 = 清空本表再插。页面一次给全量课程行，
 * 出自同一次教务快照，不做增量——分开更新会留下互相矛盾的中间态。
 */
@Entity(tableName = "curriculum_courses")
data class CurriculumCourseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 开设学期 1–8；0 = 教务没标。 */
    val term: Int,
    /** 解析序号（全表递增），列表排序用。 */
    val sortOrder: Int,
    val courseNo: String,
    val name: String,
    val kind: String,
    val attr: String,
    /** 计划学分；null = 教务没给 / 解析不出。 */
    val credit: Double?,
    /** 理论 / 实践 / 讲座 / 实验 / 总学时（字符串，保留「2(周)」原文），空串 = 没给。 */
    val hoursTheory: String,
    val hoursPractice: String,
    val hoursLecture: String,
    val hoursExperiment: String,
    val hoursTotal: String,
    /** 体系（通识限选课 / 通识必修课 / 学科基础课 / 专业课）。 */
    val tixi: String,
) {
    fun toDomain(): CurriculumCourse = CurriculumCourse(
        term = term,
        sortOrder = sortOrder,
        courseNo = courseNo,
        name = name,
        kind = kind,
        attr = attr,
        credit = credit,
        hoursTheory = hoursTheory,
        hoursPractice = hoursPractice,
        hoursLecture = hoursLecture,
        hoursExperiment = hoursExperiment,
        hoursTotal = hoursTotal,
        tixi = tixi,
    )

    companion object {
        fun fromDomain(course: CurriculumCourse): CurriculumCourseEntity = CurriculumCourseEntity(
            term = course.term,
            sortOrder = course.sortOrder,
            courseNo = course.courseNo,
            name = course.name,
            kind = course.kind,
            attr = course.attr,
            credit = course.credit,
            hoursTheory = course.hoursTheory,
            hoursPractice = course.hoursPractice,
            hoursLecture = course.hoursLecture,
            hoursExperiment = course.hoursExperiment,
            hoursTotal = course.hoursTotal,
            tixi = course.tixi,
        )
    }
}
