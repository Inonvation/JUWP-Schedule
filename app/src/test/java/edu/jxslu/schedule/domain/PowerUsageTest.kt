package edu.jxslu.schedule.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用电量推算（DESIGN §3.13「用电统计」）。
 *
 * 这一层是「用电统计」的全部口径：差分公式、充值折算、跨天均摊、窗口与标签。
 * UI 只负责画，所以这里的断言就是那几个数字的正确性保证。
 */
class PowerUsageTest {

    /** 固定时区：键与均摊都跟本地日历走，跟着机器时区飘会让断言不稳定。 */
    private val zone = ZoneId.of("Asia/Shanghai")

    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    private fun at(text: String): Long =
        LocalDateTime.parse(text, fmt).atZone(zone).toInstant().toEpochMilli()

    private fun reading(
        text: String,
        kwh: Double,
        price: Double = 0.62,
        room: String = "9A101",
    ) = PowerReading(epochMs = at(text), remainKwh = kwh, priceYuan = price, roomId = room)

    private fun recharge(text: String, amountFen: Long) =
        PowerRechargePoint(epochMs = at(text), amountFen = amountFen)

    // ------------------------------------------------------------------
    // 差分与充值折算
    // ------------------------------------------------------------------

    @Test
    fun `相邻读数差就是用量，单价已知时一并折算金额`() {
        val readings = listOf(reading("2026-09-24 09:00", 50.0), reading("2026-09-25 09:00", 45.0))
        val segments = PowerUsage.segments(readings, emptyList())
        assertEquals(1, segments.size)
        assertEquals(5.0, segments[0].usedKwh, 1e-9)
        assertEquals(3.1, segments[0].usedYuan!!, 1e-9)
        assertEquals(0L, segments[0].rechargeFen)
    }

    @Test
    fun `段内充值按单价折成度数从用量里扣掉`() {
        // 两天里电表从 20 度回到 20 度，中间充了 62 元 = 100 度 → 用了 100 度
        val readings = listOf(reading("2026-09-24 09:00", 20.0), reading("2026-09-25 09:00", 20.0))
        val segments = PowerUsage.segments(readings, listOf(recharge("2026-09-24 12:00", 6200)))
        assertEquals(1, segments.size)
        assertEquals(100.0, segments[0].usedKwh, 1e-9)
        assertEquals(6200L, segments[0].rechargeFen)
    }

    @Test
    fun `退款是负的充值，会把度数从读数差里扣回来`() {
        // 80 度 →（退掉 31 元 = 50 度）→ 20 度：期间实际用了 10 度
        val readings = listOf(reading("2026-09-24 09:00", 80.0), reading("2026-09-25 09:00", 20.0))
        val segments = PowerUsage.segments(readings, listOf(recharge("2026-09-24 12:00", -3100)))
        assertEquals(10.0, segments[0].usedKwh, 1e-9)
    }

    @Test
    fun `单价未知时度数照常差分，只是不给金额`() {
        val readings = listOf(
            reading("2026-09-24 09:00", 20.0, price = 0.0),
            reading("2026-09-25 09:00", 18.0, price = 0.0),
        )
        val segments = PowerUsage.segments(readings, emptyList())
        assertEquals(2.0, segments[0].usedKwh, 1e-9)
        assertNull(segments[0].usedYuan)
    }

    @Test
    fun `单价未知又有充值的段整段跳过`() {
        val readings = listOf(
            reading("2026-09-24 09:00", 20.0, price = 0.0),
            reading("2026-09-25 09:00", 20.0, price = 0.0),
        )
        val segments = PowerUsage.segments(readings, listOf(recharge("2026-09-24 12:00", 2000)))
        assertTrue("换不出度数就不给结论", segments.isEmpty())
    }

    @Test
    fun `电量涨得比充值能解释的还多时跳过这一段`() {
        // 没取到流水 / 平台登记延迟：20 度 → 25 度，凭空多出来 5 度
        val readings = listOf(reading("2026-09-24 09:00", 20.0), reading("2026-09-25 09:00", 25.0))
        assertTrue(PowerUsage.segments(readings, emptyList()).isEmpty())

        // 只有一条读数时没有任何段
        assertTrue(PowerUsage.segments(listOf(reading("2026-09-24 09:00", 20.0)), emptyList()).isEmpty())
    }

    // ------------------------------------------------------------------
    // 跨天均摊与桶聚合
    // ------------------------------------------------------------------

    @Test
    fun `跨天的段按时长占比摊到各天`() {
        // 48 小时用掉 4 度：首尾各占半天（1 度），中间整天（2 度）
        val readings = listOf(reading("2026-09-23 12:00", 10.0), reading("2026-09-25 12:00", 6.0))
        val days = PowerUsage.days(PowerUsage.segments(readings, emptyList()), emptyList(), zone)
        assertEquals(listOf("2026-09-23", "2026-09-24", "2026-09-25"), days.map { it.key })
        assertEquals(1.0, days[0].usedKwh, 1e-6)
        assertEquals(2.0, days[1].usedKwh, 1e-6)
        assertEquals(1.0, days[2].usedKwh, 1e-6)
        assertEquals(4.0, days.sumOf { it.usedKwh }, 1e-6)
    }

