package edu.jxslu.schedule.ui.homework

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import edu.jxslu.schedule.domain.PendingHomework
import edu.jxslu.schedule.domain.dueLabel
import edu.jxslu.schedule.ui.common.AppCardRow
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ChevronRight
import me.rerere.hugeicons.stroke.Task01
import java.time.LocalDate

/**
 * 今日页作业卡（DESIGN §3.3/§3.11）：**有未完成作业才占位**。
 *
 * 位置 = 滚动区最后一项（明天块之下、贴底固定区之上，2026-09-21 用户两次拍板）；
 * 形态 = [AppCardRow]（全 App 同一张描边卡），左图标 + 「作业 · N 项未完成」+
 * 副行「最近截止 10月12日」，有过期追加 error 色「M 项已过期」。点击进「作业中心」。
 *
 * 图标 2026-09-22 从"标题行内"移到 Column 之外：原先它跟着第一行居中，两行结构下观感偏上、
 * 像没摆正。现在由外层 Row 在**整张卡**上竖直居中，副行也不再靠 26dp 手算缩进——
 * 两行文字左缘由 Column 决定，图标换尺寸不会再错位。
 */
@Composable
fun HomeworkTodayCard(
    pending: PendingHomework,
    today: LocalDate,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (pending.isEmpty) return
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface

    AppCardRow(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        onClick = onClick,
        onClickLabel = "查看作业",
    ) {
        Icon(
            imageVector = HugeIcons.Task01,
            contentDescription = null,
            tint = primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = "作业 · ${pending.total} 项未完成",
                style = MaterialTheme.typography.titleSmall,
                color = onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                val parts = buildList {
                    pending.nextDue?.let { due -> dueLabel(due, today)?.let { add("最近截止 $it") } }
                    if (pending.overdue > 0) add("${pending.overdue} 项已过期")
                    if (isEmpty()) add("未设截止日期")
                }
                parts.forEachIndexed { index, part ->
                    if (index > 0) {
                        Text(
                            text = " · ",
                            style = MaterialTheme.typography.bodySmall,
                            color = onSurface.copy(alpha = 0.45f),
                        )
                    }
                    Text(
                        text = part,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (part.endsWith("已过期")) {
                            MaterialTheme.colorScheme.error
                        } else {
                            onSurface.copy(alpha = 0.6f)
                        },
                    )
                }
            }
        }
        // 右箭头放卡片层级，跟左图标一样在**整张卡**上竖直居中；
        // 挂在标题行里只会跟标题居中，两行结构下看着就偏上（2026-09-22 用户反馈）
        Spacer(Modifier.width(10.dp))
        Icon(
            imageVector = HugeIcons.ChevronRight,
            contentDescription = null,
            tint = onSurface.copy(alpha = 0.35f),
            modifier = Modifier.size(18.dp),
        )
    }
}
