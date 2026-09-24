package edu.jxslu.schedule.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import edu.jxslu.schedule.domain.Course
import edu.jxslu.schedule.domain.CourseKind
import edu.jxslu.schedule.domain.Homework
import edu.jxslu.schedule.domain.Note
import edu.jxslu.schedule.domain.ScoreRecord
import edu.jxslu.schedule.domain.SemesterConfig
import edu.jxslu.schedule.domain.TimeSlot
import edu.jxslu.schedule.domain.Timetable
import java.time.LocalDate

/**
 * 单个课表的元信息与课表级设置（DESIGN §4.9）。
 *
 * 学期配置、作息表、课程都以 timetableId 归属。显示偏好 v3 时期以 JSON 存在本表，
 * 2026-09-19 起全局化（DataStore `view_prefs_json`）——[prefsJson] 列保留只为 schema
 * 稳定（迁移只增不删），新代码不读不写。
 */
@Entity(tableName = "timetables")
data class TimetableEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    val sortOrder: Int,
    /** 用户是否手工改过作息；结构性作息迁移只对未自定义的课表生效。 */
    val slotsCustomized: Boolean = false,
    /** 【退役列】v3 的课表级显示偏好 JSON。仅历史迁移（globalizeViewPrefs）还会读。 */
    val prefsJson: String,
) {
    fun toDomain(): Timetable = Timetable(
        id = id,
        name = name,
        createdAt = createdAt,
        sortOrder = sortOrder,
        slotsCustomized = slotsCustomized,
    )
}

@Entity(
    tableName = "courses",
    indices = [Index("timetableId")],
)
data class CourseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 归属课表；DEFAULT 1 使 v2 老数据迁移后自动落进默认课表 */
    val timetableId: Long = 1,
    val name: String,
    val teacher: String,
    val position: String,
    /** 1=周一 … 7=周日 */
    val day: Int,
    val startSection: Int,
    val endSection: Int,
    /** 逗号分隔周次，如 1,3,5-8 展开为 1,3,5,6,7,8 */
    val weeksCsv: String,
    val isCustomTime: Boolean = false,
    val customStartTime: String? = null,
    val customEndTime: String? = null,
    val colorIndex: Int = 0,
    /** [CourseKind] 的小写名；DB 默认 'theory'，老数据迁移后自动落为理论课 */
    val kind: String = "theory",
    /** 课程备注（DESIGN §4.3，Room v8）；DEFAULT '' 让历史数据自动落成空备注 */
    val remark: String = "",
) {
    fun toDomain(): Course = Course(
        id = id,
        name = name,
        teacher = teacher,
        position = position,
        day = day,
        startSection = startSection,
        endSection = endSection,
        weeks = weeksCsv.split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .toSet(),
        isCustomTime = isCustomTime,
        customStartTime = customStartTime,
        customEndTime = customEndTime,
        colorIndex = colorIndex,
        kind = courseKindFromName(kind),
        remark = remark,
    )

    companion object {
        fun fromDomain(course: Course): CourseEntity = CourseEntity(
            id = course.id.takeIf { it > 0 } ?: 0,
            name = course.name,
            teacher = course.teacher,
            position = course.position,
            day = course.day,
            startSection = course.startSection,
            endSection = course.endSection,
            weeksCsv = course.worstCaseWeeksCsv(),
            isCustomTime = course.isCustomTime,
            customStartTime = course.customStartTime,
            customEndTime = course.customEndTime,
            colorIndex = course.colorIndex,
            kind = course.kind.name.lowercase(),
            remark = course.remark,
        )
    }
}

/**
 * 字符串 → [CourseKind]。
 * 不用 `CourseKind.valueOf`：库里可能存着历史脏值或大小写差异，取不到时应退回 Theory 而不是抛异常崩在读库路径上。
 */
fun courseKindFromName(value: String?): CourseKind =
    CourseKind.entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: CourseKind.Theory

private fun Course.worstCaseWeeksCsv(): String =
    weeks.sorted().joinToString(",")

/**
 * 作息表。v3 起每张课表一份：主键从 number 变为 (timetableId, number)。
 * SQLite 不能改主键，v2→v3 迁移用「建新表→搬数据→改名」重建（见 JuwDatabase.MIGRATION_2_3）。
 */
@Entity(tableName = "time_slots", primaryKeys = ["timetableId", "number"])
data class TimeSlotEntity(
    val timetableId: Long,
    val number: Int,
    val startTime: String,
    val endTime: String,
) {
    fun toDomain(): TimeSlot = TimeSlot(number, startTime, endTime)
}

/**
 * 学期配置。v3 起每张课表一份：主键从固定 id=1 变为 timetableId。
 */
@Entity(tableName = "semester_config", primaryKeys = ["timetableId"])
data class SemesterConfigEntity(
    val timetableId: Long,
    val startDate: String,
    val totalWeeks: Int,
    val firstDayOfWeek: Int,
) {
    fun toDomain(): SemesterConfig = SemesterConfig(startDate, totalWeeks, firstDayOfWeek)

    companion object {
        fun fromDomain(timetableId: Long, config: SemesterConfig): SemesterConfigEntity =
            SemesterConfigEntity(
                timetableId = timetableId,
                startDate = config.startDate,
                totalWeeks = config.totalWeeks,
                firstDayOfWeek = config.firstDayOfWeek,
            )
    }
}

