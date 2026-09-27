package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 学业完成情况的纯逻辑（DESIGN §4.29）。
 *
 * 这里钉的是**口径**，不是实现细节：学分单元格怎么解析、修读状态怎么归一、
 * 什么情况下拒绝写库、总账怎么求和。
 */
class ScholarProgressRulesTest {

    // ---- 学分单元格 ----

    @Test
    fun `学分带计划内标注`() {
        val cell = ScholarProgressRules.parseCredit("0.5 （计划内）")
        assertEquals(0.5, cell?.value ?: -1.0, 0.0001)
        assertEquals(true, cell?.planned)
    }

    @Test
    fun `学分带计划外标注`() {
        val cell = ScholarProgressRules.parseCredit("2（计划外）")
        assertEquals(2.0, cell?.value ?: -1.0, 0.0001)
        assertEquals(false, cell?.planned)
    }

    @Test
    fun `学分没有标注时为null`() {
        val cell = ScholarProgressRules.parseCredit("3")
        assertEquals(3.0, cell?.value ?: -1.0, 0.0001)
        assertNull(cell?.planned)
    }

    @Test
    fun `学分解析不出数字返回null`() {
        // 宁可让调用方拒绝这一行，也不要写一个 0 学分进去
        assertNull(ScholarProgressRules.parseCredit(""))
        assertNull(ScholarProgressRules.parseCredit("暂无"))
    }

    @Test
    fun `学分带不可见空白也能解析`() {
        val cell = ScholarProgressRules.parseCredit("\u00a01.5\u00a0")
        assertEquals(1.5, cell?.value ?: -1.0, 0.0001)
    }

    // ---- 修读情况归一 ----

    @Test
    fun `两种未修读写法归一成同一个标签`() {
        // 课程体系维度写「未修读」，课程属性维度写「待修读」，同一个意思
        assertEquals(
            ScholarCourseStatus.Pending.label,
            ScholarCourseStatus.fromRaw("未修读").label,
        )
        assertEquals(
            ScholarCourseStatus.Pending.label,
            ScholarCourseStatus.fromRaw("待修读").label,
        )
    }

    @Test
    fun `修读中与已修读分别命中`() {
        assertEquals(ScholarCourseStatus.Ongoing, ScholarCourseStatus.fromRaw("修读中"))
        assertEquals(ScholarCourseStatus.Earned, ScholarCourseStatus.fromRaw("已修读"))
    }

    @Test
    fun `认不出的状态退回未修读`() {
        assertEquals(ScholarCourseStatus.Pending, ScholarCourseStatus.fromRaw(""))
        assertEquals(ScholarCourseStatus.Pending, ScholarCourseStatus.fromRaw("未知状态"))
    }

    // ---- 是否学位课 ----

    @Test
    fun `学位课列空串表示不知道而不是否`() {
        assertEquals(true, ScholarProgressRules.parseDegreeCourse("是"))
        assertEquals(false, ScholarProgressRules.parseDegreeCourse("否"))
        assertNull(ScholarProgressRules.parseDegreeCourse(""))
    }

    // ---- 进度百分比 ----

    @Test
    fun `进度百分比超出范围被夹住`() {
        // 教务在「已修远超要求」时给过 17200.0% 这种值
        assertEquals(100, ScholarProgressRules.parsePercent("17200.0%"))
        assertEquals(98, ScholarProgressRules.parsePercent("98.3%"))
    }

    @Test
    fun `无穷与空进度解析成null`() {
        assertNull(ScholarProgressRules.parsePercent("∞%"))
        assertNull(ScholarProgressRules.parsePercent(""))
    }

    // ---- 完整性校验 ----

    private fun progress(
        groups: List<ScholarGroup>,
        courses: List<ScholarCourse>,
        planName: String = "2024 测试专业培养方案及教学计划",
    ) = ScholarProgress(planName = planName, groups = groups, courses = courses)

