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
}
