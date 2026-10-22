package edu.jxslu.schedule.ui.notes

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.startActivityOutsideApp
import edu.jxslu.schedule.domain.Note
import edu.jxslu.schedule.domain.imageRefs
import edu.jxslu.schedule.domain.removeImageRef
import edu.jxslu.schedule.ui.common.AppNoticeVisuals
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.DialogWindowFadeEffect
import edu.jxslu.schedule.ui.common.EmptyHint
import edu.jxslu.schedule.ui.common.ImageViewerDialog
import edu.jxslu.schedule.ui.common.LoadingHint
import edu.jxslu.schedule.ui.common.MarkdownEditorBottomBar
import edu.jxslu.schedule.ui.common.MarkdownEditorField
import edu.jxslu.schedule.ui.common.MarkdownView
import edu.jxslu.schedule.ui.common.NoticeTone
import edu.jxslu.schedule.ui.common.TitleTextField
import edu.jxslu.schedule.ui.common.epochMonthDay
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import edu.jxslu.schedule.ui.common.rememberImageInserter
import edu.jxslu.schedule.ui.common.showNotice
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Eye
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.PencilEdit02

/**
 * 笔记详情/编辑（DESIGN §3.11）：**单页双态**，2026-10-21 重排。
 *
 * 编辑态 = 标题固定 + 正文输入框占满剩余高度（内部滚动），附件条与工具条放
 * `Scaffold.bottomBar` 并 `imePadding()`；预览态 = 标题 + 创建/更新时间 + MarkdownView。
 * 保存语义与作业一致：保存 = 落库 + 切回预览 + 「已保存」+ 留在本页。
 * 删除收进溢出菜单（列表不放开删除入口，防误触）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteDetailScreen(
    courseName: String,
    noteId: Long,
    startEditing: Boolean = false,
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
        scope.launch { snackbar.showNotice(AppNoticeVisuals(message, tone = tone)) }
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
    var editing by rememberSaveable { mutableStateOf(noteId == 0L || startEditing) }
    var loadedFromDb by rememberSaveable { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(noteId == 0L) }
    var viewer by remember { mutableStateOf<String?>(null) }
    var showDelete by remember { mutableStateOf(false) }
    var showDiscard by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    // 从查看态点铅笔进编辑后主动聚焦标题（光标在末尾），省一次点击；
    // loaded 守卫：载入中标题框还没组合，requestFocus 会抛 FocusRequester not initialized
    val titleFocus = remember { FocusRequester() }
    LaunchedEffect(editing, loaded) {
        if (editing && loaded) {
            titleFocus.requestFocus()
            title = title.copy(selection = TextRange(title.text.length))
        }
    }

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
                    editing = noteId == 0L || startEditing
                }
                loadedFromDb = true
            }
            loaded = true
        }
    }

    val dirty = title.text != (original?.title ?: "") || body.text != (original?.body ?: "")

    /** 保存/删除进行中：不挡的话双击「保存」会对新建笔记插两条（original 还没回写，两次 base.id 都是 0）。 */
    var busy by remember { mutableStateOf(false) }

    /** 保存：正文归库，随后清理「本次移除且已无任何引用」的附件文件（DESIGN §4.20）。 */
    fun save(onSaved: () -> Unit = {}) {
        if (busy) return
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
        val titleAtSave = title.text
        val now = System.currentTimeMillis()
        busy = true
        scope.launch {
            try {
                val id = noteRepo.save(base.copy(title = newTitle, body = newBody))
                val removed = imageRefs(base.body) - imageRefs(newBody)
                if (removed.isNotEmpty()) {
                    val referenced = store.referencedNames(noteRepo.allBodies() + homeworkRepo.allDetails())
                    store.deleteIfUnreferenced(removed, referenced)
                }
                val refreshed = base.copy(id = if (base.id > 0) base.id else id, title = newTitle, body = newBody)
                // 新笔记的首存时间由仓库盖；展示与后续保存都以「现在」为准（重建后也不会变回 0）
                original = if (base.id > 0) {
                    refreshed.copy(updatedAt = now)
                } else {
                    refreshed.copy(createdAt = now, updatedAt = now)
                }
                // 输入框里可能还有首尾空格；保存期间没有新输入才回写 trim 后的值，
                // 否则会把用户刚敲的字冲掉（新输入会让 dirty 重新变 true）
                if (title.text == titleAtSave && titleAtSave != newTitle) {
                    title = TextFieldValue(newTitle, selection = TextRange(newTitle.length))
                }
                savedId = original!!.id
                createdAt = original!!.createdAt
                onSaved()
            } finally {
                busy = false
            }
        }
    }

    fun saveAndPreview() {
        save {
            editing = false
            showNotice("已保存", NoticeTone.Success)
        }
    }

    fun deleteNote() {
        if (busy) return
        val note = original ?: return
        busy = true
        scope.launch {
            try {
                noteRepo.delete(note.id)
                val removed = imageRefs(note.body)
                if (removed.isNotEmpty()) {
                    val referenced = store.referencedNames(noteRepo.allBodies() + homeworkRepo.allDetails())
                    store.deleteIfUnreferenced(removed, referenced)
                }
                onBack()
            } finally {
                busy = false
            }
        }
    }

    // 返回有未保存修改：先确认（不让用户白写一场）
    BackHandler(enabled = dirty) { showDiscard = true }

    // 图片插入：Photo Picker → 私有目录 → 光标处插引用（零权限，DESIGN §4.20）
    val pickImages = rememberImageInserter(
        value = body,
        onValueChange = { body = it },
        onNotice = showNotice,
    )

    val created = original?.createdAt ?: createdAt
    val updated = original?.updatedAt ?: created
    val dateText = when {
        original == null -> "新笔记"
        updated > created -> "创建于 ${epochMonthDay(created)} · 更新于 ${epochMonthDay(updated)}"
        else -> "创建于 ${epochMonthDay(created)}"
    }
    val topSubtitle = when {
        original == null && savedId == 0L -> "笔记 · 新建"
        original == null -> "笔记"
        else -> "笔记 · 更新于 ${epochMonthDay(updated)}"
    }

    Scaffold(
        snackbarHost = { AppSnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = courseName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = topSubtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
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
                                saveAndPreview()
                            },
                            enabled = (dirty || original == null) && !busy,
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
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(HugeIcons.MoreVertical, contentDescription = "更多")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (original != null) {
                                DropdownMenuItem(
                                    text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                                    onClick = {
                                        menuOpen = false
                                        showDelete = true
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (editing) {
                MarkdownEditorBottomBar(
                    value = body,
                    onValueChange = { body = it },
                    onPickImages = pickImages,
                    onRemoveImage = { name ->
                        body = TextFieldValue(removeImageRef(body.text, name))
                    },
                    onOpenImage = { viewer = it },
                )
            }
        },
    ) { padding ->
        if (!loaded) {
            LoadingHint("正在读取笔记", Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }

        if (editing) {
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TitleTextField(
                        value = title,
                        onValueChange = { title = it },
                        placeholder = "标题（可空）",
                        focusRequester = titleFocus,
                    )
                    Text(
                        text = dateText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                }
                MarkdownEditorField(
                    value = body,
                    onValueChange = { body = it },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = "写点什么…支持 Markdown 与 \$ 公式",
                    // 外层 weight(1f) 已经给足高度；这里不再设 min，键盘弹起时不会把底栏顶出去
                    minHeight = 0.dp,
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title.text.ifBlank { "未命名笔记" },
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = dateText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
                if (body.text.isBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        EmptyHint(title = "笔记是空的", body = "点右上角铅笔进入编辑。")
                    }
                } else {
                    MarkdownView(
                        markdown = body.text,
                        modifier = Modifier.padding(top = 8.dp),
                        onImageClick = { viewer = it },
                        onLinkClick = { url ->
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            runCatching { context.startActivityOutsideApp(intent) }
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
            text = {
                // 弹窗进出场动画：必须在弹窗窗口自己的组合里（内容槽第一行）。
                DialogWindowFadeEffect()
                Text("删除后无法恢复（正文里的图片会一并清理）。")
            },
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
            text = {
                // 弹窗进出场动画：必须在弹窗窗口自己的组合里（内容槽第一行）。
                DialogWindowFadeEffect()
                Text("这篇笔记有改动还没保存。")
            },
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
                        save { onBack() }
                    },
                ) {
                    Text("保存")
                }
            },
        )
    }
}
