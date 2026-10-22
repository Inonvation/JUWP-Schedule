package edu.jxslu.schedule.data.jw

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class JwLayuiJsonTest {

    @Test
    fun jsonNullIsNotTheStringNull() {
        val row = Json.parseToJsonElement("""{"xnxqid":null,"kc_mc":"高数"}""").jsonObject
        assertNull(row.strOrNull("xnxqid"))
        assertEquals("高数", row.strOrNull("kc_mc"))
    }

    @Test
    fun dataNullBecomesEmptyRows() {
        val root = JwLayuiJson.parseRoot("""{"code":0,"count":3,"data":null}""", "成绩查询")
        assertEquals(3, root.count)
        assertTrue(root.rows.isEmpty())
    }

    @Test
    fun countMissingUsesCallerDefault() {
        val root = JwLayuiJson.parseRoot("""{"code":0,"data":[]}""", "实习基地查询", countDefault = -1)
        assertEquals(-1, root.count)
    }

    @Test
    fun stringCodeIsAccepted() {
        val root = JwLayuiJson.parseRoot("""{"code":"0","count":1,"data":[{"a":1}]}""", "选课结果")
        assertEquals(1, root.count)
        assertEquals(1, root.rows.size)
    }

    @Test
    fun noOpenPageIsReadableError() {
        val e = assertThrows(IllegalStateException::class.java) {
            JwLayuiJson.parseRoot("<html>系统功能暂未开放</html>", "成绩查询")
        }
        assertTrue(e.message!!.contains("暂未开放"))
    }

    @Test
    fun loginPageIsReadableError() {
        val e = assertThrows(IllegalStateException::class.java) {
            JwLayuiJson.parseRoot("<html>用户没有登录</html>", "成绩查询")
        }
        assertTrue(e.message!!.contains("用户没有登录"))
    }

    @Test
    fun nonZeroCodeIsReadableError() {
        val e = assertThrows(IllegalStateException::class.java) {
            JwLayuiJson.parseRoot("""{"code":-1,"msg":"error"}""", "成绩查询")
        }
        assertTrue(e.message!!.contains("code=-1"))
    }

    @Test
    fun errorPrefixIsReadableError() {
        val e = assertThrows(IllegalStateException::class.java) {
            JwLayuiJson.parseRoot("ERR:net::ERR_CONNECTION_TIMED_OUT", "成绩查询")
        }
        assertTrue(e.message!!.contains("请求失败"))
    }

    @Test
    fun nonJsonIsReadableError() {
        val e = assertThrows(IllegalStateException::class.java) {
            JwLayuiJson.parseRoot("<html>登录页</html>", "成绩查询")
        }
        assertTrue(e.message!!.contains("不是有效 JSON"))
    }
}
