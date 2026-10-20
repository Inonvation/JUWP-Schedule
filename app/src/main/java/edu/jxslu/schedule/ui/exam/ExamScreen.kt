package edu.jxslu.schedule.ui.exam

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.SubpageActivity
import edu.jxslu.schedule.SubpageScreen
import edu.jxslu.schedule.data.jw.JwUrls
import edu.jxslu.schedule.data.repo.ExamSnapshotStore
import edu.jxslu.schedule.data.repo.ExamSync
import edu.jxslu.schedule.domain.ExamChangeDetector
import edu.jxslu.schedule.domain.ExamMapper.ExamEntry
import edu.jxslu.schedule.ui.common.AppCard
import edu.jxslu.schedule.ui.common.AppNoticeVisuals
import edu.jxslu.schedule.ui.common.PageEmpty
import edu.jxslu.schedule.ui.common.PageRefreshAction
import edu.jxslu.schedule.ui.common.PageScaffold
import edu.jxslu.schedule.ui.common.NoticeTone
import edu.jxslu.schedule.ui.common.showNotice
import edu.jxslu.schedule.ui.jwvw.JwWebActionButton
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 考试页（DESIGN §4.33，2026-09-30）：考试变动提醒从课表 hub 内联两行抽成独立入口，
 * 入口最终落在「我的 → 学习 → 成绩与考试 → 考试安排」（考试与成绩同属教务给的学习
 * 数据，用户口径），页内把「教务眼里的考试安排」一条一卡铺开——口径与成绩页一致：
 * 数据来自教务，不依赖是否导入过课表。
 *
 * 两件事：
 *
 * 1. **考试列表**：读 [ExamSnapshotStore]（[ExamSync] 每次检查成功后落的基线）——
 *    顶栏「更新」强制走一次 `ExamSync.sync(force = true)`（OkHttp 直取，不写课表），
 *    本次探测到的变动在对应卡上标「新增 / 调整」；已结束的排到列表末尾并置灰；
 * 2. **设置入口**：提醒开关 / 自动检查间隔 / 导入到课表统一收进「考试设置」
 *    （[SubpageScreen.EXAM_SETTINGS]）。考试进课表仍只有手动导入一条路（DESIGN §4.14 红线）。
 *
 * 导入窗口现在从「考试设置」页打开，本页不再消费导入结果。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExamScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val prefs = remember { Graph.displayPrefs(context) }
    val snapshotStore = remember { ExamSnapshotStore(context) }

    // null = 还没读完盘：首帧不渲染空态，免得「还没有考试安排」闪一下再跳真实列表
    var snapshot by remember { mutableStateOf<ExamSnapshotStore.Snapshot?>(null) }
    var checkedAtMillis by remember { mutableStateOf(0L) }
    var busy by remember { mutableStateOf(false) }

    /**
     * 本次进页手动更新探测到的变动（身份键 → 新增/调整）。只活在本页会话里，离开即丢；
     * 变动检测同时已把新基线落盘，所以列表本身显示的就是更新后的数据。
     */
    var changes by remember { mutableStateOf<Map<String, ExamChangeDetector.Kind>>(emptyMap()) }

    suspend fun reload() {
        snapshot = withContext(Dispatchers.IO) { snapshotStore.load() }
        checkedAtMillis = prefs.examAlertCheckMillis()
    }

    LaunchedEffect(Unit) { reload() }

    fun refresh() {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val result = Graph.examSync(context).sync(force = true)
                when (result) {
                    is ExamSync.Result.Updated -> {
                        changes = result.changes
                            .associate { ExamChangeDetector.keyOf(it.entry) to it.kind }
                        reload()
                        val message = when {
                            result.count == 0 -> "${result.term} 还没有考试安排（教务考前数周才录入）"
                            result.changes.isEmpty() -> "已更新：${result.term} 共 ${result.count} 场"
                            else -> "已更新：${result.term}，检测到 ${result.changes.size} 条变动"
                        }
                        snackbar.showNotice(
                            AppNoticeVisuals(
                                message,
                                tone = if (result.changes.isEmpty()) {
                                    NoticeTone.Info
                                } else {
                                    NoticeTone.Success
                                },
                            ),
                        )
                    }
                    is ExamSync.Result.Failed -> snackbar.showNotice(
                        AppNoticeVisuals(result.reason, tone = NoticeTone.Error),
                    )
                    // force=true 不会被闸门挡下；真撞上并发就让位，不排队重抓一遍
                    ExamSync.Result.InProgress -> Unit
                    ExamSync.Result.Skipped -> reload()
                }
            } finally {
                busy = false
            }
        }
    }

    val rows = remember(snapshot, checkedAtMillis, changes) {
        snapshot?.let { buildRows(it, checkedAtMillis, changes) }.orEmpty()
    }
    PageScaffold(
        title = "考试安排",
        onBack = onBack,
        snackbar = snackbar,
        actions = {
            JwWebActionButton("考试安排", JwUrls.EXAM_QUERY)
            PageRefreshAction(busy = busy, onClick = { refresh() }, label = "从教务更新")
            IconButton(onClick = { SubpageActivity.start(context, SubpageScreen.EXAM_SETTINGS) }) {
                Icon(Icons.Filled.Settings, contentDescription = "考试设置")
            }
        },
        loading = snapshot == null,
        loadingText = "正在读取考试安排",
        empty = if (rows.isEmpty()) {
            PageEmpty(
                title = "还没有考试安排",
                body = "教务通常在考前数周才录入考试。点右上角「更新」从教务读一次；" +
                    "也可能这一学期还没排考。",
                actionLabel = "从教务更新",
                onAction = { refresh() },
            )
        } else null,
    ) {
        items(rows) { row ->
            when (row) {
                is ExamRow.Header -> ExamHeader(row)
                is ExamRow.Label -> GroupLabel(row.text)
                is ExamRow.Item -> ExamCard(row)
            }
        }
        item {
            Text(
                "列表是教务最近一次检查到的考试安排，与「考试变动提醒」同一份数据；" +
                    "考试只提醒不自动写课表，提醒设置与导入都在右上角「设置」里。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** 列表行：学期头 / 分组标签（「已结束」）/ 考试卡混排。 */
private sealed interface ExamRow {
    data class Header(val term: String, val count: Int, val checkedAtMillis: Long) : ExamRow
    data class Label(val text: String) : ExamRow
    data class Item(
        val entry: ExamEntry,
        val past: Boolean,
        val change: ExamChangeDetector.Kind?,
    ) : ExamRow
}

/** 带解析结果的一行；`date == null` 的行排在有日期之后（解析层已挡脏日期，这里是兜底）。 */
private class Dated(val entry: ExamEntry, val date: LocalDate?)

/**
 * 列表顺序：**待考的按日期升序在前**（最近要考的先看到），已结束的整块挪到末尾、
 * 内部按日期倒序（刚考完的在最前），两块之间插一行「已结束」标签。
 */
private fun buildRows(
    snapshot: ExamSnapshotStore.Snapshot,
    checkedAtMillis: Long,
    changes: Map<String, ExamChangeDetector.Kind>,
): List<ExamRow> {
    val today = LocalDate.now()
    val dated = snapshot.exams.map { row ->
        val entry = ExamSnapshotStore.rowToEntry(row)
        Dated(entry, parseExamDate(entry.date))
    }
    val upcoming = dated
        .filter { it.date?.isBefore(today) != true }
        .sortedWith(compareBy({ it.date ?: LocalDate.MAX }, { it.entry.startTime }))
    val finished = dated
        .filter { it.date?.isBefore(today) == true }
        .sortedWith(compareByDescending<Dated> { it.date }.thenBy { it.entry.startTime })

    val rows = mutableListOf<ExamRow>()
    if (snapshot.term.isNotBlank()) {
        rows += ExamRow.Header(snapshot.term, dated.size, checkedAtMillis)
    }
    upcoming.forEach { rows += itemOf(it, past = false, changes) }
    if (finished.isNotEmpty()) {
        if (upcoming.isNotEmpty()) rows += ExamRow.Label("已结束")
        finished.forEach { rows += itemOf(it, past = true, changes) }
    }
    return rows
}

private fun itemOf(
    dated: Dated,
    past: Boolean,
    changes: Map<String, ExamChangeDetector.Kind>,
): ExamRow.Item = ExamRow.Item(
    entry = dated.entry,
    past = past,
    change = changes[ExamChangeDetector.keyOf(dated.entry)],
)

/** 学期 + 场次 + 上次检查时间：列表口径（教务那份）在这一行说清。 */
@Composable
private fun ExamHeader(row: ExamRow.Header) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            "${row.term} · 共 ${row.count} 场",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Text(
            formatCheckedAt(row.checkedAtMillis),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
        )
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        modifier = Modifier.padding(top = 2.dp),
    )
}

/**
 * 考试卡：卡面结构与成绩卡同语言——左侧课程名 + 时刻 + 教师/考场/座位，
 * 右侧日期 + 星期。已结束的整卡降透明度，日期不再用主色。
 */
@Composable
private fun ExamCard(row: ExamRow.Item) {
    val entry = row.entry
    val date = parseExamDate(entry.date)
    val time = formatTimeRange(entry.startTime, entry.endTime)
    val place = listOf(entry.room, entry.campus)
        .filter { it.isNotBlank() }
        .distinct()
        .joinToString(" ")
    val meta = listOfNotNull(
        entry.teacher.takeIf { it.isNotBlank() },
        place.takeIf { it.isNotBlank() },
        entry.seatNo.takeIf { it.isNotBlank() }?.let { "座位 $it" },
    ).joinToString(" · ")

    AppCard(modifier = Modifier.alpha(if (row.past) 0.6f else 1f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        entry.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    row.change?.let { kind ->
                        Spacer(Modifier.width(6.dp))
                        ExamBadge(
                            text = if (kind == ExamChangeDetector.Kind.NEW) "新增" else "调整",
                            emphasize = true,
                        )
                    }
                    if (row.past) {
                        Spacer(Modifier.width(6.dp))
                        ExamBadge(text = "已结束", emphasize = false)
                    }
                }
                if (time.isNotEmpty()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        time,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    )
                }
                if (meta.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    // 学期已在列表头上，日期只留「几月几日」；解析失败才回落原始串
                    date?.let { "${it.monthValue}月${it.dayOfMonth}日" }
                        ?: entry.date.ifBlank { "时间待定" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (row.past) {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
                date?.let {
                    Text(
                        weekdayLabel(it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    )
                }
            }
        }
    }
}

/** 小徽标（新增 / 调整 / 已结束）。强调档用 secondaryContainer（同成绩卡「待评教」）。 */
@Composable
private fun ExamBadge(text: String, emphasize: Boolean) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = if (emphasize) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        },
        modifier = Modifier
            .background(
                color = if (emphasize) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                },
                shape = RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private fun parseExamDate(date: String): LocalDate? = runCatching {
    LocalDate.parse(date.trim())
}.getOrNull()

private fun weekdayLabel(date: LocalDate): String = when (date.dayOfWeek.value) {
    1 -> "周一"
    2 -> "周二"
    3 -> "周三"
    4 -> "周四"
    5 -> "周五"
    6 -> "周六"
    else -> "周日"
}

private fun formatTimeRange(start: String, end: String): String = when {
    start.isBlank() -> ""
    end.isBlank() -> "$start 起"
    else -> "$start – $end"
}

/** 上次成功检查时间；0 = 从没成功过（那时只可能看到空态或旧基线）。 */
private fun formatCheckedAt(millis: Long): String {
    if (millis <= 0) return "还没成功检查过"
    val at = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    return "更新于 ${at.monthValue}月${at.dayOfMonth}日 " + "%02d:%02d".format(at.hour, at.minute)
}
