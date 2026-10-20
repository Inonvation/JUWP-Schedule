package edu.jxslu.schedule.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import edu.jxslu.schedule.data.prefs.DisplayPrefs
import edu.jxslu.schedule.data.repo.HomeworkRepository
import edu.jxslu.schedule.data.repo.ScheduleRepository
import edu.jxslu.schedule.data.weather.WeatherRepository
import edu.jxslu.schedule.domain.Course
import edu.jxslu.schedule.domain.LocalTimeLike
import edu.jxslu.schedule.domain.PendingHomework
import edu.jxslu.schedule.domain.PendingHomework.Companion.EMPTY
import edu.jxslu.schedule.domain.ScheduleOverride
import edu.jxslu.schedule.domain.ScheduleTips
import edu.jxslu.schedule.domain.SemesterConfig
import edu.jxslu.schedule.domain.TimeSlot
import edu.jxslu.schedule.domain.TodayState
import edu.jxslu.schedule.domain.WeatherAdvice
import edu.jxslu.schedule.domain.buildTodayState
import edu.jxslu.schedule.domain.pendingHomework
import edu.jxslu.schedule.ui.common.UndoableMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 今日页 ViewModel。
 *
 * 状态推导本身（焦点课取舍、剩余列表、明日预告）在 `domain/TodayState.kt` 的
 * [buildTodayState]——桌面小组件（`ui/widget`）与今日页共用同一份纯函数，
 * 保证两边「哪节课算正在上 / 什么时候轮到明天上桌」的口径不会分叉。
 * 本类只负责把 Room 数据流与时间 tick 喂进去。
 */
