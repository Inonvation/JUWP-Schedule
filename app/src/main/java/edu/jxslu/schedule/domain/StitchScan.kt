package edu.jxslu.schedule.domain

import kotlin.math.abs
import kotlin.math.min

/**
 * 智能拼接的行匹配核（DESIGN §4.47，纯逻辑，可 JVM 测）。
 *
 * 思路拆自 PPIICC（`bos.consoar.imagestitch` 0.5.5，REA 静态逆向，笔记在本地
 * `.re-tmp/ppiicc/`，不入库）。它解决的是「滚了几屏的截图摞成长图」里两类重复：
 *
 * 1. **公共头 / 尾**：每张截图都有的状态栏 + 标题栏 + 底部 Tab 栏——比对最上 / 最下的
 *    连续相同行，取所有图对的**最小值**（保守：宁可少裁，不可多裁）；
 * 2. **滚动重叠**：相邻两张之间重复的那几行内容——从最大可能的重叠往小搜，
 *    对每个候选**逐行**比对，全部行都落在容差内才算命中，取最大的那个；找不到记 0，
 *    调用方退回硬接（原版也是这么兜底的）。
 *
 * 与原版的三处差异：
 * - **阈值语义说人话**：原版拿「一行抽样像素的 |ΔR|+|ΔG|+|ΔB| 之和」和 4000 比，
 *   这里折算成「每样点平均色差」（同样 0..765 的量纲）与 [Tolerance] 比，好测也好调；
 * - **状态栏行不进匹配**：时钟 / 电量每张都不同，会打断公共带的连续匹配。原版靠
 *   「跳过若干行再验证」，这里把这一步显式化：调用方按系统状态栏高度传 `statusBarRows`，
 *   它**直接计入头高**、从其后才开始验证（与原版 `h()` 的口径一致）；
 * - 行比对按下标直接取像素，不再拷两份 `int[]` 全图。
 *
 * 全部数值都是**同一分辨率坐标系**（调用方把图降采样到工作分辨率后再进来，
 * 得到的工作坐标再按比例折算回原图，见 `ui/toolbox/StitchSmart.kt`）。
 */
object StitchScan {

    /** 快筛抽行步长：先隔几行比一遍，通过的候选才做全行精验。 */
    private const val FastVerifyStride = 4

    /**
     * 精修半径：找到达标候选后，在它前后各这么多行里挑平均差异最小的那个。
     *
     * 为什么需要（2026-10-12 真机实测）：**相邻行长得像时，错开一行的对齐也能过低阈值**。
     * 拿用户那对 1080×2400 的滚动截图跑：真对齐的平均逐行差异 8.0，而错一行的候选也达标
     * ——「取从大往小第一个达标的」就会差 1～38 行（档位越松越离谱），成品接缝缺一条内容。
     * 真对齐的分数明显更低，取最优即可。
     */
    private const val RefineRadius = 16

    /** 单行匹配的容差档位（每样点平均色差，0..765）。 */
    enum class Tolerance(val maxRowDiff: Int) {
        Strict(90),
        Normal(150),
        Loose(220),
    }

    /** 一部分图的像素（ARGB，行优先、每行 [width] 个）。 */
    class RowImage(val pixels: IntArray, val width: Int, val height: Int)

    /** 一张图保留的行区间（半开）：`[top, bottom)`。 */
    data class KeepRows(val top: Int, val bottom: Int) {
        val height: Int get() = bottom - top
    }

    /** 扫描结论（工作分辨率坐标）。 */
    data class Bands(
        val head: Int,
        val foot: Int,
        val extraHead: Int,
        val overlaps: List<Int>,
    )

    data class Options(
        /** 顶部假定为公共头的行数（状态栏高度，按工作分辨率折算）。 */
        val statusBarRows: Int = 0,
        /** 小于等于这个行数的公共带一律当 0（5dp 量级的碎边不值得裁）。 */
        val minBandRows: Int = 3,
        /** 小于这个行数的重叠不当真：几行的巧合对齐比「没识别到」更危险。 */
        val minOverlapRows: Int = 16,
        /**
         * 命中带的最小纹理（0..255 的平均偏差，0 = 关掉守卫）。低于它 = 大片纯色，
         * 「重叠多少」没有唯一解（全白页任意偏移都"匹配"），退回硬接——
         * 宁可留一段重复，不可把内容裁掉。
         */
        val minBandVariation: Int = 4,
        val tolerance: Tolerance = Tolerance.Normal,
    )

