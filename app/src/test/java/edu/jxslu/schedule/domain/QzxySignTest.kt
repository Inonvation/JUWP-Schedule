package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 签名。
 *
 * 算法不是猜的，是从官方 `libklcxkjencry.so` 反汇编出来的：
 * `md5(md5(字段拼接 + &key=登录码) + 固定后缀)`。这里的固定向量用来锁住实现，
 * 摘要值本身是外部算出来的，改了算法这一组立刻红。
 */
class QzxySignTest {

    private val fields = mapOf(
        "telephone" to "18613960123",
        "deviceId" to "268",
        "xfModel" to "0",
        "randomNumber" to "abcd",
    )
    private val loginCode = "loginCodeXYZ"

    @Test
    fun `固定输入给出固定摘要`() {
        val sign = QzxySign.sign(fields, loginCode)
        assertEquals("ace6933afd653fdb3b1498daa5c7ba35", sign)
        assertEquals(32, sign.length)
        assertEquals(sign.lowercase(), sign)
    }

    @Test
    fun `字段顺序不影响结果`() {
        val reversed = fields.entries.reversed().associate { it.key to it.value }
        assertEquals(QzxySign.sign(fields, loginCode), QzxySign.sign(reversed, loginCode))
    }

    @Test
    fun `换会话值会换签名`() {
        assertNotEquals(
            QzxySign.sign(fields, loginCode),
            QzxySign.sign(fields, "anotherLoginCode"),
        )
    }

    @Test
    fun `固定后缀自带 &key= 前缀`() {
        assertTrue(QzxySign.SIGN_SUFFIX.startsWith("&key="))
        // 两次哈希之间的拼接只加一个 &key=，别再多补
        assertEquals(
            QzxySign.md5(
                QzxySign.md5("deviceId268randomNumberabcdtelephone18613960123xfModel0&key=loginCodeXYZ") +
                    QzxySign.SIGN_SUFFIX,
            ),
            QzxySign.sign(fields, loginCode),
        )
    }

    @Test
    fun `试签名的候选互不重复且格式统一`() {
        val candidates = QzxySign.candidates(fields, loginCode)
        assertTrue("候选太少，试错覆盖面不够", candidates.size >= 4)
        assertEquals(candidates.size, candidates.map { it.signature }.distinct().size)
        assertEquals("反汇编确认的主算法应排第一", QzxySign.sign(fields, loginCode), candidates.first().signature)
        candidates.forEach { candidate ->
            assertEquals(32, candidate.signature.length)
            assertEquals(candidate.signature.lowercase(), candidate.signature)
            assertTrue(candidate.label.isNotBlank())
        }
    }
}
