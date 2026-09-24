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
import edu.jxslu.schedule.ui.common.SettingItem
import edu.jxslu.schedule.ui.common.SettingsSection
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.GraduationScroll
import me.rerere.hugeicons.stroke.Note01
import me.rerere.hugeicons.stroke.Task01

/** 我的 → 学习（DESIGN §3.11 / §4.15）：笔记·课件、作业与成绩查询，副标题实时计数。
 * 成绩查询 2026-09-24 自课表汇总挪入（用户要求：成绩属学习内容，不该藏在课表配置流里）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LearningHubScreen(
    onBack: () -> Unit,
    onOpenNotes: () -> Unit,
    onOpenHomework: () -> Unit,
    onOpenScores: () -> Unit,
) {
    val context = LocalContext.current
    val noteGroups by remember { Graph.noteRepository(context) }.observeGroups()
        .collectAsStateWithLifecycle(initialValue = null)
    val homeworkGroups by remember { Graph.homeworkRepository(context) }.observeGroups()
        .collectAsStateWithLifecycle(initialValue = null)

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
            }
        }
    }
}
