package edu.jxslu.schedule.data.jw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 教材接口解析（DESIGN §4.31）。fixture 依 2026-09-28 真实接口形态构造，
 * 字段值全部为编造的示例（课程名与书名不对应任何真实学生数据）。
 */
class TextbookParserTest {

    @Test
    fun listUrlRejectsDirtyTerm() {
        assertNull("学期必须过白名单（会拼进 URL），脏值拒绝", TextbookParser.listUrl("1' or '1'='1", 1))
        assertNull(TextbookParser.listUrl("", 1))
        assertEquals(
            "${JwUrls.XSD_BASE}/jsxsd/nxsjc/xsjcqr?xnxqid=2026-2027-1&pageNum=1&pageSize=200",
            TextbookParser.listUrl("2026-2027-1", 1),
        )
    }

    @Test
    fun parseFetchJsonMapsRowFields() {
        val page = TextbookParser.parseFetchJson(textbookJson(), term = "2026-2027-1")
        assertEquals(1, page.count)
        assertEquals(1, page.books.size)
        val book = page.books[0]
        assertEquals("示例课程A", book.courseName)
        assertEquals("示例教材名", book.title)
        assertEquals("张三 李四", book.author)
        assertEquals("示例出版社", book.press)
        assertEquals("第一版", book.edition)
        assertEquals("9780000000001", book.isbn)
        assertEquals("59.00", book.price)
        assertEquals("学期填进领域模型", "2026-2027-1", book.term)
    }

    @Test
    fun rowsWithoutCourseOrTitleAreDropped() {
        val page = TextbookParser.parseFetchJson(
            """{"code":0,"count":3,"data":[""" +
                textbookRow(course = "示例课程A", title = "示例教材名") + "," +
                textbookRow(course = "示例课程B", title = "") + "," +
                textbookRow(course = "", title = "孤立的教材名") + """]}""",
            term = "2026-2027-1",
        )
        assertEquals("教务没定教材名/课程名的行不产出", 1, page.books.size)
    }

    @Test
    fun errorResponsesAreReportedNotSwallowed() {
        val cases = listOf(
            "ERR:timeout",
            "<html>系统功能暂未开放</html>",
            """{"code":500,"msg":"未知异常"}""",
            "<html>不是 JSON</html>",
        )
        for (body in cases) {
            try {
                TextbookParser.parseFetchJson(body, term = "2026-2027-1")
                throw AssertionError("应当抛出：$body")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!.isNotBlank())
            }
        }
    }

    // ---- fixture ---------------------------------------------------------------

    private fun textbookRow(
        course: String,
        title: String,
        author: String = "张三 李四",
        press: String = "示例出版社",
        edition: String = "第一版",
        isbn: String = "9780000000001",
        price: String = "59.00",
    ) = """{"kcmc":"$course","jcmc":"$title","jczz":"$author","cbsmc":"$press",""" +
        """"jcbc":"$edition","isbn":"$isbn","jcdj":"$price","xnxq01id":"2026-2027-1"}"""

    private fun textbookJson() =
        """{"code":0,"count":1,"data":[${textbookRow(course = "示例课程A", title = "示例教材名")}]}"""
}
