package edu.jxslu.schedule.domain

import edu.jxslu.schedule.data.ai.AiScheduleClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对话式 AI 调课的新增纯逻辑（DESIGN §3.25 / §4.39，2026-10-06）：
 * 追问建议、读图复述、追问轮的「上一轮结果」序列化、图片能力预判、上游不吃图的判定。
 */
class AiScheduleChatTest {

    private val format = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun course(
        id: Long = 0,
        name: String = "高等数学",
        teacher: String = "张三",
        day: Int = 3,
        weeks: Set<Int> = setOf(1, 2, 3),
    ) = Course(
        id = id,
        name = name,
        teacher = teacher,
        position = "教一101",
        day = day,
        startSection = 3,
        endSection = 4,
        weeks = weeks,
        kind = CourseKind.Theory,
    )

    // ---------------- readback（v2 起 suggestions / 追问链已随对话式一起删除） ----------------

    @Test
    fun `readback 被解析出来并去掉首尾空白`() {
        val json = """{"message":"a","readback":"  10/1-10/8 放假\n9/20 补课  ","changes":[],"overrides":[]}"""
        assertEquals("10/1-10/8 放假\n9/20 补课", AiScheduleTool.parse(json, emptyList()).readback)
    }

    @Test
    fun `流式增量解析不取半截的 readback`() {
        // 半截 JSON：readback 还没闭合
        val partial = """{"message":"通知含一段停课","readback":"10/1-1"""
        assertEquals("", AiScheduleTool.parsePartial(partial, emptyList()).readback)
    }

    // ---------------- 脱敏名不得回写（2026-10-06 复核） ----------------

    @Test
    fun `模型回抄的脱敏占位名不会写回库`() {
        val rows = listOf(course(id = 11, teacher = "张三"))
        val json = """{"message":"","changes":[{"kind":"modify","existingIndex":1,"new":{"day":4,"teacher":"教师1"}}],"overrides":[]}"""
        val result = AiScheduleTool.parse(json, rows, maskedTeacherNames = setOf("教师1"))
        assertEquals(1, result.changes.size)
        assertEquals("真实姓名不能被占位名覆盖", "张三", result.changes.first().new?.teacher)
    }

    @Test
    fun `没给脱敏名单时原样接受（旧调用方行为不变）`() {
        val rows = listOf(course(id = 11, teacher = "张三"))
        val json = """{"message":"","changes":[{"kind":"modify","existingIndex":1,"new":{"day":4,"teacher":"李四"}}],"overrides":[]}"""
        val result = AiScheduleTool.parse(json, rows)
        assertEquals("李四", result.changes.first().new?.teacher)
    }

    @Test
    fun `空字符串的教师名当未提供`() {
        val rows = listOf(course(id = 11, teacher = "张三"))
        val json = """{"message":"","changes":[{"kind":"modify","existingIndex":1,"new":{"day":4,"teacher":""}}],"overrides":[]}"""
        val result = AiScheduleTool.parse(json, rows)
        assertEquals("张三", result.changes.first().new?.teacher)
    }

    @Test
    fun `空字符串的地点不当成清空`() {
        // 与 teacher/name 同口径：position 进指纹，被静默清掉会以「调整」名义落库
        val rows = listOf(course(id = 11, teacher = "张三"))
        val json = """{"message":"","changes":[{"kind":"modify","existingIndex":1,"new":{"day":4,"position":""}}],"overrides":[]}"""
        val result = AiScheduleTool.parse(json, rows)
        assertEquals("教一101", result.changes.first().new?.position)
    }

    // ---------------- 本机周次不被上限截断 ----------------

    @Test
    fun `只改教室时保留本机 31 周以后的周次`() {
        // 导入允许到 40 周，而工具上限是 30：模型没提 weeks 时不能把本机旧值截断
        val rows = listOf(course(id = 11).copy(weeks = setOf(1, 2, 31, 35)))
        val json = """{"message":"","changes":[{"kind":"modify","existingIndex":1,"new":{"position":"教二202"}}],"overrides":[]}"""
        val result = AiScheduleTool.parse(json, rows)
        assertEquals(setOf(1, 2, 31, 35), result.changes.first().new?.weeks)
    }

