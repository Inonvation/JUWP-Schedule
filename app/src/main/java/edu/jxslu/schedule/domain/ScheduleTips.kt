package edu.jxslu.schedule.domain

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 天气提示行里的「课表句」（DESIGN §3.29，2026-10-12）。
 *
 * 三个情景（全是用户口径，别自行放宽）：
 * 1. **南北楼往返跑 / 都在一栋楼**：上午（第 1–4 节）或下午（第 5–8 节）**≥2 节课**时，
 *    该半天落脚的楼全解析得出 → 两栋都出现给「往返跑」，同一栋给「都在 X 楼」；
 *    只有一节课不判（用户 2026-10-12：单节课不提示）。
 *    **上下午都有课、且全在同一栋楼** → 合并成一句「一天的课都在 X 楼」；
 *    **上下午都要往返跑** → 合并成「上午、下午都要南北楼往返跑咯」。
 *    （2026-10-14：早先不合并会拆成「上午…，下午…」两截，重复「都在南楼」+ 多余的「不用挪窝」。）
 *    其余两句并列时，日期前缀只挂第一句、「都在 X 楼」用朴素说法：
 *    「明天上午都在南楼，下午都在北楼」（不重复「明天」，不带「哦」「不用挪窝」）。
 * 2. **有实验课**：该半边有 [CourseKind.Lab]（实验课表导入即标 Lab，
 *    **不看地点**——实验课不一定在工程训练中心）。有实验课的天**只出实验课句**，
 *    南北楼句不再出（用户 2026-10-12 拍板）。
 * 3. **快下课有雨**：今天、某节课距下课 ≤ [WeatherAdvice.DISMISSAL_WINDOW_MINUTES] 分钟、
 *    下课时刻起 1 小时内逐小时预报有雨 → 「等会可能有雨，记得带伞哦」。
 *    **这条整行只显示它自己**（替换天气句与课表句，此刻的行动只有一件）。
 *
 * 兜底原则（用户 2026-10-12 明确要求）：**宁可没有提示，也不要有错误提示**。
 * 任何一环取不到（不在学期内、位置解析不出楼、节次缺作息、逐小时数据缺失、
 * 下课时刻算不出）→ 整句丢弃，不猜。
 *
 * 纯函数，JVM 可测（`ScheduleTipsTest` 钉住每个分支与边界）。
 */
object ScheduleTips {

    /** 上午覆盖的小节号（含两端）。 */
    private val MORNING_SECTIONS = 1..4

    /** 下午覆盖的小节号（含两端）。 */
    private val AFTERNOON_SECTIONS = 5..8

    /**
     * 楼名口径：**同时含**楼名与「教学」二字才算教学楼（导入数据是「教学南大楼(南B102)」）。
     * 单写「南楼」两个字不认——学校的其他地方也可能带这两个字，认错比漏提严重。
     */
    private const val SOUTH_MARKER = "南"
    private const val NORTH_MARKER = "北"
    private const val TEACHING_MARKER = "教学"

    /** 楼名展示短称（文案里说「南楼」不说全名，一长就超出顶栏那一格）。 */
    private const val SOUTH_LABEL = "南楼"
    private const val NORTH_LABEL = "北楼"

    /** 快下课判定里向下课的宽限（分钟）：下课时刻已过这么多分钟（含）就不再算「快下课」。 */
    private const val DISMISSAL_GRACE_MINUTES = 1

    /** 一个半天的南北楼判定：全在南楼 / 全在北楼 / 南北楼都有。 */
    private enum class Verdict { South, North, RoundTrip }

    /** 提示的种类。 */
    enum class Style {
        /** 普通课表句，与天气句并列。 */
        Normal,

        /** 快下课有雨：整行只显示它（替换天气句与课表句）。 */
        Dismissal,
    }

    /** 一条课表句。[prefix] 是行首 emoji（含一个空格），[text] 是整句。 */
    data class Tip(val style: Style, val prefix: String, val text: String)

    /** 一个半天的判定结果。[text] 带「明天上午」这类时间前缀。 */
    private data class Segment(val text: String)

