package edu.jxslu.schedule.domain

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 百宝箱图片工具的共用排版口径（DESIGN §4.47，纯逻辑，可 JVM 测）。
 *
 * 三条纪律，改这块前先读：
 * 1. **所有尺寸只按图片宽度取比例**（字号、边距、底块），图像高只参与「贴底」的锚点计算。
 *    这样预览图（降采样后长边 ≤ 1200）与成品（原图）用同一组参数渲染，观感一致，
 *    保存时把参数按原图尺寸重算一遍即可，不需要维护两套口径。
 * 2. 平铺水印的密度抖动沿用「一个木函」的三档：给平铺块尾部追加空行（块更高 → 纵向更疏），
 *    而不是改行距——行距一改，块内两行文字自己的间距也跟着变。
 * 3. 拼接一律「等宽归一」：目标宽取所有源图的最大宽（大图降采样、小图不放大，锐度优先），
 *    逐张按比例算高再累计。不做上下裁边——那是给截屏留的，活动截图场景里裁掉的是内容。
 */
object ToolboxImage {

    /** 水印平铺密度：尾部空行数（NONE / NORMAL / BIG 三档）。 */
    enum class Density(val blankLines: Int) {
        None(0),
        Normal(1),
        Big(2),
    }

    /** 活动截图里文字块的贴图位置。 */
    enum class Anchor {
        /** 图片正中间（默认；用户口径 2026-10-12）。 */
        Center,
        BottomCenter,
        BottomLeft,
        BottomRight,
    }

    /** 水印字号 = 图片宽度 × 该比例 × 滑杆档位。 */
    const val WatermarkTextRatio = 0.045f

    /** 活动截图字号 = 图片宽度 × 该比例 × 滑杆档位。 */
    const val IdShotTextRatio = 0.05f

    /** 字号下限（px）：窄图（长截图被压到很窄时）不能小到读不出来。 */
    const val MinTextPx = 14f

    /** 活动截图：文字行高 / 字号的倍率。 */
    const val IdShotLineSpacing = 1.4f

    /** 活动截图：底块内边距 / 字号的倍率。 */
    const val IdShotBlockPadding = 0.5f

    /** 活动截图：底块距图片边缘（左右与底）/ 图宽的倍率。 */
    const val IdShotBlockMargin = 0.03f

    /** 水印平铺块的文本：密度档位 = 尾部空行。 */
    fun watermarkPattern(text: String, density: Density): String =
        text + "\n".repeat(density.blankLines)

    /** 水印文字字号（px）。 */
    fun watermarkTextSizePx(imageWidthPx: Int, scale: Float): Float =
        max(imageWidthPx * WatermarkTextRatio * scale, MinTextPx)

    /** 活动截图文字字号（px）。 */
    fun idShotTextSizePx(imageWidthPx: Int, scale: Float): Float =
        max(imageWidthPx * IdShotTextRatio * scale, MinTextPx)

    /** 活动截图的两行文字：姓名、学号；空串直接丢弃（只填了一项就只渲染一行）。 */
    fun idShotLines(name: String, studentId: String): List<String> =
        listOf(name.trim(), studentId.trim()).filter { it.isNotEmpty() }

    /**
     * 活动截图**文字块**的几何，单位 px（定稿 2026-10-12：只画文字、无底衬，
     * 所以这里的「块」只是排版的定位框——文字画在框内、四周留 [IdShotBlockPadding] 的内边距）。
     */
    data class BlockRect(
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float,
    )

    /**
     * 活动截图底块的几何。
     *
     * [measuredTextWidthPx] 是调用方用与成品同一支 Paint 量出的最长一行宽（domain 不碰
     * Android 的文本度量）。底块宽 = 文本宽 + 两倍内边距，再夹进「图宽 − 2×边距」；
     * 块高 = 行数 × 行高 + 两倍内边距；贴底时底边距 = 图宽 × [IdShotBlockMargin]。
     */
    fun idShotBlock(
        imageWidthPx: Int,
        imageHeightPx: Int,
        textSizePx: Float,
        lineCount: Int,
        measuredTextWidthPx: Float,
        anchor: Anchor,
    ): BlockRect {
        val imageWidth = max(imageWidthPx, 1).toFloat()
        val imageHeight = max(imageHeightPx, 1).toFloat()
        val lines = max(lineCount, 1)
        val margin = imageWidth * IdShotBlockMargin
        val padding = textSizePx * IdShotBlockPadding
        val blockWidth = min(
            measuredTextWidthPx + padding * 2f,
            max(imageWidth - margin * 2f, 1f),
        )
        val blockHeight = textSizePx * IdShotLineSpacing * lines + padding * 2f
        val maxLeft = max(imageWidth - blockWidth - margin, margin)
        val left = when (anchor) {
            Anchor.BottomLeft -> margin
            Anchor.Center, Anchor.BottomCenter -> (imageWidth - blockWidth) / 2f
            Anchor.BottomRight -> imageWidth - blockWidth - margin
        }.coerceIn(margin, maxLeft)
        val top = when (anchor) {
            Anchor.Center -> (imageHeight - blockHeight) / 2f
            else -> imageHeight - blockHeight - margin
        }.coerceIn(margin, max(imageHeight - blockHeight - margin, margin))
        return BlockRect(left = left, top = top, width = blockWidth, height = blockHeight)
    }

    /** 拼接目标宽：所有源图的最大宽（小图不放大，锐度优先）。 */
    fun stitchTargetWidth(sourceWidths: List<Int>): Int =
        max(sourceWidths.maxOrNull() ?: 1, 1)

    /**
     * 目标宽再按总像素上限收一次（竖拼长图的内存与「宽 × 总高」成正比）。
     * 超限时按面积比开方把宽缩下来——等比缩放，观感不变，只是整体分辨率降一档；
     * 上限本身由调用方给（Android 侧按内存预算定，domain 不认识字节数）。
     */
    fun stitchFitTargetWidth(
        sourceSizes: List<Pair<Int, Int>>,
        maxPixels: Long,
    ): Int {
        val width = stitchTargetWidth(sourceSizes.map { it.first })
        if (sourceSizes.isEmpty() || maxPixels <= 0L) return width
        val totalHeight = sourceSizes
            .map { (srcWidth, srcHeight) -> stitchScaledHeight(srcWidth, srcHeight, width) }
            .sumOf { it.toLong() }
        val pixels = width.toLong() * totalHeight
        if (pixels <= maxPixels) return width
        val scale = sqrt(maxPixels.toDouble() / pixels.toDouble())
        return max((width * scale).toInt(), 1)
    }

    /** 等宽缩放后单张图的高度（四舍五入，至少 1px）。 */
    fun stitchScaledHeight(srcWidthPx: Int, srcHeightPx: Int, targetWidthPx: Int): Int {
        if (srcWidthPx <= 0 || srcHeightPx <= 0 || targetWidthPx <= 0) return 1
        return max(
            (srcHeightPx.toDouble() * targetWidthPx / srcWidthPx).roundToInt(),
            1,
        )
    }

    /** 竖拼总高（含图间间距）。 */
    fun stitchTotalHeight(scaledHeights: List<Int>, spacingPx: Int): Int {
        if (scaledHeights.isEmpty()) return 0
        val spacing = max(spacingPx, 0)
        return scaledHeights.sum() + spacing * (scaledHeights.size - 1)
    }

    /** 不透明度滑杆（0–100）→ Paint alpha（0–255）。 */
    fun alphaOf(percent: Float): Int =
        (percent.coerceIn(0f, 100f) * 255f / 100f).roundToInt()
}