    @Test
    fun `模型给的周次仍按上限过滤`() {
        val rows = listOf(course(id = 11))
        val json = """{"message":"","changes":[{"kind":"modify","existingIndex":1,"new":{"weeks":[1,2,99]}}],"overrides":[]}"""
        val result = AiScheduleTool.parse(json, rows)
        assertEquals(setOf(1, 2), result.changes.first().new?.weeks)
    }

    // ---------------- extractJson 取第一个可解析对象 ----------------

    @Test
    fun `说明文字里的花括号不顶掉真正的 JSON`() {
        val text = "这里用 { 花括号 } 表示占位：\n```json\n{\"message\":\"a\",\"changes\":[]}\n```"
        assertEquals(
            """{"message":"a","changes":[]}""",
            AiScheduleTool.extractJson(text),
        )
    }

    @Test
    fun `说明文字里配对的花括号不会顶掉真正的 JSON`() {
        // 前一段是**配对完整**但语义上不是 JSON 的花括号（lenient 解析也可能失败），
        // 这时要跳过它继续找后面那段真 JSON
        val text = """前一段 {"unclosed": 还有 {"message":"a","changes":[]}}"""
        assertEquals(
            """{"message":"a","changes":[]}""",
            AiScheduleTool.extractJson(text),
        )
    }

    @Test
    fun `整段花括号没配对时返回 null 而不是半截`() {
        val text = """前一段 {"unclosed": {"message":"a","changes":[]}"""
        assertNull("配不上对就不该猜", AiScheduleTool.extractJson(text))
    }

    // ---------------- 流式增量：键定位 ----------------

    @Test
    fun `changes 为 null 时不去锁 overrides 的方括号`() {
        // `"changes":null` 后面跟着 overrides 数组：直接 indexOf('[') 会把覆盖条目当改动解析
        val partial = """{"message":"","changes":null,"overrides":[{"kind":"suspended","fromDate":"2026-10-01"}]}"""
        val result = AiScheduleTool.parsePartial(partial, emptyList())
        assertTrue("changes 不该捞到东西", result.changes.isEmpty())
        assertEquals(1, result.overrides.size)
    }

    @Test
    fun `键名出现在字符串里时不会误锚`() {
        val partial = """{"message":"通知里提到 changes 这个词","changes":[{"kind":"remove","existingIndex":1}]}"""
        val result = AiScheduleTool.parsePartial(partial, listOf(course(id = 11)))
        assertEquals(1, result.changes.size)
    }

    // ---------------- userMessage 组成（v2：只放事实，无追问 / 无诉求段） ----------------

    @Test
    fun `userMessage 只含四段事实`() {
        val text = AiSchedulePrompt.userMessage(
            notice = "国庆调休通知",
            courses = listOf(course(id = 11)),
            maskTeacher = false,
            semester = null,
            existingOverrides = listOf(
                ScheduleOverride(fromDate = "2026-09-20", toDate = "2026-09-20", kind = OverrideKind.SUSPENDED),
            ),
        )
        assertTrue(text.contains("【我的课表】"))
        assertTrue(text.contains("【学期日历】"))
        assertTrue(text.contains("【已生效的日期安排】\n2026-09-20 放假（无课）"))
        assertTrue(text.contains("【调课通知】\n国庆调休通知"))
        // 诉求段 / 追问段已随对话式一起删除
        assertFalse(text.contains("【我的诉求】"))
        assertFalse(text.contains("【上一轮结果】"))
    }

    @Test
    fun `userMessage 已生效段为空时写无`() {
        val text = AiSchedulePrompt.userMessage(
            notice = "通知",
            courses = emptyList(),
            maskTeacher = false,
        )
        assertTrue(text.contains("【已生效的日期安排】\n无"))
    }

    // ---------------- 图片能力 ----------------

    @Test
    fun `视觉模型关键字预判`() {
        assertTrue(AiScheduleConfig.looksVisionModel("qwen-vl-max"))
        assertTrue(AiScheduleConfig.looksVisionModel("gpt-4o-mini"))
        assertTrue(AiScheduleConfig.looksVisionModel("claude-3-5-sonnet-20241022"))
        assertTrue(AiScheduleConfig.looksVisionModel("glm-4v-plus"))
        assertTrue(AiScheduleConfig.looksVisionModel("gemini-2.0-flash"))
    }

