package edu.jxslu.schedule.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 某个文字样式的**单行高度**（dp）。
 *
 * 给「骨架与成品同几何」用（DESIGN §3.10 付款码页 / §3.13 生活页流水）：占位条要顶掉一行
 * 真实文字时，高度取这里，而不是写死一个 dp。写死的话用户把系统字体调大，成品那行会比骨架
 * 高一截，跳动又回来了。
 *
 * 口径：一行 `Text` 的实测高度就等于样式的 `lineHeight`，所以直接换算 sp → dp
 * （乘 `fontScale` 即可；`sp → px` 乘 `fontScale × density`，`px → dp` 再除 `density`，
 * 两步里的 `density` 抵消）。样式没声明 `lineHeight` 时退回 [fallback]。
 */
@Composable
@ReadOnlyComposable
fun lineHeightDp(style: TextStyle, fallback: Dp = 16.dp): Dp {
    val lineHeight = style.lineHeight
    if (!lineHeight.isSp) return fallback
    return (lineHeight.value * LocalDensity.current.fontScale).dp
}