    private fun group(name: String, required: Double? = 2.0) = ScholarGroup(
        dimension = ScholarDimension.System.id,
        name = name,
        sortOrder = 0,
        requiredCredit = required,
    )

    private fun course(groupName: String, name: String = "测试课程") = ScholarCourse(
        dimension = ScholarDimension.System.id,
        groupName = groupName,
        sortOrder = 0,
        name = name,
    )

    @Test
    fun `没有分组或没有课程都判失败`() {
        assertEquals("没解析到任何分组", ScholarProgressRules.validate(progress(emptyList(), listOf(course("A")))))
        assertEquals("没解析到任何课程", ScholarProgressRules.validate(progress(listOf(group("A")), emptyList())))
    }

    @Test
    fun `分组与课程名字对不上判失败`() {
        val result = ScholarProgressRules.validate(
            progress(listOf(group("通识必修课")), listOf(course("专业课"))),
        )
        assertEquals("分组与课程对不上，页面结构可能已变", result)
    }

    @Test
    fun `有一个分组对得上就通过`() {
        // 公选课类别维度里有若干分组根本没课（经济管理类、劳动教育类…），不能因此判失败
        val result = ScholarProgressRules.validate(
            progress(
                groups = listOf(group("有课的分组"), group("空分组")),
                courses = listOf(course("有课的分组")),
            ),
        )
        assertNull(result)
    }

    @Test
    fun `学分要求全为0只对课程体系维度判失败`() {
        // 公选课类别维度所有分组的学分要求都是 0.0，这条检查不能当通用闸门
        val zeroed = progress(
            groups = listOf(group("创新创业类", required = 0.0)),
            courses = listOf(course("创新创业类")),
        )
        assertNull(ScholarProgressRules.validate(zeroed))
        assertEquals(
            "分组的要求学分合计为 0，页面结构可能已变",
            ScholarProgressRules.validateCreditTotal(zeroed),
        )
    }

    @Test
    fun `课程体系维度学分合计正常时通过`() {
        val ok = progress(
            groups = listOf(group("通识必修课", required = 58.5), group("专业课", required = 46.0)),
            courses = listOf(course("通识必修课"), course("专业课")),
        )
        assertNull(ScholarProgressRules.validateCreditTotal(ok))
    }

    // ---- 总账 ----

    @Test
    fun `总账逐项求和并跳过空值`() {
        val groups = listOf(
            ScholarGroup(
                dimension = ScholarDimension.System.id,
                name = "通识必修课",
                sortOrder = 0,
                requiredCredit = 58.5,
                earnedCredit = 57.5,
                remainingCredit = 0.8,
            ),
            ScholarGroup(
                dimension = ScholarDimension.System.id,
                name = "专业限选课",
                sortOrder = 1,
                requiredCredit = null,
                earnedCredit = 2.0,
                remainingCredit = null,
            ),
        )
        val courses = listOf(
            course("通识必修课", "形势与政策5").copy(
                courseNo = "030420143",
                credit = 0.25,
                status = ScholarCourseStatus.Ongoing.label,
            ),
            course("通识必修课", "机电传动控制B").copy(
                courseNo = "080803005",
                credit = 2.0,
                status = ScholarCourseStatus.Ongoing.label,
            ),
            course("通识必修课", "已修完的课").copy(
                courseNo = "000000001",
                credit = 3.0,
                status = ScholarCourseStatus.Earned.label,
            ),
        )
        val totals = ScholarProgressRules.totals(groups, courses)
        assertEquals(58.5, totals.required, 0.0001)
        assertEquals(59.5, totals.earned, 0.0001)
        // 在修只算「修读中」的课，已修读的不进这一格
        assertEquals(2.25, totals.ongoing, 0.0001)
        assertEquals(0.8, totals.remaining, 0.0001)
    }

