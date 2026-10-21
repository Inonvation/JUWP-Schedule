package edu.jxslu.schedule.data.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 运行时登录态（DESIGN §3.16 / §4.27）。
 *
 * 只装**运行时**那一半事实：哪个平台已被判失效（凭证错到停用）。「凭证在不在」是持久
 * 事实，由 `CredentialVault` 回答；三档显示由 [LoginStateRules.derive] 把两者合起来。
 * 混成一个布尔就会出现「清了凭证但状态还写着已登录」的悬空态。
 *
 * 进程级单例、只存内存：重启后停用标记由 `CasSession` 从闸门状态恢复（那份是落盘的）。
 */
object SessionStatus {

    private val _suspended = MutableStateFlow<Set<LoginTarget>>(emptySet())

    /** 上次放行「自动填表登录」的时刻（进程级：跨窗口节流，见 [LoginGateRules.AUTO_LOGIN_WINDOW_MS]）。 */
    @Volatile
    private var lastAutoLoginMs = 0L

    /** 已被判失效的平台；状态卡据此把该行显示成「登录状态已失效」。 */
    val suspended: StateFlow<Set<LoginTarget>> = _suspended.asStateFlow()

    fun markSuspended(target: LoginTarget) {
        _suspended.update { it + target }
    }

    fun clearSuspended(target: LoginTarget) {
        _suspended.update { it - target }
    }

    fun isSuspended(target: LoginTarget): Boolean = target in _suspended.value

    /** 上一次「会话作废后重登」的时刻（进程内，不落盘）。 */
    @Volatile
    private var lastReloginMs = 0L

    /** 按布尔值同步——`CasSession` 从持久闸门恢复状态时用。 */
    fun syncSuspended(target: LoginTarget, suspended: Boolean) {
        if (suspended) markSuspended(target) else clearSuspended(target)
    }

    /** 退出登录 / 清凭证时调用。 */
    fun clear(target: LoginTarget) = clearSuspended(target)

    /**
     * 取一次「自动填表登录」的许可；窗口内返回 false。
     *
     * 只在真的要提交表单前调——**拿到许可就算用掉一次**，失败也算（否则密码错时会
     * 每开一个窗口撞一遍）。
     *
     * 已停用（凭证被判错到停用）时一律不放行：那时密码就是错的，再填一次只会多撞一遍
     * CAS 的失败计数。用户改完密码会经 `CasSession.onCredentialsUpdated` 清掉停用标记与节流。
     */
    fun tryAcquireAutoLogin(nowMs: Long): Boolean {
        if (!LoginGateRules.shouldAutoLogin(lastAutoLoginMs, nowMs, isSuspended(LoginTarget.Jw))) {
            return false
        }
        lastAutoLoginMs = nowMs
        return true
    }

    /**
     * 取一次「会话被顶掉后重登」的许可；窗口内返回 false（2026-10-06 复核补）。
     *
     * 与 [tryAcquireAutoLogin] 分开：那条管「用户主动触发的一次登录」，这条管
     * **取数途中发现会话作废**。一次同步里有多个 URL（学业四个维度、选课三个 URL、
     * 成绩按翻页数），每个都能各自触发一次重登——而每次重登都会把对方顶掉
     * （强智单会话；PC 上跑脚本时尤其明显），不设窗口就是登录乒乓。
     */
    fun tryAcquireRelogin(nowMs: Long, windowMs: Long = RELOGIN_WINDOW_MS): Boolean {
        if (isSuspended(LoginTarget.Jw)) return false
        // 时钟回拨（last 在未来）不放行，等它自然走到区间内，与其它闸门同口径
        if (lastReloginMs > nowMs) return false
        if (lastReloginMs != 0L && nowMs - lastReloginMs < windowMs) return false
        lastReloginMs = nowMs
        return true
    }

    /** 用户手动登录成功、或测试里复位时调用。 */
    fun clearAutoLoginThrottle() {
        lastAutoLoginMs = 0L
        lastReloginMs = 0L
    }

    /**
     * 自动填表**没找到表单**（页面还在渲染 / 不是真正的登录页）：把这一轮用掉的节流退回去。
     *
     * 场景（2026-10-21）：CAS 页面停在跳转中的壳上时，`onPageFinished` 先到、表单还没渲染，
     * 注入脚本回 `no-form`。此前这算「用掉一次自动登录」，之后 5 分钟内任何窗口都只能手登
     * ——正是「cookie 过期后不会自动登录」的一半来源。页面级标记仍保留，同一页不反复注入。
     */
    fun releaseAutoLoginAttempt() {
        lastAutoLoginMs = 0L
    }

    /**
     * 自动填表登录**已确认成功**（探针在教务页上通过）：放开节流。
     *
     * 节流原本防的是「密码错时每开一个窗口撞一遍 CAS」。登录成功说明凭证没错，
     * 下一次会话过期（例如刚清理了 WebView cookie）应当允许立即自动登录；
     * 不在这里放开的话，成功那一刻起 5 分钟内的重登会被静默压住。
     */
    fun markAutoLoginSucceeded() {
        lastAutoLoginMs = 0L
    }

    /** 两次「发现会话作废 → 重登」之间的最小间隔。 */
    const val RELOGIN_WINDOW_MS: Long = 60_000L
}
