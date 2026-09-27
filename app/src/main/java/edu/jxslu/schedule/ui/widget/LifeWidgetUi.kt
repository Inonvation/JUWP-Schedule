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
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider

/**
 * 生活小组件（校园卡 + 电费合并卡）的渲染小件（DESIGN §3.6 二条目改版）。
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

/** 大数字的字号分档：窄卡片放不下 30sp 的 ¥128.45，降一档。 */
internal fun bigNumberFontSp(widthDp: Float): Int = if (widthDp < 150f) 24 else 30

/**
 * 窄档阈值（dp）。真机实测（Redmi K70 / 澎湃OS，density 420）：2 格宽的小组件只有
 * 150dp，4 格宽 344dp。低于此值走窄档排版。
 *
 * 取 200 而不是 150：国产 ROM 的格宽会浮动，宁可提前切窄档，也不要让宽档那套
 * 「按钮沉底」在接近 2 格的尺寸上把空白全留在卡片中间。
 */
internal const val WidgetNarrowWidthDp = 200f

/** 是否窄档（2 格宽）。排版口径，与 [bigNumberFontSp] 的字号口径分开。 */
internal fun isNarrowWidth(widthDp: Float): Boolean = widthDp < WidgetNarrowWidthDp

/**
 * 内容块之间的间隔（[ColumnScope]）。
 *
 * 窄档（2 格宽，真机实测 150×178dp）给**弹性占位**：卡片里三处间隔等分剩余高度，
 * 内容被铺满，不再出现「上下两块空白」（2026-09-27 用户第二次反馈后的口径——先做成
 * 整块居中，上下各留一大段，看着还是空）。宽档给固定 [wideDp]，末段另有弹性把按钮
 * 推到卡片底部（4×2 是横条，按钮沉底才稳）。
 */
@Composable
internal fun ColumnScope.WidgetGap(narrow: Boolean, wideDp: Int) {
    if (narrow) Spacer(GlanceModifier.defaultWeight()) else Spacer(GlanceModifier.height(wideDp.dp))
}
