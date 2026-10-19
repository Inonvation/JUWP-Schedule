package edu.jxslu.schedule.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import edu.jxslu.schedule.domain.DormRepairRecord
import edu.jxslu.schedule.domain.DormRepairRules
import edu.jxslu.schedule.ui.common.AppCard
import edu.jxslu.schedule.ui.common.formatDormRepairTime
import edu.jxslu.schedule.ui.common.heroToneAccent
import edu.jxslu.schedule.ui.common.toHeroTone
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Repair

/**
 * 今日页顶部「宿舍报修」卡（DESIGN §3.3 / §4.49，2026-10-19）。
 *
 * 只展示报修事实：状态、宿舍 · 故障、当前节点 / 提交 / 完成时间；姓名、学号等
 * 个人字段在解析层已丢弃。整卡点击进 `DORM_REPAIR` 二级页；没有可见记录时父级不渲染。
 */
@Composable
fun DormRepairCard(
    record: DormRepairRecord,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tone = DormRepairRules.toneOf(record).toHeroTone()
    val accent = heroToneAccent(tone)
    val secondary = listOf(record.dorm, record.fault)
        .filter { it.isNotBlank() }
        .joinToString(" · ")
        .ifBlank { "点按查看报修进度" }

    AppCard(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        onClick = onClick,
        onClickLabel = "查看报修进度",
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(accent.copy(alpha = 0.10f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = HugeIcons.Repair,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(21.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "宿舍报修",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = DormRepairRules.statusLabel(record),
                        style = MaterialTheme.typography.labelMedium,
                        color = accent,
                        maxLines = 1,
                    )
                }
                Text(
                    text = secondary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = detailLine(record),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun detailLine(record: DormRepairRecord): String {
    if (DormRepairRules.isFinished(record)) {
        val at = DormRepairRules.finishedAtOf(record) ?: record.updatedAt
        return if (at > 0) "完成于 ${formatDormRepairTime(at)}" else "已办结"
    }
    DormRepairRules.currentNode(record)?.let { return "当前：${it.title}" }
    return if (record.submittedAt > 0) {
        "提交于 ${formatDormRepairTime(record.submittedAt)}"
    } else {
        "等待处理"
    }
}
