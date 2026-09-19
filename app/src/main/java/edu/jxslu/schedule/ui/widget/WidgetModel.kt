package edu.jxslu.schedule.ui.widget

import edu.jxslu.schedule.domain.Course
import edu.jxslu.schedule.domain.TimeSlot
import edu.jxslu.schedule.domain.TodayState
import edu.jxslu.schedule.domain.clockOf
import edu.jxslu.schedule.domain.compactPosition
import edu.jxslu.schedule.domain.dayLabel
import edu.jxslu.schedule.domain.sectionRange
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 桌面小组件的**展示模型**（DESIGN §3.6）。
 *
 * 分两层：
 * 1. [WidgetSnapshot]：与尺寸无关的全量快照（可序列化，存进 Glance 的 DataStore 状态）。
 *    widget 的组合只读这份快照——组合里不能直接读 Room（suspend），快照由后台协程刷新。
 * 2. [WidgetSnapshot.forSize]：按尺寸裁出真正渲染的 [WidgetModel]。
 *
 * **课程口径完全来自 [TodayState]**（今日页同源）：哪节课算正在上、什么时候轮到明天上桌，
 * 由 `domain/buildTodayState` 决定，这里只做「信息怎么摆」。
 */

/** 一行课程：时刻 + 课名 + 副行（`@地点 · 教师`）。 */
@Serializable
data class WidgetCourseRow(
    val clock: String,
    val name: String,
    val meta: String,
    val colorIndex: Int,
)

/** 焦点位。 */
@Serializable
sealed interface WidgetFocus {
    /** 正在上课 / 下一节。 */
    @Serializable
    @SerialName("course")
    data class Course(
        /** `正在上课 · 第3-4节` / `下一节 · 第3-4节`（与今日页同款标签） */
        val label: String,
        val name: String,
        /** `@南B208 · 陈磊` */
        val meta: String,
        /** `还有 25 分钟下课` / `课间 · 还有 4 分钟上课`；没有则 null */
        val countdown: String? = null,
        /** 没有倒计时时的兜底文案（`10:15 上课`） */
        val fallbackNote: String? = null,
        val colorIndex: Int,
        /** 正在上课时的整门课进度 0f..1f；不画进度条时 null */
        val progress: Float? = null,
    ) : WidgetFocus

    /** 今天已无待上课程：今日上完 / 今天没课 / 假期中。 */
    @Serializable
    @SerialName("idle")
    data class Idle(val title: String, val detail: String) : WidgetFocus
}

/** 明日预告。 */
@Serializable
sealed interface WidgetTomorrow {
    @Serializable
    @SerialName("courses")
    data class Courses(val title: String, val rows: List<WidgetCourseRow>) : WidgetTomorrow

    @Serializable
    @SerialName("rest")
    data class Rest(val title: String, val detail: String) : WidgetTomorrow
}

/**
 * 与尺寸无关的全量快照。
 *
 * @param header 顶栏一行：`9月18日 周四 · 第 7 周`
 * @param rows 今日剩余列表（**不含焦点那节**，与今日页同一去重口径，未截断）
 * @param tomorrow 明日块（大尺寸才渲染）；今天还有待上课程时为 null
 */
@Serializable
data class WidgetSnapshot(
    val header: String,
    val focus: WidgetFocus,
    val rows: List<WidgetCourseRow> = emptyList(),
    val tomorrow: WidgetTomorrow? = null,
)

/**
 * 尺寸档：小组件在桌面上实际占多大。
 *
 * 2026-09-19 起目录条目为 2×2 / 4×2 / 4×4（DESIGN §3.6），**所有档位都带日期行**
 * ——包括 2×2（紧凑样式，课名让出一行）。[Tall] 不再是目录条目，保留给
 * 「4×2 被竖向拉高」的形态兜底。
 */
enum class WidgetSize(val maxRows: Int, val showTomorrow: Boolean, val showHeader: Boolean) {
    /** 2×2：紧凑日期行 + 焦点课（课名一行，地点/教师保留，长名截断）。 */
    Small(maxRows = 0, showTomorrow = false, showHeader = true),

    /**
     * 4×2 横条：目录条目之一。110dp 高只放得下日期行 + 焦点卡
     * （实测行高预算：日期 17dp + 焦点卡 ≈70dp ≈ 满格），剩余课程不放，
     * 与 2×2 的差别是横向更宽、日期与焦点卡都更舒展。
     */
    Wide(maxRows = 0, showTomorrow = false, showHeader = true),

    /** 2×4 竖条（4×2 竖向拉伸的兜底档）：焦点 + 三行剩余。 */
    Tall(maxRows = 3, showTomorrow = false, showHeader = true),

    /** 4×4：焦点 + 全部剩余 + 明日块。 */
    Large(maxRows = Int.MAX_VALUE, showTomorrow = true, showHeader = true),
}

/**
 * 一档尺寸的渲染输入（由 [WidgetSnapshot.forSize] 裁出）。
 *
 * @param rowsMoreLabel 列表被截断时的提示（`还有 2 节`）；未截断为 null
 */
data class WidgetModel(
    val header: String,
    val focus: WidgetFocus,
    val rows: List<WidgetCourseRow>,
    val rowsMoreLabel: String?,
    val tomorrow: WidgetTomorrow?,
)

/**
 * 按可用宽高选档。分界取各条目默认 dp 的一半左右：
 * 2×2 默认 110×110、4×2 默认 250×110、4×4 默认 250×250、2×4（拉伸兜底）110×250。
 *
 * 国产 ROM 给的尺寸不一定对齐整数格位，因此按「够不够放」判，不要求精确等于某档；
 * 横条（宽 ≥ 2 格但高不足 2 格）走 [WidgetSize.Wide]——它既是 4×2 条目的默认档，
 * 也是竖条被横向拉扁后的落点。
 */
