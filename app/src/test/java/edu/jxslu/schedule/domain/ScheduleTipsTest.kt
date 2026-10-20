package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 顶栏里「课表句」与「快下课句」的契约（DESIGN §3.29，2026-10-12；2026-10-20 收口）：
 *
 * [ScheduleTips.dayTipFor]：
 * - **一条只讲一件事**：实验课 > 往返跑 > 楼变化 > 同楼；
 * - 往返跑：该半天 **≥2 节课**且楼全解析得出；两个半天都往返才合并，只有一个半天往返只报那一个；
 * - 有实验课（kind == Lab）：**只出实验课句**，南北楼句不再出；
 * - 措辞按「参考日是不是今天」定（今天 / 明天），today 由调用方传入。
 *
 * [ScheduleTips.dismissalTipFor]：
 * - 距下课 ≤15 分钟、下课起 1 小时内有雨 → **整行只显示它**；
 * - 永远看今天（18:00 后正文讲明天也照提示）。
 *
 * 兜底：任何一环取不到 → 整句丢弃（宁可没有提示，不要错误提示）。
 */
class ScheduleTipsTest {

    /** 学期：2026-09-07（周一）开学，20 周。 */
    private val semester = SemesterConfig(startDate = "2026-09-07", totalWeeks = 20)

    private val slots = listOf(
        TimeSlot(1, "08:30", "09:10"),
        TimeSlot(2, "09:15", "09:55"),
        TimeSlot(3, "10:15", "10:55"),
        TimeSlot(4, "11:00", "11:40"),
        TimeSlot(5, "14:00", "14:40"),
        TimeSlot(6, "14:45", "15:25"),
        TimeSlot(7, "15:45", "16:25"),
        TimeSlot(8, "16:30", "17:10"),
        TimeSlot(9, "19:00", "19:40"),
    )

    /** 2026-10-13 周二（第 6 周）。 */
    private val tuesday = LocalDate.of(2026, 10, 13)

    /** 2026-10-14 周三（第 6 周）。 */
    private val wednesday = LocalDate.of(2026, 10, 14)

    private fun courseOf(
        id: Long,
        day: Int,
        start: Int,
        end: Int,
        position: String,
        weeks: Set<Int> = setOf(6),
        kind: CourseKind = CourseKind.Theory,
        isCustomTime: Boolean = false,
        customStart: String? = null,
        customEnd: String? = null,
    ) = Course(
        id = id,
        name = "课$id",
        teacher = "",
        position = position,
        day = day,
        startSection = start,
        endSection = end,
        weeks = weeks,
        isCustomTime = isCustomTime,
        customStartTime = customStart,
        customEndTime = customEnd,
        kind = kind,
    )

    /** 周二第 1-4 节；楼由 [positions] 决定。 */
    private fun tuesdayMorning(vararg positions: String): List<Course> =
        positions.mapIndexed { i, position ->
            courseOf(
                id = (i + 1).toLong(),
                day = 2,
                start = if (i == 0) 1 else 3,
                end = if (i == 0) 2 else 4,
                position = position,
            )
        }

    private fun dayTip(
        date: LocalDate,
        courses: List<Course>,
        overrides: List<ScheduleOverride> = emptyList(),
        /** 真正的今天；默认与参考日相同（措辞说「今天」）。 */
        today: LocalDate = date,
    ) = ScheduleTips.dayTipFor(
        date = date,
        today = today,
        semester = semester,
        slots = slots,
        courses = courses,
        overrides = overrides,
    )

    private fun hour(time: String, code: Int = 0, prob: Int? = 0) = WeatherHour(
        time = LocalDateTime.parse(time),
        tempC = 20.0,
        precipProbability = prob,
        code = code,
    )

    /** 2026-10-13 的某个时刻。 */
    private fun at(hour: Int, minute: Int) = LocalDateTime.of(2026, 10, 13, hour, minute)

    private fun dismissal(
        courses: List<Course>,
        now: LocalDateTime,
        hourly: List<WeatherHour>,
    ) = ScheduleTips.dismissalTipFor(
        semester = semester,
        slots = slots,
        courses = courses,
        overrides = emptyList(),
        now = now,
        hourly = hourly,
    )

    // ---- 南北楼 ----

