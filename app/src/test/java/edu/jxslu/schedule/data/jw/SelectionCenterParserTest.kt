package edu.jxslu.schedule.data.jw

import edu.jxslu.schedule.domain.SelectionQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 应用内选课中心解析（DESIGN §4.36，2026-10-08 窗口期实测样本）。
 * 夹具取自真实响应（结构逐字保留；教师名做了替换，公开仓库不落真实姓名）。
 */
class SelectionCenterParserTest {

    // ---- 列表 ----

    @Test
    fun parsesLiveListRows() {
        // 实测行（「本学期计划选课」查询「机械」sfct=false）：同门课两块——一块未选、一块已选
        val payload = """
            {"aaData":[
              {"jx0404id":"202620271000834","jx02id":"1D2491B39902403591A23597FF51E405","cfbs":null,
               "kch":"080230006","kcmc":"机械制造基础A","kctype":"理论课（含实践）","xf":3,
               "skls":"教师甲","sksj":"1-10周 星期四 1-2 <br>1-10周 星期五 5-6 ","skdd":"北B106<br>南B206",
               "xqmc":"瑶湖校区","xkrs":85,"pkrs":83,"syrs":"19","skfsmc":"其它","xkzt":"0","fzmc":null,
               "ctsm":"与已选课程 “PLC原理及应用B” 冲突","kcsxmc":"必修","kcxzmc":"学科基础课"},
              {"jx0404id":"202620271000835","jx02id":"1D2491B39902403591A23597FF51E405","cfbs":null,
               "kch":"080230006","kcmc":"机械制造基础A","xf":3,"skls":"教师甲",
               "xkrs":82,"pkrs":82,"syrs":"28","xkzt":"9","ctsm":"该课堂已选"}
            ],"sEcho":"1","iTotalRecords":2,"iTotalDisplayRecords":2,
             "jx0502zbid":"2BF44EE44C5844D4A021EBB4F93F710A","jfViewStr":""}
        """.trimIndent()
        val page = SelectionCenterParser.parseList(payload)
        assertEquals(2, page.total)
        assertEquals(2, page.rows.size)

        val first = page.rows[0]
        // 主键 = jx0404id|jx02id|cfbs（cfbs 为 null 时空段）
        assertEquals("202620271000834|1D2491B39902403591A23597FF51E405|", first.id)
        assertEquals("机械制造基础A", first.name)
        assertEquals("080230006", first.courseNo)
        assertEquals("教师甲", first.teacher)
        assertEquals(3.0, first.credit!!, 0.0)
        assertEquals("必修", first.attribute)
        assertEquals("学科基础课", first.category)
        assertEquals(83, first.capacity)
        assertEquals(85, first.enrolled)
        // 余量以教务的 syrs 为准（19），不是 pkrs-xkrs（-2）
        assertEquals(19, first.remainingSeats)
        assertEquals(19, first.remaining)
        assertFalse(first.selected)
        assertTrue(first.conflictNote.contains("冲突"))
        // <br> 转 \n
        assertEquals("1-10周 星期四 1-2\n1-10周 星期五 5-6", first.timeText)
        assertEquals("北B106\n南B206", first.placeText)
        // v2 补的展示字段（对齐教务列）：行内 kctype 给的就是中文名
        assertEquals("理论课（含实践）", first.courseType)
        assertEquals("瑶湖校区", first.campus)
        assertEquals("其它", first.teachMode)
        assertEquals("", first.groupName) // fzmc 为 null

        val second = page.rows[1]
        assertTrue(second.selected)
        assertEquals("该课堂已选", second.conflictNote)
        assertEquals(28, second.remaining)
        // 缺列一律空串，不编
        assertEquals("", second.courseType)
        assertEquals("", second.campus)
    }

    @Test
    fun dropsRowsWithoutIdOrName() {
        val payload = """{"aaData":[
            {"kcmc":"没有主键","jx02id":"x"},
            {"jx0404id":"1","jx02id":"y"},
            {"jx0404id":"2","kcmc":"有效课","syrs":"5"}],"iTotalRecords":3}"""
        val page = SelectionCenterParser.parseList(payload)
        assertEquals(1, page.rows.size)
        assertEquals("有效课", page.rows[0].name)
        assertEquals(5, page.rows[0].remainingSeats)
    }

