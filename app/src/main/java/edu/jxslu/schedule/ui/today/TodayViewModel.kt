package edu.jxslu.schedule.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import edu.jxslu.schedule.data.repo.ScheduleRepository
import edu.jxslu.schedule.domain.Course
import edu.jxslu.schedule.domain.CoursePhase
import edu.jxslu.schedule.domain.LocalTimeLike
import edu.jxslu.schedule.domain.ScheduleCalculator
import edu.jxslu.schedule.domain.SemesterConfig
import edu.jxslu.schedule.domain.TimeSlot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 今日页状态。
 *
 * 核心取舍：
 * - **已结束的课不出现**；列表只放「还没开始的」。
 * - **同一节课只出现一次**：顶部焦点卡（正在上课 / 下一节）拿走第一门，
 *   [listCourses] 是 [remaining] 去掉那一门之后的列表。旧版没做这层去重，
 *   列表第一项和状态卡是同一节课，看着像内容重复。
 * - 今天没有待上课程（上完 / 没课）时才让「明天」上桌（[tomorrowVisible]）。
 */
data class TodayUiState(
    val loading: Boolean = true,
    val semester: SemesterConfig? = null,
    /** 0 表示不在学期内 */
    val week: Int = 0,
    val day: Int = 1,
    /** 今天的日期，顶栏「9月18日 周四」用 */
    val date: LocalDate = LocalDate.now(),
    val inTerm: Boolean = false,
    val slots: List<TimeSlot> = emptyList(),
    val totalCourseCount: Int = 0,

    /** 今天还没开始的课（不含正在上、不含已结束），按有效开始时刻排序 */
    val remaining: List<Course> = emptyList(),
    /** 正在上的课，多门重叠时取最早开始的；**不同时出现在 remaining 里** */
    val ongoing: Course? = null,
    /** 今天最近一节还没开始的课；**仍在 remaining 里**（列表头计数含它） */
    val next: Course? = null,
    /** 焦点卡下方的列表 = [remaining] 去掉焦点那门，避免同一节课两处重复 */
    val listCourses: List<Course> = emptyList(),
    /** 距下一节上课还有几分钟 */
    val minutesToNext: Int? = null,
    /** 当前这节还剩几分钟下课（整门课口径，自定义时间/兜底用） */
    val minutesToOngoingEnd: Int? = null,
    /**
     * 正在上课时右侧的细粒度倒计时：
     * `还有 25 分钟下课` / `课间 · 还有 4 分钟上课`。
     * 不带小节序号——课时卡已有「第3-4节」，再标一个「第1节」是第二套编号。
     */
    val ongoingCountdown: String? = null,
    /** 正在上课时的整门课进度 0f..1f；其余情况 null（不画进度条） */
    val ongoingProgress: Float? = null,
    /** 今天排了课，但已经全部上完 */
    val todayAllDone: Boolean = false,
    /** 今天本来就没有课 */
    val todayEmpty: Boolean = false,
    /** 今天一共几节（含已结束） */
    val todayTotal: Int = 0,

    /** 明天 */
    val tomorrowDay: Int = 1,
    val tomorrowWeek: Int = 0,
    val tomorrowInTerm: Boolean = false,
    /** 只有今天没有待上课程（上完 / 没课 / 不在上课）才让明天上桌，今天的信息优先 */
    val tomorrowVisible: Boolean = false,
    val tomorrowCourses: List<Course> = emptyList(),
)

/**
 * 纯函数版本的状态推导，便于单测：
 * 「今天还有几节 / 是否都上完 / 明天有没有课」这些分支全部在这里决定，界面只做渲染。
 */
