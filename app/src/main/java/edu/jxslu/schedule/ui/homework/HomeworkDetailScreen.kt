package edu.jxslu.schedule.ui.homework

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.domain.Homework
import edu.jxslu.schedule.domain.dueDetailLabel
import edu.jxslu.schedule.domain.imageRefs
import edu.jxslu.schedule.domain.removeImageRef
import edu.jxslu.schedule.ui.common.AppCard
import edu.jxslu.schedule.ui.common.AppCardDivider
import edu.jxslu.schedule.ui.common.AppNoticeVisuals
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.AttachmentStrip
import edu.jxslu.schedule.ui.common.EmptyHint
import edu.jxslu.schedule.ui.common.ImageViewerDialog
import edu.jxslu.schedule.ui.common.LoadingHint
import edu.jxslu.schedule.ui.common.MarkdownEditor
import edu.jxslu.schedule.ui.common.MarkdownView
import edu.jxslu.schedule.ui.common.NoticeTone
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import edu.jxslu.schedule.ui.common.rememberImageInserter
import edu.jxslu.schedule.ui.reminder.ClassReminder
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Eye
import me.rerere.hugeicons.stroke.Calendar03
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.PencilEdit02
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 作业详情/编辑（DESIGN §3.11）：**编辑/预览双态**（2026-09-23 改）。
 *
 * 无标题——列表行与提醒文案取正文第一行摘要（`homeworkDisplayTitle`），进页直接写正文。
 * 编辑态 = 截止日期 + 完成勾选 + 附件条 + 编辑器；预览态 = 截止日期 + 完成勾选 + MarkdownView
 * 渲染（与笔记详情同一套）。保存是显式动作，返回有未保存修改给确认弹窗。
 * 编辑器区域自己滚（外层不滚），工具条固定在底部——正文再长工具条也够得着。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeworkDetailScreen(
    courseName: String,
    homeworkId: Long,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { Graph.homeworkRepository(context) }
    val noteRepo = remember { Graph.noteRepository(context) }
    val store = remember { Graph.attachmentStore(context) }
    val snackbar = remember { SnackbarHostState() }
    val haptics = rememberAppHaptics()
    val showNotice: (String, NoticeTone) -> Unit = { message, tone ->
        scope.launch { snackbar.showSnackbar(AppNoticeVisuals(message, tone = tone)) }
    }

    // 抗重建（优化 2）：转屏 / 进程回收后未保存的编辑必须还在（口径与笔记详情一致）。
    // 截止日期存 epochDay（Long 一定可存 Bundle），Long.MIN_VALUE = 未设置 —— 不赌 LocalDate 的可保存性。
    var original by remember { mutableStateOf<Homework?>(null) }
    var savedId by rememberSaveable { mutableStateOf(homeworkId) }
    var createdAt by rememberSaveable { mutableStateOf(0L) }
    var detail by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }
    var dueEpochDay by rememberSaveable { mutableStateOf(NO_DUE) }
    val dueDate: LocalDate? = if (dueEpochDay == NO_DUE) null else LocalDate.ofEpochDay(dueEpochDay)
    var done by rememberSaveable { mutableStateOf(false) }
    var loadedFromDb by rememberSaveable { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(homeworkId == 0L) }
    var viewer by remember { mutableStateOf<String?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showDiscard by remember { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(homeworkId) {
        if (homeworkId > 0) {
            val item = repo.homework(homeworkId)
            if (item != null) {
                original = item
                if (!loadedFromDb) {
                    savedId = item.id
                    createdAt = item.createdAt
                    detail = TextFieldValue(item.detail)
                    dueEpochDay = item.dueDate?.toEpochDay() ?: NO_DUE
                    done = item.done
                }
                loadedFromDb = true
            }
            loaded = true
        }
    }

    // 新作业：写过任何字段才算「有改动」（否则空表单返回时白问一次）
    val dirty = original?.let { base ->
        detail.text != base.detail ||
            dueDate != base.dueDate ||
            done != base.done
    } ?: (detail.text.isNotBlank() || dueDate != null || done)

    fun save(onSaved: () -> Unit = {}) {
        if (detail.text.isBlank()) {
            showNotice("先写点什么", NoticeTone.Warning)
            return
        }
        // savedId/createdAt 参与：恢复后的新作业再次保存要更新同一行，不能再插一条
        val base = original ?: Homework(
            id = savedId,
            courseName = courseName,
            detail = "",
            createdAt = createdAt,
        )
        val newDetail = detail.text
        val due = dueDate
        scope.launch {
            repo.save(
                base.copy(
                    detail = newDetail,
                    dueDate = due,
                    done = done,
                ),
            )
            val removed = imageRefs(base.detail) - imageRefs(newDetail)
            if (removed.isNotEmpty()) {
                val referenced = store.referencedNames(noteRepo.allBodies() + repo.allDetails())
                store.deleteIfUnreferenced(removed, referenced)
            }
            // 作业落库会改变"下一个提醒点"：立即重排（与设置变更同口径）
            ClassReminder.enqueueCheck(context)
            onSaved()
        }
    }

    fun deleteHomework() {
        val item = original ?: return
        scope.launch {
            repo.delete(item.id)
            val removed = imageRefs(item.detail)
            if (removed.isNotEmpty()) {
                val referenced = store.referencedNames(noteRepo.allBodies() + repo.allDetails())
                store.deleteIfUnreferenced(removed, referenced)
            }
            ClassReminder.enqueueCheck(context)
            onBack()
        }
    }

    BackHandler(enabled = dirty && original != null) { showDiscard = true }

    Scaffold(
        snackbarHost = { AppSnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (original != null) "作业" else "新建作业") },
                navigationIcon = {
                    IconButton(
                        onClick = { if (dirty && original != null) showDiscard = true else onBack() },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (editing) {
                        IconButton(
                            onClick = {
                                haptics.tap()
                                editing = false
                            },
                        ) {
                            Icon(HugeIcons.Eye, contentDescription = "预览")
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
                    TextButton(
                        onClick = {
                            haptics.tap()
                            save { onBack() }
                        },
                    ) {
                        Text("保存")
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
            LoadingHint("正在读取作业", Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }

        val pickImages = rememberImageInserter(
            value = detail,
            onValueChange = { detail = it },
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
            // 两项成组（2026-09-22）：截止日期与完成状态同一张卡、中间一条分隔线。
            // 此前日期是独立描边卡、「已完成」裸放，两块既不成组，纵向还各占一层间距
            AppCard(contentPadding = PaddingValues(0.dp)) {
                // 截止日期（可空）：点击选择，右侧「清除」只在已设置时出现
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showDatePicker = true }
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = HugeIcons.Calendar03,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "截止日期",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.width(12.dp))
                    // 值占剩余宽度、右对齐：远期日期会带星期几，长文案不能把「清除」挤出卡片
                    Text(
                        text = dueDate?.let { due -> dueDetailLabel(due, LocalDate.now()) } ?: "未设置",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (dueDate != null) {
                        Spacer(Modifier.width(4.dp))
                        TextButton(onClick = { dueEpochDay = NO_DUE }) { Text("清除") }
                    }
                }
                AppCardDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            haptics.toggle()
                            done = !done
                        }
                        .padding(end = 14.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = done,
                        onCheckedChange = { checked ->
                            haptics.toggle()
                            done = checked
                        },
                    )
                    Text(
                        text = "已完成",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                    )
                }
            }

            if (editing) {
                AttachmentStrip(
                    fileNames = imageRefs(detail.text).toList(),
                    onRemove = { name -> detail = TextFieldValue(removeImageRef(detail.text, name)) },
                    onOpen = { viewer = it },
                )
                MarkdownEditor(
                    value = detail,
                    onValueChange = { detail = it },
                    placeholder = "作业要求、要提交的题号…（支持 Markdown 与 \$ 公式）",
                    minHeight = 240.dp,
                    onPickImages = pickImages,
                )
            } else {
                if (detail.text.isBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 360.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        EmptyHint(
                            title = "作业是空的",
                            body = "点右上角铅笔进入编辑。",
                        )
                    }
                } else {
                    MarkdownView(
                        markdown = detail.text,
                        modifier = Modifier.fillMaxWidth(),
                        onImageClick = { viewer = it },
                        onLinkClick = { url ->
                            if (!openLink(context, url)) showNotice("打不开这个链接", NoticeTone.Warning)
                        },
                    )
                }
            }
        }
    }

    viewer?.let { fileName ->
        ImageViewerDialog(fileName = fileName, onDismiss = { viewer = null })
    }

    if (showDatePicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (dueDate ?: LocalDate.now())
                .atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.selectedDateMillis?.let { millis ->
                            dueEpochDay = Instant.ofEpochMilli(millis)
                                .atZone(ZoneId.of("UTC"))
                                .toLocalDate()
                                .toEpochDay()
                        }
                        showDatePicker = false
                    },
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = state)
        }
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("删除这条作业？") },
            text = { Text("删除后无法恢复（详情里的图片会一并清理）。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDelete = false
                        deleteHomework()
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
            text = { Text("这条作业有改动还没保存。") },
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

/** 未设置截止日期在可保存状态里的哨兵值（Long.MIN_VALUE，正常 epochDay 不会取到）。 */
private const val NO_DUE = Long.MIN_VALUE

/** 详情里打开链接的兜底（与笔记页同口径）。 */
internal fun openLink(context: android.content.Context, url: String): Boolean =
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.isSuccess