    /** 行内抽样步长：按宽度归一，窄图逐像素、宽图隔几个抽一个。 */
    fun rowStep(width: Int): Int = (width / 60).coerceIn(1, 16)

    /**
     * 宽度是否在同一量级（智能拼接的前提）。**只要求宽度**：竖拼与行匹配的坐标系都只
     * 依赖宽度，高度可以不同（系统长截图 / 裁过的图和普通截图混拼是常态）。
     * 容许 [toleranceRatio] 的相对差（来源不同的截图可能差一两像素，内容仍可对齐）。
     */
    fun sameWidth(sizes: List<Pair<Int, Int>>, toleranceRatio: Float = 0.02f): Boolean {
        if (sizes.isEmpty()) return false
        val max = sizes.maxOf { it.first }
        val min = sizes.minOf { it.first }
        if (min <= 0) return false
        return max - min <= (max * toleranceRatio).toInt().coerceAtLeast(1)
    }

    /**
     * 共同工作宽度：匹配成本是行数的平方级，图里有超长截图（高宽比大）时按
     * [maxWorkHeight] 反推一个更小的宽度。**所有图必须用同一个宽度**——各图宽度
     * 不一致的话行坐标就对不齐了。下面的 [floor] 是精度下限（再窄行内样本太少）。
     */
    fun workWidthFor(
        sizes: List<Pair<Int, Int>>,
        preferred: Int = 480,
        maxWorkHeight: Int = 2400,
        floor: Int = 160,
    ): Int {
        if (sizes.isEmpty()) return preferred
        val maxAspect = sizes.maxOf { it.second.toFloat() / it.first }
        if (maxAspect <= 0f) return preferred
        return min(preferred, (maxWorkHeight / maxAspect).toInt().coerceAtLeast(floor))
    }

    /** 两行的平均色差（0..765）：抽样点上 |ΔR|+|ΔG|+|ΔB| 的平均值。 */
    fun rowDiff(a: IntArray, b: IntArray, aRow: Int, bRow: Int, width: Int, step: Int): Int {
        var sum = 0L
        var samples = 0
        var x = 0
        while (x < width) {
            val ca = a[aRow * width + x]
            val cb = b[bRow * width + x]
            sum += (abs(((ca shr 16) and 0xFF) - ((cb shr 16) and 0xFF)) +
                abs(((ca shr 8) and 0xFF) - ((cb shr 8) and 0xFF)) +
                abs((ca and 0xFF) - (cb and 0xFF))).toLong()
            samples++
            x += step
        }
        return if (samples == 0) 765 else (sum / samples).toInt()
    }

    /** 一行的纹理（抽样点相对本行亮度均值的平均偏差，0..255）：纯色行 ≈ 0。 */
    fun rowVariation(pixels: IntArray, row: Int, width: Int, step: Int): Int {
        var sum = 0L
        var samples = 0
        var x = 0
        while (x < width) {
            sum += luma(pixels[row * width + x]).toLong()
            samples++
            x += step
        }
        if (samples == 0) return 0
        val mean = sum / samples
        var deviation = 0L
        x = 0
        while (x < width) {
            deviation += abs(luma(pixels[row * width + x]) - mean).toLong()
            x += step
        }
        return (deviation / samples).toInt()
    }

    private fun luma(color: Int): Int {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (r * 30 + g * 59 + b * 11) / 100
    }

    /** 一段行区间的纹理（逐行 [rowVariation] 的平均，按 [stride] 抽行）。 */
    fun bandVariation(
        image: RowImage,
        startRow: Int,
        length: Int,
        step: Int,
        stride: Int = 4,
    ): Int {
        if (length <= 0) return 0
        var sum = 0L
        var rows = 0
        var row = startRow.coerceAtLeast(0)
        val end = (startRow + length).coerceAtMost(image.height)
        while (row < end) {
            sum += rowVariation(image.pixels, row, image.width, step).toLong()
            rows++
            row += stride.coerceAtLeast(1)
        }
        return if (rows == 0) 0 else (sum / rows).toInt()
    }

