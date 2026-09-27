package edu.jxslu.schedule.data.jw

import edu.jxslu.schedule.domain.ScholarCourseStatus
import edu.jxslu.schedule.domain.ScholarDimension
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 学业完成情况 HTML 解析（DESIGN §4.29）。
 *
 * fixture 是**按实测结构手写的**（真实抓取结果不进公开仓库的测试文件）：结构、class 名、
 * 换行与缩进都照教务原样，数据换成虚构的。三段 fixture 分别代表三种列集合：
 * 课程体系 11 列 / 课程性质 9 列（没有「修读学期」）/ 公选课类别 10 列（没有「课程属性」）。
 */
class ScholarProgressParserTest {

    // ---- 课程体系：11 列，字段最全 ----

    private val kctxHtml = """
        <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Transitional//EN" "dtd">
        <html xmlns="http://www.w3.org/1999/xhtml"><body>
        <div class="mod-total-area">
          <div class="header-item"><h5 class="header-item-text">2024 某专业培养方案及教学计划</h5></div>
          <div class="total-list">
            <div class="list-tr"><div class="list-td"><span class="list-td-cell jClass-item">通识必修课</span></div><div class="list-td"><span class="list-td-cell">58.5</span></div></div>
          </div>
        </div>
        <div class="mod-item-detail box-shadow">
          <div class="header-content">
            <div class="header-content-title">
              <h5 class="header-title"><span class="header-title-text">课程体系 :</span><b id="jClass-1" class="header-title-blod jClassName">通识必修课<i class="layui-icon jClassName">&#xe62f;</i></b></h5>
            </div>
            <div class="header-content-area">
              <div class="study-infos">
                <div class="study-result"><label class="study-result-label">本轮结论 :</label><span class="study-result-content"><span class="result-tag bg-red"><span class="result-tag-text">未通过</span></span></span></div>
                <div class="study-info"><label class="study-info-label">学分要求 : 58.5</label><span class="study-info-content"><div class="layui-progress"><div class="layui-progress-bar layui-bg-blue" lay-percent="98.3%"></div></div></span></div>
              </div>
              <div class="study-numbers">
                <span class="study-number-item">
                  已修<b class="study-number"> 57.5 </b>
                </span>
                <span class="study-number-item">
                  还需<b class="study-number"> 0.8 </b>
                </span>
              </div>
            </div>
          </div>
          <div class="sub-table">
            <div class="sub-table-header-tr">
              <div class="header-th"><span class="header-th-cell">修读学期</span></div>
              <div class="header-th"><span class="header-th-cell">课程编号</span></div>
              <div class="header-th"><span class="header-th-cell">课程名称</span></div>
              <div class="header-th"><span class="header-th-cell">学分</span></div>
              <div class="header-th"><span class="header-th-cell">课程类别</span></div>
              <div class="header-th"><span class="header-th-cell">课程属性</span></div>
              <div class="header-th"><span class="header-th-cell">课程性质</span></div>
              <div class="header-th"><span class="header-th-cell">修读情况</span></div>
              <div class="header-th"><span class="header-th-cell">课程成绩</span></div>
              <div class="header-th"><span class="header-th-cell">备注</span></div>
              <div class="header-th"><span class="header-th-cell">是否学位课</span></div>
            </div>
            <div class="list-tr">
              <div class="list-td"><span class="list-td-cell">
                2025-2026-2
              </span></div>
              <div class="list-td"><span class="list-td-cell">030401002</span></div>
              <div class="list-td"><span class="list-td-cell">大学生职业生涯规划（下）</span></div>
              <div class="list-td"><span class="list-td-cell">
                0.5

                            （计划内）

              </span></div>
              <div class="list-td"><span class="list-td-cell">1</span></div>
              <div class="list-td"><span class="list-td-cell">必修</span></div>
              <div class="list-td"><span class="list-td-cell">通识必修课</span></div>
              <div class="list-td is-pass"><span class="list-td-cell">已修读</span></div>
              <div class="list-td"><span class="list-td-cell">84</span></div>
              <div class="list-td"><span class="list-td-cell"></span></div>
              <div class="list-td"><span class="list-td-cell">否</span></div>
            </div>
            <div class="list-tr">
              <div class="list-td"><span class="list-td-cell">2026-2027-1</span></div>
              <div class="list-td"><span class="list-td-cell">030420143</span></div>
              <div class="list-td"><span class="list-td-cell">形势与政策5</span></div>
              <div class="list-td"><span class="list-td-cell">0.25</span></div>
              <div class="list-td"><span class="list-td-cell">1</span></div>
              <div class="list-td"><span class="list-td-cell">必修</span></div>
              <div class="list-td"><span class="list-td-cell">通识必修课</span></div>
              <div class="list-td is-blue"><span class="list-td-cell">修读中</span></div>
              <div class="list-td"><span class="list-td-cell">0</span></div>
              <div class="list-td"><span class="list-td-cell"></span></div>
              <div class="list-td"><span class="list-td-cell">否</span></div>
            </div>
          </div>
        </div>
        <div class="mod-item-detail box-shadow">
          <div class="header-content">
            <div class="header-content-title">
              <h5 class="header-title"><span class="header-title-text">课程体系 :</span><b id="jClass-2" class="header-title-blod jClassName">计划外非通选课</b></h5>
            </div>
          </div>
          <div class="sub-table">
            <div class="sub-table-header-tr">
              <div class="header-th"><span class="header-th-cell">修读学期</span></div>
              <div class="header-th"><span class="header-th-cell">课程编号</span></div>
              <div class="header-th"><span class="header-th-cell">课程名称</span></div>
              <div class="header-th"><span class="header-th-cell">学分</span></div>
              <div class="header-th"><span class="header-th-cell">课程类别</span></div>
              <div class="header-th"><span class="header-th-cell">课程属性</span></div>
              <div class="header-th"><span class="header-th-cell">课程性质</span></div>
              <div class="header-th"><span class="header-th-cell">修读情况</span></div>
              <div class="header-th"><span class="header-th-cell">课程成绩</span></div>
              <div class="header-th"><span class="header-th-cell">备注</span></div>
              <div class="header-th"><span class="header-th-cell">是否学位课</span></div>
            </div>
            <div class="list-tr">
              <div class="list-td"><span class="list-td-cell">2024-2025-1</span></div>
              <div class="list-td"><span class="list-td-cell">040220010</span></div>
              <div class="list-td"><span class="list-td-cell">国家学生体质健康测试1</span></div>
              <div class="list-td"><span class="list-td-cell">0</span></div>
              <div class="list-td"><span class="list-td-cell">理论课（不含实践）</span></div>
              <div class="list-td"><span class="list-td-cell">必修</span></div>
              <div class="list-td"><span class="list-td-cell">通识限选课</span></div>
              <div class="list-td is-pass"><span class="list-td-cell">已修读</span></div>
              <div class="list-td"><span class="list-td-cell">53.2</span></div>
              <div class="list-td"><span class="list-td-cell"></span></div>
              <div class="list-td"><span class="list-td-cell">否</span></div>
            </div>
          </div>
        </div>
        </body></html>
    """.trimIndent()

