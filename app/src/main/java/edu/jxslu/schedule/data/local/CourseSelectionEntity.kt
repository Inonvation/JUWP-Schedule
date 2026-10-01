package edu.jxslu.schedule.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import edu.jxslu.schedule.domain.CourseSelection
import kotlinx.coroutines.flow.Flow

/**
 * 选课记录（DESIGN §4.35，2026-09-30；表 `course_selections`，DB v17）。
 *
 * 与 `scores` 同构：全局归属学生、不挂 timetableId，写入口径是「按学期替换」——
 * 同一学期先删后插，重复导入不产生重复行。
 *
 * 实体列**不写 Kotlin 默认值**（同 `RideRecordEntity`）：Room 会把构造默认值写进
 * 预期 schema 的 DEFAULT，迁移建表就要逐字带上（v7→v8、v13→v14 的老坑）；
 * 全部字段必填，迁移里的 CREATE TABLE 就是朴素的 `NOT NULL`。
 * 索引名由 Room 生成为 `index_course_selections_term`，迁移的 CREATE INDEX 必须逐字对齐。
 */
@Entity(
    tableName = "course_selections",
    indices = [Index("term")],
)
data class CourseSelectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 学年学期，如 2026-2027-1。 */
    val term: String,
    val courseNo: String,
    val name: String,
    val teacher: String,
    val credit: Double,
    val hours: Double,
    /** 课程属性：必修 / 任选… */
    val attribute: String,
    /** 课程性质：集中实践教学环节 / 通识必修课… */
    val category: String,
    /** 教学班。 */
    val className: String,
    /** 开课学院。 */
    val college: String,
    /** 上课时间原文（多行）。 */
    val timeText: String,
    /** 上课地点原文（多行）。 */
    val placeText: String,
    val status: String,
    val remark: String,
    val importedAt: Long,
) {
    fun toDomain(): CourseSelection = CourseSelection(
        id = id,
        term = term,
        courseNo = courseNo,
        name = name,
        teacher = teacher,
        credit = credit,
        hours = hours,
        attribute = attribute,
        category = category,
        className = className,
        college = college,
        timeText = timeText,
        placeText = placeText,
        status = status,
        remark = remark,
    )

    companion object {
        fun fromDomain(record: CourseSelection, importedAt: Long): CourseSelectionEntity =
            CourseSelectionEntity(
                id = record.id.takeIf { it > 0 } ?: 0,
                term = record.term,
                courseNo = record.courseNo,
                name = record.name,
                teacher = record.teacher,
                credit = record.credit,
                hours = record.hours,
                attribute = record.attribute,
                category = record.category,
                className = record.className,
                college = record.college,
                timeText = record.timeText,
                placeText = record.placeText,
                status = record.status,
                remark = record.remark,
                importedAt = importedAt,
            )
    }
}

@Dao
interface CourseSelectionDao {

    /** 有选课记录的学期列表，倒序（字典序倒序即时间倒序：2026-2027-1 > 2025-2026-2）。 */
    @Query("SELECT DISTINCT term FROM course_selections ORDER BY term DESC")
    fun observeTerms(): Flow<List<String>>

    /** 某学期的选课记录，按入库顺序（= 教务列表顺序）。 */
    @Query("SELECT * FROM course_selections WHERE term = :term ORDER BY id")
    fun observeForTerm(term: String): Flow<List<CourseSelectionEntity>>

    /** 全学期（选课页一次取全，按学期分组在内存里做）。 */
    @Query("SELECT * FROM course_selections ORDER BY term DESC, id")
    fun observeAll(): Flow<List<CourseSelectionEntity>>

    /** 「有没有数据」的廉价判定（同步闸门用）：不把整张表读进内存。 */
    @Query("SELECT COUNT(*) FROM course_selections")
    suspend fun count(): Int

    /** 某学期是否已有本地数据（决定要不要自动抓）。 */
    @Query("SELECT COUNT(*) FROM course_selections WHERE term = :term")
    suspend fun countForTerm(term: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(records: List<CourseSelectionEntity>)

    @Query("DELETE FROM course_selections WHERE term = :term")
    suspend fun deleteForTerm(term: String)

    @Query("DELETE FROM course_selections")
    suspend fun deleteAll()
}
