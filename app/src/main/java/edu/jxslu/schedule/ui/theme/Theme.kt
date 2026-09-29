package edu.jxslu.schedule.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
        // 窗口级默认内容色。`Text` / `Icon` 不写 `color` / `tint` 时读的就是它，
        // 而它唯一的正式提供方是 M3 的 `Surface`（`Scaffold` / `Card` / `Button` 内部都有）。
        // 页面根节点只用 `Modifier.background` 画底色时没人提供，`Text` 会落回
        // `LocalContentColor` 的默认值**纯黑**——浅色主题下看不出，深色主题下正文糊进背景
        // （2026-09-29 首启引导页的标题就是这么丢的）。这里给整个窗口兜一个 onBackground，
        // 各 `Surface` / `Scaffold` 仍按自己的底色覆盖，互不冲突。
        CompositionLocalProvider(LocalContentColor provides colorScheme.onBackground) {
            // 语气色（成功/警告）M3 无对应角色，随深浅色在这里统一提供（提示卡用，见 AppNotice）
            ProvideSemanticColors(darkTheme) { content() }
        }
    }
}
