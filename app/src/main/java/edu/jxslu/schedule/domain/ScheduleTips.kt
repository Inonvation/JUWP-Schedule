package edu.jxslu.schedule.domain

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 天气提示行里的「课表句」（DESIGN §3.29，2026-10-12；2026-10-20 收口成「一条只讲一件事」）。
 *
 * 按优先级命中即返回，每句都控制在一行能放下的长度：
 * 1. **有实验课**：上午（第 1–4 节）或下午（第 5–8 节）有 [CourseKind.Lab] →
 *    只出实验课句（**不看地点**——实验课不一定在工程训练中心），楼名句不再出。
 * 2. **南北楼往返跑**：该半天 **≥2 节课**、且楼全解析得出（`教学南/北大楼`）：
 *    两栋都出现 → 往返跑。两个半天都往返 → 合并成一句；只有一个半天往返 →
 *    只报那个半天（另一个半天的信息留给课表页）。
 * 3. **没有往返跑**：上下午同楼 → 「一天的课都在 X 楼」；上下午不同楼 →
 *    「上午在 X 楼，下午在 Y 楼」；只有一个半天有 ≥2 节课 → 报那个半天。
 *
 * 早先的并列句「上午往返跑，下午都在南楼」有 24 字，顶栏一行必被截断，且后半句
 * （都在某楼）对行动没有价值——2026-10-20 改为一条只说一件事。
 *
 * 兜底原则（用户 2026-10-12 明确要求）：**宁可没有提示，也不要有错误提示**。
 * 任何一环取不到（不在学期内、位置解析不出楼、节次缺作息）→ 整句丢弃，不猜。
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

    /**
     * 参考日整天的课表句；没有任何该说的话时给 null
     * （调用方照常显示天气句）。
     *
     * @param date 「参考日」——18:00 前是今天、18:00 起是明天（与 [WeatherAdvice] 同源）
     * @param today 真正的今天：只用来决定措辞说「今天」还是「明天」，由调用方传入，
     *   不在纯函数里读系统时钟（2026-10-20）
     * @param courses 当前课表全量课程（含调休覆盖的判定）
     */
    fun dayTipFor(
        date: LocalDate,
        today: LocalDate,
        semester: SemesterConfig?,
        slots: List<TimeSlot>,
        courses: List<Course>,
        overrides: List<ScheduleOverride> = emptyList(),
    ): TodayTip? {
        // 不在学期内 / 学期缺配置：任何课表句都不出（宁可没有，不可乱说）
        if (semester == null) return null
        val week = runCatching { ScheduleCalculator.weekNumberOf(semester, date) }.getOrNull()
            ?: return null
        if (week !in 1..semester.totalWeeks) return null
        val plan = ScheduleOverrides.plan(overrides, semester)
        val dayCourses = ScheduleCalculator.coursesOnDay(courses, week, date.dayOfWeek.value, plan)
        // 措辞按「参考日是不是今天」定：18:00 前讲今天、18:00 起讲明天
        val label = if (date == today) "今天" else "明天"
        return dayTip(label, dayCourses, slots, date)
    }

    /**
     * 快下课有雨的整行提示；无命中给 null。
     *
     * **与 [dayTipFor] 是两条独立链路**：这条永远看**今天**的课表与逐小时数据——
     * 18:00 后顶栏正文讲的是明天，但此刻还在上的课在 15 分钟内要下课这件事与「讲哪一天」无关
     * （2026-10-12 复查补上：早先跟参考日走，18:00 后这条提示会静默消失）。
     *
     * 它依赖逐小时预报：天气功能关着时没有这份数据，这条不出（课表句照常出）。
     */
    fun dismissalTipFor(
        semester: SemesterConfig?,
        slots: List<TimeSlot>,
        courses: List<Course>,
        overrides: List<ScheduleOverride> = emptyList(),
        now: LocalDateTime = LocalDateTime.now(),
        hourly: List<WeatherHour> = emptyList(),
    ): TodayTip? {
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
     * 参考日整天的课表句。优先级：
     * **有实验课 → 只出实验课句**；否则**往返跑 > 楼变化 > 同楼**，只报最值得提前知道的那一件。
     */
    private fun dayTip(
        label: String,
        dayCourses: List<Course>,
        slots: List<TimeSlot>,
        date: LocalDate,
    ): TodayTip? {
        val morning = dayCourses.filter { sectionIn(it, slots, MORNING_SECTIONS) }
        val afternoon = dayCourses.filter { sectionIn(it, slots, AFTERNOON_SECTIONS) }

        // 1. 有实验课：只出实验课句（用户 2026-10-12：有实验课就只提实验课 + 天气）。
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
        if (labs != null) return scheduleTip("🔬", labs)

        // 2. 往返跑最值得提前知道：两个半天都往返合并一句，只有一个半天往返只报那一个
        val morningTrip = isRoundTrip(morning)
        val afternoonTrip = isRoundTrip(afternoon)
        if (morningTrip && afternoonTrip) {
            return scheduleTip(
                "🧭",
                variant(
                    date,
                    "${label}上下午都得南北楼来回跑",
                    "${label}上下午都要南北楼往返跑",
                ),
            )
        }
        if (morningTrip || afternoonTrip) {
            val half = if (morningTrip) "上午" else "下午"
            return scheduleTip(
                "🧭",
                variant(
                    date,
                    "${label}${half}得南北楼来回跑",
                    "${label}${half}要南北楼往返跑",
                ),
            )
        }

        // 3. 没有往返跑：按「楼有没有变」说一件事
        val morningBuilding = sameBuildingOf(morning)
        val afternoonBuilding = sameBuildingOf(afternoon)
        val text = when {
            // 上下午都有课、且全在同一栋楼 → 一句说清，不拆成两截
            morningBuilding != null && morningBuilding == afternoonBuilding ->
                variant(
                    date,
                    "${label}一天的课都在$morningBuilding",
                    "${label}一天的课都在${morningBuilding}哦",
                )

            // 上下午各在一栋楼（两个半天都 ≥2 节课才判——单节课不提示，用户 2026-10-12）
            morningBuilding != null && afternoonBuilding != null &&
                morning.size >= 2 && afternoon.size >= 2 ->
                variant(
                    date,
                    "${label}上午在$morningBuilding，下午在$afternoonBuilding",
                    "${label}上午$morningBuilding，下午$afternoonBuilding",
                )

            // 只有一个半天有 ≥2 节课：报那半天（单节课不提示，用户 2026-10-12）
            morningBuilding != null && morning.size >= 2 ->
                variant(
                    date,
                    "${label}上午的课都在$morningBuilding",
                    "${label}上午都在$morningBuilding",
                )

            afternoonBuilding != null && afternoon.size >= 2 ->
                variant(
                    date,
                    "${label}下午的课都在$afternoonBuilding",
                    "${label}下午都在$afternoonBuilding",
                )

            else -> null
        }
        return text?.let { scheduleTip("🧭", it) }
    }

    /**
     * 该半天是否要南北楼往返跑：**≥2 节课**、楼全解析得出、且南北两栋都出现。
     * 缺一项 → false（宁可不说，不说错）。
     */
    private fun isRoundTrip(halfCourses: List<Course>): Boolean {
        if (halfCourses.size < 2) return false
        val buildings = halfCourses.map { buildingOf(it.position) ?: return false }
        return buildings.any { it == SOUTH_LABEL } && buildings.any { it == NORTH_LABEL }
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
    ): TodayTip? {
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
        return scheduleTip("🌧️", pool[poolIndex(date, salt = 2, size = pool.size)])
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

    /** 课表句统一格式：[emoji] 是行首图标（实验课 🔬 / 楼名 🧭 / 快下课 🌧️），[text] 是句子。 */
    private fun scheduleTip(emoji: String, text: String) =
        TodayTip(TodayTip.Kind.Schedule, "$emoji $text")

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
     * 按天取变体下标：同一天内稳定（页面每 30 秒重算也不会跳字），换天换说法。
     *
     * `size = 2` 时**不能用 `dayOfYear % 2`**：那等价于 dayOfYear 的奇偶，
     * 同一年里要么永远选第一条、要么永远选第二条（2026-10-13 是偶数 286，长变体一次都出不来）。
     * `/ 2` 让下标每相邻两天交替一次，两条变体都能轮到。
     */
    private fun poolIndex(date: LocalDate, salt: Int, size: Int): Int =
        Math.floorMod(date.dayOfYear / 2 + salt, size)

    /** 两个变体里按天挑一个。 */
    private fun variant(date: LocalDate, first: String, second: String): String =
        if (poolIndex(date, salt = 0, size = 2) == 0) first else second
}
