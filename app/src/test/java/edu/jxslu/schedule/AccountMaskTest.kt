package edu.jxslu.schedule

import edu.jxslu.schedule.domain.AccountMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 账号条遮罩口径（DESIGN §3.3）：长号遮中段，短号不给遮罩直接隐藏。 */
class AccountMaskTest {

    @Test
    fun `常规学号保留前后各两位`() {
        assertEquals("20****56", AccountMask.maskStudentId("202400000056"))
    }

    @Test
    fun `恰好六位也遮中段`() {
        assertEquals("12****56", AccountMask.maskStudentId("123456"))
    }

    @Test
    fun `短于六位返回null`() {
        assertNull(AccountMask.maskStudentId("12345"))
        assertNull(AccountMask.maskStudentId(""))
    }

    @Test
    fun `空白与首尾空格先修剪再遮罩`() {
        assertNull(AccountMask.maskStudentId("   "))
        assertEquals("20****56", AccountMask.maskStudentId("  202400000056 "))
    }

    @Test
    fun `null输入返回null`() {
        assertNull(AccountMask.maskStudentId(null))
    }
}
