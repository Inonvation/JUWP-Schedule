package edu.jxslu.schedule.ui.toolbox

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import edu.jxslu.schedule.domain.ToolboxImage
import edu.jxslu.schedule.domain.StitchScan
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * 百宝箱图片工具的渲染核（DESIGN §4.47）。
 *
 * 三个函数都是「原图进、新图出」，不回收调用方的 bitmap；尺寸口径全部走
 * [ToolboxImage] 的纯函数，预览（降采样源）与成品（原图源）用同一份参数渲染，
 * 因此预览即所得。水印的满图平铺沿用「一个木函」的做法（BitmapShader +
 * TileMode.REPEAT），但平铺块先按旋转后的包围盒扩边，避免转角处切割文字
 * （原版直接旋原尺寸块，45° 时会啃掉字边）。
 */
object ToolboxRenderer {

    /** 满图平铺文字水印。 */
    fun watermark(
        src: Bitmap,
        text: String,
        color: Int,
        alphaPercent: Float,
        rotationDeg: Float,
        scale: Float,
        density: ToolboxImage.Density,
    ): Bitmap {
        val textSize = ToolboxImage.watermarkTextSizePx(src.width, scale)
        val paint = TextPaint().apply {
            this.color = color
            this.textSize = textSize
            isAntiAlias = true
            textAlign = Paint.Align.LEFT
        }
        val pattern = ToolboxImage.watermarkPattern(text, density)
        // 平铺块宽度只能来自**文字实际渲染宽度**，不能用 `layout.width`：
        // `Layout.getWidth()` 返回的是构造时传进去的宽度，不是最长行的宽度。
        // 之前为「不换行」传了 Int.MAX_VALUE，`layout.width` 就跟着是 Int.MAX_VALUE，
        // `Bitmap.createBitmap(2147483647, h, …)` 会让原生 `SkBitmap::setInfo` 失败、
        // colorType 留成未知，`LOG_ALWAYS_FATAL("unknown bitmap configuration")`
        // **直接 abort 整个进程**——用户看到的是「选完图自动跳回上一页」
        // （2026-10-12 真机，见 .agents/rules/toolbox.md）。
        val wrapWidth = pattern.split('\n')
            .maxOf { paint.measureText(it) }
            .let { ceil(it.toDouble()).toInt() }
            .coerceAtLeast(1)
        val layout = StaticLayout.Builder
            .obtain(pattern, 0, pattern.length, paint, wrapWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1f)
            .build()
        val widestLine = (0 until layout.lineCount)
            .maxOfOrNull { layout.getLineWidth(it).toDouble() } ?: 0.0
        val tileWidth = ceil(widestLine).toInt().coerceAtLeast(1)
        val tileHeight = ceil(layout.height.toFloat()).toInt().coerceAtLeast(1)
        val tile = Bitmap.createBitmap(tileWidth, tileHeight, Bitmap.Config.ARGB_8888)
        layout.draw(Canvas(tile))

        val rotated = rotateWithBounds(tile, rotationDeg)
        if (rotated !== tile) tile.recycle()

        val result = Bitmap.createBitmap(src.width, src.height, safeCopyConfig(src))
        val canvas = Canvas(result)
        canvas.drawBitmap(src, 0f, 0f, null)
        val tilePaint = Paint().apply {
            alpha = ToolboxImage.alphaOf(alphaPercent)
            shader = BitmapShader(rotated, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }
        canvas.drawRect(0f, 0f, result.width.toFloat(), result.height.toFloat(), tilePaint)
        rotated.recycle()
        return result
    }

    /**
     * 竖拼（**流式**）：目标宽取最大源宽（等宽归一），逐张「要一张、画一张、丢一张」。
     * 长图拼接是内存最重的一个工具——九张截屏同时解码就是上百 MB，
     * 所以这里只收尺寸表（先 `inJustDecodeBounds` 读边界，零像素内存），
     * 源图由 [sourceAt] 现取现回收，任意时刻只有一张源图在内存里。
     *
     * 长图的内存与「宽 × 总高」成正比：总像素超过 [MaxStitchPixels] 时按 [ToolboxImage]
     * 的判据把目标宽缩下来（等比降档，不是裁内容）；再超过 [Rgb565ThresholdPixels]
     * 的成品换 `RGB_565`（每像素 2 字节，截图类内容观感几乎无差）。
     *
     * [keeps] 由智能拼接的规划给出（`ui/toolbox/StitchSmart.kt`）——每张只画保留的行区间，
     * 重复的头尾与滚动内容在那里被裁掉；null = 直接拼接（逐张全画）。
     *
     * [sourceAt] 返回 null = 这一张读不出来：对应高度留白（底色白）继续拼，
     * 不让单张失败把整张长图作废。底色铺白——带透明通道的 PNG 截图拼出来不该是黑底。
     */
    suspend fun stitchStreaming(
        sourceSizes: List<Pair<Int, Int>>,
        spacingPx: Int,
        /** 每张图保留的行区间（原图坐标，半开）；null = 逐张全画（直接拼接）。 */
        keeps: List<StitchScan.KeepRows>? = null,
        sourceAt: suspend (Int) -> Bitmap?,
    ): Bitmap {
        require(sourceSizes.isNotEmpty()) { "stitch 至少要一张源图" }
        val targetWidth = ToolboxImage.stitchFitTargetWidth(
            sourceSizes = sourceSizes,
            maxPixels = MaxStitchPixels,
        )
        val scaledHeights = sourceSizes.mapIndexed { index, (srcWidth, srcHeight) ->
            val keptHeight = keeps?.getOrNull(index)?.height ?: srcHeight
            ToolboxImage.stitchScaledHeight(srcWidth, keptHeight, targetWidth)
        }
        val spacing = spacingPx.coerceAtLeast(0)
        val totalHeight = ToolboxImage.stitchTotalHeight(scaledHeights, spacing)
        val config = if (targetWidth.toLong() * totalHeight > Rgb565ThresholdPixels) {
            Bitmap.Config.RGB_565
        } else {
            Bitmap.Config.ARGB_8888
        }
        val result = Bitmap.createBitmap(targetWidth, totalHeight, config)
        val canvas = Canvas(result)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        var y = 0f
        sourceSizes.indices.forEach { index ->
            val height = scaledHeights[index].toFloat()
            sourceAt(index)?.let { bitmap ->
                val keep = keeps?.getOrNull(index)
                canvas.drawBitmap(
                    bitmap,
                    if (keep == null) {
                        null
                    } else {
                        Rect(0, keep.top, bitmap.width, keep.bottom.coerceAtMost(bitmap.height))
                    },
                    RectF(0f, y, targetWidth.toFloat(), y + height),
                    paint,
                )
                bitmap.recycle()
            }
            y += height + spacing
        }
        return result
    }

    /**
     * 按旋转角扩边后绘制平铺块：先算旋转后包围盒，把原块旋转画进新画布中心。
     * 角度为 0 时原样返回，不白拷一张。
     */
    private fun rotateWithBounds(tile: Bitmap, rotationDeg: Float): Bitmap {
        if (rotationDeg % 360f == 0f) return tile
        val radians = Math.toRadians(rotationDeg.toDouble())
        val cosA = abs(cos(radians)).toFloat()
        val sinA = abs(sin(radians)).toFloat()
        val width = tile.width.toFloat()
        val height = tile.height.toFloat()
        val expandedWidth = ceil(width * cosA + height * sinA).toInt().coerceAtLeast(1)
        val expandedHeight = ceil(width * sinA + height * cosA).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(expandedWidth, expandedHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.rotate(rotationDeg, expandedWidth / 2f, expandedHeight / 2f)
        canvas.drawBitmap(
            tile,
            (expandedWidth - width) / 2f,
            (expandedHeight - height) / 2f,
            null,
        )
        return out
    }

    /**
     * 出图用的 config：只放行 ARGB_8888 / RGB_565，其余一律回退 ARGB_8888。
     *
     * **不要**直接转发 `src.config`：10-bit / HDR 照片在 API 34+ 会被解成
     * RGBA_F16 / RGBA_1010102 这类非常规配置，不该漏给 `createBitmap`。
     * `src.config` 对 HARDWARE 位图返回 null，也落进这里的回退。
     * （2026-10-12 那次真机 abort 的病因不是这里，是平铺块宽度取错，见 [watermark]。）
     */
    private fun safeCopyConfig(src: Bitmap): Bitmap.Config =
        when (src.config) {
            Bitmap.Config.RGB_565 -> Bitmap.Config.RGB_565
            else -> Bitmap.Config.ARGB_8888
        }

    /** 拼接成品的总像素上限（约等于 1080 × 20000 的长图；超出即等比缩宽）。 */
    private const val MaxStitchPixels = 24_000_000L

    /** 超过这个像素数换 RGB_565：每像素 2 字节，给长图留出内存余量。 */
    private const val Rgb565ThresholdPixels = 8_000_000L
}
