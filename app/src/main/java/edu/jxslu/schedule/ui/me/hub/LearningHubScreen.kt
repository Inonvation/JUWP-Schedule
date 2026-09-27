package edu.jxslu.schedule.ui.me.hub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.domain.ScholarDimension
import edu.jxslu.schedule.domain.ScholarProgressRules
import edu.jxslu.schedule.ui.common.SettingItem
import edu.jxslu.schedule.ui.common.SettingsSection
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.GraduationScroll
import me.rerere.hugeicons.stroke.Note01
import me.rerere.hugeicons.stroke.Task01
import me.rerere.hugeicons.stroke.Target01

/** 我的 → 学习（DESIGN §3.11 / §4.15）：笔记·课件、作业与成绩查询，副标题实时计数。
 * 成绩查询 2026-09-24 自课表汇总挪入（用户要求：成绩属学习内容，不该藏在课表配置流里）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LearningHubScreen(
    onBack: () -> Unit,
    onOpenNotes: () -> Unit,
    onOpenHomework: () -> Unit,
    onOpenScores: () -> Unit,
    onOpenScholar: () -> Unit,
) {
    val context = LocalContext.current
    val noteGroups by remember { Graph.noteRepository(context) }.observeGroups()
        .collectAsStateWithLifecycle(initialValue = null)
    val homeworkGroups by remember { Graph.homeworkRepository(context) }.observeGroups()
        .collectAsStateWithLifecycle(initialValue = null)
    // 学业完成情况只取「课程体系」维度算总账：四个维度是同一批课程的不同切法，
    // 混在一起求和会算成四倍（DESIGN §4.29）
    val scholarGroups by remember { Graph.scholarProgressRepository(context) }.groups
        .collectAsStateWithLifecycle(initialValue = null)
    val scholarCourses by remember { Graph.scholarProgressRepository(context) }.courses
        .collectAsStateWithLifecycle(initialValue = null)
    val scholarTotals = remember(scholarGroups, scholarCourses) {
        val systemGroups = scholarGroups?.filter { it.dimension == ScholarDimension.System.id }
        if (systemGroups == null) {
            null
        } else {
            ScholarProgressRules.totals(
                systemGroups,
                scholarCourses.orEmpty().filter { it.dimension == ScholarDimension.System.id },
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("学习") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val noteCount = noteGroups?.sumOf { it.count } ?: 0
            val courseCount = noteGroups?.size ?: 0
            SettingsSection(title = "笔记·课件") {
                SettingItem(
                    title = if (noteCount > 0) "$noteCount 篇 · $courseCount 门课" else "笔记·课件库",
                    subtitle = if (noteCount > 0) "按课程分组查看" else "暂无内容 · 记下课件与公式",
                    icon = HugeIcons.Note01,
                    onClick = onOpenNotes,
                )
            }
            val pendingCount = homeworkGroups?.sumOf { it.pending } ?: 0
            SettingsSection(title = "作业") {
                SettingItem(
                    title = if (pendingCount > 0) "$pendingCount 项未完成" else "作业库",
                    subtitle = if (pendingCount > 0) "按课程分组查看" else "暂无作业 · 可设截止提醒",
                    icon = HugeIcons.Task01,
                    onClick = onOpenHomework,
                )
            }

            SettingsSection(title = "成绩") {
                SettingItem(
                    title = "成绩查询",
                    subtitle = "按学期 · 学年汇总 · 从教务导入",
                    icon = HugeIcons.GraduationScroll,
                    onClick = onOpenScores,
                )
                SettingItem(
                    title = "学业完成情况",
                    subtitle = scholarSubtitle(scholarTotals),
                    icon = HugeIcons.Target01,
                    onClick = onOpenScholar,
                )
            }
        }
    }
}

/** 学业完成情况的副标题：有总账就报进度，没数据就说明来源。 */
private fun scholarSubtitle(totals: edu.jxslu.schedule.domain.ScholarTotals?): String {
    if (totals == null) return "培养方案达成度 · 从教务导入"
    if (totals.required <= 0.0) return "培养方案达成度 · 已修 ${trimCredit(totals.earned)} 学分"
    val line = "已修 ${trimCredit(totals.earned)} / ${trimCredit(totals.required)} 学分"
    return if (totals.remaining > 0.0) "$line · 还需 ${trimCredit(totals.remaining)}" else "$line · 已达成"
}

/** 学分展示：整数不带小数点（79.0 → 79），小数最多两位。 */
private fun trimCredit(value: Double): String {
    val rounded = Math.round(value * 100) / 100.0
    return if (rounded == rounded.toLong().toDouble()) {
        rounded.toLong().toString()
    } else {
        rounded.toString().trimEnd('0').trimEnd('.')
    }
}
