package edu.jxslu.schedule.data.jw

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * 导出流程的编排逻辑（DESIGN §4.25）。
 *
 * 传输层是假的，所以这里能跑完「列表 → 令牌 → 出单」全过程，包括三条必须拦住的失败路径：
 * 无成绩学期、令牌为空、会话失效。**不联网**。
 */
class TranscriptClientTest {

    @Test
    fun fetchTermsWalksEveryPage() = runBlocking {
        val transport = FakeTransport { _, form ->
            when (form.first { it.first == "page" }.second) {
                "1" -> pageResponse(term = "2025-2026-2", rows = 15, total = 20, pages = 2)
                "2" -> pageResponse(term = "2024-2025-1", rows = 5, total = 20, pages = 2)
                else -> throw AssertionError("不该请求第 3 页")
            }
        }
        val terms = TranscriptClient(transport).fetchTerms(cookie = "sid=x")
        assertEquals(2, transport.calls.size)
        assertEquals(listOf("2025-2026-2", "2024-2025-1"), terms.map { it.term })
        assertEquals(listOf(15, 5), terms.map { it.courseCount })
        // 拉学期清单时按「全部学期」查，且带上会话 Cookie
        assertTrue(transport.calls.all { it.second.any { p -> p.first == "xnxq" && p.second == "" } })
        assertEquals(listOf("sid=x", "sid=x"), transport.cookies)
    }

    @Test
    fun fetchTermsRespectsPageCap() = runBlocking {
        // 脏数据自保：服务端报了 99 页但每页都重复同一批行，也不能无限翻下去
        val transport = FakeTransport { _, _ ->
            pageResponse(term = "2025-2026-2", rows = 15, total = 999, pages = 99)
        }
        val terms = TranscriptClient(transport).fetchTerms(cookie = null)
        assertEquals(PtworkTranscript.MAX_PAGES, transport.calls.size)
        assertEquals(1, terms.size)
    }

    @Test
    fun exportSendsPagePriAndNeverTerms() = runBlocking {
        val transport = FakeTransport { url, form ->
            when {
                url.endsWith(PtworkTranscript.LIST_PATH) ->
                    TranscriptResponse(200, LIST_WITH_TOKEN.toByteArray())
                url.endsWith(PtworkTranscript.PRINT_PATH) -> {
                    assertEquals("TOKEN-1", form.first { it.first == "dysj" }.second)
                    assertEquals("1", form.first { it.first == "dytype" }.second)
                    assertTrue("导出请求不该带 xnxq", form.none { it.first == "xnxq" })
                    TranscriptResponse(200, PDF_BYTES)
                }
                else -> throw AssertionError("意外的地址 $url")
            }
        }
        val bytes = TranscriptClient(transport).export(listOf("2025-2026-2"), cookie = "sid=x")
        assertEquals(2, transport.calls.size)
        assertEquals(PtworkTranscript.PRINT_PATH, transport.calls[1].first.substringAfter("jwxyxx.juwp.edu.cn"))
        assertTrue(PtworkTranscript.isPdf(bytes))
    }

    @Test
    fun exportRefusesEmptyTerm() = runBlocking {
        // 无成绩的学期服务端照样给 pagePri，直接导会失败；必须在本地用 total 拦住
        val transport = FakeTransport { url, _ ->
            if (url.endsWith(PtworkTranscript.LIST_PATH)) {
                TranscriptResponse(200, emptyPage(hasToken = true).toByteArray())
            } else {
                throw AssertionError("total=0 时就不该去请求出单接口")
            }
        }
        val e = assertThrowsBlocking { TranscriptClient(transport).export(listOf("2029-2030-1"), null) }
        assertTrue(e is TranscriptException.NoContent)
        assertTrue(e.message!!.contains("没有成绩"))
    }

    @Test
    fun exportRefusesBlankToken() = runBlocking {
        // total>0 但令牌为空：服务端数据不一致，不能拿着空令牌去出单
        val transport = FakeTransport { _, _ ->
            TranscriptResponse(
                200,
                (
                    """[1,{"total":1,"pages":1,"pagePri":"","list":[""" +
                        """{"XNXQID":"2025-2026-2","KCMC":"样课甲"}]}]"""
                    ).toByteArray(),
            )
        }
        val e = assertThrowsBlocking { TranscriptClient(transport).export(listOf("2025-2026-2"), null) }
        assertTrue(e is TranscriptException.Protocol)
    }

