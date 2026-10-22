package edu.jxslu.schedule.domain

import java.time.LocalDate
import kotlinx.serialization.Serializable

/**
 * 日期覆盖（调休 / 停课，DESIGN §4.40，2026-10-06）。
 *
 * 根因：课表是「周次 × 星期」两维的，而学校的放假与调休通知是**按具体日期**发的
 * ——「9月20日(星期日)按照9月28日(第五周周一)的教学安排进行」「9月25日-10月7日放假，
 * 教学活动暂停」。这两类事实用 [Course] 的 `weeks` / `day` 表达不了：
 * 把课挪走只能切碎 `weeks`（破坏性、不可撤销），停课更是无处可写。
 *
 * 覆盖是**渲染层的叠加**，不写 `courses` 表：
 * - [OverrideKind.SUSPENDED]：该日期区间没有课（放假 / 停课）；
 * - [OverrideKind.FOLLOW]：该日期上**另一个 (周次, 星期) 槽位**的课
 *   （第 4 周周日上第 5 周周一的课）。单日，不取区间。
 *
 * 键统一收敛成 (周次, 星期)：网格、今日页、小组件、提醒全部按它索引，
 * 日期 → 槽位用 [slotOf]（周一为首日，与 [ScheduleCalculator.weekNumberOf] 同口径）。
 */
@Serializable
data class ScheduleOverride(
    val id: Long = 0,
    /** yyyy-MM-dd */
    val fromDate: String,
    /** yyyy-MM-dd；单日时与 [fromDate] 相同。 */
    val toDate: String,
    val kind: OverrideKind,
    /** [OverrideKind.FOLLOW] 必填：源槽位的周次。 */
    val sourceWeek: Int? = null,
    /** [OverrideKind.FOLLOW] 必填：源槽位的星期（1=周一 … 7=周日）。 */
    val sourceWeekday: Int? = null,
    val note: String = "",
)

enum class OverrideKind {
    /** 按另一个 (周次, 星期) 的课表上课。 */
    FOLLOW,

    /** 放假 / 停课。 */
    SUSPENDED,
}

/**
 * 覆盖的纯逻辑：日期 ↔ 槽位换算、覆盖摊平成查表、文案。
 * 全部纯函数，JVM 可测（`ScheduleOverridesTest`）。
 */
object ScheduleOverrides {

    /** 一个「第 N 周 周 X」槽位。 */
    data class Slot(val week: Int, val day: Int)

    /** 摊平后的覆盖指令。 */
    sealed interface Directive {
        /** 该槽位没有课。 */
        data object Suspended : Directive

        /** 该槽位上 [week] 周 [weekday] 的课。 */
        data class Follow(val week: Int, val weekday: Int) : Directive
    }

    /**
     * 单条 [OverrideKind.SUSPENDED] 最多展开多少天。
     * 防脏数据（年份写错、区间反了）把摊平循环拖死——超出直接截断。
     */
    const val MAX_RANGE_DAYS = 400

    /**
     * 把覆盖列表摊平成 `槽位 → 指令` 查表。
     *
     * 学期缺失、日期解析失败、周次落在学期外的条目一律**跳过**（宁可少覆盖，
     * 不要把课表整体搞乱）。同日多条时**后面的覆盖前面的**（调用方按用户确认顺序存）。
     */
    fun plan(
        overrides: List<ScheduleOverride>,
        semester: SemesterConfig?,
    ): Map<Slot, Directive> {
        if (semester == null || overrides.isEmpty()) return emptyMap()
        val out = mutableMapOf<Slot, Directive>()
        overrides.forEach { override ->
            when (override.kind) {
                OverrideKind.SUSPENDED -> {
                    eachDate(override.fromDate, override.toDate) { date ->
                        slotOf(semester, date)?.let { out[it] = Directive.Suspended }
                    }
                }

                OverrideKind.FOLLOW -> {
                    val sourceWeek = override.sourceWeek ?: return@forEach
                    val sourceWeekday = override.sourceWeekday ?: return@forEach
                    if (sourceWeekday !in 1..7) return@forEach
                    val target = slotOf(semester, parseOrNull(override.fromDate) ?: return@forEach)
                        ?: return@forEach
                    out[target] = Directive.Follow(sourceWeek, sourceWeekday)
                }
            }
        }
        return out
    }

