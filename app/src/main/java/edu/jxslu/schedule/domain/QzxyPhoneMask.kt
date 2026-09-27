package edu.jxslu.schedule.domain

/**
 * 登录手机号的遮蔽显示（DESIGN §4.30）。
 *
 * 账号一行默认给遮蔽后的号码，点眼睛才展开完整的。手机号是账号标识，
 * 页面会被截图、会被旁人瞥见，默认露出没有好处——与「我的」页学号同一个口径。
 */
object QzxyPhoneMask {

    /** 11 位号码留前 3 后 4（`138****8888`）；短号只留首尾各一位。 */
    fun mask(phone: String): String {
        val text = phone.trim()
        if (text.isEmpty()) return "-"
        if (text.length <= 4) return "*".repeat(text.length)
        val head = if (text.length >= 11) 3 else 1
        val tail = if (text.length >= 11) 4 else 1
        return text.take(head) + "****" + text.takeLast(tail)
    }
}
