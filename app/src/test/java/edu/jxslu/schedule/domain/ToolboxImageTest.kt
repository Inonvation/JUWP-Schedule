package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 百宝箱图片排版口径（DESIGN §4.47）：
 * - 水印密度三档 = 平铺块尾部空行（NONE/NORMAL/BIG），不改行距；
 * - 字号按图宽取比例、有下限，预览与成品同一口径；
 * - 活动截图底块贴底、左右按锚点夹在边距内，超长文本不越出图片；
 * - 拼接等宽归一的取宽与累计高（含间距），退化输入不除零。
 */
class ToolboxImageTest {

    @Test
    fun `水印密度三档就是尾部空行`() {
        assertEquals("水贝贝", ToolboxImage.watermarkPattern("水贝贝", ToolboxImage.Density.None))
        assertEquals(
            "水贝贝\n",
            ToolboxImage.watermarkPattern("水贝贝", ToolboxImage.Density.Normal),
        )
        assertEquals(
            "水贝贝\n\n",
            ToolboxImage.watermarkPattern("水贝贝", ToolboxImage.Density.Big),
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
        assertEquals(
            ToolboxImage.MinTextPx,
            ToolboxImage.idShotTextSizePx(imageWidthPx = 1, scale = 1f),
            0.001f,
        )
    }

    @Test
    fun `活动截图两行只在有内容时出现`() {
        assertEquals(
            listOf("张三", "2023001"),
            ToolboxImage.idShotLines(" 张三 ", "2023001"),
        )
        assertEquals(listOf("张三"), ToolboxImage.idShotLines("张三", "   "))
        assertTrue(ToolboxImage.idShotLines("", "").isEmpty())
    }

    @Test
    fun `活动截图底块贴底且块高随行数增长`() {
        val one = ToolboxImage.idShotBlock(
            imageWidthPx = 1080,
            imageHeightPx = 2400,
            textSizePx = 54f,
            lineCount = 1,
            measuredTextWidthPx = 200f,
            anchor = ToolboxImage.Anchor.BottomCenter,
        )
        val two = ToolboxImage.idShotBlock(
            imageWidthPx = 1080,
            imageHeightPx = 2400,
            textSizePx = 54f,
            lineCount = 2,
            measuredTextWidthPx = 200f,
            anchor = ToolboxImage.Anchor.BottomCenter,
        )
        assertTrue("两行块必须比一行块高", two.height > one.height)
        // 底边距 = 图宽 × 3%
        assertEquals(2400f - 32.4f, one.top + one.height, 0.01f)
        // 底部居中：左右边距相等
        assertEquals(1080f - (one.left + one.width), one.left, 0.01f)
    }

    @Test
    fun `活动截图左锚与右锚贴对应边`() {
        val margin = 1080f * ToolboxImage.IdShotBlockMargin
        val left = ToolboxImage.idShotBlock(
            1080, 2400, 54f, 2, 300f, ToolboxImage.Anchor.BottomLeft,
        )
        val right = ToolboxImage.idShotBlock(
            1080, 2400, 54f, 2, 300f, ToolboxImage.Anchor.BottomRight,
        )
        assertEquals(margin, left.left, 0.01f)
        assertEquals(1080f - margin, right.left + right.width, 0.01f)
    }

    @Test
    fun `活动截图居中锚是正中间`() {
        val block = ToolboxImage.idShotBlock(
            1080, 2400, 54f, 2, 300f, ToolboxImage.Anchor.Center,
        )
        // 左右边距相等（水平居中）
        assertEquals(1080f - (block.left + block.width), block.left, 0.01f)
        // 上下边距相等（垂直居中）
        assertEquals(2400f - (block.top + block.height), block.top, 0.01f)
    }

    @Test
    fun `活动截图锚点顺序与界面选项一一对应`() {
        // 设置页的选项列表按 entries 顺序排（居中 / 底部居中 / 左下 / 右下），
        // 顺序变了界面就错位——这里钉住声明顺序。
        assertEquals(
            listOf(
                ToolboxImage.Anchor.Center,
                ToolboxImage.Anchor.BottomCenter,
                ToolboxImage.Anchor.BottomLeft,
                ToolboxImage.Anchor.BottomRight,
            ),
            ToolboxImage.Anchor.entries.toList(),
        )
    }

    @Test
    fun `超长文本的底块夹在图内`() {
        val block = ToolboxImage.idShotBlock(
            imageWidthPx = 500,
            imageHeightPx = 800,
            textSizePx = 25f,
            lineCount = 2,
            measuredTextWidthPx = 4000f,
            anchor = ToolboxImage.Anchor.BottomCenter,
        )
        assertTrue("块宽不得超过图宽", block.width <= 500f + 0.01f)
        assertTrue("块左不得为负", block.left >= -0.01f)
        assertTrue("块右不得越出图片", block.left + block.width <= 500f + 0.01f)
    }

    @Test
    fun `矮图底块不越出顶部`() {
        val block = ToolboxImage.idShotBlock(
            imageWidthPx = 1080,
            imageHeightPx = 100,
            textSizePx = 54f,
            lineCount = 2,
            measuredTextWidthPx = 200f,
            anchor = ToolboxImage.Anchor.BottomCenter,
        )
        assertTrue("块顶不得为负", block.top >= 0f)
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
