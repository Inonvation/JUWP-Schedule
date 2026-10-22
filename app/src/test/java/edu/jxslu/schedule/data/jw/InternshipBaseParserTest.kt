package edu.jxslu.schedule.data.jw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 实习基地解析（DESIGN §4.43）：fixture 照教务 `sxjd.do` 的真实 JSON 结构
 * （`code`/`msg`/`count`/`data[]`，字段名照实录），数据虚构。
 */
class InternshipBaseParserTest {

    @Test
    fun `解析多条并丢弃缺名字的行`() {
        val json = """
        {"msg":"0","code":0,"count":2,"data":[
          {"sj0604id":"00000000000000000000000000000001","sxjdbh":"90001",
           "sxjdmc":"示例机械实习基地","dmmc":"校外基地","sxdwmc":"机械工程学院",
           "sxjdfzr":"张三","sxjddz":"某某市某路 1 号","sxjdlxdh":"0791-00000000",
           "jcsj":"2020-01-01","gpsx":"5","rnrs":300,"xnlxr":"李四",
           "xnlxrdh":"0791-00000001","dwmc":"机械工程学院","sxjdjj":"无"},
          {"sxjdbh":"90002"}
        ]}
        """.trimIndent()
        val page = InternshipBaseParser.parsePage(json)
        assertEquals(2, page.count)
        assertEquals(1, page.bases.size)
        val base = page.bases.first()
        assertEquals("示例机械实习基地", base.name)
        assertEquals("90001", base.no)
        assertEquals("校外基地", base.category)
        assertEquals("300", base.capacity) // 数字字段按原文收成字符串
        assertEquals("张三", base.leader)
    }

    @Test
    fun `code 非 0 抛错`() {
        val json = """{"msg":"未登录","code":-1,"count":0,"data":[]}"""
        val e = assertThrows(IllegalStateException::class.java) {
            InternshipBaseParser.parsePage(json)
        }
        assertTrue(e.message!!.contains("code=-1"))
    }

    @Test
    fun `空列表合法`() {
        val page = InternshipBaseParser.parsePage("""{"msg":"0","code":0,"count":0,"data":[]}""")
        assertEquals(0, page.count)
        assertTrue(page.bases.isEmpty())
    }

    @Test
    fun `null 字段按空串收`() {
        val json = """{"code":0,"count":1,"data":[{"sxjdmc":"只有名字的基地","sxjdfzr":null}]}"""
        val page = InternshipBaseParser.parsePage(json)
        assertEquals(1, page.bases.size)
        assertEquals("", page.bases.first().leader)
        assertEquals("", page.bases.first().no)
    }

    @Test
    fun `listUrl 带 pageNum 与 pageSize`() {
        val url = InternshipBaseParser.listUrl(2)
        assertTrue(url.contains("pageNum=2"))
        assertTrue(url.contains("pageSize=${InternshipBaseParser.PAGE_SIZE}"))
    }
}