    @Test
    fun `在修不看分组头的正修读字段`() {
        // 教务的分组块实测不给这个数，「在修」必须从明细现算；
        // 就算分组头带了值（教务将来加回来），也不该影响汇总
        val groups = listOf(
            ScholarGroup(
                dimension = ScholarDimension.System.id,
                name = "通识必修课",
                sortOrder = 0,
                requiredCredit = 58.5,
                ongoingCredit = 99.0,
            ),
        )
        assertEquals(0.0, ScholarProgressRules.totals(groups).ongoing, 0.0001)
    }

    @Test
    fun `在修按课程编号去重`() {
        // 同一门课在同一维度下被列进两个分组时不能重复计数
        val groups = listOf(
            ScholarGroup(dimension = ScholarDimension.System.id, name = "甲", sortOrder = 0),
            ScholarGroup(dimension = ScholarDimension.System.id, name = "乙", sortOrder = 1),
        )
        val duplicated = listOf(
            course("甲", "同一门课").copy(courseNo = "DUP", credit = 2.0, status = ScholarCourseStatus.Ongoing.label),
            course("乙", "同一门课").copy(courseNo = "DUP", credit = 2.0, status = ScholarCourseStatus.Ongoing.label),
        )
        assertEquals(2.0, ScholarProgressRules.totals(groups, duplicated).ongoing, 0.0001)
    }

    @Test
    fun `空分组列表的总账全是零`() {
        val totals = ScholarProgressRules.totals(emptyList())
        assertEquals(0.0, totals.required, 0.0001)
        assertEquals(0.0, totals.earned, 0.0001)
        assertFalse(totals.remaining > 0.0)
        assertTrue(totals.ongoing == 0.0)
    }

    // ---- 维度 ----

    @Test
    fun `维度id能往返`() {
        ScholarDimension.entries.forEach { dim ->
            assertEquals(dim, ScholarDimension.fromId(dim.id))
        }
        assertNull(ScholarDimension.fromId("nonexistent"))
    }

    // ---- 搜索（UI 的课程搜索框，逻辑放在 domain 才测得动）----

    private val searchable = mapOf(
        "学科基础课" to listOf(
            course("学科基础课", "高等数学A(上)").copy(courseNo = "070121000"),
            course("学科基础课", "大学物理A实验").copy(courseNo = "070227010"),
        ),
        "专业课" to listOf(
            course("专业课", "PLC原理及应用B").copy(courseNo = "080803002"),
        ),
        "经济管理类" to emptyList(),
    )

    @Test
    fun `空关键字原样返回且保留没课的分组`() {
        // 搜索关掉时列表要恢复教务给的全貌；顺手滤掉空分组会让维度少几行、像数据丢了
        val result = ScholarProgressRules.filterForSearch(searchable, "")
        assertEquals(searchable.keys, result.keys)
        assertEquals(2, result["学科基础课"]?.size)
    }

    @Test
    fun `按课程名匹配并滤掉空分组`() {
        val result = ScholarProgressRules.filterForSearch(searchable, "大学物理")
        assertEquals(setOf("学科基础课"), result.keys)
        assertEquals(listOf("大学物理A实验"), result["学科基础课"]?.map { it.name })
    }

    @Test
    fun `按课程编号匹配`() {
        val result = ScholarProgressRules.filterForSearch(searchable, "080803002")
        assertEquals(listOf("PLC原理及应用B"), result["专业课"]?.map { it.name })
    }

    @Test
    fun `英文课程名忽略大小写`() {
        val result = ScholarProgressRules.filterForSearch(searchable, "plc")
        assertEquals(listOf("PLC原理及应用B"), result["专业课"]?.map { it.name })
    }

    @Test
    fun `搜不到时返回空表`() {
        assertTrue(ScholarProgressRules.filterForSearch(searchable, "不存在的课").isEmpty())
    }

    @Test
    fun `关键字首尾空白被忽略`() {
        val result = ScholarProgressRules.filterForSearch(searchable, "  高等数学  ")
        assertEquals(listOf("高等数学A(上)"), result["学科基础课"]?.map { it.name })
    }
}
