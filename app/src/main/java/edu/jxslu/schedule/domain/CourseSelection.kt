package edu.jxslu.schedule.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 一条选课记录（DESIGN §4.35）：教务「学生选课中心 → 选课结果查询」里的一行选课日志
 * （接口 `/jsxsd/xkgl/loadXsxkjgList?lx=xkrz&type=list`，layui JSON）。
 *
 * 与 [ScoreRecord] 同构：全局归属学生、不挂 timetableId、按学期整体替换。
 * **不是**课表课程（[Course]）——这里没有排课网格语义，课表由导入链路单独写。
 * [timeText] / [placeText] 是教务原文（多行以 `\n` 分隔），只做展示，不解析成节次。
 */
data class CourseSelection(
    val id: Long = 0,
    /** 学年学期，如 2026-2027-1。 */
    val term: String,
    val courseNo: String = "",
    val name: String,
    val teacher: String = "",
    val credit: Double = 0.0,
    val hours: Double = 0.0,
    /** 课程属性：必修 / 任选…（教务 kclb_mc）。 */
    val attribute: String = "",
    /** 课程性质：集中实践教学环节 / 通识必修课 / 专业任选课…（教务 kcxz_mc）。 */
    val category: String = "",
    /** 教学班（教务 ktmc，如「24机械设计制造及其自动化[01-04]班」）。 */
    val className: String = "",
    /** 开课学院（教务 yx_mc）。 */
    val college: String = "",
    /** 上课时间原文（教务 sksj，多行）。 */
    val timeText: String = "",
    /** 上课地点原文（教务 skdd，多行）。 */
    val placeText: String = "",
    /** 审核状态（教务 shzt，通常为空）。 */
    val status: String = "",
    /** 审核意见（教务 yy，通常为空）。 */
    val remark: String = "",
)

/** 一个学期的选课汇总（只统计、不做及格判定——选课是"选上了"的记录，没有成绩语义）。 */
data class SelectionSummary(
    val count: Int,
    /** 学分合计，两位小数。 */
    val credits: Double,
)

object SelectionCalculator {

    fun summarize(rows: List<CourseSelection>): SelectionSummary? {
        if (rows.isEmpty()) return null
        return SelectionSummary(
            count = rows.size,
            credits = Math.round(rows.sumOf { it.credit } * 100.0) / 100.0,
        )
    }
}

/**
 * 一个选课轮次（DESIGN §4.35）：教务「学生选课中心」列表的一行
 * （接口 `/jsxsd/xsxk/xklc_list_data`）。
 *
 * [timeText] 是教务原文（列 `xksj`，格式未实测——非选课期接口为空）；
 * [startAt] / [endAt] 是容错解析出的毫秒时刻，**解析不了就是 null**，展示回退原文、
 * 提醒也不排（宁可少提醒，不猜时间）。
 */
data class SelectionRound(
    /** 轮次 id（教务 jx0502zbid），进选课与去重键都用它。 */
    val id: String,
    /** 学年学期（教务 xqmc）。 */
    val term: String = "",
    /** 选课名称（教务 xklc_mc）。 */
    val name: String,
    /** 选课时间原文（教务 xksj）。 */
    val timeText: String = "",
    val startAt: Long? = null,
    val endAt: Long? = null,
    /** 是否可预览（教务 yxzt == '1' 时页面给「预览选课」按钮）。 */
    val canPreview: Boolean = false,
)

/** 轮次状态（按解析出的起止时刻判定；时间解析不了 = [Unknown]，不编状态）。 */
enum class SelectionRoundPhase { Upcoming, Active, Ended, Unknown }

object SelectionRounds {

    /** 「开始前」提醒的提前量：开始前 30 分钟。 */
    const val START_LEAD_MS: Long = 30 * 60 * 1000L

    /** 「截止前」提醒的提前量：截止前 6 小时。 */
    const val END_LEAD_MS: Long = 6 * 60 * 60 * 1000L

    fun phase(round: SelectionRound, now: Long): SelectionRoundPhase {
        val start = round.startAt
        val end = round.endAt
        if (start == null && end == null) return SelectionRoundPhase.Unknown
        if (start != null && now < start) return SelectionRoundPhase.Upcoming
        if (end != null) return if (now > end) SelectionRoundPhase.Ended else SelectionRoundPhase.Active
        // 只有开始时间且已过：进行中还是已结束无法判定，不编
        return SelectionRoundPhase.Unknown
    }

    /**
     * 学期号的后继：`2026-2027-1` → `2026-2027-2`、`2026-2027-2` → `2027-2028-1`。
     * 格式不认返回 null。用途：选课结果默认抓「教务当前学期 + 下一学期」——选课常常
     * 发生在学期末选下学期的课，光抓当前学期会漏掉「已选下学期」这件最要紧的事。
     */
    fun nextTerm(term: String): String? {
        val m = termRe.matchEntire(term.trim()) ?: return null
        val y1 = m.groupValues[1].toInt()
        val y2 = m.groupValues[2].toInt()
        return if (m.groupValues[3] == "1") {
            "$y1-$y2-2"
        } else {
            "${y1 + 1}-${y2 + 1}-1"
        }
    }

