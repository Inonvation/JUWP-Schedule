package edu.jxslu.schedule.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.SubpageActivity
import edu.jxslu.schedule.SubpageScreen
import edu.jxslu.schedule.data.local.InboxMessageEntity
import edu.jxslu.schedule.domain.DormRepairRecord
import edu.jxslu.schedule.domain.DormRepairRules
import edu.jxslu.schedule.domain.InboxKind
import edu.jxslu.schedule.ui.common.AppCard
import edu.jxslu.schedule.ui.common.formatDormRepairTime
import edu.jxslu.schedule.ui.common.heroToneAccent
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import edu.jxslu.schedule.ui.common.toHeroTone
import edu.jxslu.schedule.ui.inbox.inboxKindIcon
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ChevronRight
import me.rerere.hugeicons.stroke.Inbox
import me.rerere.hugeicons.stroke.Repair

/**
 * 今日页收件箱卡（DESIGN §3.24，2026-10-21 收口）：收件箱的**唯一展示入口**。
 *
 * 卡内自上而下：
 * 1. **宿舍报修行**（[DormRepairRules.cardRecord] 口径：在途优先，否则 3 天内已完成且
 *    未被「完成并隐藏」）——在途状态不是 inbox 消息，不写 `inbox_messages`（收件箱只收
 *    「发生过的事实」，在途是随时会刷新的当前态），渲染在这里只是**状态展示**；
 *    点行进 `DORM_REPAIR` 详情页。没有可见记录时整行不渲染；
 * 2. **今天的消息**：只列今天（`InboxRules.timeGroupOf` 的 TODAY 桶同口径，本地时区）；
 *    点行 = 标已读 + 按 route 跳二级页（与收件箱页同一套「按枚举名安全解析，认不出就不跳」）；
 * 3. 标题行右侧「查看更多」打开收件箱页（完整列表：昨天 / 本周 / 更早 + 一键已读 + 多选删除）。
 *
 * 今天没有消息时消息区收成一行：「今天没有新消息」+ 查看更多（收件箱可能有更早的消息，
 * 入口不能断——这也是它必须常驻而不是按天显隐的原因）。
 */
