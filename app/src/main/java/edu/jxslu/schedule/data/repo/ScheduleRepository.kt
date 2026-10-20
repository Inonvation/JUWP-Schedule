package edu.jxslu.schedule.data.repo

import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.DefaultData
import edu.jxslu.schedule.data.local.CourseEntity
import edu.jxslu.schedule.data.local.CurriculumCourseEntity
import edu.jxslu.schedule.data.local.DateOverrideEntity
import edu.jxslu.schedule.data.local.JuwDatabase
import edu.jxslu.schedule.data.local.ScoreEntity
import edu.jxslu.schedule.data.local.ScholarCourseEntity
import edu.jxslu.schedule.data.local.ScholarGroupEntity
import edu.jxslu.schedule.data.local.SemesterConfigEntity
import edu.jxslu.schedule.data.local.TextbookEntity
import edu.jxslu.schedule.data.local.TimeSlotEntity
import edu.jxslu.schedule.data.local.TimetableEntity
import edu.jxslu.schedule.data.local.courseKindFromName
import edu.jxslu.schedule.data.prefs.DisplayPrefs
import edu.jxslu.schedule.data.prefs.DisplayPrefsStore
import edu.jxslu.schedule.data.prefs.ReminderKeyKind
import edu.jxslu.schedule.domain.AiScheduleConfig
import edu.jxslu.schedule.domain.Course
import edu.jxslu.schedule.domain.courseRemarksCarriedOver
import edu.jxslu.schedule.domain.mergeKey
import edu.jxslu.schedule.domain.CourseFilter
import edu.jxslu.schedule.domain.CourseKind
import edu.jxslu.schedule.domain.CourseTweaker
import edu.jxslu.schedule.domain.AppliedEntry
import edu.jxslu.schedule.domain.ScheduleDiff
import edu.jxslu.schedule.domain.ScheduleUndo
import edu.jxslu.schedule.domain.ScheduleOverride
import edu.jxslu.schedule.domain.ScheduleOverrides
import edu.jxslu.schedule.domain.EbikeUseMode
import edu.jxslu.schedule.domain.FloatingBall
import edu.jxslu.schedule.domain.QuietWindow
import edu.jxslu.schedule.domain.ScheduleCalculator
import edu.jxslu.schedule.domain.BgScale
import edu.jxslu.schedule.domain.ScheduleBackground
import edu.jxslu.schedule.domain.ScheduleExporter
import edu.jxslu.schedule.domain.ScheduleExporter.CourseEvent
import edu.jxslu.schedule.domain.CurriculumCourse
import edu.jxslu.schedule.domain.ScoreRecord
import edu.jxslu.schedule.domain.ScholarCourse
import edu.jxslu.schedule.domain.ScholarCourseStatus
import edu.jxslu.schedule.domain.ScholarGroup
import edu.jxslu.schedule.domain.SemesterConfig
import edu.jxslu.schedule.domain.ShortcutItem
import edu.jxslu.schedule.domain.ShortcutSettings
import edu.jxslu.schedule.domain.StartPage
import edu.jxslu.schedule.domain.ThemeMode
import edu.jxslu.schedule.domain.ThemePalette
import edu.jxslu.schedule.domain.TimeSlot
import edu.jxslu.schedule.domain.Timetable
import edu.jxslu.schedule.domain.Textbook
import edu.jxslu.schedule.domain.TimetablePrefs
import edu.jxslu.schedule.domain.TimeSlotRules
import edu.jxslu.schedule.domain.TweakMode
import edu.jxslu.schedule.domain.TweakPlan
import androidx.room.withTransaction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.SharingStarted
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

/**
 * 备份文件里的单条成绩。字段与 [ScoreRecord] 对齐、全带默认值：
 * 手工编辑或旧格式缺字段时降级为空值而不是解码失败。
 */
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

