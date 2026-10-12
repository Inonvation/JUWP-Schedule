package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 今日页顶栏天气提示的契约（DESIGN §3.29）：
 * - **18:00 分界**：18:00 前讲今天，18:00 起讲明天；
 * - 话术按优先级命中：正在下雨 > 雪 / 雷暴 > 有雨 > 可能下雨 > 雾 > 防晒 > 防暑 >
 *   添衣 > 风大 > 中性描述；
 * - 取不到参考日（daily 为空）时给 null，页面不占位。
 */
class WeatherAdviceTest {

    private fun day(
        code: Int = 0,
        max: Double? = 28.0,
        min: Double? = 19.0,
        prob: Int? = 0,
        uv: Double? = 3.0,
        wind: Double? = 10.0,
    ) = WeatherDay(
        date = LocalDate.of(2026, 10, 12),
        code = code,
        tempMaxC = max,
        tempMinC = min,
        precipProbabilityMax = prob,
        uvIndexMax = uv,
        windMaxKmh = wind,
        sunrise = "06:14",
        sunset = "17:51",
    )

    private fun snapshot(
        currentCode: Int = 0,
        isDay: Boolean = true,
        daily: List<WeatherDay> = listOf(day(), day()),
        hourly: List<WeatherHour> = emptyList(),
    ) = WeatherSnapshot(
        current = WeatherNow(
            tempC = 22.0,
            apparentC = 23.0,
            humidity = 70,
            windKmh = 7.0,
            code = currentCode,
            isDay = isDay,
        ),
        hourly = hourly,
        daily = daily,
    )

    /**
     * 10-12 周一：课文按周一排（[course] 的 day 默认 1）。
     * 18:00 后讲 10-13 周二——「明天」那一路的课表也取得到（否则会静默退化成只有天气句）。
     */
    private fun at(hour: Int, minute: Int = 0): LocalDateTime =
        LocalDateTime.of(2026, 10, 12, hour, minute)

    /** 只取天气句：没有课表句 / 快下课句时，[WeatherAdvice.segments] 的第一条就是它。 */
    private fun weatherOf(snapshot: WeatherSnapshot, now: LocalDateTime): String? =
        WeatherAdvice.segments(snapshot, now).firstOrNull()

    // ---- 18:00 分界 ----

    @Test
    fun `18点前讲今天`() {
        assertEquals(0, WeatherAdvice.referenceDayIndex(at(17, 59)))
    }

    @Test
    fun `18点整起讲明天`() {
        assertEquals(1, WeatherAdvice.referenceDayIndex(at(18, 0)))
        assertEquals(1, WeatherAdvice.referenceDayIndex(at(23, 59)))
    }

    @Test
    fun `18点后那行是明天的天气`() {
        val snapshot = snapshot(
            daily = listOf(day(code = 0), day(code = 63, prob = 80)),
        )
        val line = weatherOf(snapshot, at(20))
        assertEquals("🌧️ 明天有雨，记得带伞", line)
    }

    // ---- 话术优先级 ----

    @Test
    fun `正在下雨优先于一切`() {
        val snapshot = snapshot(currentCode = 63, daily = listOf(day(code = 0, uv = 8.0), day()))
        assertEquals("外面正在下雨，出门记得带伞", WeatherAdvice.tip(snapshot, snapshot.daily[0], today = true))
    }

    @Test
    fun `讲明天时不看此刻在下什么`() {
        val snapshot = snapshot(currentCode = 63, daily = listOf(day(code = 63), day(code = 0)))
        assertEquals("明天晴，19~28°C", WeatherAdvice.tip(snapshot, snapshot.daily[1], today = false))
    }

    @Test
    fun `雷暴码不给带伞话术`() {
        val snapshot = snapshot(daily = listOf(day(code = 95), day()))
        assertEquals("今天有雷暴，尽量别在户外", WeatherAdvice.tip(snapshot, snapshot.daily[0], today = true))
    }

    @Test
    fun `雪优先于雨`() {
        val snapshot = snapshot(daily = listOf(day(code = 71), day()))
        assertEquals("今天有雪，注意保暖防滑", WeatherAdvice.tip(snapshot, snapshot.daily[0], today = true))
    }