internal fun buildTodayState(
    semester: SemesterConfig?,
    slots: List<TimeSlot>,
    courses: List<Course>,
    today: LocalDate,
    now: LocalTimeLike,
): TodayUiState {
    val day = today.dayOfWeek.value
    val week = semester?.let { ScheduleCalculator.weekNumberOf(it, today) } ?: 0
    val inTerm = semester != null && week in 1..semester.totalWeeks

    val dayCourses = if (inTerm) {
        ScheduleCalculator.coursesOnDay(courses, week, day)
    } else {
        emptyList()
    }
    // 排序键统一走「有效开始时刻」：自定义时间的课按 startSection 会排错序（错到别的课前面）
    val sorted = dayCourses.sortedBy { startKey(slots, it) }
    val phases = sorted.associate { it.id to ScheduleCalculator.coursePhase(slots, it, now) }

    // 列表只放「还没开始」：已结束不进列表，正在上的只进顶部焦点卡（避免同一节课两处重复）
    val remaining = sorted.filter { phases[it.id] == CoursePhase.Upcoming }
    val ongoing = sorted.firstOrNull { phases[it.id] == CoursePhase.Ongoing }
    val next = remaining.firstOrNull()
    val focus = ongoing ?: next

    val tomorrow = today.plusDays(1)
    val tomorrowDay = tomorrow.dayOfWeek.value
    val tomorrowWeek = semester?.let { ScheduleCalculator.weekNumberOf(it, tomorrow) } ?: 0
    val tomorrowInTerm = semester != null && tomorrowWeek in 1..semester.totalWeeks

    return TodayUiState(
        loading = false,
        semester = semester,
        week = week,
        day = day,
        date = today,
        inTerm = inTerm,
        slots = slots,
        totalCourseCount = courses.size,
        remaining = remaining,
        ongoing = ongoing,
        next = next,
        listCourses = remaining.filter { it.id != focus?.id },
        minutesToNext = next?.let { minutesUntilStart(slots, it, now) },
        minutesToOngoingEnd = ongoing?.let { minutesUntilEnd(slots, it, now) },
        ongoingCountdown = ongoing?.let { ongoingCountdownLabel(slots, it, now) },
        ongoingProgress = ongoing?.let { ongoingProgress(slots, it, now) },
        todayAllDone = dayCourses.isNotEmpty() && dayCourses.all { phases[it.id] == CoursePhase.Ended },
        todayEmpty = dayCourses.isEmpty(),
        todayTotal = dayCourses.size,
        tomorrowDay = tomorrowDay,
        tomorrowWeek = tomorrowWeek,
        tomorrowInTerm = tomorrowInTerm,
        tomorrowVisible = tomorrowInTerm && focus == null,
        tomorrowCourses = if (tomorrowInTerm) {
            ScheduleCalculator.coursesOnDay(courses, tomorrowWeek, tomorrowDay)
                .sortedBy { startKey(slots, it) }
        } else {
            emptyList()
        },
    )
}

/** 排序键：有效开始时刻；作息表缺该节又没自定义时间的课排到最后，不让它们冒到列表头上。 */
private fun startKey(slots: List<TimeSlot>, course: Course): Int =
    ScheduleCalculator.courseStartMinutes(slots, course) ?: Int.MAX_VALUE