/**
 * 调课自动检测的两张表（`detect_baselines`/`detect_reports`）已随功能移除（2026-09-24，
 * v10 迁移 DROP TABLE）。实体与 DAO 一并删除；`MIGRATION_4_5` 的建表语句保留——
 * 迁移链不可断，v4 用户仍需先建表再被 v10 删掉。
 */

class Converters {
    @TypeConverter
    fun fromWeeksSet(weeks: Set<Int>): String = weeks.sorted().joinToString(",")

    @TypeConverter
    fun toWeeksSet(value: String): Set<Int> =
        value.split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
}

/**
 * 课程成绩（DESIGN §4.15）。全局归属学生、不挂 timetableId；
 * 写入口径是「按学期替换」——同一学期先删后插，重复导入不产生重复行。
 */
@Entity(
    tableName = "scores",
    indices = [Index("term")],
)data class ScoreEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 学年学期，如 2025-2026-2 */
    val term: String,
    val courseNo: String,
    val name: String,
    val unit: String,
    val credit: Double,
    val hours: Double,
    val examForm: String,
    val courseAttr: String,
    val category: String,
    /** 数值分；等级制成绩为 null */
    val score: Double?,
    val scoreStr: String,
    val gradePoint: Double?,
    val status: String,
    val pendingReview: Boolean,
    val importedAt: Long,
) {
    fun toDomain(): ScoreRecord = ScoreRecord(
        id = id,
        term = term,
        courseNo = courseNo,
        name = name,
        unit = unit,
        credit = credit,
        hours = hours,
        examForm = examForm,
        courseAttr = courseAttr,
        category = category,
        score = score,
        scoreStr = scoreStr,
        gradePoint = gradePoint,
        status = status,
        pendingReview = pendingReview,
    )

    companion object {
        fun fromDomain(record: ScoreRecord, importedAt: Long): ScoreEntity = ScoreEntity(
            id = record.id.takeIf { it > 0 } ?: 0,
            term = record.term,
            courseNo = record.courseNo,
            name = record.name,
            unit = record.unit,
            credit = record.credit,
            hours = record.hours,
            examForm = record.examForm,
            courseAttr = record.courseAttr,
            category = record.category,
            score = record.score,
            scoreStr = record.scoreStr,
            gradePoint = record.gradePoint,
            status = record.status,
            pendingReview = record.pendingReview,
            importedAt = importedAt,
        )
    }
}

/**
 * 课程笔记·课件（DESIGN §4.20，Room v6 → v7）。
 *
 * 归属键 [courseName] 是**原样字符串**：课程行 id 在覆盖导入（清表重建）、调课（删行重建）、
 * 撤销（原 id 回滚）里都会被换掉，绑 id 必丢数据（取舍段见 DESIGN §4.20「归属」）。
 * 也**不带 timetableId**：多课表 = 多学期，换课表后旧笔记仍应可查，这是有意为之，
 * 不要按「课表数据按 timetableId 过滤」的旧纪律给它补一列。
 *
 * 索引名由 Room 生成（`index_notes_courseName` / `index_notes_updatedAt`），
 * 迁移里的 CREATE INDEX 必须逐字对齐，否则迁移校验崩溃。
 */
@Entity(
    tableName = "notes",
    indices = [Index("courseName"), Index("updatedAt")],
)
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val courseName: String,
    val title: String,
    /** Markdown 源码；图片以 `![](img:文件名)` 内联引用。 */
    val body: String,
    val createdAt: Long,
    val updatedAt: Long,
) {
    fun toDomain(): Note = Note(
        id = id,
        courseName = courseName,
        title = title,
        body = body,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    companion object {
        fun fromDomain(note: Note): NoteEntity = NoteEntity(
            id = note.id.takeIf { it > 0 } ?: 0,
            courseName = note.courseName,
            title = note.title,
            body = note.body,
            createdAt = note.createdAt,
            updatedAt = note.updatedAt,
        )
    }
}

/**
 * 作业（DESIGN §4.20，Room v6 → v7）。归属口径同 [NoteEntity]：挂课程名、不带 timetableId。
 *
 * [dueDate] 存 `yyyy-MM-dd` 文本：ISO 文本的字典序 = 时间序（同 `semester_config.startDate`
 * 的既有惯例），可在 SQL 里直接排序/比较；解析失败的脏值退回 null 而不崩在读库路径上。
 */
@Entity(
    tableName = "homework",
    indices = [Index("courseName"), Index("done"), Index("dueDate")],
)
data class HomeworkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val courseName: String,
    val detail: String,
    /** yyyy-MM-dd；null = 未设截止日期。 */
    val dueDate: String?,
    val done: Boolean,
    val doneAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
) {
    fun toDomain(): Homework = Homework(
        id = id,
        courseName = courseName,
        detail = detail,
        dueDate = dueDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        done = done,
        doneAt = doneAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    companion object {
        fun fromDomain(homework: Homework): HomeworkEntity = HomeworkEntity(
            id = homework.id.takeIf { it > 0 } ?: 0,
            courseName = homework.courseName,
            detail = homework.detail,
            dueDate = homework.dueDate?.toString(),
            done = homework.done,
            doneAt = homework.doneAt,
            createdAt = homework.createdAt,
            updatedAt = homework.updatedAt,
        )
    }
}
