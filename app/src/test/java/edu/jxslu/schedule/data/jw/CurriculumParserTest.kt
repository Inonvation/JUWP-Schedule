package edu.jxslu.schedule.data.jw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 培养方案「课程设置总表」HTML 解析（DESIGN §4.41）。
 *
 * fixture 按实测结构手写（真实抓取结果不进公开仓库）：结构、class 名、换行与缩进
 * 照教务原样，数据虚构。**学时五列的 class 在 DOM 里全同（cols-9-2-1）**——
 * fixture 必须保留这个特征，它正是解析器最容易搞错的地方（只能按行内出现顺序认）。
 */
class CurriculumParserTest {

    private val page = """
        <!DOCTYPE html><html><body>
        <div class="item-area">
          <p class="item-area-content-inner"> 20XX 测试专业培养方案 </p>
        </div>
        <div class="item-area-title">三、课程设置总表</div>
        <div class="body-table">
          <div class="body-table-tr">
            <div class="tixi-cell cols-1">
              <div class="tixi-cell-text">通识必修课</div>
            </div>
            <div class="sub-content"><div class="son-table">
              <div class="son-table-tr">
                <div class="table-td-cell cols-3"></div>
                <div class="table-td-cell cols-4">000000001</div>
                <div class="table-td-cell cols-5">测试课程甲</div>
                <div class="table-td-cell cols-6">通识必修课</div>
                <div class="table-td-cell cols-7">必修</div>
                <div class="table-td-cell cols-8">3</div>
                <div class="table-td-cell cols-9-2-1">46</div>
                <div class="table-td-cell cols-9-2-1">2</div>
                <div class="table-td-cell cols-9-2-1">0</div>
                <div class="table-td-cell cols-9-2-1">0</div>
                <div class="table-td-cell cols-9-2-1">48</div>
                <div class="table-td-cell cols-10">2</div>
              </div>
              <div class="son-table-tr">
                <div class="table-td-cell cols-3"></div>
                <div class="table-td-cell cols-4">000000002</div>
                <div class="table-td-cell cols-5">测试实践课</div>
                <div class="table-td-cell cols-6">集中实践教学环节</div>
                <div class="table-td-cell cols-7">必修</div>
                <div class="table-td-cell cols-8">1</div>
                <div class="table-td-cell cols-9-2-1">0</div>
                <div class="table-td-cell cols-9-2-1">2(周)</div>
                <div class="table-td-cell cols-9-2-1">0</div>
                <div class="table-td-cell cols-9-2-1">0</div>
                <div class="table-td-cell cols-9-2-1">0</div>
                <div class="table-td-cell cols-10">8</div>
              </div>
              <div class="son-table-tr">
                <div class="table-td-cell cols-3"></div>
                <div class="table-td-cell cols-4"></div>
                <div class="table-td-cell cols-5"></div>
                <div class="table-td-cell cols-6"></div>
                <div class="table-td-cell cols-7"></div>
                <div class="table-td-cell cols-8">928</div>
                <div class="table-td-cell cols-9-2-1">80</div>
                <div class="table-td-cell cols-9-2-1">0</div>
                <div class="table-td-cell cols-9-2-1">0</div>
                <div class="table-td-cell cols-9-2-1">1008</div>
                <div class="table-td-cell cols-10"></div>
              </div>
            </div></div>
          </div>
          <div class="body-table-tr">
            <div class="tixi-cell cols-1">
              <div class="tixi-cell-text">专业课</div>
            </div>
            <div class="sub-content"><div class="son-table">
              <div class="son-table-tr">
                <div class="table-td-cell cols-3"></div>
                <div class="table-td-cell cols-4">000000003</div>
                <div class="table-td-cell cols-5">测试课程丙</div>
                <div class="table-td-cell cols-6">专业课</div>
                <div class="table-td-cell cols-7">限选</div>
                <div class="table-td-cell cols-8">2.5</div>
                <div class="table-td-cell cols-9-2-1">32</div>
                <div class="table-td-cell cols-9-2-1">8</div>
                <div class="table-td-cell cols-9-2-1">0</div>
                <div class="table-td-cell cols-9-2-1">0</div>
                <div class="table-td-cell cols-9-2-1">40</div>
                <div class="table-td-cell cols-10">5</div>
              </div>
            </div></div>
          </div>
        </div>
        </body></html>
    """.trimIndent()

    @Test
    fun `解析出方案名与全部课程`() {
        val parsed = CurriculumParser.parse(page)
        assertNotNull(parsed)
        assertEquals("20XX 测试专业培养方案", parsed!!.planName)
        // 汇总行（无课程名）被丢掉，不产生第 4 条
        assertEquals(3, parsed.courses.size)
    }

    @Test
    fun `字段逐列落位且学时按行内顺序对应`() {
        val c = CurriculumParser.parse(page)!!.courses.first()
        assertEquals(2, c.term)
        assertEquals(0, c.sortOrder)
        assertEquals("000000001", c.courseNo)
        assertEquals("测试课程甲", c.name)
        assertEquals("通识必修课", c.kind)
        assertEquals("必修", c.attr)
        assertEquals(3.0, c.credit!!, 0.0001)
        // 五个子列 class 全同：顺序必须是 理论 / 实践 / 讲座 / 实验 / 总
        assertEquals("46", c.hoursTheory)
        assertEquals("2", c.hoursPractice)
        assertEquals("0", c.hoursLecture)
        assertEquals("0", c.hoursExperiment)
        assertEquals("48", c.hoursTotal)
        assertEquals("通识必修课", c.tixi)
    }

    @Test
    fun `实践学时保留周次原文`() {
        val c = CurriculumParser.parse(page)!!.courses[1]
        assertEquals("2(周)", c.hoursPractice)
        assertEquals("集中实践教学环节", c.kind)
        assertEquals(8, c.term)
    }

    @Test
    fun `切换体系块后课程归属跟着变`() {
        val c = CurriculumParser.parse(page)!!.courses[2]
        assertEquals("专业课", c.tixi)
        assertEquals("限选", c.attr)
        assertEquals(2.5, c.credit!!, 0.0001)
        assertEquals(5, c.term)
    }

    @Test
    fun `没有课程名的行被丢掉而不是写进库`() {
        val parsed = CurriculumParser.parse(page)!!
        assertTrue(parsed.courses.none { it.name.isBlank() })
        assertEquals(3, parsed.courses.size)
    }

    @Test
    fun `未登录页解析成null`() {
        assertNull(CurriculumParser.parse("<html><body>请先登录系统</body></html>"))
        assertNull(CurriculumParser.parse("<html><body>用户没有登录</body></html>"))
        assertNull(CurriculumParser.parse(""))
    }

    @Test
    fun `结构变了返回null`() {
        // 有标记但没有课程行
        assertNull(CurriculumParser.parse("<html><body>三、课程设置总表 但没有任何行</body></html>"))
    }

    @Test
    fun `开课学期非法时归为0学分缺省为null`() {
        val weird = """
            <html><body><p class="item-area-content-inner">测试方案</p>
            三、课程设置总表
            <div class="body-table-tr">
              <div class="tixi-cell cols-1"><div class="tixi-cell-text">通识必修课</div></div>
              <div class="son-table-tr">
                <div class="table-td-cell cols-4">1</div>
                <div class="table-td-cell cols-5">测试课</div>
                <div class="table-td-cell cols-10">待定</div>
              </div>
            </div></body></html>
        """.trimIndent()
        val c = CurriculumParser.parse(weird)!!.courses.single()
        assertEquals(0, c.term)
        assertNull(c.credit)
    }
}