    @Test
    fun missingTotalFallsBackToRows() {
        val payload = """{"aaData":[{"jx0404id":"1","kcmc":"课"}]}"""
        assertEquals(1, SelectionCenterParser.parseList(payload).total)
    }

    // ---- 页面形态（可读错误） ----

    @Test
    fun notInWindowPageIsReadableError() {
        val page = "<html><center>当前不在选课时间范围内，具体请查看学校选课通知！</center></html>"
        val e = assertThrows(IllegalStateException::class.java) { SelectionCenterParser.parseList(page) }
        assertEquals(SelectionCenterParser.NOT_IN_WINDOW_TEXT, e.message)
    }

    @Test
    fun qz404PageIsReadableError() {
        val page = """<h5 class="page-error-tips-text">抱歉，您访问的页面不存在！</h5>"""
        val e = assertThrows(IllegalStateException::class.java) { SelectionCenterParser.parseList(page) }
        assertTrue(e.message!!.contains("404"))
    }

    @Test
    fun noOpenPageIsReadableError() {
        val e = assertThrows(IllegalStateException::class.java) {
            SelectionCenterParser.parseAction("<div>系统功能暂未开放，敬请等待</div>")
        }
        assertTrue(e.message!!.contains("暂未开放"))
    }

    @Test
    fun loginTextIsReadableError() {
        val e = assertThrows(IllegalStateException::class.java) {
            SelectionCenterParser.parseList("<html>用户没有登录</html>")
        }
        assertTrue(e.message!!.contains("会话已失效"))
    }

    // ---- 提交 / 退课应答 ----

    @Test
    fun parsesActionSuccess() {
        val outcome = SelectionCenterParser.parseAction("""{"success":true,"message":"选课成功"}""")
        assertTrue(outcome.success)
        assertFalse(outcome.conflictPending)
        assertEquals("选课成功", outcome.message)
    }

    @Test
    fun parsesActionRejected() {
        val outcome = SelectionCenterParser.parseAction("""{"success":false,"message":"人数已满"}""")
        assertFalse(outcome.success)
        assertEquals("人数已满", outcome.message)
    }

    @Test
    fun conflictPendingIsNotSuccess() {
        // 页面口径：success && message 含「还有」= 冲突未决，会再弹 cfbs 处置框——
        // App 按「未成功」报，绝不自动走冲突处置
        val outcome = SelectionCenterParser.parseAction(
            """{"success":true,"message":"该课程还有 1 门冲突课程，是否继续选课","cfbs":"1"}""",
        )
        assertTrue(outcome.conflictPending)
        assertTrue(outcome.success)
    }

    @Test
    fun emptyMessageIsReadable() {
        assertEquals("教务已受理", SelectionCenterParser.parseAction("""{"success":true}""").message)
        assertEquals("教务未给出原因", SelectionCenterParser.parseAction("""{"success":false}""").message)
    }

    @Test
    fun fatalMessages() {
        assertTrue(SelectionCenterParser.isFatalMessage("验证码错误"))
        assertTrue(SelectionCenterParser.isFatalMessage("您不在选课时间范围内"))
        assertTrue(SelectionCenterParser.isFatalMessage("选课已结束"))
        assertTrue(SelectionCenterParser.isFatalMessage("请重新登录"))
        assertFalse(SelectionCenterParser.isFatalMessage("人数已满"))
        assertFalse(SelectionCenterParser.isFatalMessage("该课堂已选"))
    }

    // ---- 验证码开关 ----

    @Test
    fun captchaFlagParsing() {
        assertFalse(
            SelectionCenterParser.captchaEnabled(
                """<input type="hidden" id="sfyzmxk" name="sfyzmxk" value="0"/>""",
            ),
        )
        assertTrue(
            SelectionCenterParser.captchaEnabled(
                """<input type="hidden" id="sfyzmxk" name="sfyzmxk" value="1"/>""",
            ),
        )
        // 属性顺序反了也要认（value 在前）
        assertTrue(SelectionCenterParser.captchaEnabled("""<input value="1" name="sfyzmxk"/>"""))
        // 抽不到按 0（页面默认）
        assertFalse(SelectionCenterParser.captchaEnabled("<html>没有开关</html>"))
    }

