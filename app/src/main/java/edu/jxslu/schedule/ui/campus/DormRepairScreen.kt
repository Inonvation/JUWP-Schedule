package edu.jxslu.schedule.ui.campus

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.XgFormActivity
import edu.jxslu.schedule.data.repo.DormRepairSync
import edu.jxslu.schedule.data.xg.XgUrls
import edu.jxslu.schedule.domain.DormRepairRecord
import edu.jxslu.schedule.domain.DormRepairRules
import edu.jxslu.schedule.ui.common.AppCard
import edu.jxslu.schedule.ui.common.AppCardDivider
import edu.jxslu.schedule.ui.common.EmptyHint
import edu.jxslu.schedule.ui.common.LoadingHint
import edu.jxslu.schedule.ui.common.PageRefreshAction
import edu.jxslu.schedule.ui.common.PageScaffoldColumn
import edu.jxslu.schedule.ui.common.SettingsNote
import edu.jxslu.schedule.ui.common.StatusHeroCard
import edu.jxslu.schedule.ui.common.approverSummary
import edu.jxslu.schedule.ui.common.formatDormRepairTime
import edu.jxslu.schedule.ui.common.heroToneAccent
import edu.jxslu.schedule.ui.common.showNotice
import edu.jxslu.schedule.ui.common.toHeroTone
import kotlinx.coroutines.launch

/**
 * 宿舍报修详情（DESIGN §3.15 / §4.49，2026-10-19）：今日页卡片 / 收件箱通知的落点。
 *
 * 纯原生只读页：状态、报修字段、审批流程、当前处理人。底部「去学工报修页核对」
 * 打开现有 `XgFormActivity`，需要看官方页面时用；「完成并隐藏」**只写本机快照**，
 * 不向学工提交任何写操作（真正的办结/评价在官方页面里由用户自己操作）。
 */