    @Test
    fun `充值归到事件当天，不参与均摊`() {
        val readings = listOf(reading("2026-09-23 12:00", 10.0), reading("2026-09-25 12:00", 6.0))
        val days = PowerUsage.days(
            PowerUsage.segments(readings, listOf(recharge("2026-09-24 08:00", 3100))),
            listOf(recharge("2026-09-24 08:00", 3100)),
            zone,
        )
        // 充值把度数加回去：4 + 50 = 54 度，仍按原有天数占比摊
        assertEquals(54.0, days.sumOf { it.usedKwh }, 1e-6)
        assertEquals(3100L, days.first { it.key == "2026-09-24" }.rechargeFen)
        assertEquals(0L, days.first { it.key == "2026-09-23" }.rechargeFen)
    }

    @Test
    fun `周桶从周一起算，月桶按自然月`() {
        // 2026-09-24 是周四，所属周的周一是 09-21
        assertEquals("2026-09-21", PowerUsage.bucketKeyOf(PowerUsageRange.Week, "2026-09-24"))
        // 2026-09-20 是周日，属于 09-14 那周
        assertEquals("2026-09-14", PowerUsage.bucketKeyOf(PowerUsageRange.Week, "2026-09-20"))
        assertEquals("2026-09-24", PowerUsage.bucketKeyOf(PowerUsageRange.Day, "2026-09-24"))
        assertEquals("2026-09", PowerUsage.bucketKeyOf(PowerUsageRange.Month, "2026-09-24"))
    }

    @Test
    fun `窗口是近 14 天 12 周 12 月，升序且含当前桶`() {
        val today = LocalDate.of(2026, 9, 24)
        val days = PowerUsage.recentKeys(PowerUsageRange.Day, today)
        assertEquals(14, days.size)
        assertEquals("2026-09-11", days.first())
        assertEquals("2026-09-24", days.last())

        val weeks = PowerUsage.recentKeys(PowerUsageRange.Week, today)
        assertEquals(12, weeks.size)
        assertEquals("2026-09-21", weeks.last())
        assertEquals("2026-07-06", weeks.first())

        val months = PowerUsage.recentKeys(PowerUsageRange.Month, today)
        assertEquals(12, months.size)
        assertEquals("2026-09", months.last())
        assertEquals("2025-10", months.first())
    }

    // ------------------------------------------------------------------
    // 汇总
    // ------------------------------------------------------------------

    @Test
    fun `汇总把段落到窗口内的桶里，空桶补零`() {
        // 取整天边界：段的用量整块落在 09-24 那一天，数字干净
        val readings = listOf(reading("2026-09-24 00:00", 50.0), reading("2026-09-25 00:00", 45.0))
        val summary = PowerUsage.summarize(
            readings = readings,
            recharges = emptyList(),
            range = PowerUsageRange.Day,
            nowMs = at("2026-09-25 12:00"),
            zone = zone,
        )
        assertEquals(14, summary.buckets.size)
        assertEquals(5.0, summary.totalKwh, 1e-6)
        assertEquals(3.1, summary.totalYuan!!, 1e-6)
        assertEquals(2, summary.readingCount)
        assertEquals(1, summary.spanDays)
        assertEquals(0, summary.skippedSegments)
        assertEquals(0L, summary.totalRechargeFen)
        assertEquals(45.0, summary.latest!!.remainKwh, 1e-9)
        assertEquals(5.0, summary.buckets.first { it.key == "2026-09-24" }.usedKwh, 1e-6)
        assertEquals(0.0, summary.buckets.first { it.key == "2026-09-25" }.usedKwh, 1e-9)
        // 窗口是 09-12 ~ 09-25（近 14 天，含今天），最早那个桶是 0
        assertEquals("2026-09-12", summary.buckets.first().key)
        assertEquals(0.0, summary.buckets.first().usedKwh, 1e-9)
    }

    @Test
    fun `窗口之外的历史不进合计`() {
        val readings = listOf(reading("2026-08-01 09:00", 50.0), reading("2026-08-02 09:00", 40.0))
        val summary = PowerUsage.summarize(
            readings = readings,
            recharges = emptyList(),
            range = PowerUsageRange.Day,
            nowMs = at("2026-09-25 12:00"),
            zone = zone,
        )
        assertEquals(0.0, summary.totalKwh, 1e-9)
        // 读数还在，只是全在窗口左侧
        assertEquals(2, summary.readingCount)
    }

