package edu.jxslu.schedule.data.session

/**
 * 学校系统取数失败时的网络排查提示（**唯一口径**，2026-09-27）。
 *
 * 两句分支：VPN/代理开着时点名关掉它（学校对代理出口会超时或返回错误页，2026-09-18 真机
 * 实测，见 DESIGN §7.6 与 `JwVpnDetector`）；没开代理时让人换一条网络（校园网与手机流量
 * 是两条出口）。教务、一卡通、电费、胖乖的取数失败都用这一份，别再各写一句。
 *
 * **为什么有长短两套**：整行的地方（WebView 失败浮层、付款码错误态、账单页提示块、
 * 消息条）用 [of]，能把原因说全；钱包卡副行这类只有一行、约十来个字宽的地方用 [briefOf]，
 * 写全了会被省略号吃掉，等于没提示。两套一起改，`NetworkHintTest` 钉住短文案的字数上限。
 *
 * 纯逻辑，不依赖 android.*，可 JVM 单测。
 */
object NetworkHint {

    /** 长文案：检测到 VPN/代理。 */
    const val VPN: String = "检测到 VPN/代理：学校系统对代理出口会超时或拒绝，请先关掉它再试"

    /** 长文案：没开代理时换一条网络（校园网 Wi-Fi ↔ 手机流量）。 */
    const val SWITCH_NETWORK: String = "若一直连不上，换一条网络再试：关掉校园网 Wi-Fi，用手机流量"

    /** 短文案：[VPN] 的钱包卡副行版。 */
    const val VPN_BRIEF: String = "VPN 已开，关掉再试"

    /** 短文案：[SWITCH_NETWORK] 的钱包卡副行版。 */
    const val SWITCH_BRIEF: String = "连不上，换个网络再试"

    /** 整行场景的提示。 */
    fun of(vpnActive: Boolean): String = if (vpnActive) VPN else SWITCH_NETWORK

    /** 一行窄场景（钱包卡副行、状态条）的提示。 */
    fun briefOf(vpnActive: Boolean): String = if (vpnActive) VPN_BRIEF else SWITCH_BRIEF
}