fun widgetSizeFor(widthDp: Int, heightDp: Int): WidgetSize = when {
    heightDp < 180 -> if (widthDp >= 180) WidgetSize.Wide else WidgetSize.Small
    widthDp < 180 -> WidgetSize.Tall
    else -> WidgetSize.Large
}

/** 按尺寸裁剪快照：只决定「放得下多少」，不改任何课程取舍。 */
fun WidgetSnapshot.forSize(size: WidgetSize): WidgetModel {
    val shown = rows.take(size.maxRows)
    val hidden = rows.size - shown.size
    return WidgetModel(
        header = header,
        focus = focus,
        rows = shown,
        // maxRows = 0 的档位（2×2 / 4×2）整列都不放，「还有 N 节」也省——
        // 一行提示挤不掉，信息以日期行 + 焦点卡为准
        rowsMoreLabel = if (hidden > 0 && size.maxRows > 0) "还有 $hidden 节" else null,
        tomorrow = if (size.showTomorrow) tomorrow else null,
    )
}

/**
 * 今日状态 → 全量快照。
 *
 * 分支与今日页一致（DESIGN §3.3）：
 * - 有焦点课 → 焦点卡（正在上课带进度条）
 * - 焦点为空 → 今天上完 / 今天没课 / 假期中，各给一句落点
 * - 明日块只在 [TodayState.tomorrowVisible]（今天没有待上课程）时出现
 */
fun buildWidgetSnapshot(state: TodayState): WidgetSnapshot {
    val header = buildString {
        append("${state.date.monthValue}月${state.date.dayOfMonth}日")
        append(" 周${dayLabel(state.day)}")
        if (state.week > 0) append(" · 第 ${state.week} 周")
    }
    return WidgetSnapshot(
        header = header,
        focus = buildFocus(state),
        rows = state.listCourses.map { it.toRow(state.slots) },
        tomorrow = buildTomorrow(state),
    )
}

private fun buildFocus(state: TodayState): WidgetFocus {
    val course = state.focus
    if (course == null) {
        // 焦点为空：假期中 / 今天上完 / 今天没课，三种文案
        val (title, detail) = when {
            !state.inTerm -> "假期中" to "未在学期内，去「我的」设置开学日期"
            state.todayAllDone -> "今天的课都上完了" to "今天共 ${state.todayTotal} 节"
            else -> "今天没有课" to "去「课表」看看本周安排"
        }
        return WidgetFocus.Idle(title = title, detail = detail)
    }

    val ongoing = state.ongoing != null
    val countdown: String?
    val fallback: String?
    if (ongoing) {
        // 与今日页同一取舍：优先细粒度倒计时，退整门口径
        countdown = state.ongoingCountdown
            ?: state.minutesToOngoingEnd?.let { "还有 $it 分钟下课" }
        fallback = null
    } else {
        // ≤60 分钟说还剩多久；更远直接给上课时刻（避免「还有 1430 分钟上课」这种废话）
        val minutes = state.minutesToNext
        countdown = minutes?.takeIf { it <= 60 }?.let { "还有 $it 分钟上课" }
        fallback = if (countdown == null) "${clockOf(state.slots, course)} 上课" else null
    }

    return WidgetFocus.Course(
        label = (if (ongoing) "正在上课" else "下一节") + " · " + sectionRange(course),
        name = course.name,
        meta = metaOf(course),
        countdown = countdown,
        fallbackNote = fallback,
        colorIndex = course.colorIndex,
        progress = if (ongoing) state.ongoingProgress else null,
    )
}

private fun buildTomorrow(state: TodayState): WidgetTomorrow? {
    if (!state.tomorrowVisible) return null
    val title = "明天 · 周${dayLabel(state.tomorrowDay)}"
    if (state.tomorrowCourses.isEmpty()) {
        return WidgetTomorrow.Rest(title = title, detail = "没有课，可以放松一下")
    }
    return WidgetTomorrow.Courses(
        title = title,
        rows = state.tomorrowCourses.map { it.toRow(state.slots) },
    )
}

private fun Course.toRow(slots: List<TimeSlot>): WidgetCourseRow = WidgetCourseRow(
    clock = clockOf(slots, this),
    name = name,
    meta = metaOf(this),
    colorIndex = colorIndex,
)

/**
 * 小组件的副行文案：`@南B208 · 陈磊`。
 *
 * 与今日页 `metaLine` 的差别：widget 不放时刻（时刻在行首或焦点位已给出），
 * 否则小尺寸里会出现两遍 `10:15`。
 */
private fun metaOf(course: Course): String {
    val location = compactPosition(course.position)
    return listOfNotNull(
        location.takeIf { it.isNotBlank() }?.let { "@$it" },
        course.teacher.takeIf { it.isNotBlank() },
    ).joinToString(" · ")
}

/** 快照与 Glance 状态之间的编解码；解码失败返回 null（宁可重读 Room，也不崩在组合里）。 */
internal object WidgetSnapshotCodec {
    private val json = Json { ignoreUnknownKeys = true }

    // 显式传 serializer：reified 版是扩展函数，需要额外 import，
    // 在 object 里容易漏（漏了就会把 snapshot 当成 SerializationStrategy 报类型错）
    fun encode(snapshot: WidgetSnapshot): String =
        json.encodeToString(WidgetSnapshot.serializer(), snapshot)

    fun decode(raw: String?): WidgetSnapshot? =
        raw?.let { runCatching { json.decodeFromString(WidgetSnapshot.serializer(), it) }.getOrNull() }
}
