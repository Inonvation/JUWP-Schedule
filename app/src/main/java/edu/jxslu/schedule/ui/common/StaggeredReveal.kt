package edu.jxslu.schedule.ui.common

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** 错峰入场的单块时长（与 [StaggeredReveal] 同一处口径）。 */
private const val RevealDurationMillis = 220

/** 相邻两块之间的起跑间隔。 */
private const val RevealStepMillis = 60L

/** 入场位移：6dp。刻意压小——二级页窗口本身已有 280ms 右推入，这里只让区块落位。 */
private val RevealOffset = 6.dp

/**
 * 进页错峰入场：第 [index] 块延迟 `index × 60ms` 后淡入、并从下往上落 6dp。
 *
 * **只在首次组合播一次**（[LaunchedEffect] 的键是 `Unit`）：页面重组、数据刷新、
 * 状态变化都不会重播——否则每次轮询回来内容都闪一下。
 *
 * 只改透明度与位移（`graphicsLayer` 的 lambda 形式，动画值在绘制阶段读），
 * 不参与测量，因此**不影响布局几何**、也不会把滚动位置算错。
 *
 * 用法：只包页面里「一直存在的主区块」，别包由 `AnimatedVisibility` 管出现/消失的块
 * ——那两套动画叠起来会互相打架。
 */
@Composable
fun StaggeredReveal(
    index: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (index > 0) delay(index * RevealStepMillis)
        shown = true
    }
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(durationMillis = RevealDurationMillis, easing = FastOutSlowInEasing),
        label = "staggeredReveal",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = progress
                translationY = (1f - progress) * RevealOffset.toPx()
            },
    ) {
        content()
    }
}
