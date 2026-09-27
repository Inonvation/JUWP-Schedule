package edu.jxslu.schedule.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider

/**
 * 校园卡 / 电费小组件共享的渲染小件（DESIGN §3.6 三条目改版）。
 *
 * 配色口径：底与文字走 `GlanceTheme`（深浅色跟随系统）；胶囊按钮用品牌青固定色——
 * 两个深浅模式下 #0F7C7C 上白字对比度都够，不必引入动态色依赖。
 */
internal val WidgetAccentColor: ColorProvider = ColorProvider(Color(0xFF0F7C7C))

/** 圆角胶囊按钮（「出示付款码」/「用电统计」/「去设置」）：实心底 + 白字。 */
@Composable
internal fun WidgetPill(text: String) {
    Box(
        modifier = GlanceModifier
            .background(WidgetAccentColor)
            .cornerRadius(14.dp)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = TextStyle(
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = ColorProvider(Color.White),
            ),
            maxLines = 1,
        )
    }
}

/** 头部行：条目名 + 「更新 HH:mm」（取不到时刻就只给条目名）。 */
@Composable
internal fun WidgetHeaderRow(title: String, updatedLabel: String?) {
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        Text(
            text = title,
            style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurfaceVariant),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight(),
        )
        if (updatedLabel != null) {
            Text(
                text = "更新 $updatedLabel",
                style = TextStyle(fontSize = 10.sp, color = GlanceTheme.colors.onSurfaceVariant),
                maxLines = 1,
            )
        }
    }
}

/** 大数字的字号分档：2×2（实测宽约 110dp）放不下 30sp 的 ¥128.45，降一档。 */
internal fun bigNumberFontSp(widthDp: Float): Int = if (widthDp < 150f) 24 else 30