    @Test
    fun `上午两节课跨南北楼给往返跑`() {
        val result = dayTip(
            tuesday,
            tuesdayMorning("教学南大楼(南B206)", "教学北大楼(北B306)"),
        )
        assertNotNull(result)
        assertEquals(TodayTip.Kind.Schedule, result!!.kind)
        assertTrue("实际=${result.text}", result.text.startsWith("🧭 "))
        assertTrue(
            "实际=${result.text}",
            result.text.endsWith("上午得南北楼来回跑") ||
                result.text.endsWith("上午要南北楼往返跑"),
        )
    }

    @Test
    fun `上午两节课全在南楼给都在南楼`() {
        val result = dayTip(
            tuesday,
            tuesdayMorning("教学南大楼(南B206)", "教学南大楼(南C303)"),
        )
        assertNotNull(result)
        assertTrue(
            "实际=${result!!.text}",
            result.text.endsWith("上午的课都在南楼") ||
                result.text.endsWith("上午都在南楼"),
        )
    }

    @Test
    fun `上下午都跨楼合并成一句`() {
        val result = dayTip(
            tuesday,
            tuesdayMorning("教学南大楼(南B206)", "教学北大楼(北B306)") +
                listOf(
                    courseOf(3, 2, 5, 6, "教学北大楼(北B402)"),
                    courseOf(4, 2, 7, 8, "教学南大楼(南B102)"),
                ),
        )
        assertNotNull(result)
        val text = result!!.text
        // 上下午都要往返跑 → 合并一句，别各说一遍
        assertTrue(
            "上下午都往返跑应合并：$text",
            text.endsWith("上下午都得南北楼来回跑") ||
                text.endsWith("上下午都要南北楼往返跑"),
        )
    }

    @Test
    fun `上下午都在南楼合并成一句`() {
        val result = dayTip(
            tuesday,
            tuesdayMorning("教学南大楼(南B206)", "教学南大楼(南C303)") +
                listOf(
                    courseOf(3, 2, 5, 6, "教学南大楼(南B402)"),
                    courseOf(4, 2, 7, 8, "教学南大楼(南B102)"),
                ),
        )
        assertNotNull(result)
        assertTrue("实际=${result!!.text}", result.text.startsWith("🧭 "))
        assertTrue("实际=${result.text}", result.text.contains("一天的课都在南楼"))
        // 不该再拆成「上午…，下午…」两截
        assertTrue("实际=${result.text}", !result.text.contains("上午"))
        assertTrue("实际=${result.text}", !result.text.contains("下午"))
    }

    @Test
    fun `上下午都在北楼合并成一句`() {
        val result = dayTip(
            tuesday,
            tuesdayMorning("教学北大楼(北B206)", "教学北大楼(北C303)") +
                listOf(
                    courseOf(3, 2, 5, 6, "教学北大楼(北B402)"),
                    courseOf(4, 2, 7, 8, "教学北大楼(北B102)"),
                ),
        )
        assertNotNull(result)
        assertTrue("实际=${result!!.text}", result.text.contains("一天的课都在北楼"))
    }

    @Test
    fun `上午两节南下午一节南也说一天都在南楼`() {
        // 上下午都有课、且全在同一栋楼 → 整句（不因下午只有一节就退回半天口径）
        val result = dayTip(
            tuesday,
            tuesdayMorning("教学南大楼(南B206)", "教学南大楼(南C303)") +
                courseOf(3, 2, 7, 8, "教学南大楼(南B102)"),
        )
        assertNotNull(result)
        assertTrue("实际=${result!!.text}", result.text.contains("一天的课都在南楼"))
    }

    @Test
    fun `上下午不同栋楼并列一句且不重复日期`() {
        // 上下午不同栋楼 → 两句并列成一句：日期前缀只挂第一句，「都在 X 楼」用朴素说法
        val result = dayTip(
            tuesday,
            tuesdayMorning("教学南大楼(南B206)", "教学南大楼(南C303)") +
                listOf(
                    courseOf(3, 2, 5, 6, "教学北大楼(北B402)"),
                    courseOf(4, 2, 7, 8, "教学北大楼(北B102)"),
                ),
        )
        assertNotNull(result)
        val text = result!!.text
        // 一句话说清一次换楼：上午南楼、下午北楼
        assertTrue("实际=$text", text.contains("上午") && text.contains("南楼"))
        assertTrue("实际=$text", text.contains("下午") && text.contains("北楼"))
        // 第二个半天不再重复日期前缀，也不叠「哦」
        assertTrue("实际=$text", !text.contains("明天下午") && !text.contains("今天下午"))
        assertTrue("实际=$text", !text.contains("哦"))
        // 2026-10-20：一条只讲一件事，整句必须能放进顶栏一行
        assertTrue("实际=$text", text.length <= 16)
    }

