package edu.jxslu.schedule.ui.common

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

/**
 * 底部胶囊底栏给页面留的净空（DESIGN §4.22）。
 *
 * 悬浮形态下页面内容**一直铺到窗口底**、允许被胶囊压住一部分（这才是「悬浮」），
 * 页面的滚动内容靠这个值把最后一项顶到胶囊上方。
 *
 * 普通形态（不悬浮）下它是 0：那种形态的让位由外层 Scaffold 的 `padding` 提供，
 * 页面不需要自己管。两种形态各有一条让位通道，互不叠加。
 */
val LocalBottomBarClearance = staticCompositionLocalOf { 0.dp }