    @Test
    fun `周月窗口按桶合计，且跳过段会被计数`() {
        // 都在 09-21 那一周里（09-22 周二 ~ 09-25 周五），中间夹一段算不出来的
        val readings = listOf(
            reading("2026-09-22 00:00", 30.0),
            reading("2026-09-23 00:00", 28.0),
            reading("2026-09-24 00:00", 40.0),
            reading("2026-09-25 00:00", 37.0),
        )
        val summary = PowerUsage.summarize(
            readings = readings,
            recharges = emptyList(),
            range = PowerUsageRange.Week,
            nowMs = at("2026-09-24 12:00"),
            zone = zone,
        )
        assertEquals(1, summary.skippedSegments)
        assertEquals(12, summary.buckets.size)
        val currentWeek = summary.buckets.last()
        assertEquals("2026-09-21", currentWeek.key)
        assertEquals(5.0, currentWeek.usedKwh, 1e-6)
        // 上一周没有用量
        assertEquals(0.0, summary.buckets.first { it.key == "2026-09-14" }.usedKwh, 1e-9)
        // 同一个月视图下也是 5 度
        val byMonth = PowerUsage.summarize(
            readings = readings,
            recharges = emptyList(),
            range = PowerUsageRange.Month,
            nowMs = at("2026-09-24 12:00"),
            zone = zone,
        )
        assertEquals(5.0, byMonth.totalKwh, 1e-6)
    }

    @Test
    fun `单价未知的桶不报金额，合计金额为 null`() {
        val readings = listOf(
            reading("2026-09-24 09:00", 50.0, price = 0.0),
            reading("2026-09-25 09:00", 45.0, price = 0.0),
        )
        val summary = PowerUsage.summarize(
            readings = readings,
            recharges = emptyList(),
            range = PowerUsageRange.Day,
            nowMs = at("2026-09-25 12:00"),
            zone = zone,
        )
        assertEquals(5.0, summary.totalKwh, 1e-6)
        assertNull(summary.totalYuan)
        assertNull(summary.buckets.first { it.key == "2026-09-25" }.usedYuan)
    }

    @Test
    fun `统计只看最新读数那个房间，换寝室从新房间重新开始`() {
        val readings = listOf(
            reading("2026-09-01 09:00", 30.0, room = "9A101"),
            reading("2026-09-02 09:00", 20.0, room = "9A101"),
            reading("2026-09-20 09:00", 99.0, room = "1A101"),
        )
        val kept = PowerUsage.readingsOfLatestRoom(readings)
        assertEquals(listOf("1A101"), kept.map { it.roomId }.distinct())
        assertEquals(1, kept.size)
        // 新房间只有一条读数，算不出用量，也不会把旧房间那条算进来
        val summary = PowerUsage.summarize(
            readings = readings,
            recharges = emptyList(),
            range = PowerUsageRange.Day,
            nowMs = at("2026-09-20 12:00"),
            zone = zone,
        )
        assertEquals(1, summary.readingCount)
        assertEquals(0.0, summary.totalKwh, 1e-9)
    }

    // ------------------------------------------------------------------
    // 标签与文案
    // ------------------------------------------------------------------

    @Test
    fun `桶标签`() {
        val today = LocalDate.of(2026, 9, 24)
        assertEquals("今天", PowerUsage.labelOf(PowerUsageRange.Day, "2026-09-24", today))
        assertEquals("昨天", PowerUsage.labelOf(PowerUsageRange.Day, "2026-09-23", today))
        assertEquals("09-01", PowerUsage.labelOf(PowerUsageRange.Day, "2026-09-01", today))
        assertEquals("本周", PowerUsage.labelOf(PowerUsageRange.Week, "2026-09-21", today))
        assertEquals("9/14 起", PowerUsage.labelOf(PowerUsageRange.Week, "2026-09-14", today))
        assertEquals("本月", PowerUsage.labelOf(PowerUsageRange.Month, "2026-09", today))
        assertEquals("2026 年 8 月", PowerUsage.labelOf(PowerUsageRange.Month, "2026-08", today))
    }

    @Test
    fun `轴标签只留辨识所需的部分`() {
        assertEquals("24", PowerUsage.axisLabel(PowerUsageRange.Day, "2026-09-24"))
        assertEquals("9/21", PowerUsage.axisLabel(PowerUsageRange.Week, "2026-09-21"))
        assertEquals("8", PowerUsage.axisLabel(PowerUsageRange.Month, "2026-08"))
        // 认不出的键原样回显，不抛异常
        assertEquals("乱码", PowerUsage.labelOf(PowerUsageRange.Day, "乱码", LocalDate.of(2026, 9, 24)))
    }

    @Test
    fun `度数文案固定两位小数且不受系统语言影响`() {
        assertEquals("0.00", PowerUsage.kwhText(0.0))
        assertEquals("12.35", PowerUsage.kwhText(12.3456))
    }
}