    @Test
    fun `单节课不判南北楼`() {
        // 用户 2026-10-12：上午只有一节课就不用提示了
        assertNull(dayTip(tuesday, tuesdayMorning("教学南大楼(南B206)")))
        assertNull(
            dayTip(
                tuesday,
                listOf(courseOf(1, 2, 5, 6, "教学南大楼(南B206)")),
            ),
        )
    }

    @Test
    fun `上下午各一节且不同楼不提示`() {
        // 单节课不提示（2026-10-12）的延伸：两个半天各只有一节、只是换了栋楼，也不占顶栏
        assertNull(
            dayTip(
                tuesday,
                listOf(
                    courseOf(1, 2, 1, 2, "教学南大楼(南B206)"),
                    courseOf(2, 2, 5, 6, "教学北大楼(北B306)"),
                ),
            ),
        )
    }

    @Test
    fun `只报往返跑的半天 不拼下午同楼`() {
        // 2026-10-20 截图场景：早先会拼成「上午得在南楼北楼之间来回跑咯，下午都在南楼」
        // （24 字，顶栏放不下）；现在只报最值得提前知道的那一件。
        val result = dayTip(
            tuesday,
            tuesdayMorning("教学南大楼(南B206)", "教学北大楼(北B306)") +
                courseOf(3, 2, 7, 8, "教学南大楼(南B102)"),
        )
        assertNotNull(result)
        assertTrue("实际=${result!!.text}", result.text.contains("上午"))
        assertTrue("下午只有一节课：${result.text}", !result.text.contains("下午"))
        assertTrue("实际=${result.text}（${result.text.length} 字）", result.text.length <= 16)
    }

    @Test
    fun `只有下午有课时报下午`() {
        val result = dayTip(
            tuesday,
            listOf(
                courseOf(1, 2, 5, 6, "教学南大楼(南B206)"),
                courseOf(2, 2, 7, 8, "教学南大楼(南C303)"),
            ),
        )
        assertNotNull(result)
        assertTrue("实际=${result!!.text}", result.text.contains("下午"))
        assertTrue(
            "实际=${result.text}",
            result.text.endsWith("下午的课都在南楼") || result.text.endsWith("下午都在南楼"),
        )
    }

    @Test
    fun `位置缺一节课整句不出现`() {
        // 兜底：一门课的位置空着（`()` 或用户手动加的课）→ 整句丢弃，不猜
        assertNull(
            dayTip(tuesday, tuesdayMorning("教学南大楼(南B206)", "()")),
        )
        assertNull(
            dayTip(tuesday, tuesdayMorning("教学南大楼(南B206)", "工程训练中心208")),
        )
    }

    @Test
    fun `自定义时间的课按有效开始时刻分半天`() {
        // 上午两节真实课（南+北），下午一门自定义时间的课（实际 14:00 开课）——
        // 节次字段写着第 1-2 节，但有效时刻在下午：上午照常判出往返跑，
        // 下午只有一门课不判（若只看节次字段，这门课会被误当成「上午的第三节课」）
        val result = dayTip(
            tuesday,
            tuesdayMorning("教学南大楼(南B206)", "教学北大楼(北B306)") +
                courseOf(
                    9,
                    day = 2,
                    start = 1,
                    end = 2,
                    position = "教学北大楼(北B306)",
                    isCustomTime = true,
                    customStart = "14:00",
                    customEnd = "15:00",
                ),
        )
        assertNotNull(result)
        assertTrue(
            "实际=${result!!.text}",
            result.text.endsWith("上午得南北楼来回跑") ||
                result.text.endsWith("上午要南北楼往返跑"),
        )
        assertTrue("下午段不该出：${result.text}", !result.text.contains("下午"))
    }

    @Test
    fun `不在学期内不提示`() {
        assertNull(
            dayTip(
                LocalDate.of(2026, 8, 10), // 开学前
                tuesdayMorning("教学南大楼(南B206)", "教学北大楼(北B306)"),
            ),
        )
    }