class TodayViewModel(
    private val repo: ScheduleRepository,
    private val todayProvider: () -> LocalDate = { LocalDate.now() },
) : ViewModel() {

    private val tick = MutableStateFlow(0L)

    /**
     * 首帧门闸。
     * 根因：ensureDefaults 首次启动会写库（建默认课表/迁移/配色自愈），而 Room 流是
     * 「先吐当前值」——初始化完成前的空库帧会先发出去，UI 就在
     * 「加载中 → 尚未开学/课表为空 → 真实内容」之间连跳几帧，表现成闪屏。
     * 方案：初始化完成前不订阅数据流，对外第一帧就是写库之后的终态。
     */
    private val ready = MutableStateFlow(false)

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<TodayUiState> = ready.flatMapLatest { ready ->
        if (!ready) {
            flowOf(TodayUiState())
        } else {
            combine(
                repo.semester,
                repo.timeSlots,
                repo.courses,
                tick,
            ) { semester, slots, courses, _ ->
                buildTodayState(
                    semester = semester,
                    slots = slots,
                    courses = courses,
                    today = todayProvider(),
                    now = LocalTimeLike.now(),
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUiState())

    init {
        viewModelScope.launch {
            repo.ensureDefaults()
            ready.value = true
        }
    }

    /** 界面每 30 秒调一次：让「还剩 X 分钟」和课的状态跟着时间走。 */
    fun refreshTick() {
        tick.value = System.currentTimeMillis()
    }

    fun upsert(course: Course) {
        viewModelScope.launch { repo.upsertCourse(course) }
    }

    fun deleteCourse(course: Course) {
        viewModelScope.launch { repo.deleteCourse(course) }
    }

    class Factory(private val repo: ScheduleRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            TodayViewModel(repo) as T
    }
}

/** 距上课还有几分钟；已过开课时间返回 null。自定义时间课以 customStartTime 为准。 */
internal fun minutesUntilStart(slots: List<TimeSlot>, course: Course, now: LocalTimeLike): Int? =
    ScheduleCalculator.courseStartMinutes(slots, course)
        ?.let { (it - now.toMinutes()).takeIf { delta -> delta > 0 } }

/** 距下课还有几分钟；已过下课时间返回 null。自定义时间课以 customEndTime 为准。 */
internal fun minutesUntilEnd(slots: List<TimeSlot>, course: Course, now: LocalTimeLike): Int? =
    ScheduleCalculator.courseEndMinutes(slots, course)
        ?.let { (it - now.toMinutes()).takeIf { delta -> delta > 0 } }

/** 正在上课时的整门课进度 0f..1f；缺起始/结束时间时不画。 */
internal fun ongoingProgress(
    slots: List<TimeSlot>,
    course: Course,
    now: LocalTimeLike,
): Float? {
    val start = ScheduleCalculator.courseStartMinutes(slots, course) ?: return null
    val end = ScheduleCalculator.courseEndMinutes(slots, course) ?: return null
    if (end <= start) return null
    return ((now.toMinutes() - start).toFloat() / (end - start)).coerceIn(0f, 1f)
}

/**
 * 正在上课时的细粒度倒计时文案。
 *
 * 大节内两小节之间有 5 分钟课间：上课时说「第3节 · 还有 xx 分钟下课」，
 * 课间说「课间 · 还有 xx 分钟上课」。
 *
 * 节次号用**作息小节号**（`slot.number`），与焦点卡标题的「第3-4节」同一套坐标；
 * 旧版用课程内序号（`index + 1`），于是「正在上课 · 第3-4节」旁边挂着「第1节」——
 * 同一张卡上两套编号，是今日页显乱的来源之一。
 *
 * 自定义时间课没有小节表，退回整门口径。
 */
internal fun ongoingCountdownLabel(
    slots: List<TimeSlot>,
    course: Course,
    now: LocalTimeLike,
): String? {
    val cur = now.toMinutes()
    if (course.isCustomTime) {
        val mins = minutesUntilEnd(slots, course, now) ?: return null
        return "还有 $mins 分钟下课"
    }
    val courseSlots = slots
        .filter { it.number in course.startSection..course.endSection }
        .sortedBy { it.number }
    if (courseSlots.isEmpty()) {
        return minutesUntilEnd(slots, course, now)?.let { "还有 $it 分钟下课" }
    }
    courseSlots.forEachIndexed { index, slot ->
        val start = ScheduleCalculator.toMinutes(slot.startTime)
        val end = ScheduleCalculator.toMinutes(slot.endTime)
        if (cur in start until end) {
            val mins = end - cur
            return if (courseSlots.size <= 1) {
                "还有 $mins 分钟下课"
            } else {
                "第${slot.number}节 · 还有 $mins 分钟下课"
            }
        }
        if (index < courseSlots.lastIndex) {
            val nextStart = ScheduleCalculator.toMinutes(courseSlots[index + 1].startTime)
            if (cur in end until nextStart) {
                return "课间 · 还有 ${nextStart - cur} 分钟上课"
            }
        }
    }
    return minutesUntilEnd(slots, course, now)?.let { "还有 $it 分钟下课" }
}