    @Test
    fun `课程体系页解析出方案名与两个分组`() {
        val parsed = ScholarProgressParser.parse(ScholarDimension.System, kctxHtml)
        assertEquals("2024 某专业培养方案及教学计划", parsed?.planName)
        assertEquals(2, parsed?.groups?.size)
        assertEquals(listOf("通识必修课", "计划外非通选课"), parsed?.groups?.map { it.name })
    }

    @Test
    fun `分组名不带图标字体实体`() {
        // 实测里分组名的 <b> 里套着一个 iconfont 图标（&#xe62f;），只去标签会把它留下
        val names = ScholarProgressParser.parse(ScholarDimension.System, kctxHtml)!!.groups.map { it.name }
        names.forEach { name ->
            assertFalse("分组名里不该有实体：$name", name.contains("&#"))
            assertFalse("分组名里不该有标签：$name", name.contains("<"))
        }
    }

    @Test
    fun `分组头解析结论学分进度与已修还需`() {
        val group = ScholarProgressParser.parse(ScholarDimension.System, kctxHtml)!!.groups[0]
        assertEquals(false, group.passed)
        assertEquals(58.5, group.requiredCredit ?: -1.0, 0.0001)
        assertEquals(57.5, group.earnedCredit ?: -1.0, 0.0001)
        assertEquals(0.8, group.remainingCredit ?: -1.0, 0.0001)
        assertEquals("98.3%", group.percent)
        assertNull(group.ongoingCredit)
    }