    @Test
    fun `deepseek-flash 给视觉打勾`() {
        // deepseek-flash = DeepSeek-V4.1-Flash（2026-09-10 发布），官方 model card 标明多模态；
        // 已退役的 deepseek-chat / deepseek-reasoner / legacy 别名 v4-flash 不在名单里
        assertTrue(AiScheduleConfig.looksVisionModel("deepseek-flash"))
        assertTrue(AiScheduleConfig.looksVisionModel("deepseek-v4-flash-vision"))
        assertFalse(AiScheduleConfig.looksVisionModel("deepseek-chat"))
        assertFalse(AiScheduleConfig.looksVisionModel("deepseek-reasoner"))
        assertFalse(AiScheduleConfig.looksVisionModel("deepseek-v4-flash"))
        assertFalse(AiScheduleConfig.looksVisionModel(""))
    }

    @Test
    fun `模型名归一——legacy 别名迁移到现行默认`() {
        // deepseek-flash 是现行官方名（V4.1-Flash）；deepseek-v4-flash 是已退役模型的
        // legacy 别名，读到它迁回现行默认（含上一版误改落盘的值）
        assertEquals("deepseek-flash", AiScheduleConfig.normalizeModel("deepseek-v4-flash"))
        assertEquals("deepseek-flash", AiScheduleConfig.normalizeModel("DeepSeek-V4-Flash"))
        assertEquals("deepseek-flash", AiScheduleConfig.normalizeModel(null))
        assertEquals("deepseek-flash", AiScheduleConfig.normalizeModel(""))
        assertEquals("deepseek-flash", AiScheduleConfig.normalizeModel("  "))
        // 现行名与用户自定义名字尊重原值
        assertEquals("deepseek-flash", AiScheduleConfig.normalizeModel("deepseek-flash"))
        assertEquals("deepseek-v4-pro", AiScheduleConfig.normalizeModel("deepseek-v4-pro"))
        assertEquals("gpt-4o", AiScheduleConfig.normalizeModel(" gpt-4o "))
    }

    @Test
    fun `上游报错里提到图片才算图片类失败`() {
        assertTrue(
            AiScheduleClient.looksLikeImageRejection(
                400,
                """{"error":{"message":"messages[0].content[1].image_url is not supported"}}""",
            ),
        )
        assertTrue(AiScheduleClient.looksLikeImageRejection(422, "model does not support vision input"))
        assertTrue(AiScheduleClient.looksLikeImageRejection(400, "不支持图片输入"))
    }

    @Test
    fun `其它 4xx 与 5xx 不算图片类失败`() {
        assertFalse(AiScheduleClient.looksLikeImageRejection(401, "invalid api key"))
        assertFalse(AiScheduleClient.looksLikeImageRejection(402, "insufficient balance"))
        // 5xx 不是「模型不吃图」，别把服务端故障误判成配置问题
        assertFalse(AiScheduleClient.looksLikeImageRejection(500, "image pipeline error"))
    }

    @Test
    fun `工具 schema 声明了 readback 且不再有 suggestions`() {
        // suggestions / 追问链已随对话式一起删除（v2 简化）：schema 不该再声明它
        val schema = format.parseToJsonElement(AiScheduleTool.PARAMETERS_JSON) as JsonObject
        val properties = schema["properties"] as JsonObject
        assertTrue(properties.containsKey("readback"))
        assertFalse("suggestions 已删，schema 不该再有", properties.containsKey("suggestions"))
        val required = schema["required"] as JsonArray
        assertTrue(required.none { (it as JsonPrimitive).content == "suggestions" })
    }

    // ---------------- 工作单派生量（2026-10-07 改版：单份 AiWorkState） ----------------

    @Test
    fun `勾选默认全勾且换一轮即重置`() {
        val result = AiScheduleTool.Result(
            changes = listOf(
                ScheduleDiff.Change(ScheduleDiff.ChangeKind.ADDED, new = course(id = 0)),
                ScheduleDiff.Change(ScheduleDiff.ChangeKind.REMOVED, old = course(id = 3)),
            ),
            overrides = listOf(
                ScheduleOverride(fromDate = "2026-10-01", toDate = "2026-10-01", kind = OverrideKind.SUSPENDED),
            ),
        )
        val fresh = edu.jxslu.schedule.ui.ai.AiWorkState(result = result)
        assertEquals(setOf(0, 1), fresh.effectiveSelection)
        assertEquals(setOf(0), fresh.effectiveOverrideSelection)
        assertEquals(3, fresh.selectedCount)
        assertEquals(3, fresh.totalCount)

        // 只收窄 changes：覆盖仍是默认全勾，所以总数是 1 + 1
        val narrowed = fresh.copy(selected = setOf(1))
        assertEquals(setOf(1), narrowed.effectiveSelection)
        assertEquals(2, narrowed.selectedCount)

        // 两类都收窄到 0 才是「一处都不应用」
        val none = fresh.copy(selected = emptySet(), selectedOverrides = emptySet())
        assertEquals(0, none.selectedCount)
    }

