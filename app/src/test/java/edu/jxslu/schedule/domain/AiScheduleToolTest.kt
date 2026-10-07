package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 调课工具解析（DESIGN §4.39）：钉住「只认编号、old 只取本机、非法条目逐条丢弃」的契约。
 *
 * 核心契约：
 * - `modify` / `remove` 的 `old` 一律从本机课表取，模型回抄的旧字段不被采信；
 * - 越界编号 / 缺必填字段 / 非法取值只丢该条，其余条目照常产出；
 * - 修改前后指纹一致视为无变化，丢弃而不是产出一条空改动。
 */
class AiScheduleToolTest {

    private fun course(
        name: String = "高等数学",
        teacher: String = "张三",
        position: String = "教一101",
        day: Int = 3,
        startSection: Int = 3,
        endSection: Int = 4,
        weeks: Set<Int> = (1..16).toSet(),
        kind: CourseKind = CourseKind.Theory,
    ) = Course(
        id = 0,
        name = name,
        teacher = teacher,
        position = position,
        day = day,
        startSection = startSection,
        endSection = endSection,
        weeks = weeks,
        kind = kind,
    )

    private val current = listOf(
        course(name = "高等数学", day = 3),
        course(name = "大学物理", day = 5, startSection = 1, endSection = 2),
    )

    @Test
    fun `modify 只认编号 old 取自本机课表`() {
        val result = AiScheduleTool.parse(
            """
            {"message":"把高数挪到周五","changes":[
              {"kind":"modify","existingIndex":1,"new":{"day":5},"reason":"通知要求"}
            ]}
            """.trimIndent(),
            current,
        )
        assertEquals(1, result.changes.size)
        val change = result.changes.single()
        assertEquals(ScheduleDiff.ChangeKind.MODIFIED, change.kind)
        // old 必须是本机条目原样，而不是模型回抄的字段
        assertEquals(current[0], change.old)
        assertEquals(5, change.new?.day)
        // 未提到的字段保持原值
        assertEquals(3, change.new?.startSection)
        assertEquals("张三", change.new?.teacher)
        assertEquals(listOf("通知要求"), result.reasons)
        assertTrue(result.rejected.isEmpty())
    }

    @Test
    fun `编号越界只丢该条 其余照常产出`() {
        val result = AiScheduleTool.parse(
            """
            {"message":"","changes":[
              {"kind":"remove","existingIndex":9},
              {"kind":"remove","existingIndex":2}
            ]}
            """.trimIndent(),
            current,
        )
        assertEquals(1, result.changes.size)
        assertEquals(ScheduleDiff.ChangeKind.REMOVED, result.changes.single().kind)
        assertEquals("大学物理", result.changes.single().old?.name)
        assertEquals(1, result.rejected.size)
        assertTrue(result.rejected.single().reason.contains("越界"))
    }

    @Test
    fun `缺少 existingIndex 的 remove 被拒`() {
        val result = AiScheduleTool.parse(
            """{"message":"","changes":[{"kind":"remove"}]}""",
            current,
        )
        assertTrue(result.changes.isEmpty())
        assertTrue(result.rejected.single().reason.contains("越界"))
    }

    @Test
    fun `add 缺必填字段被拒`() {
        val result = AiScheduleTool.parse(
            """
            {"message":"","changes":[
              {"kind":"add","new":{"name":"体育","day":2,"startSection":1}}
            ]}
            """.trimIndent(),
            current,
        )
        assertTrue(result.changes.isEmpty())
        assertTrue(result.rejected.single().reason.contains("add"))
    }

    @Test
    fun `add 字段齐全产出新增`() {
        val result = AiScheduleTool.parse(
            """
            {"message":"","changes":[
              {"kind":"add","new":{"name":"体育","teacher":"李四","position":"操场",
               "day":2,"startSection":1,"endSection":2,"weeks":[1,2,3,4,5],"kind":"lab"}}
            ]}
            """.trimIndent(),
            current,
        )
        val change = result.changes.single()
        assertEquals(ScheduleDiff.ChangeKind.ADDED, change.kind)
        assertEquals("体育", change.new?.name)
        assertEquals(CourseKind.Lab, change.new?.kind)
        assertEquals(setOf(1, 2, 3, 4, 5), change.new?.weeks)
    }

    @Test
    fun `未知 kind 被拒`() {
        val result = AiScheduleTool.parse(
            """{"message":"","changes":[{"kind":"swap","existingIndex":1}]}""",
            current,
        )
        assertTrue(result.changes.isEmpty())
        assertTrue(result.rejected.single().reason.contains("未知的改动类型"))
    }

