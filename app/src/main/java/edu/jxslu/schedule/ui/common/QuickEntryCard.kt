package edu.jxslu.schedule.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ChevronRight

/**
 * 今日页底部固定区「快速入口卡」的最小高度：两行文本（bodyMedium + bodySmall）+
 * 上下 11dp 内边距。整块 dock 的入口卡（快趣出行 / 胖乖 / 趣智）都取它，
 * 高度恒定；系统大字体时自然高度超过 min 也不受影响（min 只是下限）。
 */
val QuickCardMinHeight = 58.dp

/**
 * 「点一下进某个页面」的整行入口卡（DESIGN §3.2 卡片规格的出处之一）：
 * 图标（20dp 主色）+ 标题 + 副行 + 右向箭头，整卡可点。
 *
 * 今日页的「快趣出行」与生活页的「U净 · 洗衣房」共用这一张（2026-10-13 统一）：
 * 两者都是「一行入口卡」，此前各画一份，图标底座 / 字号 / 箭头 / 内边距都不一致。
 * 新加同类入口（一行、点进去一个页面）就复用它，别再各画一份。
 */
@Composable
fun QuickEntryCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClickLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** 进行中态点亮（主色描边），如趣智「用水中」。 */
    highlighted: Boolean = false,
) {
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    AppCardRow(
        modifier = modifier.heightIn(min = QuickCardMinHeight),
        onClick = onClick,
        onClickLabel = onClickLabel,
        contentPadding = PaddingValues(horizontal = 13.dp, vertical = 11.dp),
        highlighted = highlighted,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = onSurface.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            HugeIcons.ChevronRight,
            contentDescription = null,
            tint = onSurface.copy(alpha = 0.35f),
            modifier = Modifier.size(16.dp),
        )
    }
}
