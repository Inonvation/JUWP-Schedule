package edu.jxslu.schedule.ui.notes

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.domain.Note
import edu.jxslu.schedule.domain.imageRefs
import edu.jxslu.schedule.domain.removeImageRef
import edu.jxslu.schedule.ui.common.AppNoticeVisuals
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.AttachmentStrip
import edu.jxslu.schedule.ui.common.EmptyHint
import edu.jxslu.schedule.ui.common.ImageViewerDialog
import edu.jxslu.schedule.ui.common.LoadingHint
import edu.jxslu.schedule.ui.common.MarkdownEditor
import edu.jxslu.schedule.ui.common.MarkdownView
import edu.jxslu.schedule.ui.common.NoticeTone
import edu.jxslu.schedule.ui.common.TitleTextField
import edu.jxslu.schedule.ui.common.epochMonthDay
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import edu.jxslu.schedule.ui.common.rememberImageInserter
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.Eye
import me.rerere.hugeicons.stroke.PencilEdit02

/**
 * 笔记详情/编辑（DESIGN §3.11）：**单页双态**。
 *
 * 查看态 = 渲染 Markdown/公式/图片（图片点开全屏）；编辑态 = 标题 + 附件条 + 编辑器
 * （工具条 + 自动补全，规格见 `domain/MarkdownEdit.kt`）。删除只在详情页（确认弹窗），
 * 列表不放开删除入口，防误触。
 *
 * 保存是显式动作（顶栏「保存」），返回时若有未保存修改给确认弹窗——与 `CourseEditSheet` 同口径。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteDetailScreen(
    courseName: String,
    noteId: Long,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val noteRepo = remember { Graph.noteRepository(context) }
    val homeworkRepo = remember { Graph.homeworkRepository(context) }
    val store = remember { Graph.attachmentStore(context) }
    val snackbar = remember { SnackbarHostState() }
    val haptics = rememberAppHaptics()
    val showNotice: (String, NoticeTone) -> Unit = { message, tone ->
        scope.launch { snackbar.showSnackbar(AppNoticeVisuals(message, tone = tone)) }
    }

    // 抗重建（优化 2）：转屏 / 进程回收后未保存的编辑必须还在。
    // 输入框与「首存 id / 创建时间」走 rememberSaveable；original 是库里的基线，恢复后重新加载
    // 但**不覆盖**已恢复的输入框内容（见下面的 loadedFromDb 门闸——否则一恢复就被库里内容冲掉）。
    var original by remember { mutableStateOf<Note?>(null) }
    var savedId by rememberSaveable { mutableStateOf(noteId) }
    var createdAt by rememberSaveable { mutableStateOf(0L) }
    var title by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }
    var body by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }
    var editing by rememberSaveable { mutableStateOf(noteId == 0L) }
    var loadedFromDb by rememberSaveable { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(noteId == 0L) }
    var viewer by remember { mutableStateOf<String?>(null) }
    var showDelete by remember { mutableStateOf(false) }
    var showDiscard by remember { mutableStateOf(false) }

    LaunchedEffect(noteId) {
        if (noteId > 0) {
            val note = noteRepo.note(noteId)
            if (note != null) {
                // 基线总是刷新（脏判断/删除要用）；输入框只在首次进入时灌库里的内容
                original = note
                if (!loadedFromDb) {
                    savedId = note.id
                    createdAt = note.createdAt
                    title = TextFieldValue(note.title)
                    body = TextFieldValue(note.body)
                    editing = false
                }
                loadedFromDb = true
            }
            loaded = true
        }
    }

    val dirty = title.text != (original?.title ?: "") || body.text != (original?.body ?: "")

    /** 保存：正文归库，随后清理「本次移除且已无任何引用」的附件文件（DESIGN §4.20）。 */
    fun save(onSaved: () -> Unit = {}) {
        if (title.text.isBlank() && body.text.isBlank()) {
            showNotice("还没有内容，先写点什么", NoticeTone.Warning)
            return
        }
        // savedId/createdAt 也参与：恢复后的新笔记再次保存要更新同一行，不能再插一条
        val base = original ?: Note(
            id = savedId,
            courseName = courseName,
            title = "",
            body = "",
            createdAt = createdAt,
        )
        val newBody = body.text
        val newTitle = title.text.trim()
        val now = System.currentTimeMillis()
        scope.launch {
            val id = noteRepo.save(base.copy(title = newTitle, body = newBody))
            val removed = imageRefs(base.body) - imageRefs(newBody)
            if (removed.isNotEmpty()) {
                val referenced = store.referencedNames(noteRepo.allBodies() + homeworkRepo.allDetails())
                store.deleteIfUnreferenced(removed, referenced)
            }
            val refreshed = base.copy(id = if (base.id > 0) base.id else id, title = newTitle, body = newBody)
            // 新笔记的首存时间由仓库盖；展示与后续保存都以「现在」为准（重建后也不会变回 0）
            original = if (base.id > 0) refreshed else refreshed.copy(createdAt = now)
            savedId = original!!.id
            createdAt = original!!.createdAt
            showNotice("已保存", NoticeTone.Success)
            onSaved()
        }
    }

    fun deleteNote() {
        val note = original ?: return
        scope.launch {
            noteRepo.delete(note.id)
            val removed = imageRefs(note.body)
            if (removed.isNotEmpty()) {
                val referenced = store.referencedNames(noteRepo.allBodies() + homeworkRepo.allDetails())
                store.deleteIfUnreferenced(removed, referenced)
            }
            onBack()
        }
    }

    // 返回有未保存修改：先确认（不让用户白写一场）
    BackHandler(enabled = dirty) { showDiscard = true }

    Scaffold(
        snackbarHost = { AppSnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(if (savedId > 0) "笔记" else "新建笔记")
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (dirty) showDiscard = true else onBack()
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (editing) {
                        // 「预览」在顶栏：此前是内容里的分段按钮，正文一长就得滚回顶部才够得着
                        IconButton(
                            onClick = {
                                haptics.tap()
                                editing = false
                            },
                        ) {
                            Icon(HugeIcons.Eye, contentDescription = "预览")
                        }
                        TextButton(
                            onClick = {
                                haptics.tap()
                                save()
                            },
                            enabled = dirty || original == null,
                        ) {
                            Text("保存")
                        }
                    } else {
                        IconButton(
                            onClick = {
                                haptics.tap()
                                editing = true
                            },
                        ) {
                            Icon(HugeIcons.PencilEdit02, contentDescription = "编辑")
                        }
                    }
                    if (original != null) {
                        IconButton(onClick = { showDelete = true }) {
                            Icon(HugeIcons.Delete02, contentDescription = "删除")
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (!loaded) {
            LoadingHint("正在读取笔记", Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }

        // 图片插入：Photo Picker → 私有目录 → 光标处插引用（零权限，DESIGN §4.20）
        val pickImages = rememberImageInserter(
            value = body,
            onValueChange = { body = it },
            onNotice = showNotice,
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 标题 + 创建日期成组（4dp）：日期属于标题的副信息，不该和正文抢同一层间距
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (editing) {
                    TitleTextField(
                        value = title,
                        onValueChange = { title = it },
                        placeholder = "标题（可空）",
                    )
                } else {
                    Text(
                        text = title.text.ifBlank { "未命名笔记" },
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
                Text(
                    text = original?.let { "创建于 ${epochMonthDay(it.createdAt)}" } ?: "新笔记",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }

            if (editing) {
                val refs = imageRefs(body.text).toList()
                AttachmentStrip(
                    fileNames = refs,
                    onRemove = { name ->
                        body = TextFieldValue(removeImageRef(body.text, name))
                    },
                    onOpen = { viewer = it },
                )
                MarkdownEditor(
                    value = body,
                    onValueChange = { body = it },
                    onPickImages = pickImages,
                )
            } else {
                if (body.text.isBlank()) {
                    EmptyHint(title = "笔记是空的", body = "点右上角铅笔进入编辑。")
                } else {
                    MarkdownView(
                        markdown = body.text,
                        onImageClick = { viewer = it },
                        onLinkClick = { url ->
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            runCatching { context.startActivity(intent) }
                                .onFailure { showNotice("打不开这个链接", NoticeTone.Warning) }
                        },
                    )
                }
            }
        }
    }

    viewer?.let { fileName ->
        ImageViewerDialog(fileName = fileName, onDismiss = { viewer = null })
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("删除这篇笔记？") },
            text = { Text("删除后无法恢复（正文里的图片会一并清理）。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDelete = false
                        deleteNote()
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) { Text("取消") }
            },
        )
    }

    if (showDiscard) {
        AlertDialog(
            onDismissRequest = { showDiscard = false },
            title = { Text("放弃未保存的修改？") },
            text = { Text("这篇笔记有改动还没保存。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscard = false
                        onBack()
                    },
                ) {
                    Text("放弃")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDiscard = false
                        save()
                    },
                ) {
                    Text("保存")
                }
            },
        )
    }
}
