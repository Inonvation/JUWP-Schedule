package edu.jxslu.schedule.qiekj

import edu.jxslu.schedule.domain.QiekjSign
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 签名算法对齐测试：参考实现 light-life HeaderInterceptor 的拼接顺序为
 * `appSecret -> channel -> timestamp -> token -> version` 后直接接 path，
 * 整串小写 SHA-256 hex。这里用独立构造的原始串做交叉验证，防止实现侧改动拼接顺序。
 */
class QiekjSignTest {

    private val appSecret = "nFU9pbG8YQoAe1kFh+E7eyrdlSLglwEJeA0wwHB1j5o="
    private val channel = "android_app"
    private val timestamp = "1726570000000"
    private val token = "TOKEN123"
    private val version = "1.60.3"
    private val path = "/goods/water/unlock"

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    @Test
    fun `sha256Hex 与标准测试向量一致`() {
        // NIST FIPS 180-2 标准向量
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            QiekjSign.sha256Hex("abc"),
        )
    }

    @Test
    fun `sign 拼接顺序与参考实现一致`() {
        val raw = "appSecret=$appSecret&channel=$channel&timestamp=$timestamp&token=$token&version=$version$path"
        assertEquals(sha256Hex(raw), QiekjSign.sign(appSecret, channel, timestamp, token, version, path))
    }

    @Test
    fun `sign 逐字段变化会改变结果`() {
        val base = QiekjSign.sign(appSecret, channel, timestamp, token, version, path)
        assertTrue(base != QiekjSign.sign(appSecret, channel, timestamp, token, version, "/user/balance"))
        assertTrue(base != QiekjSign.sign(appSecret, channel, "1726570000001", token, version, path))
        assertTrue(base != QiekjSign.sign(appSecret, "android_ios", timestamp, token, version, path))
    }

    @Test
    fun `sign 输出为 64 位小写 hex`() {
        val sign = QiekjSign.sign(appSecret, channel, timestamp, token, version, path)
        assertEquals(64, sign.length)
        assertEquals(sign, sign.lowercase())
        assertTrue(sign.all { it.isDigit() || it in 'a'..'f' })
    }
}