    @Test
    fun `modify 没有给出字段被拒`() {
        val result = AiScheduleTool.parse(
            """{"message":"","changes":[{"kind":"modify","existingIndex":1,"new":{}}]}""",
            current,
        )
        assertTrue(result.changes.isEmpty())
        assertTrue(result.rejected.single().reason.contains("没有给出要修改的字段"))
    }

    @Test
    fun `修改前后完全一致被拒`() {
        val result = AiScheduleTool.parse(
            """{"message":"","changes":[{"kind":"modify","existingIndex":1,"new":{"day":3}}]}""",
            current,
        )
        assertTrue(result.changes.isEmpty())
        assertTrue(result.rejected.single().reason.contains("无实际变化"))
    }

    @Test
    fun `非法节次被拒`() {
        val result = AiScheduleTool.parse(
            """{"message":"","changes":[{"kind":"modify","existingIndex":1,"new":{"startSection":12,"endSection":13}}]}""",
            current,
        )
        assertTrue(result.changes.isEmpty())
        assertTrue(result.rejected.single().reason.contains("不合法"))
    }

    @Test
    fun `非 JSON 参数整批被拒`() {
        val result = AiScheduleTool.parse("not json at all", current)
        assertTrue(result.changes.isEmpty())
        assertEquals(1, result.rejected.size)
    }

    @Test
    fun `overrides 解析出补课与放假`() {
        val result = AiScheduleTool.parse(
            """
            {"message":"国庆放假并调休","changes":[],"overrides":[
              {"kind":"suspended","fromDate":"2026-09-25","toDate":"2026-10-07","reason":"放假"},
              {"kind":"follow","fromDate":"2026-09-20","sourceWeek":5,"sourceWeekday":1,"reason":"补课"}
            ]}
            """.trimIndent(),
            current,
            SemesterConfig(startDate = "2026-09-07", totalWeeks = 20),
        )
        assertEquals(2, result.overrides.size)
        assertEquals(OverrideKind.SUSPENDED, result.overrides[0].kind)
        assertEquals("2026-09-25", result.overrides[0].fromDate)
        assertEquals("2026-10-07", result.overrides[0].toDate)
        assertEquals(OverrideKind.FOLLOW, result.overrides[1].kind)
        assertEquals(5, result.overrides[1].sourceWeek)
        assertEquals(1, result.overrides[1].sourceWeekday)
        // follow 是单日：toDate 归一到 fromDate
        assertEquals("2026-09-20", result.overrides[1].toDate)
        assertTrue(result.rejected.isEmpty())
    }

    @Test
    fun `overrides 的 follow 缺源槽位被拒`() {
        val result = AiScheduleTool.parse(
            """{"message":"","changes":[],"overrides":[{"kind":"follow","fromDate":"2026-09-20"}]}""",
            current,
        )
        assertTrue(result.overrides.isEmpty())
        assertEquals(1, result.rejected.size)
    }

    @Test
    fun `overrides 日期非法被拒`() {
        val result = AiScheduleTool.parse(
            """{"message":"","changes":[],"overrides":[{"kind":"suspended","fromDate":"下周一"}]}""",
            current,
        )
        assertTrue(result.overrides.isEmpty())
        assertTrue(result.rejected.single().reason.contains("日期覆盖不合法"))
    }

    @Test
    fun `overrides 的源周次超出学期被拒`() {
        val result = AiScheduleTool.parse(
            """{"message":"","changes":[],"overrides":[{"kind":"follow","fromDate":"2026-09-20","sourceWeek":99,"sourceWeekday":1}]}""",
            current,
            SemesterConfig(startDate = "2026-09-07", totalWeeks = 20),
        )
        assertTrue(result.overrides.isEmpty())
        assertEquals(1, result.rejected.size)
    }

    @Test
    fun `extractJson 剥围栏并配对括号`() {
        val text = "好的：\n```json\n{\"message\":\"a\",\"changes\":[]}\n```\n完毕"
        assertEquals("{\"message\":\"a\",\"changes\":[]}", AiScheduleTool.extractJson(text))
    }

    @Test
    fun `extractJson 忽略字符串里的花括号`() {
        val text = """前缀 {"message":"含 } 和 { 的说明","changes":[]} 后缀"""
        assertEquals("""{"message":"含 } 和 { 的说明","changes":[]}""", AiScheduleTool.extractJson(text))
    }

    @Test
    fun `extractJson 无对象时返回 null`() {
        assertNull(AiScheduleTool.extractJson("没有任何 JSON"))
    }
}