    /**
     * 参考日整天的课表句（南北楼 / 实验课）；没有任何该说的话时给 null
     * （调用方照常显示天气句）。
     *
     * @param date 「参考日」——18:00 前是今天、18:00 起是明天（与 [WeatherAdvice] 同源）
     * @param courses 当前课表全量课程（含调休覆盖的判定）
     */
    fun dayTipFor(
        date: LocalDate,
        semester: SemesterConfig?,
        slots: List<TimeSlot>,
        courses: List<Course>,
        overrides: List<ScheduleOverride> = emptyList(),
    ): Tip? {
        // 不在学期内 / 学期缺配置：任何课表句都不出（宁可没有，不可乱说）
        if (semester == null) return null
        val week = runCatching { ScheduleCalculator.weekNumberOf(semester, date) }.getOrNull()
            ?: return null
        if (week !in 1..semester.totalWeeks) return null
        val plan = ScheduleOverrides.plan(overrides, semester)
        val dayCourses = ScheduleCalculator.coursesOnDay(courses, week, date.dayOfWeek.value, plan)
        // 措辞按「参考日是不是今天」定：18:00 前讲今天、18:00 起讲明天
        val label = if (date == LocalDate.now()) "今天" else "明天"
        return dayTip(label, dayCourses, slots, date)
    }

    /**
     * 快下课有雨的整行提示（[Style.Dismissal]）；无命中给 null。
     *
     * **与 [dayTipFor] 是两条独立链路**：这条永远看**今天**的课表与逐小时数据——
     * 18:00 后顶栏正文讲的是明天，但此刻还在上的课在 15 分钟内要下课这件事与「讲哪一天」无关
     * （2026-10-12 复查补上：早先跟参考日走，18:00 后这条提示会静默消失）。
     */
    fun dismissalTipFor(
        semester: SemesterConfig?,
        slots: List<TimeSlot>,
        courses: List<Course>,
        overrides: List<ScheduleOverride> = emptyList(),
        now: LocalDateTime = LocalDateTime.now(),
        hourly: List<WeatherHour> = emptyList(),
    ): Tip? {
        if (semester == null) return null
        val today = now.toLocalDate()
        val week = runCatching { ScheduleCalculator.weekNumberOf(semester, today) }.getOrNull()
            ?: return null
        if (week !in 1..semester.totalWeeks) return null
        val plan = ScheduleOverrides.plan(overrides, semester)
        val dayCourses =
            ScheduleCalculator.coursesOnDay(courses, week, today.dayOfWeek.value, plan)
        return dismissalTip(dayCourses, slots, now, today, hourly)
    }

    // ---- 一天里该说什么 ----

    /**
     * 参考日整天的课表句。优先级按用户口径：
     * **有实验课 → 只出实验课句**；否则出南北楼句——
     * 上下午都在同一栋楼 / 都要往返跑时各合并成一句，其余情况按半天拼接。
     */
    private fun dayTip(
        label: String,
        dayCourses: List<Course>,
        slots: List<TimeSlot>,
        date: LocalDate,
    ): Tip? {
        val morning = dayCourses.filter { sectionIn(it, slots, MORNING_SECTIONS) }
        val afternoon = dayCourses.filter { sectionIn(it, slots, AFTERNOON_SECTIONS) }

        // 有实验课：只出实验课句（用户 2026-10-12：有实验课就只提实验课 + 天气）。
        // 只说「有实验课」不点数量——同一半天可能排两节实验（同门课按周次/房间拆行），
        // 写死「有一节」会说错（用户要求「不要错误提示」）
        val labs = run {
            val m = morning.any { it.kind == CourseKind.Lab }
            val a = afternoon.any { it.kind == CourseKind.Lab }
            when {
                m && a -> "${label}上午、下午都有实验课"
                m -> "${label}上午有实验课"
                a -> "${label}下午有实验课"
                else -> null
            }
        }
        if (labs != null) return Tip(Style.Normal, "🔬 ", labs)

        // 上下午都有课、且全在同一栋楼 → 一句说清「一天的课都在 X 楼」。
        // 别拆成「上午…，下午…」两截：两截会各说一遍「都在 X 楼」，还带一句多余的「不用挪窝」（2026-10-14）
        val morningBuilding = sameBuildingOf(morning)
        val afternoonBuilding = sameBuildingOf(afternoon)
        if (morningBuilding != null && morningBuilding == afternoonBuilding) {
            return Tip(
                Style.Normal,
                "🧭 ",
                variant(
                    date,
                    salt = 0,
                    "${label}一天的课都在${morningBuilding}哦",
                    "${label}一天的课都在${morningBuilding}，不用挪窝",
                ),
            )
        }

        val morningVerdict = verdictOf(morning)
        val afternoonVerdict = verdictOf(afternoon)

        // 上下午都要往返跑 → 也合并成一句（同一类重复，2026-10-14）
        if (morningVerdict == Verdict.RoundTrip && afternoonVerdict == Verdict.RoundTrip) {
            return Tip(
                Style.Normal,
                "🧭 ",
                variant(
                    date,
                    salt = 0,
                    "${label}上午、下午都要南北楼往返跑咯",
                    "${label}上午、下午都得在南楼北楼之间来回跑咯",
                ),
            )
        }

        // 两个半天都有话说 → 并列成一句：日期前缀只挂第一句，「都在 X 楼」用朴素说法
        val joined = morningVerdict != null && afternoonVerdict != null

        val segments = listOfNotNull(
            buildingSegment("上午", morningVerdict, date, salt = 0, joined = joined),
            buildingSegment("下午", afternoonVerdict, date, salt = 1, joined = joined),
        )
        if (segments.isEmpty()) return null
        // 日期前缀只挂第一句：两句并列时「明天上午都在南楼，下午都在北楼」，不重复「明天」
        return Tip(Style.Normal, "🧭 ", label + segments.joinToString("，") { it.text })
    }

