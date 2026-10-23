package edu.jxslu.schedule.domain

/**
 * 胖乖「打开软件自动签到」的到期判定。
 *
 * 纯函数，便于 JVM 单测：开关关着、未登录、或今天已经跑过，都不发请求。
 * [lastRunDate] 与 [today] 都是 ISO `yyyy-MM-dd`（本地时区）。
 */
object QiekjAutoSignInRules {
    fun shouldRun(
        enabled: Boolean,
        loggedIn: Boolean,
        lastRunDate: String?,
        today: String,
    ): Boolean = enabled && loggedIn && lastRunDate != today
}
