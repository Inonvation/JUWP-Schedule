package edu.jxslu.schedule.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import edu.jxslu.schedule.domain.Course
import edu.jxslu.schedule.domain.CourseKind
import edu.jxslu.schedule.domain.SemesterConfig
import edu.jxslu.schedule.domain.TimeSlot
import edu.jxslu.schedule.domain.Timetable
import edu.jxslu.schedule.domain.TimetablePrefs

/**
 * 单个课表的元信息与课表级设置（DESIGN §4.9）。
 *
 * 学期配置、作息表、课程都以 timetableId 归属；显示偏好以 JSON 存在本表：
 * 这些值没有查询需求，放 JSON 列让「复制配置到新课表」变成拷一行，加字段也不用动 Room 版本号。
 */
@Entity(tableName = "timetables")
data class TimetableEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    val sortOrder: Int,
    /** 用户是否手工改过作息；结构性作息迁移只对未自定义的课表生效。 */
    val slotsCustomized: Boolean = false,
    /** [TimetablePrefs] 的 JSON。解码失败退默认值（见 TimetablePrefs.decode）。 */
    val prefsJson: String,
) {
    fun toDomain(): Timetable = Timetable(
        id = id,
        name = name,
        createdAt = createdAt,
        sortOrder = sortOrder,
        slotsCustomized = slotsCustomized,
        prefs = TimetablePrefs.decode(prefsJson),
    )

    companion object {
        fun fromDomain(t: Timetable): TimetableEntity = TimetableEntity(
            id = t.id,
            name = t.name,
            createdAt = t.createdAt,
            sortOrder = t.sortOrder,
            slotsCustomized = t.slotsCustomized,
            prefsJson = t.prefs.encode(),
        )
    }
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

class Converters {
    @TypeConverter
    fun fromWeeksSet(weeks: Set<Int>): String = weeks.sorted().joinToString(",")

    @TypeConverter
    fun toWeeksSet(value: String): Set<Int> =
        value.split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
}