    @Test
    fun `没有结论行与进度条的分组不报错`() {
        // 实测里「计划外非通选课」这块就是只有标题和明细
        val group = ScholarProgressParser.parse(ScholarDimension.System, kctxHtml)!!.groups[1]
        assertNull(group.passed)
        assertEquals("", group.percent)
        assertNull(group.requiredCredit)
    }

    @Test
    fun `明细行按表头名取值`() {
        val courses = ScholarProgressParser.parse(ScholarDimension.System, kctxHtml)!!.courses
        assertEquals(3, courses.size)
        val first = courses[0]
        assertEquals("2025-2026-2", first.term)
        assertEquals("030401002", first.courseNo)
        assertEquals("大学生职业生涯规划（下）", first.name)
        assertEquals(0.5, first.credit, 0.0001)
        assertEquals(true, first.planned)
        assertEquals("必修", first.attribute)
        assertEquals("通识必修课", first.nature)
        assertEquals(ScholarCourseStatus.Earned.label, first.status)
        assertEquals("84", first.scoreText)
        assertEquals(false, first.degreeCourse)
        assertEquals("通识必修课", first.groupName)
    }

    @Test
    fun `计划外标注与修读中状态`() {
        val courses = ScholarProgressParser.parse(ScholarDimension.System, kctxHtml)!!.courses
        val second = courses[1]
        assertEquals(ScholarCourseStatus.Ongoing.label, second.status)
        assertNull(second.planned)  // 这一行教务没标（计划内 / 计划外）
        assertEquals(0.25, second.credit, 0.0001)
    }

    @Test
    fun `明细行的分组归属与顶层汇总表不混淆`() {
        val parsed = ScholarProgressParser.parse(ScholarDimension.System, kctxHtml)!!
        // 顶层 total-list 里的行不该被当成分组块
        assertEquals(2, parsed.groups.size)
        val third = parsed.courses[2]
        assertEquals("计划外非通选课", third.groupName)
        assertEquals(0.0, third.credit, 0.0001)
    }

    // ---- 课程性质：9 列，没有「修读学期」与「是否学位课」 ----

    private val kclbHtml = """
        <html><body>
        <div class="mod-item-detail box-shadow">
          <div class="header-content">
            <div class="header-content-title">
              <h5 class="header-title"><span class="header-title-text">课程性质 :</span><b class="header-title-blod jClassName">通识必修课</b></h5>
            </div>
          </div>
          <div class="sub-table">
            <div class="sub-table-header-tr">
              <div class="header-th"><span class="header-th-cell">课程编号</span></div>
              <div class="header-th"><span class="header-th-cell">课程名称</span></div>
              <div class="header-th"><span class="header-th-cell">学分</span></div>
              <div class="header-th"><span class="header-th-cell">课程类别</span></div>
              <div class="header-th"><span class="header-th-cell">课程属性</span></div>
              <div class="header-th"><span class="header-th-cell">课程性质</span></div>
              <div class="header-th"><span class="header-th-cell">修读情况</span></div>
              <div class="header-th"><span class="header-th-cell">总成绩</span></div>
              <div class="header-th"><span class="header-th-cell">备注</span></div>
            </div>
            <div class="list-tr">
              <div class="list-td"><span class="list-td-cell">030420102</span></div>
              <div class="list-td"><span class="list-td-cell">思想道德与法治</span></div>
              <div class="list-td"><span class="list-td-cell">3</span></div>
              <div class="list-td"><span class="list-td-cell">理论课（含实践）</span></div>
              <div class="list-td"><span class="list-td-cell">必修</span></div>
              <div class="list-td"><span class="list-td-cell">通识必修课</span></div>
              <div class="list-td is-pass"><span class="list-td-cell">已修读</span></div>
              <div class="list-td"><span class="list-td-cell">82</span></div>
              <div class="list-td"><span class="list-td-cell"></span></div>
            </div>
          </div>
        </div>
        </body></html>
    """.trimIndent()

