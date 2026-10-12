package edu.jxslu.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 智能拼接的匹配核（DESIGN §4.47，口径拆自 PPIICC）：
 * - 公共头/尾 = 所有图对连续相同行的最小值（保守），状态栏行按假定计入；
 * - 滚动重叠从大往小搜、逐行验证，命中取最大，找不到记 0（退回硬接）；
 * - 额外头只在 >2 张时算；
 * - 折叠成保留区间时第一张保头、最后一张保尾，算空了整单作废（返回 null）。
 *
 * 用合成的「虚拟长页 + 不同滚动窗口」造数据：像素可控，答案可解析地算出来，
 * 不依赖任何真实截图。
 */
class StitchScanTest {

    private val width = 64
    private val height = 600

    /**
     * 一行的像素：按 (虚拟页行号, 列) 取色，同一虚拟行在所有窗口里逐像素一致。
     *
     * **必须是非周期函数**：最早写成 `(row * 7 + col) and 0xFF` 这类线性取模，
     * 256 行一循环，于是「错开 446 行」和「错开 190 行」会巧合对齐，重叠检测返回 448。
     * 这里用空间哈希（Kraay 经典常数）打散。
     */
    private fun rowColor(virtualRow: Int, col: Int): Int {
        var h = virtualRow * 73_856_093 xor col * 19_349_663
        h = h xor (h ushr 13)
        h *= -0x7ee3623b // 0x811c9dc5
        return (0xFF shl 24) or (h and 0xFFFFFF)
    }

    /**
     * 造一张「截图」：
     * - 顶部 [statusRows] 行是状态栏（各图不同：模拟时钟差异）；
     * - 接着 [headRows] 行是公共头（标题栏，各图相同）；
     * - 中部是虚拟长页的窗口，起点 [contentStart]；
     * - 底部 [footRows] 行是公共尾（Tab 栏，各图相同）。
     */
    private fun screenshot(
        statusRows: Int,
        headRows: Int,
        contentStart: Int,
        footRows: Int,
        clockSeed: Int = 0,
    ): StitchScan.RowImage {
        val contentRows = height - statusRows - headRows - footRows
        val pixels = IntArray(width * height)
        for (row in 0 until height) {
            for (col in 0 until width) {
                val color = when {
                    row < statusRows -> rowColor(10_000 + clockSeed, col) // 每张不同
                    row < statusRows + headRows -> rowColor(100, col)     // 公共头
                    row < statusRows + headRows + contentRows ->
                        rowColor(contentStart + (row - statusRows - headRows), col)
                    else -> rowColor(99_999, col)                          // 公共尾
                }
                pixels[row * width + col] = color
            }
        }
        return StitchScan.RowImage(pixels, width, height)
    }

    @Test
    fun `公共头尾与滚动重叠一次全中`() {
        val statusRows = 24
        val headRows = 56
        val footRows = 30
        val contentRows = height - statusRows - headRows - footRows // 490
        // 第二张向下滚了 300 行 → 重叠 = 490 - 300 = 190
        val first = screenshot(statusRows, headRows, contentStart = 0, footRows = footRows, clockSeed = 0)
        val second = screenshot(statusRows, headRows, contentStart = 300, footRows = footRows, clockSeed = 1)

        val bands = StitchScan.scan(
            listOf(first, second),
            StitchScan.Options(statusBarRows = statusRows, minBandRows = 3),
        )

        assertEquals("头 = 状态栏 + 公共标题栏", statusRows + headRows, bands.head)
        assertEquals("尾 = 公共 Tab 栏", footRows, bands.foot)
        assertEquals("滚动重叠", 190, bands.overlaps.single())

        val keeps = StitchScan.crops(bands, listOf(height, height))!!
        // 第一张：保头、去尾
        assertEquals(StitchScan.KeepRows(top = 0, bottom = height - footRows), keeps[0])
        // 最后一张：去头（含重叠），保尾
        assertEquals(
            StitchScan.KeepRows(top = statusRows + headRows + 190, bottom = height),
            keeps[1],
        )
    }

