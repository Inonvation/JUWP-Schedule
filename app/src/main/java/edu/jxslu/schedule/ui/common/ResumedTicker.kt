package edu.jxslu.schedule.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/**
 * 页面级定时器的生命周期口径（2026-10-02 收口）。
 *
 * 此前散在各页的 `LaunchedEffect(Unit) { while(true) { tick; delay } }` 只随「组合离开」
 * 停——App 退到后台 / 灭屏时组合还在，循环照跑：每 30 秒唤醒进程 + 重组整页的时钟
 * （今日页 / 课表页）、每秒走秒（骑行卡）、每 15 秒重算「更新于」（车辆面板）。
 * 全部收口成 `repeatOnLifecycle(RESUMED)`：**灭屏或退后台立刻停，回前台立刻续**。
 * delay 的最后一次到点若恰好落在后台，回前台后第一拍立即补上，不丢拍。
 */

/** 周期回调型：每 [intervalMillis] 跑一次 [onTick]，仅 RESUMED 期间（刷新整页状态的那类）。 */
@Composable
fun ResumedTickerEffect(intervalMillis: Long, onTick: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    androidx.compose.runtime.LaunchedEffect(owner, intervalMillis) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(intervalMillis)
                onTick()
            }
        }
    }
}

/**
 * 读值型：RESUMED 期间每 [intervalMillis] 更新一次「现在」的 [androidx.compose.runtime.State]，
 * 非 RESUMED 时循环挂起、值定格（读值消费方也停重组，两不耽误）。
 */
@Composable
fun rememberResumedNow(intervalMillis: Long, key: Any? = null): State<Long> {
    val owner = LocalLifecycleOwner.current
    return produceState(initialValue = System.currentTimeMillis(), key, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                value = System.currentTimeMillis()
                delay(intervalMillis)
            }
        }
    }
}
