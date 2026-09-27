package edu.jxslu.schedule.domain

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 上传消费数据后服务端返回的 `clData`（DESIGN §4.30）。
 *
 * 官方 App 拿到它之后调 `JniUtils.decryptByAES` 解一次，明文里带一个 `-` 分隔符。
 * 设备那句「每次需验证通过后才能清除数据」很可能就指它：蓝牙设备服务端够不着，
 * 只能由服务端签发一张凭据、App 转交给设备验，验过了才让清。
 *
 * 密钥来自官方 `libklcxkjencry.so` 的反汇编（2026-09-27）。`decryptByAES` 的入口
 * 把两个 `.rodata` 常量当密钥与 IV 传进去：
 *
 * ```
 * .rodata 0xa282e  "20210118klcx@002"   ← 密钥，16 字节
 * .rodata 0xa283f  "1234567876543210"   ← IV，ECB 模式下不用
 * ```
 *
 * 同一段 so 里 `getSk` / `getCk` / `getMk` / `getBk` / `getDk` 另有几把旧协议的
 * 密钥（`20181201klcx@001`、`klcx002@20181224` 等），与这里无关，别混用。
 *
 * 模式是 ECB。so 里那句 `Data not multiple of Block Size` 说明它按无填充处理，
 * 所以这里也走 `NoPadding`，再手工剥 PKCS#7——有填充就剥，没有就原样返回。
 */
object QzxyClData {

    /** AES-128 密钥，官方 so 里的常量。 */
    const val KEY: String = "20210118klcx@002"

    /** ECB 模式下用不到，CBC 回退时才用。同样是官方 so 里的常量。 */
    const val IV: String = "1234567876543210"

    private const val BLOCK_SIZE = 16

    /**
     * 解密 `clData`。密文按十六进制或 Base64 解，哪种能解出 16 的整数倍就用哪种。
     * 长度不对、密钥不可用、解密失败都返回 null。
     *
     * 模式按官方分析给的是 ECB，但那个 `decryptByAES` 的入参里确实带着 IV，
     * 所以 ECB 解出来不是可打印文本时再试一次 CBC。正常路径不会走到 CBC。
     */
    fun decrypt(encoded: String?): ByteArray? {
        val text = encoded?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val cipherText = decodeCipherText(text) ?: return null
        val key = SecretKeySpec(KEY.toByteArray(Charsets.US_ASCII), "AES")
        val ecb = runCatching {
            stripPadding(transform("AES/ECB/NoPadding", key, cipherText, null))
        }.getOrNull()
        if (ecb != null && isPrintable(ecb)) return ecb
        val cbc = runCatching {
            stripPadding(
                transform(
                    "AES/CBC/NoPadding",
                    key,
                    cipherText,
                    IvParameterSpec(IV.toByteArray(Charsets.US_ASCII)),
                ),
            )
        }.getOrNull()
        if (cbc != null && isPrintable(cbc)) return cbc
        return ecb
    }

    private fun transform(
        algorithm: String,
        key: SecretKeySpec,
        cipherText: ByteArray,
        iv: IvParameterSpec?,
    ): ByteArray {
        val cipher = Cipher.getInstance(algorithm)
        if (iv == null) {
            cipher.init(Cipher.DECRYPT_MODE, key)
        } else {
            cipher.init(Cipher.DECRYPT_MODE, key, iv)
        }
        return cipher.doFinal(cipherText)
    }

    private fun isPrintable(bytes: ByteArray): Boolean =
        bytes.isNotEmpty() && bytes.all { (it.toInt() and 0xFF) in 0x20..0x7E }

    private fun decodeCipherText(text: String): ByteArray? {
        QzxyFrame.hexToBytes(text)
            ?.takeIf { it.size % BLOCK_SIZE == 0 }
            ?.let { return it }
        return runCatching { Base64.getDecoder().decode(text) }
            .getOrNull()
            ?.takeIf { it.size % BLOCK_SIZE == 0 }
    }

    /** 剥 PKCS#7 填充。填充不合法就原样返回，宁可把多出来的字节留给调用方看。 */
    private fun stripPadding(plain: ByteArray): ByteArray {
        if (plain.isEmpty()) return plain
        val pad = plain.last().toInt() and 0xFF
        if (pad !in 1..BLOCK_SIZE || pad > plain.size) return plain
        if (plain.takeLast(pad).any { (it.toInt() and 0xFF) != pad }) return plain
        return plain.copyOfRange(0, plain.size - pad)
    }

    /**
     * 给人看的明文形式：全是可打印 ASCII 就按文本给，否则给十六进制。
     * 调试日志与诊断文本共用，省得两边各写一份。
     */
    fun describe(plain: ByteArray?): String {
        if (plain == null) return "-"
        if (plain.isEmpty()) return "（空）"
        val printable = plain.all { (it.toInt() and 0xFF) in 0x20..0x7E }
        return if (printable) {
            "${plain.toString(Charsets.US_ASCII)}（${plain.size} 字节）"
        } else {
            "${QzxyFrame.bytesToHex(plain)}（${plain.size} 字节）"
        }
    }
}
