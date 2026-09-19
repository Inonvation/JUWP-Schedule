package edu.jxslu.schedule.data.jw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 成绩抓取解析（注入 fetch 的 JSON → ScoreRecord）。样例取自 2026-09-19 真实接口返回。 */
class ScoreParserTest {

    @Test
    fun parsesRealPayloadShape() {
        val payload = """
            {"msg":"","code":0,"count":2,"data":[
              {"cj0708id":"A","xnxqid":"2025-2026-2","kch":"030401002",
               "kc_mc":"大学生职业生涯规划与创新创业基础（下）","ksdw":"工商管理学院",
               "xqmc":"2025-2026-2","xf":0.5,"zxs":8,"ksfs":"考查","kcsx":"必修",
               "xqstr":"2025-2026-2","zcj":84,"zcjstr":"84","kz":0,
               "kcxzmc":"通识必修课","xs0101id":"S","jx0404id":"J","jd":3.3,
               "ksxz":"正常考试","rownum_":1},
              {"cj0708id":"B","xnxqid":"2025-2026-1","kch":"020101001",
               "kc_mc":"大学体育3","ksdw":"体育学院","xqmc":"2025-2026-1",
               "xf":1,"zxs":16,"ksfs":"考查","kcsx":"必修","xqstr":"2025-2026-1",
               "kz":1,"kcxzmc":"通识必修课","jd":null,"ksxz":"正常考试","rownum_":2}
            ]}
        """.trimIndent()
        val page = ScoreParser.parseFetchJson(payload)
        assertEquals(2, page.count)
        assertEquals(2, page.records.size)

        val first = page.records[0]
        assertEquals("2025-2026-2", first.term)
        assertEquals("大学生职业生涯规划与创新创业基础（下）", first.name)
        assertEquals(0.5, first.credit, 1e-9)
        assertEquals(84.0, first.score!!, 1e-9)
        assertEquals("84", first.scoreStr)
        assertEquals(3.3, first.gradePoint!!, 1e-9)
        assertEquals(false, first.pendingReview)
        assertEquals("正常考试", first.status)

        // kz=1：评教未完成，成绩被锁定（这里 zcj 缺失 + jd=null 是锁定的真实形态）
        val locked = page.records[1]
        assertEquals(true, locked.pendingReview)
        assertNull(locked.score)
        assertNull(locked.gradePoint)
    }

    @Test
    fun gradedScoreKeepsStringOnly() {
        // 等级制：zcj 缺失、zcjstr 是等级文本——数值口径必须落成 null
        val payload = """
            {"code":0,"count":1,"data":[
              {"xnxqid":"2024-2025-2","kch":"1","kc_mc":"劳动教育","zcjstr":"优",
               "kz":0,"xf":1,"jd":4}
            ]}
        """.trimIndent()
        val record = ScoreParser.parseFetchJson(payload).records.single()
        assertEquals("优", record.scoreStr)
        assertNull(record.score)
        assertEquals(4.0, record.gradePoint!!, 1e-9)
    }

    @Test
    fun noOpenPageIsReadableError() {
        val e = assertThrows(IllegalStateException::class.java) {
            ScoreParser.parseFetchJson("""<html>系统功能暂未开放</html>""")
        }
        assert(e.message!!.contains("暂未开放"))
    }

    @Test
    fun nonZeroCodeIsReadableError() {
        assertThrows(IllegalStateException::class.java) {
            ScoreParser.parseFetchJson("""{"code":-1,"msg":"error"}""")
        }
    }

    @Test
    fun invalidJsonIsReadableError() {
        assertThrows(IllegalStateException::class.java) {
            ScoreParser.parseFetchJson("not json")
        }
    }

    @Test
    fun fetchJsUsesEmptyTermForAllSemesters() {
        val js = ScoreParser.fetchJs(term = "", page = 2)
        assertTrue(js.contains("kksj=' + encodeURIComponent('')"))
        assertTrue(js.contains("pageNum=2"))
        assert(!js.contains("__TERM__"))
        assert(!js.contains("__PAGE__"))
    }
}