    @Test
    fun `内容完全不同时重叠记 0`() {
        val first = screenshot(24, 56, contentStart = 0, footRows = 30, clockSeed = 0)
        // 换一个「完全不同」的虚拟页：直接把内容行整体反相
        val pixels = first.pixels.copyOf()
        for (row in 80 until height - 30) {
            for (col in 0 until width) {
                pixels[row * width + col] = pixels[row * width + col] xor 0xFFFFFF
            }
        }
        val second = StitchScan.RowImage(pixels, width, height)

        val bands = StitchScan.scan(
            listOf(first, second),
            StitchScan.Options(statusBarRows = 24, minBandRows = 3),
        )
        assertEquals("头仍然是公共的", 80, bands.head)
        assertEquals(0, bands.overlaps.single())
    }

    @Test
    fun `三张图时第二张起出现的额外头被识别`() {
        val statusRows = 24
        val headRows = 56
        val footRows = 30
        val extraRows = 20
        val contentRows = height - statusRows - headRows - footRows
        // 第一张：公共头之后直接是内容
        val first = screenshot(statusRows, headRows, contentStart = 0, footRows = footRows, clockSeed = 0)
        // 第二、三张：公共头之后多 20 行「滚动后才出现的标题栏」，再接内容
        fun withExtra(contentStart: Int, clockSeed: Int): StitchScan.RowImage {
            val base = screenshot(statusRows, headRows, contentStart, footRows, clockSeed)
            for (row in 0 until extraRows) {
                for (col in 0 until width) {
                    base.pixels[(statusRows + headRows + row) * width + col] = rowColor(500, col)
                }
            }
            return base
        }
        val second = withExtra(contentStart = 100, clockSeed = 1)
        val third = withExtra(contentStart = 200, clockSeed = 2)

        val bands = StitchScan.scan(
            listOf(first, second, third),
            StitchScan.Options(statusBarRows = statusRows, minBandRows = 3),
        )
        assertEquals(statusRows + headRows, bands.head)
        // 第二、三张的额外头：公共头之后 20 行一样，第 21 行开始内容不同
        assertTrue("额外头应当被识别出来（实际 ${bands.extraHead}）", bands.extraHead >= extraRows)
        assertEquals(footRows, bands.foot)
    }

    @Test
    fun `容差档位决定噪声能不能过`() {
        val clean = screenshot(0, 0, contentStart = 0, footRows = 0)
        // 每通道 ±40 的噪声 → 每像素约 120 的色差
        val noisy = StitchScan.RowImage(clean.pixels.copyOf(), width, height)
        for (i in noisy.pixels.indices) {
            val c = noisy.pixels[i]
            noisy.pixels[i] = (0xFF shl 24) or
                ((((c shr 16) and 0xFF) - 40).coerceAtLeast(0) shl 16) or
                ((((c shr 8) and 0xFF) - 40).coerceAtLeast(0) shl 8) or
                (((c and 0xFF) - 40).coerceAtLeast(0))
        }
        val strict = StitchScan.scan(
            listOf(clean, noisy),
            StitchScan.Options(minBandRows = 3, tolerance = StitchScan.Tolerance.Strict),
        )
        val loose = StitchScan.scan(
            listOf(clean, noisy),
            StitchScan.Options(minBandRows = 3, tolerance = StitchScan.Tolerance.Loose),
        )
        assertEquals("严格档：噪声图不该判成公共带", 0, strict.head)
        assertEquals("宽松档：噪声图仍算公共带", height, loose.head)
    }

    @Test
    fun `单张图没有拼接结论`() {
        val one = screenshot(24, 56, contentStart = 0, footRows = 30)
        val bands = StitchScan.scan(listOf(one), StitchScan.Options())
        assertEquals(StitchScan.Bands(0, 0, 0, emptyList()), bands)
        assertEquals(
            listOf(StitchScan.KeepRows(0, height)),
            StitchScan.crops(bands, listOf(height)),
        )
    }

