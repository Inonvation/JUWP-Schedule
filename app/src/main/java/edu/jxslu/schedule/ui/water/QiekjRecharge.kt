package edu.jxslu.schedule.ui.water

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 胖乖生活充值入口。
 *
 * 充值不在 `userapi.qiekj.com` 的 REST 接口里（QiekjApi 没有充值端点），而是
 * **支付宝小程序**：小程序 appId [MINI_APP_ID]，充值页 buyGold，带商家店号
 * [SHOP_ID]。与趣智同一条路（ui/qzxy/QzxyRecharge.kt）：深链把用户交给支付宝，
 * 页面按支付宝身份自己登录并匹配到本校商户，App 侧不拼任何登录态。
 *
 * 链路来源：官方分享短链 `ur.alipay.com/_…` 2026-09-28 重定向实测解析。短链只是
 * `alipays://platformapi/startapp` 的外壳（appId=20000067 开网页再跳），剥掉分享
 * 埋点（chInfo / shareTimestamp / apshareid / enbsv 版本戳）后钉住业务参数。
 * page 值里的 `/` `?` `&` `=` 必须按支付宝自己生成的形态整体百分号编码——
 * `?`/`&` 不编码会被当成外层 query 截断。
 */
object QiekjRecharge {

    /** 胖乖生活支付宝小程序 appId。 */
    const val MINI_APP_ID = "2018072460764274"

    /** 充值页所属商家店号（本校运营方的商户，非用户态，分享链里原样带着）。 */
    const val SHOP_ID = "202408091502510000078486084837"

    /**
     * 拉起支付宝小程序充值页的深链。
     *
     * 拆成纯字符串是为了能在 JVM 单测里钉住（`android.net.Uri` 在单测里是空壳）。
     */
    fun deepLink(): String =
        "alipays://platformapi/startapp" +
            "?appId=$MINI_APP_ID" +
            "&page=user%2Frecharge%2FbuyGold%2FbuyGold%3F__appxPageId%3D7%26shopId%3D$SHOP_ID"

    /** 深链的 [Uri] 形态，交给 [android.content.Intent]。 */
    fun uri(): Uri = Uri.parse(deepLink())

    /**
     * 打开充值页。
     *
     * @return true = 已交给支付宝；false = 本机没有能处理 `alipays://` 的应用
     *   （通常是没装支付宝）。能否解析取决于 manifest `<queries>` 里
     *   `alipays` scheme 的声明——趣智充值时已加过，这里共用一条。
     */
    fun open(context: Context): Boolean = try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri()))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