class TodayViewModel(
    private val repo: ScheduleRepository,
    private val homeworkRepo: HomeworkRepository,
    private val todayProvider: () -> LocalDate = { LocalDate.now() },
    /**
     * 天气仓库（DESIGN §3.29）。可空注入：测试与旧构造点不传时顶栏那行恒为空。
     */
    private val weatherRepo: WeatherRepository? = null,
    /** 天气提示的「现在」来源，独立于 [todayProvider]（它只给日期，提示要判 18:00）。 */
    private val nowProvider: () -> LocalDateTime = { LocalDateTime.now() },
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
    val uiState: StateFlow<TodayState> = ready.flatMapLatest { ready ->
        if (!ready) {
            flowOf(TodayState())
        } else {
            combine(
                repo.semester,
                repo.timeSlots,
                repo.courses,
                // 日期覆盖（调休 / 停课，§4.40）随课表一起进 combine：改一条覆盖整页重算，
                // 与网格、小组件走同一个 coursesOnDay 口径
                repo.overrides,
                tick,
            ) { semester, slots, courses, overrides, _ ->
                buildTodayState(
                    semester = semester,
                    slots = slots,
                    courses = courses,
                    today = todayProvider(),
                    now = LocalTimeLike.now(),
                    overrides = overrides,
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayState())

    init {
        viewModelScope.launch {
            repo.ensureDefaults()
            ready.value = true
        }
    }

    /**
     * 今日页作业卡（DESIGN §3.3/§3.11）：未完成作业的排序与计数，口径全在
     * `domain/HomeworkCenter`（逾期 → 今天 → 未来 → 无截止）。
     *
     * 单独订阅、不并进 [uiState]：作业与课表是两套数据，混在一起会让改一条作业
     * 重算整页课表状态；随 [tick] 重算是为了跨天时文案（今天/明天/已过期）跟着走。
     */
    val homeworkPending: StateFlow<PendingHomework> =
        combine(homeworkRepo.observePending(), tick) { list, _ ->
            pendingHomework(list, todayProvider())
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EMPTY)

    /**
     * 当前课表学期下「课程名 → 教材名」映射（DESIGN §4.31，2026-10-07）：今日页课程卡
     * 把教材名挂在课名右边（2026-10-08 收口，不再另起一行）。与 [homeworkPending]
     * 同纪律**单独订阅**、不并进 [uiState]——教材是另一张表的数据，混进课表状态会让
     * 导入教材重算整页课表推导；课表没有学期号 / 无教材数据时是空映射，卡片不占位。
     */
    val textbookTitles: StateFlow<Map<String, List<String>>> = repo.textbookTitlesByCourse
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * [weatherLine] 的输入快照：课表侧四个 Room 流 + 天气开关 + [tick]。
     * 五个源是 combine 的标准重载上限，课表侧四个先套一层（类型都在 5 以内）。
     * **声明在 [weatherLine] 之前**：Kotlin 属性按声明顺序初始化，反过来会读到未初始化值。
     */
    private val scheduleInputs = combine(
        combine(
            repo.semester,
            repo.timeSlots,
            repo.courses,
            repo.overrides,
        ) { semester, slots, courses, overrides ->
            TipInputs(semester = semester, slots = slots, courses = courses, overrides = overrides)
        },
        repo.weatherEnabled,
        tick,
    ) { schedule, enabled, _ ->
        schedule.copy(enabled = enabled)
    }

    /**
     * 今日页顶栏那一行（DESIGN §3.29）：天气句 / 课表句 / 快下课有雨，**轮换显示**。
     *
     * 五条口径：
     * 1. **天气开关关着 = 天气一次网络都不发**（[repo] 的 weatherEnabled 是默认关的全局项），
     *    但**课表句照常出**（用户 2026-10-12：天气关着也显示课表提示）——课表句零网络，
     *    数据就是本页已有的 semester / slots / courses / overrides；
     * 2. 随 [tick] 重算（页面每 30 秒一次）：`now` 跨过 18:00 时提示从「今天」自动切到
     *    「明天」，不用等页面重进；命中仓库的 30 分钟 TTL 缓存时零网络；
     * 3. 「快下课有雨」是**独立链路**（[ScheduleTips.dismissalTipFor]）：永远看今天的课，
     *    命中就整行独占——即便 18:00 后正文讲的是明天，此刻还在上的课要下课了照样提示
     *    （2026-10-12 复查补上，早先跟参考日走会在 18:00 后静默消失）；
     *    它零网络（判据 = 课表 + 逐小时预报），天气关着也工作；
     * 4. **天气句与课表句各自独立成句、每 [ROTATE_MILLIS] 轮换**（用户 2026-10-12 定）：
     *    顶栏那一行窄，两句拼一起必被截断；轮换让每条都有整行宽度可读。
     *    只有一条时不轮换、直接常显；
     * 5. 取不到任何句子给 null，顶栏不占位、不显示半截内容。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val weatherLine: StateFlow<String?> = scheduleInputs
        .map { inputs -> segmentsFor(inputs) }
        // 每 30 秒的 tick 大多产出同一组句子：只有这组句子真的变了才重开轮换，
        // 否则每 30 秒会把轮换重置回第一条（2026-10-12 复查补上）
        .distinctUntilChanged()
        .flatMapLatest { segments -> rotate(segments) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * 算当前该显示的候选句（快下课独占 / 天气句 / 课表句），按显示优先级排列。
     * 天气开关关着时零网络，只出课表句。
     */
    private suspend fun segmentsFor(inputs: TipInputs): List<String> {
        val now = nowProvider()
        val weather = weatherRepo
        val snapshot = if (inputs.enabled && weather != null) {
            runCatching { weather.snapshot() }.getOrNull()
        } else {
            null
        }
        if (snapshot != null) {
            return WeatherAdvice.segments(
                snapshot = snapshot,
                now = now,
                semester = inputs.semester,
                slots = inputs.slots,
                courses = inputs.courses,
                overrides = inputs.overrides,
            )
        }
        // 天气开关关着就不发请求（DESIGN §3.29 口径），但课表句照出（零网络）
        val date = now.toLocalDate().plusDays(WeatherAdvice.referenceDayIndex(now).toLong())
        return ScheduleTips.dayTipFor(
            date = date,
            semester = inputs.semester,
            slots = inputs.slots,
            courses = inputs.courses,
            overrides = inputs.overrides,
        )?.let { listOf(it.prefix + it.text) } ?: emptyList()
    }

    /** 多条时每 [ROTATE_MILLIS] 轮换一条；单条常显；空表给 null（页面不占位）。 */
    private fun rotate(segments: List<String>): Flow<String?> = flow {
        when {
            segments.isEmpty() -> emit(null)
            segments.size == 1 -> emit(segments.first())
            else -> {
                var index = 0
                while (true) {
                    emit(segments[index % segments.size])
                    index++
                    delay(ROTATE_MILLIS)
                }
            }
        }
    }

    private companion object {
        /** 顶栏多条提示的轮换间隔（毫秒）。 */
        const val ROTATE_MILLIS = 5_000L
    }

    /** [weatherLine] 的输入快照：课表侧四个 Room 流 + 天气开关 + tick 的产物。 */
    private data class TipInputs(
        /** 天气开关；课表句不看它（详见 [weatherLine] 的口径 1）。 */
        val enabled: Boolean = true,
        val semester: SemesterConfig?,
        val slots: List<TimeSlot>,
        val courses: List<Course>,
        val overrides: List<ScheduleOverride>,
    )

    /**
     * 卡片开关的首帧初值。这三个开关独立于 [ready] 门闸（那管的是 Room 派生状态），
     * DataStore 的真值必然晚于首帧：初值写 true 的话，关过卡的用户冷启动会先渲染出
     * 卡片、真值到达后再消失（跳一下，2026-09-28 通用设置页同款反馈）。
     * 构造时阻塞读一次真值当初值，与 MainActivity 启动链的 runBlocking 读同一模式。
     */
    private val initialPrefs: DisplayPrefs = runBlocking { repo.displayPrefs.first() }

    /**
     * 今日页开水卡片开关（DESIGN §3.3 底部固定区）。与 [shortcuts] 同理单独订阅：
     * 纯设置值，改动不应触发今日页课表状态重算。
     */
    val waterCardEnabled: StateFlow<Boolean> = repo.displayPrefs
        .map { it.waterCardEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialPrefs.waterCardEnabled)

    /**
     * 今日页趣智校园卡片开关（DESIGN §4.30）。与 [waterCardEnabled] 同口径：
     * 纯设置值单独订阅，不掺进课表状态。关掉后胖乖生活卡独占整行。
     */
    val qzxyCardEnabled: StateFlow<Boolean> = repo.displayPrefs
        .map { it.qzxyCardEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialPrefs.qzxyCardEnabled)

    /**
     * 今日页共享单车卡开关（DESIGN §3.9）。与 [waterCardEnabled] 同口径：
     * 纯设置值单独订阅，不掺进课表状态。
     */
    val ebikeCardEnabled: StateFlow<Boolean> = repo.displayPrefs
        .map { it.ebikeCardEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialPrefs.ebikeCardEnabled)

    /**
     * 今日页校园卡付款码卡开关（DESIGN §3.10）。默认关（涉及凭证与资金），
     * 「我的 → 校园卡」子页控制；同样单独订阅不掺课表状态。
     *
     * 2026-09-24 起今日页不再展示一卡通卡（能力收进生活页，DESIGN §3.13），
     * 该开关仅剩历史偏好值；保留 Flow 以免清理旧偏好数据。
     */
    val campusCardEnabled: StateFlow<Boolean> = repo.displayPrefs
        .map { it.campusCardEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** 界面每 30 秒调一次：让「还剩 X 分钟」和课的状态跟着时间走。 */
    fun refreshTick() {
        tick.value = System.currentTimeMillis()
    }

    fun upsert(course: Course) {
        viewModelScope.launch { repo.upsertCourse(course) }
    }

    fun deleteCourse(course: Course) {
        viewModelScope.launch {
            repo.deleteCourse(course)
            _undoable.value = UndoableMessage("已删除「${course.name}」") {
                repo.upsertCourse(course)
            }
        }
    }

    // ---- 撤销（DESIGN §3.3）：删除后 5 秒内可撤销 ----

    private val _undoable = MutableStateFlow<UndoableMessage?>(null)

    /** 带撤销动作的反馈；UI 侧消费后调 [consumeUndoable] 置空。 */
    val undoable: StateFlow<UndoableMessage?> = _undoable

    fun consumeUndoable() {
        _undoable.value = null
    }

    class Factory(
        private val repo: ScheduleRepository,
        private val homeworkRepo: HomeworkRepository,
            private val weatherRepo: WeatherRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
                TodayViewModel(repo, homeworkRepo, weatherRepo = weatherRepo) as T
    }
}
