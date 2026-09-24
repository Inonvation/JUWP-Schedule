package edu.jxslu.schedule.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * 寝室电费的**用电量**推算（DESIGN §3.13「用电统计」，2026-09-24）。
 *
 * 平台没有用电量接口（只有「当前剩余电量」这一个读数，见 `PowerReadingEntity` 的 KDoc），
 * 所以用电量只能靠本机记录的电表读数差分。三条口径：
 *
 * 1. **一段的用量** = 上次度数 + 段内充值度数 − 这次度数。充值度数 = 充值金额 ÷ 单价：
 *    电表加电是从余额里加的，不减掉它，充过值的那一段就会算成「没用过电」。
 *    单价缺失（项目详情没给 price）时：段内没有充值就照常差分（度数差分不需要单价），
 *    有充值就**整段跳过**——宁可少一段，也不按猜的单价编出一段用量。
 * 2. **算不出来的段整段跳过，不归零**：读数差值为负说明电表涨得比已知的充值还多
 *    （流水还没取到、平台登记延迟、电控侧改过数），此时那段用量根本无从得知——
 *    按 0 记等于凭空抹掉几天的电，按差额记又可能是个负数。两样都不做，
 *    只把它记进 [PowerUsageSummary.skippedSegments]，由页面如实告诉用户。
 * 3. **跨天按时长均摊**：两条读数之间可能隔了几天（记录密度 = 用户打开 App 的密度），
 *    这一段的用量按它在每个自然日里的**时间占比**摊到各天，再按自然日汇总成周（周一起）与月。
 *    读数稀疏时按天看到的就是均摊值，页面里如实说明。
 *
 * 纯 JVM，可单测（`PowerUsageTest`）。
 */

/** 一条电表读数（本机记录，见 `data/local/PowerReadingEntity`）。 */
data class PowerReading(
    val epochMs: Long,
    /** 剩余电量（度）。 */
    val remainKwh: Double,
    /** 单价（元/度）；0 = 未知。 */
    val priceYuan: Double,
    /** 房间标识（换寝室后不把两间房的读数串起来）。 */
    val roomId: String,
)

/** 一笔充值（用于把「加进去的电」从用量里扣掉）。 */
data class PowerRechargePoint(val epochMs: Long, val amountFen: Long)

/** 统计粒度。 */
enum class PowerUsageRange { Day, Week, Month }

/** 相邻两条读数之间的一段用量。 */
data class PowerUsageSegment(
    val startMs: Long,
    val endMs: Long,
    val usedKwh: Double,
    /** 折算金额（元）；单价未知为 null。 */
    val usedYuan: Double?,
    /** 段内充值合计（分）。 */
    val rechargeFen: Long,
)

/** 一天的用量与充值。 */
data class PowerUsageDay(
    /** `2026-09-24`。 */
    val key: String,
    val usedKwh: Double,
    val usedYuan: Double?,
    val rechargeFen: Long,
)

/** 一个统计桶（天 / 周 / 月）。 */
data class PowerUsageBucket(
    /** 天的键是日期，周的键是那一周的周一日期，月的键是 `2026-09`。 */
    val key: String,
    val usedKwh: Double,
    /** 折算金额（元）；该桶里有单价未知的用量时为 null。 */
    val usedYuan: Double?,
    val rechargeFen: Long,
)

/** 用电统计的一次完整结果。 */
data class PowerUsageSummary(
    /** 窗口内的桶（升序，含没有用量的空桶）。 */
    val buckets: List<PowerUsageBucket>,
    /** 最新一条读数（剩余电量与时刻）；没有记录为 null。 */
    val latest: PowerReading?,
    /** 参与统计的读数条数。 */
    val readingCount: Int,
    /** 首末读数相隔的天数（0 = 只有一条读数）。 */
    val spanDays: Int,
    /** 窗口内用电合计（度）。 */
    val totalKwh: Double,
    /** 窗口内用电合计（元）；窗口里有单价未知的用量时为 null。 */
    val totalYuan: Double?,
    /** 窗口内充值合计（分）。 */
    val totalRechargeFen: Long,
    /** 被跳过的相邻读数对（缺单价又有充值）；> 0 时页面如实提示。 */
    val skippedSegments: Int,
)

