package edu.jxslu.schedule.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 设置行标题旁的「作用域 / 生效时机」标签（我的改版 2026-09-28）。
 *
 * 用途：说明文字容易被扫视漏掉，把它最关键的一个词提炼成常驻小标签挂在标题旁——
 * 「重启生效」（悬浮导航栏、启动页）、「当前课表」（开学日期、总周数、作息、调课）。
 *
 * 视觉：10dp 圆角描边框、labelSmall、65% 前景——只是提示不是徽章，不抢标题。
 */
@Composable
fun SettingsTag(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                RoundedCornerShape(5.dp),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
