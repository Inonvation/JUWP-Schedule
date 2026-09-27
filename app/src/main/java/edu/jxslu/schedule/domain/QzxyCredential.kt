package edu.jxslu.schedule.domain

import java.security.MessageDigest
import java.util.Locale

/**
 * 趣智校园登录凭证的两个本地推导算法（DESIGN §4.30）。
 *
 * 两条都来自公开逆向结论，与 linyu 的 `SignUtils.smsSecret` / quzhi-lite 的
 * `LoginCredentials` 一致，可互相校验：
 *
 * - 密码：MD5 十六进制后取**后 10 位**并转大写；
 * - 短信验证码 secret：`MD5(手机号前 3 位 + 后 4 位 + "klcx")`，小写 32 位。
 *   secret 不含设备密钥，任何手机号都能本地算出来，所以短信登录不需要抓包。
 */
object QzxyCredential {

    /** 密码传输格式：MD5 → 后 10 位 → 大写。官方 App 与两个参考实现口径一致。 */
    fun passwordValue(password: String): String =
        md5Hex(password).uppercase(Locale.ROOT).takeLast(10)

    /** 短信验证码接口的 secret，仅由手机号推导。 */
    fun smsSecret(phone: String): String {
        require(phone.length == 11) { "手机号必须为 11 位" }
        return md5Hex(phone.substring(0, 3) + phone.substring(7, 11) + "klcx")
    }

    fun md5Hex(input: String): String =
        MessageDigest.getInstance("MD5")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