    /**
     * 一个半天的南北楼判定。
     *
     * 兜底（缺一出 null）：课不足 2 节、有课的位置解析不出楼、不在两栋楼范围内 → 不说。
     */
    private fun verdictOf(halfCourses: List<Course>): Verdict? {
        if (halfCourses.size < 2) return null
        val buildings = halfCourses.map { buildingOf(it.position) ?: return null }
        val south = buildings.any { it == SOUTH_LABEL }
        val north = buildings.any { it == NORTH_LABEL }
        return when {
            south && north -> Verdict.RoundTrip
            south -> Verdict.South
            else -> Verdict.North
        }
    }

    /**
     * 该半天是否「全在同一栋楼」，是则给出楼名（[SOUTH_LABEL] / [NORTH_LABEL]）。
     * 空、混合、有课解析不出楼 → null。**不看课数**——全天整句用的是这个口径
     * （上下午各只有一节、但在同一栋楼，也算「一天的课都在 X 楼」）。
     */
    private fun sameBuildingOf(courses: List<Course>): String? {
        if (courses.isEmpty()) return null
        val buildings = courses.map { buildingOf(it.position) ?: return null }
        val first = buildings.first()
        return if (buildings.all { it == first }) first else null
    }

    /**
     * 一个半天的南北楼句（**不含日期前缀**——前缀由调用方只在第一句挂一次）。
     * [verdict] 为 null（不足 2 节 / 解析不出）时不说话。
     *
     * [joined] = 与另一个半天并列成一句：此时「都在 X 楼」用朴素说法，不带「哦」「不用挪窝」
     * （用户 2026-10-14：并列时说「明天上午都在南楼，下午都在北楼」，不重复日期、不叠语气词）。
     */
    private fun buildingSegment(
        half: String,
        verdict: Verdict?,
        date: LocalDate,
        salt: Int,
        joined: Boolean,
    ): Segment? = when (verdict) {
        null -> null

        Verdict.RoundTrip -> Segment(
            variant(
                date,
                salt,
                "${half}要南北楼往返跑咯",
                "${half}得在南楼北楼之间来回跑咯",
            ),
        )

        Verdict.South -> Segment(
            if (joined) {
                variant(date, salt, "${half}都在南楼", "${half}的课都在南楼")
            } else {
                variant(date, salt, "${half}都在南楼哦", "${half}的课都在南楼，不用挪窝")
            },
        )

        Verdict.North -> Segment(
            if (joined) {
                variant(date, salt, "${half}都在北楼", "${half}的课都在北楼")
            } else {
                variant(date, salt, "${half}都在北楼哦", "${half}的课都在北楼，不用挪窝")
            },
        )
    }

    // ---- 快下课有雨 ----

