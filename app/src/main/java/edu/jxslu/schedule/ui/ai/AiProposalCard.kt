package edu.jxslu.schedule.ui.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import edu.jxslu.schedule.domain.Course
import edu.jxslu.schedule.domain.OverrideKind
import edu.jxslu.schedule.domain.ScheduleDiff
import edu.jxslu.schedule.domain.ScheduleOverride
import edu.jxslu.schedule.domain.ScheduleOverrides
import edu.jxslu.schedule.domain.SemesterConfig
import edu.jxslu.schedule.ui.common.AppCard
import edu.jxslu.schedule.ui.common.ChangeDetailLabels
import edu.jxslu.schedule.ui.common.ScheduleChangeCard
import edu.jxslu.schedule.ui.common.SkeletonBox
import edu.jxslu.schedule.ui.theme.semanticColors
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert02
import me.rerere.hugeicons.stroke.CheckmarkCircle02
import me.rerere.hugeicons.stroke.ChevronDown
import me.rerere.hugeicons.stroke.ChevronUp
import me.rerere.hugeicons.stroke.Undo02

/**
 * ② 提案卡 / 失败卡（DESIGN §3.25 v2）。
 *
 * 一页只有一种卡片观感（[AppCard]）、一套徽章色映射（[changeKindColor] /
 * [overrideKindColor]，与共享的 ScheduleChangeCard 同口径）、一套浅底块
 * （[AiSoftBlock]，读图核对 / 思考过程 / 拒绝项 / 回执行共用）。
 *
 * 交互要点：结果就绪**默认展开**（勾选是主战场）；应用成功后才折叠成摘要
 * （摘要行保留「撤销」直达）；撤销不进底栏——它跟着回执行走。
 */

/** AI 页的明细措辞：这些改动是模型给的建议，不是教务的口径。 */
internal val AiDetailLabels = ChangeDetailLabels(
    added = "建议新增",
    removed = "建议删除",
)

/** 思考过程展开后的高度上限——它一直长，不设限会把下面的结果顶出屏幕。 */
private val REASONING_MAX_HEIGHT = 132.dp

/** 改动类型的颜色映射（单点定义，与共享 ScheduleChangeCard 的徽章同口径）。 */
@Composable
internal fun changeKindColor(kind: ScheduleDiff.ChangeKind): Color = when (kind) {
    ScheduleDiff.ChangeKind.MODIFIED -> MaterialTheme.colorScheme.primary
    ScheduleDiff.ChangeKind.ADDED -> MaterialTheme.semanticColors.success
    ScheduleDiff.ChangeKind.REMOVED -> MaterialTheme.colorScheme.error
}

/** 日期覆盖的颜色映射（单点定义：停课=error、补课=primary）。 */
@Composable
internal fun overrideKindColor(kind: OverrideKind): Color = when (kind) {
    OverrideKind.SUSPENDED -> MaterialTheme.colorScheme.error
    OverrideKind.FOLLOW -> MaterialTheme.colorScheme.primary
}

