package edu.jxslu.schedule.domain

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 考试安排 → 课表 `Course(kind = Exam)` 的映射（DESIGN §4.14）。
 *
 * 考试不是独立实体：复用 `Course` 既有字段——
 * - 考试日期 → `weeks`（单元素教学周）+ `day`（星期几），由学期配置推算；
 * - 起止时刻 → `customStartTime/EndTime`（`isCustomTime = true`），今日页时刻/倒计时口径自动生效；
 * - 网格节次 → 按作息表把时刻映射到**相交小节**（实测考试时段全部与节次边界对齐）；
 * - 考场 → `position`。
 *
 * 日期超出学期范围（或学期未配置开学日）时 [toExamCourse] 返回 null：
 * 导入层据此跳过并计数提示，不静默丢弃也不落错周。
 */
object ExamMapper {

    private val dateFmt: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    private val hhmmRe = Regex("""^(\d{1,2}):(\d{2})$""")

    /** 严格 HH:mm 解析；接口/脚本产出的脏时刻在这里被挡下，不落成错误的网格位置。 */
    private fun parseMinutes(hhmm: String): Int? {
        val m = hhmmRe.matchEntire(hhmm.trim()) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        if (h !in 0..23 || min !in 0..59) return null
        return h * 60 + min
    }

    /** 与 `scripts/out/exams.json` 及注入 JS fetch 输出对齐的考试条目。 */
    data class ExamEntry(
        val courseNo: String = "",
        val name: String,
        val teacher: String = "",
        val room: String = "",
        val campus: String = "",
        /** yyyy-MM-dd；空视为脏数据，无法定位。 */
        val date: String = "",
        /** HH:mm。 */
        val startTime: String = "",
        val endTime: String = "",
        val seatNo: String = "",
        val sessionNo: String = "",
    )

    /**
     * 考试起止时刻映射到**相交**的小节区间。
     *
     * 相交判定用半开区间（节次 `[start, end)` 与考试 `[startMin, endMin)` 有公共部分）：
     * 08:30~09:55 → 1-2 节、15:45~17:10 → 7-8 节、19:00~20:25 → 9-10 节。
     * 与任何小节都不相交（考试落在午饭/晚饭空档等）时取开始时刻之后的最近一节兜底；
     * 开始时刻晚于所有节次则取最后一节。作息表为空或时刻非法返回 null。
     */
    fun sectionsForTimeRange(
        slots: List<TimeSlot>,
        startHHmm: String,
        endHHmm: String,
    ): IntRange? {
        val ordered = slots.sortedBy { it.number }
        if (ordered.isEmpty()) return null
        val startMin = parseMinutes(startHHmm) ?: return null
        val endMin = parseMinutes(endHHmm) ?: return null

        val overlap = ordered.filter { slot ->
            ScheduleCalculator.toMinutes(slot.startTime) < endMin &&
                ScheduleCalculator.toMinutes(slot.endTime) > startMin
        }
        if (overlap.isNotEmpty()) {
            return overlap.first().number..overlap.last().number
        }
        // 空档兜底：开始时刻早于所有节次取第一节；落在空档（某节结束后、下一节开始前）
        // 取下一节；晚于所有节次取最后一节。
        val after = ordered.lastOrNull { ScheduleCalculator.toMinutes(it.endTime) <= startMin }
        val target = if (after == null) {
            ordered.first()
        } else {
            ordered.firstOrNull { it.number > after.number } ?: ordered.last()
        }
        return target.number..target.number
    }

    /**
     * 考试日期 → (教学周, 星期)。日期非法或不在学期范围内返回 null
     * （超范围无法在网格定位，导入层跳过）。
     */
    fun weekAndDay(
        config: SemesterConfig,
        dateStr: String,
    ): Pair<Int, Int>? {
        val date = try {
            LocalDate.parse(dateStr, dateFmt)
        } catch (_: Exception) {
            return null
        }
        val week = ScheduleCalculator.weekNumberOf(config, date)
        if (week !in 1..config.totalWeeks) return null
        // Java DayOfWeek: MONDAY=1 … SUNDAY=7，与「1=周一 … 7=周日」口径一致
        return week to date.dayOfWeek.value
    }

    /**
     * 考试 → `Course(kind = Exam)`。日期/时刻无法定位时返回 null。
     * `colorIndex` 由导入路径统一重排（withSortedNameColors），这里恒传 0。
     */
    fun toExamCourse(
        entry: ExamEntry,
        slots: List<TimeSlot>,
        config: SemesterConfig,
    ): Course? {
        if (entry.name.isBlank()) return null
        val location = listOf(entry.room, entry.campus)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
        val (week, day) = weekAndDay(config, entry.date) ?: return null
        val sections = sectionsForTimeRange(slots, entry.startTime, entry.endTime) ?: return null
        return Course(
            id = 0,
            name = entry.name.trim(),
            teacher = entry.teacher.trim(),
            position = location,
            day = day,
            startSection = sections.first,
            endSection = sections.last,
            weeks = setOf(week),
            isCustomTime = true,
            customStartTime = entry.startTime.ifBlank { null },
            customEndTime = entry.endTime.ifBlank { null },
            colorIndex = 0,
            kind = CourseKind.Exam,
        )
    }

    /** 批量转换并按可定位性分组：返回 (可导入的课程, 无法定位的条数)。 */
    fun toExamCourses(
        entries: List<ExamEntry>,
        slots: List<TimeSlot>,
        config: SemesterConfig?,
    ): Pair<List<Course>, Int> {
        if (config == null) return emptyList<Course>() to entries.size
        var skipped = 0
        val courses = entries.mapNotNull { entry ->
            toExamCourse(entry, slots, config) ?: run { skipped++; null }
        }
        return courses to skipped
    }
}
