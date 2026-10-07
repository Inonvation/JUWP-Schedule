package edu.jxslu.schedule.domain

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 教学日历（DESIGN §4.42）：教务「教学日历查询」页的抽取值——只取用户要的那一件事：
 * **第 1 周周一 = 开学日**。
 *
 * [term] 是这份日历对应的学期（`2026-2027-1` 形态）。页面本身有一张 25 周的
 * 「周次 × 日期」表，但我们刻意不把整表存下来：它唯一的消费点是「导入课表后校准开学日」，
 * 多存无益（YAGNI）。
 */
data class TeachingCalendar(
    val startDate: LocalDate,
    val term: String,
)

/**
 * 教学日历的纯规则（JVM 可测）：页面只给「第 1 周周六的 `MM月DD日`」，
 * 年份与周一靠学期号推导，规则全部收在这里。
 *
 * 页面为什么只有周末列带月份：工作日列渲染的是**裸日号**（`31`、`01`，跨月也不带月份），
 * 周末列才是 `09月05日`。所以：周一 = 周六日期 − 5 天，
 * 年份取「让结果落在该学期窗口内」的那个候选年。
 */
object TeachingCalendarRules {

    /** 学期号的起止年（`2026-2027-1` → 2026 to 2027）；年份不连续或格式不符返回 null。 */
    fun termYears(term: String): Pair<Int, Int>? {
        val m = Regex("""(\d{4})-(\d{4})-(\d)""").matchEntire(term) ?: return null
        val y1 = m.groupValues[1].toIntOrNull() ?: return null
        val y2 = m.groupValues[2].toIntOrNull() ?: return null
        return if (y2 == y1 + 1) y1 to y2 else null
    }

    /**
     * 由学期号 + 第 1 周周六的（月、日）推导第 1 周周一。
     *
     * 年份口径：`-1`（秋）学期落起始年 7–12 月，`-2`（春）学期落次年年 1–6 月。
     * 递错的年份会先被「周六格必须是周六日期」挡下、再被窗口校验拦下——
     * 这也是「教务忽略参数回了别的学期」的第二道闸（第一道在解析器里：
     * 响应学期 ≠ 请求学期直接拒收）。
     *
     * 已知边界：窗口按真实学期日历收紧——「第 1 周周六跨到次年 1 月」（第 1 周周一在
     * 12 月 28 日及以后）的极端排法不在支持范围（多数会被周六格星期校验挡下）；
     * 真实校历不会出现，真出现宁可不动开学日。
     */
    fun week1Monday(term: String, satMonth: Int, satDay: Int): LocalDate? {
        val (y1, y2) = termYears(term) ?: return null
        val expectedYear = if (term.endsWith("-2")) y2 else y1
        val saturday = runCatching { LocalDate.of(expectedYear, satMonth, satDay) }.getOrNull()
            ?: return null
        if (saturday.dayOfWeek != DayOfWeek.SATURDAY) return null
        val monday = saturday.minusDays(5)
        val inWindow = when {
            term.endsWith("-1") -> monday.year == y1 && monday.monthValue in 7..12
            term.endsWith("-2") -> monday.year == y2 && monday.monthValue in 1..6
            else -> false
        }
        return monday.takeIf { inWindow }
    }
}
