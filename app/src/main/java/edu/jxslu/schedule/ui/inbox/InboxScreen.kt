package edu.jxslu.schedule.ui.inbox

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.SubpageActivity
import edu.jxslu.schedule.SubpageScreen
import edu.jxslu.schedule.data.local.InboxMessageEntity
import edu.jxslu.schedule.domain.InboxKind
import edu.jxslu.schedule.ui.common.AppCard
import edu.jxslu.schedule.ui.common.DialogWindowFadeEffect
import edu.jxslu.schedule.ui.common.SkeletonBox
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert02
import me.rerere.hugeicons.stroke.BookEdit
import me.rerere.hugeicons.stroke.Calendar01
import me.rerere.hugeicons.stroke.Exchange01
import me.rerere.hugeicons.stroke.GraduationScroll
import me.rerere.hugeicons.stroke.Inbox
import me.rerere.hugeicons.stroke.Wallet01

/**
 * 收件箱（DESIGN §3.24 / §4.38，2026-10-06）：应用内重要事实的留痕——考试安排、成绩、
 * 调课差异、选课轮次、余额预警。入口在今日页顶栏「+」下拉（带未读角标）。
 *
 * 交互口径：
 * - 行右侧红点 = 未读；点行 = 标已读 + 按 [InboxMessageEntity.route] 跳二级页
 *   （route 按枚举名安全解析，认不出就不跳——老消息不会因路由演进点崩）；
 * - 顶栏「全部已读」一键已读；长按行进多选（底部「标为已读 / 删除」批量操作条，
 *   删除过确认弹窗）；删除是软删——同键重投不复活，30 天后物理清除。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { Graph.inboxRepository(context) }
    val scope = rememberCoroutineScope()

    // null = Room 首帧未到（渲染骨架防「先空态后跳真数据」）；空列表 = 真空态
    val messages by repo.messages().collectAsStateWithLifecycle(initialValue = null)
    val unreadCount by repo.unreadCount.collectAsStateWithLifecycle(initialValue = 0)
    // null = 非多选；非 null = 选中集（ScheduleChangesScreen 同款状态机）
    var selection by remember { mutableStateOf<Set<Long>?>(null) }
    var confirmingDelete by remember { mutableStateOf(false) }

    // 多选模式接管返回：先退多选再退页
    BackHandler(enabled = selection != null) { selection = null }

    fun openMessage(msg: InboxMessageEntity) {
        scope.launch { repo.markRead(msg.id) }
        val target = SubpageScreen.entries.firstOrNull { it.name == msg.route }
        if (target != null) {
            SubpageActivity.start(context, target, focusItemId = msg.focusItemId)
        }
    }

    fun toggleSelected(id: Long) {
        val current = selection ?: return
        val next = current.toMutableSet().apply { if (!add(id)) remove(id) }
        selection = next.ifEmpty { null }
    }

    fun markSelectedRead() {
        val ids = selection ?: return
        scope.launch {
            ids.forEach { repo.markRead(it) }
            selection = null
        }
    }

    fun deleteSelected() {
        val ids = selection ?: return
        scope.launch {
            repo.softDelete(ids)
            selection = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("收件箱") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (selection != null) {
                        IconButton(onClick = { selection = null }) {
                            Icon(Icons.Filled.Close, contentDescription = "退出多选")
                        }
                    } else {
                        TextButton(
                            onClick = { scope.launch { repo.markAllRead() } },
                            enabled = unreadCount > 0,
                        ) { Text("全部已读") }
                    }
                },
            )
        },
    ) { padding ->
        val list = messages
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                list == null -> SkeletonList()
                list.isEmpty() -> EmptyState()
                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(list, key = { it.id }) { msg ->
                            MessageRow(
                                msg = msg,
                                selecting = selection != null,
                                checked = msg.id in selection.orEmpty(),
                                onClick = {
                                    if (selection != null) toggleSelected(msg.id) else openMessage(msg)
                                },
                                onLongClick = { selection = setOf(msg.id) },
                            )
                        }
                    }
                    if (selection != null) {
                        val size = selection.orEmpty().size
                        // 固定动作条：heightIn 防大字体档位下按钮被裁字（调课处置页同款纪律）
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                onClick = ::markSelectedRead,
                                enabled = size > 0,
                                modifier = Modifier.heightIn(min = 44.dp),
                            ) { Text("标为已读") }
                            Button(
                                onClick = { confirmingDelete = true },
                                enabled = size > 0,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 44.dp),
                            ) {
                                Text("删除所选（$size）", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmingDelete) {
        DialogWindowFadeEffect()
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("删除所选消息？") },
            text = {
                Text(
                    "将删除选中的 ${selection.orEmpty().size} 条消息。" +
                        "若同一事件后续又有更新（如余额持续偏低），会以新消息重新出现。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    deleteSelected()
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text("取消") }
            },
        )
    }
}

/** 消息行：kind 图标 + 标题/正文/时间 + 右侧未读红点（多选时换成勾选框）。 */
@Composable
private fun MessageRow(
    msg: InboxMessageEntity,
    selecting: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val unread = msg.readAt == null
    AppCard(
        onClick = onClick,
        onClickLabel = msg.title,
        onLongClick = onLongClick,
        highlighted = checked,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                kindIcon(msg.kind),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    msg.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    msg.body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    timeLabel(msg.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                )
            }
            Spacer(Modifier.size(8.dp))
            if (selecting) {
                Checkbox(checked = checked, onCheckedChange = null)
            } else if (unread) {
                // 未读红点钉在行右侧（用户口径）
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error),
                )
            }
        }
    }
}