    @Test
    fun `容差档位顺序与界面选项一一对应`() {
        // 拼接页的选项列表按 entries 顺序排（严格 / 标准 / 宽松），顺序变了界面就错位
        assertEquals(
            listOf(
                StitchScan.Tolerance.Strict,
                StitchScan.Tolerance.Normal,
                StitchScan.Tolerance.Loose,
            ),
            StitchScan.Tolerance.entries.toList(),
        )
        // 档位之间必须真的拉开距离（严格 < 标准 < 宽松），否则「调节没手感」
        assertTrue(StitchScan.Tolerance.Strict.maxRowDiff < StitchScan.Tolerance.Normal.maxRowDiff)
        assertTrue(StitchScan.Tolerance.Normal.maxRowDiff < StitchScan.Tolerance.Loose.maxRowDiff)
    }

    @Test
    fun `纹理守卫挡住纯色带的伪匹配`() {
        // 全白页：行内也没有任何纹理，任意偏移都「匹配」，重叠没有唯一解
        val uniform = IntArray(width * height) { 0xFFFFFFFF.toInt() }
        val a = StitchScan.RowImage(uniform.copyOf(), width, height)
        val b = StitchScan.RowImage(uniform.copyOf(), width, height)
        // 守卫开着（默认）：退回硬接
        assertEquals(
            0,
            StitchScan.findOverlap(a, b, foot = 0, contentTop = 0, maxRowDiff = 150, minBandVariation = 4),
        )
        // 守卫关掉：原样返回最大可能重叠（对照组，证明守卫真的在起作用）
        assertEquals(
            height,
            StitchScan.findOverlap(a, b, foot = 0, contentTop = 0, maxRowDiff = 150, minBandVariation = 0),
        )
    }

    @Test
    fun `小于下限的碎片重叠不当真`() {
        // 真实重叠 20 行，下限设 100 → 不认
        val first = screenshot(0, 0, contentStart = 0, footRows = 0)
        val second = screenshot(0, 0, contentStart = height - 20, footRows = 0)
        assertEquals(
            20,
            StitchScan.findOverlap(
                first, second, foot = 0, contentTop = 0,
                maxRowDiff = 150, minOverlapRows = 1, minBandVariation = 4,
            ),
        )
        assertEquals(
            0,
            StitchScan.findOverlap(
                first, second, foot = 0, contentTop = 0,
                maxRowDiff = 150, minOverlapRows = 100, minBandVariation = 4,
            ),
        )
    }

    @Test
    fun `个别噪声行不枪毙整条重叠`() {
        val statusRows = 24
        val headRows = 56
        val footRows = 30
        val contentTop = statusRows + headRows
        val first = screenshot(statusRows, headRows, contentStart = 0, footRows = footRows, clockSeed = 0)
        val second = screenshot(statusRows, headRows, contentStart = 300, footRows = footRows, clockSeed = 1)
        // 真重叠 190 行；把其中一行换成完全不同的行（模拟光标闪 / 图没加载完）
        for (col in 0 until width) {
            second.pixels[(contentTop + 50) * width + col] = rowColor(999_999, col)
        }
        assertEquals(
            190,
            StitchScan.findOverlap(
                first, second, foot = footRows, contentTop = contentTop,
                maxRowDiff = 150, minOverlapRows = 16, minBandVariation = 4,
            ),
        )
        // 反过来：一整条重叠里每两行就换一行（远超 5% 的坏行预算）→ 不能算命中
        val noisy = screenshot(statusRows, headRows, contentStart = 300, footRows = footRows, clockSeed = 1)
        var row = 0
        while (row < height) {
            for (col in 0 until width) {
                noisy.pixels[row * width + col] = rowColor(888_888 + row, col)
            }
            row += 2
        }
        assertEquals(
            0,
            StitchScan.findOverlap(
                first, noisy, foot = footRows, contentTop = contentTop,
                maxRowDiff = 150, minOverlapRows = 16, minBandVariation = 4,
            ),
        )
    }

    @Test
    fun `保留区间算空了就整单作废`() {
        // head + extraHead + overlap 超过一张图的高度 → 第二张保留区间为空
        val bands = StitchScan.Bands(
            head = 300,
            foot = 0,
            extraHead = 100,
            overlaps = listOf(250),
        )
        assertNull(StitchScan.crops(bands, listOf(600, 600)))
    }

