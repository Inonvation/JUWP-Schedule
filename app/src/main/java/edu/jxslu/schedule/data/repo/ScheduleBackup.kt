package edu.jxslu.schedule.data.repo

import edu.jxslu.schedule.data.local.courseKindFromName
import edu.jxslu.schedule.domain.Course
import edu.jxslu.schedule.domain.CourseKind
import edu.jxslu.schedule.domain.CurriculumCourse
import edu.jxslu.schedule.domain.ScholarCourse
import edu.jxslu.schedule.domain.ScholarCourseStatus
import edu.jxslu.schedule.domain.ScholarGroup
import edu.jxslu.schedule.domain.ScheduleCalculator
import edu.jxslu.schedule.domain.ScoreRecord
import edu.jxslu.schedule.domain.SemesterConfig
import edu.jxslu.schedule.domain.Textbook
import edu.jxslu.schedule.domain.TimeSlot
import edu.jxslu.schedule.domain.TimeSlotRules
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class CourseJson(
    val id: Long = 0,
    val name: String,
    val teacher: String = "",
    val position: String = "",
    val day: Int,
    val startSection: Int,
    val endSection: Int,
    val weeks: List<Int> = emptyList(),
    val isCustomTime: Boolean = false,
    val customStartTime: String? = null,
    val customEndTime: String? = null,
    val colorIndex: Int = 0,
    /** [CourseKind] 的小写名。带默认值，保证加字段前导出的旧 JSON 仍能读进来。 */
    val kind: String = "theory",
    /** 课程备注（DESIGN §4.3，2026-09-21，可选键）：旧文件缺省为空串，新版多写的键拾光侧忽略。 */
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
        weeks = weeks.toSet(),
        isCustomTime = isCustomTime,
        customStartTime = customStartTime,
        customEndTime = customEndTime,
        colorIndex = colorIndex,
        kind = courseKindFromName(kind),
        remark = remark,
    )
}

@Serializable
data class CourseExport(
    val courses: List<CourseJson> = emptyList(),
    /** 数据所属学年学期（如 2026-2027-1），来自教务/脚本导出；旧文件或手工编辑没有则缺省。 */
    val term: String? = null,
    /**
     * 成绩备份段（DESIGN §4.3/§4.15）：换机/重装随课表一起恢复。
     * 读取侧 ignoreUnknownKeys——旧版 App 忽略该段、新版读旧文件缺省为空，两版互不破坏。
     */
    val scores: List<ScoreBackupJson> = emptyList(),
    /**
     * 学业完成情况备份段（DESIGN §4.3/§4.29）：换机/重装随课表一起恢复。
     * 与成绩同理，旧版 App 忽略该段、新版读旧文件缺省为空，两版互不破坏。
     *
     * 培养方案名不在备份里：它是展示性字段，恢复后为空只是少显示一行，
     * 下次抓取就有了；为此把它塞进 [DisplayPrefs] 反而会牵动整套偏好默认值契约。
     */
    val scholarGroups: List<ScholarGroupBackupJson> = emptyList(),
    val scholarCourses: List<ScholarCourseBackupJson> = emptyList(),
    /**
     * 培养方案课程设置备份段（DESIGN §4.3/§4.41）：换机/重装随备份一起恢复。
     * 与学业段同理，旧版 App 忽略该段、新版读旧文件缺省为空，两版互不破坏。
     * 方案名不进备份（展示性字段，恢复后下次抓取即补，同学业段的口径）。
     */
    val curriculumCourses: List<CurriculumCourseBackupJson> = emptyList(),
    /**
     * 教材备份段（DESIGN §4.31）：换机/重装随备份一起恢复。与成绩同理，
     * 旧版 App 忽略该段、新版读旧文件缺省为空，两版互不破坏。
     */
    val textbooks: List<TextbookBackupJson> = emptyList(),
    /**
     * 学期配置备份段（DESIGN §4.3）：导出当前课表、导入恢复到目标课表。
     * 旧文件没有此键 → 不动目标课表的学期配置。
     */
    val semester: SemesterBackupJson? = null,
    /**
     * 作息备份段（DESIGN §4.3）：一条一个 40 分钟小节（小节号口径 §3.5）。
     * 旧文件没有此键 → 不动目标课表的作息。
     */
    val timeSlots: List<TimeSlotBackupJson> = emptyList(),
)

/** 备份文件里的学期配置段（DESIGN §4.3）：全带默认值，异构文件缺字段时降级而不是解码失败。 */
@Serializable
data class SemesterBackupJson(
    /** yyyy-MM-dd。 */
    val startDate: String = "",
    val totalWeeks: Int = 20,
    val firstDayOfWeek: Int = 1,
)

/** 备份文件里的作息段：一条一个 40 分钟小节（小节号口径见 DESIGN §3.5）。 */
@Serializable
data class TimeSlotBackupJson(
    val number: Int,
    val startTime: String = "",
    val endTime: String = "",
)

/**
 * 备份文件里的单条成绩。字段与 [ScoreRecord] 对齐、全带默认值：
 * 手工编辑或旧格式缺字段时降级为空值而不是解码失败。
 */
