package edu.jxslu.schedule.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.domain.Note
import edu.jxslu.schedule.domain.hasImageRef
import edu.jxslu.schedule.domain.plainExcerpt
import edu.jxslu.schedule.ui.common.EmptyHint
import edu.jxslu.schedule.ui.common.LoadingHint
import edu.jxslu.schedule.ui.common.epochMonthDay
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Image01

/**
 * 某课程的笔记列表（DESIGN §3.11）。
 *
 * 列表项 = 标题（空标题显示占位名）+ 创建日期（**自动记录，编辑不改**）+ 正文摘要 1 行 +
 * 有图时的角标；新建入口在顶栏（不用 FAB，列表页窄屏遮挡小）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteCourseScreen(
    courseName: String,
    onBack: () -> Unit,
    onOpenNote: (Long) -> Unit,
) {
    val context = LocalContext.current
    val repo = remember { Graph.noteRepository(context) }
    val notes by repo.observeForCourse(courseName).collectAsStateWithLifecycle(initialValue = null)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(courseName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { onOpenNote(0L) }) {
                        Icon(HugeIcons.Add01, contentDescription = "新建笔记")
                    }
                },
            )
        },
    ) { padding ->
        val list = notes
        when {
            list == null -> LoadingHint("正在读取笔记", Modifier.fillMaxSize().padding(padding))

            list.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                EmptyHint(
                    title = "这门课还没有笔记",
                    body = "点右上角「+」写下第一条；文字支持 Markdown 与 \$ 公式，也能插图片。",
                )
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(list, key = { it.id }) { note ->
                    NoteRow(note = note, onClick = { onOpenNote(note.id) })
                }
            }
        }
    }
}

@Composable
private fun NoteRow(note: Note, onClick: () -> Unit) {
    val haptics = rememberAppHaptics()
    val excerpt = remember(note.body) { plainExcerpt(note.body) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f),
                shape = RoundedCornerShape(12.dp),
            )
            .clickable {
                haptics.tap()
                onClick()
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = note.title.ifBlank { "未命名笔记" },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (hasImageRef(note.body)) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = HugeIcons.Image01,
                    contentDescription = "含图片",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.size(15.dp),
                )
            }
        }
        if (excerpt.isNotBlank()) {
            Text(
                text = excerpt,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(0.dp))
        Text(
            text = epochMonthDay(note.createdAt),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
        )
    }
}
