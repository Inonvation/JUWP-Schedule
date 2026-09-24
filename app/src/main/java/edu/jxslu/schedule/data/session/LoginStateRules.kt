package edu.jxslu.schedule.data.session

/** App 要展示登录态的三个平台（DESIGN §3.16 账户卡的三行）。 */
enum class LoginTarget { Jw, Ykt, Qiekj }

enum class LoginState { NotLoggedIn, LoggedIn, Expired }

/**
 * 三档状态推导（DESIGN §3.16「状态判定口径」）。
 *
 * 「凭证在不在」是持久事实，「停用」是运行时事实，两者不能混成一个布尔：
 * 停用优先（凭证在也判失效），其次看凭证或 WebView 会话。
 *
 * [webSessionExists] 是给升级用户的：他们没存凭证，但 WebView 里可能还留着有效的
 * 教务会话。不认这一点，状态卡会出现「说未登录、点进导入却能用」的自相矛盾。
 */
object LoginStateRules {

    fun derive(
        credentialExists: Boolean,
        webSessionExists: Boolean,
        suspended: Boolean,
    ): LoginState = when {
        suspended -> LoginState.Expired
        credentialExists || webSessionExists -> LoginState.LoggedIn
        else -> LoginState.NotLoggedIn
    }
}
