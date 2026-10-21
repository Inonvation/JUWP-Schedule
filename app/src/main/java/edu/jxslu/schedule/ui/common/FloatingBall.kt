package edu.jxslu.schedule.ui.common

import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import edu.jxslu.schedule.domain.FloatingBall
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Wallet01

/** 球与可拖动区域边缘的留白（左右上下同宽）。 */
private val BallMargin = 8.dp

/** 球内图标的尺寸。 */
private val BallIconSize = 24.dp

/** 拖动中球的放大倍率：给「正在移动」一点反馈，松手弹回。 */
private const val BallDragScale = 1.08f

/** 常态不透明度：半透明压得住内容，又不至于喧宾夺主；拖动时提到全不透明。 */
private const val BallIdleAlpha = 0.8f

/** 位置跟随的弹簧：偏硬，跟手不拖沓，松手原地停住（不吸附边缘）。 */
private val BallMotion = spring<Float>(dampingRatio = 1f, stiffness = 6_000f)

/**
 * 阴影：**自绘**径向渐变，不用平台 elevation 阴影。平台会把圆形轮廓近似成多边形
 * （七边形），球的底色又只有 80% 不透明，阴影的多边形面会直接透出来（2026-10-21 用户报）。
 */
private val BallShadowSpread = 7.dp
private val BallShadowOffsetY = 2.dp
private const val BallShadowAlpha = 0.14f

/**
 * 拖动刚结束后挡掉单击的窗口（毫秒）。长按后**原地松手**（没移动）时，单击检测仍可能把
 * 这一次判成 tap——按「拖动结束时刻」挡掉紧随其后的单击，免得「想拖没拖动」误触发付款码。
 * 双击判定窗口约 300ms，取 500ms 留余量。
 */
private const val TapSuppressAfterDragMs = 500L

/**
 * 应用内悬浮球本体（DESIGN §3.1）：圆形主色底 + 阴影，单击 / 双击触发动作，
 * 长按进入拖动。**位置不吸附边缘**——松手停在拖到的任意位置（用户拍板：贴边太丑）。
 * 常态半透明（[BallIdleAlpha]）、拖动时放大并提到全不透明。
 *
 * **渲染位置在 [modifier] 给定的区域里**（外壳传的是整窗，再靠 [topInset] / [bottomInset]
 * 让开状态栏与底栏）。可拖动轨道 = 区域 − 球边长 − 两侧留白，位置以归一化分数表示，
 * 换屏 / 转屏按同一比例落位（见 [FloatingBall]）。
 *
 * 位置用**弹簧**驱动（[BallMotion]）：拖动时球平滑跟手、松手原地停住，不会「啪」地闪到
 * 别处；球自身分数是目标值，`animateFloatAsState` 负责过渡。
 *
 * 手势是**两个独立的 pointerInput**：`detectTapGestures`（单击 / 双击）与
 * `detectDragGesturesAfterLongPress`（长按后拖动）。长按触发拖动后，拖动会消费移动事件，
 * 单击检测据此取消，不会在松手时误触发单击。**单击与双击共存**意味着单击必然有一档
 * ~300ms 的判定延迟（等第二击），这是该交互的固有代价。
 *
 * 拖动期只动本组合内的状态（不牵动外壳）；只有松手才把位置回给宿主落盘。
 */