@Serializable
data class ScoreBackupJson(
    val term: String = "",
    val courseNo: String = "",
    val name: String = "",
    val unit: String = "",
    val credit: Double = 0.0,
    val hours: Double = 0.0,
    val examForm: String = "",
    val courseAttr: String = "",
    val category: String = "",
    /** 数值分；等级制成绩为 null。 */
    val score: Double? = null,
    val scoreStr: String = "",
    val gradePoint: Double? = null,
    val status: String = "",
    val pendingReview: Boolean = false,
)

/**
 * 备份文件里的学业完成情况分组段（DESIGN §4.3 / §4.29）。
 *
 * 字段全部带默认值：手工编辑或旧格式缺字段时降级为空值，而不是整个文件解码失败。
 * 四个维度混在一张列表里，用 `dimension` 区分（与库里的存法一致）。
 */
@Serializable
data class ScholarGroupBackupJson(
    val dimension: String = "",
    val name: String = "",
    val sortOrder: Int = 0,
    val requiredCredit: Double? = null,
    val earnedCredit: Double? = null,
    val ongoingCredit: Double? = null,
    val remainingCredit: Double? = null,
    val passed: Boolean? = null,
    val percent: String = "",
)

/** 备份文件里的学业完成情况课程明细段（DESIGN §4.3 / §4.29）。 */
@Serializable
data class ScholarCourseBackupJson(
    val dimension: String = "",
    val groupName: String = "",
    val sortOrder: Int = 0,
    val term: String = "",
    val courseNo: String = "",
    val name: String = "",
    val credit: Double = 0.0,
    val planned: Boolean? = null,
    val category: String = "",
    val attribute: String = "",
    val nature: String = "",
    val status: String = "",
    val scoreText: String = "",
    val remark: String = "",
    val degreeCourse: Boolean? = null,
)

/**
 * 备份文件里的培养方案课程段（DESIGN §4.3 / §4.41）：字段与 [CurriculumCourse] 对齐、
 * 全带默认值——手工编辑或旧格式缺字段时降级为空值，而不是整个文件解码失败。
 */
@Serializable
data class CurriculumCourseBackupJson(
    val term: Int = 0,
    val sortOrder: Int = 0,
    val courseNo: String = "",
    val name: String = "",
    val kind: String = "",
    val attr: String = "",
    val credit: Double? = null,
    val hoursTheory: String = "",
    val hoursPractice: String = "",
    val hoursLecture: String = "",
    val hoursExperiment: String = "",
    val hoursTotal: String = "",
    val tixi: String = "",
)

/** 备份文件里的单条教材（DESIGN §4.31）：字段与 [Textbook] 对齐、全带默认值。 */
@Serializable
data class TextbookBackupJson(
    val term: String = "",
    val courseName: String = "",
    val title: String = "",
    val author: String = "",
    val press: String = "",
    val edition: String = "",
    val isbn: String = "",
    val price: String = "",
)

fun Textbook.toBackupJson(): TextbookBackupJson = TextbookBackupJson(
    term = term,
    courseName = courseName,
    title = title,
    author = author,
    press = press,
    edition = edition,
    isbn = isbn,
    price = price,
)

fun TextbookBackupJson.toTextbook(): Textbook = Textbook(
    term = term,
    courseName = courseName,
    title = title,
    author = author,
    press = press,
    edition = edition,
    isbn = isbn,
    price = price,
)

