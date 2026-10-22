package edu.jxslu.schedule.ui.common

import android.os.SystemClock

/**
 * 进程级防连点：窗口启动类动作在 500ms 内只放行第一次。
 *
 * 只用于「双击会开出两个相同窗口」的入口（二级页、设置）。页面内的业务按钮
 * 仍用各自的 busy/防抖状态，不要拿这个替代。
 */
object LaunchGate {
    private const val WINDOW_MS = 500L
    private var lastAt = 0L

    fun allow(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAt < WINDOW_MS) return false
        lastAt = now
        return true
    }
}