    @Test
    fun `降水概率两档`() {
        val snapshot = snapshot()
        assertEquals("今天可能有雨，记得带伞", WeatherAdvice.tip(snapshot, day(prob = 60), today = true))
        assertEquals("今天可能有雨，带把伞稳妥", WeatherAdvice.tip(snapshot, day(prob = 30), today = true))
        // 29% 不够格，落到中性描述
        assertEquals("今天晴，19~28°C", WeatherAdvice.tip(snapshot, day(prob = 29), today = true))
    }

    @Test
    fun `紫外线强提醒防晒`() {
        val snapshot = snapshot()
        assertEquals("今天紫外线强，注意防晒", WeatherAdvice.tip(snapshot, day(uv = 6.0), today = true))
        // 5.9 不提
        assertEquals("今天晴，19~28°C", WeatherAdvice.tip(snapshot, day(uv = 5.9), today = true))
    }

    @Test
    fun `高温与低温`() {
        val snapshot = snapshot()
        assertEquals("今天很热，注意补水防暑", WeatherAdvice.tip(snapshot, day(max = 33.0), today = true))
        assertEquals("今天气温低，记得添衣", WeatherAdvice.tip(snapshot, day(min = 5.0), today = true))
    }

    @Test
    fun `雾天与大风`() {
        val snapshot = snapshot()
        assertEquals("今天有雾，出行注意安全", WeatherAdvice.tip(snapshot, day(code = 45), today = true))
        assertEquals("今天风大，出门注意", WeatherAdvice.tip(snapshot, day(wind = 30.0), today = true))
    }

    @Test
    fun `优先级顺序`() {
        val snapshot = snapshot()
        // 有雨压过防晒 / 高温
        assertEquals("今天有雨，记得带伞", WeatherAdvice.tip(snapshot, day(code = 61, uv = 9.0, max = 35.0), today = true))
        // 防晒压过高温
        assertEquals("今天紫外线强，注意防晒", WeatherAdvice.tip(snapshot, day(uv = 8.0, max = 35.0), today = true))
    }

    // ---- 兜底与容错 ----

    @Test
    fun `中性描述给天气与温度区间`() {
        val snapshot = snapshot()
        assertEquals("今天多云，19~29°C", WeatherAdvice.tip(snapshot, day(code = 2, max = 29.0), today = true))
    }

    @Test
    fun `缺温度时只报天气`() {
        val snapshot = snapshot()
        assertEquals("今天多云", WeatherAdvice.tip(snapshot, day(code = 2, max = null, min = null), today = true))
        assertNull(WeatherAdvice.tempRange(day(max = null)))
    }

    @Test
    fun `晴天夜间用月亮`() {
        val snapshot = snapshot(isDay = false, daily = listOf(day(code = 0), day()))
        val line = weatherOf(snapshot, at(9))
        assertTrue(line!!.startsWith("🌙"))
    }

    @Test
    fun `参考日取不到时不给提示`() {
        val snapshot = snapshot(daily = emptyList())
        assertNull(weatherOf(snapshot, at(9)))
    }

    @Test
    fun `只有今天一天时 18点后不给提示`() {
        val snapshot = snapshot(daily = listOf(day()))
        assertNull(weatherOf(snapshot, at(20)))
    }

    // ---- 课表句拼接（2026-10-12） ----

    /** 2026-10-12 周一（第 6 周）的课表上下文。 */
    private val semester = SemesterConfig(startDate = "2026-09-07", totalWeeks = 20)

    private val slots = listOf(
        TimeSlot(1, "08:30", "09:10"),
        TimeSlot(2, "09:15", "09:55"),
        TimeSlot(3, "10:15", "10:55"),
        TimeSlot(4, "11:00", "11:40"),
    )

    private fun course(
        id: Long,
        name: String,
        position: String,
        start: Int,
        end: Int,
        day: Int = 1,
    ) = Course(
        id = id,
        name = name,
        teacher = "",
        position = position,
        day = day,
        startSection = start,
        endSection = end,
        weeks = setOf(6),
    )

    // ---- 天气句 / 课表句 / 快下课句：各成一条、轮换显示（2026-10-12） ----