    /**
     * 挑「当前该操作的轮次」：**进行中**的优先；没有就取最近一个**即将开始**的；
     * 都没有返回 null（已结束的不返回——它不是可操作目标）。
     * 选课页的入口与应用内选课中心共用这一份口径，别各写一套。
     */
    fun pickTarget(rounds: List<SelectionRound>, now: Long): SelectionRound? =
        rounds.firstOrNull { phase(it, now) == SelectionRoundPhase.Active }
            ?: rounds.filter { phase(it, now) == SelectionRoundPhase.Upcoming }
                .minByOrNull { it.startAt ?: Long.MAX_VALUE }

    /**
     * 从教务原文里容错解析起止时刻（本地时区）。
     *
     * 教务格式未实测（非选课期 `xksj` 为空），按常见形态兜底：
     * 含时刻的日期对（`2026-09-28 08:00 ~ 2026-10-05 17:00`，秒可选）→ 两个时刻；
     * 只有日期对（`2026-09-28 ~ 2026-10-05`）→ 起 00:00、止当日 23:59:59.999；
     * 只解析到一个 → 当开始时刻、结束为 null；一个都没有 → (null, null)。
     * **解析失败不抛**：展示回退原文，提醒跳过。
     */
    fun parseTimeRange(text: String, zone: ZoneId = ZoneId.systemDefault()): Pair<Long?, Long?> {
        val dateTimes = dateTimeRe.findAll(text).mapNotNull { m ->
            runCatching {
                LocalDateTime.of(
                    m.groupValues[1].toInt(),
                    m.groupValues[2].toInt(),
                    m.groupValues[3].toInt(),
                    m.groupValues[4].toInt(),
                    m.groupValues[5].toInt(),
                    m.groupValues[6].toIntOrNull() ?: 0,
                ).atZone(zone).toInstant().toEpochMilli()
            }.getOrNull()
        }.toList()
        if (dateTimes.size >= 2) return dateTimes[0] to dateTimes[1]
        if (dateTimes.size == 1) return dateTimes[0] to null

        val dates = dateRe.findAll(text).mapNotNull { m ->
            runCatching {
                LocalDate.of(
                    m.groupValues[1].toInt(),
                    m.groupValues[2].toInt(),
                    m.groupValues[3].toInt(),
                ).atStartOfDay(zone).toInstant().toEpochMilli()
            }.getOrNull()
        }.toList()
        if (dates.size >= 2) {
            return dates[0] to (dates[1] + DAY_MS - 1)
        }
        if (dates.size == 1) return dates[0] to null
        return null to null
    }

    /**
     * 一个待提醒点：开始/截止的提前量时刻。
     *
     * [key] 是对外唯一身份（`轮次id|start` / `轮次id|end`），已发去重、闹钟重排都认它。
     * [at] 是**触发时刻**（提前量点，已过但事件没结束的钳到 now）、[eventAt] 是事件本身的
     * 时刻（开始/截止），通知文案按它算「还有多久」。
     * 只有在事件尚未结束（`事件时刻 > now`）时才生成。
     */
    data class ReminderPoint(
        val key: String,
        val roundId: String,
        val roundName: String,
        val at: Long,
        val eventAt: Long,
        val kind: Kind,
    ) {
        enum class Kind { StartSoon, EndSoon }
    }

    fun reminderPoints(rounds: List<SelectionRound>, now: Long): List<ReminderPoint> =
        rounds.flatMap { round ->
            buildList {
                round.startAt?.let { start ->
                    if (start > now) {
                        add(
                            ReminderPoint(
                                key = "${round.id}|start",
                                roundId = round.id,
                                roundName = round.name,
                                at = maxOf(start - START_LEAD_MS, now),
                                eventAt = start,
                                kind = ReminderPoint.Kind.StartSoon,
                            ),
                        )
                    }
                }
                round.endAt?.let { end ->
                    if (end > now) {
                        add(
                            ReminderPoint(
                                key = "${round.id}|end",
                                roundId = round.id,
                                roundName = round.name,
                                at = maxOf(end - END_LEAD_MS, now),
                                eventAt = end,
                                kind = ReminderPoint.Kind.EndSoon,
                            ),
                        )
                    }
                }
            }
        }.sortedBy { it.at }

    private const val DAY_MS = 24L * 60 * 60 * 1000

    private val termRe = Regex("""(\d{4})-(\d{4})-([12])""")

    private val dateTimeRe = Regex(
        """(\d{4})\s*[-/.年]\s*(\d{1,2})\s*[-/.月]\s*(\d{1,2})\s*日?\s*""" +
            """(\d{1,2})\s*[:：时]\s*(\d{1,2})(?:\s*[:：分]\s*(\d{1,2}))?""",
    )

    private val dateRe = Regex("""(\d{4})\s*[-/.年]\s*(\d{1,2})\s*[-/.月]\s*(\d{1,2})\s*日?""")
}
