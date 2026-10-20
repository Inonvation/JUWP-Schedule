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
 * 只展示报修事实，两行：状态 + 当前进度（同一行）/ 宿舍 · 故障；姓名、学号等
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
    val detail = detailLine(record)

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
                // 第一行：标题 + 状态 + 当前进度（进度跟在状态后面，读起来是一句）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "宿舍报修",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = DormRepairRules.statusLabel(record),
                        style = MaterialTheme.typography.labelMedium,
                        color = accent,
                        maxLines = 1,
                    )
                    if (detail != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Text(
                    text = secondary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 当前进度：完成时间 / 当前节点 / 提交时间；都没有时返回 null（不占位、不复述状态）。
 *
 * 进度紧跟在状态后面显示，因此不再写「当前：」「完成于」这类前缀——状态已经把语义
 * 交代清楚，前缀只会挤占节点名（节点名可能是「宿管科下发维修工单」这种长句）。
 */
private fun detailLine(record: DormRepairRecord): String? {
    if (DormRepairRules.isFinished(record)) {
        val at = DormRepairRules.finishedAtOf(record) ?: record.updatedAt
        return if (at > 0) formatDormRepairTime(at) else null
    }
    DormRepairRules.currentNode(record)?.let { return it.title }
    return if (record.submittedAt > 0) {
        "提交于 ${formatDormRepairTime(record.submittedAt)}"
    } else {
        null
    }
}
