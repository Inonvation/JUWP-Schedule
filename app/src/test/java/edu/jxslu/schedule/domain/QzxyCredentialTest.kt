package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 凭证推导。短信 secret 用公开文档给的手机号-摘要对做锚点；
 * 密码取位口径以两个参考实现的**代码**为准（都取 MD5 后 10 位），
 * linyu 文档里那条 `mypassword → C854BC85B3` 的示例与它自己的代码对不上，不采信。
 */
class QzxyCredentialTest {

    @Test
    fun `短信 secret 与公开文档的样例一致`() {
        assertEquals(
            "e3d2220b920cca13499ea76328e24a4e",
            QzxyCredential.smsSecret("18582613960"),
        )
    }

    @Test
    fun `短信 secret 只跟手机号有关`() {
        assertEquals(
            QzxyCredential.smsSecret("13800138000"),
            QzxyCredential.smsSecret("13800138000"),
        )
    }

    @Test
    fun `密码取 MD5 后十位并大写`() {
        // MD5("mypassword") = 34819d7beeabb9260a5c854bc85b3e44
        assertEquals("4BC85B3E44", QzxyCredential.passwordValue("mypassword"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `非十一位手机号直接拒绝`() {
        QzxyCredential.smsSecret("1380013800")
    }
}