    @Test
    fun `课程性质页没有学期列时不把学分读成状态`() {
        val parsed = ScholarProgressParser.parse(ScholarDimension.Nature, kclbHtml)!!
        val course = parsed.courses.single()
        assertEquals("", course.term)
        assertEquals("思想道德与法治", course.name)
        assertEquals(3.0, course.credit, 0.0001)
        assertEquals("必修", course.attribute)
        assertEquals("通识必修课", course.nature)
        assertEquals(ScholarCourseStatus.Earned.label, course.status)
        // 「总成绩」这个列名也要认
        assertEquals("82", course.scoreText)
        assertNull(course.degreeCourse)
    }

    // ---- 公选课类别：10 列，没有「课程属性」 ----

    private val szklbHtml = """
        <html><body>
        <div class="mod-item-detail box-shadow">
          <div class="header-content">
            <div class="header-content-title">
              <h5 class="header-title"><span class="header-title-text">公选课类别 :</span><b class="header-title-blod jClassName">工程技术</b></h5>
            </div>
          </div>
          <div class="sub-table">
            <div class="sub-table-header-tr">
              <div class="header-th"><span class="header-th-cell">修读学期</span></div>
              <div class="header-th"><span class="header-th-cell">课程编号</span></div>
              <div class="header-th"><span class="header-th-cell">课程名称</span></div>
              <div class="header-th"><span class="header-th-cell">学分</span></div>
              <div class="header-th"><span class="header-th-cell">公选课类别</span></div>
              <div class="header-th"><span class="header-th-cell">课程性质</span></div>
              <div class="header-th"><span class="header-th-cell">修读情况</span></div>
              <div class="header-th"><span class="header-th-cell">课程成绩</span></div>
              <div class="header-th"><span class="header-th-cell">备注</span></div>
              <div class="header-th"><span class="header-th-cell">是否学位课</span></div>
            </div>
            <div class="list-tr">
              <div class="list-td"><span class="list-td-cell">2025-2026-2</span></div>
              <div class="list-td"><span class="list-td-cell">0903011172</span></div>
              <div class="list-td"><span class="list-td-cell">PLC实验</span></div>
              <div class="list-td"><span class="list-td-cell">0.5</span></div>
              <div class="list-td"><span class="list-td-cell">工程技术</span></div>
              <div class="list-td"><span class="list-td-cell">通识任选课</span></div>
              <div class="list-td is-pass"><span class="list-td-cell">已修读</span></div>
              <div class="list-td"><span class="list-td-cell">84</span></div>
              <div class="list-td"><span class="list-td-cell"></span></div>
              <div class="list-td"><span class="list-td-cell"></span></div>
            </div>
          </div>
        </div>
        </body></html>
    """.trimIndent()