    /**
     * 顶部公共带：从 [startRow] 往下数连续匹配的行数，[assumed] 行直接计入
     * （状态栏那种「假定是公共头、不参与比对」的前缀）。
     */
    fun matchTop(
        prev: RowImage,
        next: RowImage,
        startRow: Int,
        assumed: Int,
        maxRowDiff: Int,
    ): Int {
        val width = prev.width
        val step = rowStep(width)
        val rows = min(prev.height, next.height)
        var row = startRow.coerceAtLeast(0)
        var matched = assumed.coerceAtLeast(0)
        while (row < rows) {
            if (rowDiff(prev.pixels, next.pixels, row, row, width, step) > maxRowDiff) break
            matched++
            row++
        }
        return matched
    }

    /** 底部公共带：从最后一行往上数连续匹配的行数。 */
    fun matchBottom(prev: RowImage, next: RowImage, maxRowDiff: Int): Int {
        val width = prev.width
        val step = rowStep(width)
        var row = min(prev.height, next.height) - 1
        var matched = 0
        while (row >= 0) {
            if (rowDiff(prev.pixels, next.pixels, row, row, width, step) > maxRowDiff) break
            matched++
            row--
        }
        return matched
    }

    /**
     * 相邻两图的滚动重叠行数：`prev` 的 `[H-foot-overlap, H-foot)` 与
     * `next` 的 `[contentTop, contentTop+overlap)` 全行匹配。
     * 从可能的最大重叠往小搜，返回第一个命中的（没有则 0）。
     */
    fun findOverlap(
        prev: RowImage,
        next: RowImage,
        foot: Int,
        contentTop: Int,
        maxRowDiff: Int,
        minOverlapRows: Int = 0,
        minBandVariation: Int = 0,
    ): Int {
        val width = prev.width
        val step = rowStep(width)
        val height = min(prev.height, next.height)
        val prevBottom = height - foot
        val maxOverlap = min(prevBottom, height - contentTop)
        val floor = minOverlapRows.coerceAtLeast(1)
        var overlap = maxOverlap
        while (overlap >= floor) {
            val score = candidateScore(prev, next, prevBottom, contentTop, overlap, step, maxRowDiff)
            if (score != null) {
                // 纹理守卫：大片纯色的「匹配」没有信息量，退回硬接
                if (minBandVariation > 0) {
                    val variation = bandVariation(prev, prevBottom - overlap, overlap, step)
                    if (variation < minBandVariation) return 0
                }
                return refine(prev, next, prevBottom, contentTop, overlap, floor, step, maxRowDiff, score)
            }
            overlap--
        }
        return 0
    }

    /**
     * 精修：在候选 ±[RefineRadius] 行里找平均差异最小的达标候选。
     * 上限取 `maxOverlap`（别越过搜索起点），下限取 `floor`。
     */
    private fun refine(
        prev: RowImage,
        next: RowImage,
        prevBottom: Int,
        contentTop: Int,
        candidate: Int,
        floor: Int,
        step: Int,
        maxRowDiff: Int,
        candidateScore: Int,
    ): Int {
        var best = candidate
        var bestScore = candidateScore
        for (delta in 1..RefineRadius) {
            val smaller = candidate - delta
            if (smaller >= floor) {
                val score = candidateScore(prev, next, prevBottom, contentTop, smaller, step, maxRowDiff)
                if (score != null && score < bestScore) {
                    best = smaller
                    bestScore = score
                }
            }
            val larger = candidate + delta
            val maxOverlap = min(prevBottom, prev.height - contentTop)
            if (larger <= maxOverlap) {
                val score = candidateScore(prev, next, prevBottom, contentTop, larger, step, maxRowDiff)
                if (score != null && score < bestScore) {
                    best = larger
                    bestScore = score
                }
            }
        }
        return best
    }

