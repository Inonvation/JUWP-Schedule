package edu.jxslu.schedule.ui.jwvw

import android.webkit.WebBackForwardList
import edu.jxslu.schedule.data.jw.JwUrls

/**
 * 教务窗口**系统返回**（手势 / 三键）的逐页判定（DESIGN §4.4.4，2026-10-08）。
 *
 * 用户报「有时候按返回没有用」。查下来不是返回键坏了，而是**历史里有一堆退过去看不出变化的条目**：
 *
 * - **同址条目**：重定向链、同址重载、`location.href` 指回自己，都会在历史里留下与当前页
 *   同 URL 的条目——退过去页面长得一模一样，用户看到的就是"按了没反应"；
 * - **认证链**：CAS / SSO / 教务登录页也在历史里（App 自己就是从那儿走过来的）。退到登录页
 *   对用户毫无意义（要重登有顶栏刷新 / 错误浮层的重试），同样会被当成"返回坏了"。
 *
 * 所以系统返回的口径改成：**退到最近一个"有意义的业务页"；没有就关窗口**（等于系统默认的退窗）。
 * 判据是纯函数（[backDelta]），列表来自 [WebBackForwardList] 的薄适配——前者 JVM 可测。
 *
 * ⚠️ **顶栏左上角那枚箭头不走这里**：用户口径（2026-10-08）是箭头**直接退出窗口**，
 * 逐页回退只归系统返回——本对象只服务后者。
 */
object JwWebBack {

    /**
     * 该后退几步（负值）；**null = 没有有意义的上一页，调用方应关窗口**。
     *
     * [entries] 与 [currentIndex] 对应 `WebBackForwardList`（下标 0 是最早的条目）。
     */
    fun backDelta(entries: List<String?>, currentIndex: Int): Int? {
        if (currentIndex !in entries.indices) return null
        val current = entries[currentIndex]
        for (i in currentIndex - 1 downTo 0) {
            val url = entries[i]
            if (url.isNullOrBlank() || url == "about:blank") continue
            // 同址条目：退过去页面一模一样，看着像"返回没反应"
            if (sameTarget(url, current)) continue
            // 认证链：退到登录页没有意义
            if (isAuthChainUrl(url)) continue
            return i - currentIndex
        }
        return null
    }

    /** [WebBackForwardList] 适配（Android 侧唯一一处；逻辑全在上面那个纯函数里）。 */
    fun backDelta(list: WebBackForwardList): Int? {
        val size = list.size
        if (size <= 0) return null
        val entries = (0 until size).map { list.getItemAtIndex(it)?.url }
        return backDelta(entries, list.currentIndex)
    }

    /**
     * 认证链：CAS 域、`/cas/login`、`Logon.do`、`/sso.jsp`（与 [JwWebFit.isLoginLikeUrl] 同口径，
     * 这里再叠一层 CAS 域判断——登录页可能挂在 CAS 域的其它路径上）。
     */
    fun isAuthChainUrl(url: String?): Boolean =
        JwWebFit.isLoginLikeUrl(url) || JwUrls.isCasHost(url)

    /** 比较时忽略 fragment：同文档锚点跳转不算"另一页"。 */
    private fun sameTarget(a: String?, b: String?): Boolean =
        a.orEmpty().substringBefore('#') == b.orEmpty().substringBefore('#')
}
