package edu.jxslu.schedule.data.xg

import edu.jxslu.schedule.data.jw.JwUrls

/**
 * 学工系统（超星智慧学工）的地址与纯判定（DESIGN §4.26）。
 *
 * 与教务 [JwUrls] 分开放：两个系统除了共用一套统一身份认证，其余没有关系——
 * 域不同，会话机制也不同（学工平台把 token 放在 localStorage，
 * 表单引擎那侧靠 cookie `session_oa` + `JSESSIONID`）。
 *
 * 纯 Kotlin，不碰 android.*：判定逻辑要能在 JVM 上直接测。
 */
object XgUrls {

    /** 学工平台根（超星智慧学工，学校侧 unitId = 343962）。 */
    const val BASE = "https://xgxt.juwp.edu.cn"

    /**
     * 接入学校统一身份认证的入口。未登录 CAS 时 302 到 `eapp2.juwp.edu.cn:9443`；
     * 已有会话则直接送回学工，也就是「从教务进来就免登」的那条链路。
     *
     * **不要**改成超星 passport 的 `/passport/mlogin`：那是学校没配统一认证时的兜底
     * 账号体系（手机号 + 学习通密码），与教务不是一个账号。见 DESIGN §4.26。
     */
    const val SSO_LOGIN = "$BASE/sfrz/login343962"

    /** 学工平台首页（SSO 落地后的落点，报修入口「宿管服务」在这里）。 */
    const val HOME = "$BASE/"

    /** 超星 office 表单引擎根；报修表单跑在 `apps/forms/mobile/apply.html`。 */
    const val OFFICE = "$BASE/office/"

    /** 学工域判定。host 段精确比较，复用 [JwUrls.hostOf] 的唯一实现。 */
    fun isXgHost(url: String?): Boolean = JwUrls.hostOf(url) == "xgxt.juwp.edu.cn"

    /** 统一认证域判定（`eapp2.juwp.edu.cn:9443`，与教务同一个 CAS）。 */
    fun isCasHost(url: String?): Boolean = JwUrls.isCasHost(url)

    /**
     * 顶栏状态条文案（DESIGN §3.15）。只按域分三档，不做更细的页面判断——
     * 学工是 SPA，路由变化不产生新导航，按 URL 猜页面一定会猜错。
     */
    fun statusHint(url: String?): String = when {
        isCasHost(url) -> "用学校统一身份认证登录（与教务同一个账号）"
        isXgHost(url) -> "已进入学工系统，报修在「宿管服务 → 宿舍报修」"
        else -> "正在打开学工系统…"
    }
}
