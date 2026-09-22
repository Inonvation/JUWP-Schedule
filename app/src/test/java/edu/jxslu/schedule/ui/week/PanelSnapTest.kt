package edu.jxslu.schedule.ui.week

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 显示设置弹层「拖把手调高度」的纯函数契约。
 *
 * 把手交互：上下拖连续调面板高度，松手吸附到最近档位（nearestAnchor）；
 * 拖到最小档继续下拉超过 [PanelCollapseOverdrag] 才关闭。
 * 旧版是「拖过阈值立即关」，用户想调高度却一拖就关（真机反馈）——
 * 这些常量与吸附行为是本轮交互语义的根，改动应是有意的。
 */
class PanelSnapTest {

    private val anchors = listOf(200.dp, 320.dp, 480.dp)

    /** 中间值吸附到最近档：低于半程吸附下档、超过半程吸附上档。 */
    @Test
    fun snapsToNearestAnchor() {
        assertEquals(200.dp, nearestAnchor(200.dp, anchors))
        assertEquals(200.dp, nearestAnchor(210.dp, anchors))
        // 260 是 200/320 的中点，minBy 取先出现的下档（稳定语义）
        assertEquals(200.dp, nearestAnchor(260.dp, anchors))
        assertEquals(320.dp, nearestAnchor(261.dp, anchors))
        assertEquals(320.dp, nearestAnchor(390.dp, anchors))
        assertEquals(480.dp, nearestAnchor(480.dp, anchors))
    }

    /** 超出锚点范围时吸附到最近端点（不会飘出列表外）。 */
    @Test
    fun clampsOutOfRangeToEdgeAnchors() {
        assertEquals(200.dp, nearestAnchor(50.dp, anchors))
        assertEquals(480.dp, nearestAnchor(900.dp, anchors))
    }

    /** 空锚点表退回原值：调用方保证非空，这里只钉住不崩的兜底行为。 */
    @Test
    fun fallsBackToInputWhenNoAnchors() {
        assertEquals(123.dp, nearestAnchor(123.dp, emptyList()))
    }

    /**
     * 拖动全程夹取上下两端。
     *
     * 上限这条是 2026-09-22 真机反馈的根因：只夹下限时向上拖能把面板拉过最高档
     * （高度在 measure 阶段按 state 定，不受父约束拦），松手吸附回最高档就是「弹窗跳一下」。
     */
    @Test
    fun dragClampsBothEnds() {
        assertEquals(180f, clampPanelHeight(120f, 180f, 600f), 0f)
        assertEquals(600f, clampPanelHeight(900f, 180f, 600f), 0f)
        assertEquals(400f, clampPanelHeight(400f, 180f, 600f), 0f)
    }

    /** 退化屏（下限高于上限，极矮的分屏窗口）退回下限，不能抛：`coerceIn` 遇 min > max 会抛。 */
    @Test
    fun degenerateScreenFallsBackToMin() {
        assertEquals(180f, clampPanelHeight(300f, 180f, 90f), 0f)
    }

    /** 交互常量：最小高度 / 关闭余量 / 默认档与最大档。 */
    @Test
    fun interactionConstantsAreStable() {
        assertEquals(180f, PanelMinHeight.value, 0f)
        assertEquals(64f, PanelCollapseOverdrag.value, 0f)
        assertEquals(0.40f, PanelDefaultFraction, 0f)
        assertEquals(0.60f, PanelMaxFraction, 0f)
        // 档位升序且包含默认档
        assertTrue(PanelHeightFractions.contains(PanelDefaultFraction))
        assertEquals(PanelHeightFractions.sorted(), PanelHeightFractions)
        // 最大档即上限
        assertEquals(PanelMaxFraction, PanelHeightFractions.last(), 0f)
    }

    /** 关闭只在拖到最小档之后发生：常量语义钉子（阈值 > 0）。 */
    @Test
    fun collapseRequiresPositiveOverdrag() {
        assertFalse(PanelCollapseOverdrag.value <= 0f)
    }
}
