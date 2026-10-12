package edu.jxslu.schedule.ui.toolbox

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import edu.jxslu.schedule.domain.StitchScan
import edu.jxslu.schedule.ui.common.decodeUriSampled
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 智能拼接的装配（DESIGN §4.47）：原图降采样到工作分辨率跑 [StitchScan]，
 * 结论按比例折算回原图坐标，交给 [ToolboxRenderer.stitchStreaming] 裁着拼。
 *
 * 为什么要降采样：行比对是 O(高度 × 宽度/步长) 的，全分辨率比对九张 1080×2400 会明显卡；
 * 降一档后（长边 ~1080）偏移的识别粒度是原图的 2 像素级，对「滚动截屏」这个场景足够
 * ——滚多少是几百像素量级。**匹配用工作分辨率、出图用原分辨率**，两者只靠一个缩放比相连。
 *
 * 智能模式的硬前提：**所有图同宽**（差 ≤2%）。竖拼与行匹配的坐标系都只依赖宽度，
 * 高度可以不同——系统长截图、裁过的图和普通截图混拼是常态，上一版要求「宽高都相同」
 * 把这些场景全挡在门外了（2026-10-12 用户报「刚刚能拼的，现在提示尺寸不一致」）。
 * 宽度确实不一致（不同设备/分辨率）时没有同一套坐标系，调用方先判
 * [StitchScan.sameWidth]，不满足就退回直接拼接。
 */
object StitchSmart {

    /** 匹配用的首选工作宽度（[StitchScan.workWidthFor] 会按最长的那张图再收一次）。 */
    private const val WorkWidth = 480

    /**
     * 规划裁剪区间（原图坐标，半开）。返回 null = 这次智能识别没结论
     * （解码失败 / 裁完有图是空的），调用方退回直接拼接。
     *
     * @param fullSizes 所有图的原始尺寸（宽度一致性已由调用方保证）
     * @param statusBarRowsFull 状态栏高度（原始坐标的行数）
     */
    suspend fun plan(
        context: Context,
        uris: List<Uri>,
        fullSizes: List<Pair<Int, Int>>,
        statusBarRowsFull: Int,
        tolerance: StitchScan.Tolerance,
    ): List<StitchScan.KeepRows>? {
        val workWidth = StitchScan.workWidthFor(fullSizes, preferred = WorkWidth)
        val rowImages = ArrayList<StitchScan.RowImage>(uris.size)
        val workHeights = ArrayList<Int>(uris.size)
        uris.forEachIndexed { index, uri ->
            val (fullWidth, fullHeight) = fullSizes[index]
            // 解码目标：让解出来的宽 ≥ 工作宽（长边 × 宽高比），再精确缩到工作宽。
            // 采样是 2 的幂，长截图（1:7 那种）不这么算就会解出一张 135 宽的图再放大 3 倍。
            val longSide = maxOf(fullWidth, fullHeight).toLong()
            val target = (workWidth.toLong() * longSide / fullWidth)
                .coerceAtLeast(workWidth.toLong())
                .toInt()
            val decoded = withContext(Dispatchers.IO) {
                decodeUriSampled(context, uri, target)
            } ?: return null
            val scaled = if (decoded.width == workWidth) {
                decoded
            } else {
                val height = (decoded.height.toFloat() * workWidth / decoded.width)
                    .roundToInt()
                    .coerceAtLeast(1)
                Bitmap.createScaledBitmap(decoded, workWidth, height, true)?.also {
                    if (it !== decoded) decoded.recycle()
                } ?: return null
            }
            val pixels = IntArray(workWidth * scaled.height)
            scaled.getPixels(pixels, 0, workWidth, 0, 0, workWidth, scaled.height)
            workHeights += scaled.height
            scaled.recycle()
            rowImages += StitchScan.RowImage(pixels, workWidth, workHeights.last())
        }
        val bands = withContext(Dispatchers.Default) {
            StitchScan.scan(
                images = rowImages,
                options = StitchScan.Options(
                    statusBarRows = statusBarRowsFull * workWidth / fullSizes.first().first,
                    minBandRows = maxOf(3, workHeights.min() / 200),
                    tolerance = tolerance,
                ),
            )
        }
        val workKeeps = StitchScan.crops(bands, workHeights) ?: return null
        // 逐图折算：纵向比例取每张图自己的（高度不同、宽高比不同都成立）
        val keeps = workKeeps.mapIndexed { index, keep ->
            val fullHeight = fullSizes[index].second
            val scaleY = fullHeight.toFloat() / workHeights[index]
            val top = (keep.top * scaleY).roundToInt().coerceIn(0, fullHeight)
            val bottom = (keep.bottom * scaleY).roundToInt().coerceIn(0, fullHeight)
            StitchScan.KeepRows(top, bottom)
        }
        return if (keeps.any { it.height <= 0 }) null else keeps
    }
}
