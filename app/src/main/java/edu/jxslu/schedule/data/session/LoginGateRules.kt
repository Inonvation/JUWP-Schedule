package edu.jxslu.schedule.data.session

/**
 * 登录闸门状态（DESIGN §4.27）。
 *
 * 必须**持久化**：停用只存在内存的话，进程一重启闸门就忘了，用户改密码之前每次冷启动
 * 都会再撞两次风控——那正是这条规则要防的事。
 */
data class LoginGateState(
    /** 连续凭证错次数。成功一次清零。 */
    val consecutiveCredentialFailures: Int = 0,
    /** 已停用：不再自动尝试，等用户更新密码。 */
    val suspended: Boolean = false,
    /** 最近一次登录/会话校验成功时刻。 */
    val lastSuccessMs: Long = 0L,
    /** 最近一次尝试时刻（含失败）。 */
    val lastAttemptMs: Long = 0L,
)

/**
 * 闸门规则（纯函数，便于 JVM 单测）：
 *
 * 1. 同账号 [REPEAT_WINDOW_MS] 内不重复尝试——打开三个窗口不该登三次；
 * 2. [MAX_CREDENTIAL_FAILURES] 次**连续凭证错**即停用，只有凭证错计数，
 *    验证码 / 网络 / 5xx 都不计（它们的成因不在密码上，计数只会误伤）；
 * 3. 最近一次成功在 [SESSION_TRUST_MS] 内直接信会话，不再打校验请求
 *    ——这条是「减少请求」的那一半。
 */
object LoginGateRules {

    const val MAX_CREDENTIAL_FAILURES = 2
    const val REPEAT_WINDOW_MS = 5 * 60_000L
    const val SESSION_TRUST_MS = 10 * 60_000L

    /**
     * 自动填表登录的重复窗口。
     *
     * 页面级那个「只自动一次」挡不住重开窗口：密码改过而 App 还存着旧的时，每开一次
     * 导入页 / 报修 / 成绩单就撞一次 CAS —— 正好是最容易触发风控的形状。
     */
    const val AUTO_LOGIN_WINDOW_MS = 5 * 60_000L

    /**
     * 自动填表登录是否放行。
     *
     * [lastAutoLoginMs] = 上次放行时刻，0 = 从没放过；[suspended] = 该平台的凭证已被判错到停用
     * ——那时再拿同一份密码去撞一次 CAS 只有坏处（账号锁定就靠这个计数）。
     */
    fun shouldAutoLogin(
        lastAutoLoginMs: Long,
        nowMs: Long,
        suspended: Boolean = false,
        windowMs: Long = AUTO_LOGIN_WINDOW_MS,
    ): Boolean = !suspended && (lastAutoLoginMs <= 0L || nowMs - lastAutoLoginMs >= windowMs)

    fun canAttempt(
        state: LoginGateState,
        nowMs: Long,
        windowMs: Long = REPEAT_WINDOW_MS,
    ): Boolean {
        if (state.suspended) return false
        // 窗口只挡「失败后的重试」。`lastAttempt ≤ lastSuccess` 说明那次尝试是**成功**的
        // （旧版本在成功时也写 lastAttempt），不该被挡——进程重启后 cookie 丢了，
        // 正需要立刻重登一次（2026-09-24 实测：引导登录 4.6 分钟后冷启动，
        // lastAttempt 还停在成功那一刻，于是重登被窗口挡掉，用户看到「还是要手动登」）。
        if (state.lastAttemptMs <= state.lastSuccessMs) return true
        return nowMs - state.lastAttemptMs >= windowMs
    }

    fun afterSuccess(nowMs: Long): LoginGateState = LoginGateState(
        consecutiveCredentialFailures = 0,
        suspended = false,
        lastSuccessMs = nowMs,
        // **不设 lastAttemptMs**：重复窗口只该由「失败」驱动。
        // 成功时把它一起推进会让下一次尝试被窗口挡住——而进程重启后 cookie 是丢的、
        // `last_success` 是留的，那时正需要立刻重登一次（2026-09-24 实测踩到）。
        lastAttemptMs = 0L,
    )

    fun afterCredentialFailure(
        state: LoginGateState,
        nowMs: Long,
        maxFailures: Int = MAX_CREDENTIAL_FAILURES,
    ): LoginGateState {
        val failures = state.consecutiveCredentialFailures + 1
        return state.copy(
            consecutiveCredentialFailures = failures,
            suspended = failures >= maxFailures,
            lastAttemptMs = nowMs,
        )
    }

    /** 非凭证错：只推进时间窗，不清零也不累加。 */
    fun afterTransientFailure(state: LoginGateState, nowMs: Long): LoginGateState =
        state.copy(lastAttemptMs = nowMs)

    /** 会话是否还在可信期内。 */
    fun sessionTrusted(
        state: LoginGateState,
        nowMs: Long,
        ttlMs: Long = SESSION_TRUST_MS,
    ): Boolean = state.lastSuccessMs > 0L && nowMs - state.lastSuccessMs <= ttlMs

    fun reset(): LoginGateState = LoginGateState()
}
