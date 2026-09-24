package edu.jxslu.schedule.data.power

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale

/**
 * 缴费账单的月度聚合（DESIGN §3.13「缴费账单页」/ §4.24）。
 *
 * 数据源只有一条已验证的接口：`/charge/turnover/personal_data?feeitemid=181&flag=3`
 * （充值/退款记录）。平台的 `/bill` 网页给的是同一批记录的按月合计，所以本地算即可——
 * 不猜平台的账单接口，也不为这一页多发一条请求。
 *
 * **月份归属取缴费日期（`createdate`）的年月，不取 `feerange`**：后者是费用所属月，
 * 9 月 1 日交 8 月的费时两者差一个月，而用户问的是「这个月花了多少」。
 * 日期解析不出来才退回 `feerange`（`202608` 形态）；两者都没有的归到 [UNKNOWN_MONTH]。
 *
 * 纯 JVM，可单测（`PowerBillTest`）。
 */
data class PowerBillMonth(
    /** 月份键 `2026-08`；[UNKNOWN_MONTH] 表示日期与 `feerange` 都没解析出来。 */
    val key: String,
    /** 该月充值合计（分，不含退款）。 */
    val rechargeFen: Long,
    /** 该月退款合计（分，正数）。 */
    val refundFen: Long,
    /** 该月记录条数（含退款）。 */
    val count: Int,
)

object PowerBill {

    /** 月份认不出来时的分组键（排在最后，标签「月份未知」）。 */
    const val UNKNOWN_MONTH = ""

    /** 账单页窗口：近 12 个月（含当月）。 */
    const val WINDOW_MONTHS = 12

    /** 一条记录归到哪个月。 */
    fun monthKeyOf(turnover: PowerTurnover): String {
        if (turnover.epochMs > 0) {
            val date = Instant.ofEpochMilli(turnover.epochMs).atZone(ZoneId.systemDefault()).toLocalDate()
            return "%04d-%02d".format(date.year, date.monthValue)
        }
        return normalizeCompact(turnover.month)
    }

    /**
     * 按月聚合，**月份键降序**（新的在前）；[UNKNOWN_MONTH] 恒排最后。
     * 只保留出现过的月——没有记录的月由 [recentMonthKeys] 补位，不在这里造空条目。
     */
    fun monthsOf(rows: List<PowerTurnover>): List<PowerBillMonth> =
        rows.groupBy(::monthKeyOf)
            .map { (key, group) ->
                PowerBillMonth(
                    key = key,
                    rechargeFen = group.filterNot { it.refund }.sumOf { it.amountFen },
                    refundFen = group.filter { it.refund }.sumOf { it.amountFen },
                    count = group.size,
                )
            }
            // 先按「是不是未知月」升序（false 在前 = 未知月沉底），再按月份键降序
            .sortedWith(compareBy<PowerBillMonth> { it.key.isEmpty() }.thenByDescending { it.key })

    /** 某月明细，按时间**倒序**（新的在前）；同刻按 id 降序，保证顺序稳定。 */
    fun rowsOf(rows: List<PowerTurnover>, monthKey: String): List<PowerTurnover> =
        rows.filter { monthKeyOf(it) == monthKey }
            .sortedWith(compareByDescending<PowerTurnover> { it.epochMs }.thenByDescending { it.turnoverId ?: 0L })

    /** 近 [count] 个月的键（含当月，升序）——月切换与柱状图共用一份口径。 */
    fun recentMonthKeys(now: YearMonth, count: Int = WINDOW_MONTHS): List<String> =
        (count - 1 downTo 0).map { offset ->
            val month = now.minusMonths(offset.toLong())
            "%04d-%02d".format(month.year, month.monthValue)
        }

    /** 月份标签：`2026-08` → 「2026 年 8 月」；[UNKNOWN_MONTH] → 「月份未知」。 */
    fun monthLabel(key: String): String {
        if (key == UNKNOWN_MONTH) return "月份未知"
        val (year, month) = key.split("-").takeIf { it.size == 2 } ?: return key
        val monthValue = month.trimStart('0').toIntOrNull() ?: return key
        return "${year.toIntOrNull() ?: year} 年 $monthValue 月"
    }

    /** 柱状图的短标签：`2026-08` → `8`（轴下只标月份数字）。 */
    fun shortMonthLabel(key: String): String =
        key.split("-").getOrNull(1)?.trimStart('0')?.takeIf { it.isNotEmpty() } ?: key

    /** 金额文案（分 → `¥12.00`）；固定 Locale.US，免得系统语言把小数点换成逗号。 */
    fun amountText(fen: Long): String = String.format(Locale.US, "¥%.2f", fen / 100.0)

    /** `feerange` 的 `202608` 形态 → `2026-08`；不是 6 位数字就给 [UNKNOWN_MONTH]。 */
    private fun normalizeCompact(raw: String?): String {
        val text = raw.orEmpty().trim()
        if (text.length != 6 || !text.all { it.isDigit() }) return UNKNOWN_MONTH
        return "${text.substring(0, 4)}-${text.substring(4)}"
    }
}
