package edu.jxslu.schedule.domain

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.abs

/**
 * 上课提醒的取值域单一来源（模式对齐 [CalendarSyncDefaults]）。
 * 提前量的候选与夹取只在这里写，设置页滚轮与存储读路径都引用它。
 */
object ReminderDefaults {
    const val DEFAULT_LEAD_MINUTES = 10
    val LEAD_CHOICES = listOf(5, 10, 15, 20, 30)

    /** 存储值落在候选之外（手改数据/旧值）时吸附到最近的候选档。 */
    fun coerceLead(value: Int): Int =
        LEAD_CHOICES.minByOrNull { abs(it - value) } ?: DEFAULT_LEAD_MINUTES

    fun leadLabel(minutes: Int): String = "$minutes 分钟"
}

/**
 * 一条已定的提醒：某天某节课在 [triggerAt] 触发。
 *
 * [startMinutes] 与 TimeSlot 同口径（自午夜起的分钟数）；时刻文本的渲染走
 * `domain/TodayFormat`，这里只做计算不生产文案。
 */
data class ReminderPlan(
    val course: Course,
    val date: LocalDate,
    val startMinutes: Int,
    /** 上课时刻。 */
    val startAt: LocalDateTime,
    /** 触发时刻 = 上课时刻 − 提前量。 */
    val triggerAt: LocalDateTime,
) {
    /** 去重键：同一节课同一天只提醒一次（闹钟与周期核对共用，DESIGN §3.7）。 */
    val dedupKey: String get() = "$date|${course.id}|$startMinutes"
}

/**
 * 现在（含今天往后 [maxDaysAhead] 天）内所有「还没开始」的上课时刻，按上课时间升序。
 *
 * 课程时间只有一条口径：[ScheduleCalculator.courseStartMinutes]（自定义时间课以
 * custom 字段为准），这里不另写分支。学期未配置返回空。
 */
private fun upcomingPlans(
    semester: SemesterConfig?,
    slots: List<TimeSlot>,
    courses: List<Course>,
    now: LocalDateTime,
    leadMinutes: Int,
    maxDaysAhead: Int,
): List<ReminderPlan> {
    if (semester == null) return emptyList()
    val result = mutableListOf<ReminderPlan>()
    var date = now.toLocalDate()
    repeat(maxDaysAhead) {
        val week = ScheduleCalculator.weekNumberOf(semester, date)
        if (week in 1..semester.totalWeeks) {
            for (course in ScheduleCalculator.coursesOnDay(courses, week, date.dayOfWeek.value)) {
                val start = ScheduleCalculator.courseStartMinutes(slots, course) ?: continue
                val startAt = date.atStartOfDay().plusMinutes(start.toLong())
                if (!startAt.isAfter(now)) continue
                result += ReminderPlan(
                    course = course,
                    date = date,
                    startMinutes = start,
                    startAt = startAt,
                    triggerAt = startAt.minusMinutes(leadMinutes.toLong()),
                )
            }
        }
        date = date.plusDays(1)
    }
    return result.sortedBy { it.startAt }
}

/**
 * 「现在就该提醒」的课：已进入提前量窗口（上课时刻 − now ≤ 提前量）且还没上课。
 * 闹钟晚触发、周期核对补发都靠它判断；窗口已过（迟到的提醒）返回 null，不补发噪音。
 */
fun dueReminderPlan(
    semester: SemesterConfig?,
    slots: List<TimeSlot>,
    courses: List<Course>,
    now: LocalDateTime,
    leadMinutes: Int,
): ReminderPlan? = upcomingPlans(semester, slots, courses, now, leadMinutes, maxDaysAhead = 1)
    .firstOrNull { it.startAt <= now.plusMinutes(leadMinutes.toLong()) }

/**
 * 下一个还没错过的提醒（触发时刻严格在 now 之后），供排闹钟；没有则返回 null
 * （提醒关、课表空、不在学期内的兜底判断由调用方读偏好完成）。
 * 最多向后找 14 天：一个学期不会连着两周没课还没到头。
 */
fun nextReminderPlan(
    semester: SemesterConfig?,
    slots: List<TimeSlot>,
    courses: List<Course>,
    now: LocalDateTime,
    leadMinutes: Int,
    maxDaysAhead: Int = 14,
): ReminderPlan? = upcomingPlans(semester, slots, courses, now, leadMinutes, maxDaysAhead)
    .firstOrNull { it.triggerAt.isAfter(now) }

/** 作业提醒的固定触发时刻（时）：两个提醒点都在 20:00（DESIGN §3.11）。 */
private const val HOMEWORK_REMIND_HOUR = 20

/** [nextHomeworkReminder] 向后看的天数上限：更远的截止日连提醒点都在 30 天外（DESIGN §4.20）。 */
private const val HOMEWORK_HORIZON_DAYS = 30L

/**
 * 一条作业截止提醒点（DESIGN §3.11）：[noticeDate] 这天 [HOMEWORK_REMIND_HOUR] 点触发。
 *
 * [kind] 只决定文案（「明天截止」/「今天截止」）；时刻与有效性判断全在
 * [homeworkReminderPlan] / [nextHomeworkReminder]，这里不生产文案。
 */
