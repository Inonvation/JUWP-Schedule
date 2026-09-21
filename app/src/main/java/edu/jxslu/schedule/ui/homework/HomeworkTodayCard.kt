package edu.jxslu.schedule.ui.homework

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import edu.jxslu.schedule.domain.PendingHomework
import edu.jxslu.schedule.domain.dueLabel
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ChevronRight
import me.rerere.hugeicons.stroke.Task01
import java.time.LocalDate

/**
 * 今日页作业卡（DESIGN §3.3/§3.11）：**有未完成作业才占位**。
 *
 * 位置 = 焦点卡之下、「今天还有 N 节」之上；形态 = 1dp 描边卡（surface 底，与底部固定区的
 * 卡片同款），左图标 + 「作业 · N 项未完成」+ 副行「最近截止 10月12日」，有过期追加
 * error 色「M 项已过期」。点击进「作业中心」。
 */
@Composable
fun HomeworkTodayCard(
    pending: PendingHomework,
    today: LocalDate,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (pending.isEmpty) return
    val haptics = rememberAppHaptics()
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val shape = RoundedCornerShape(12.dp)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f), shape)
            .clickable(onClickLabel = "查看作业") {
                haptics.tap()
                onClick()
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = HugeIcons.Task01,
                contentDescription = null,
                tint = primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "作业 · ${pending.total} 项未完成",
                style = MaterialTheme.typography.titleSmall,
                color = onSurface,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = HugeIcons.ChevronRight,
                contentDescription = null,
                tint = onSurface.copy(alpha = 0.35f),
                modifier = Modifier.size(18.dp),
            )
        }
        Row(
            modifier = Modifier.padding(start = 26.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
}