    /**
     * 快下课有雨：今天、最近一节要下的课距下课 ≤ 15 分钟、下课时刻起 1 小时内有雨。
     * 取最早要下的那一节（同刻下课的课只提一次）。
     *
     * 兜底：参考日不是今天 → null；下课时刻算不出（作息表缺该节）→ 跳过该课；
     * 逐小时数据缺失 / 该窗口没有雨 → null。
     */
    private fun dismissalTip(
        dayCourses: List<Course>,
        slots: List<TimeSlot>,
        now: LocalDateTime,
        date: LocalDate,
        hourly: List<WeatherHour>,
    ): Tip? {
        if (date != now.toLocalDate()) return null
        val nowMinutes = now.hour * 60 + now.minute
        val due = dayCourses
            .mapNotNull { course ->
                val end = ScheduleCalculator.courseEndMinutes(slots, course) ?: return@mapNotNull null
                val remain = end - nowMinutes
                // 闭区间：剩 15 分钟整（= 窗口值）也要提示，`until` 会把它排掉
                if (remain in -DISMISSAL_GRACE_MINUTES..WeatherAdvice.DISMISSAL_WINDOW_MINUTES) {
                    end to course
                } else {
                    null
                }
            }
            .minByOrNull { it.first } ?: return null
        if (!rainAfter(hourly, date, due.first)) return null
        val pool = listOf(
            "等会下课可能有雨，记得带伞哦",
            "快下课啦，外面可能要下雨，伞别忘了",
            "下课那会儿可能有雨，记得拿把伞哦",
        )
        return Tip(Style.Dismissal, "🌧️ ", pool[poolIndex(date, salt = 2, size = pool.size)])
    }

    /**
     * 下课时刻 [endMinutes]（当天分钟数）起 1 小时内有没有雨。
     *
     * 窗口 = 「下课所在的小时」到「其后一小时」两个整点条目：接口的逐小时数据落在整点，
     * 某一小时条目代表该整点起的一小时，所以下课前的那个整点条目也要看（它覆盖下课那一刻）。
     * **跨天安全**：只取 [date] 当天的条目，不会拿第二天的条目来判今天。
     */
    private fun rainAfter(hourly: List<WeatherHour>, date: LocalDate, endMinutes: Int): Boolean {
        val endHour = endMinutes / 60
        return hourly.any { hour ->
            hour.time.toLocalDate() == date &&
                hour.time.hour in (endHour - 1)..(endHour + 1) &&
                WeatherCodes.willPrecipitate(hour)
        }
    }

    // ---- 小工具 ----

    /**
     * 这门课算不算这个半天。按**有效开始时刻**判（自习时间课以 customStartTime 为准，
     * 与 `ScheduleCalculator.courseStartMinutes` 同一口径）；作息表缺该节 → 不算，
     * 免得把算不出时间的课误分到某个半天里。
     */
    private fun sectionIn(course: Course, slots: List<TimeSlot>, range: IntRange): Boolean {
        val start = ScheduleCalculator.courseStartMinutes(slots, course) ?: return false
        return slots.any { slot ->
            slot.number in range &&
                start in ScheduleCalculator.toMinutes(slot.startTime) until
                ScheduleCalculator.toMinutes(slot.endTime)
        }
    }

    /**
     * 位置 → 楼。[Course.position] 的实际形态（2026-10-12 查三份真实课表）：
     * `教学北大楼(北B102)`、`教学南大楼(南C303)`、`工程训练中心208`、空 `()`。
     * 除前两者外一律 null（实验课在工程训练中心，本来就不该混进南北楼判定）。
     */
    private fun buildingOf(position: String): String? = when {
        position.contains(SOUTH_MARKER) && position.contains(TEACHING_MARKER) -> SOUTH_LABEL
        position.contains(NORTH_MARKER) && position.contains(TEACHING_MARKER) -> NORTH_LABEL
        else -> null
    }

    /**
     * 按天 + 盐取变体下标：同一天内稳定（页面每 30 秒重算也不会跳字），换天换说法。
     *
     * `size = 2` 时**不能用 `dayOfYear % 2`**：那等价于 dayOfYear 的奇偶，
     * 同一年里要么永远选第一条、要么永远选第二条（2026-10-13 是偶数 286，长变体一次都出不来）。
     * `/ 2` 让下标每相邻两天交替一次，两条变体都能轮到。
     */
    private fun poolIndex(date: LocalDate, salt: Int, size: Int): Int =
        Math.floorMod(date.dayOfYear / 2 + salt, size)

    /** 两个变体里按天挑一个。 */
    private fun variant(date: LocalDate, salt: Int, first: String, second: String): String =
        if (poolIndex(date, salt, 2) == 0) first else second
}
