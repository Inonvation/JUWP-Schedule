package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 百宝箱图片排版口径（DESIGN §4.47）：
 * - 水印密度三档 = 平铺块尾部空行（NONE/NORMAL/BIG），不改行距；
 * - 字号按图宽取比例、有下限，预览与成品同一口径；
 * - 拼接等宽归一的取宽与累计高（含间距），退化输入不除零。
 */
class ToolboxImageTest {

    @Test
    fun `水印密度三档就是尾部空行`() {
        assertEquals("JUWPHub", ToolboxImage.watermarkPattern("JUWPHub", ToolboxImage.Density.None))
        assertEquals(
            "JUWPHub\n",
            ToolboxImage.watermarkPattern("JUWPHub", ToolboxImage.Density.Normal),
        )
        assertEquals(
            "JUWPHub\n\n",
            ToolboxImage.watermarkPattern("JUWPHub", ToolboxImage.Density.Big),
        )
    }

    @Test
    fun `水印字号按图宽取比例且随档位线性`() {
        val base = ToolboxImage.watermarkTextSizePx(imageWidthPx = 1000, scale = 1f)
        assertEquals(45f, base, 0.001f)
        assertEquals(
            base * 2f,
            ToolboxImage.watermarkTextSizePx(imageWidthPx = 1000, scale = 2f),
            0.001f,
        )
        // 不是横向线性：图宽翻倍，字号翻倍
        assertEquals(
            base * 2f,
            ToolboxImage.watermarkTextSizePx(imageWidthPx = 2000, scale = 1f),
            0.001f,
        )
    }

    @Test
    fun `窄图字号有下限`() {
        assertEquals(
            ToolboxImage.MinTextPx,
            ToolboxImage.watermarkTextSizePx(imageWidthPx = 32, scale = 0.05f),
            0.001f,
        )
    }

    @Test
    fun `拼接目标宽取最大宽且不放大`() {
        assertEquals(1200, ToolboxImage.stitchTargetWidth(listOf(1080, 1200, 720)))
        assertEquals(1, ToolboxImage.stitchTargetWidth(emptyList()))
        assertEquals(1, ToolboxImage.stitchTargetWidth(listOf(-5, 0)))
    }

    @Test
    fun `拼接单张高度按比例四舍五入`() {
        // 1000×333 缩到 1200 宽 → 高 399.6 → 400
        assertEquals(400, ToolboxImage.stitchScaledHeight(1000, 333, 1200))
        // 同宽不缩放
        assertEquals(2400, ToolboxImage.stitchScaledHeight(1080, 2400, 1080))
        // 退化输入不除零
        assertEquals(1, ToolboxImage.stitchScaledHeight(0, 100, 1200))
        assertEquals(1, ToolboxImage.stitchScaledHeight(100, 100, 0))
    }

    @Test
    fun `拼接总高含图间间距`() {
        assertEquals(0, ToolboxImage.stitchTotalHeight(emptyList(), 8))
        assertEquals(100, ToolboxImage.stitchTotalHeight(listOf(100), 8))
        // 3 张 100 高 + 2 段间距 8
        assertEquals(316, ToolboxImage.stitchTotalHeight(listOf(100, 100, 100), 8))
        // 负间距按 0 处理
        assertEquals(300, ToolboxImage.stitchTotalHeight(listOf(100, 100, 100), -4))
    }

    @Test
    fun `拼接目标宽受总像素上限约束`() {
        // 1080×2400 ×9 张：不设限时宽取 1080
        val nine = List(9) { 1080 to 2400 }
        assertEquals(1080, ToolboxImage.stitchFitTargetWidth(nine, maxPixels = Long.MAX_VALUE))
        // 上限压到 100 万像素：宽必须缩到满足 宽 × 总高 ≤ 上限
        val limited = ToolboxImage.stitchFitTargetWidth(nine, maxPixels = 1_000_000L)
        assertTrue("超限时必须缩宽", limited < 1080)
        val totalHeight = nine
            .map { (w, h) -> ToolboxImage.stitchScaledHeight(w, h, limited) }
            .sumOf { it.toLong() }
        assertTrue(
            "缩小后总像素应落回上限内（允许取整误差）",
            limited.toLong() * totalHeight <= 1_000_000L + limited,
        )
        // 上限 <= 0 或不超限：原样返回最大宽
        assertEquals(1080, ToolboxImage.stitchFitTargetWidth(nine, maxPixels = 0L))
        assertEquals(1, ToolboxImage.stitchFitTargetWidth(emptyList(), maxPixels = 1000L))
    }

    @Test
    fun `不透明度滑杆换算成 alpha 并夹取`() {
        assertEquals(0, ToolboxImage.alphaOf(0f))
        assertEquals(128, ToolboxImage.alphaOf(50f))
        assertEquals(255, ToolboxImage.alphaOf(100f))
        assertEquals(0, ToolboxImage.alphaOf(-10f))
        assertEquals(255, ToolboxImage.alphaOf(300f))
    }
}