@Composable
internal fun AiProposalCard(
    work: AiWorkState,
    semester: SemesterConfig?,
    courses: List<Course>,
    existingOverrides: List<ScheduleOverride>,
    showReasoning: Boolean,
    onToggleChange: (Int, Boolean) -> Unit,
    onToggleOverride: (Int, Boolean) -> Unit,
    onToggleCollapsed: () -> Unit,
    onUndo: () -> Unit,
    onReadbackToComposer: (String) -> Unit,
    onCopyHeadline: (String) -> Unit,
) {
    AppCard(contentPadding = PaddingValues(0.dp)) {
        // 折叠 / 展开都过动画：应用成功自动收起、点摘要行展开，别硬切
        AnimatedVisibility(
            visible = work.collapsed,
            enter = fadeIn(tween(200)) + expandVertically(tween(260)),
            exit = fadeOut(tween(140)) + shrinkVertically(tween(200)),
        ) {
            CollapsedSummary(work, onToggleCollapsed, onUndo)
        }
        AnimatedVisibility(
            visible = !work.collapsed,
            enter = fadeIn(tween(220)) + expandVertically(tween(300)),
            exit = fadeOut(tween(180)) + shrinkVertically(tween(240)),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                ProposalHeader(work, onToggleCollapsed)
                ProposalBody(
                    work = work,
                    semester = semester,
                    courses = courses,
                    existingOverrides = existingOverrides,
                    showReasoning = showReasoning,
                    onToggleChange = onToggleChange,
                    onToggleOverride = onToggleOverride,
                    onReadbackToComposer = onReadbackToComposer,
                    onCopyHeadline = onCopyHeadline,
                )
                val receipt = work.receiptForThisProposal
                if (receipt != null && (work.phase == AiPhase.APPLIED || work.phase == AiPhase.UNDONE)) {
                    Spacer(Modifier.height(10.dp))
                    AiReceiptRow(
                        receipt = receipt,
                        undoSkipped = work.undoSkipped,
                        onUndo = onUndo,
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------
// 折叠摘要（应用成功后自动折叠；点开恢复完整卡）
// ------------------------------------------------------------------

@Composable
private fun CollapsedSummary(work: AiWorkState, onExpand: () -> Unit, onUndo: () -> Unit) {
    val dotColor = when {
        work.phase == AiPhase.APPLIED -> MaterialTheme.semanticColors.success
        work.phase == AiPhase.UNDONE -> MaterialTheme.colorScheme.outlineVariant
        else -> MaterialTheme.semanticColors.warning
    }
    Row(
        Modifier
            .fillMaxWidth()
            // 退场动画期间（collapsed 已翻 false）这一份还在布局里，禁掉点击：
            // 否则快速点开会把刚展开的卡又折回去（「弹回」）
            .clickable(enabled = work.collapsed) { onExpand() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(
                collapseTitle(work),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                collapseSub(work),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            )
        }
        // 摘要卡常驻「撤销」直达（Compose 点击不冒泡，不会触发展开）
        if (work.undoAvailable && work.receiptForThisProposal != null) {
            Text(
                "撤销",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onUndo() }
                    // 竖向 14dp：命中区凑到 ~44dp（项目对卡片动作的既定口径）
                    .padding(horizontal = 10.dp, vertical = 14.dp),
            )
        } else {
            Icon(
                HugeIcons.ChevronDown,
                contentDescription = "展开",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private fun collapseTitle(work: AiWorkState): String = when {
    work.phase == AiPhase.APPLIED -> "已应用 ${work.receipt?.applied ?: 0} 处 · 课表已更新"
    work.phase == AiPhase.UNDONE -> "已撤销 · 课表回到应用前"
    else -> work.headline.ifBlank { "分析结果" }
}

private fun collapseSub(work: AiWorkState): String = buildString {
    if (!work.isEmptyResult) {
        val parts = buildList {
            work.overrides.count { it.kind == OverrideKind.SUSPENDED }
                .takeIf { it > 0 }?.let { add("停课 $it") }
            work.overrides.count { it.kind == OverrideKind.FOLLOW }
                .takeIf { it > 0 }?.let { add("补课 $it") }
            work.changes.count { it.kind == ScheduleDiff.ChangeKind.MODIFIED }
                .takeIf { it > 0 }?.let { add("调整 $it") }
            work.changes.count { it.kind == ScheduleDiff.ChangeKind.ADDED }
                .takeIf { it > 0 }?.let { add("新增 $it") }
            work.changes.count { it.kind == ScheduleDiff.ChangeKind.REMOVED }
                .takeIf { it > 0 }?.let { add("删除 $it") }
        }
        append("已选 ${work.selectedCount}/${work.totalCount}")
        if (parts.isNotEmpty()) append(" · ").append(parts.joinToString(" · "))
        append(" · 点开查看明细")
    } else {
        append("点开查看模型回复")
    }
    append(" · ").append(work.receipt?.at ?: work.analyzedAt)
}

// ------------------------------------------------------------------
// 头部与主体
// ------------------------------------------------------------------

@Composable
private fun ProposalHeader(work: AiWorkState, onToggleCollapsed: () -> Unit) {
    val (statusText, statusColor) = when (work.phase) {
        AiPhase.APPLIED -> "已应用" to MaterialTheme.semanticColors.success
        AiPhase.UNDONE -> "已撤销" to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
        AiPhase.ANALYZING -> "分析中" to MaterialTheme.semanticColors.warning
        AiPhase.APPLYING -> "应用中" to MaterialTheme.semanticColors.warning
        else -> "待确认" to MaterialTheme.semanticColors.warning
    }
    val title = when (work.phase) {
        AiPhase.ANALYZING ->
            // 增量解析：已经数出来的条目如实报；还没有就明说在分析（绝不出「没有改动」假结论）
            if (work.isEmptyResult) "正在分析…" else "已整理 ${work.totalCount} 处改动 · 分析中"

        else -> work.headline.ifBlank {
            if (work.isEmptyResult) "模型认为没有需要改动的课程" else "已整理 ${work.totalCount} 处改动"
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        RowKindLabel(statusText, statusColor)
        Spacer(Modifier.width(8.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (work.phase == AiPhase.APPLIED) {
            // 退场（正在折叠）期间这份还在布局里，禁掉点击，免得又翻回去
            SmallAction("收起", enabled = !work.collapsed) { onToggleCollapsed() }
        }
    }
    if (work.phase != AiPhase.ANALYZING && work.analyzedAt.isNotBlank()) {
        Text(
            "依据：通知" +
                (if (work.noticeImages.isNotEmpty()) " + ${work.noticeImages.size} 张截图" else "文字") +
                " · ${work.analyzedAt}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
        )
    }
}

@Composable
private fun ProposalBody(
    work: AiWorkState,
    semester: SemesterConfig?,
    courses: List<Course>,
    existingOverrides: List<ScheduleOverride>,
    showReasoning: Boolean,
    onToggleChange: (Int, Boolean) -> Unit,
    onToggleOverride: (Int, Boolean) -> Unit,
    onReadbackToComposer: (String) -> Unit,
    onCopyHeadline: (String) -> Unit,
) {
    val result = work.result
    val analyzing = work.phase == AiPhase.ANALYZING
    // 逐段出现 / 消失都过同一套动效：分析完成时结果不再是「啪」地整块冒出来
    val enter = fadeIn(tween(220)) + expandVertically(tween(260))
    val exit = fadeOut(tween(160)) + shrinkVertically(tween(220))

    // ---- 读图核对块：让用户能发现 OCR 错字（截图的唯一纠正机会）
    AnimatedVisibility(visible = !result?.readback.isNullOrBlank(), enter = enter, exit = exit) {
        Column {
            Spacer(Modifier.height(10.dp))
            result?.readback?.takeIf { it.isNotBlank() }?.let {
                ReadbackBlock(text = it, onUseAsText = onReadbackToComposer)
            }
        }
    }

    // ---- 分析中且还没有任何增量：骨架占位（几何近似成品，不出现假结论）
    AnimatedVisibility(
        visible = analyzing && work.isEmptyResult,
        enter = fadeIn(tween(180)),
        exit = fadeOut(tween(180)) + shrinkVertically(tween(220)),
    ) {
        Column {
            Spacer(Modifier.height(10.dp))
            SkeletonBox(Modifier.fillMaxWidth().height(15.dp))
            Spacer(Modifier.height(8.dp))
            SkeletonBox(Modifier.fillMaxWidth(0.7f).height(15.dp))
        }
    }

    // ---- 统计条（全页唯一的类型计数处）
    AnimatedVisibility(visible = !work.isEmptyResult, enter = enter, exit = exit) {
        Column {
            Spacer(Modifier.height(10.dp))
            StatsRow(work)
        }
    }

    // ---- 明细分组（勾选主战场；分析中不可交互，故不渲染）
    AnimatedVisibility(visible = !work.isEmptyResult && !analyzing, enter = enter, exit = exit) {
        Column {
            Spacer(Modifier.height(2.dp))
            DetailGroups(
                work = work,
                semester = semester,
                onToggleChange = onToggleChange,
                onToggleOverride = onToggleOverride,
            )
        }
    }

    // ---- 空结果：给「复制」而不是死路的禁用按钮
    AnimatedVisibility(
        visible = work.phase == AiPhase.READY && work.isEmptyResult,
        enter = enter,
        exit = exit,
    ) {
        Column {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "模型没有给出可应用的改动。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.weight(1f),
                )
                if (work.headline.isNotBlank()) {
                    SmallAction("复制回答") { onCopyHeadline(work.headline) }
                }
            }
        }
    }

    // ---- 拒绝项必须可见（只说「没有改动」是误导）
    AnimatedVisibility(
        visible = !analyzing && work.result?.rejected.orEmpty().isNotEmpty(),
        enter = enter,
        exit = exit,
    ) {
        RejectedNote(work)
    }

    // ---- 思考过程：默认折叠成一行，展开后限高 + 内部滚动 + 自动跟随
    AnimatedVisibility(
        visible = (showReasoning || analyzing) && work.reasoning.isNotBlank(),
        enter = enter,
        exit = exit,
    ) {
        Column {
            Spacer(Modifier.height(10.dp))
            ReasoningBlock(text = work.reasoning, streaming = analyzing)
        }
    }

    // ---- 前后对照预览（分析中不渲染，避免半截状态闪烁）
    AnimatedVisibility(
        visible = !analyzing && !work.isEmptyResult && courses.isNotEmpty(),
        enter = enter,
        exit = exit,
    ) {
        Column {
            Spacer(Modifier.height(12.dp))
            PreviewBlock(work, semester, courses, existingOverrides)
        }
    }
}

// ------------------------------------------------------------------
// 统计条
// ------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatsRow(work: AiWorkState) {
    // FlowRow：五种类型 chip 全出现 + 大字体档位时，单行 Row 放不下的子项会被
    // 直接裁成 0 宽（尾部 chip 凭空消失）；换行兜住，窄屏只是多一行
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatChip("停课", work.overrides.count { it.kind == OverrideKind.SUSPENDED }, overrideKindColor(OverrideKind.SUSPENDED))
        StatChip("补课", work.overrides.count { it.kind == OverrideKind.FOLLOW }, overrideKindColor(OverrideKind.FOLLOW))
        StatChip(
            "调整",
            work.changes.count { it.kind == ScheduleDiff.ChangeKind.MODIFIED },
            changeKindColor(ScheduleDiff.ChangeKind.MODIFIED),
        )
        StatChip(
            "新增",
            work.changes.count { it.kind == ScheduleDiff.ChangeKind.ADDED },
            changeKindColor(ScheduleDiff.ChangeKind.ADDED),
        )
        StatChip(
            "删除",
            work.changes.count { it.kind == ScheduleDiff.ChangeKind.REMOVED },
            changeKindColor(ScheduleDiff.ChangeKind.REMOVED),
        )
        Text(
            "已选 ${work.selectedCount} / ${work.totalCount}",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(7.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun StatChip(label: String, count: Int, color: Color) {
    if (count <= 0) return
    Text(
        "$label $count",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(color.copy(alpha = 0.13f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

// ------------------------------------------------------------------
// 明细分组
// ------------------------------------------------------------------

@Composable
private fun DetailGroups(
    work: AiWorkState,
    semester: SemesterConfig?,
    onToggleChange: (Int, Boolean) -> Unit,
    onToggleOverride: (Int, Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // 空组不渲染：纯课表改动不该顶着一个「日期覆盖（0/0）」的空标题
        if (work.overrides.isNotEmpty()) {
            GroupHeader(
                "日期覆盖 · 调休 / 停课",
                allChecked = work.overrides.indices.all { it in work.effectiveOverrideSelection },
                onToggleAll = { all -> work.overrides.indices.forEach { onToggleOverride(it, all) } },
            )
            work.overrides.forEachIndexed { index, override ->
                AiOverrideRow(
                    override = override,
                    note = work.result?.overrideReasons?.getOrNull(index)?.takeIf { it.isNotBlank() },
                    checked = index in work.effectiveOverrideSelection,
                    onChecked = { onToggleOverride(index, it) },
                    semester = semester,
                )
            }
        }
        if (work.changes.isNotEmpty()) {
            GroupHeader(
                "课表改动",
                allChecked = work.changes.indices.all { it in work.effectiveSelection },
                onToggleAll = { all -> work.changes.indices.forEach { onToggleChange(it, all) } },
            )
            work.changes.forEachIndexed { index, change ->
                ScheduleChangeCard(
                    change = change,
                    checked = index in work.effectiveSelection,
                    onCheckedChange = { onToggleChange(index, it) },
                    note = work.result?.reasons?.getOrNull(index)?.takeIf { it.isNotBlank() },
                    labels = AiDetailLabels,
                    onRowToggle = { onToggleChange(index, index !in work.effectiveSelection) },
                )
            }
        }
    }
}

@Composable
private fun GroupHeader(title: String, allChecked: Boolean, onToggleAll: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            modifier = Modifier.weight(1f),
        )
        SmallAction(if (allChecked) "全不选" else "全选") { onToggleAll(!allChecked) }
    }
}

/** 一条日期覆盖的勾选行：行解剖与 ScheduleChangeCard 对齐（徽章 + 正文 + 勾选框）。 */
@Composable
private fun AiOverrideRow(
    override: ScheduleOverride,
    note: String?,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    semester: SemesterConfig?,
) {
    AppCard(onClick = { onChecked(!checked) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RowKindLabel(
                        if (override.kind == OverrideKind.SUSPENDED) "停课" else "补课",
                        overrideKindColor(override.kind),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        ScheduleOverrides.describe(override, semester),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!note.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }
            Checkbox(checked = checked, onCheckedChange = onChecked)
        }
    }
}

// ------------------------------------------------------------------
// 拒绝项 / 思考过程 / 读图核对 / 回执
// ------------------------------------------------------------------

/** 被丢弃的提议：必须在 UI 里可见（只说「没有改动」是误导）。图标不用 emoji。 */
@Composable
private fun RejectedNote(work: AiWorkState) {
    val rejected = work.result?.rejected.orEmpty()
    if (rejected.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    Spacer(Modifier.height(8.dp))
    AiSoftBlock {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                HugeIcons.Alert02,
                contentDescription = null,
                tint = MaterialTheme.semanticColors.warning,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                "${rejected.size} 条提议已略过，展开看原因",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.semanticColors.warning,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (expanded) HugeIcons.ChevronUp else HugeIcons.ChevronDown,
                contentDescription = if (expanded) "收起" else "展开",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.size(16.dp),
            )
        }
        if (expanded) {
            Spacer(Modifier.height(4.dp))
            rejected.forEach {
                Text(
                    "· ${it.reason}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }
    }
}

@Composable
private fun ReasoningBlock(text: String, streaming: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    AiSoftBlock {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                buildString {
                    append(if (streaming) "思考中" else "思考过程")
                    append(" · ")
                    append(text.length)
                    append(" 字")
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.weight(1f),
            )
            SmallAction(if (expanded) "收起" else "展开") { expanded = !expanded }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(200)) + expandVertically(tween(240)),
            exit = fadeOut(tween(140)) + shrinkVertically(tween(200)),
        ) {
            Column {
                Spacer(Modifier.height(6.dp))
                // 展开内容状态随组合销毁/重建：LaunchedEffect 在每次展开时再滚到底
                val scroll = rememberScrollState()
                LaunchedEffect(text) { scroll.scrollTo(scroll.maxValue) }
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = REASONING_MAX_HEIGHT)
                        .verticalScroll(scroll),
                )
            }
        }
    }
}

@Composable
private fun ReadbackBlock(text: String, onUseAsText: (String) -> Unit) {
    AiSoftBlock {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RowKindLabel("读图", MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(
                "我读到的通知 · 请核对",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        Spacer(Modifier.height(6.dp))
        text.lines().filter { it.isNotBlank() }.forEach { line ->
            Text(
                "· $line",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
        SmallAction("识别有误？点这里改成文字") { onUseAsText(text) }
    }
}

/**
 * 应用回执行（卡内页脚 / 页面「上次应用」条共用同一内容）。
 *
 * [receipt].undone 时是留档展示（已撤销，不再可撤）；未撤销时带「撤销」动作——
 * **撤销只出现在这里**（摘要行 / 回执行），永远不进底栏。
 */
@Composable
internal fun AiReceiptRow(
    receipt: AiAppliedReceipt,
    undoSkipped: Int,
    onUndo: () -> Unit,
) {
    val undone = receipt.undone
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (undone) {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                } else {
                    MaterialTheme.semanticColors.success.copy(alpha = 0.12f)
                },
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (undone) HugeIcons.Undo02 else HugeIcons.CheckmarkCircle02,
                contentDescription = null,
                tint = if (undone) {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                } else {
                    MaterialTheme.semanticColors.success
                },
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                buildString {
                    if (undone) {
                        append("已撤销，课表回到应用前")
                        if (undoSkipped > 0) append(" · $undoSkipped 条你后来改过，未动")
                    } else {
                        append("已应用 ").append(receipt.applied).append(" 处")
                        if (receipt.appliedOverrides > 0) {
                            append("（日期覆盖 ").append(receipt.appliedOverrides).append("）")
                        }
                        if (receipt.skipped > 0) {
                            append(" · ").append(receipt.skipped).append(" 条未应用（重复或对不上）")
                        }
                    }
                    append(" · ").append(receipt.at)
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                modifier = Modifier.weight(1f),
            )
            if (!undone) {
                TextButton(onClick = onUndo, modifier = Modifier.heightIn(min = 44.dp)) {
                    Text("撤销", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

// ------------------------------------------------------------------
// 课表预览（全宽单栏 + 前后切换）
// ------------------------------------------------------------------

@Composable
private fun PreviewBlock(
    work: AiWorkState,
    semester: SemesterConfig?,
    courses: List<Course>,
    existingOverrides: List<ScheduleOverride>,
) {
    val pickedOverrides = work.overrides.filterIndexed { index, _ -> index in work.effectiveOverrideSelection }
    val weeks = remember(work.changes, work.effectiveSelection, pickedOverrides, semester) {
        previewWeeksOf(work.changes, work.effectiveSelection, pickedOverrides, semester)
            .ifEmpty { listOf(1) }
    }
    var week by remember(weeks) { mutableStateOf(weeks.first()) }
    val activeWeek = week.takeIf { it in weeks } ?: weeks.first()
    var showAfter by remember { mutableStateOf(true) }
    val previewCourses = remember(courses, work.changes, work.effectiveSelection) {
        ScheduleDiff.preview(courses, work.changes, work.effectiveSelection)
    }
    val changed = remember(
        work.changes, work.effectiveSelection, pickedOverrides, previewCourses, activeWeek, semester,
    ) {
        changedCellSet(work.changes, work.effectiveSelection, pickedOverrides, previewCourses, activeWeek, semester)
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "课表预览",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            modifier = Modifier.weight(1f),
        )
        SingleChoiceSegmentedButtonRow {
            listOf("应用前" to false, "应用后" to true).forEachIndexed { index, (label, after) ->
                SegmentedButton(
                    selected = showAfter == after,
                    onClick = { showAfter = after },
                    // 不显示选中对勾：选中段自带填充色（与用电面板分段控件同口径）
                    icon = {},
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                    label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                    modifier = Modifier.height(36.dp),
                )
            }
        }
    }
    if (weeks.size > 1) {
        Spacer(Modifier.height(6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(weeks) { candidate ->
                FilterChip(
                    selected = candidate == activeWeek,
                    onClick = { week = candidate },
                    label = { Text("第 $candidate 周", style = MaterialTheme.typography.labelMedium) },
                )
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    // 预览容器是浅底块（不是卡片）：卡中套卡会多一层描边，网格自带格子观感
    //
    // 「应用前」= **现状** = 课程行 + 已生效的日期安排（existingOverrides）——
    // 不带 existing 的话，调休应用完之后这里会把放假那天照常画出课，看着像「调课前」。
    // 「应用后」= 现状 + 本轮勾选的提案：plan 摊平时后写的覆盖前面的，
    // 所以本轮勾选与已生效安排撞同一天时，预览里显示的是**本轮为准**。
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
            .padding(8.dp),
    ) {
        SchedulePreviewGrid(
            courses = if (showAfter) previewCourses else courses,
            semester = semester,
            week = activeWeek,
            overrides = if (showAfter) existingOverrides + pickedOverrides else existingOverrides,
            changedCells = changed,
        )
    }
    Spacer(Modifier.height(6.dp))
    Text(
        "描边 = 本次受影响的格子；「应用后」随勾选实时更新",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
    )
}

// ------------------------------------------------------------------
// 失败卡
// ------------------------------------------------------------------

@Composable
internal fun AiFailCard(
    work: AiWorkState,
    onOpenSettings: () -> Unit,
    onRetryWithoutImages: () -> Unit,
) {
    AppCard {
        Row(verticalAlignment = Alignment.Top) {
            RowKindLabel("失败", MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(8.dp))
            Text(
                work.failure.ifBlank { "请求失败" },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
        }
        // 流式中途断掉（截断 / 流中 error / 网络中断）时已解析到的条目一并报出——
        // 「已解析出的部分结果一并显示，不藏起来」（这条曾被只渲染失败卡吞掉）
        if (work.totalCount > 0) {
            Spacer(Modifier.height(4.dp))
            Text(
                "已解析到 ${work.totalCount} 处改动（分析未完成，不可应用）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // 「去掉图片重发」是上游不吃图时唯一能走通的路（重试在底栏）
            if (work.failureIsImage) {
                TextButton(onClick = onRetryWithoutImages, modifier = Modifier.heightIn(min = 44.dp)) {
                    Text("去掉图片重发")
                }
                TextButton(onClick = onOpenSettings, modifier = Modifier.heightIn(min = 44.dp)) {
                    Text("去开启图片识别")
                }
            } else {
                TextButton(onClick = onOpenSettings, modifier = Modifier.heightIn(min = 44.dp)) {
                    Text("去配置")
                }
            }
        }
    }
}

// ------------------------------------------------------------------
// 小件
// ------------------------------------------------------------------

/** 行内类型标签（与共享 ScheduleChangeCard 的徽章同口径：纯色 labelSmall）。 */
@Composable
private fun RowKindLabel(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
    )
}

/** 卡内小动作（深色文字按钮）；比 M3 TextButton 紧凑，用于组头 / 块内。 */
@Composable
private fun SmallAction(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

/** 卡内浅底块（读图核对 / 思考过程 / 拒绝项 / 回执行共用，不再各写一种描边卡）。 */
@Composable
private fun AiSoftBlock(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(horizontal = 11.dp, vertical = 9.dp),
        content = content,
    )
}
