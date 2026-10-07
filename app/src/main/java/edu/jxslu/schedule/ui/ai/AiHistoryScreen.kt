package edu.jxslu.schedule.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.repo.AiApplyHistoryStore
import edu.jxslu.schedule.data.repo.AiApplyRecord
import edu.jxslu.schedule.ui.common.AppCard
import edu.jxslu.schedule.ui.common.AppNoticeVisuals
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.DialogWindowFadeEffect
import edu.jxslu.schedule.ui.common.NoticeTone
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 「调课记录」页（DESIGN §3.25 / §4.39，2026-10-07）：AI 调课每次应用的本机留痕，
 * 跨会话可撤销（栈式：只能撤最近一条未撤销，见 [AiHistoryViewModel]）。
 *
 * 入口在 AI 调课页顶栏（历史图标）。列表观感与全 App 一致：`AppCard` + 16dp 横距。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiHistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val viewModel: AiHistoryViewModel = viewModel(
        factory = AiHistoryViewModel.Factory(
            Graph.repository(context),
            Graph.aiApplyHistoryStore(context),
        ),
    )
    val records by viewModel.records.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    val haptics = rememberAppHaptics()

    var confirming by remember { mutableStateOf<AiApplyRecord?>(null) }

    LaunchedEffect(notice) {
        notice?.let {
            if (it.tone == NoticeTone.Success) haptics.success()
            snackbar.showSnackbar(AppNoticeVisuals(it.text, tone = it.tone))
            viewModel.consumeNotice()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("调课记录", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        if (records.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "还没有应用记录",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "在「AI 调课」里应用所选后，每次都会记在这里；\n最近一次可以撤销",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            val aliveIds by viewModel.aliveTimetableIds.collectAsStateWithLifecycle()
            val undoable = remember(records, aliveIds) {
                AiApplyHistoryStore.latestUndoable(records, aliveIds)
            }
            Column(Modifier.fillMaxSize().padding(padding)) {
                Text(
                    "只能撤销最近一次；更早的记录仅供查看",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    records.forEach { record ->
                        AiHistoryRow(
                            record = record,
                            canUndo = record.id == undoable?.id,
                            // 课表已删除的记录永远撤不动（反向写会向不存在的课表塞孤儿行），
                            // 行上标出来，别让用户点了才知道；alive 未知（首帧）时不标
                            deadTimetable = !record.undone &&
                                aliveIds?.let { record.timetableId !in it } == true,
                            onUndo = { confirming = record },
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }

    confirming?.let { record ->
        DialogWindowFadeEffect()
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text("撤销这次应用？") },
            text = {
                Text(
                    "会把这次应用的改动倒回（课表与日期覆盖在同一事务里）。" +
                        "应用之后你手动改过的行会跳过，事后如实报条数。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = null
                        viewModel.undoFromUi(record)
                    },
                ) { Text("撤销") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = null }) { Text("取消") }
            },
        )
    }
}

/** 一条应用记录：时间 + 摘要 + 计数；右侧按状态给「撤销 / 已撤销 / 课表已删除」。 */
@Composable
private fun AiHistoryRow(
    record: AiApplyRecord,
    canUndo: Boolean,
    deadTimetable: Boolean,
    onUndo: () -> Unit,
) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    timeText(record.id),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (record.summary.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        record.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    buildString {
                        append("已应用 ").append(record.applied).append(" 处")
                        if (record.appliedOverrides > 0) {
                            append("（日期覆盖 ").append(record.appliedOverrides).append("）")
                        }
                        if (record.skipped > 0) append(" · 跳过 ").append(record.skipped)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                )
            }
            when {
                record.undone -> Text(
                    "已撤销",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                )

                canUndo -> TextButton(
                    onClick = onUndo,
                    modifier = Modifier.heightIn(min = 44.dp),
                ) { Text("撤销") }

                deadTimetable -> Text(
                    "课表已删除",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                )
            }
        }
    }
}

private val SAME_YEAR = DateTimeFormatter.ofPattern("M月d日 HH:mm")
private val OTHER_YEAR = DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm")

/** 记录时间：当年省年份，跨年带年份（留痕上限 50 条，跨年是常态不是例外）。 */
private fun timeText(epochMillis: Long): String {
    val time = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
    return if (time.year == java.time.LocalDate.now().year) {
        time.format(SAME_YEAR)
    } else {
        time.format(OTHER_YEAR)
    }
}
