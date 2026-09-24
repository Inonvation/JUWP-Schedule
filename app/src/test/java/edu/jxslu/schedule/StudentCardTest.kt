package edu.jxslu.schedule

import edu.jxslu.schedule.data.jw.ScoreParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 学籍卡片解析（DESIGN §3.3 账号条数据源）：姓名走 form，班级/学号走顶栏明文标签。 */
class StudentCardTest {

    private val html = """
        <html><body>
          <div class="HeaderContentDetailtext">
            <span class="detiailtextItem">院系：示例学院</span>
            <span class="detiailtextItem">班级：24示例专业01</span>
            <span class="detiailtextItem">学号：2024000000</span>
          </div>
          <form>
            <div>
              <label class="layui-form-label">姓名</label>
              <div class="layui-input-block">
                <input name="kkbs" value="张三" class="layui-input" readonly>
              </div>
            </div>
          </form>
        </body></html>
    """.trimIndent()

    @Test
    fun `解析姓名班级学号`() {
        val card = ScoreParser.parseStudentCard(html)
        assertEquals("张三", card?.name)
        assertEquals("24示例专业01", card?.studentClass)
        assertEquals("2024000000", card?.studentId)
    }

    @Test
    fun `缺任意一项返回null`() {
        assertNull(ScoreParser.parseStudentCard(html.replace("学号：2024000000", "")))
        assertNull(ScoreParser.parseStudentCard(html.replace("value=\"张三\"", "value=\"\"")))
        assertNull(ScoreParser.parseStudentCard(html.replace("班级：24示例专业01", "")))
    }

    @Test
    fun `错误页与空串返回null`() {
        assertNull(ScoreParser.parseStudentCard("ERR:timeout"))
        assertNull(ScoreParser.parseStudentCard(""))
    }
}