    @Test
    fun `天气句与课表句各成一条`() {
        // 白天 9:00 讲今天：晴 → 太阳图标；上午两节都在北楼 → 一条课表句
        val snapshot = snapshot(
            daily = listOf(day(code = 0, max = 28.0, min = 19.0, prob = 0), day()),
        )
        val segments = WeatherAdvice.segments(
            snapshot = snapshot,
            now = at(9),
            semester = semester,
            slots = slots,
            courses = listOf(
                course(1, "液压", "教学北大楼(北B102)", 1, 2),
                course(2, "PLC", "教学北大楼(北B402)", 3, 4),
            ),
        )
        assertEquals(2, segments.size)
        assertEquals("☀️ 今天晴，19~28°C", segments[0])
        assertTrue("实际=${segments[1]}", segments[1].startsWith("🧭 "))
        assertTrue("实际=${segments[1]}", segments[1].contains("上午"))
    }

    @Test
    fun `只有天气没有课表时给一条`() {
        val snapshot = snapshot(
            daily = listOf(day(code = 0, max = 28.0, min = 19.0, prob = 0), day()),
        )
        assertEquals(listOf("☀️ 今天晴，19~28°C"), WeatherAdvice.segments(snapshot, at(9)))
    }

    @Test
    fun `有实验课时只出天气句与实验课句`() {
        val snapshot = snapshot(
            daily = listOf(day(code = 0, max = 28.0, min = 19.0, prob = 0), day()),
        )
        val segments = WeatherAdvice.segments(
            snapshot = snapshot,
            now = at(9),
            semester = semester,
            slots = slots,
            courses = listOf(
                course(1, "液压", "教学北大楼(北B102)", 1, 2),
                course(2, "PLC", "教学北大楼(北B402)", 3, 4),
                course(3, "实验", "工程训练中心208", 1, 2).copy(kind = CourseKind.Lab),
            ),
        )
        assertEquals(2, segments.size)
        assertEquals("☀️ 今天晴，19~28°C", segments[0])
        assertTrue("实际=${segments[1]}", segments[1].startsWith("🔬 "))
        assertTrue("实际=${segments[1]}", segments[1].contains("实验课"))
        assertTrue("不该有南北楼句：${segments[1]}", !segments[1].contains("往返跑"))
    }

    @Test
    fun `快下课有雨时只回那一条`() {
        // 9:40 距第 1-2 节（9:55）下课 15 分钟，逐小时 9 点有雨 → 独占整行
        val snapshot = snapshot(
            hourly = listOf(
                WeatherHour(LocalDateTime.parse("2026-10-12T09:00"), 20.0, 80, 61),
            ),
        )
        val segments = WeatherAdvice.segments(
            snapshot = snapshot,
            now = at(9, 40),
            semester = semester,
            slots = slots,
            courses = listOf(course(1, "液压", "教学北大楼(北B102)", 1, 2)),
        )
        assertEquals(1, segments.size)
        assertTrue("实际=${segments[0]}", segments[0].startsWith("🌧️ "))
        assertTrue("实际=${segments[0]}", segments[0].contains("下课"))
    }

    @Test
    fun `课表数据不传时只有天气句`() {
        val snapshot = snapshot(
            daily = listOf(day(code = 0, max = 28.0, min = 19.0, prob = 0), day()),
        )
        assertEquals("☀️ 今天晴，19~28°C", weatherOf(snapshot, at(9)))
    }

    // ---- 逐小时「会不会下」 ----

    @Test
    fun `逐小时降水判据`() {
        fun h(code: Int, prob: Int? = 0) = WeatherHour(
            time = LocalDateTime.of(2026, 10, 13, 9, 0),
            tempC = 20.0,
            precipProbability = prob,
            code = code,
        )
        assertTrue(WeatherCodes.willPrecipitate(h(61)))
        assertTrue(WeatherCodes.willPrecipitate(h(71)))
        assertTrue(WeatherCodes.willPrecipitate(h(95)))
        // 概率两档：≥30 算「可能下」，29 不算
        assertTrue(WeatherCodes.willPrecipitate(h(0, prob = 30)))
        assertTrue(WeatherCodes.willPrecipitate(h(0, prob = 29)).not())
        assertTrue(WeatherCodes.willPrecipitate(h(3, prob = null)).not())
    }
}
