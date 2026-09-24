package edu.jxslu.schedule.data.jw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 签章系统成绩单导出的解析与判定（DESIGN §4.25）。
 *
 * 样本是**合成**的：形状照 2026-09-24 实测的 `ddqzcjList` 应答（数组外壳、字段名、
 * pagePri 里带空格的 base64 形态），课程与学期为虚构值，不含任何真实个人信息。
 */
class TranscriptParsingTest {

    @Test
    fun parsesListPageShape() {
        val page = PtworkTranscript.parsePage(PAGE_JSON)
        assertEquals(2, page.total)
        assertEquals(1, page.pages)
        assertEquals(2, page.rows.size)
        // pagePri 里的空格原样保留：服务端把 base64 的 + 换成了空格，回传时不要「顺手修正」
        assertEquals("AAAABBBB CCCC=", page.pagePri)
        assertEquals("2025-2026-2", page.rows[0].term)
        assertEquals("样课甲", page.rows[0].name)
        assertEquals(2.0, page.rows[0].credit, 0.001)
        assertEquals("83", page.rows[0].scoreStr)
        // 等级制成绩：ZCJSTR 是展示口径，ZCJ 为空
        assertEquals("优", page.rows[1].scoreStr)
        assertEquals(0.5, page.rows[1].credit, 0.001)
    }

    @Test
    fun toleratesDirtyRows() {
        val body = """
            [1,{"pages":1,"pagePri":"x","list":[
              "不是对象",
              {"KCMC":"只有课名没有学期"},
              {"XNXQID":"2025-2026-1"},
              {"XNXQID":"2025-2026-1","KCMC":null,"XF":null,"ZCJ":null,"ZCJSTR":null}
            ]}]
        """.trimIndent()
        val page = PtworkTranscript.parsePage(body)
        // 只有「不是对象」那条被丢弃，其余三条保留（字段缺失一律降级为空/0，不抛异常）
        assertEquals(3, page.rows.size)
        // total 缺失时退回实际行数
        assertEquals(3, page.total)
        assertEquals("只有课名没有学期", page.rows[0].name)
        assertEquals("", page.rows[0].term)
        assertEquals(0.0, page.rows[1].credit, 0.001)
        assertEquals("", page.rows[2].scoreStr)
    }

    @Test
    fun totalFallsBackToRowCountWhenMissing() {
        val body = """
            [1,{"pages":1,"pagePri":"x","list":[{"XNXQID":"2025-2026-2","KCMC":"样课甲"}]}]
        """.trimIndent()
        assertEquals(1, PtworkTranscript.parsePage(body).total)
    }

    @Test
    fun rejectsGarbageAndUnexpectedFlag() {
        // 截断的 JSON：解析器开了宽松模式（容忍学校那边偶发的不规范 JSON），
        // 但截断的结构仍然解析不出来
        val truncated = assertThrows(TranscriptException.Protocol::class.java) {
            PtworkTranscript.parsePage("""[1,{"total":""")
        }
        assertTrue(truncated.message!!.contains("不是 JSON"))

        // 宽松模式会把 HTML 当成一个裸字符串读进来，于是落到「结构已变」这一支。
        // 会话失效的登录页不靠这里判定（TranscriptClient 在解析前先看页面内容）
        val html = assertThrows(TranscriptException.Protocol::class.java) {
            PtworkTranscript.parsePage("<html>系统维护中</html>")
        }
        assertTrue(html.message!!.isNotBlank())

        val badFlag = assertThrows(TranscriptException.Protocol::class.java) {
            PtworkTranscript.parsePage("""[0,{"total":0,"list":[]}]""")
        }
        assertTrue(badFlag.message!!.contains("code=0"))

        assertThrows(TranscriptException.Protocol::class.java) {
            PtworkTranscript.parsePage("""[1]""")
        }
    }

    @Test
    fun groupsTermsWithCountsNewestFirst() {
        val rows = listOf(
            row("2024-2025-1"), row("2025-2026-2"), row("2024-2025-1"),
            row("2025-2026-1"), row(""), row("2024-2025-1"),
        )
        val terms = PtworkTranscript.termsFrom(rows)
        assertEquals(listOf("2025-2026-2", "2025-2026-1", "2024-2025-1"), terms.map { it.term })
        assertEquals(listOf(1, 1, 3), terms.map { it.courseCount })
    }

    @Test
    fun pagesFollowServerPageSize() {
        // 服务端固定 15 行一页，limit 参数实测被忽略
        assertEquals(1, PtworkTranscript.pagesOf(0))
        assertEquals(1, PtworkTranscript.pagesOf(1))
        assertEquals(1, PtworkTranscript.pagesOf(15))
        assertEquals(2, PtworkTranscript.pagesOf(16))
        assertEquals(5, PtworkTranscript.pagesOf(66))
    }

    @Test
    fun detectsPdfByMagic() {
        assertTrue(PtworkTranscript.isPdf("%PDF-1.4\n…".toByteArray()))
        assertFalse(PtworkTranscript.isPdf("<script>x</script>".toByteArray()))
        assertFalse(PtworkTranscript.isPdf(byteArrayOf('%'.code.toByte())))
        assertFalse(PtworkTranscript.isPdf(ByteArray(0)))
    }

    @Test
    fun classifiesFailureBodies() {
        val noContent = PtworkTranscript.classifyNonPdf(
            "<script language='javascript'>parent.wzalert('未发现打印内容')</script>\n",
        )
        assertTrue(noContent is TranscriptException.NoContent)
        assertTrue(noContent.message!!.contains("没有成绩"))

        val login = PtworkTranscript.classifyNonPdf(LOGIN_PAGE_HTML)
        assertTrue(login is TranscriptException.SessionExpired)

        val other = PtworkTranscript.classifyNonPdf("<html><body>系统维护中</body></html>")
        assertTrue(other is TranscriptException.Protocol)

        assertTrue(PtworkTranscript.classifyNonPdf("") is TranscriptException.Protocol)
    }