    @Test
    fun `宽度一致即可智能拼接高度可以不同`() {
        // 同宽不同高（长截图 / 裁过的图和普通截图混拼）——2026-10-12 用户报的就是这种
        assertTrue(StitchScan.sameWidth(listOf(1080 to 2400, 1080 to 8000)))
        // 差一两像素的来源差异也放行（相对 2%）
        assertTrue(StitchScan.sameWidth(listOf(1080 to 2400, 1082 to 2400)))
        // 宽度真的不同（不同设备/分辨率）：没有同一套行坐标系
        assertFalse(StitchScan.sameWidth(listOf(1080 to 2400, 1200 to 2400)))
        assertFalse(StitchScan.sameWidth(listOf(1080 to 2400, 720 to 1280)))
        assertFalse(StitchScan.sameWidth(emptyList()))
    }

    @Test
    fun `高度不同时最后一张按自己的高度保尾`() {
        val bands = StitchScan.Bands(head = 80, foot = 30, extraHead = 0, overlaps = listOf(190))
        val keeps = StitchScan.crops(bands, listOf(600, 800))!!
        // 第一张：保头去尾（尾按自己的高度）
        assertEquals(StitchScan.KeepRows(top = 0, bottom = 570), keeps[0])
        // 最后一张：去头（含重叠）保尾（尾按它自己的 800）
        assertEquals(StitchScan.KeepRows(top = 270, bottom = 800), keeps[1])
    }

    @Test
    fun `超长截图把共同工作宽度收窄`() {
        // 普通竖屏截图（1:2.2）：用首选宽度
        assertEquals(480, StitchScan.workWidthFor(listOf(1080 to 2400, 1080 to 2400)))
        // 一张 1:7.4 的长截图：按最大高宽比反推（2400 / 7.4 ≈ 324），所有图共用一个宽度
        val withLong = listOf(1080 to 2400, 1080 to 8000)
        val narrowed = StitchScan.workWidthFor(withLong)
        assertTrue("长截图在场时必须收窄（实际 $narrowed）", narrowed < 480)
        assertTrue("收窄到 324 上下", narrowed in 320..330)
        // 极端比例：不跌破精度下限
        assertEquals(160, StitchScan.workWidthFor(listOf(1080 to 21600)))
        // 空输入回首选
        assertEquals(480, StitchScan.workWidthFor(emptyList()))
    }

    @Test
    fun `精修把差一两行的候选拉回真对齐`() {
        // 真实内容在纵向上是「局部平滑」的（自然图像、UI 都一样）：错开一两行也能过阈值，
        // 「取从大往小第一个达标的」就会差一两行——真机实测那对 1080×2400 截图，
        // 真对齐平均 8.5、错一行 14.9、错两行 27.9（见 .re-tmp 的 probe4）。
        // 这里用「随机行 + 纵向 3 行盒式模糊」造同款地形：真对齐 0 分，越错分越高。
        val smooth = IntArray(width * height)
        for (row in 0 until height) {
            for (col in 0 until width) smooth[row * width + col] = rowColor(row * 31, col)
        }
        val blurred = IntArray(width * height)
        for (row in 0 until height) {
            for (col in 0 until width) {
                var r = 0
                var g = 0
                var b = 0
                for (d in -1..1) {
                    val src = ((row + d).coerceIn(0, height - 1) * width) + col
                    r += (smooth[src] shr 16) and 0xFF
                    g += (smooth[src] shr 8) and 0xFF
                    b += smooth[src] and 0xFF
                }
                blurred[row * width + col] =
                    (0xFF shl 24) or ((r / 3) shl 16) or ((g / 3) shl 8) or (b / 3)
            }
        }
        fun window(start: Int) = IntArray(width * height) { i ->
            blurred[((start + i / width).coerceIn(0, height - 1)) * width + (i % width)]
        }
        val scroll = 150
        val first = StitchScan.RowImage(window(0), width, height)
        val second = StitchScan.RowImage(window(scroll), width, height)
        // 无头无尾：真重叠 = 高度 − 滚动行数
        val exact = height - scroll
        assertEquals(
            "精修后必须落在真对齐上（差一行就是接缝缺一条）",
            exact,
            StitchScan.findOverlap(
                first, second, foot = 0, contentTop = 0,
                maxRowDiff = 150, minOverlapRows = 16, minBandVariation = 0,
            ),
        )
    }
}
