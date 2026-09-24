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

    /** 用户手动登录成功、或测试里复位时调用。 */
    fun clearAutoLoginThrottle() {
        lastAutoLoginMs = 0L
    }
}