    /**
     * 候选评分：**快筛通过 + 全行精验达标**才有分，分 = 带内平均逐行差异（越小越准）。
     *
     * 两遍：① **抽样行快筛**——按 [FastVerifyStride] 抽行，累计平均超阈值即弃，
     * 挡掉绝大多数错位候选（一个候选通常第一行就被挡回）；② **全行精验**——
     * 带内**平均**必须在阈值内，且超阈值的行不得超过 [badRowBudget]（约 5%，至少 1 行）：
     * 真实截图偶尔有一行不同（光标闪、图还没加载完），整条枪毙会把真重叠放走。
     */
    private fun candidateScore(
        prev: RowImage,
        next: RowImage,
        prevBottom: Int,
        contentTop: Int,
        overlap: Int,
        step: Int,
        maxRowDiff: Int,
    ): Int? {
        val aStart = prevBottom - overlap
        val bStart = contentTop
        val length = overlap
        val width = prev.width
        val stride = FastVerifyStride.coerceAtMost(length).coerceAtLeast(1)
        var sum = 0L
        var samples = 0
        var row = 0
        while (row < length) {
            val diff = rowDiff(prev.pixels, next.pixels, aStart + row, bStart + row, width, step)
            sum += diff.toLong()
            samples++
            if (sum / samples > maxRowDiff) return null
            row += stride
        }
        // 抽样通过 → 全行精验：平均达标 + 超标行数在预算内
        sum = 0
        var badRows = 0
        row = 0
        while (row < length) {
            val diff = rowDiff(prev.pixels, next.pixels, aStart + row, bStart + row, width, step)
            if (diff > maxRowDiff && ++badRows > badRowBudget(length)) return null
            sum += diff.toLong()
            row++
        }
        if (length <= 0) return null
        val average = (sum / length).toInt()
        return if (average <= maxRowDiff) average else null
    }

    /** 允许「不一样」的行数预算：约 5%，至少 1 行（个别噪声行的容错）。 */
    private fun badRowBudget(length: Int): Int = (length / 20).coerceAtLeast(1)

    /**
     * 全量扫描：公共头尾（所有图对取最小）→ 额外头（第 2 张起才有的头栏，只在 >2 张时算）
     * → 相邻重叠（从最大往小搜，找不到记 0）。
     */
    fun scan(images: List<RowImage>, options: Options): Bands {
        require(images.isNotEmpty()) { "scan 至少要一张图" }
        if (images.size == 1) return Bands(0, 0, 0, emptyList())
        val height = min(images[0].height, images[1].height)
        val maxRowDiff = options.tolerance.maxRowDiff
        val assumed = options.statusBarRows.coerceIn(0, min(height, images[0].height))

        var head = height
        var foot = height
        for (i in 1 until images.size) {
            head = min(head, matchTop(images[0], images[i], assumed, assumed, maxRowDiff))
            foot = min(foot, matchBottom(images[0], images[i], maxRowDiff))
        }
        if (head <= options.minBandRows) head = 0
        if (foot <= options.minBandRows) foot = 0

        // 额外头：图像 1 与后续各图从「公共头之后」开始对齐的那一段（原版同口径）
        var extraHead = 0
        if (images.size > 2 && head > 0) {
            var best = height
            for (i in 2 until images.size) {
                best = min(best, matchTop(images[1], images[i], head, 0, maxRowDiff))
            }
            extraHead = if (best <= options.minBandRows) 0 else best
        }

        val overlaps = ArrayList<Int>(images.size - 1)
        for (i in 1 until images.size) {
            overlaps += findOverlap(
                prev = images[i - 1],
                next = images[i],
                foot = foot,
                contentTop = head + extraHead,
                maxRowDiff = maxRowDiff,
                minOverlapRows = options.minOverlapRows,
                minBandVariation = options.minBandVariation,
            )
        }
        return Bands(head = head, foot = foot, extraHead = extraHead, overlaps = overlaps)
    }

    /**
     * 扫描结论 → 每张图保留的行区间。
     * 第一张保头（状态栏只在长图最上面出现一次）、最后一张保尾（真实页尾），
     * 中间各张去头去尾；相邻图的重叠归**后一张**的头（那几行是重复内容）。
     * 任何一张算空了返回 null——调用方退回硬接，不拿半张图去拼。
     */
    fun crops(bands: Bands, heights: List<Int>): List<KeepRows>? {
        if (heights.isEmpty()) return null
        val keeps = ArrayList<KeepRows>(heights.size)
        for (i in heights.indices) {
            val height = heights[i]
            val top = when {
                i == 0 -> 0
                else -> bands.head + bands.extraHead + bands.overlaps.getOrElse(i - 1) { 0 }
            }
            val bottom = if (i == heights.size - 1) height else height - bands.foot
            if (top < 0 || bottom > height || bottom - top <= 0) return null
            keeps += KeepRows(top = top, bottom = bottom)
        }
        return keeps
    }
}
