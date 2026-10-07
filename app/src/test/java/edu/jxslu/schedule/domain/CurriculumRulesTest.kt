package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 培养方案分组与校验的纯规则（DESIGN §4.41）。 */
class CurriculumRulesTest {

    private fun course(term: Int, credit: Double? = 1.0, name: String = "测试课") =
        CurriculumCourse(
            term = term,
            sortOrder = 0,
            courseNo = "000000000",
            name = name,
            kind = "通识必修课",
            attr = "必修",
            credit = credit,
        )

    @Test
    fun `按学年分组只保留有课的学年与学期`() {
        val years = CurriculumRules.byYear(
            listOf(course(1), course(1), course(2), course(3), course(5)),
        )
        assertEquals(listOf("大一", "大二", "大三"), years.map { it.label })
        assertEquals(listOf(1, 2), years[0].terms.map { it.term })
        assertEquals(3, years[0].courseCount)
        assertEquals(listOf(3), years[1].terms.map { it.term })
        assertEquals(listOf(5), years[2].terms.map { it.term })
    }

    @Test
    fun `学期与学年标签`() {
        assertEquals("大一上", CurriculumRules.termLabel(1))
        assertEquals("大一下", CurriculumRules.termLabel(2))
        assertEquals("大四下", CurriculumRules.termLabel(8))
        // 0 = 未标注，越界同样落到未标注（不猜）
        assertEquals("未标注", CurriculumRules.termLabel(0))
        assertEquals("未标注", CurriculumRules.termLabel(9))
        assertEquals("大一", CurriculumRules.yearLabel(0))
        assertEquals("大三", CurriculumRules.yearLabel(2))
        assertEquals("其它", CurriculumRules.yearLabel(4))
    }

    @Test
    fun `学分合计跳过解析不出的行`() {
        val total = CurriculumRules.totalCredit(
            listOf(course(1, 3.0), course(2, null), course(2, 0.5)),
        )
        assertEquals(3.5, total, 0.0001)
    }

    @Test
    fun `按学年分组的学分与门数只算本学年`() {
        val years = CurriculumRules.byYear(
            listOf(course(1, 3.0), course(2, 0.5), course(3, 10.0)),
        )
        assertEquals(3.5, years[0].credits, 0.0001)
        assertEquals(10.0, years[1].credits, 0.0001)
    }

    @Test
    fun `validate挡住空表与全无学期的残值`() {
        assertEquals("没解析到任何课程", CurriculumRules.validate(Curriculum("", emptyList())))
        // 半张表残值：课程全在、但一门都没有开设学期——页面结构已变的信号
        assertNotNull(
            CurriculumRules.validate(Curriculum("方案", listOf(course(0), course(0)))),
        )
        assertNull(
            CurriculumRules.validate(Curriculum("方案", listOf(course(2), course(0)))),
        )
    }
}
