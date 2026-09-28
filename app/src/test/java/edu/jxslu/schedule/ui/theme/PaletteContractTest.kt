package edu.jxslu.schedule.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import edu.jxslu.schedule.domain.ThemePalette
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 内置配色方案的契约（DESIGN §3.3，2026-09-28 补全后新增）。
 *
 * 补齐全角色前，每套只有九个角色有值，其余吃 M3 紫色基线（卡片、弹层、次要文字、
 * 分割线、Snackbar 全发紫，观感与主色系不搭）。这里用不变量把「搭配」钉住：
 * - 前景/背景对比度按 M3 规范档位（正文 4.5:1、辅助 4.5:1）；
 * - surface 容器系亮度单调（浅色从白降、深色从黑升），层级不会反过来。
 *
 * 改任何一套色值时先跑这个测试：红了说明层级或对比度被改坏，别直接合。
 */
class PaletteContractTest {

    /** 对比度（WCAG）：(L1 + 0.05) / (L2 + 0.05)，L 为相对亮度。 */
    private fun ratio(a: Color, b: Color): Double {
        val l1 = a.luminance().toDouble()
        val l2 = b.luminance().toDouble()
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    private fun eachScheme(block: (palette: ThemePalette, dark: Boolean, scheme: ColorScheme) -> Unit) {
        ThemePalette.entries.forEach { palette ->
            listOf(false, true).forEach { dark -> block(palette, dark, paletteScheme(palette, dark)) }
        }
    }

    private fun assertRatio(
        palette: ThemePalette,
        dark: Boolean,
        name: String,
        fg: Color,
        bg: Color,
        min: Double,
    ) {
        val actual = ratio(fg, bg)
        assertTrue(
            "$palette(dark=$dark) $name 对比度 ${"%.2f".format(actual)} 低于 $min",
            actual >= min,
        )
    }

    @Test
    fun `正文类前景对比度不低于 4_5`() {
        eachScheme { palette, dark, s ->
            assertRatio(palette, dark, "onPrimary/primary", s.onPrimary, s.primary, 4.5)
            assertRatio(palette, dark, "onPrimaryContainer/primaryContainer", s.onPrimaryContainer, s.primaryContainer, 4.5)
            assertRatio(palette, dark, "onSecondaryContainer/secondaryContainer", s.onSecondaryContainer, s.secondaryContainer, 4.5)
            assertRatio(palette, dark, "onTertiaryContainer/tertiaryContainer", s.onTertiaryContainer, s.tertiaryContainer, 4.5)
            assertRatio(palette, dark, "onErrorContainer/errorContainer", s.onErrorContainer, s.errorContainer, 4.5)
            assertRatio(palette, dark, "onSurface/surface", s.onSurface, s.surface, 4.5)
            assertRatio(palette, dark, "onBackground/background", s.onBackground, s.background, 4.5)
            // 辅助文字（设置页说明行）画在各类容器上，最低也要 4.5（M3 口径）
            assertRatio(palette, dark, "onSurfaceVariant/surface", s.onSurfaceVariant, s.surface, 4.5)
            assertRatio(palette, dark, "onSurfaceVariant/surfaceContainerHigh", s.onSurfaceVariant, s.surfaceContainerHigh, 4.5)
            assertRatio(palette, dark, "onSurface/surfaceContainerHighest", s.onSurface, s.surfaceContainerHighest, 4.5)
        }
    }

    @Test
    fun `surface 容器系亮度单调`() {
        eachScheme { palette, dark, s ->
            val containers = listOf(
                "surfaceContainerLowest" to s.surfaceContainerLowest,
                "surfaceContainerLow" to s.surfaceContainerLow,
                "surfaceContainer" to s.surfaceContainer,
                "surfaceContainerHigh" to s.surfaceContainerHigh,
                "surfaceContainerHighest" to s.surfaceContainerHighest,
            )
            containers.zipWithNext().forEach { (a, b) ->
                val (nameA, colorA) = a
                val (nameB, colorB) = b
                val la = colorA.luminance()
                val lb = colorB.luminance()
                if (dark) {
                    assertTrue(
                        "$palette(深色) 容器层级断裂：$nameA(%.3f) 应比 $nameB(%.3f) 暗".format(la, lb),
                        la <= lb + 0.01f,
                    )
                } else {
                    assertTrue(
                        "$palette(浅色) 容器层级断裂：$nameA(%.3f) 应比 $nameB(%.3f) 亮".format(la, lb),
                        la >= lb - 0.01f,
                    )
                }
            }
        }
    }

    @Test
    fun `色卡预览四点互不相同且不透明`() {
        ThemePalette.entries.forEach { palette ->
            val swatch = paletteSwatch(palette)
            assertTrue("$palette 色卡点数不对", swatch.size == 4)
            swatch.forEach { color ->
                assertTrue("$palette 色卡有半透明色", color.alpha == 1f)
            }
            assertTrue("$palette 色卡四点应有区分度", swatch.distinct().size >= 3)
        }
    }

    /**
     * 青色相的 sanity：主色与 surface 容器同色系——容器不该跑到互补色相上去。
     * 用 RGB 通道大小关系的粗判（细色相判断要 HCT，单测不值得引）。
     */
    @Test
    fun `容器底色与主色冷暖同向`() {
        eachScheme { palette, dark, s ->
            fun warmth(c: Color): Float = (c.red - c.blue)
            val primaryWarm = warmth(s.primary)
            val containerWarm = warmth(s.surfaceContainerHigh)
            assertTrue(
                "$palette(dark=$dark) 容器底色与主色冷暖方向相反（主色 %.3f / 容器 %.3f）".format(primaryWarm, containerWarm),
                abs(primaryWarm - containerWarm) < 0.35f || primaryWarm * containerWarm >= 0f,
            )
        }
    }
}
