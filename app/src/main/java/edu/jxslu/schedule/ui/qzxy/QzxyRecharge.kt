package edu.jxslu.schedule.ui.qzxy

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 趣智校园充值入口（DESIGN §3.18「充值」）。
 *
 * 充值不在趣智的 REST 接口里，而是**支付宝小程序**：小程序 appId
 * [MINI_APP_ID]，充值页 [PAGE]。官方 App 用 mPaaS 把同一个小程序嵌在应用内跑，
 * 我们不复制那条路（那要厂商的 mPaaS 工作区密钥与客户端证书，等于冒充官方身份），
 * 改成深链把用户交给支付宝。
 *
 * **不拼 `myInfo`**。那是小程序侧的登录态（含 `loginCode`），官方「分享 → 复制链接」
 * 为了在别人手机上还原分享者账户才把它塞进 URL。2026-09-27 真机实测：不带它直接开
 * 充值页，小程序按支付宝身份自己登录并匹配到本校账户（页面显示学校名与余额），
 * 所以没必要把会话写进 URL。
 */
object QzxyRecharge {

    /** 趣智校园支付宝小程序 appId（2026-09-27 由官方分享链重定向实测得到）。 */
    const val MINI_APP_ID = "2018090661238647"

    /** 小程序里的充值页路径。 */
    const val PAGE = "pages/recharge/recharge"

    /**
     * 拉起支付宝小程序充值页的深链。
     *
     * 拆成纯字符串是为了能在 JVM 单测里钉住（`android.net.Uri` 在单测里是空壳）。
     */
    fun deepLink(): String = "alipays://platformapi/startapp?appId=$MINI_APP_ID&page=$PAGE"

    /** 深链的 [Uri] 形态，交给 [android.content.Intent]。 */
    fun uri(): Uri = Uri.parse(deepLink())

    /**
     * 打开充值页。
     *
     * @return true = 已交给支付宝；false = 本机没有能处理 `alipays://` 的应用
     *   （通常是没装支付宝）。能否解析取决于 manifest `<queries>` 里
     *   `alipays` scheme 的声明，缺了这条会恒返回 false。
     */
    fun open(context: Context): Boolean = try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri()))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