    @Test
    fun `停课的日子不提示`() {
        // 调休覆盖：该日停课 → 没有课 → 无提示
        val overrides = listOf(
            ScheduleOverride(
                id = 1,
                fromDate = "2026-10-13",
                toDate = "2026-10-13",
                kind = OverrideKind.SUSPENDED,
            ),
        )
        assertNull(
            dayTip(
                tuesday,
                tuesdayMorning("教学南大楼(南B206)", "教学北大楼(北B306)"),
                overrides,
            ),
        )
    }

    // ---- 实验课 ----

    @Test
    fun `有实验课只出实验课句`() {
        val result = dayTip(
            tuesday,
            tuesdayMorning("教学南大楼(南B206)", "教学北大楼(北B306)") +
                courseOf(3, 2, 5, 6, "工程训练中心208", kind = CourseKind.Lab),
        )
        assertNotNull(result)
        assertTrue("实际=${result!!.text}", result.text.startsWith("🔬 "))
        // 有实验课 → 南北楼句不再出（用户 2026-10-12 拍板）
        assertTrue("实际=${result.text}", result.text.endsWith("下午有实验课"))
        assertTrue("不该含南北楼：${result.text}", !result.text.contains("往返跑"))
    }

    @Test
    fun `实验课看类型不看地点`() {
        // 位置写着南北楼、但类型是实验课 → 仍按实验课出句
        val result = dayTip(
            tuesday,
            listOf(
                courseOf(1, 2, 1, 2, "教学南大楼(南B206)", kind = CourseKind.Lab),
            ),
        )
        assertNotNull(result)
        assertTrue("实际=${result!!.text}", result.text.endsWith("上午有实验课"))
    }

    @Test
    fun `上午下午都有实验课合并一句`() {
        val result = dayTip(
            tuesday,
            listOf(
                courseOf(1, 2, 1, 2, "工程训练中心208", kind = CourseKind.Lab),
                courseOf(2, 2, 5, 6, "工程训练中心201", kind = CourseKind.Lab),
            ),
        )
        assertNotNull(result)
        assertTrue("实际=${result!!.text}", result.text.endsWith("上午、下午都有实验课"))
    }

    // ---- 措辞：参考日 = 今天 → 「今天」；参考日 = 明天 → 「明天」 ----

    @Test
    fun `参考日措辞跟着日期关系走`() {
        // 用「今天 / 明天」的星期与所在周排课（此前沿用 courseOf 的默认第 6 周，
        // 非第 6 周运行必失败——2026-10-19 修正）。学期外 `dayTipFor` 本来就返回 null，
        // 这条用例断言不了任何东西，直接跳过而不是误报失败。
        val today = LocalDate.now()
        val tomorrow = today.plusDays(1)
        val todayWeek = ScheduleCalculator.weekNumberOf(semester, today)
        val tomorrowWeek = ScheduleCalculator.weekNumberOf(semester, tomorrow)
        assumeTrue("今天不在学期内，跳过措辞用例", todayWeek in 1..semester.totalWeeks)
        assumeTrue("明天不在学期内，跳过措辞用例", tomorrowWeek in 1..semester.totalWeeks)
        val courses = listOf(
            courseOf(1, today.dayOfWeek.value, 1, 2, "教学南大楼(南B206)", weeks = setOf(todayWeek)),
            courseOf(2, today.dayOfWeek.value, 3, 4, "教学南大楼(南C303)", weeks = setOf(todayWeek)),
        )
        val todayText = dayTip(today, courses, today = today)?.text.orEmpty()
        assertTrue("实际=$todayText", todayText.startsWith("🧭 今天"))
        // 参考日 = 明天（同一学期周内，明天星期几就排星期几的课），措辞换成「明天」
        val tomorrowCourses = listOf(
            courseOf(1, tomorrow.dayOfWeek.value, 1, 2, "教学南大楼(南B206)", weeks = setOf(tomorrowWeek)),
            courseOf(2, tomorrow.dayOfWeek.value, 3, 4, "教学南大楼(南C303)", weeks = setOf(tomorrowWeek)),
        )
        val tomorrowText = dayTip(tomorrow, tomorrowCourses, today = today)?.text.orEmpty()
        assertTrue("实际=$tomorrowText", tomorrowText.startsWith("🧭 明天"))
    }

    // ---- 快下课有雨 ----

