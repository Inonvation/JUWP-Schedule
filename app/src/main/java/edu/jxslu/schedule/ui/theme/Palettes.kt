package edu.jxslu.schedule.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import edu.jxslu.schedule.domain.ThemePalette

/**
 * 内置配色方案（DESIGN §3.3 观感）。
 *
 * 角色口径与 Color.kt 的品牌青完全一致：只填 primary / onPrimary / primaryContainer /
 * onPrimaryContainer / secondary / background / onBackground / surface / onSurface 九个角色，
 * tertiary、error、outline 等维持 M3 基线兜底（现状即如此）。色值按 Material 色调角色取：
 * 主色浅色 tone 40 / 深色 tone 80，容器 90/30，on 容器 10/90——保证各配色下白字/正文对比度同档。
 *
 * 品牌青直接复用 [Color.kt] 的常量（唯一来源）；其余配色新增在这里，不要回写 Color.kt。
 */
internal fun paletteScheme(palette: ThemePalette, dark: Boolean): ColorScheme = when (palette) {
    ThemePalette.Brand -> if (dark) BrandDark else BrandLight
    ThemePalette.SkyBlue -> if (dark) SkyBlueDark else SkyBlueLight
    ThemePalette.DuskPurple -> if (dark) DuskPurpleDark else DuskPurpleLight
    ThemePalette.Sakura -> if (dark) SakuraDark else SakuraLight
    ThemePalette.Sunset -> if (dark) SunsetDark else SunsetLight
    ThemePalette.Forest -> if (dark) ForestDark else ForestLight
}

/** 色卡弹层的预览色点：主色 / 容器 / 次要 / 底色（浅色系，色相一目了然）。 */
internal fun paletteSwatch(palette: ThemePalette): List<Color> {
    val light = paletteScheme(palette, dark = false)
    return listOf(light.primary, light.primaryContainer, light.secondary, light.background)
}

private val BrandLight = lightColorScheme(
    primary = PrimaryLight,
    onPrimary = OnPrimaryLight,
    primaryContainer = PrimaryContainerLight,
    onPrimaryContainer = OnPrimaryContainerLight,
    secondary = SecondaryLight,
    background = BackgroundLight,
    onBackground = OnBackgroundLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
)

private val BrandDark = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = OnPrimaryDark,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryContainerDark,
    secondary = SecondaryDark,
    background = BackgroundDark,
    onBackground = OnBackgroundDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
)

private val SkyBlueLight = lightColorScheme(
    primary = Color(0xFF3B5EA8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD9E2FF),
    onPrimaryContainer = Color(0xFF001945),
    secondary = Color(0xFF575E71),
    background = Color(0xFFF9F9FF),
    onBackground = Color(0xFF191C22),
    surface = Color(0xFFFCFCFF),
    onSurface = Color(0xFF191C22),
)

private val SkyBlueDark = darkColorScheme(
    primary = Color(0xFFADC6FF),
    onPrimary = Color(0xFF002D6F),
    primaryContainer = Color(0xFF1E4588),
    onPrimaryContainer = Color(0xFFD9E2FF),
    secondary = Color(0xFFBFC6DC),
    background = Color(0xFF0F1419),
    onBackground = Color(0xFFDFE2EA),
    surface = Color(0xFF12151C),
    onSurface = Color(0xFFDFE2EA),
)

private val DuskPurpleLight = lightColorScheme(
    primary = Color(0xFF6750A4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE9DDFF),
    onPrimaryContainer = Color(0xFF220F5D),
    secondary = Color(0xFF625B71),
    background = Color(0xFFF9F5FF),
    onBackground = Color(0xFF1C1B20),
    surface = Color(0xFFFCF9FF),
    onSurface = Color(0xFF1C1B20),
)

private val DuskPurpleDark = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = Color(0xFFE9DDFF),
    secondary = Color(0xFFCCC2DC),
    background = Color(0xFF121016),
    onBackground = Color(0xFFE5E1E9),
    surface = Color(0xFF16141B),
    onSurface = Color(0xFFE5E1E9),
)

private val SakuraLight = lightColorScheme(
    primary = Color(0xFF984061),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFD9E2),
    onPrimaryContainer = Color(0xFF3E001D),
    secondary = Color(0xFF7D5260),
    background = Color(0xFFFDF8F8),
    onBackground = Color(0xFF201A1B),
    surface = Color(0xFFFFF9F9),
    onSurface = Color(0xFF201A1B),
)

private val SakuraDark = darkColorScheme(
    primary = Color(0xFFFFB1C8),
    onPrimary = Color(0xFF5E1133),
    primaryContainer = Color(0xFF7B2951),
    onPrimaryContainer = Color(0xFFFFD9E2),
    secondary = Color(0xFFE0B8C2),
    background = Color(0xFF181113),
    onBackground = Color(0xFFEBE0E1),
    surface = Color(0xFF1C1415),
    onSurface = Color(0xFFEBE0E1),
)

private val SunsetLight = lightColorScheme(
    primary = Color(0xFF9A4A00),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDBC7),
    onPrimaryContainer = Color(0xFF331200),
    secondary = Color(0xFF75594B),
    background = Color(0xFFFAF4EF),
    onBackground = Color(0xFF201A17),
    surface = Color(0xFFFDF9F7),
    onSurface = Color(0xFF201A17),
)

private val SunsetDark = darkColorScheme(
    primary = Color(0xFFFFB877),
    onPrimary = Color(0xFF4F2600),
    primaryContainer = Color(0xFF6F3900),
    onPrimaryContainer = Color(0xFFFFDBC7),
    secondary = Color(0xFFE4BFAE),
    background = Color(0xFF17120E),
    onBackground = Color(0xFFEEE2DA),
    surface = Color(0xFF1B1613),
    onSurface = Color(0xFFEEE2DA),
)

private val ForestLight = lightColorScheme(
    primary = Color(0xFF38693C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB9F0BB),
    onPrimaryContainer = Color(0xFF00210A),
    secondary = Color(0xFF52634F),
    background = Color(0xFFF5FAF3),
    onBackground = Color(0xFF181D17),
    surface = Color(0xFFFAFCF8),
    onSurface = Color(0xFF181D17),
)

private val ForestDark = darkColorScheme(
    primary = Color(0xFF9DD49C),
    onPrimary = Color(0xFF003912),
    primaryContainer = Color(0xFF205227),
    onPrimaryContainer = Color(0xFFB9F0BB),
    secondary = Color(0xFFB9CCB3),
    background = Color(0xFF0F140F),
    onBackground = Color(0xFFDFE4DA),
    surface = Color(0xFF131812),
    onSurface = Color(0xFFDFE4DA),
)