    /** 日期 → 槽位；学期缺失 / 日期不合法 / 周次落在 1..totalWeeks 之外时 null。 */
    fun slotOf(semester: SemesterConfig, date: LocalDate): Slot? {
        val week = runCatching { ScheduleCalculator.weekNumberOf(semester, date) }.getOrNull()
            ?: return null
        if (!ScheduleCalculator.isWeekInTerm(semester, week)) return null
        return Slot(week, date.dayOfWeek.value)
    }

    /** 槽位 → 日期（表头日期、覆盖文案用）。唯一实现在 [ScheduleCalculator.dateOf]。 */
    fun dateOf(semester: SemesterConfig?, week: Int, day: Int): LocalDate? =
        ScheduleCalculator.dateOf(semester, week, day)

    /** 某槽位是不是放假/停课。 */
    fun isSuspended(plan: Map<Slot, Directive>, week: Int, day: Int): Boolean =
        plan[Slot(week, day)] == Directive.Suspended

    /** 某槽位是不是从别处借来的课（返回源槽位）。 */
    fun followedFrom(plan: Map<Slot, Directive>, week: Int, day: Int): Slot? =
        (plan[Slot(week, day)] as? Directive.Follow)?.let { Slot(it.week, it.weekday) }

    /**
     * 一组覆盖涉及到的周次（升序去重）。预览网格的周次选择条用它——
     * 调休通知天然横跨好几周（放假一段 + 若干补课日），用户要能翻着看。
     */
    fun weeksOf(overrides: List<ScheduleOverride>, semester: SemesterConfig?): List<Int> {
        if (semester == null) return emptyList()
        val weeks = sortedSetOf<Int>()
        overrides.forEach { override ->
            eachDate(override.fromDate, override.toDate) { date ->
                slotOf(semester, date)?.let { weeks += it.week }
            }
        }
        return weeks.toList()
    }

    /** 点课表里「放假 / 补课」标记时弹出的详情（DESIGN §3.25 / §4.40）。 */
    data class Detail(
        val week: Int,
        val day: Int,
        val date: LocalDate?,
        /** true = 放假 / 停课；false = 补课（[source] 非空）。 */
        val suspended: Boolean,
        /** 补课的源槽位（照搬哪一周周几）。 */
        val source: Slot?,
        /**
         * **原有安排**：不受覆盖影响，这天按常规本来有哪些课。
         * 放假时看它 = 「本来要上什么」；补课时看它 = 「这天原来有没有课」。
         */
        val originalCourses: List<Course>,
        /** 覆盖生效后这天实际有哪些课。 */
        val effectiveCourses: List<Course>,
        /** 覆盖自带的说明（AI 落下的理由 / 教务通知原文摘录）。 */
        val note: String,
    )

    /**
     * 组装某槽位的覆盖详情；该槽位没有覆盖时返回 null（调用方据此不弹）。
     * [courses] 传当前课表的全量（非考试）条目。
     */
    fun detail(
        week: Int,
        day: Int,
        overrides: List<ScheduleOverride>,
        courses: List<Course>,
        semester: SemesterConfig?,
    ): Detail? {
        val plan = plan(overrides, semester)
        val directive = plan[Slot(week, day)] ?: return null
        return Detail(
            week = week,
            day = day,
            date = dateOf(semester, week, day),
            suspended = directive == Directive.Suspended,
            source = (directive as? Directive.Follow)?.let { Slot(it.week, it.weekday) },
            originalCourses = ScheduleCalculator.coursesOnDay(courses, week, day),
            effectiveCourses = ScheduleCalculator.coursesOnDay(courses, week, day, plan),
            note = overrides.firstOrNull { covers(it, Slot(week, day), semester) }?.note.orEmpty(),
        )
    }

