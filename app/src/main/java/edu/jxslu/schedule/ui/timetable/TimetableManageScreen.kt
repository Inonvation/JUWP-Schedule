package edu.jxslu.schedule.ui.timetable

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.repo.ScheduleRepository
import edu.jxslu.schedule.domain.Timetable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ManageUiState(
    val timetables: List<Timetable> = emptyList(),
    val currentId: Long = 0L,
    val defaultSourceId: Long? = null,
    val counts: Map<Long, Int> = emptyMap(),
    val message: String? = null,
)

class TimetableViewModel(private val repo: ScheduleRepository) : ViewModel() {

    private val message = MutableStateFlow<String?>(null)

    val uiState: StateFlow<ManageUiState> = combine(
        repo.timetables,
        repo.currentTimetableId,
        message,
    ) { list, currentId, msg ->
        ManageUiState(
            timetables = list,
            currentId = currentId,
            defaultSourceId = repo.defaultConfigSourceId(),
            counts = list.associate { it.id to repo.timetableCourseCount(it.id) },
            message = msg,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ManageUiState())

    fun consumeMessage() {
        message.value = null
    }

    fun select(id: Long) {
        viewModelScope.launch {
            repo.setCurrentTimetable(id)
        }
    }

    fun create(name: String) {
        viewModelScope.launch {
            repo.createTimetable(name)
            message.value = "已新建课表「${name.trim()}」"
        }
    }

    fun rename(id: Long, name: String) {
        viewModelScope.launch {
            repo.renameTimetable(id, name)
        }
    }

    fun duplicate(id: Long) {
        viewModelScope.launch {
            repo.duplicateTimetable(id)
            message.value = "已复制课表"
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            val ok = repo.deleteTimetable(id)
            message.value = if (ok) "已删除课表" else "至少要保留一张课表"
        }
    }

    /** 设为/取消默认配置源。引用型默认：之后新建的课表拷贝该课表当时的设置。 */
    fun setDefaultSource(id: Long?) {
        viewModelScope.launch {
            repo.setDefaultConfigSource(id)
            message.value = if (id == null) "已取消默认配置" else "已设为新建课表的默认配置"
        }
    }

    class Factory(private val repo: ScheduleRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            TimetableViewModel(repo) as T
    }
}

/**
 * 课表管理页（DESIGN §4.9）：新建 / 切换 / 重命名 / 复制 / 删除 / 设为默认配置。
 *
 * 删除保护放在 Repository（至少保留一张；删当前自动切换），这里只负责禁用入口与文案。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimetableManageScreen(
    onBack: () -> Unit,
    viewModel: TimetableViewModel = viewModel(
        factory = TimetableViewModel.Factory(Graph.repository(LocalContext.current)),
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    var createOpen by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<Timetable?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<Timetable?>(null) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    // 根因：迁到 SubpageActivity 独立窗口后没有外层 Scaffold 垫状态栏，
    // windowInsets 归零（嵌 NavHost 时期防双倍空白的老规避）会让顶栏顶进状态栏；
    // 现走 M3 默认——TopAppBar 自行消费状态栏，contentWindowInsets 管住手势条。
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("课表管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = {
                        newName = ""
                        createOpen = true
                    }) { Text("新建课表") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "当前课表以「当前」标注；新建课表的配置（学期/作息/显示设置）默认拷贝「默认配置」标注的那张课表当时的设置。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            state.timetables.forEach { t ->
                val isCurrent = t.id == state.currentId
                val isDefaultSource = t.id == state.defaultSourceId
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isCurrent) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        },
                    ),
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                t.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = if (isCurrent) {
                                    androidx.compose.ui.text.font.FontWeight.Bold
                                } else {
                                    androidx.compose.ui.text.font.FontWeight.Normal
                                },
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "${state.counts[t.id] ?: 0} 门课",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (isCurrent) Badge("当前")
                            if (isDefaultSource) Badge("默认配置")
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            if (!isCurrent) {
                                ManageAction("切换") { viewModel.select(t.id) }
                            }
                            ManageAction("重命名") {
                                renameText = t.name
                                renameTarget = t
                            }
                            ManageAction("复制") { viewModel.duplicate(t.id) }
                            ManageAction(if (isDefaultSource) "取消默认" else "设为默认") {
                                viewModel.setDefaultSource(if (isDefaultSource) null else t.id)
                            }
                            // 只有一张时禁用删除：Repository 也会拒绝，这里提前禁掉入口
                            ManageAction(
                                "删除",
                                enabled = state.timetables.size > 1,
                                tint = MaterialTheme.colorScheme.error,
                            ) { deleteTarget = t }
                        }
                    }
                }
            }
        }
    }

    if (createOpen) {
        NameDialog(
            title = "新建课表",
            label = "课表名称",
            initial = newName,
            onValueChange = { newName = it },
            onConfirm = {
                viewModel.create(newName)
                createOpen = false
            },
            onDismiss = { createOpen = false },
        )
    }

    renameTarget?.let { t ->
        NameDialog(
            title = "重命名课表",
            label = "课表名称",
            initial = renameText,
            onValueChange = { renameText = it },
            onConfirm = {
                viewModel.rename(t.id, renameText)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }

    deleteTarget?.let { t ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除课表「${t.name}」？") },
            text = {
                Text(
                    "将删除该课表的全部课程与设置（不可撤销）。\n" +
                        "课程数：${state.counts[t.id] ?: 0}",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(t.id)
                        deleteTarget = null
                    },
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun Badge(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .background(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                shape = MaterialTheme.shapes.small,
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun ManageAction(
    label: String,
    enabled: Boolean = true,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = tint)
    }
}

@Composable
private fun NameDialog(
    title: String,
    label: String,
    initial: String,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = initial,
                onValueChange = onValueChange,
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(enabled = initial.isNotBlank(), onClick = onConfirm) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