    @Test
    fun `没结果时判为空且未应用`() {
        val empty = edu.jxslu.schedule.ui.ai.AiWorkState(
            result = AiScheduleTool.Result(message = "没有需要改动的课程"),
        )
        assertTrue(empty.isEmptyResult)
        assertFalse(empty.isApplied)
    }

    @Test
    fun `撤销后不算已应用`() {
        val receipt = edu.jxslu.schedule.ui.ai.AiAppliedReceipt(
            appliedCourses = 1,
            appliedOverrides = 1,
            skipped = 0,
            entries = emptyList(),
            overrideIds = emptyList(),
            timetableId = 1L,
            at = "9:43",
            proposalSeq = 1,
        )
        // 合计处数 = 课表改动 + 日期覆盖（只算前者时调休类通知会显示「已应用 0 处」）
        assertEquals(2, receipt.applied)
        val applied = edu.jxslu.schedule.ui.ai.AiWorkState(
            receipt = receipt,
            phase = edu.jxslu.schedule.ui.ai.AiPhase.APPLIED,
        )
        assertTrue(applied.isApplied)
        val undone = applied.copy(phase = edu.jxslu.schedule.ui.ai.AiPhase.UNDONE)
        assertFalse(undone.isApplied)
    }

    // ---------------- 已生效的日期安排（2026-10-07：同一通知重发不再重报） ----------------

    @Test
    fun `与已生效安排等价的提议进拒绝项而不是待确认`() {
        val existing = listOf(
            ScheduleOverride(
                fromDate = "2026-09-20",
                toDate = "2026-09-20",
                kind = OverrideKind.FOLLOW,
                sourceWeek = 5,
                sourceWeekday = 1,
            ),
            ScheduleOverride(fromDate = "2026-09-25", toDate = "2026-10-07", kind = OverrideKind.SUSPENDED),
        )
        val json = """
            {"message":"","changes":[],"overrides":[
              {"kind":"follow","fromDate":"2026-09-20","sourceWeek":5,"sourceWeekday":1},
              {"kind":"suspended","fromDate":"2026-09-25","toDate":"2026-10-07"},
              {"kind":"follow","fromDate":"2026-10-11","sourceWeek":6,"sourceWeekday":4}]}
        """.trimIndent()
        val result = AiScheduleTool.parse(json, emptyList(), existingOverrides = existing)
        // 已生效的两条不再列为待确认；新的一天照常产出
        assertEquals(1, result.overrides.size)
        assertEquals("2026-10-11", result.overrides.first().fromDate)
        // 不静默吞：两条重复要可见，并注明原因
        assertEquals(2, result.rejected.size)
        assertTrue(result.rejected.all { it.reason.contains("已生效") })
    }

    @Test
    fun `userMessage 带已生效的日期安排段`() {
        val msg = AiSchedulePrompt.userMessage(
            notice = "通知",
            courses = emptyList(),
            maskTeacher = false,
            existingOverrides = listOf(
                ScheduleOverride(
                    fromDate = "2026-09-20",
                    toDate = "2026-09-20",
                    kind = OverrideKind.FOLLOW,
                    sourceWeek = 5,
                    sourceWeekday = 1,
                ),
                ScheduleOverride(fromDate = "2026-09-25", toDate = "2026-10-07", kind = OverrideKind.SUSPENDED),
            ),
        )
        assertTrue(msg.contains("【已生效的日期安排】"))
        assertTrue(msg.contains("2026-09-20 按 第5周周一 的课表上课"))
        assertTrue(msg.contains("2026-09-25 至 2026-10-07 放假（无课）"))

        // 为空也必须给段（写「无」）——模型才知道是「没有已生效安排」而不是「没给这个信息」
        val empty = AiSchedulePrompt.userMessage("通知", emptyList(), false)
        assertTrue(empty.contains("【已生效的日期安排】\n无"))
    }
}