@Composable
fun InboxTodayCard(
    /** 报修当前该展示的那条（`DormRepairRules.cardRecord` 的结果）；null = 无可见记录。 */
    repair: DormRepairRecord? = null,
    onOpenInbox: () -> Unit = {},
    /** 报修行点击：进原生报修详情页（与旧独立报修卡同落点）。 */
    onOpenRepair: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repo = remember(context) { Graph.inboxRepository(context) }
    val scope = rememberCoroutineScope()
    // null = Room 首帧未到；先按空态渲染，数据到了自然撑开
    val messages by repo.messages().collectAsStateWithLifecycle(initialValue = null)

    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    // 只留今天的：列表本身最新在前，过滤保序；上一条与这一条比较的口径足够（无跨天穿插）
    val todays = messages.orEmpty().filter {
        Instant.ofEpochMilli(it.createdAt).atZone(zone).toLocalDate() == today
    }
    // 标题计数 = 今天**全部**消息数（2026-10-21 二次口径修正：与「还有 N 条」同一总数，
    // 数字和列表对得上——此前只数待办类，出现过「今日 1 条」却铺着 5 行的矛盾）。
    // 红点仍只属于待办类：账单是静默留痕（silent），不影响计数口径。
    val headlineCount = todays.size
    val important = todays.filter { it.kind !in NOTICE_WORTHY_EXCLUDED_KINDS }
    val bills = todays.filter { it.kind in NOTICE_WORTHY_EXCLUDED_KINDS }
    // 今日卡固定铺 **3 行**（2026-10-21 用户口径），**报修状态行占其中 1 行**——
    // 卡片整体不再长长一截。可选消息槽不足 3 个时按实际条数铺。
    val repairSlots = if (repair != null) 1 else 0
    val messageSlots = (TODAY_PREVIEW_LIMIT - repairSlots).coerceAtLeast(0)
    // 待办类优先占位：纯时间截断时，一串账单会把带红点的消息挤进「还有 N 条」（真机踩到）。
    // 选完槽位再按时间排序渲染，预览里既有最新流水，也一定看得到需要处理的那条。
    val importantShown = important.take(messageSlots)
    val selected = (importantShown + bills.take(messageSlots - importantShown.size))
        .sortedByDescending { it.createdAt }
    val moreCount = todays.size - selected.size

    AppCard(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        val haptics = rememberAppHaptics()
        // 标题行：「收件箱」+ 今日条数 + 查看更多（右缘）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 32.dp)
                // 整行命中区点开收件箱页；「查看更多」是行尾的视觉指引，不再单独一小块
                .clickable(onClickLabel = "打开收件箱") {
                    haptics.tap()
                    onOpenInbox()
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = HugeIcons.Inbox,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "收件箱",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            if (headlineCount > 0) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "今日 $headlineCount 条",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    maxLines = 1,
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = "查看更多",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
            Icon(
                imageVector = HugeIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
        }

        // 报修在途/近完成置顶：比一般消息更值得关注（提交人自己盯的事）
        if (repair != null) {
            Spacer(Modifier.height(6.dp))
            RepairTodayRow(record = repair, onClick = onOpenRepair)
        }

        // 今天有消息才铺行；没有时收成一行副文（入口不能断）
        if (todays.isEmpty()) {
            Text(
                text = "今天没有新消息",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 30.dp, top = 2.dp),
            )
        } else {
            // 报修行自带上下 6dp 内距；有它时不再叠这层 6dp，否则「报修 → 首条消息」
            // 是 18dp，比消息之间（6+6=12dp）大出一截（2026-10-21 真机反馈）
            if (repair == null) Spacer(Modifier.height(6.dp))
            selected.forEach { msg ->
                InboxTodayRow(
                    msg = msg,
                    onClick = {
                        scope.launch { repo.markRead(msg.id) }
                        val target = SubpageScreen.entries.firstOrNull { it.name == msg.route }
                        if (target != null) {
                            SubpageActivity.start(context, target, focusItemId = msg.focusItemId)
                        }
                    },
                )
            }
            if (moreCount > 0) {
                // 余量出口：不铺全部，一行收口（点开收件箱页看完整列表）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClickLabel = "查看全部消息") {
                            haptics.tap()
                            onOpenInbox()
                        }
                        .padding(start = 30.dp, top = 4.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "还有 $moreCount 条",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/**
 * 收件箱卡内的报修状态行（原独立报修卡 2026-10-21 并入）：
 * 状态词走 [DormRepairRules.statusLabel] + 语气色（与详情页英雄卡同色系），
 * 跟当前进度（节点 / 完成时间 / 提交时间）；副行「宿舍 · 故障」。整行点进详情页。
 */
@Composable
private fun RepairTodayRow(
    record: DormRepairRecord,
    onClick: () -> Unit,
) {
    val tone = DormRepairRules.toneOf(record).toHeroTone()
    val accent = heroToneAccent(tone)
    val haptics = rememberAppHaptics()
    val secondary = listOf(record.dorm, record.fault)
        .filter { it.isNotBlank() }
        .joinToString(" · ")
        .ifBlank { "点按查看报修进度" }
    val detail = repairDetailLine(record)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(onClickLabel = "查看报修进度") {
                haptics.tap()
                onClick()
            }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = HugeIcons.Repair,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "宿舍报修",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = DormRepairRules.statusLabel(record),
                    style = MaterialTheme.typography.bodyMedium,
                    color = accent,
                    maxLines = 1,
                )
                if (detail != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Text(
                text = secondary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = HugeIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * 报修行的进度短句：完成时间 / 当前节点 / 提交时间；都没有时返回 null（不占位）。
 * 口径与详情页英雄卡一致（`DormRepairRules`），前缀「完成于/提交于」在这里保留——
 * 行内空间窄，状态词与进度并排时时间需要交代自己是什么。
 */
private fun repairDetailLine(record: DormRepairRecord): String? {
    if (DormRepairRules.isFinished(record)) {
        val at = DormRepairRules.finishedAtOf(record) ?: record.updatedAt
        return if (at > 0) "完成于 ${formatDormRepairTime(at)}" else null
    }
    DormRepairRules.currentNode(record)?.let { return it.title }
    return if (record.submittedAt > 0) {
        "提交于 ${formatDormRepairTime(record.submittedAt)}"
    } else {
        null
    }
}

/**
 * 今日页收件箱卡的行（2026-10-21 改两行排版）：
 * - 第一行 = 图标 + 标题 + 时间 + 未读点；
 * - 第二行 = 正文（**满宽**、最多两行省略）。
 *
 * 此前标题与正文挤同一行对拆（各占 weight 一半），账单类正文（「车号 · 骑行 13 分钟；
 * 费用 ¥1.50；已自动结清」）必然截断——摘要被截的消息等于没写。两行后正文拿到整行宽，
 * 长账单也能读全；行高随正文 1~2 行自然伸缩。
 */
@Composable
private fun InboxTodayRow(
    msg: InboxMessageEntity,
    onClick: () -> Unit,
) {
    val unread = msg.readAt == null
    val zone = ZoneId.systemDefault()
    val haptics = rememberAppHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = msg.title) {
                haptics.tap()
                onClick()
            }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = inboxKindIcon(msg.kind),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = msg.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = Instant.ofEpochMilli(msg.createdAt).atZone(zone)
                        .format(DateTimeFormatter.ofPattern("HH:mm")),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                )
                Spacer(Modifier.width(6.dp))
                if (unread) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(MaterialTheme.colorScheme.error, CircleShape),
                    )
                } else {
                    // 已读占位，保证时间列右缘对齐
                    Spacer(Modifier.size(8.dp))
                }
            }
            Text(
                text = msg.body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
    }
}

/**
 * 今日卡标题计数**不统计**的来源（2026-10-21）：账单/流水类消息是静默留痕（`silent`，
 * 不点未读红点），计数也要和红点语义一致——否则「今日 6 条」里 5 条是流水，数字失真。
 * 消息本身仍在列表里显示、点开照样可回查。
 */
private val NOTICE_WORTHY_EXCLUDED_KINDS = setOf(
    InboxKind.KVCX_RIDE.name,
    InboxKind.WATER_SESSION.name,
    InboxKind.QZXY_SESSION.name,
    InboxKind.UJING_ORDER.name,
)

/** 今日卡最多铺多少行（消息行 + 报修状态行合计，2026-10-21 用户口径 3 行）。 */
private const val TODAY_PREVIEW_LIMIT = 3
