package edu.jxslu.schedule.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import edu.jxslu.schedule.domain.ThemePalette

@Composable
fun JuwTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    palette: ThemePalette = ThemePalette.Brand,
    content: @Composable () -> Unit,
) {
    // 优先级：动态取色（Material You，Android 12+）> 内置配色 > 品牌青兜底
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        else -> paletteScheme(palette, darkTheme)
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = JuwTypography,
    ) {
        // 语气色（成功/警告）M3 无对应角色，随深浅色在这里统一提供（提示卡用，见 AppNotice）
        ProvideSemanticColors(darkTheme) { content() }
    }
}