    // ---- 主键 / 请求体 ----

    @Test
    fun courseKeyRoundTrip() {
        val key = SelectionCenterParser.courseKey("202620271000834", "ABC123", "")
        assertEquals("202620271000834|ABC123|", key)
        assertEquals(Triple("202620271000834", "ABC123", ""), SelectionCenterParser.splitCourseKey(key))
        assertEquals(Triple("1", "2", "3"), SelectionCenterParser.splitCourseKey("1|2|3"))
    }

    @Test
    fun splitCourseKeyRejectsBadShape() {
        assertNull(SelectionCenterParser.splitCourseKey("只有一段"))
        assertNull(SelectionCenterParser.splitCourseKey("|noJx0404|"))
        assertNull(SelectionCenterParser.splitCourseKey("a|b|c|d"))
    }

    @Test
    fun listBodyCarriesLegacyDataTablesKeys() {
        // 少了 sEcho / mDataProp_i 教务会回它自家的 404 错误页（实测）——键集是契约
        val body = SelectionCenterParser.listBody(start = 100, pageSize = 50).toMap()
        assertEquals("1", body["sEcho"])
        assertEquals("14", body["iColumns"])
        assertEquals("100", body["iDisplayStart"])
        assertEquals("50", body["iDisplayLength"])
        assertEquals("kch", body["mDataProp_0"])
        assertEquals("czOper", body["mDataProp_13"])
        assertEquals("1", body["iSortingCols"])
    }

    // ---- v2 查询参数（2026-10-08 实测的浏览/筛选口径） ----

    @Test
    fun listQueryInjectsBrowseKeywordWhenBlank() {
        // 空条件不是「查全部」：教务要求 关键词/教师/星期 至少一个非空（全空回 0 行）。
        // 浏览全量 = 专用搜索串（编号全以 0 开头，实测 48/48 覆盖）。
        val query = SelectionCenterParser.listQuery(SelectionQuery()).toMap()
        assertEquals(SelectionCenterParser.BROWSE_KEYWORD, query["kch"])
        // 默认开关（App 口径）：保留冲突与已满（已选课要可见可退）、限选照教务先滤
        assertEquals("false", query["sfct"])
        assertEquals("false", query["sfym"])
        assertEquals("true", query["sfxx"])
        // 其余条件全空
        assertEquals("", query["jg0101id"])
        assertEquals("", query["skxq"])
        assertEquals("", query["kctype"])
    }

    @Test
    fun listQueryKeepsExplicitConditions() {
        val query = SelectionCenterParser.listQuery(
            SelectionQuery(
                keyword = "机械",
                teacher = "教师甲",
                weekday = 1,
                startSection = 1,
                endSection = 2,
                courseType = "01",
                hideFull = true,
                hideConflict = true,
                hideRestricted = false,
            ),
        ).toMap()
        assertEquals("机械", query["kch"])
        assertEquals("教师甲", query["jg0101id"])
        assertEquals("1", query["skxq"])
        assertEquals("1", query["skjc"])
        assertEquals("2", query["endJc"])
        assertEquals("01", query["kctype"])
        assertEquals("true", query["sfym"])
        assertEquals("true", query["sfct"])
        assertEquals("false", query["sfxx"])
    }

    @Test
    fun listQueryTeacherOnlyDoesNotInjectBrowse() {
        // 教师单独在 = 本身满足「至少一个非空」，不注入浏览串（两者都空才注入）
        val query = SelectionCenterParser.listQuery(SelectionQuery(teacher = "教师甲")).toMap()
        assertEquals("", query["kch"])
        assertEquals("教师甲", query["jg0101id"])
        // 星期单独在时注入浏览串不影响结果（实测 kch=0+星期 = 星期单查，同为 7 行）
        val weekdayOnly = SelectionCenterParser.listQuery(SelectionQuery(weekday = 3)).toMap()
        assertEquals(SelectionCenterParser.BROWSE_KEYWORD, weekdayOnly["kch"])
        assertEquals("3", weekdayOnly["skxq"])
    }
}
