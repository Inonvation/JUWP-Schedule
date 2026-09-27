package edu.jxslu.schedule.domain

import edu.jxslu.schedule.data.qzxy.QzxySession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 会话串的解析与导出。测试数据是合成的，形状照登录响应与抓包复制出来的样子写。
 */
class QzxySessionLinkTest {

    @Test
    fun `粘登录响应原文能认出会话`() {
        val json = """
            {"success":true,"errorCode":0,"data":{
              "loginCode":"abc123","v3LoginCode":"v3-xyz",
              "userId":"1001","accountId":"2002","projectId":"42","telephone":"13800000000"}}
        """.trimIndent()

        val session = QzxySessionLink.parse(json)!!
        // v3LoginCode 不能顶替 loginCode：它是另一套字段，服务端只认前者
        assertEquals("abc123", session.loginCode)
        assertEquals("1001", session.userId)
        assertEquals("2002", session.accountId)
        assertEquals("42", session.projectId)
        assertEquals("13800000000", session.telephone)
    }

    @Test
    fun `表单样式与逐行键值都能认`() {
        val form = "loginCode=abc&userId=1&accountId=2&projectId=42&telephone=13800000000"
        assertEquals("abc", QzxySessionLink.parse(form)!!.loginCode)
        assertEquals("42", QzxySessionLink.parse(form)!!.projectId)

        val lines = "loginCode: abc\nprojectId: 42\ntelephone: 13800000000"
        assertEquals("abc", QzxySessionLink.parse(lines)!!.loginCode)
        assertEquals("42", QzxySessionLink.parse(lines)!!.projectId)
        assertEquals("13800000000", QzxySessionLink.parse(lines)!!.telephone)
    }

    @Test
    fun `只认 telPhone 时也能取到手机号`() {
        val session = QzxySessionLink.parse("loginCode=a&projectId=42&telPhone=13900000000")!!
        assertEquals("13900000000", session.telephone)
    }

    @Test
    fun `缺必填字段时解析失败并指出缺哪个`() {
        assertNull(QzxySessionLink.parse("projectId=42&userId=1"))
        assertEquals("loginCode", QzxySessionLink.missingField("projectId=42"))
        assertNull(QzxySessionLink.parse("loginCode=abc"))
        assertEquals("projectId", QzxySessionLink.missingField("loginCode=abc"))
        assertNull(QzxySessionLink.parse(""))
        assertEquals("loginCode", QzxySessionLink.missingField("随便一段文本"))
    }

    @Test
    fun `导出与解析互为逆运算`() {
        val session = QzxySession(
            loginCode = "abc123",
            userId = "1001",
            accountId = "2002",
            projectId = "42",
            telephone = "13800000000",
        )
        val text = QzxySessionLink.format(session)
        assertEquals("loginCode=abc123&userId=1001&accountId=2002&projectId=42&telephone=13800000000", text)
        val parsed = QzxySessionLink.parse(text)!!
        assertEquals(session.loginCode, parsed.loginCode)
        assertEquals(session.userId, parsed.userId)
        assertEquals(session.accountId, parsed.accountId)
        assertEquals(session.projectId, parsed.projectId)
        assertEquals(session.telephone, parsed.telephone)
    }

    @Test
    fun `空字段不写进导出串`() {
        val text = QzxySessionLink.format(
            QzxySession(loginCode = "a", userId = "", accountId = "", projectId = "42", telephone = ""),
        )
        assertEquals("loginCode=a&projectId=42", text)
    }
}
