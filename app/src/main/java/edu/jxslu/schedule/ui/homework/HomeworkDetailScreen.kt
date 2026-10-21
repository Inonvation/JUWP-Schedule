package edu.jxslu.schedule.ui.homework

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.domain.Homework
import edu.jxslu.schedule.domain.dueDetailLabel
import edu.jxslu.schedule.domain.imageRefs
import edu.jxslu.schedule.domain.removeImageRef
import edu.jxslu.schedule.startActivityOutsideApp
import edu.jxslu.schedule.ui.common.AppCard
import edu.jxslu.schedule.ui.common.AppCardDivider
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
import edu.jxslu.schedule.ui.common.pinnedStatusBars
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import edu.jxslu.schedule.ui.common.rememberImageInserter
import edu.jxslu.schedule.ui.common.showNotice
import edu.jxslu.schedule.ui.reminder.ClassReminder
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Calendar03
import me.rerere.hugeicons.stroke.Eye
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.PencilEdit02
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * 作业详情/编辑（DESIGN §3.11）：**编辑/预览双态**，2026-10-21 重排。
 *
 * 编辑态 = 满屏写作布局：截止 chips + 完成勾选固定在顶部，正文输入框占满剩余高度
 * （内部滚动），附件条与格式工具条放在 `Scaffold.bottomBar` 并 `imePadding()`——
 * 长文写到下半段时工具条仍在键盘上缘。
 * 预览态 = 截止/完成卡 + `MarkdownView` 渲染；截止与完成是离散状态，改动即落库，
 * 只有正文需要显式保存。
 * 保存语义与笔记一致：保存 = 落库 + 切回预览 + 「已保存」+ 留在本页；返回时才退出。
 * 删除与「保存并继续新建」收进溢出菜单。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeworkDetailScreen(
    courseName: String,
    homeworkId: Long,
    startEditing: Boolean = false,
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
        scope.launch { snackbar.showNotice(AppNoticeVisuals(message, tone = tone)) }
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
    var menuOpen by remember { mutableStateOf(false) }
    // 默认态：新建（id=0）直接进编辑；已有作业进预览，列表铅笔入口传 startEditing 直达编辑。
    var editing by rememberSaveable { mutableStateOf(homeworkId == 0L || startEditing) }
    // 「保存并继续新建」后再次聚焦正文：focusTick 变化触发 LaunchedEffect
    var focusTick by remember { mutableStateOf(0) }
    val today = LocalDate.now()
    val dueText = dueDate?.let { dueDetailLabel(it, today) } ?: "未设置"

    // 从预览点铅笔进编辑后主动聚焦正文（光标在末尾），省一次点击；
    // loaded 守卫：载入中编辑器还没组合，requestFocus 会抛 FocusRequester not initialized
    val editorFocus = remember { FocusRequester() }
    LaunchedEffect(editing, loaded, focusTick) {
        if (editing && loaded) {
            editorFocus.requestFocus()
            detail = detail.copy(selection = TextRange(detail.text.length))
        }
    }

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
                    editing = homeworkId == 0L || startEditing
                }
                loadedFromDb = true
            }
            loaded = true
        }
    }

    // 新作业：写过任何字段才算「有改动」（否则空表单返回时白问一次）。
    // 已有作业：截止/完成改动即落库，脏判断只看正文。
    val dirty = original?.let { base ->
        detail.text != base.detail
    } ?: (detail.text.isNotBlank() || dueDate != null || done)

    /** 保存/删除进行中：不挡的话双击「保存」会对新建作业插两条（original 还没回写，两次 base.id 都是 0）。 */
    var busy by remember { mutableStateOf(false) }

    /** 保存：正文归库，随后清理「本次移除且已无任何引用」的附件文件（DESIGN §4.20）。 */
    fun save(onSaved: () -> Unit = {}) {
        if (busy) return
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
        // 与 repo.save 的 createdAt 戳记条件保持一致：已有行保旧值、新行盖「现在」
        val now = System.currentTimeMillis()
        busy = true
        scope.launch {
            try {
                val id = repo.save(
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
                // 回写基线与首存 id/创建时间（与笔记详情同口径）：保存后若留在本页再存，
                // 走更新同一行而不是拿 original==null / savedId==0 再插一条
                original = base.copy(
                    id = if (base.id > 0) base.id else id,
                    detail = newDetail,
                    dueDate = due,
                    done = done,
                    createdAt = if (base.id > 0 && base.createdAt > 0) base.createdAt else now,
                    updatedAt = now,
                )
                savedId = original!!.id
                createdAt = original!!.createdAt
                // 作业落库会改变"下一个提醒点"：立即重排（与设置变更同口径）
                ClassReminder.enqueueCheck(context)
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

    fun resetForNew() {
        original = null
        savedId = 0L
        createdAt = 0L
        detail = TextFieldValue("")
        dueEpochDay = NO_DUE
        done = false
        editing = true
        focusTick++
    }

    /** 截止日期改动即落库（已有作业）；新建作业先留在本地，随首次保存一起写入。 */
    fun applyDue(newDue: LocalDate?) {
        dueEpochDay = newDue?.toEpochDay() ?: NO_DUE
        val base = original ?: return
        if (base.id <= 0) return
        original = base.copy(dueDate = newDue, updatedAt = System.currentTimeMillis())
        showNotice("截止日期已保存", NoticeTone.Success)
        // 用进程级作用域：改完立刻返回时，写库不会被页面销毁取消
        Graph.appScope.launch {
            repo.setDueDate(base.id, newDue)
            ClassReminder.enqueueCheck(context)
        }
    }

    /** 完成状态改动即落库（定向 UPDATE，不覆盖正文）。 */
    fun applyDone(newDone: Boolean) {
        done = newDone
        val base = original ?: return
        if (base.id <= 0) return
        val now = System.currentTimeMillis()
        original = base.copy(done = newDone, doneAt = if (newDone) now else null, updatedAt = now)
        showNotice(
            if (newDone) "已完成，已自动保存" else "已取消完成，已自动保存",
            NoticeTone.Success,
        )
        Graph.appScope.launch {
            repo.setDone(base.id, newDone)
            ClassReminder.enqueueCheck(context)
        }
    }

    fun deleteHomework() {
        if (busy) return
        val item = original ?: return
        busy = true
        scope.launch {
            try {
                repo.delete(item.id)
                val removed = imageRefs(item.detail)
                if (removed.isNotEmpty()) {
                    val referenced = store.referencedNames(noteRepo.allBodies() + repo.allDetails())
                    store.deleteIfUnreferenced(removed, referenced)
                }
                ClassReminder.enqueueCheck(context)
                onBack()
            } finally {
                busy = false
            }
        }
    }

    // 与笔记详情同口径：dirty 已把空表单挡掉，新建写完按返回也要弹确认（防静默丢内容）
    BackHandler(enabled = dirty) { showDiscard = true }

    // 图片插入：Photo Picker → 私有目录 → 光标处插引用（零权限，DESIGN §4.20）
    val pickImages = rememberImageInserter(
        value = detail,
        onValueChange = { detail = it },
        onNotice = showNotice,
    )

    Scaffold(
        snackbarHost = { AppSnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                windowInsets = pinnedStatusBars(),
                title = {
                    Column {
                        Text(
                            text = courseName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "作业 · 截止 $dueText",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = { if (dirty) showDiscard = true else onBack() },
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
                            DropdownMenuItem(
                                text = { Text(if (editing) "保存并继续新建" else "新建下一条") },
                                onClick = {
                                    menuOpen = false
                                    if (editing) {
                                        save {
                                            resetForNew()
                                            showNotice("已保存，继续新建", NoticeTone.Success)
                                        }
                                    } else {
                                        resetForNew()
                                    }
                                },
                            )
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
                    value = detail,
                    onValueChange = { detail = it },
                    onPickImages = pickImages,
                    onRemoveImage = { name ->
                        detail = TextFieldValue(removeImageRef(detail.text, name))
                    },
                    onOpenImage = { viewer = it },
                )
            }
        },
    ) { padding ->
        if (!loaded) {
            LoadingHint("正在读取作业", Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }

        if (editing) {
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                HomeworkMetaEditor(
                    dueDate = dueDate,
                    done = done,
                    onDueChange = ::applyDue,
                    onDoneChange = ::applyDone,
                    onCustomDate = { showDatePicker = true },
                )
                MarkdownEditorField(
                    value = detail,
                    onValueChange = { detail = it },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = "作业要求、要提交的题号…（支持 Markdown 与 \$ 公式）",
                    // 外层 weight(1f) 已经给足高度；这里不再设 min，键盘弹起时不会把底栏顶出去
                    minHeight = 0.dp,
                    focusRequester = editorFocus,
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                HomeworkMetaCard(
                    dueDate = dueDate,
                    dueText = dueText,
                    done = done,
                    onDueClick = { showDatePicker = true },
                    onClearDue = { applyDue(null) },
                    onDoneChange = ::applyDone,
                )
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
                            applyDue(
                                Instant.ofEpochMilli(millis)
                                    .atZone(ZoneId.of("UTC"))
                                    .toLocalDate(),
                            )
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
            text = {
                // 弹窗进出场动画：必须在弹窗窗口自己的组合里（内容槽第一行）。
                DialogWindowFadeEffect()
                Text("删除后无法恢复（详情里的图片会一并清理）。")
            },
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
            text = {
                // 弹窗进出场动画：必须在弹窗窗口自己的组合里（内容槽第一行）。
                DialogWindowFadeEffect()
                Text("这条作业有改动还没保存。")
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

/** 编辑态的截止快捷 chips + 完成勾选（固定在正文上方，不随正文滚动）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeworkMetaEditor(
    dueDate: LocalDate?,
    done: Boolean,
    onDueChange: (LocalDate?) -> Unit,
    onDoneChange: (Boolean) -> Unit,
    onCustomDate: () -> Unit,
) {
    val today = LocalDate.now()
    val nextSunday = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
    val haptics = rememberAppHaptics()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "截止",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.CenterVertically),
            )
            DueChipButton("今天", dueDate == today) { onDueChange(today) }
            DueChipButton("明天", dueDate == today.plusDays(1)) { onDueChange(today.plusDays(1)) }
            DueChipButton("本周日", dueDate == nextSunday) { onDueChange(nextSunday) }
            DueChipButton("自定义", false, onCustomDate)
            DueChipButton("清除", false) { onDueChange(null) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = done,
                onCheckedChange = { checked ->
                    haptics.toggle()
                    onDoneChange(checked)
                },
            )
            Text(
                text = "已完成",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
            )
        }
    }
}

/** 预览态的截止/完成卡：两个离散状态都可点，改动即落库。 */
@Composable
private fun HomeworkMetaCard(
    dueDate: LocalDate?,
    dueText: String,
    done: Boolean,
    onDueClick: () -> Unit,
    onClearDue: () -> Unit,
    onDoneChange: (Boolean) -> Unit,
) {
    AppCard(contentPadding = PaddingValues(0.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onDueClick() }
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
            Text(
                text = dueText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (dueDate != null) {
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = onClearDue) { Text("清除") }
            }
        }
        AppCardDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onDoneChange(!done) }
                .padding(end = 14.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = done,
                onCheckedChange = onDoneChange,
            )
            Text(
                text = "已完成",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
            )
        }
    }
}

/** 截止快捷 chip：选中态走主色容器，未选中走描边。 */
@Composable
private fun DueChipButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
        },
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            )
            .border(
                width = 1.dp,
                color = if (selected) Color.Transparent else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(50),
            )
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/** 未设置截止日期在可保存状态里的哨兵值（Long.MIN_VALUE，正常 epochDay 不会取到）。 */
private const val NO_DUE = Long.MIN_VALUE

/** 详情里打开链接的兜底（与笔记页同口径）。 */
internal fun openLink(context: android.content.Context, url: String): Boolean =
    runCatching {
        context.startActivityOutsideApp(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }.isSuccess
