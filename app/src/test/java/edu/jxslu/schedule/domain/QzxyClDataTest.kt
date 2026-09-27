package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 服务端凭据 `clData` 的解密。密钥取自官方 `libklcxkjencry.so` 的 `.rodata`，
 * 这里用同一把密钥反着加密一遍再解回来，钉住「密钥、模式、去填充」三件事。
 */
class QzxyClDataTest {

    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(QzxyClData.KEY.toByteArray(Charsets.US_ASCII), "AES"),
        )
        return cipher.doFinal(plain)
    }

    @Test
    fun `密钥是 so 里的十六字节常量`() {
        assertEquals(16, QzxyClData.KEY.length)
        assertEquals("20210118klcx@002", QzxyClData.KEY)
    }

    @Test
    fun `Base64 密文能解出原文`() {
        val plain = "260927203533-abcdef".toByteArray(Charsets.US_ASCII)
        val encoded = Base64.getEncoder().encodeToString(encrypt(plain))
        assertEquals(
            "260927203533-abcdef",
            QzxyClData.decrypt(encoded)?.toString(Charsets.US_ASCII),
        )
    }

    @Test
    fun `十六进制密文也能解出原文`() {
        val plain = "aabbccdd".toByteArray(Charsets.US_ASCII)
        val hex = QzxyFrame.bytesToHex(encrypt(plain))
        assertEquals("aabbccdd", QzxyClData.decrypt(hex)?.toString(Charsets.US_ASCII))
    }

    @Test
    fun `ECB 解不出可打印文本时回退 CBC`() {
        val plain = "260927203533-abcdef".toByteArray(Charsets.US_ASCII)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(QzxyClData.KEY.toByteArray(Charsets.US_ASCII), "AES"),
            IvParameterSpec(QzxyClData.IV.toByteArray(Charsets.US_ASCII)),
        )
        val encoded = Base64.getEncoder().encodeToString(cipher.doFinal(plain))
        assertEquals(
            "260927203533-abcdef",
            QzxyClData.decrypt(encoded)?.toString(Charsets.US_ASCII),
        )
    }

    @Test
    fun `空值与长度不对的密文返回 null`() {
        assertNull(QzxyClData.decrypt(null))
        assertNull(QzxyClData.decrypt(""))
        assertNull(QzxyClData.decrypt("   "))
        // 两个字节既不是 16 的整数倍，也不是合法 Base64 块
        assertNull(QzxyClData.decrypt("aabb"))
    }

    @Test
    fun `明文描述按可打印性给文本或十六进制`() {
        assertEquals("-", QzxyClData.describe(null))
        assertEquals("（空）", QzxyClData.describe(ByteArray(0)))
        assertEquals("ab（2 字节）", QzxyClData.describe("ab".toByteArray(Charsets.US_ASCII)))
        assertEquals("00ff（2 字节）", QzxyClData.describe(byteArrayOf(0x00, 0xFF.toByte())))
    }
}