    @Test
    fun `公选课类别页没有课程属性列时不错位`() {
        // 这一页的列集合最容易踩坑：没有「课程属性」，第 6 列直接是「课程性质」
        val parsed = ScholarProgressParser.parse(ScholarDimension.Elective, szklbHtml)!!
        val course = parsed.courses.single()
        assertEquals("工程技术", course.category)
        assertEquals("", course.attribute)
        assertEquals("通识任选课", course.nature)
        assertEquals(ScholarCourseStatus.Earned.label, course.status)
        assertEquals("84", course.scoreText)
    }

    // ---- 兜底 ----

    @Test
    fun `未登录提示页解析成null`() {
        val notLogged = "<html><body><font>用户没有登录，请重新登录！</font></body></html>"
        assertNull(ScholarProgressParser.parse(ScholarDimension.System, notLogged))
    }

    @Test
    fun `空页面解析成null`() {
        assertNull(ScholarProgressParser.parse(ScholarDimension.System, ""))
        assertNull(ScholarProgressParser.parse(ScholarDimension.System, "<html><body></body></html>"))
    }

    @Test
    fun `分组没有粗体名字时退回标题文本`() {
        val html = """
            <html><body>
            <div class="mod-item-detail">
              <div class="header-content-title">
                <h5 class="header-title"><span class="header-title-text">其它分组 :</span></h5>
              </div>
              <div class="sub-table">
                <div class="sub-table-header-tr">
                  <div class="header-th"><span class="header-th-cell">课程名称</span></div>
                  <div class="header-th"><span class="header-th-cell">学分</span></div>
                </div>
                <div class="list-tr">
                  <div class="list-td"><span class="list-td-cell">某门课</span></div>
                  <div class="list-td"><span class="list-td-cell">1</span></div>
                </div>
              </div>
            </div>
            </body></html>
        """.trimIndent()
        val parsed = ScholarProgressParser.parse(ScholarDimension.System, html)!!
        assertEquals("其它分组", parsed.groups.single().name)
        assertEquals("其它分组", parsed.courses.single().groupName)
    }

    @Test
    fun `没有课程名的行被丢掉而不是写进库`() {
        val html = """
            <html><body>
            <div class="mod-item-detail">
              <div class="header-content-title">
                <h5 class="header-title"><span class="header-title-text">分组 :</span><b class="header-title-blod">甲</b></h5>
              </div>
              <div class="sub-table">
                <div class="sub-table-header-tr">
                  <div class="header-th"><span class="header-th-cell">课程名称</span></div>
                  <div class="header-th"><span class="header-th-cell">学分</span></div>
                </div>
                <div class="list-tr">
                  <div class="list-td"><span class="list-td-cell"></span></div>
                  <div class="list-td"><span class="list-td-cell">2</span></div>
                </div>
                <div class="list-tr">
                  <div class="list-td"><span class="list-td-cell">有名字的课</span></div>
                  <div class="list-td"><span class="list-td-cell">2</span></div>
                </div>
              </div>
            </div>
            </body></html>
        """.trimIndent()
        val parsed = ScholarProgressParser.parse(ScholarDimension.System, html)!!
        assertEquals(1, parsed.courses.size)
        assertEquals("有名字的课", parsed.courses.single().name)
    }

    @Test
    fun `表头读不出时不产出课程`() {
        // 表头整个丢失 = 页面结构变了，宁可这门课不解析，也不要按下标瞎猜
        val html = """
            <html><body>
            <div class="mod-item-detail">
              <div class="header-content-title">
                <h5 class="header-title"><span class="header-title-text">分组 :</span><b class="header-title-blod">甲</b></h5>
              </div>
              <div class="sub-table">
                <div class="list-tr">
                  <div class="list-td"><span class="list-td-cell">某门课</span></div>
                  <div class="list-td"><span class="list-td-cell">2</span></div>
                </div>
              </div>
            </div>
            </body></html>
        """.trimIndent()
        val parsed = ScholarProgressParser.parse(ScholarDimension.System, html)
        assertTrue(parsed == null || parsed.courses.isEmpty())
        assertFalse(parsed?.courses?.isNotEmpty() ?: false)
    }
}