/** 首帧骨架：几何与成品行一致（图标位 + 三行文本），防「先空态后跳数据」。 */
@Composable
private fun SkeletonList() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(4) {
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SkeletonBox(Modifier.size(22.dp))
                    Spacer(Modifier.size(10.dp))
                    Column(Modifier.weight(1f)) {
                        SkeletonBox(Modifier.fillMaxWidth(0.5f).heightIn(min = 20.dp))
                        Spacer(Modifier.size(4.dp))
                        SkeletonBox(Modifier.fillMaxWidth(0.85f).heightIn(min = 16.dp))
                        Spacer(Modifier.size(4.dp))
                        SkeletonBox(Modifier.fillMaxWidth(0.3f).heightIn(min = 16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            HugeIcons.Inbox,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
            modifier = Modifier.size(44.dp),
        )
        Spacer(Modifier.size(12.dp))
        Text("暂无消息", style = MaterialTheme.typography.bodyLarge)
        Text(
            "考试安排、成绩、调课、余额预警等重要信息会自动收进来。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        )
    }
}

/** 各来源的行首图标（图标名全部查过本地 JAR 或项目已在用，勿在此替换成没查过的名字）。 */
private fun kindIcon(kind: String) = when (
    runCatching { InboxKind.valueOf(kind) }.getOrDefault(InboxKind.EXAM)
) {
    InboxKind.EXAM -> HugeIcons.Calendar01
    InboxKind.SCORE -> HugeIcons.GraduationScroll
    InboxKind.SCHEDULE_CHANGE -> HugeIcons.Exchange01
    InboxKind.SELECTION_ROUND -> HugeIcons.BookEdit
    InboxKind.POWER_LOW -> HugeIcons.Alert02
    InboxKind.YKT_LOW -> HugeIcons.Wallet01
}

/** 时间标签：当天只给 HH:mm，当年给「M月d日 HH:mm」，更早带年份。 */
private fun timeLabel(createdAt: Long): String {
    val zone = ZoneId.systemDefault()
    val time = Instant.ofEpochMilli(createdAt).atZone(zone)
    val today = LocalDate.now(zone)
    return when {
        time.toLocalDate() == today -> time.format(DateTimeFormatter.ofPattern("HH:mm"))
        time.year == today.year -> time.format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))
        else -> time.format(DateTimeFormatter.ofPattern("yyyy年M月d日"))
    }
}
