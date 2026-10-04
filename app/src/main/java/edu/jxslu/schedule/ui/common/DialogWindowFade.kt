package edu.jxslu.schedule.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import edu.jxslu.schedule.R

/**
 * 给弹窗窗口补上进出场动画（淡入 + 轻微放大 / 淡出，样式在
 * `res/values/dialog_motion.xml` 的 `DialogWindowFade`）。
 *
 * 为什么要这一段：material3 1.3.0 的 `AlertDialog` 内部没有任何 compose 侧动画，
 * compose 的 Dialog 窗口主题（`DialogWindowTheme`）也没配 `windowAnimationStyle`
 * ——弹窗开与关都是瞬间出现 / 瞬间抽掉（2026-10-03 用户报「开车还车的弹窗有突变
 * 跳动」时的定位结论）。修法是把窗口层的动画样式补上：弹窗内容里的 `LocalView`
 * 就是 `DialogLayout`（它实现 [DialogWindowProvider]），据此拿到 dialog window 设
 * `setWindowAnimations`；窗口动画由 WindowManager 播，内容与背后的压暗一起过渡，
 * 退场时窗口表面停在最后一帧直到播完——不会出现「内容先没了、窗再抽掉」。
 *
 * `setWindowAnimations` 在首帧绘制前生效（本副作用落在首帧 traversal 里，而 enter
 * 动画取样式发生在首帧 HAS_DRAWN 之后），所以进、出两段都能播。
 *
 * 用法：放在 `AlertDialog { }` 同一个组合里调用一次（任意位置，通常是函数体第一行）。
 * 仅对当前这个弹窗窗口生效，不影响页面其它部分。material3 1.4.0 起弹窗自带
 * 进出场动画，届时删掉调用点即可。
 */
@Composable
fun DialogWindowFadeEffect() {
    val view = LocalView.current
    SideEffect {
        // 弹窗内容里的 LocalView 应当就是 DialogLayout；parent 一层是兼容写法，
        // 万一某版本把内容装进子 AndroidComposeView（Popup 那套结构）也不落空
        val window = (view as? DialogWindowProvider)?.window
            ?: (view.parent as? DialogWindowProvider)?.window
        window?.setWindowAnimations(R.style.DialogWindowFade)
    }
}
