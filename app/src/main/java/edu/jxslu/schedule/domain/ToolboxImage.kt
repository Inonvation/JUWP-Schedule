package edu.jxslu.schedule.domain

import kotlin.math.max
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
 *    逐张按比例算高再累计。不做上下裁边——那会裁掉真实内容。
 */
object ToolboxImage {

    /** 水印平铺密度：尾部空行数（NONE / NORMAL / BIG 三档）。 */
    enum class Density(val blankLines: Int) {
        None(0),
        Normal(1),
        Big(2),
    }

    /** 水印字号 = 图片宽度 × 该比例 × 滑杆档位。 */
    const val WatermarkTextRatio = 0.045f

    /** 字号下限（px）：窄图（长截图被压到很窄时）不能小到读不出来。 */
    const val MinTextPx = 14f

    /** 水印平铺块的文本：密度档位 = 尾部空行。 */
    fun watermarkPattern(text: String, density: Density): String =
        text + "\n".repeat(density.blankLines)

    /** 水印文字字号（px）。 */
    fun watermarkTextSizePx(imageWidthPx: Int, scale: Float): Float =
        max(imageWidthPx * WatermarkTextRatio * scale, MinTextPx)

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