    /** 这条覆盖是否命中该槽位（详情里挑备注用）。 */
    fun covers(override: ScheduleOverride, slot: Slot, semester: SemesterConfig?): Boolean {
        if (semester == null) return false
        return when (override.kind) {
            OverrideKind.FOLLOW ->
                parseOrNull(override.fromDate)?.let { slotOf(semester, it) } == slot

            OverrideKind.SUSPENDED -> {
                val start = parseOrNull(override.fromDate) ?: return false
                val end = parseOrNull(override.toDate) ?: start
                if (end.isBefore(start)) return slotOf(semester, start) == slot
                var date = start
                var count = 0
                while (!date.isAfter(end) && count < MAX_RANGE_DAYS) {
                    if (slotOf(semester, date) == slot) return true
                    date = date.plusDays(1)
                    count++
                }
                false
            }
        }
    }

    /** 单条覆盖的人话文案（覆盖清单、日志用）。 */
    fun describe(override: ScheduleOverride, semester: SemesterConfig?): String {
        val from = shortDate(override.fromDate)
        return when (override.kind) {
            OverrideKind.SUSPENDED -> {
                val to = shortDate(override.toDate)
                if (override.fromDate == override.toDate) "$from 放假" else "$from 至 $to 放假"
            }

            OverrideKind.FOLLOW -> {
                val source = if (override.sourceWeek != null && override.sourceWeekday != null) {
                    "第${override.sourceWeek}周${weekdayLabel(override.sourceWeekday)}"
                } else {
                    "指定安排"
                }
                "$from 按 $source 的课表上课"
            }
        }
    }

    /**
     * 两条覆盖是否**等价**（同一件事）：同 kind、同起止日、follow 再比源槽位。
     * [ScheduleOverride.note] 只是理由文案，不参与。
     *
     * 用途：① 解析层对「已生效的安排」做确定性去重（模型重发同一通知时不再列为待确认）；
     * ② 写入层幂等兜底（同一件事不裸插第二行）。
     * follow 的 `toDate` 已由 `parseOverride` 归一为 `fromDate`，语义上是单日，
     * 比较时同样只认 `fromDate`。
     */
    fun sameEffect(a: ScheduleOverride, b: ScheduleOverride): Boolean {
        if (a.kind != b.kind || a.fromDate != b.fromDate) return false
        return when (a.kind) {
            OverrideKind.SUSPENDED -> a.toDate == b.toDate
            OverrideKind.FOLLOW -> a.sourceWeek == b.sourceWeek && a.sourceWeekday == b.sourceWeekday
        }
    }

    private fun shortDate(value: String): String {
        val date = parseOrNull(value) ?: return value
        return "${date.monthValue}月${date.dayOfMonth}日"
    }

    private fun weekdayLabel(day: Int): String = when (day) {
        1 -> "周一"
        2 -> "周二"
        3 -> "周三"
        4 -> "周四"
        5 -> "周五"
        6 -> "周六"
        7 -> "周日"
        else -> "周$day"
    }

    private fun parseOrNull(value: String): LocalDate? =
        runCatching { ScheduleCalculator.parseDate(value) }.getOrNull()

    /** 逐日遍历闭区间；非法区间（起 > 止）与超长区间安全退化。 */
    private inline fun eachDate(from: String, to: String, action: (LocalDate) -> Unit) {
        val start = parseOrNull(from) ?: return
        val end = parseOrNull(to) ?: start
        if (end.isBefore(start)) {
            action(start)
            return
        }
        var date = start
        var count = 0
        while (!date.isAfter(end) && count < MAX_RANGE_DAYS) {
            action(date)
            date = date.plusDays(1)
            count++
        }
    }
}