data class HomeworkReminderPlan(
    val homework: Homework,
    /** 提醒点日期：前一天提醒 = `截止日 − 1 天`，当天提醒 = 截止日。 */
    val noticeDate: LocalDate,
    /** 触发时刻 = [noticeDate] 20:00。 */
    val triggerAt: LocalDateTime,
    val kind: Kind,
) {
    /** 两个提醒点：截止日前一天 20:00 与截止日 20:00。 */
    enum class Kind(val label: String) {
        /** 截止日前一天 20:00。 */
        Tomorrow("明天截止"),

        /** 截止日当天 20:00。 */
        Today("今天截止"),
    }

    /**
     * 去重键 = `作业id|提醒点日期`（DESIGN §3.11，如 `12|2026-09-21`）。
     * 落 DataStore 的「已发键」集合，闹钟与周期核对共用去重。
     */
    val dedupKey: String get() = "${homework.id}|$noticeDate"
}

/** 一条作业的两个提醒点（截止日前一天 20:00 / 截止日 20:00），按时间先后。 */
private fun homeworkReminderPoints(homework: Homework, due: LocalDate): List<HomeworkReminderPlan> =
    listOf(
        HomeworkReminderPlan(
            homework = homework,
            noticeDate = due.minusDays(1),
            triggerAt = due.minusDays(1).atTime(HOMEWORK_REMIND_HOUR, 0),
            kind = HomeworkReminderPlan.Kind.Tomorrow,
        ),
        HomeworkReminderPlan(
            homework = homework,
            noticeDate = due,
            triggerAt = due.atTime(HOMEWORK_REMIND_HOUR, 0),
            kind = HomeworkReminderPlan.Kind.Today,
        ),
    )

/**
 * 「现在正处于有效期内的」作业提醒点（DESIGN §3.11）：触发时刻已到（含 20:00 整点），
 * 且还没越过**提醒点次日 00:00**——越过即跳过、不补发（与 §3.7「迟到不补」同口径）。
 * 20:00 的提醒拖到 21:00 发出来仍是「今天截止」，有用；过了午夜就是噪音，不再发。
 *
 * 不发（返回 null）的情形：已勾选完成（[Homework.done]；调用方通常直接传
 * `observePending()` 的未完成列表，这里仍显式兜一道）、无截止日期、已逾期
 * （`截止日 < 今天`——两个提醒点的窗口必然都已关闭）。
 */
fun homeworkReminderPlan(homework: Homework, now: LocalDateTime): HomeworkReminderPlan? {
    if (homework.done) return null
    val due = homework.dueDate ?: return null
    if (due.isBefore(now.toLocalDate())) return null
    return homeworkReminderPoints(homework, due).firstOrNull { plan ->
        !now.isBefore(plan.triggerAt) && now.isBefore(plan.noticeDate.plusDays(1).atStartOfDay())
    }
}

/**
 * 「现在该发」的提醒组（DESIGN §3.11「汇总一条」）：当前处于有效期内的提醒点，
 * **有「今天截止」优先发那一组**（更紧急），否则发「明天截止」组；都没有返回空表。
 *
 * 通知层据此决定单条还是汇总：1 条 = 单作业通知（点击直达该作业），
 * 多条 = 汇总通知（「N 项作业今天/明天截止」，点击进作业中心）。
 */
fun homeworkReminderGroup(items: List<Homework>, now: LocalDateTime): List<HomeworkReminderPlan> {
    val due = items.mapNotNull { homeworkReminderPlan(it, now) }
    if (due.isEmpty()) return emptyList()
    val today = due.filter { it.kind == HomeworkReminderPlan.Kind.Today }
    return if (today.isNotEmpty()) today else due.filter { it.kind == HomeworkReminderPlan.Kind.Tomorrow }
}

/**
 * 全部（未完成）作业里触发时刻**严格在 now 之后**的最近一个提醒点，供排闹钟；没有则返回 null。
 *
 * 与上课提醒的 [nextReminderPlan] 同语义（闹钟取两者更早的触发时刻，见 `ClassReminder`）。
 * 只看截止日在 [HOMEWORK_HORIZON_DAYS] 天内（含今天）的作业：更远的截止日提醒点也在 30 天外，
 * 排一个太远的闹钟没有意义。无截止日期/已逾期/已完成的作业没有未来的触发点，自然被排除。
 */
fun nextHomeworkReminder(items: List<Homework>, now: LocalDateTime): HomeworkReminderPlan? {
    val horizon = now.toLocalDate().plusDays(HOMEWORK_HORIZON_DAYS)
    return items.mapNotNull { homework ->
        if (homework.done) return@mapNotNull null
        val due = homework.dueDate ?: return@mapNotNull null
        if (due.isAfter(horizon)) return@mapNotNull null
        homeworkReminderPoints(homework, due).firstOrNull { it.triggerAt.isAfter(now) }
    }.minByOrNull { it.triggerAt }
}
