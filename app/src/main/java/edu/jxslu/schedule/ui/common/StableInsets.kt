package edu.jxslu.schedule.ui.common

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity

/**
 * 钉住的状态栏 insets，顶栏的 `windowInsets` 参数专用（代替实时 `WindowInsets.statusBars`）。
 *
 * 2026-09-26 修「打开微信瞬间顶栏上跳再还原」：拉起外部应用的跨 task 过渡期间，系统会
 * 临时改变状态栏的可见状态（HyperOS 的过渡动画本身、微信扫一扫相机页的沉浸式全屏），
 * 本窗口会收到 statusBars 高度**瞬时归零**的 insets——顶栏贴着实时 insets 让位，就跟着
 * 上跳、等 insets 还原后再回落。而状态栏高度在本窗口活着期间不会真的变小，所以这里
 * **只认更大的值**：瞬时 0 与回落都不落地，顶栏钉在原地。
 *
 * 代价：横屏这类「状态栏高度真的变小」的形态里，顶栏仍保留竖屏的让位高度——宁可多让，
 * 不跟系统栏的瞬时变化跳舞。
 *
 * M3 `TopAppBar` 的默认 insets 是实时 systemBars，**会拉起外部应用的页面必须显式传
 * 本值**（出码页、一卡通、缴费账单、作业详情、快捷方式、权限设置、附近单车、今日页、
 * 生活页）；不拉起外部应用的页面可以继续用默认值。
 */
@Composable
internal fun pinnedStatusBars(): WindowInsets {
    val density = LocalDensity.current
    val live = WindowInsets.statusBars
    val pinnedTop = remember { mutableIntStateOf(live.getTop(density)) }
    SideEffect {
        val top = live.getTop(density)
        if (top > pinnedTop.intValue) pinnedTop.intValue = top
    }
    return WindowInsets(0, pinnedTop.intValue, 0, 0)
}