class ScheduleRepository(
    private val db: JuwDatabase,
    private val prefs: DisplayPrefsStore,
    /** 调课检测的基线快照（DESIGN §4.17）：导入/合并成功时推进，检查时与教务比对。 */
    private val baselineStore: ScheduleBaselineStore? = null,
) {

    private val json = CourseJsonFormat

    /** displayPrefs 合并用的全局偏好切片（combine 参数上限的收拢容器）。 */
    private data class GlobalPrefs(
        val theme: ThemeMode,
        val dynamicColor: Boolean,
        val haptics: Boolean,
        val waterDouble: Boolean,
        val waterCard: Boolean,
    )

    /** 共享单车相关偏好的收拢切片（DESIGN §3.9；combine 参数上限的同款处理）。 */
    private data class EbikePrefs(
        val cardEnabled: Boolean,
        val autoSave: Boolean,
        val recentIds: List<String>,
        /** 使用方式（§3.9 / §4.32）：小程序方式 / 账号登录，能力的唯一判据。 */
        val useMode: EbikeUseMode,
    )

    /** 页面外壳类偏好的收拢切片：底栏形态（§4.22）与启动页（§3.3）。 */
    private data class ShellPrefs(
        val floatingNavBar: Boolean,
        val startPage: StartPage,
        val qzxyCardEnabled: Boolean,
    )

    /** 杂项单值的收拢切片：校园卡开关 + 字体缩放锁定 + 百宝箱开关（combine 参数上限的同款处理）。 */
    private data class MiscPrefs(
        val campusCard: Boolean,
        val fontScaleLocked: Boolean,
        val toolboxEnabled: Boolean,
    )

    // ------------------------------------------------------------------
    // 课表清单与当前课表
    // ------------------------------------------------------------------

    val timetables: Flow<List<Timetable>> =
        db.timetableDao().observeAll().map { list -> list.map { it.toDomain() } }

    /**
     * 当前课表的**已解析** id：存储值失效（被删/未设置）时回退到第一张。
     * 直接暴露解析后的值，调用方不用各自处理「指向已删除课表」的脏状态。
     */
    val currentTimetableId: Flow<Long> = combine(
        db.timetableDao().observeAll(),
        prefs.currentTimetableId,
    ) { list, stored ->
        val id = stored ?: 1L
        if (list.any { it.id == id }) id else list.firstOrNull()?.id ?: 1L
    }.distinctUntilChanged()

    // ------------------------------------------------------------------
    // 当前课表的数据流（换课表即换内容）
    // ------------------------------------------------------------------

    @OptIn(ExperimentalCoroutinesApi::class)
    val courses: Flow<List<Course>> = currentTimetableId.flatMapLatest { id ->
        db.courseDao().observeForTimetable(id).map { list -> list.map { it.toDomain() } }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val timeSlots: Flow<List<TimeSlot>> = currentTimetableId.flatMapLatest { id ->
        db.timeSlotDao().observeForTimetable(id).map { list -> list.map { it.toDomain() } }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val semester: Flow<SemesterConfig?> = currentTimetableId.flatMapLatest { id ->
        db.semesterConfigDao().observeForTimetable(id).map { it?.toDomain() }
    }

    /**
     * 显示偏好 = 全局项（主题、动态取色、触感、开水双击）+ 全局视图偏好（DESIGN §4.9）。
     * 2026-09-19 起全部字段全局，不再依赖当前课表——换课表不换观感。
     * 对 UI 仍暴露合并后的 [DisplayPrefs]，调用点签名与分层时期一致。
     */
    private val baseDisplayPrefs: Flow<DisplayPrefs> = combine(
        // 全局项先合成一层：combine 的类型安全重载最多 5 参，全局项已满员
        combine(
            prefs.themeMode,
            prefs.dynamicColor,
            prefs.hapticsEnabled,
            prefs.waterRequireDoubleClick,
            prefs.waterCardEnabled,
        ) { theme, dynamicColor, haptics, waterDouble, waterCard ->
            GlobalPrefs(theme, dynamicColor, haptics, waterDouble, waterCard)
        },
        // 共享单车切片（DESIGN §3.9）：三个流合成一层，再并入最外层 combine
        combine(
            prefs.ebikeCardEnabled,
            prefs.ebikeAutoSave,
            prefs.ebikeRecentIds,
            prefs.ebikeUseMode,
            ::EbikePrefs,
        ),
        // 校园卡开关 + 字体缩放锁定 + 百宝箱开关：单值并一槽（外层 combine 五参已满）
        combine(
            prefs.campusCardEnabled,
            prefs.fontScaleLocked,
            prefs.toolboxEnabled,
            ::MiscPrefs,
        ),
        prefs.viewPrefs,
        // 外壳类偏好（DESIGN §3.3 今日页抽屉 / §4.22 悬浮导航栏 / §3.13 生活页）：
        // 最外层 combine 的第 5 个参数
        combine(
            prefs.floatingNavBar,
            prefs.startPage,
            prefs.qzxyCardEnabled,
            ::ShellPrefs,
        ),
    ) { global, ebike, misc, p, shell ->
        // 夹取沿用旧 DataStore 读路径的防线：旧数据/手改数据超出收紧后的滑块范围会让 Slider 抛异常
        DisplayPrefs(
            themeMode = global.theme,
            dynamicColor = global.dynamicColor,
            hapticsEnabled = global.haptics,
            fontScaleLocked = misc.fontScaleLocked,
            waterRequireDoubleClick = global.waterDouble,
            waterCardEnabled = global.waterCard,
            qzxyCardEnabled = shell.qzxyCardEnabled,
            ebikeCardEnabled = ebike.cardEnabled,
            ebikeAutoSave = ebike.autoSave,
            ebikeRecentIds = ebike.recentIds,
            ebikeUseMode = ebike.useMode,
            campusCardEnabled = misc.campusCard,
            toolboxEnabled = misc.toolboxEnabled,
            floatingNavBar = shell.floatingNavBar,
            startPage = shell.startPage,
            // 遗留单开关也一并透出，与实际存储保持一致，免得读了它的人拿到陈旧值。
            showWeekend = p.showSaturday && p.showSunday,
            showSaturday = p.showSaturday,
            showSunday = p.showSunday,
            showNonCurrentWeek = p.showNonCurrentWeek,
            courseFilter = p.courseFilter,
            gridFontDp = p.gridFontDp,
            gridRoomDp = p.gridRoomDp,
            gridTeacherDp = p.gridTeacherDp,
            gridRailDp = p.gridRailDp,
            gridDateDp = p.gridDateDp,
            rowHeightScale = p.rowHeightScale.coerceIn(0.5f, 1.5f),
            railWidthDp = p.railWidthDp.coerceIn(
                TimetablePrefs.MinRailWidthDp,
                TimetablePrefs.MaxRailWidthDp,
            ),
            dayHeaderHeightDp = p.dayHeaderHeightDp.coerceIn(
                TimetablePrefs.MinDayHeaderHeightDp,
                TimetablePrefs.MaxDayHeaderHeightDp,
            ),
            cellRadiusDp = p.cellRadiusDp.coerceIn(0f, 12f),
            cellOpacity = p.cellOpacity.coerceIn(0.5f, 1f),
            cellCenterH = p.cellCenterH,
            cellCenterV = p.cellCenterV,
            showTeacher = p.showTeacher,
            showNowLine = p.showNowLine,
            showCellBorder = p.showCellBorder,
            showGridLines = p.showGridLines,
            showAtSign = p.showAtSign,
            tapBlankToAdd = p.tapBlankToAdd,
            bgImageName = p.bgImageName?.takeIf { ScheduleBackground.isValidFileName(it) },
            bgImageOpacity = p.bgImageOpacity.coerceIn(
                TimetablePrefs.MinBgImageOpacity,
                1f,
            ),
            bgImageDim = p.bgImageDim.coerceIn(0f, TimetablePrefs.MaxBgImageDim),
            bgImageBlur = p.bgImageBlur.coerceIn(0f, 1f),
            bgImageScale = p.bgImageScale,
        )
        // 去重：combine 每次发射都 new 一个 DisplayPrefs，值实际没变（如写库后回读同值）
        // 时下游两个 VM 不必整体重算
    }.distinctUntilChanged()

    /**
     * 内置主题配色并入基础偏好（DESIGN §3.3）：外层 combine 五参已满员，
     * 单独并一层，copy 改动只在配色真的变了时透传（data class equals 去重）。
     *
     * **共享热流**（shareIn）：上游是 17 条 DataStore 订阅合成的冷流，此前每个收集者
     * （rememberAppHaptics 全应用 60+ 个调用点）都各自重建并订阅整条链；收成共享后
     * 上游只有一份。`replay = 1` 让后到的订阅者立即拿到最近值；首个订阅者仍等
     * DataStore 首发射，与冷流语义一致（首帧由各调用点的 initialValue 兜底）。
     * `WhileSubscribed(5s)`：无人收集 5 秒后释放上游，不常驻。
     */
    val displayPrefs: Flow<DisplayPrefs> = combine(
        baseDisplayPrefs,
        prefs.themePalette,
    ) { base, palette -> base.copy(themePalette = palette) }
        .distinctUntilChanged()
        .shareIn(scope = Graph.appScope, started = SharingStarted.WhileSubscribed(5_000), replay = 1)

    /**
     * 触感开关的轻流：[edu.jxslu.schedule.ui.common.rememberAppHaptics] 的 60+ 调用点
     * 只关心这一个布尔，不该为它拖整份 [DisplayPrefs]（收共享热流后再收一层轻流，
     * 其他偏好变化完全不经过触感收集器）。
     */
    val hapticsEnabled: Flow<Boolean> = prefs.hapticsEnabled

    /**
     * 壳层切片（[edu.jxslu.schedule.MainActivity] 的 JuwApp）：课表背景图五参数，
     * 是壳层唯一消费的字段。壳收切片而不是整份 [DisplayPrefs]——
     * NavHost 挂在壳里，此前拖一根字号滑杆都会重组整个壳。
     */
    val appShellPrefs: Flow<AppShellPrefs> = displayPrefs.map { p ->
        AppShellPrefs(
            bgImageName = p.bgImageName,
            bgImageOpacity = p.bgImageOpacity,
            bgImageDim = p.bgImageDim,
            bgImageBlur = p.bgImageBlur,
            bgImageScale = p.bgImageScale,
        )
    }.distinctUntilChanged()

    /**
     * 今日页快捷方式（DESIGN §3.8/§4.16）。全局 DataStore 项，与当前课表无关，直接透传 store。
     */
    val shortcutSettings: Flow<ShortcutSettings> = prefs.shortcutSettings

    /**
     * 今日页顶栏天气提示开关（DESIGN §3.29）。**默认关**，单独一条流——
     * `baseDisplayPrefs` 的 combine 已满员（AGENTS.md 口径），不进合并模型；
     * 消费方是通用设置页与今日页 ViewModel，各自 collect 即可。
     */
    val weatherEnabled: Flow<Boolean> = prefs.weatherEnabled

    /**
     * 应用内悬浮球（DESIGN §3.1）：开关 + 归一化位置。**独立单流**——
     * `baseDisplayPrefs` 的 combine 已满员（AGENTS.md 口径），不进合并模型；
     * 消费方 = 外壳（`MainActivity.JuwApp`）与通用设置页，各自 collect。
     */
    val floatingBallEnabled: Flow<Boolean> = prefs.floatingBallEnabled

    /** 悬浮球归一化位置（x/y ∈ [0,1]，缺省 = 右下角）。 */
    val floatingBallPosition: Flow<FloatingBall.Position> = prefs.floatingBallPosition

    // ------------------------------------------------------------------
    // 偏好写入：视图偏好写全局 DataStore 键，主题等全局项同层
    // ------------------------------------------------------------------

    // 快捷方式（DESIGN §4.16）：开关与条目列表统一走 store 的 updateShortcuts
    suspend fun setShortcutsEnabled(value: Boolean) = prefs.setShortcutsEnabled(value)

    /** 今日页开水卡片开关（DESIGN §3.3 底部固定区）。 */
    suspend fun setWaterCardEnabled(value: Boolean) = prefs.setWaterCardEnabled(value)

    /** 今日页趣智校园卡片开关（DESIGN §4.30）。 */
    suspend fun setQzxyCardEnabled(value: Boolean) = prefs.setQzxyCardEnabled(value)

    /** 今日页共享单车卡开关（DESIGN §3.9）。 */
    suspend fun setEbikeCardEnabled(value: Boolean) = prefs.setEbikeCardEnabled(value)

    /** 今日页顶栏天气提示开关（DESIGN §3.29）。 */
    suspend fun setWeatherEnabled(value: Boolean) = prefs.setWeatherEnabled(value)

    /** 应用内悬浮球开关（DESIGN §3.1）。 */
    suspend fun setFloatingBallEnabled(value: Boolean) = prefs.setFloatingBallEnabled(value)

    /** 悬浮球位置（DESIGN §3.1）。 */
    suspend fun setFloatingBallPosition(position: FloatingBall.Position) =
        prefs.setFloatingBallPosition(position)

    /** 今日页校园卡付款码卡开关（DESIGN §3.10）。凭证的写/清走 YktCredentialStore，不在这里。 */
    suspend fun setCampusCardEnabled(value: Boolean) = prefs.setCampusCardEnabled(value)

    /** 今日页底部抽屉展开态（DESIGN §3.3）。 */
    suspend fun updateShortcuts(transform: (List<ShortcutItem>) -> List<ShortcutItem>) =
        prefs.updateShortcuts(transform)

    /** 视图偏好统一写全局键（2026-09-19 起不再按课表分，DESIGN §4.9）。 */
    private suspend fun updateViewPrefs(transform: (TimetablePrefs) -> TimetablePrefs) =
        prefs.updateViewPrefs(transform)

    suspend fun setShowSaturday(value: Boolean) =
        updateViewPrefs { it.copy(showSaturday = value, showWeekend = value && it.showSunday) }

    suspend fun setShowSunday(value: Boolean) =
        updateViewPrefs { it.copy(showSunday = value, showWeekend = it.showSaturday && value) }

    /** 兼容入口：老调用点一次改两天。新代码请用两个独立 setter。 */
    suspend fun setShowWeekend(value: Boolean) =
        updateViewPrefs { it.copy(showSaturday = value, showSunday = value, showWeekend = value) }

    suspend fun setShowAtSign(value: Boolean) = updateViewPrefs { it.copy(showAtSign = value) }

    suspend fun setTapBlankToAdd(value: Boolean) = updateViewPrefs { it.copy(tapBlankToAdd = value) }

    suspend fun setShowNonCurrentWeek(value: Boolean) = updateViewPrefs { it.copy(showNonCurrentWeek = value) }

    suspend fun setCourseFilter(value: CourseFilter) =
        updateViewPrefs { it.copy(courseFilter = value) }

    /** null = 清掉覆盖，回到跟随（口径同 gridFontDp）。 */
    suspend fun setGridFontDp(value: Float?) = updateViewPrefs { it.copy(gridFontDp = value) }

    suspend fun setGridRoomDp(value: Float?) = updateViewPrefs { it.copy(gridRoomDp = value) }

    suspend fun setGridTeacherDp(value: Float?) = updateViewPrefs { it.copy(gridTeacherDp = value) }

    /** 时间轴字号；null = 跟随（同课名口径，独立于课名）。 */
    suspend fun setGridRailDp(value: Float?) = updateViewPrefs { it.copy(gridRailDp = value) }

    /** 月份 / 日期表头字号；null = 跟随（同上）。 */
    suspend fun setGridDateDp(value: Float?) = updateViewPrefs { it.copy(gridDateDp = value) }

    suspend fun setRowHeightScale(value: Float) = updateViewPrefs { it.copy(rowHeightScale = value) }

    /** 左侧时间轴栏宽（dp）；写入前夹进滑块范围，防脏数据撑爆布局。 */
    suspend fun setRailWidthDp(value: Float) = updateViewPrefs {
        it.copy(railWidthDp = value.coerceIn(TimetablePrefs.MinRailWidthDp, TimetablePrefs.MaxRailWidthDp))
    }

    /** 顶部表头高度（dp）；写入前夹进滑块范围。 */
    suspend fun setDayHeaderHeightDp(value: Float) = updateViewPrefs {
        it.copy(
            dayHeaderHeightDp = value.coerceIn(
                TimetablePrefs.MinDayHeaderHeightDp,
                TimetablePrefs.MaxDayHeaderHeightDp,
            ),
        )
    }

    suspend fun setCellRadiusDp(value: Float) = updateViewPrefs { it.copy(cellRadiusDp = value) }

    suspend fun setCellOpacity(value: Float) = updateViewPrefs { it.copy(cellOpacity = value) }

    suspend fun setCellCenterH(value: Boolean) = updateViewPrefs { it.copy(cellCenterH = value) }

    suspend fun setCellCenterV(value: Boolean) = updateViewPrefs { it.copy(cellCenterV = value) }

    suspend fun setShowTeacher(value: Boolean) = updateViewPrefs { it.copy(showTeacher = value) }

    suspend fun setShowNowLine(value: Boolean) = updateViewPrefs { it.copy(showNowLine = value) }

    suspend fun setShowCellBorder(value: Boolean) = updateViewPrefs { it.copy(showCellBorder = value) }

    suspend fun setShowGridLines(value: Boolean) = updateViewPrefs { it.copy(showGridLines = value) }

    // ---- 课表页背景图（DESIGN §4.21）：文件名之外的三个数值写入前夹取，防脏数据撑爆绘制 ---

    /**
     * 设置背景图文件名；同时把三个数值参数重置回默认（不透明度 1、无遮罩、无模糊）。
     *
     * 根因：换一张图后旧图的参数不该沿用——上一条是「压暗 80% 的深色图」，
     * 换成亮色图后整页糊成一片，用户还得自己一项项调回去。每次选图从干净状态起步。
     */
    suspend fun setBackgroundImage(fileName: String?) = updateViewPrefs {
        if (fileName == null) {
            it.copy(bgImageName = null)
        } else {
            it.copy(
                bgImageName = fileName,
                bgImageOpacity = 1f,
                bgImageDim = 0f,
                bgImageBlur = 0f,
            )
        }
    }

    suspend fun setBgImageOpacity(value: Float) = updateViewPrefs {
        it.copy(bgImageOpacity = value.coerceIn(TimetablePrefs.MinBgImageOpacity, 1f))
    }

    suspend fun setBgImageDim(value: Float) = updateViewPrefs {
        it.copy(bgImageDim = value.coerceIn(0f, TimetablePrefs.MaxBgImageDim))
    }

    suspend fun setBgImageBlur(value: Float) = updateViewPrefs {
        it.copy(bgImageBlur = value.coerceIn(0f, 1f))
    }

    suspend fun setBgImageScale(value: BgScale) = updateViewPrefs { it.copy(bgImageScale = value) }

    suspend fun setThemeMode(value: ThemeMode) = prefs.setThemeMode(value)

    /** 动态取色开关（全局，默认开）。 */
    suspend fun setDynamicColor(value: Boolean) = prefs.setDynamicColor(value)

    /** 内置主题配色（DESIGN §3.3）。动态取色开着时被 UI 置灰提示、实际不生效。 */
    suspend fun setThemePalette(value: ThemePalette) = prefs.setThemePalette(value)

    /** 悬浮导航栏（DESIGN §4.22）：底栏半透明磨砂，课表背景图透到屏幕底部。默认关。 */
    suspend fun setFloatingNavBar(value: Boolean) = prefs.setFloatingNavBar(value)

    /** 百宝箱开关（DESIGN §4.47）：今日页「+」下拉里的图片工具入口，默认开。 */
    suspend fun setToolboxEnabled(value: Boolean) = prefs.setToolboxEnabled(value)

    /** 启动页（DESIGN §3.3）。重启应用后生效（读口径见 MainActivity 的启动期冻结）。 */
    suspend fun setStartPage(value: StartPage) = prefs.setStartPage(value)

    /** 触感反馈开关（全局）。 */
    suspend fun setHapticsEnabled(value: Boolean) = prefs.setHapticsEnabled(value)

    /** 字体缩放锁定（「固定字体大小」，默认开）。即时生效，无重启要求。 */
    suspend fun setFontScaleLocked(value: Boolean) = prefs.setFontScaleLocked(value)

    /** 开水双击确认（全局，默认开）。 */
    suspend fun setWaterRequireDoubleClick(value: Boolean) = prefs.setWaterRequireDoubleClick(value)

    /** 日历同步提前提醒分钟数（全局，0 = 不提醒），供设置页与同步动作共用（DESIGN §4.12）。 */
    val calendarReminderMinutes: Flow<Int> = prefs.calendarReminderMinutes

    suspend fun setCalendarReminderMinutes(value: Int) = prefs.setCalendarReminderMinutes(value)

    // ---- 上课提醒（全局，DESIGN §3.7；调度在 ui/reminder，数据口径在这里） ----

    val reminderEnabled: Flow<Boolean> = prefs.reminderEnabled

    val reminderLeadMinutes: Flow<Int> = prefs.reminderLeadMinutes

    /**
     * 通知强提醒（2026-10-06，通用设置；语义见 `DisplayPrefsStore.strongReminderVibration`）：
     * 上课 / 作业截止 / 免费时长三类提醒发出即直接震动。**单独一条流，不进
     * `baseDisplayPrefs` 合并模型**（combine 已满员）——消费方是提醒链（receiver/
     * worker 里的 suspend 读）与通用设置页，各自 `first()` / collect 即可。
     */
    val strongReminderVibration: Flow<Boolean> = prefs.strongReminderVibration

    // ---- 免扰时段（quiet hours，DESIGN §3.7）：两条独立单流（combine 已满员）。
    // 消费方是提醒链（Gate 拦截判定）与免扰设置页，各自 first() / collect 即可。

    /** 免扰总开关，默认开。关 = 通知回到立即发送，时段配置保留。 */
    val quietHoursEnabled: Flow<Boolean> = prefs.quietHoursEnabled

    /** 免扰时段全表（含停用窗口），缺键 / 坏 JSON 回预置两时段。 */
    val quietHoursWindows: Flow<List<QuietWindow>> = prefs.quietHoursWindows

    suspend fun setReminderEnabled(value: Boolean) = prefs.setReminderEnabled(value)

    suspend fun setStrongReminderVibration(value: Boolean) =
        prefs.setStrongReminderVibration(value)

    suspend fun setQuietHoursEnabled(value: Boolean) = prefs.setQuietHoursEnabled(value)

    suspend fun setQuietHoursWindows(windows: List<QuietWindow>) =
        prefs.setQuietHoursWindows(windows)

    // ---- 启动自动检查更新（DESIGN §3.3，2026-10-08）：开关 + 已忽略版本号。
    // 独立单流（combine 已满员，口径同上）。消费方 = 冷启动检查链（first()）
    // 与关于页开关（collect + setter）。

    /** 启动自动检查更新开关，默认开。关 = 冷启动检查链零网络空跑。 */
    val autoUpdateCheckEnabled: Flow<Boolean> = prefs.autoUpdateCheckEnabled

    suspend fun setAutoUpdateCheckEnabled(value: Boolean) =
        prefs.setAutoUpdateCheckEnabled(value)

    /** 「已忽略的版本号」（空 = 没有忽略；**手动检查不读它**）。 */
    suspend fun ignoredUpdateVersion(): String = prefs.ignoredUpdateVersion()

    suspend fun setIgnoredUpdateVersion(value: String) = prefs.setIgnoredUpdateVersion(value)

    // ---- AI 调课（DESIGN §4.39，2026-10-06）----
    // 四条偏好各自单独成流，不进 baseDisplayPrefs（combine 已满员）。
    // API Key 不在 Repository 暴露：它走 AiCredentialStore，只由 AI 页面/配置页直接读。

    /** 服务地址（默认 DeepSeek 兼容端点；空串 = 用户清空待重填）。 */
    val aiBaseUrl: Flow<String> = prefs.aiBaseUrl

    /** 模型名（默认 [AiScheduleConfig.DEFAULT_MODEL]，旧预填名在 prefs 读取处已归一）。 */
    val aiModel: Flow<String> = prefs.aiModel

    /** 教师名脱敏开关，默认开。 */
    val aiMaskTeacher: Flow<Boolean> = prefs.aiMaskTeacher

    /** 首次隐私确认是否已完成。 */
    val aiPrivacyAcknowledged: Flow<Boolean> = prefs.aiPrivacyAcknowledged

    suspend fun setAiBaseUrl(value: String) = prefs.setAiBaseUrl(value)

    suspend fun setAiModel(value: String) = prefs.setAiModel(value)

    suspend fun setAiMaskTeacher(value: Boolean) = prefs.setAiMaskTeacher(value)

    suspend fun setAiPrivacyAcknowledged(value: Boolean) = prefs.setAiPrivacyAcknowledged(value)

    /** 思考档位（none / low / high / max，§4.39）。 */
    val aiThinkingEffort: Flow<String> = prefs.aiThinkingEffort

    /** 是否在页面上显示思考过程（§4.39）。 */
    val aiShowReasoning: Flow<Boolean> = prefs.aiShowReasoning

    suspend fun setAiThinkingEffort(value: String) = prefs.setAiThinkingEffort(value)

    suspend fun setAiShowReasoning(value: Boolean) = prefs.setAiShowReasoning(value)

    /** 该模型是否支持图片识别（§4.39），默认关。见 `DisplayPrefsStore.aiSupportsImage`。 */
    val aiSupportsImage: Flow<Boolean> = prefs.aiSupportsImage

    suspend fun setAiSupportsImage(value: Boolean) = prefs.setAiSupportsImage(value)

    suspend fun setReminderLeadMinutes(value: Int) = prefs.setReminderLeadMinutes(value)

    suspend fun reminderLastKey(kind: ReminderKeyKind): String? = prefs.reminderLastKey(kind)

    suspend fun setReminderLastKey(kind: ReminderKeyKind, key: String) =
        prefs.setReminderLastKey(kind, key)

    // ---- 作业截止提醒（全局，DESIGN §3.11；与上课提醒共用闹钟与核对 Worker） ----

    val homeworkReminderEnabled: Flow<Boolean> = prefs.homeworkReminderEnabled

    suspend fun setHomeworkReminderEnabled(value: Boolean) = prefs.setHomeworkReminderEnabled(value)

    suspend fun homeworkRemindedKeys(): Set<String> = prefs.homeworkRemindedKeys()

    suspend fun addHomeworkRemindedKey(key: String) = prefs.addHomeworkRemindedKey(key)

    // ---- 余额提醒（全局，DESIGN §3.10 / §3.13；调度在 ui/reminder/BalanceAlertReminder） ----

    /** 寝室电费提醒开关（默认关：通知是打扰型能力，用户显式开启）。 */
    val powerAlertEnabled: Flow<Boolean> = prefs.powerAlertEnabled

    /** 寝室电费提醒阈值（元，10–80 步长 5）。 */
    val powerAlertYuan: Flow<Int> = prefs.powerAlertYuan

    /** 一卡通余额提醒开关（默认关）。 */
    val yktAlertEnabled: Flow<Boolean> = prefs.yktAlertEnabled

    /** 一卡通余额提醒阈值（元，10–50 步长 5）。 */
    val yktAlertYuan: Flow<Int> = prefs.yktAlertYuan

    suspend fun setPowerAlertEnabled(value: Boolean) = prefs.setPowerAlertEnabled(value)

    suspend fun setPowerAlertYuan(value: Int) = prefs.setPowerAlertYuan(value)

    suspend fun setYktAlertEnabled(value: Boolean) = prefs.setYktAlertEnabled(value)

    suspend fun setYktAlertYuan(value: Int) = prefs.setYktAlertYuan(value)


    suspend fun setCurrentTimetable(id: Long) = prefs.setCurrentTimetable(id)

    /** null = 清掉默认配置源，回退内置默认。 */
    suspend fun setDefaultConfigSource(id: Long?) = prefs.setDefaultConfigSource(id)

    suspend fun defaultConfigSourceId(): Long? = prefs.defaultConfigSourceId.first()

    // ------------------------------------------------------------------
    // 课表管理
    // ------------------------------------------------------------------

    /**
     * 新建课表。配置（学期/作息/作息自定义标记）按用户拍板用**引用型**默认：
     * 实时拷贝 [copyFromId]（缺省取全局默认配置源，再缺省用内置默认）当时的值。
     * 显示偏好 2026-09-19 起全局，不再随配置拷贝。
     */
    suspend fun createTimetable(name: String, copyFromId: Long? = null): Long {
        val sourceId = copyFromId ?: prefs.defaultConfigSourceId.first()
        val source = sourceId?.let { db.timetableDao().getById(it) }
        val sortOrder = (db.timetableDao().getAll().maxOfOrNull { it.sortOrder } ?: 0) + 1
        return createTimetableInternal(
            name = name.trim().ifEmpty { "新课表" },
            source = source,
            sortOrder = sortOrder,
        )
    }

    /** 复制课表：除配置外连同课程一起拷贝。 */
    suspend fun duplicateTimetable(id: Long): Long? {
        val source = db.timetableDao().getById(id) ?: return null
        val courses = getTimetableCourses(id)
        val newId = createTimetableInternal(
            name = "${source.name} 副本",
            source = source,
            sortOrder = (db.timetableDao().getAll().maxOfOrNull { it.sortOrder } ?: 0) + 1,
        )
        db.courseDao().insertAll(
            courses.map { CourseEntity.fromDomain(it).copy(id = 0, timetableId = newId) },
        )
        // 日期覆盖（调休 / 停课）也要跟着复制：漏了它，副本在调休日照常显示课
        val overrides = db.dateOverrideDao().getForTimetable(id)
        if (overrides.isNotEmpty()) {
            db.dateOverrideDao().insertAll(
                overrides.map { it.copy(id = 0, timetableId = newId) },
            )
        }
        return newId
    }

    private suspend fun createTimetableInternal(
        name: String,
        source: TimetableEntity?,
        sortOrder: Int,
    ): Long {
        val id = db.timetableDao().upsert(
            TimetableEntity(
                name = name,
                createdAt = System.currentTimeMillis(),
                sortOrder = sortOrder,
                slotsCustomized = source?.slotsCustomized ?: false,
                // prefs_json 列已退役（显示偏好全局化）：只写内置默认占位，读写都走 DataStore
                prefsJson = TimetablePrefs().encode(),
            ),
        )
        if (source != null) {
            // 引用型拷贝：拿源课表「当时」的学期与作息快照
            db.semesterConfigDao().getForTimetable(source.id)?.let {
                db.semesterConfigDao().upsert(it.copy(timetableId = id))
            }
            db.timeSlotDao().upsertAll(
                db.timeSlotDao().getForTimetable(source.id).map { it.copy(timetableId = id) },
            )
        } else {
            db.semesterConfigDao().upsert(
                SemesterConfigEntity.fromDomain(id, DefaultData.defaultSemester),
            )
            writeDefaultTimeSlots(id)
        }
        return id
    }

    suspend fun renameTimetable(id: Long, name: String) {
        val entity = db.timetableDao().getById(id) ?: return
        db.timetableDao().upsert(entity.copy(name = name.trim().ifEmpty { entity.name }))
    }

    /**
     * 删除课表。返回 false 表示拒绝执行：至少要保留一张课表。
     * 删除当前课表时自动切到剩下第一张；删除默认配置源时清回内置默认。
     */
    suspend fun deleteTimetable(id: Long): Boolean {
        if (db.timetableDao().count() <= 1) return false
        db.courseDao().clearForTimetable(id)
        db.timeSlotDao().clearForTimetable(id)
        db.semesterConfigDao().deleteForTimetable(id)
        // 日期覆盖跟着课表走：课表没了，挂在它上面的调休/停课一起清（§4.40）
        db.dateOverrideDao().deleteForTimetable(id)
        db.timetableDao().delete(id)
        clearScheduleBaseline(id)
        if (prefs.defaultConfigSourceId.first() == id) {
            prefs.setDefaultConfigSource(null)
        }
        if (prefs.currentTimetableId.first() == id) {
            db.timetableDao().getAll().firstOrNull()?.let { prefs.setCurrentTimetable(it.id) }
        }
        return true
    }

    suspend fun timetableCourseCount(id: Long): Int = db.courseDao().countForTimetable(id)

    // ------------------------------------------------------------------
    // 启动初始化与迁移
    // ------------------------------------------------------------------

    suspend fun ensureDefaults() {
        // 课表兜底：全新安装建第一张；升级安装已由 MIGRATION_2_3 插入 id=1
        if (db.timetableDao().count() == 0) {
            createTimetableInternal(name = "我的课表", source = null, sortOrder = 0)
        }
        globalizeViewPrefs()
        migrateTimeSlotSchema()
        rebalanceCourseColorsIfColliding()
    }

    /**
     * 显示偏好全局化一次性迁移（DESIGN §4.9，2026-09-19）：把显示偏好搬进 DataStore
     * `view_prefs_json`，此后所有课表共用一份。
     *
     * 数据源优先级：
     * - v2 直升用户：旧全局键还在（`prefs_migrated` 未落）→ 以 `legacyViewPrefs()` 为准
     *   （此时课表行的 prefs_json 是 MIGRATION_2_3 写的内置默认，不能代表用户设置）；
     * - v3 时期用户：旧全局键已清（`legacyViewPrefs()` 返回 null）→ 取**当前课表行**的
     *   `prefs_json`（保留用户正在使用的观感）；
     * - 全新安装：两者皆默认值，写入等价默认。
     *
     * 幂等靠 `view_prefs_globalized` 标记；旧「改过作息」标记仍映射到课表 1（作息仍课表级）。
     */
    private suspend fun globalizeViewPrefs() {
        if (prefs.viewPrefsGlobalized()) return
        val source = prefs.legacyViewPrefs()
            ?: currentTimetableId.first()
                .let { db.timetableDao().getById(it) }
                ?.let { TimetablePrefs.decode(it.prefsJson) }
            ?: TimetablePrefs()
        prefs.setInitialViewPrefs(source)
        if (prefs.legacySlotCustomized()) {
            db.timetableDao().getById(1)?.let {
                db.timetableDao().upsert(it.copy(slotsCustomized = true))
            }
        }
        prefs.setPrefsMigrated(true)
        prefs.setViewPrefsGlobalized()
    }

    /**
     * 根因：老版本的节次表是 5 条「大节」（08:00/10:00/…），而课程存的是小节号 1–11，
     * 语义不一致会让第 7 行以后的课落到网格外。这里按版本号做一次性覆盖。
     * 用 DataStore 记版本而不是改 Room 版本号：节次表结构没变，只是数据语义变了。
     *
     * v3 起作息表按课表各一份：迁移对**未自定义**的每张课表逐张覆盖；
     * 旧全局 slotCustomized 标记已在 globalizeViewPrefs 映射到课表 1。
     */
    private suspend fun migrateTimeSlotSchema() {
        if (prefs.slotSchemaVersion() >= DefaultData.SLOT_SCHEMA_VERSION) return
        db.timetableDao().getAll()
            .filter { !it.slotsCustomized }
            .forEach { writeDefaultTimeSlots(it.id) }
        prefs.setSlotSchemaVersion(DefaultData.SLOT_SCHEMA_VERSION)
    }

    private suspend fun writeDefaultTimeSlots(timetableId: Long) {
        db.timeSlotDao().upsertAll(
            DefaultData.defaultTimeSlots.map {
                TimeSlotEntity(timetableId, it.number, it.startTime, it.endTime)
            },
        )
    }

    /**
     * 既有课程撞色自愈（按课表逐张做：配色以课表为 scope，跨课表的名次互不相干）。
     *
     * 根因：调色板 12→16 之前入库的课按 12 取模分配，不同课名超过 12 个时必然回绕撞色。
     * 色值存在数据库里，扩色板救不了旧数据，所以在启动时检测一次：
     * 若按「课名排序名次」重排能减少撞色才整体重排；确定性映射保证幂等。
     */
    private suspend fun rebalanceCourseColorsIfColliding() {
        db.timetableDao().getAll().forEach { t ->
            val courses = getTimetableCourses(t.id)
            if (courses.isEmpty()) return@forEach

            fun collisionCount(mapping: Map<String, Int>): Int =
                mapping.values.groupingBy { it }.eachCount().count { it.value > 1 }

            val current = courses.groupBy { it.name }
                .mapValues { (_, group) -> group.first().colorIndex }
            val target = ScheduleCalculator.colorIndexesBySortedName(current.keys)

            if (collisionCount(target) >= collisionCount(current)) return@forEach

            courses
                .filter { it.colorIndex != target[it.name] }
                .forEach {
                    db.courseDao().upsert(
                        CourseEntity.fromDomain(it.copy(colorIndex = target[it.name] ?: it.colorIndex))
                            .copy(timetableId = t.id),
                    )
                }
        }
    }

    // ------------------------------------------------------------------
    // 课程 CRUD（scope = 当前课表）
    // ------------------------------------------------------------------

    private suspend fun getTimetableCourses(timetableId: Long): List<Course> =
        db.courseDao().getForTimetable(timetableId).map { it.toDomain() }

    private suspend fun usedColorIndexes(timetableId: Long): List<Int> =
        db.courseDao().getForTimetable(timetableId).map { it.colorIndex }

    suspend fun upsertCourse(course: Course): Long {
        val timetableId = currentTimetableId.first()
        // 只有新增的课才重新配色；已有课程保留原色，避免改一门课把整屏颜色打乱
        val target = if (course.id > 0 || course.colorIndex != 0) {
            course
        } else {
            course.copy(colorIndex = ScheduleCalculator.nextColorIndex(usedColorIndexes(timetableId)))
        }
        return db.courseDao().upsert(CourseEntity.fromDomain(target).copy(timetableId = timetableId))
    }

    suspend fun deleteCourse(course: Course) {
        db.courseDao().delete(CourseEntity.fromDomain(course))
    }

    suspend fun clearCourses() {
        val ttId = currentTimetableId.first()
        db.courseDao().clearForTimetable(ttId)
        // 课都没了，挂在日期上的调休/停课也没有意义（§4.40）
        db.dateOverrideDao().deleteForTimetable(ttId)
        // 用户主动清空 = 与教务的一致状态也被清掉：基线一并清，检查链下次按首跑重建
        clearScheduleBaseline(ttId)
    }

    suspend fun replaceAllCourses(courses: List<Course>, timetableId: Long? = null) {
        val ttId = timetableId ?: currentTimetableId.first()
        // 覆盖导入会清表重建：先把旧行的备注按 mergeKey 搬回新行，
        // 否则用户写过的课程备注会在一次"覆盖导入"后凭空消失（DESIGN §4.3「备注的存活口径」）
        val existing = getTimetableCourses(ttId)
        val withRemarks = courseRemarksCarriedOver(existing, courses)
        db.courseDao().clearForTimetable(ttId)
        db.courseDao().insertAll(
            withSortedNameColors(withRemarks).map { CourseEntity.fromDomain(it).copy(timetableId = ttId) },
        )
    }

    /**
     * 撤销回滚专用：按**原 id 与原颜色**逐门恢复。不能用 [replaceAllCourses] 兜——
     * 那会重排序取色，撤销后整屏颜色就变了。
     */
    suspend fun restoreCourses(courses: List<Course>, timetableId: Long? = null) {
        val ttId = timetableId ?: currentTimetableId.first()
        db.courseDao().insertAll(
            courses.map { CourseEntity.fromDomain(it).copy(timetableId = ttId) },
        )
    }

    suspend fun mergeCourses(courses: List<Course>, timetableId: Long? = null): Int {
        val ttId = timetableId ?: currentTimetableId.first()
        val existing = getTimetableCourses(ttId)
        val keys = existing.map { it.mergeKey() }.toHashSet()
        val used = existing.map { it.colorIndex }.toMutableList()
        var added = 0
        for (c in courses) {
            val key = c.mergeKey()
            if (key in keys) continue
            val colored = c.copy(colorIndex = ScheduleCalculator.nextColorIndex(used))
            db.courseDao().upsert(CourseEntity.fromDomain(colored).copy(timetableId = ttId))
            used += colored.colorIndex
            keys += key
            added++
        }
        return added
    }

    /**
     * 整批课程按课程名排序后的名次取色。
     * 这样同一份课表每次导入/覆盖出来的颜色完全一致，且 16 门以内不会撞色。
     */
    private fun withSortedNameColors(courses: List<Course>): List<Course> {
        val mapping = ScheduleCalculator.colorIndexesBySortedName(courses.map { it.name })
        return courses.map { c ->
            c.copy(colorIndex = mapping[c.name] ?: ScheduleCalculator.colorIndexFor(c.name))
        }
    }

    /**
     * 教务解析结果入库；merge=false 全量覆盖目标课表。
     * [term] 是本次数据的学年学期，写进目标课表（[Timetable.term]）——它是课程详情查
     * 教材（[textbooksFor]）的钥匙；null 表示这次导入不带学期口径（旧调用路径），不动课表的 term。
     *
     * 写库成功 = 与教务达成了一次一致：收口推进调课检测基线（[refreshBaselineFromDb]），
     * 之后 `ScheduleSync.check` 以它为底与教务比对。所有导入调用点都经过这里，
     * 新入口不需要（也不应该）自己记得写基线。
     */
    suspend fun importParsedCourses(
        courses: List<Course>,
        merge: Boolean,
        timetableId: Long? = null,
        term: String? = null,
    ): Int {
        val ttId = timetableId ?: currentTimetableId.first()
        if (!term.isNullOrBlank()) {
            db.timetableDao().getById(ttId)?.let {
                db.timetableDao().upsert(it.copy(term = term))
            }
        }
        val result = if (merge) mergeCourses(courses, ttId) else {
            // 覆盖导入 = 课表回到教务口径：挂在旧课表上的**日期覆盖**（调休 / 停课，§4.40）
            // 一并清掉。它们是 AI 从通知里落下的本机安排、按旧课表算出来的，
            // 整表重建后已无对应关系；留着就会出现「重导了但课表还是调休后的样子」
            // ——2026-10-06 用户实测报的就是这个。合并导入不清：那条路不重置课表。
            db.dateOverrideDao().deleteForTimetable(ttId)
            replaceAllCourses(courses, ttId)
            courses.size
        }
        refreshBaselineFromDb(ttId)
        return result
    }

    // ------------------------------------------------------------------
    // 教材（DESIGN §4.31）
    // ------------------------------------------------------------------

    /** 按学期整批替换教材（教务口径的快照；事务里先清后插，防中途读到半套）。 */
    suspend fun replaceTextbooks(term: String, books: List<Textbook>) {
        db.withTransaction {
            db.textbookDao().deleteForTerm(term)
            db.textbookDao().insertAll(books.map { TextbookEntity.fromDomain(it) })
        }
    }

    /**
     * 当前课表的学期号流（教材查询的钥匙）；没课表 / 没写过 term 时 null。
     * 教材的两条读路径（[textbooksFor] 按课查 / [textbookTitlesByCourse] 整学期映射）
     * 共用这一份推导，改学期口径只动这里。
     */
    private val currentTermFlow: Flow<String?> =
        combine(currentTimetableId, db.timetableDao().observeAll()) { id, list ->
            list.firstOrNull { it.id == id }?.term
        }.distinctUntilChanged()

    /**
     * 某门课在**当前课表学期**下的教材流。课表没有学期号（旧数据/手工建表）时永远给空——
     * 没有学期钥匙就查教材会串学期（同一门课不同学期教材不同），宁可少显示。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun textbooksFor(courseName: String): Flow<List<Textbook>> =
        currentTermFlow.flatMapLatest { term ->
            if (term.isNullOrBlank()) {
                kotlinx.coroutines.flow.flowOf(emptyList())
            } else {
                db.textbookDao().observeForCourse(term, courseName).map { list ->
                    list.map { it.toDomain() }
                }
            }
        }

    /**
     * 当前课表学期下「课程名 → 教材名列表」的映射流（今日页卡片显示教材名的数据源，
     * 2026-10-07）。与 [textbooksFor] 同一把学期钥匙（[currentTermFlow]），
     * 课表没有学期号时永远给空映射——宁可少显示，不串学期。
     * 多本教材按教务清单顺序（id 序）；查询键只认课程名逐字匹配，与详情弹窗同口径。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val textbookTitlesByCourse: Flow<Map<String, List<String>>> =
        currentTermFlow.flatMapLatest { term ->
            if (term.isNullOrBlank()) {
                flowOf(emptyMap())
            } else {
                db.textbookDao().observeForTerm(term).map { list ->
                    list.map { it.toDomain() }
                        .groupBy(keySelector = { it.courseName }, valueTransform = { it.title })
                }
            }
        }.distinctUntilChanged()

    /**
     * 当前课表学期的全部教材（教材页的数据源，2026-10-20）。
     *
     * 与 [textbooksFor] / [textbookTitlesByCourse] 共用 [currentTermFlow]；没有学期号时
     * 返回空列表，宁可少显示，也不跨学期串数据。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val textbooksForCurrentTerm: Flow<List<Textbook>> =
        currentTermFlow.flatMapLatest { term ->
            if (term.isNullOrBlank()) {
                flowOf(emptyList())
            } else {
                db.textbookDao().observeForTerm(term).map { list ->
                    list.map { it.toDomain() }
                }
            }
        }

    /**
     * 当前课表的学期号（一次性读，教材查询的钥匙）；没课表 / 没写过 term 时 null。
     *
     * 教材原本只在导入路径上用到 term，冷启动补抓（[TextbookSync.backfillIfMissing]，
     * 2026-10-06）需要在没有导入动作时也拿到它。与流版 [currentTermFlow] 同语义：
     * 流版给订阅方，这版给挂起调用点，两处别各写一份推导。
     */
    suspend fun currentTerm(): String? =
        db.timetableDao().getById(currentTimetableId.first())?.term?.takeIf { it.isNotBlank() }

    /** 该学期有没有教材数据（[TextbookSync.backfillIfMissing] 的判据，2026-10-06）。 */
    suspend fun hasTextbooksFor(term: String): Boolean =
        db.textbookDao().getForTerm(term).isNotEmpty()

    // ------------------------------------------------------------------
    // 日期覆盖（调休 / 停课，DESIGN §4.40，2026-10-06）
    // 渲染层叠加，**不写 courses 表**：网格 / 今日页 / 小组件 / 提醒都拿它摊平后
    // 走 ScheduleCalculator.coursesOnDay，口径只有一处。
    // ------------------------------------------------------------------

    /** 当前课表的覆盖流（Room 表 `date_overrides`）。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    val overrides: Flow<List<ScheduleOverride>> = currentTimetableId.flatMapLatest { id ->
        db.dateOverrideDao().observeForTimetable(id).map { list -> list.map { it.toDomain() } }
    }

    /** 非 Flow 读（导出、提醒排期这类一次性快照）。 */
    suspend fun overridesFor(timetableId: Long? = null): List<ScheduleOverride> {
        val ttId = timetableId ?: currentTimetableId.first()
        return db.dateOverrideDao().getForTimetable(ttId).map { it.toDomain() }
    }

    /**
     * 写入日期覆盖，返回**实际插入**各行的自增 id（AI 调课的「撤销」按 id 删回）。
     *
     * 幂等兜底：与已生效安排**等价**（[ScheduleOverrides.sameEffect]）的条目不重复插入，
     * 列表内部重复也只留一条——同一通知被应用两遍时不再留同指令的脏行
     * （解析层已挡一道，这里是写入侧的第二道）。全部重复时返回空列表。
     */
    suspend fun addOverrides(list: List<ScheduleOverride>, timetableId: Long? = null): List<Long> {
        if (list.isEmpty()) return emptyList()
        val ttId = timetableId ?: currentTimetableId.first()
        val existing = db.dateOverrideDao().getForTimetable(ttId).map { it.toDomain() }
        val accepted = mutableListOf<ScheduleOverride>()
        list.forEach { candidate ->
            val duplicate = existing.any { ScheduleOverrides.sameEffect(it, candidate) } ||
                accepted.any { ScheduleOverrides.sameEffect(it, candidate) }
            if (!duplicate) accepted += candidate
        }
        if (accepted.isEmpty()) return emptyList()
        return db.dateOverrideDao().insertAll(accepted.map { DateOverrideEntity.of(it.copy(id = 0), ttId) })
    }

    /** 删掉指定的覆盖（覆盖清单里逐条撤销）。 */
    suspend fun removeOverrides(ids: List<Long>) {
        if (ids.isEmpty()) return
        db.dateOverrideDao().deleteByIds(ids)
    }

    /** 清空某张课表的全部覆盖（删课表 / 清空课程时同步调）。 */
    suspend fun clearOverrides(timetableId: Long? = null) {
        val ttId = timetableId ?: currentTimetableId.first()
        db.dateOverrideDao().deleteForTimetable(ttId)
    }

    // ------------------------------------------------------------------
    // 调课检测基线（DESIGN §4.17，2026-10-04 重立；旧 detect_baselines/detect_reports
    // 两 Room 表已随 2026-09-24 的下线 DROP，基线现走 filesDir JSON，不动 DB 版本）
    // ------------------------------------------------------------------

    /** 当前基线快照（`ScheduleSync.check` 读）。未配置基线存储（构造缺省）时返回空快照。 */
    suspend fun loadScheduleBaseline(): ScheduleBaselineStore.Snapshot =
        baselineStore?.load() ?: ScheduleBaselineStore.Snapshot()

    /**
     * 用「写库后的实际课表」推进基线（过滤考试条目——教务课表页抓不到考试，
     * 留在基线里会把每一场考试报成「教务删除」）。同时清已阅指纹与待处置差异：
     * 基线推进后旧差异整体作废，与旧指纹的比对不再有意义；待处置缓存留着只会让
     * 处置页一直挂着已经不存在的变动（2026-10-07 用户报障：重新导入后仍显示有差异），
     * 而「合并」会把它们照指纹套到刚导入的课表上（静默删课）。同课表才清。
     */
    suspend fun refreshBaselineFromDb(timetableId: Long) {
        val store = baselineStore ?: return
        val term = db.timetableDao().getById(timetableId)?.term.orEmpty()
        val courses = getTimetableCourses(timetableId).filter { it.kind != CourseKind.Exam }
        store.save(ScheduleBaselineStore.snapshotOf(timetableId, term, courses, System.currentTimeMillis()))
        prefs.setScheduleIgnoredFingerprint("")
        if (prefs.schedulePendingChanges()?.timetableId == timetableId) {
            prefs.setSchedulePendingChanges(null)
        }
    }

    /** 用教务抓取结果直接建基线（检查首跑 / 换学期：此刻课表未动，教务现状即「一致状态」）。 */
    suspend fun saveScheduleBaseline(timetableId: Long, term: String, courses: List<Course>) {
        val store = baselineStore ?: return
        store.save(ScheduleBaselineStore.snapshotOf(timetableId, term, courses, System.currentTimeMillis()))
        prefs.setScheduleIgnoredFingerprint("")
    }

    /** 删除课表 / 清空课程时把基线一并清掉（timetableId 归属已失效，留着只会误比对）；待处置差异同清。 */
    suspend fun clearScheduleBaseline(timetableId: Long) {
        val store = baselineStore ?: return
        if (store.load().timetableId == timetableId) {
            store.save(ScheduleBaselineStore.Snapshot())
            prefs.setSchedulePendingChanges(null)
        }
    }

    /**
     * [applyScheduleChanges] / [applyLocalChanges] 的结果：
     * [applied] 实际应用条数；[skipped] 匹配不上而跳过（防重复导入）；
     * [entries] 实际生效的条目（按执行顺序），撤销时**倒序**反向操作。
     */
    data class ApplyReport(
        val applied: Int,
        val skipped: Int,
        val entries: List<AppliedEntry> = emptyList(),
    )

    /**
     * 合并差异成功后的基线推进（2026-10-07 复核补）：**在旧基线上套用已应用的改动**，
     * 而不是拿写库后的课表整体重建。
     *
     * 重建会把手动添加 / AI 调课加进来的**本机条目**一起塞进基线——它们不在教务课表里，
     * 下一次检查全被报成「教务删除」，勾选合并就把用户自己的课删了（与 AI 改动
     * 「不推基线」是同一条纪律的两面，见 §4.39）。旧基线缺失 / 不属于目标课表
     * （异常路径）时退回整体重建，保证基线始终可用。
     */
    private suspend fun advanceBaselineFromApplied(timetableId: Long, entries: List<AppliedEntry>) {
        val store = baselineStore ?: return
        val current = store.load()
        val term = db.timetableDao().getById(timetableId)?.term.orEmpty()
        if (current.timetableId != timetableId || current.term != term) {
            refreshBaselineFromDb(timetableId)
            return
        }
        val after = ScheduleDiff.baselineAfter(current.courses.map { it.toCourse() }, entries)
        store.save(ScheduleBaselineStore.snapshotOf(timetableId, term, after, System.currentTimeMillis()))
        prefs.setScheduleIgnoredFingerprint("")
        if (prefs.schedulePendingChanges()?.timetableId == timetableId) {
            prefs.setSchedulePendingChanges(null)
        }
    }

    /**
     * 把用户勾选的调课差异应用到**当前课表**（事务内逐条删/改/增），成功后把基线
     * 推进为「旧基线 + 已应用的改动」。未勾选的条目下一次检查仍会如实报出；
     * 本机条目（手动添加 / AI 调课）不进基线——它们从来不是教务口径的一部分。
     *
     * 这是「与教务达成一致」的合并路径（调课检测处置页）。AI 调课 / 其它本机改动走
     * [applyLocalChanges]：共用同一套写实现，但**完全不碰基线**。
     */
    suspend fun applyScheduleChanges(
        changes: List<ScheduleDiff.Change>,
        acceptedIndexes: Set<Int>,
    ): ApplyReport {
        val (report, ttId) = applyChangeSet(changes, acceptedIndexes)
        advanceBaselineFromApplied(ttId, report.entries)
        return report
    }

    /**
     * AI 调课（或其它本机改动）的**一次落库**：课表改动 + 日期覆盖在**同一个事务**里，
     * 要么全成要么全不动（DESIGN §4.39）。语义**与用户手动改课一致**：
     * 不推进调课检测基线、不清已阅指纹。基线的语义是「上次与教务达成一致的状态」，
     * 本机改动不是与教务的一致——推进它等于把 AI 改动当成教务现状，之后
     * `ScheduleSync.check` 反而报不出教务那边的真实变动。
     *
     * 写实现与 [applyScheduleChanges] 共用 [applyChangeSet]：「合并是唯一的写路径」
     * 指的是不另造写库逻辑，不是不允许两种基线语义。
     *
     * 返回写入的覆盖行 id —— 撤销要按 id 删回去。
     */
    suspend fun applyLocalChanges(
        changes: List<ScheduleDiff.Change>,
        acceptedIndexes: Set<Int>,
        overrides: List<ScheduleOverride> = emptyList(),
        acceptedOverrides: Set<Int> = emptySet(),
    ): LocalApplyResult = db.withTransaction {
        val (report, ttId) = applyChangeSet(changes, acceptedIndexes)
        // 去重：同一条覆盖被勾两次（模型重复给 / 用户手滑）不该写两行——
        // `date_overrides` 没有唯一索引，裸插就是两份，撤销还只删得掉一份
        val picked = overrides.filterIndexed { index, _ -> index in acceptedOverrides }
            .distinctBy { listOf(it.fromDate, it.toDate, it.kind, it.sourceWeek, it.sourceWeekday) }
        val overrideIds = if (picked.isEmpty()) {
            emptyList()
        } else {
            db.dateOverrideDao().insertAll(picked.map { DateOverrideEntity.of(it.copy(id = 0), ttId) })
        }
        LocalApplyResult(report, overrideIds, ttId)
    }

    /**
     * [applyLocalChanges] 的结果：课表部分 + 写入的日期覆盖行 id + **落库时的课表 id**。
     *
     * 记 `timetableId` 是必须的：撤销可能发生在用户切换当前课表之后，
     * 那时再按「当前课表」落库会把课插进**另一张表**。
     */
    data class LocalApplyResult(
        val report: ApplyReport,
        val overrideIds: List<Long>,
        val timetableId: Long,
    )

    /**
     * 唯一写实现：事务内逐条删/改/增，**不碰基线**。返回 (report, timetableId)，
     * 由调用方决定要不要推进基线。
     *
     * 逐条匹配用 [edu.jxslu.schedule.domain.ScheduleDiff.fingerprint]：当前课表里找不到
     * 指纹一致的条目（用户又改过）就跳过而不是硬套，`skipped` 计数交给 UI 汇报。
     * 新增课程带 `id=0` 走 Room 自增并取下一个未用配色；修改课程保留原行 id、配色与备注。
     */
    private suspend fun applyChangeSet(
        changes: List<ScheduleDiff.Change>,
        acceptedIndexes: Set<Int>,
    ): Pair<ApplyReport, Long> = db.withTransaction {
        val ttId = currentTimetableId.first()
        var current = getTimetableCourses(ttId)
        var applied = 0
        var skipped = 0
        val entries = mutableListOf<AppliedEntry>()
        acceptedIndexes.sorted().forEach { index ->
            val change = changes.getOrNull(index) ?: run { skipped++; return@forEach }
            when (change.kind) {
                edu.jxslu.schedule.domain.ScheduleDiff.ChangeKind.REMOVED -> {
                    val old = change.old ?: run { skipped++; return@forEach }
                    val target = current.firstOrNull {
                        ScheduleDiff.fingerprint(it) == ScheduleDiff.fingerprint(old)
                    } ?: run { skipped++; return@forEach }
                    db.courseDao().deleteByIds(listOf(target.id))
                    current = current.filterNot { it.id == target.id }
                    applied++
                    entries += AppliedEntry(
                        kind = edu.jxslu.schedule.domain.ScheduleDiff.ChangeKind.REMOVED,
                        before = target,
                    )
                }

                edu.jxslu.schedule.domain.ScheduleDiff.ChangeKind.MODIFIED -> {
                    val old = change.old ?: run { skipped++; return@forEach }
                    val new = change.new ?: run { skipped++; return@forEach }
                    val target = current.firstOrNull {
                        ScheduleDiff.fingerprint(it) == ScheduleDiff.fingerprint(old)
                    } ?: run { skipped++; return@forEach }
                    // 与预览共用同一份收口（ScheduleDiff.appliedCopy），
                    // 否则「批准的预览」与「落库结果」会不一致
                    val merged = ScheduleDiff.appliedCopy(new, target)
                    db.courseDao().upsert(CourseEntity.fromDomain(merged).copy(timetableId = ttId))
                    current = current.map { if (it.id == target.id) merged else it }
                    applied++
                    entries += AppliedEntry(
                        kind = edu.jxslu.schedule.domain.ScheduleDiff.ChangeKind.MODIFIED,
                        before = target,
                        after = merged,
                    )
                }

                edu.jxslu.schedule.domain.ScheduleDiff.ChangeKind.ADDED -> {
                    val course = change.new ?: run { skipped++; return@forEach }
                    // 防重复：同一课块且周次有交集才算重复（与预览、冲突提示同一把钥匙）；
                    // 周次不相交的同槽位课块是补课 / 回课时换教室，要另插一行
                    if (ScheduleDiff.duplicateOf(course, current) != null) {
                        skipped++
                    } else {
                        val colored = course.copy(
                            id = 0,
                            colorIndex = ScheduleCalculator.nextColorIndex(current.map { it.colorIndex }),
                        )
                        val id = db.courseDao().upsert(CourseEntity.fromDomain(colored).copy(timetableId = ttId))
                        current = current + colored.copy(id = id)
                        applied++
                        entries += AppliedEntry(
                            kind = edu.jxslu.schedule.domain.ScheduleDiff.ChangeKind.ADDED,
                            after = colored.copy(id = id),
                        )
                    }
                }
            }
        }
        ApplyReport(applied = applied, skipped = skipped, entries = entries) to ttId
    }

    /**
     * 撤销一次 [applyLocalChanges] / [applyScheduleChanges] 的**课表部分**（DESIGN §4.39）。
     *
     * 倒序反向执行（后落库的先撤，避免同门课被改两次时撤错行）：
     * - ADDED → 删掉写入的那一行（按 [AppliedEntry.after] 的 id）；
     * - MODIFIED → 用 [AppliedEntry.before] 覆盖回去（带原 id）；
     * - REMOVED → 把 [AppliedEntry.before] 原样插回（带原 id，此时 id 已空出来）。
     *
     * 单个事务：要么全撤回来，要么一行不动。**不碰基线**——与写入同语义
     * （AI 改动与手动改课同类，基线是「与教务一致的状态」）。
     */
    suspend fun undoApplied(entries: List<AppliedEntry>, timetableId: Long? = null): Int {
        if (entries.isEmpty()) return 0
        return db.withTransaction {
            val ttId = timetableId ?: currentTimetableId.first()
            // 目标课表已删除（用户先删了课表再撤销）：反向写会向不存在的 timetableId
            // 塞孤儿行——任何界面都查不到、也没有清理路径。整批按「跳过」处理，
            // 正常路径由调用方（AI 页 / 记录页）先一步拒绝并说清。
            if (db.timetableDao().getById(ttId) == null) return@withTransaction entries.size
            val current = getTimetableCourses(ttId)
            val plan = ScheduleUndo.plan(entries, current)
            plan.actions.forEach { action ->
                when (action) {
                    is ScheduleUndo.Action.Delete -> db.courseDao().deleteByIds(listOf(action.id))
                    is ScheduleUndo.Action.Upsert ->
                        db.courseDao().upsert(CourseEntity.fromDomain(action.course).copy(timetableId = ttId))
                }
            }
            plan.skipped
        }
    }

    /** 现存课表 id 集合：撤销前核对目标课表是否还在（AI 调课页与「调课记录」页共用）。 */
    suspend fun timetableIds(): Set<Long> = db.timetableDao().getAll().map { it.id }.toSet()

    /**
     * 撤销一次 [applyLocalChanges]：课表反向 + 删掉那次写入的日期覆盖行，**同一个事务**。
     * 与写入同语义，**不碰基线**。
     */
    suspend fun undoLocalChanges(
        entries: List<AppliedEntry>,
        overrideIds: List<Long>,
        timetableId: Long? = null,
    ): Int {
        if (entries.isEmpty() && overrideIds.isEmpty()) return 0
        return db.withTransaction {
            val skipped = undoApplied(entries, timetableId)
            if (overrideIds.isNotEmpty()) db.dateOverrideDao().deleteByIds(overrideIds)
            skipped
        }
    }

    /**
     * 调课（DESIGN §4.11）：把 [fromWeek] 周 [fromDay] 的课按 [mode] 调整到 [toWeek] 周 [toDay]。
     *
     * 规划与写库分离：[CourseTweaker.plan] 是纯函数（JVM 可测），这里只负责
     * 「按目标课表 scope 读全量 → 规划 → 在一个事务里删/改/插」。
     * 事务是必需的：规划基于读到的快照，中间若混进别的写入（导入、编辑），
     * 会按陈旧快照把课程改回旧值。
     *
     * 返回实际执行的动作，供 UI 汇报（不依赖再查一次库）。
     */
    suspend fun tweakCourses(
        mode: TweakMode,
        fromWeek: Int,
        fromDay: Int,
        toWeek: Int,
        toDay: Int,
        timetableId: Long? = null,
    ): TweakPlan {
        val ttId = timetableId ?: currentTimetableId.first()
        return db.withTransaction {
            val courses = getTimetableCourses(ttId)
            val plan = CourseTweaker.plan(courses, mode, fromWeek, fromDay, toWeek, toDay)
            if (plan.deleteIds.isNotEmpty()) db.courseDao().deleteByIds(plan.deleteIds)
            if (plan.updates.isNotEmpty()) {
                db.courseDao().insertAll(
                    plan.updates.map { CourseEntity.fromDomain(it).copy(timetableId = ttId) },
                )
            }
            if (plan.inserts.isNotEmpty()) {
                db.courseDao().insertAll(
                    plan.inserts.map { CourseEntity.fromDomain(it.copy(id = 0)).copy(timetableId = ttId) },
                )
            }
            plan
        }
    }

    /**
     * 写**指定课表**的学期配置（DESIGN §4.9）：课表设置页可切换编辑目标，
     * 不再隐式只写当前课表。
     */
    suspend fun updateSemesterFor(timetableId: Long, config: SemesterConfig) {
        db.semesterConfigDao().upsert(SemesterConfigEntity.fromDomain(timetableId, config))
    }

    /**
     * 指定课表的学期配置（DESIGN §4.14）：考试导入在确认弹窗选定目标课后，
     * 用**目标课表**的开学日重算周次——多课表各有自己的开学日，站在 A 课表
     * 把考试导进 B 课表时只有按 B 定位才落得准。
     */
    suspend fun semesterFor(timetableId: Long): SemesterConfig? =
        db.semesterConfigDao().getForTimetable(timetableId)?.toDomain()

    /** 指定课表学期配置的观察流：课表设置子页切换目标后实时跟随（DESIGN §4.9）。 */
    fun observeSemesterFor(timetableId: Long): Flow<SemesterConfig?> =
        db.semesterConfigDao().observeForTimetable(timetableId).map { it?.toDomain() }

    /** 指定课表的课程数观察流：课表设置页目标信息行（DESIGN §4.9）。 */
    fun observeCourseCountFor(timetableId: Long): Flow<Int> =
        db.courseDao().observeCountForTimetable(timetableId)

    fun coursesForWeek(week: Int): Flow<List<Course>> =
        courses.map { ScheduleCalculator.coursesInWeek(it, week) }

    // ------------------------------------------------------------------
    // 作息表（scope = 当前课表）
    // ------------------------------------------------------------------

    /**
     * 保存用户自定义作息（写入当前课表并标记 slotsCustomized）。
     * 课表行高、时刻线、「还剩 X 分」、下一节判定全都依赖这张表，
     * 一旦用户按学校实际调整过，后续任何迁移都不该再覆盖它。
     * 调用方需先过 [TimeSlotRules.validate]。
     */
    suspend fun saveTimeSlots(slots: List<TimeSlot>) {
        val timetableId = currentTimetableId.first()
        db.timeSlotDao().upsertAll(
            slots.sortedBy { it.number }
                .map { TimeSlotEntity(timetableId, it.number, it.startTime, it.endTime) },
        )
        db.timetableDao().getById(timetableId)?.let {
            db.timetableDao().upsert(it.copy(slotsCustomized = true))
        }
    }

    /** 恢复默认作息（当前课表），并重新交回给迁移逻辑管辖。 */
    suspend fun resetTimeSlotsToDefault() {
        val timetableId = currentTimetableId.first()
        writeDefaultTimeSlots(timetableId)
        db.timetableDao().getById(timetableId)?.let {
            db.timetableDao().upsert(it.copy(slotsCustomized = false))
        }
    }

    // ------------------------------------------------------------------
    // JSON 导入导出（导出 = 当前课表；格式不变，拾光互导兼容）
    // ------------------------------------------------------------------

    suspend fun exportJson(timetableId: Long? = null): String {
        val courses = getTimetableCourses(timetableId ?: currentTimetableId.first()).map {
            CourseJson(
                id = it.id,
                name = it.name,
                teacher = it.teacher,
                position = it.position,
                day = it.day,
                startSection = it.startSection,
                endSection = it.endSection,
                weeks = it.weeks.sorted(),
                isCustomTime = it.isCustomTime,
                customStartTime = it.customStartTime,
                customEndTime = it.customEndTime,
                colorIndex = it.colorIndex,
                kind = it.kind.name.lowercase(),
                remark = it.remark,
            )
        }
        // 成绩全局归属学生（DESIGN §4.15），随备份一起带走；timetableId 不影响它
        val scores = db.scoreDao().getAll().map { it.toDomain().toBackupJson() }
        // 学业完成情况同理（DESIGN §4.29）：全局归属学生，不随课表。没抓过就是空段，
        // 导入侧对空段不动作，不会把新设备上已有的数据清掉
        val scholarGroups = db.scholarProgressDao().getGroups().map { it.toDomain().toBackupJson() }
        val scholarCourses = db.scholarProgressDao().getCourses().map { it.toDomain().toBackupJson() }
        // 培养方案（DESIGN §4.41）：同样全局归属学生，全量一起带走；没抓过就是空段
        val curriculumCourses = db.curriculumDao().getCourses().map { it.toDomain().toBackupJson() }
        // 教材（DESIGN §4.31）：同样全局归属学生，全学期一起带走
        val textbooks = db.textbookDao().getAll().map { it.toDomain().toBackupJson() }
        // 学期配置与作息按课表（DESIGN §4.3）：导出的就是这份课表的时间口径，恢复时跟课表走
        val ttId = timetableId ?: currentTimetableId.first()
        val semester = db.semesterConfigDao().getForTimetable(ttId)?.toDomain()?.let {
            SemesterBackupJson(it.startDate, it.totalWeeks, it.firstDayOfWeek)
        }
        val slots = db.timeSlotDao().getForTimetable(ttId).map {
            TimeSlotBackupJson(it.number, it.startTime, it.endTime)
        }
        return json.encodeToString(
            CourseExport.serializer(),
            CourseExport(
                courses = courses,
                scores = scores,
                scholarGroups = scholarGroups,
                scholarCourses = scholarCourses,
                curriculumCourses = curriculumCourses,
                textbooks = textbooks,
                semester = semester,
                timeSlots = slots,
            ),
        )
    }

    /**
     * 当前课表展开成具体日期的日历事件（DESIGN §4.12）。
     *
     * 分享弹层的「同步到日历 / 导出 CSV」与「我的 → 日历同步」共用这一条口径。
     * 学期未配置或课表为空返回 null，由调用方决定提示文案；时刻口径与跳过规则
     * 见 [ScheduleExporter.expandEvents]。
     */
    suspend fun expandedCalendarEvents(): List<CourseEvent>? {
        val semester = semester.first() ?: return null
        val slots = timeSlots.first()
        val courses = courses.first()
        if (courses.isEmpty()) return null
        // 日期覆盖（§4.40）：放假不导出、补课按借来的课导到目标日期
        return ScheduleExporter.expandEvents(courses, slots, semester, overridesFor()).ok
    }

    /**
     * 导入 JSON 到指定课表（null = 当前课表）。
     * 目标选择与覆盖/合并由 UI 弹窗强制确定（DESIGN §4.9），本方法只负责校验与写库。
     * 文件携带成绩段时**整体替换**现有成绩（先清后插，DESIGN §4.3）；空段/缺段不动成绩。
     */
    suspend fun importJson(text: String, merge: Boolean, timetableId: Long? = null): ImportResult {
        val export = try {
            json.decodeFromString(CourseExport.serializer(), text)
        } catch (e: Exception) {
            return ImportResult.Failure("JSON 解析失败：${e.message ?: "格式不正确"}")
        }
        val courses = export.courses
        if (courses.isEmpty()) {
            return ImportResult.Failure("文件中没有课程（courses 为空）")
        }
        courses.forEachIndexed { index, c ->
            val err = validateCourseJson(index, c)
            if (err != null) return ImportResult.Failure(err)
        }
        // 成绩在校验阶段一并挡下：课程写了一半才发现成绩段脏数据，等于留下半套备份
        val scoreRecords = export.scores.map { it.toScoreRecord() }
        scoreRecords.forEachIndexed { index, s ->
            if (s.term.isBlank() || s.name.isBlank()) {
                return ImportResult.Failure("第 ${index + 1} 条成绩缺少 term 或 name")
            }
        }
        // 学业完成情况同样在校验阶段挡下（DESIGN §4.29）：缺维度或课程名的行一律整体拒绝
        val scholarGroups = export.scholarGroups.map { it.toScholarGroup() }
        val scholarCourses = export.scholarCourses.map { it.toScholarCourse() }
        scholarGroups.forEachIndexed { index, g ->
            if (g.dimension.isBlank() || g.name.isBlank()) {
                return ImportResult.Failure("第 ${index + 1} 条学业分组缺少 dimension 或 name")
            }
        }
        scholarCourses.forEachIndexed { index, c ->
            if (c.dimension.isBlank() || c.name.isBlank()) {
                return ImportResult.Failure("第 ${index + 1} 条学业课程缺少 dimension 或 name")
            }
        }
        // 培养方案段同样在校验阶段挡下（DESIGN §4.41）：缺课程名的行一律整体拒绝
        val backupCurriculum = export.curriculumCourses.map { it.toCurriculumCourse() }
        backupCurriculum.forEachIndexed { index, c ->
            if (c.name.isBlank()) {
                return ImportResult.Failure("第 ${index + 1} 条培养方案课程缺少 name")
            }
        }
        // 教材段同样先全量校验（DESIGN §4.31）：缺学期/课程名/书名的行没意义，一律整体拒绝
        val backupTextbooks = export.textbooks.map { it.toTextbook() }
        backupTextbooks.forEachIndexed { index, b ->
            if (b.term.isBlank() || b.courseName.isBlank() || b.title.isBlank()) {
                return ImportResult.Failure("第 ${index + 1} 条教材缺少 term、courseName 或 title")
            }
        }
        // 学期/作息段同样先全量校验（DESIGN §4.3）：宁可整体拒绝，不留「课程对了时间错」的半套
        val backupSemester = export.semester?.let { s ->
            val date = runCatching { ScheduleCalculator.parseDate(s.startDate) }.getOrNull()
                ?: return ImportResult.Failure("备份里的开学日期格式不正确：${s.startDate}")
            SemesterConfig(date.toString(), s.totalWeeks.coerceIn(1, 30), s.firstDayOfWeek)
        }
        val backupSlots = export.timeSlots.map { TimeSlot(it.number, it.startTime, it.endTime) }
        if (backupSlots.isNotEmpty()) {
            TimeSlotRules.validate(backupSlots)?.let {
                return ImportResult.Failure("备份里的作息表有问题：$it")
            }
        }
        val domain = courses.map { it.toDomain() }
        val restoredScores = if (scoreRecords.isEmpty()) {
            0
        } else {
            db.withTransaction {
                db.scoreDao().deleteAll()
                db.scoreDao().insertAll(
                    scoreRecords.map { ScoreEntity.fromDomain(it, System.currentTimeMillis()) },
                )
            }
            scoreRecords.size
        }
        // 学业完成情况（DESIGN §4.29）：与成绩同一口径——带数据才整体替换，空段不动现有数据
        val restoredScholar = if (scholarGroups.isEmpty() && scholarCourses.isEmpty()) {
            0
        } else {
            db.withTransaction {
                db.scholarProgressDao().deleteGroups()
                db.scholarProgressDao().deleteCourses()
                db.scholarProgressDao().insertGroups(
                    scholarGroups.map { ScholarGroupEntity.fromDomain(it) },
                )
                db.scholarProgressDao().insertCourses(
                    scholarCourses.map { ScholarCourseEntity.fromDomain(it) },
                )
            }
            scholarCourses.size
        }
        // 培养方案（DESIGN §4.41）：与学业同一口径——带数据才整体替换，空段不动现有数据
        val restoredCurriculum = if (backupCurriculum.isEmpty()) {
            0
        } else {
            db.withTransaction {
                db.curriculumDao().deleteAll()
                db.curriculumDao().insertAll(
                    backupCurriculum.map { CurriculumCourseEntity.fromDomain(it) },
                )
            }
            backupCurriculum.size
        }
        // 教材（DESIGN §4.31）：与成绩同一口径——带数据才整体替换，空段不动现有数据
        val restoredTextbooks = if (backupTextbooks.isEmpty()) {
            0
        } else {
            db.withTransaction {
                db.textbookDao().deleteAll()
                db.textbookDao().insertAll(
                    backupTextbooks.map { TextbookEntity.fromDomain(it) },
                )
            }
            backupTextbooks.size
        }
        // 配置恢复到**目标课表**（与课程同落点）；恢复作息视为用户数据，置位防结构性迁移覆盖
        val ttId = timetableId ?: currentTimetableId.first()
        var restoredSemester = false
        var restoredSlots = 0
        if (backupSemester != null || backupSlots.isNotEmpty()) {
            db.withTransaction {
                if (backupSemester != null) {
                    db.semesterConfigDao().upsert(SemesterConfigEntity.fromDomain(ttId, backupSemester))
                    restoredSemester = true
                }
                if (backupSlots.isNotEmpty()) {
                    db.timeSlotDao().clearForTimetable(ttId)
                    db.timeSlotDao().upsertAll(
                        backupSlots.map { TimeSlotEntity(ttId, it.number, it.startTime, it.endTime) },
                    )
                    db.timetableDao().getById(ttId)?.let {
                        db.timetableDao().upsert(it.copy(slotsCustomized = true))
                    }
                    restoredSlots = backupSlots.size
                }
            }
        }
        // 备份顶层 term 是数据的学年学期：恢复时写进目标课表，教材查询才有学期钥匙。
        // 文件没带 term 就不动课表现有值
        if (!export.term.isNullOrBlank()) {
            db.timetableDao().getById(ttId)?.let {
                db.timetableDao().upsert(it.copy(term = export.term))
            }
        }
        // 课程写入拆成结果值；基线推进必须排在**课程写完之后**（见函数尾部的理由）。
        val result = if (merge) {
            val added = mergeCourses(domain, timetableId)
            ImportResult.Success(
                added = added,
                total = domain.size,
                merge = true,
                restoredScores = restoredScores,
                restoredScholarCourses = restoredScholar,
                restoredCurriculumCourses = restoredCurriculum,
                restoredTextbooks = restoredTextbooks,
                restoredSemester = restoredSemester,
                restoredSlots = restoredSlots,
            )
        } else {
            // 恢复式导入同理（§4.40）：备份 JSON 里不带日期覆盖，整表恢复后旧覆盖已无
            // 对应关系，留着会让「恢复成备份的样子」变成一句空话
            db.dateOverrideDao().deleteForTimetable(ttId)
            replaceAllCourses(domain, timetableId)
            ImportResult.Success(
                added = domain.size,
                total = domain.size,
                merge = false,
                restoredScores = restoredScores,
                restoredScholarCourses = restoredScholar,
                restoredCurriculumCourses = restoredCurriculum,
                restoredTextbooks = restoredTextbooks,
                restoredSemester = restoredSemester,
                restoredSlots = restoredSlots,
            )
        }
        // 备份恢复也是一次「写库成功」：基线推进为恢复后的实际内容。**必须排在课程
        // 写完之后**——此前调用点写得太早，基线存的是恢复前的旧课表，恢复完再检查
        // 全是假差异（2026-10-07 复核修）。旧基线与已阅指纹整体作废。
        refreshBaselineFromDb(ttId)
        return result
    }

    /** 解析并校验待导入文件；成功时携带领域模型，供目标课表弹窗按所选目标计算统计。 */
    suspend fun previewImport(text: String): ImportPreview {
        val export = try {
            json.decodeFromString(CourseExport.serializer(), text)
        } catch (e: Exception) {
            return ImportPreview.Error("JSON 解析失败：${e.message ?: "格式不正确"}")
        }
        if (export.courses.isEmpty()) {
            return ImportPreview.Error("文件中没有课程（courses 为空）")
        }
        export.courses.forEachIndexed { index, c ->
            validateCourseJson(index, c)?.let { return ImportPreview.Error(it) }
        }
        val domain = export.courses.map { it.toDomain() }
        val scores = export.scores.map { it.toScoreRecord() }
        val scholarGroups = export.scholarGroups.map { it.toScholarGroup() }
        val scholarCourses = export.scholarCourses.map { it.toScholarCourse() }
        scholarGroups.forEachIndexed { index, g ->
            if (g.dimension.isBlank() || g.name.isBlank()) {
                return ImportPreview.Error("第 ${index + 1} 条学业分组缺少 dimension 或 name")
            }
        }
        scholarCourses.forEachIndexed { index, c ->
            if (c.dimension.isBlank() || c.name.isBlank()) {
                return ImportPreview.Error("第 ${index + 1} 条学业课程缺少 dimension 或 name")
            }
        }
        // 培养方案段同口径（DESIGN §4.41）：弹窗阶段就把坏备份挡下
        val backupCurriculum = export.curriculumCourses.map { it.toCurriculumCourse() }
        backupCurriculum.forEachIndexed { index, c ->
            if (c.name.isBlank()) {
                return ImportPreview.Error("第 ${index + 1} 条培养方案课程缺少 name")
            }
        }
        // 学期/作息段也先验一遍（与 importJson 同口径）：弹窗阶段就把坏备份挡下
        val backupSemester = export.semester?.let { s ->
            val date = runCatching { ScheduleCalculator.parseDate(s.startDate) }.getOrNull()
                ?: return ImportPreview.Error("备份里的开学日期格式不正确：${s.startDate}")
            SemesterConfig(date.toString(), s.totalWeeks.coerceIn(1, 30), s.firstDayOfWeek)
        }
        val backupSlots = export.timeSlots.map { TimeSlot(it.number, it.startTime, it.endTime) }
        if (backupSlots.isNotEmpty()) {
            TimeSlotRules.validate(backupSlots)?.let {
                return ImportPreview.Error("备份里的作息表有问题：$it")
            }
        }
        return ImportPreview.Ok(
            courses = domain,
            sample = domain.take(5).joinToString { it.name },
            term = export.term?.takeIf { it.isNotBlank() },
            scores = scores,
            scholarCourseCount = scholarCourses.size,
            curriculumCourseCount = backupCurriculum.size,
            semester = backupSemester,
            slotCount = backupSlots.size,
        )
    }

    /** 给定目标课表计算覆盖/合并统计（弹窗里随目标切换刷新）。 */
    suspend fun importStatsFor(courses: List<Course>, timetableId: Long): ImportStats {
        val existing = getTimetableCourses(timetableId)
        val keys = existing.map { it.mergeKey() }.toHashSet()
        return ImportStats(
            existing = existing.size,
            newCount = courses.count { it.mergeKey() !in keys },
        )
    }

}

sealed interface ImportResult {
    data class Success(
        val added: Int,
        val total: Int,
        val merge: Boolean,
        /** 随文件整体替换的成绩条数；0 = 文件没带成绩段。 */
        val restoredScores: Int = 0,
        /** 随文件整体替换的学业完成情况课程条数；0 = 文件没带学业段。 */
        val restoredScholarCourses: Int = 0,
        /** 随文件整体替换的培养方案课程条数；0 = 文件没带培养方案段。 */
        val restoredCurriculumCourses: Int = 0,
        /** 是否随文件恢复了目标课表的学期配置。 */
        val restoredSemester: Boolean = false,
        /** 随文件恢复的作息条数；0 = 文件没带作息段。 */
        val restoredSlots: Int = 0,
        /** 随文件整体替换的教材条数；0 = 文件没带教材段。 */
        val restoredTextbooks: Int = 0,
    ) : ImportResult
    data class Failure(val message: String) : ImportResult
}

sealed interface ImportPreview {
    /** [courses] 已通过校验，可直接作为写库入参与目标统计的输入。 */
    data class Ok(
        val courses: List<Course>,
        val sample: String,
        /** 文件声明的学年学期，仅用于导入确认弹窗展示；不影响写库。 */
        val term: String? = null,
        /** 文件携带的成绩（DESIGN §4.3）：导入时整体替换，弹窗按它出提示。 */
        val scores: List<ScoreRecord> = emptyList(),
        /** 文件携带的学业完成情况课程条数（DESIGN §4.29）：导入时整体替换；0 = 没带。 */
        val scholarCourseCount: Int = 0,
        /** 文件携带的培养方案课程条数（DESIGN §4.41）：导入时整体替换；0 = 没带。 */
        val curriculumCourseCount: Int = 0,
        /** 文件带的学期配置（null = 没有）；随预览展示到确认弹窗注记。 */
        val semester: SemesterConfig? = null,
        /** 文件带的作息条数（0 = 没有）。 */
        val slotCount: Int = 0,
    ) : ImportPreview
    data class Error(val message: String) : ImportPreview
}

/**
 * [ScheduleRepository.appShellPrefs] 的载荷：壳层（课表背景图）消费的字段切片。
 * 壳收切片不收整份 [DisplayPrefs]——NavHost 挂在壳里，其他偏好变化
 * （拖滑杆、改触感）不该重组整个壳。
 */
data class AppShellPrefs(
    val bgImageName: String? = null,
    val bgImageOpacity: Float = 1f,
    val bgImageDim: Float = 0f,
    val bgImageBlur: Float = 0f,
    val bgImageScale: BgScale = BgScale.Fill,
)
