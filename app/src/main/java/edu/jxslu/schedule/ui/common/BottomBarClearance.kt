package edu.jxslu.schedule.ui.common

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
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

/**
 * 页面内浮层请求「收起底栏」的通道（DESIGN §3.1/§4.22）：值为 true 表示底栏该显示。
 *
 * 浮层画在 `NavHost` 之内，而底栏在外层 Scaffold 的 `bottomBar` 槽里、画在页面内容**之后**：
 * 不收底栏，面板就盖不住屏幕最下面那一条——悬浮胶囊会浮在面板上，那一带的点击也归胶囊
 * （点「移除背景」会直接跳走 Tab）。当前只有课表页的显示设置面板用这条通道。
 *
 * 传的是 [MutableState] 实例本身，不是 setter lambda：`staticCompositionLocalOf` 换值会重组
 * 整棵子树，而每帧新建 lambda 就等于每次重组都换值。实例归 JuwApp 持有，页面只写 `.value`。
 * 默认值是个没人读的实例，独立窗口的页面（SubpageActivity）里写它等于空操作。
 */
val LocalBottomBarVisibleRequest =
    staticCompositionLocalOf<MutableState<Boolean>> { mutableStateOf(true) }