@Composable
fun DormRepairScreen(
    onBack: () -> Unit,
    focusItemId: String? = null,
) {
    val context = LocalContext.current
    val store = remember { Graph.dormRepairStore(context) }
    val sync = remember { Graph.dormRepairSync(context) }
    val snapshot by store.snapshot.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var busy by remember { mutableStateOf(false) }
    var refreshedOnce by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val targetId = remember(focusItemId) { focusItemId?.toLongOrNull() }
    val invalidTarget = focusItemId != null && targetId == null
    val now = System.currentTimeMillis()
    val record = remember(snapshot, targetId, invalidTarget) {
        when {
            invalidTarget -> null
            targetId != null -> DormRepairRules.find(snapshot, targetId)
            else -> DormRepairRules.cardRecord(snapshot, now)
        }
    }

    val refresh: () -> Unit = {
        if (!busy) {
            scope.launch {
                busy = true
                error = null
                when (val result = sync.refresh()) {
                    is DormRepairSync.CheckResult.Ok -> Unit
                    is DormRepairSync.CheckResult.Failed -> error = result.message
                    DormRepairSync.CheckResult.Skipped ->
                        error = "没有可用的学工会话，先打开报修页登录一次"
                }
                busy = false
                refreshedOnce = true
            }
        }
    }

    // 缓存里没有目标记录才打一次网络；有缓存先展示，进页不打网络
    LaunchedEffect(Unit) {
        if (!invalidTarget && record == null) refresh()
    }
    // 刷新失败 / 需要登录：用页面统一的提示宿主播一条，不弹系统 Toast
    LaunchedEffect(error) {
        error?.let {
            snackbar.showNotice(it)
            error = null
        }
    }

    // 首次进页 / 目标记录不在缓存：先给与成品同几何的加载态，别先渲染空态再跳
    val initialLoading =
        record == null && !invalidTarget && (busy || !refreshedOnce)

    PageScaffoldColumn(
        title = "宿舍报修",
        onBack = onBack,
        snackbar = snackbar,
        actions = { PageRefreshAction(busy = busy, onClick = refresh) },
    ) {
        when {
            initialLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 56.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    LoadingHint("正在读取报修进度")
                }
            }

            record == null -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyHint(
                        title = if (targetId != null || invalidTarget) {
                            "这条报修记录已不在本机缓存"
                        } else {
                            "还没有宿舍报修"
                        },
                        body = if (targetId != null || invalidTarget) {
                            "可以在学工报修页查看这条记录，或点刷新再试一次。"
                        } else {
                            "在学工报修页提交后，进度会显示在这里。"
                        },
                        actionLabel = "打开报修页",
                        onAction = { XgFormActivity.start(context, XgUrls.REPAIR) },
                    )
                }
            }

            else -> {
                RepairHero(record)
                RepairInfoCard(record)
                RepairFlowCard(record)
                RepairActionsCard(
                    record = record,
                    onOpenWeb = { XgFormActivity.start(context, XgUrls.REPAIR) },
                    onDismiss = {
                        scope.launch {
                            store.dismiss(record.formUserId)
                            onBack()
                        }
                    },
                )
                Text(
                    text = when {
                        busy -> "正在同步…"
                        snapshot.fetchedAt > 0 ->
                            "上次同步：${formatDormRepairTime(snapshot.fetchedAt)}"
                        else -> "本机暂无同步记录"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun RepairHero(record: DormRepairRecord) {
    val tone = DormRepairRules.toneOf(record).toHeroTone()
    val accent = heroToneAccent(tone)
    val status = DormRepairRules.statusLabel(record)
    val approval = DormRepairRules.approvalStatusLabel(record)
    StatusHeroCard(tone = tone) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(accent, CircleShape),
            )
            Text(
                text = status,
                style = MaterialTheme.typography.titleSmall,
                color = accent,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (approval.isNotBlank() && approval != status) {
                Text(
                    text = "审批：$approval",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = heroDetail(record),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
        )
    }
}

private fun heroDetail(record: DormRepairRecord): String {
    if (DormRepairRules.isFinished(record)) {
        val at = DormRepairRules.finishedAtOf(record) ?: record.updatedAt
        return if (at > 0) "完成于 ${formatDormRepairTime(at)}" else "该报修已办结"
    }
    DormRepairRules.currentNode(record)?.let { return "当前节点：${it.title}" }
    return if (record.submittedAt > 0) {
        "提交于 ${formatDormRepairTime(record.submittedAt)}"
    } else {
        "等待学工处理"
    }
}

@Composable
private fun RepairInfoCard(record: DormRepairRecord) {
    val rows = buildList {
        if (record.dorm.isNotBlank()) add("宿舍" to record.dorm)
        if (record.fault.isNotBlank()) add("故障" to record.fault)
        if (record.description.isNotBlank()) add("描述" to record.description)
        if (record.submittedAt > 0) add("提交" to formatDormRepairTime(record.submittedAt))
        if (DormRepairRules.isFinished(record)) {
            val at = DormRepairRules.finishedAtOf(record)
            if (at != null && at > 0) add("完成" to formatDormRepairTime(at))
        }
        if (!DormRepairRules.isFinished(record)) {
            val approvers = approverSummary(record.currentApprovers)
            if (approvers.isNotBlank()) add("处理人" to approvers)
        }
        // 审批已通过但表单还写着「已派单」时，把表单口径也列出来，不藏差异
        val heroStatus = DormRepairRules.statusLabel(record)
        if (record.repairStatus.isNotBlank() && record.repairStatus != heroStatus) {
            add("报修状态" to record.repairStatus)
        }
        add("审批" to DormRepairRules.approvalStatusLabel(record))
    }
    if (rows.isEmpty()) return
    AppCard {
        Text(
            text = "报修信息",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        rows.forEachIndexed { index, (label, value) ->
            if (index > 0) AppCardDivider()
            InfoRow(label, value)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.width(48.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun RepairFlowCard(record: DormRepairRecord) {
    val tone = DormRepairRules.toneOf(record).toHeroTone()
    val accent = heroToneAccent(tone)
    val neutral = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
    AppCard {
        Text(
            text = "审批流程",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        if (record.flowNodes.isEmpty()) {
            Text(
                text = "暂无流程信息",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            record.flowNodes.forEachIndexed { index, node ->
                if (index > 0) Spacer(Modifier.height(10.dp))
                val approvers = approverSummary(
                    node.approvers.ifEmpty { if (node.current) record.currentApprovers else emptyList() },
                )
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        modifier = Modifier
                            .padding(top = 5.dp)
                            .size(8.dp)
                            .background(if (node.current) accent else neutral, CircleShape),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = node.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (node.current) {
                                    accent
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                fontWeight = if (node.current) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (node.current) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "当前",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = accent,
                                )
                            }
                        }
                        if (approvers.isNotBlank()) {
                            Text(
                                text = approvers,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (DormRepairRules.isFinished(record)) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "流程已结束",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }
        }
    }
}

@Composable
private fun RepairActionsCard(
    record: DormRepairRecord,
    onOpenWeb: () -> Unit,
    onDismiss: () -> Unit,
) {
    AppCard {
        OutlinedButton(
            onClick = onOpenWeb,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("去学工报修页核对")
        }
        if (DormRepairRules.isFinished(record)) {
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("完成并隐藏（仅本机）")
            }
            SettingsNote("只隐藏本机卡片，报修状态仍以学工系统为准。")
        } else {
            SettingsNote("报修状态由学工系统更新；本页只读，不会修改你的单据。")
        }
    }
}
