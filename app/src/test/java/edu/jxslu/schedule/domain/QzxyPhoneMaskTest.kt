package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/** 手机号遮蔽：账号一行默认显示这个，别把完整号码亮出来。 */
class QzxyPhoneMaskTest {

    @Test
    fun `十一位号码留前三位与后四位`() {
        assertEquals("138****8888", QzxyPhoneMask.mask("13800008888"))
        assertEquals("138****8888", QzxyPhoneMask.mask(" 13800008888 "))
    }

    @Test
    fun `短号也遮蔽而不是原样露出`() {
        assertEquals("1****8", QzxyPhoneMask.mask("12345678"))
        assertEquals("****", QzxyPhoneMask.mask("1234"))
        assertEquals("***", QzxyPhoneMask.mask("123"))
    }

    @Test
    fun `空值给占位符`() {
        assertEquals("-", QzxyPhoneMask.mask(""))
        assertEquals("-", QzxyPhoneMask.mask("   "))
    }
}