    @Test
    fun readsAlertMessageAndLoginMarkers() {
        assertEquals(
            "未发现打印内容",
            PtworkTranscript.alertMessage("parent.wzalert('未发现打印内容')"),
        )
        assertNull(PtworkTranscript.alertMessage("<html>没有脚本</html>"))
        // 登录页判定用两个独有标记，任一命中即可（跟随后 URL 会丢，不能靠 URL）
        assertTrue(PtworkTranscript.looksLikeLoginPage("""<input name="dsing" id="dsing"/>"""))
        assertTrue(PtworkTranscript.looksLikeLoginPage("立即登录"))
        assertFalse(PtworkTranscript.looksLikeLoginPage("<html>成绩表</html>"))
    }

    @Test
    fun formsCarryExpectedFields() {
        val single = PtworkTranscript.listForm(listOf("2025-2026-2"), page = 2)
        assertEquals("2", single.first { it.first == "page" }.second)
        assertEquals("15", single.first { it.first == "limit" }.second)
        assertEquals("1", single.first { it.first == "cjfs" }.second)
        assertEquals(listOf("2025-2026-2"), single.filter { it.first == "xnxq" }.map { it.second })

        val multi = PtworkTranscript.listForm(listOf("2025-2026-2", "2024-2025-2"), page = 1)
        assertEquals(
            listOf("2025-2026-2", "2024-2025-2"),
            multi.filter { it.first == "xnxq" }.map { it.second },
        )

        // 空清单 = 全部学期，仍要带一个空的 xnxq（服务端按空串处理）
        val all = PtworkTranscript.listForm(emptyList(), page = 1)
        assertEquals(listOf(""), all.filter { it.first == "xnxq" }.map { it.second })

        // 导出表单不带 xnxq：条件全在 dysj 里，实测 xnxq 被忽略
        val print = PtworkTranscript.printForm("TOKEN")
        assertEquals(listOf("dysj", "dytype", "cjfs"), print.map { it.first })
        assertEquals("TOKEN", print.first().second)
    }

    @Test
    fun authorizedLandingIsSealingHostOnly() {
        assertTrue(PtworkTranscript.isAuthorizedLanding("http://jwxyxx.juwp.edu.cn/ptwork/mainIndex?isDd=1"))
        assertFalse(PtworkTranscript.isAuthorizedLanding("http://jwxyxx.juwp.edu.cn/ptwork/LoginPage"))
        assertFalse(PtworkTranscript.isAuthorizedLanding("https://eapp2.juwp.edu.cn:9443/cas/login?service=x"))
        assertFalse(PtworkTranscript.isAuthorizedLanding(null))
    }

    @Test
    fun buildsReadableFileName() {
        // 2026-09-24 10:30（东八区）→ 固定时间戳，避免依赖运行机器的时区
        val zone = java.time.ZoneId.of("Asia/Shanghai")
        val millis = java.time.ZonedDateTime.of(2026, 9, 24, 10, 30, 0, 0, zone)
            .toInstant().toEpochMilli()
        assertEquals(
            "成绩单-2025-2026-2-20260924-1030.pdf",
            PtworkTranscript.defaultFileName(listOf("2025-2026-2"), millis, zone),
        )
        assertEquals(
            "成绩单-2025-2026-2等3个学期-20260924-1030.pdf",
            PtworkTranscript.defaultFileName(
                listOf("2025-2026-2", "2025-2026-1", "2024-2025-2"),
                millis,
                zone,
            ),
        )
        assertEquals(
            "成绩单-全部学期-20260924-1030.pdf",
            PtworkTranscript.defaultFileName(emptyList(), millis, zone),
        )
        assertEquals("2025-2026-2", PtworkTranscript.sanitizeFileLabel("2025-2026-2"))
        assertEquals("a_b_c", PtworkTranscript.sanitizeFileLabel("a/b:c"))
    }

    private fun row(term: String) =
        TranscriptCourse(term = term, name = "样课", credit = 1.0, scoreStr = "80")

    private companion object {
        val PAGE_JSON = """
            [1,{"endRow":2,"hasNextPage":false,"isLastPage":true,"list":[
              {"XNXQID":"2025-2026-2","KCH":"000000001","KCMC":"样课甲","KSDW":"测试学院",
               "XF":2,"ZXS":32,"KSFS":"考试","KCSX":"必修","ZCJ":83,"ZCJSTR":"83","JD":null,
               "KSXZ":"正常考试","ROW_ID":1},
              {"XNXQID":"2024-2025-1","KCH":"000000002","KCMC":"样课乙","XF":0.5,
               "ZCJSTR":"优","ZCJ":null,"ROW_ID":2}
            ],"pageNum":1,"pagePri":"AAAABBBB CCCC=","pageSize":15,"pages":1,"total":2}]
        """.trimIndent()

        /** 签章系统自己的登录页；`sid` 失效时列表接口会落到这里。 */
        val LOGIN_PAGE_HTML = """
            <html><head><title>登录</title></head><body>
            <form action="/ptwork/mainIndex" id="form1" name="form1" method="post">
              <input type="hidden" name="dsing" id="dsing"/>
              <input type="password" name="password" id="password"/>
              <button type="button" onclick="LoginJs(this);">立即登录</button>
            </form></body></html>
        """.trimIndent()
    }
}