    @Test
    fun exportClassifiesNonPdfAnswers() = runBlocking {
        // 服务端说「未发现打印内容」
        val alert = fakeNoPdf("<script>parent.wzalert('未发现打印内容')</script>")
        assertTrue(alert is TranscriptException.NoContent)

        // 空白模板 PDF 之外的另一种坏结局：会话失效落到登录页
        val login = fakeNoPdf("""<input name="dsing"/><button>立即登录</button>""")
        assertTrue(login is TranscriptException.SessionExpired)

        val other = fakeNoPdf("{}")
        assertTrue(other is TranscriptException.Protocol)
    }

    @Test
    fun listLoginPageMeansSessionExpired() = runBlocking {
        val transport = FakeTransport { _, _ ->
            TranscriptResponse(200, """<html><input name="dsing"/>立即登录</html>""".toByteArray())
        }
        val e = assertThrowsBlocking { TranscriptClient(transport).fetchTerms("sid=stale") }
        assertTrue(e is TranscriptException.SessionExpired)
    }

    @Test
    fun ioErrorsBecomeNetworkFailures() = runBlocking {
        val transport = FakeTransport { _, _ -> throw IOException("connect timed out") }
        val e = assertThrowsBlocking { TranscriptClient(transport).fetchTerms(null) }
        assertTrue(e is TranscriptException.Network)
        assertEquals("connect timed out", e.message)
    }

    private suspend fun fakeNoPdf(body: String): Throwable {
        val transport = FakeTransport { url, _ ->
            if (url.endsWith(PtworkTranscript.LIST_PATH)) {
                TranscriptResponse(200, LIST_WITH_TOKEN.toByteArray())
            } else {
                TranscriptResponse(200, body.toByteArray())
            }
        }
        return assertThrowsBlocking { TranscriptClient(transport).export(listOf("2025-2026-2"), null) }
    }

    /** 断言挂起函数抛出的异常（JUnit 的 assertThrows 只吃同步 lambda）。 */
    private suspend fun assertThrowsBlocking(block: suspend () -> Unit): Throwable {
        var thrown: Throwable? = null
        try {
            block()
        } catch (e: Throwable) {
            thrown = e
        }
        // 没抛异常才在这里报错：写在 try 里会被自己的 catch 吞掉，报出的是误导性的断言消息
        return thrown ?: throw AssertionError("期望抛异常，实际正常返回")
    }

    private class FakeTransport(
        private val handler: (String, List<Pair<String, String>>) -> TranscriptResponse,
    ) : TranscriptTransport {

        val calls = mutableListOf<Pair<String, List<Pair<String, String>>>>()
        val cookies = mutableListOf<String?>()

        override suspend fun post(
            url: String,
            form: List<Pair<String, String>>,
            cookie: String?,
        ): TranscriptResponse {
            calls += url to form
            cookies += cookie
            return handler(url, form)
        }
    }

    private companion object {
        val PDF_BYTES = "%PDF-1.4\n% 合成样本，不是真成绩单\n".toByteArray()

        val LIST_WITH_TOKEN =
            """[1,{"total":1,"pages":1,"pagePri":"TOKEN-1","list":[""" +
                """{"XNXQID":"2025-2026-2","KCMC":"样课甲","XF":2,"ZCJSTR":"83"}]}]"""

        /** 造一页：一页里的行同属 [term]，方便断言「按学期归并 + 计数」。 */
        fun pageResponse(term: String, rows: Int, total: Int, pages: Int): TranscriptResponse {
            val body = buildString {
                append("""[1,{"total":$total,"pages":$pages,"pagePri":"TOKEN-1","list":[""")
                repeat(rows) { i ->
                    if (i > 0) append(',')
                    append("""{"XNXQID":"$term","KCMC":"样课$i","XF":1,"ZCJSTR":"80"}""")
                }
                append("]}]")
            }
            return TranscriptResponse(200, body.toByteArray())
        }

        fun emptyPage(hasToken: Boolean): String =
            """[1,{"total":0,"pages":1,"pagePri":"${if (hasToken) "TOKEN-1" else ""}","list":[]}]"""
    }
}