fun ScholarGroupBackupJson.toScholarGroup(): ScholarGroup = ScholarGroup(
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

fun ScholarGroup.toBackupJson(): ScholarGroupBackupJson = ScholarGroupBackupJson(
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

fun ScholarCourseBackupJson.toScholarCourse(): ScholarCourse = ScholarCourse(
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
    status = status.ifBlank { ScholarCourseStatus.Pending.label },
    scoreText = scoreText,
    remark = remark,
    degreeCourse = degreeCourse,
)

fun ScholarCourse.toBackupJson(): ScholarCourseBackupJson = ScholarCourseBackupJson(
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
    status = status,
    scoreText = scoreText,
    remark = remark,
    degreeCourse = degreeCourse,
)

fun CurriculumCourseBackupJson.toCurriculumCourse(): CurriculumCourse = CurriculumCourse(
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

fun CurriculumCourse.toBackupJson(): CurriculumCourseBackupJson = CurriculumCourseBackupJson(
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

fun ScoreBackupJson.toScoreRecord(): ScoreRecord = ScoreRecord(
    term = term.trim(),
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

fun ScoreRecord.toBackupJson(): ScoreBackupJson = ScoreBackupJson(
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

/**
 * 导入/导出共用的 JSON 配置。
 *
 * 提到顶层是为了让测试能复用**同一份**配置：之前测试只断言字符串里含某些键名，
 * 这里把 `ignoreUnknownKeys` 改掉、或者字段加/改默认值，测试都不会红。
 * 现在测试直接拿它 decode，配置漂移会被测出来。
 *
 * `encodeDefaults = true`：kotlinx 默认不写等于默认值的键，于是 `teacher=""`、`kind="theory"`
 * 这类字段会直接从导出文件里消失。读取侧有默认值兜底、我们自己来回转换不会出错，
 * 但文件是要给兼容工具互导的（见设置页说明），键时有时无会让对方必须猜默认值。
 * 全量写出后导出结果自解释，代价只是文件略大。
 */
internal val CourseJsonFormat: Json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * 校验一条待导入的课程。
 * 返回 null 表示通过，否则返回给用户看的错误文案（含第几门课，便于对文件定位）。
 *
 * 与解码分离：`Json` 只保证结构能读出来，字段取值范围要靠这一层，
 * 否则 day=9 这种脏数据会一路写进库里，最后表现成「课程不见了」。
 */
internal fun validateCourseJson(index: Int, c: CourseJson): String? {
    val label = "第 ${index + 1} 门课"
    if (c.name.isBlank()) return "$label：缺少 name"
    if (c.day !in 1..7) return "$label「${c.name}」：day 应在 1–7，实际为 ${c.day}"
    if (c.startSection < 1) return "$label「${c.name}」：startSection 应 ≥1"
    if (c.endSection < c.startSection) {
        return "$label「${c.name}」：endSection(${c.endSection}) 不能小于 startSection"
    }
    if (c.weeks.isEmpty()) return "$label「${c.name}」：缺少 weeks（如 [1,2,3]）"
    if (c.weeks.any { it !in 1..40 }) return "$label「${c.name}」：weeks 含非法周次"
    return null
}

/** 目标课表的导入统计：弹窗按所选目标实时展示（覆盖会清掉 existing 门，合并新增 newCount 门）。 */
data class ImportStats(val existing: Int, val newCount: Int)

/** 备份文件的统一校验产物：预览与真导入共用，避免两条校验链漂移。 */
internal data class ValidatedBackup(
    val courses: List<Course>,
    val scores: List<ScoreRecord>,
    val scholarGroups: List<ScholarGroup>,
    val scholarCourses: List<ScholarCourse>,
    val curriculum: List<CurriculumCourse>,
    val textbooks: List<Textbook>,
    val semester: SemesterConfig?,
    val slots: List<TimeSlot>,
)

internal sealed interface BackupCheck {
    data class Ok(val value: ValidatedBackup) : BackupCheck
    data class Error(val message: String) : BackupCheck
}

/** 全量校验备份：任何一段有脏数据都整体拒绝，预览与真导入必须走同一份。 */
internal fun validateBackup(export: CourseExport): BackupCheck {
    if (export.courses.isEmpty()) return BackupCheck.Error("文件中没有课程（courses 为空）")
    export.courses.forEachIndexed { index, c ->
        validateCourseJson(index, c)?.let { return BackupCheck.Error(it) }
    }
    val scores = export.scores.map { it.toScoreRecord() }
    scores.forEachIndexed { index, s ->
        if (s.term.isBlank() || s.name.isBlank()) {
            return BackupCheck.Error("第 ${index + 1} 条成绩缺少 term 或 name")
        }
    }
    val scholarGroups = export.scholarGroups.map { it.toScholarGroup() }
    val scholarCourses = export.scholarCourses.map { it.toScholarCourse() }
    scholarGroups.forEachIndexed { index, g ->
        if (g.dimension.isBlank() || g.name.isBlank()) {
            return BackupCheck.Error("第 ${index + 1} 条学业分组缺少 dimension 或 name")
        }
    }
    scholarCourses.forEachIndexed { index, c ->
        if (c.dimension.isBlank() || c.name.isBlank()) {
            return BackupCheck.Error("第 ${index + 1} 条学业课程缺少 dimension 或 name")
        }
    }
    val curriculum = export.curriculumCourses.map { it.toCurriculumCourse() }
    curriculum.forEachIndexed { index, c ->
        if (c.name.isBlank()) {
            return BackupCheck.Error("第 ${index + 1} 条培养方案课程缺少 name")
        }
    }
    val textbooks = export.textbooks.map { it.toTextbook() }
    textbooks.forEachIndexed { index, b ->
        if (b.term.isBlank() || b.courseName.isBlank() || b.title.isBlank()) {
            return BackupCheck.Error("第 ${index + 1} 条教材缺少 term、courseName 或 title")
        }
    }
    val semester = export.semester?.let { s ->
        val date = runCatching { ScheduleCalculator.parseDate(s.startDate) }.getOrNull()
            ?: return BackupCheck.Error("备份里的开学日期格式不正确：${s.startDate}")
        SemesterConfig(date.toString(), s.totalWeeks.coerceIn(1, 30), s.firstDayOfWeek)
    }
    val slots = export.timeSlots.map { TimeSlot(it.number, it.startTime, it.endTime) }
    if (slots.isNotEmpty()) {
        TimeSlotRules.validate(slots)?.let {
            return BackupCheck.Error("备份里的作息表有问题：$it")
        }
    }
    return BackupCheck.Ok(
        ValidatedBackup(
            courses = export.courses.map { it.toDomain() },
            scores = scores,
            scholarGroups = scholarGroups,
            scholarCourses = scholarCourses,
            curriculum = curriculum,
            textbooks = textbooks,
            semester = semester,
            slots = slots,
        ),
    )
}