    @Test
    fun `快下课有雨给整行替换`() {
        val result = dismissal(
            tuesdayMorning("教学南大楼(南B206)"),
            now = at(9, 40),
            hourly = listOf(
                hour("2026-10-13T09:00", code = 61, prob = 80),
                hour("2026-10-13T10:00", code = 63, prob = 90),
            ),
        )
        assertNotNull(result)
        assertEquals(TodayTip.Kind.Schedule, result!!.kind)
        assertTrue("实际=${result.text}", result.text.startsWith("🌧️ "))
        assertTrue(
            "实际=${result.text}",
            result.text.endsWith("记得带伞哦") ||
                result.text.endsWith("伞别忘了") ||
                result.text.endsWith("拿把伞哦"),
        )
    }

    @Test
    fun `距下课超过十五分钟不提示`() {
        assertNull(
            dismissal(
                tuesdayMorning("教学南大楼(南B206)"),
                now = at(9, 39), // 9:55 下课，还差 16 分钟
                hourly = listOf(hour("2026-10-13T09:00", code = 61, prob = 80)),
            ),
        )
    }

    @Test
    fun `快下课窗口边界刚好十五分钟算`() {
        val result = dismissal(
            tuesdayMorning("教学南大楼(南B206)"),
            now = at(9, 40), // 9:55 下课，正好 15 分钟
            hourly = listOf(hour("2026-10-13T09:00", code = 61, prob = 80)),
        )
        assertNotNull(result)
        assertEquals(TodayTip.Kind.Schedule, result!!.kind)
    }

    @Test
    fun `下课附近没雨不提示`() {
        assertNull(
            dismissal(
                tuesdayMorning("教学南大楼(南B206)"),
                now = at(9, 45),
                hourly = listOf(
                    hour("2026-10-13T09:00", code = 0, prob = 10),
                    hour("2026-10-13T10:00", code = 1, prob = 5),
                    // 雨在更晚（12:00），不属于下课窗口
                    hour("2026-10-13T12:00", code = 61, prob = 90),
                ),
            ),
        )
    }

    @Test
    fun `逐小时数据缺失不提示`() {
        assertNull(
            dismissal(
                tuesdayMorning("教学南大楼(南B206)"),
                now = at(9, 45),
                hourly = emptyList(),
            ),
        )
    }

    @Test
    fun `跨天的逐小时数据不拿来判今天`() {
        // 只有 10-14 那条有雨，10-13 下课窗口内没有 → 不提示
        assertNull(
            dismissal(
                tuesdayMorning("教学南大楼(南B206)"),
                now = at(9, 45),
                hourly = listOf(hour("2026-10-14T09:00", code = 61, prob = 80)),
            ),
        )
    }

    @Test
    fun `下课时间已过不提示`() {
        // 第 1-2 节 9:55 下课；10:30 时早已下课，不该再提
        assertNull(
            dismissal(
                tuesdayMorning("教学南大楼(南B206)"),
                now = at(10, 30),
                hourly = listOf(hour("2026-10-13T10:00", code = 61, prob = 80)),
            ),
        )
    }

    @Test
    fun `18点后照样提示快下课`() {
        // 第 9 节 19:00-19:40：18:00 后顶栏正文讲明天，但今天这门课要下课了，
        // 快下课链路永远看今天（2026-10-12 复查补上）
        val result = dismissal(
            listOf(courseOf(1, 2, 9, 9, "教学南大楼(南B206)")),
            now = at(19, 30),
            hourly = listOf(hour("2026-10-13T19:00", code = 61, prob = 80)),
        )
        assertNotNull(result)
        assertEquals(TodayTip.Kind.Schedule, result!!.kind)
    }

    // ---- 变体稳定性 ----

    @Test
    fun `同一天内文案稳定`() {
        val courses = tuesdayMorning("教学南大楼(南B206)", "教学北大楼(北B306)")
        val first = dayTip(tuesday, courses)!!.text
        val second = dayTip(tuesday, courses)!!.text
        assertEquals(first, second)
    }

    @Test
    fun `相邻两天能换出不同变体`() {
        // 变体按天交替：10-12（dayOfYear/2=142）与 10-13（/2=143）取到不同变体。
        // 两天都落在第 6 周，课文各按自己那天的星期排
        val a = dayTip(
            LocalDate.of(2026, 10, 12),
            listOf(
                courseOf(1, 1, 1, 2, "教学南大楼(南B206)"),
                courseOf(2, 1, 3, 4, "教学北大楼(北B306)"),
            ),
        )!!.text
        val b = dayTip(tuesday, tuesdayMorning("教学南大楼(南B206)", "教学北大楼(北B306)"))!!.text
        assertTrue("实际=$a / $b", a != b)
    }
}