@Composable
fun FloatingBallOverlay(
    position: FloatingBall.Position,
    onTap: () -> Unit,
    onDoubleTap: () -> Unit,
    onPositionChange: (FloatingBall.Position) -> Unit,
    modifier: Modifier = Modifier,
    topInset: Dp = 0.dp,
    bottomInset: Dp = 0.dp,
) {
    val haptics = rememberAppHaptics()
    val density = LocalDensity.current
    val ballSize = FloatingBall.SizeDp.dp

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .padding(top = topInset, bottom = bottomInset),
    ) {
        val ballPx = with(density) { ballSize.toPx() }
        val marginPx = with(density) { BallMargin.toPx() }
        val trackW = (with(density) { maxWidth.toPx() } - ballPx - marginPx * 2).coerceAtLeast(0f)
        val trackH = (with(density) { maxHeight.toPx() } - ballPx - marginPx * 2).coerceAtLeast(0f)

        // 目标位置分数（拖动直接改；球自身持有的稳定状态对象，pointerInput 不重启也能读到当前值）
        val fracX = remember { mutableFloatStateOf(FloatingBall.clamp(position).x) }
        val fracY = remember { mutableFloatStateOf(FloatingBall.clamp(position).y) }
        // 拖动起点的分数 + 累计像素增量：拖动期据此算出新分数（不吸附）
        val dragStartX = remember { mutableFloatStateOf(0f) }
        val dragStartY = remember { mutableFloatStateOf(0f) }
        var dragPx by remember { mutableStateOf(Offset.Zero) }
        var dragging by remember { mutableStateOf(false) }
        // 拖动结束时刻：挡掉紧随其后、由长按松手误判出来的单击（见 TapSuppressAfterDragMs）
        var lastDragEndAt by remember { mutableStateOf(0L) }

        // 落盘值回读（首次真值到达 / 每次写入回发）时同步。拖动期 position 不变，
        // 本 effect 不触发，因此不会打断进行中的拖动。
        LaunchedEffect(position.x, position.y) {
            fracX.floatValue = position.x
            fracY.floatValue = position.y
        }

        // pointerInput 不随重组重启（key = Unit），闭包里读到的裸 Float / 回调可能是
        // 首帧那一份——经 rememberUpdatedState 转一道，手势回调里永远读到当前值。
        val trackWState = rememberUpdatedState(trackW)
        val trackHState = rememberUpdatedState(trackH)
        val onTapState = rememberUpdatedState(onTap)
        val onDoubleTapState = rememberUpdatedState(onDoubleTap)
        val onPosState = rememberUpdatedState(onPositionChange)

        // 显示位置走弹簧：拖动平滑跟手、松手原地停住（无吸附，故不会「闪」到边缘）
        val animX by animateFloatAsState(targetValue = fracX.floatValue, animationSpec = BallMotion, label = "ballX")
        val animY by animateFloatAsState(targetValue = fracY.floatValue, animationSpec = BallMotion, label = "ballY")
        val scale by animateFloatAsState(
            targetValue = if (dragging) BallDragScale else 1f,
            animationSpec = BallMotion,
            label = "ballScale",
        )
        // 常态半透明、拖动时提到全不透明：既压得住内容，拖动时又看得清。
        // **不透明度落在 Surface 底色上**（图标 tint 保持不透明），不能对整个节点用
        // Modifier.alpha：那会生成一层离屏图层，把阴影裁成节点矩形（用户 2026-10-19
        // 反馈的「球下方矩形阴影」）。阴影同样不能用 `shadowElevation`——平台把圆轮廓
        // 多边形化，半透明底色下透出一块七边形（用户 2026-10-21 反馈），改自绘（见上）。
        val alpha by animateFloatAsState(
            targetValue = if (dragging) 1f else BallIdleAlpha,
            animationSpec = BallMotion,
            label = "ballAlpha",
        )
        val dispX = FloatingBall.offsetPx(animX, trackW)
        val dispY = FloatingBall.offsetPx(animY, trackH)

        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary.copy(alpha = alpha),
            modifier = Modifier
                .offset(
                    x = with(density) { dispX.toDp() } + BallMargin,
                    y = with(density) { dispY.toDp() } + BallMargin,
                )
                .size(ballSize)
                .scale(scale)
                .drawBehind {
                    val radius = size.minDimension / 2f
                    val spread = BallShadowSpread.toPx()
                    val outer = radius + spread
                    val inner = radius / outer
                    val mid = (inner + 1f) / 2f
                    val shadowCenter = center + Offset(0f, BallShadowOffsetY.toPx())
                    // 球内一段是等强度，出球后先缓后急地淡出，接近平台 6dp elevation 的观感
                    drawCircle(
                        brush = Brush.radialGradient(
                            colorStops = arrayOf(
                                0f to Color.Black.copy(alpha = BallShadowAlpha),
                                inner to Color.Black.copy(alpha = BallShadowAlpha),
                                mid to Color.Black.copy(alpha = BallShadowAlpha * 0.45f),
                                1f to Color.Transparent,
                            ),
                            center = shadowCenter,
                            radius = outer,
                        ),
                        radius = outer,
                        center = shadowCenter,
                    )
                }
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            dragging = true
                            dragPx = Offset.Zero
                            dragStartX.floatValue = fracX.floatValue
                            dragStartY.floatValue = fracY.floatValue
                            // 长按即进拖动：给一次触感，明确「现在可以拖了」
                            haptics.tap()
                        },
                        onDrag = { change, delta ->
                            change.consume()
                            dragPx += delta
                            val w = trackWState.value
                            val h = trackHState.value
                            fracX.floatValue = FloatingBall.fraction(
                                FloatingBall.offsetPx(dragStartX.floatValue, w) + dragPx.x,
                                w,
                            )
                            fracY.floatValue = FloatingBall.fraction(
                                FloatingBall.offsetPx(dragStartY.floatValue, h) + dragPx.y,
                                h,
                            )
                        },
                        onDragEnd = {
                            dragging = false
                            dragPx = Offset.Zero
                            lastDragEndAt = SystemClock.elapsedRealtime()
                            onPosState.value(
                                FloatingBall.clamp(FloatingBall.Position(fracX.floatValue, fracY.floatValue)),
                            )
                        },
                        onDragCancel = {
                            dragging = false
                            dragPx = Offset.Zero
                            lastDragEndAt = SystemClock.elapsedRealtime()
                        },
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            // 拖动刚结束时（长按原地松手会误判成单击）不响应
                            if (SystemClock.elapsedRealtime() - lastDragEndAt > TapSuppressAfterDragMs) {
                                haptics.tap()
                                onTapState.value()
                            }
                        },
                        onDoubleTap = {
                            haptics.tap()
                            onDoubleTapState.value()
                        },
                    )
                },
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = HugeIcons.Wallet01,
                    contentDescription = "悬浮球：单击付款码，双击扫一扫，长按拖动",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(BallIconSize),
                )
            }
        }
    }
}
