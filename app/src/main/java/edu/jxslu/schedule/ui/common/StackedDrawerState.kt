package edu.jxslu.schedule.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 「主页面在前、侧栏在后」的层叠抽屉状态（2026-10-20，参考 rikkahub 的 DrawerScaffold）。
 *
 * 与上一版的区别：
 * - 手势改用 `Modifier.draggable`（不是 `detectHorizontalDragGestures`）：与页面纵向滚动
 *   共用同一套触摸仲裁，右滑不会再被列表吃掉；
 * - 20dp 方向锁 + 32dp 落定阈值：手指一开始的抖动不会把抽屉判成"反向"；
 * - 松手用临界阻尼弹簧（无回弹），并把手指速度带进动画；速度按 ω·剩余距离封顶，
 *   到边不会冲过头再弹回来；
 * - **状态与触感都在动画落定之后才发生**（2026-10-20 修正）：`isOpen` 不在松手那一刻翻，
 *   而是等页面真的滑到位再翻；`onSettled` 同理。上一版在松手瞬间就震动，
 *   手感上是"还没展开就震了一下"。
 */
@Stable
class StackedDrawerState(
    private val scope: CoroutineScope,
    /** 页面完全打开时的横向位移 = 侧栏宽度。 */
    val maxOffsetPx: Float,
    /** 落定阈值：位移超过它才认为用户想开/关。 */
    private val dragThresholdPx: Float,
    /** 方向锁：水平位移超过它才锁定方向，避免纵向滚动被误判。 */
    private val directionLockPx: Float,
    /** 手势落定（动画播完）后的回调，参数为落定后的开合状态。 */
    private val onSettled: (Boolean) -> Unit = {},
) {
    private val anim = Animatable(0f)

    /** 0 = 全关，1 = 全开。读取发生在 graphicsLayer/offset 的绘制阶段，不触发重组。 */
    val progress: Float get() = anim.value

    /** 已经落定的开合状态（动画播完才翻）。 */
    var isOpen by mutableStateOf(false)
        private set

    /** 用户意图（手势松手 / 按钮点击立即生效），用于去重与返回键。 */
    private var intentOpen by mutableStateOf(false)

    /** 返回键用：展开中或已展开都算"可见"。 */
    val isVisible: Boolean get() = intentOpen || isOpen

    private var dragging = false
    private var dragBase = 0f
    private var dragTotal = 0f
    private var direction = 0f
    private var settleJob: Job? = null

    private val springSpec = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
        visibilityThreshold = 0.002f,
    )
    private val omega = sqrt(Spring.StiffnessMediumLow)

    fun open() {
        if (intentOpen) return
        intentOpen = true
        animateTo(1f)
    }

    fun close() {
        if (!intentOpen) return
        intentOpen = false
        animateTo(0f)
    }

    fun beginDrag() {
        settleJob?.cancel()
        dragging = true
        dragBase = anim.value
        dragTotal = 0f
        direction = 0f
    }

    fun drag(delta: Float) {
        if (!dragging) return
        dragTotal += delta
        if (direction == 0f && abs(dragTotal) > directionLockPx) {
            direction = if (dragTotal > 0f) 1f else -1f
        }
        val target = (dragBase + dragTotal / maxOffsetPx).coerceIn(0f, 1f)
        scope.launch { anim.snapTo(target) }
    }

    fun settle(velocity: Float) {
        if (!dragging) return
        dragging = false
        val target = when {
            dragBase > 0.5f -> {
                // 已开：反向拉回、位移超阈值、或快速左甩 → 关
                if (direction < 0f ||
                    dragTotal < -dragThresholdPx ||
                    (abs(dragTotal) <= dragThresholdPx && velocity < -800f)
                ) 0f else 1f
            }
            else -> {
                // 已关：位移超阈值、或快速右甩 → 开
                if (dragTotal > dragThresholdPx ||
                    (abs(dragTotal) <= dragThresholdPx && velocity > 800f)
                ) 1f else 0f
            }
        }
        intentOpen = target > 0f
        settleJob?.cancel()
        settleJob = scope.launch {
            val v = velocity / maxOffsetPx
            val remaining = target - anim.value
            // 临界阻尼：朝目标方向的速度超过 ω·剩余距离才会冲过头，按它封顶
            val initial = if (remaining == 0f) 0f
            else if (v * remaining <= 0f) v
            else v.coerceIn(-abs(remaining) * omega, abs(remaining) * omega)
            anim.animateTo(target, springSpec, initialVelocity = initial)
            isOpen = target > 0f
            onSettled(isOpen)
        }
    }

    private fun animateTo(target: Float) {
        settleJob?.cancel()
        settleJob = scope.launch {
            anim.animateTo(target, springSpec)
            isOpen = target > 0f
        }
    }
}