object PowerUsage {

    /** 各粒度的默认窗口：近 14 天 / 近 12 周 / 近 12 个月。 */
    fun windowOf(range: PowerUsageRange): Int = when (range) {
        PowerUsageRange.Day -> 14
        PowerUsageRange.Week -> 12
        PowerUsageRange.Month -> 12
    }

    /**
     * 只留最新读数那个房间的读数（升序）。
     *
     * 判断依据是**最新那条读数**的房间：换寝室后曲线从新房间第一条读数重新开始，
     * 旧房间的记录留着但不参与统计（两间房的电表各记各的，混起来没有意义）。
     */
    fun readingsOfLatestRoom(readings: List<PowerReading>): List<PowerReading> {
        val latest = readings.maxByOrNull { it.epochMs } ?: return emptyList()
        return readings.filter { it.roomId == latest.roomId }.sortedBy { it.epochMs }
    }

    // ------------------------------------------------------------------
    // 桶键与标签
    // ------------------------------------------------------------------

    /** 时刻 → 自然日键 `2026-09-24`。 */
    fun dayKeyOf(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate().toString()

    /** 自然日键 → 所属桶的键（周桶取那一周的周一）。 */
    fun bucketKeyOf(range: PowerUsageRange, dayKey: String): String {
        if (range == PowerUsageRange.Month) return dayKey.take(7)
        if (range == PowerUsageRange.Day) return dayKey
        val date = runCatching { LocalDate.parse(dayKey) }.getOrNull() ?: return dayKey
        return mondayOf(date).toString()
    }

    /** 近 [count] 个桶的键（升序，最后一个是当前所在的桶）。 */
    fun recentKeys(
        range: PowerUsageRange,
        today: LocalDate,
        count: Int = windowOf(range),
    ): List<String> = (count - 1 downTo 0).map { offset ->
        when (range) {
            PowerUsageRange.Day -> today.minusDays(offset.toLong()).toString()
            PowerUsageRange.Week -> mondayOf(today).minusWeeks(offset.toLong()).toString()
            PowerUsageRange.Month -> monthKeyOf(today.withDayOfMonth(1).minusMonths(offset.toLong()))
        }
    }

    /** 列表行的桶标签（`今天` / `09-24` / `本周` / `9/21 起` / `本月` / `2026 年 8 月`）。 */
    fun labelOf(range: PowerUsageRange, key: String, today: LocalDate): String = when (range) {
        PowerUsageRange.Day -> when (key) {
            today.toString() -> "今天"
            today.minusDays(1).toString() -> "昨天"
            else -> key.substringAfter('-', key).takeIf { it.length == 5 } ?: key
        }

        PowerUsageRange.Week ->
            if (key == mondayOf(today).toString()) "本周" else "${shortDateLabel(key)} 起"

        PowerUsageRange.Month ->
            if (key == monthKeyOf(today.withDayOfMonth(1))) "本月" else monthLabelOf(key)
    }

    /** 柱状图两端的短标签（日 → `24`，周 → `9/21`，月 → `8`）。 */
    fun axisLabel(range: PowerUsageRange, key: String): String = when (range) {
        PowerUsageRange.Day -> key.substringAfterLast('-', key).trimStart('0').ifEmpty { key }
        PowerUsageRange.Week -> shortDateLabel(key)
        PowerUsageRange.Month -> key.substringAfter('-', key).trimStart('0').ifEmpty { key }
    }

    // ------------------------------------------------------------------
    // 推算
    // ------------------------------------------------------------------

    /**
     * 相邻读数之间的一段段用量。
     *
     * 段内充值归入「时刻晚于上一次读数、不晚于这一次读数」的那些流水——充值只在
     * 两次读数的读数差里体现一次，边界取「(上次, 这次]」可以保证既不漏也不重。
     *
     * 跳过的两种段（都不进结果，由 [PowerUsageSummary.skippedSegments] 计数）：
     * 段内有充值但单价未知；读数差值算出来是负数。
     */
    fun segments(
        readings: List<PowerReading>,
        recharges: List<PowerRechargePoint>,
    ): List<PowerUsageSegment> {
        if (readings.size < 2) return emptyList()
        val points = recharges.sortedBy { it.epochMs }
        val out = ArrayList<PowerUsageSegment>(readings.size - 1)
        val sorted = readings.sortedBy { it.epochMs }
        for (index in 0 until sorted.size - 1) {
            val previous = sorted[index]
            val current = sorted[index + 1]
            if (current.epochMs <= previous.epochMs) continue
            val rechargeFen = points
                .filter { it.epochMs > previous.epochMs && it.epochMs <= current.epochMs }
                .sumOf { it.amountFen }
            val price = current.priceYuan.takeIf { it > 0 }
                ?: previous.priceYuan.takeIf { it > 0 }
            val rechargeKwh = if (rechargeFen == 0L) {
                0.0
            } else {
                // 有充值但单价未知：这一段的度数换不出来，整段跳过
                val unit = price ?: continue
                (rechargeFen / 100.0) / unit
            }
            val usedKwh = (previous.remainKwh + rechargeKwh - current.remainKwh)
            // 电量涨得比已知充值能解释的还多：这段算不出来，跳过（见类 KDoc 第 2 条）
            if (usedKwh < 0) continue
            out += PowerUsageSegment(
                startMs = previous.epochMs,
                endMs = current.epochMs,
                usedKwh = usedKwh,
                usedYuan = price?.let { usedKwh * it },
                rechargeFen = rechargeFen,
            )
        }
        return out
    }

    /**
     * 按自然日汇总：每段用量按时间占比摊到它跨过的各天，充值归到事件当天
     * （充值是瞬时事件，没有摊的必要）。
     */
    fun days(
        segments: List<PowerUsageSegment>,
        recharges: List<PowerRechargePoint>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<PowerUsageDay> {
        val kwh = HashMap<String, Double>()
        val yuan = HashMap<String, Double>()
        val yuanUnknown = HashSet<String>()

        for (segment in segments) {
            val span = (segment.endMs - segment.startMs).coerceAtLeast(1L)
            val slices = splitByDay(segment.startMs, segment.endMs, zone)
            for ((dayKey, overlapMs) in slices) {
                val weight = overlapMs.toDouble() / span.toDouble()
                kwh.merge(dayKey, segment.usedKwh * weight, Double::plus)
                val yuanPart = segment.usedYuan
                if (yuanPart == null) yuanUnknown += dayKey
                else yuan.merge(dayKey, yuanPart * weight, Double::plus)
            }
        }

        val rechargeFen = HashMap<String, Long>()
        for (point in recharges) {
            rechargeFen.merge(dayKeyOf(point.epochMs, zone), point.amountFen, Long::plus)
        }

        val keys = sortedSetOf<String>().apply {
            addAll(kwh.keys)
            addAll(yuanUnknown)
            addAll(rechargeFen.keys)
        }
        return keys.map { key ->
            PowerUsageDay(
                key = key,
                usedKwh = kwh[key] ?: 0.0,
                // 这一天只要有一段单价未知的用量，这天就不报金额（不猜）
                usedYuan = if (key in yuanUnknown) null else yuan[key] ?: 0.0,
                rechargeFen = rechargeFen[key] ?: 0L,
            )
        }
    }

    /** 自然日 → 统计桶（升序）。 */
    fun bucketize(days: List<PowerUsageDay>, range: PowerUsageRange): List<PowerUsageBucket> =
        days.groupBy { bucketKeyOf(range, it.key) }
            .map { (key, group) ->
                PowerUsageBucket(
                    key = key,
                    usedKwh = group.sumOf { it.usedKwh },
                    usedYuan = if (group.any { it.usedYuan == null }) {
                        null
                    } else {
                        group.sumOf { it.usedYuan ?: 0.0 }
                    },
                    rechargeFen = group.sumOf { it.rechargeFen },
                )
            }
            .sortedBy { it.key }

    /**
     * 一次性算出页面要的全部数字。[nowMs] 决定「今天」与窗口右端（测试注入固定时刻）。
     */
    fun summarize(
        readings: List<PowerReading>,
        recharges: List<PowerRechargePoint>,
        range: PowerUsageRange,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): PowerUsageSummary {
        val roomReadings = readingsOfLatestRoom(readings)
        val segments = segments(roomReadings, recharges)
        val days = days(segments, recharges, zone)
        val inWindow = bucketize(days, range).associateBy { it.key }
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val buckets = recentKeys(range, today).map { key ->
            inWindow[key] ?: PowerUsageBucket(key = key, usedKwh = 0.0, usedYuan = 0.0, rechargeFen = 0L)
        }
        val firstDate = roomReadings.firstOrNull()
            ?.let { Instant.ofEpochMilli(it.epochMs).atZone(zone).toLocalDate() }
        val lastDate = roomReadings.lastOrNull()
            ?.let { Instant.ofEpochMilli(it.epochMs).atZone(zone).toLocalDate() }
        return PowerUsageSummary(
            buckets = buckets,
            latest = roomReadings.lastOrNull(),
            readingCount = roomReadings.size,
            spanDays = if (firstDate == null || lastDate == null) {
                0
            } else {
                ChronoUnit.DAYS.between(firstDate, lastDate).toInt()
            },
            totalKwh = buckets.sumOf { it.usedKwh },
            totalYuan = if (buckets.any { it.usedYuan == null }) {
                null
            } else {
                buckets.sumOf { it.usedYuan ?: 0.0 }
            },
            totalRechargeFen = buckets.sumOf { it.rechargeFen },
            skippedSegments = (roomReadings.size - 1).coerceAtLeast(0) - segments.size,
        )
    }

    /** 度数文案（固定 Locale.US，免得系统语言把小数点换成逗号）。 */
    fun kwhText(kwh: Double): String = String.format(Locale.US, "%.2f", kwh)

    // ------------------------------------------------------------------

    private fun mondayOf(date: LocalDate): LocalDate =
        date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    private fun monthKeyOf(date: LocalDate): String =
        "%04d-%02d".format(date.year, date.monthValue)

    private fun monthLabelOf(key: String): String {
        val (year, month) = key.split("-").takeIf { it.size == 2 } ?: return key
        val monthValue = month.trimStart('0').toIntOrNull() ?: return key
        return "${year.toIntOrNull() ?: year} 年 $monthValue 月"
    }

    /** `2026-09-21` → `9/21`。 */
    private fun shortDateLabel(key: String): String {
        val parts = key.split("-")
        if (parts.size != 3) return key
        val month = parts[1].trimStart('0').ifEmpty { parts[1] }
        val day = parts[2].trimStart('0').ifEmpty { parts[2] }
        return "$month/$day"
    }

    /** 一段跨过的自然日与各自的重叠毫秒；段落在同一天时给整段重量。 */
    private fun splitByDay(startMs: Long, endMs: Long, zone: ZoneId): List<Pair<String, Long>> {
        val startDate = Instant.ofEpochMilli(startMs).atZone(zone).toLocalDate()
        val endDate = Instant.ofEpochMilli(endMs).atZone(zone).toLocalDate()
        val out = ArrayList<Pair<String, Long>>()
        var date = startDate
        while (!date.isAfter(endDate)) {
            val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val overlap = minOf(endMs, dayEnd) - maxOf(startMs, dayStart)
            if (overlap > 0) out += date.toString() to overlap
            date = date.plusDays(1)
        }
        if (out.isEmpty()) out += startDate.toString() to 1L
        return out
    }
}
