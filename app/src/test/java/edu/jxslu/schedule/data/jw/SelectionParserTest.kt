package edu.jxslu.schedule.data.jw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 选课抓取解析（DESIGN §4.35）。样例字段取自 2026-09-30 真实接口返回（教师名做了替换）。 */
class SelectionParserTest {

    @Test
    fun parsesRealPayloadShape() {
        val payload = """
            {"msg":"0","code":0,"count":2,"data":[
              {"kc_mc":"机电液综合课程设计","kch":"080327067","xm":"教师甲","zxs":2,"xf":2,
               "kclb_mc":"必修","kcxz_mc":"集中实践教学环节",
               "ktmc":"24机械设计制造及其自动化[01-04]班","yx_mc":"机械工程学院",
               "jx02id":"76C9AEB52FC447A0A5FEC189D82BDC95","jx0501id":"C9DE760DFE9F4BE1907F047B3D41F912"},
              {"kc_mc":"人机交互技术","kch":"080230015","xm":"教师乙","zxs":24,"xf":1.5,
               "kclb_mc":"任选","kcxz_mc":"专业任选课",
               "ktmc":"24机械设计制造及其自动化[03-04]班","yx_mc":"机械工程学院",
               "sksj":"星期四 0708节<br>星期五 0708节","skdd":"南B106<br>南B106",
               "jx02id":"13B92F63313E44438C6BE67CD358F201","jx0501id":"5DFBCD5024584BF89EBE78196495919E"}
            ]}
        """.trimIndent()
        val page = SelectionParser.parseFetchJson(payload)
        assertEquals(2, page.count)
        assertEquals(2, page.rows.size)

        val first = page.rows[0]
        assertEquals("机电液综合课程设计", first.name)
        assertEquals("080327067", first.courseNo)
        assertEquals("教师甲", first.teacher)
        assertEquals(2.0, first.credit, 0.0)
        assertEquals("必修", first.attribute)
        assertEquals("集中实践教学环节", first.category)
        // 无 sksj/skdd 的行：空串而不是 null，展示侧不用再判空
        assertEquals("", first.timeText)
        assertEquals("", first.placeText)

        // <br> 转 \n：多行时间/地点在列表里分行展示
        val second = page.rows[1]
        assertEquals("星期四 0708节\n星期五 0708节", second.timeText)
        assertEquals("南B106\n南B106", second.placeText)
        assertEquals(1.5, second.credit, 1e-9)
    }

    @Test
    fun dropsRowsWithoutCourseName() {
        val payload = """{"msg":"0","code":0,"count":2,"data":[
            {"kc_mc":"","kch":"1","xf":1},
            {"kc_mc":"有效课程","kch":"2","xf":1}]}"""
        val page = SelectionParser.parseFetchJson(payload)
        assertEquals(2, page.count)
        assertEquals(1, page.rows.size)
        assertEquals("有效课程", page.rows[0].name)
    }

    @Test
    fun loginPageIsReadableError() {
        val e = assertThrows(IllegalStateException::class.java) {
            SelectionParser.parseFetchJson("<html>用户没有登录</html>")
        }
        assertTrue(e.message!!.contains("会话已失效"))
    }

    @Test
    fun noOpenPageIsReadableError() {
        val e = assertThrows(IllegalStateException::class.java) {
            SelectionParser.parseFetchJson("<html>系统功能暂未开放，敬请等待</html>")
        }
        assertTrue(e.message!!.contains("暂未开放"))
    }

    @Test
    fun errorPrefixIsReadableError() {
        val e = assertThrows(IllegalStateException::class.java) {
            SelectionParser.parseFetchJson("ERR:net::ERR_CONNECTION_TIMED_OUT")
        }
        assertTrue(e.message!!.contains("请求失败"))
    }

    @Test
    fun nonZeroCodeIsReadableError() {
        val e = assertThrows(IllegalStateException::class.java) {
            SelectionParser.parseFetchJson("""{"code":500,"msg":"未知异常","count":0,"data":[]}""")
        }
        assertTrue(e.message!!.contains("code=500"))
    }

    @Test
    fun listUrlRejectsDirtyTerm() {
        assertNull(SelectionParser.listUrl("2026-2027-1'", 1))
        assertNull(SelectionParser.listUrl("", 1))
        val url = SelectionParser.listUrl("2026-2027-1", 2)!!
        assertTrue(url.contains("xnxqid=2026-2027-1"))
        assertTrue(url.contains("pageNum=2"))
        assertTrue(url.contains("lx=xkrz"))
        assertTrue(url.contains("type=list"))
    }

    @Test
    fun parsesRoundsPayload() {
        // 字段名来自页面表格定义（非选课期无样本）：xqmc / xklc_mc / xksj / jx0502zbid / yxzt
        // code 是**字符串**形态（实测 `{"msg":"","code":"0",...}`）——与结果接口的数字形态都要认
        val payload = """{"msg":"","code":"0","count":2,"data":[
            {"xqmc":"2026-2027-1","xklc_mc":"第一轮选课","xksj":"2026-09-28 08:00 ~ 2026-10-05 17:00",
             "jx0502zbid":"A1","sfxkxm":"0","yxzt":"1"},
            {"xqmc":"2026-2027-1","xklc_mc":"第二轮选课","xksj":"2026-10-08 08:00 ~ 2026-10-10 17:00",
             "jx0502zbid":"A2","sfxkxm":"0","yxzt":"0"}]}"""
        val page = SelectionParser.parseRounds(payload)
        assertEquals(2, page.rounds.size)
        val first = page.rounds[0]
        assertEquals("A1", first.id)
        assertEquals("第一轮选课", first.name)
        assertEquals("2026-2027-1", first.term)
        assertTrue(first.canPreview)
        val (start, end) = first.startAt!! to first.endAt!!
        assertTrue(end > start)
        assertEquals(false, page.rounds[1].canPreview)
    }

    @Test
    fun dropsRoundsWithoutIdOrName() {
        val payload = """{"msg":"","code":0,"count":2,"data":[
            {"xklc_mc":"没有 id","xksj":""},
            {"xqmc":"2026-2027-1","jx0502zbid":"B1","xksj":""}]}"""
        assertEquals(0, SelectionParser.parseRounds(payload).rounds.size)
    }

    @Test
    fun shellTermReadsSelectedOption() {
        val html = """
            <select id="xnxqid" name="xnxqid" lay-verify="required" >
                <option value="">--请选择--</option>
                <option value="2026-2027-2" >2026-2027-2</option>
                <option value="2026-2027-1" selected>2026-2027-1</option>
                <option value="2025-2026-2" >2025-2026-2</option>
            </select>
        """.trimIndent()
        assertEquals("2026-2027-1", SelectionParser.shellTerm(html))
        assertEquals(
            listOf("2026-2027-2", "2026-2027-1", "2025-2026-2"),
            SelectionParser.shellTerms(html),
        )
    }

    @Test
    fun shellTermWithoutSelectReturnsNull() {
        assertNull(SelectionParser.shellTerm("<html>没有下拉</html>"))
        assertTrue(SelectionParser.shellTerms("<html>没有下拉</html>").isEmpty())
    }
}
