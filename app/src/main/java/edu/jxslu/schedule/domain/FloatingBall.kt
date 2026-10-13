package edu.jxslu.schedule.domain

/**
 * 应用内悬浮球（AssistiveTouch 式快捷入口，DESIGN §3.1）。
 *
 * 位置以**归一化分数**表示（x/y ∈ [0,1]，(1,1) = 右下角），与像素脱钩：换屏 / 转屏 /
 * 分辨率变化时按同一比例落位，不会跑出可视区。**位置不吸附边缘**——松手停在拖到的
 * 任意位置（用户拍板：贴边太丑）。本对象只做纯几何——分数 ↔ 像素、夹取——UI 层不重算，
 * 口径集中一处、可 JVM 测。
 */
object FloatingBall {

    /** 球的边长（dp）。48dp 是可点面积的下限，也是主流悬浮球尺寸。 */
    const val SizeDp = 48f

    /** 默认位置：右下角。 */
    val DefaultPosition = Position(x = 1f, y = 1f)

    /** 归一化位置：0 = 左/上边缘，1 = 右/下边缘。 */
    data class Position(val x: Float, val y: Float)

    /** 分数 → 可拖动轨道内的像素偏移；分数先夹到 [0,1]，轨道为负（未布局）时回 0。 */
    fun offsetPx(fraction: Float, trackPx: Float): Float =
        fraction.coerceIn(0f, 1f) * trackPx.coerceAtLeast(0f)

    /** 像素偏移 → 分数；轨道为 0（未布局）时回 0。 */
    fun fraction(offsetPx: Float, trackPx: Float): Float =
        if (trackPx <= 0f) 0f else (offsetPx / trackPx).coerceIn(0f, 1f)

    /** 夹取到合法区间（读脏偏好 / 越界拖动的兜底）。非有限值（NaN / Inf）退回默认位置。 */
    fun clamp(position: Position): Position =
        Position(
            finite(position.x, DefaultPosition.x),
            finite(position.y, DefaultPosition.y),
        )

    private fun finite(value: Float, fallback: Float): Float =
        if (value.isFinite()) value.coerceIn(0f, 1f) else fallback
}
