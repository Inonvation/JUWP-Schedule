package edu.jxslu.schedule.ui.ai

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.ai.AiScheduleClient
import edu.jxslu.schedule.domain.AiScheduleConfig
import edu.jxslu.schedule.ui.common.AppNoticeVisuals
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.ImeAwareModalBottomSheet
import edu.jxslu.schedule.ui.common.NoticeTone
import edu.jxslu.schedule.ui.common.SettingChoiceRow
import edu.jxslu.schedule.ui.common.SettingItem
import edu.jxslu.schedule.ui.common.SettingSwitchRow
import edu.jxslu.schedule.ui.common.SettingsSection
import edu.jxslu.schedule.ui.common.showNotice
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiBrain01
import me.rerere.hugeicons.stroke.Cpu
import me.rerere.hugeicons.stroke.Image01
import me.rerere.hugeicons.stroke.Link01
import me.rerere.hugeicons.stroke.LockPassword
import me.rerere.hugeicons.stroke.Refresh

/**
 * AI 调课的服务配置页（DESIGN §4.39，2026-10-06）。
 *
 * 预填 DeepSeek 的 OpenAI 兼容端点与默认模型，**用户通常只需填 API Key**；
 * 地址与模型可清空自定义其它兼容服务。API Key 落 `AiCredentialStore`
 * （EncryptedSharedPreferences，`secure_ai.xml` 已排除备份），不进 DataStore。
 *
 * 「测试连接」= 拉一次 `/models`：既验证地址与 Key，也把可用模型列出来供选。
 *
 * 2026-10-07 排版收口：「能力」节拆掉——图片识别开关并入「服务」节（紧跟模型行，
 * 语义顺序：先选模型、再声明能力）；挂在节尾的两条「模型名看着像…」说明 Text 删除
 * （说教式文案，判断依据已由「从上游获取」的模型列表替代）；隐私节说明收进节副标题。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val viewModel: AiSettingsViewModel = viewModel(
        factory = AiSettingsViewModel.Factory(
            Graph.repository(context),
            Graph.aiCredentialStore(context),
            Graph.aiScheduleClient(context),
        ),
    )
    val baseUrl by viewModel.baseUrl.collectAsStateWithLifecycle()
    val model by viewModel.model.collectAsStateWithLifecycle()
    val maskTeacher by viewModel.maskTeacher.collectAsStateWithLifecycle()
    val thinkingEffort by viewModel.thinkingEffort.collectAsStateWithLifecycle()
    val showReasoning by viewModel.showReasoning.collectAsStateWithLifecycle()
    val supportsImage by viewModel.supportsImage.collectAsStateWithLifecycle()
    val apiKey by viewModel.apiKey.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var editingUrl by remember { mutableStateOf(false) }
    var editingModel by remember { mutableStateOf(false) }
    var editingKey by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI 调课服务") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsSection(
                title = "服务",
                subtitle = "默认 DeepSeek；通常只需填 API Key",
            ) {
                SettingItem(
                    title = "服务地址",
                    subtitle = baseUrl.ifBlank { "未填写" },
                    icon = HugeIcons.Link01,
                    onClick = { editingUrl = true },
                )
                SettingItem(
                    title = "模型",
                    subtitle = model.ifBlank { AiScheduleConfig.DEFAULT_MODEL },
                    icon = HugeIcons.Cpu,
                    onClick = { editingModel = true },
                )
                SettingSwitchRow(
                    title = "支持图片识别",
                    subtitle = "开启后可以在 AI 调课里发通知截图",
                    checked = supportsImage,
                    onCheckedChange = viewModel::setSupportsImage,
                    icon = HugeIcons.Image01,
                )
                SettingItem(
                    title = "API Key",
                    subtitle = if (apiKey.isNullOrBlank()) "未配置" else "已配置",
                    icon = HugeIcons.LockPassword,
                    onClick = { editingKey = true },
                )
                SettingItem(
                    title = "测试连接",
                    subtitle = "验证地址与 Key",
                    icon = HugeIcons.Refresh,
                    enabled = !busy,
                    onClick = {
                        scope.launch {
                            val result = viewModel.probe()
                            val message = result.fold(
                                onSuccess = { list ->
                                    if (list.isEmpty()) "连接成功，但上游没有返回模型列表"
                                    else "连接成功，可用模型 ${list.size} 个"
                                },
                                onFailure = { aiErrorText(it) },
                            )
                            snackbar.showNotice(
                                AppNoticeVisuals(
                                    message,
                                    tone = if (result.isSuccess) NoticeTone.Success else NoticeTone.Error,
                                ),
                            )
                        }
                    },
                )
            }

            SettingsSection(
                title = "思考",
                subtitle = "档位越高越慢越贵；开启后结构化结果可能降级",
            ) {
                val efforts = AiScheduleClient.Effort.entries
                SettingChoiceRow(
                    title = "思考档位",
                    options = efforts.map { it.label },
                    selectedIndex = efforts.indexOf(
                        AiScheduleClient.Effort.fromWire(thinkingEffort),
                    ),
                    onSelect = { index -> viewModel.setThinkingEffort(efforts[index].wire) },
                    icon = HugeIcons.AiBrain01,
                )
                SettingSwitchRow(
                    title = "显示思考过程",
                    subtitle = "实时显示推理文本（默认收起）",
                    checked = showReasoning,
                    onCheckedChange = viewModel::setShowReasoning,
                    icon = HugeIcons.AiBrain01,
                )
            }

            SettingsSection(
                title = "隐私",
                subtitle = "发送课表与通知内容，不含学号姓名",
            ) {
                SettingSwitchRow(
                    title = "教师名脱敏",
                    subtitle = "教师姓名替换成「教师1 / 教师2 …」",
                    checked = maskTeacher,
                    onCheckedChange = viewModel::setMaskTeacher,
                    icon = HugeIcons.AiBrain01,
                )
            }
        }
    }

    if (editingUrl) {
        ImeAwareModalBottomSheet(onDismiss = { editingUrl = false }) {
            TextEditSheet(
                title = "服务地址",
                initial = baseUrl,
                placeholder = AiScheduleConfig.DEFAULT_BASE_URL,
                hint = "以 /v1 结尾的 OpenAI 兼容地址",
                onConfirm = { value ->
                    viewModel.setBaseUrl(value)
                    editingUrl = false
                },
                onDismiss = { editingUrl = false },
            )
        }
    }

    if (editingModel) {
        ImeAwareModalBottomSheet(onDismiss = { editingModel = false }) {
            ModelSheet(
                initial = model,
                suggestions = AiScheduleConfig.MODEL_SUGGESTIONS,
                onFetch = { viewModel.probe() },
                onConfirm = { value ->
                    viewModel.setModel(value)
                    editingModel = false
                },
                onDismiss = { editingModel = false },
            )
        }
    }

    if (editingKey) {
        ImeAwareModalBottomSheet(onDismiss = { editingKey = false }) {
            TextEditSheet(
                title = "API Key",
                initial = "",
                placeholder = if (apiKey.isNullOrBlank()) "粘贴 API Key" else "已配置，留空则不改动",
                hint = "仅保存在本机加密存储，不上传、不进备份",
                isSecret = true,
                confirmEnabled = { it.isNotBlank() || !apiKey.isNullOrBlank() },
                onConfirm = { value ->
                    // 留空 = 不改动（避免用户只是想看看却把 Key 清掉）
                    if (value.isNotBlank()) viewModel.saveApiKey(value)
                    editingKey = false
                },
                onDismiss = { editingKey = false },
            )
        }
    }
}

@Composable
private fun TextEditSheet(
    title: String,
    initial: String,
    placeholder: String,
    hint: String? = null,
    isSecret: Boolean = false,
    confirmEnabled: (String) -> Boolean = { true },
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (hint != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(placeholder) },
            singleLine = true,
            visualTransformation = if (isSecret) PasswordVisualTransformation() else VisualTransformation.None,
        )
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
            Button(
                onClick = { onConfirm(text.trim()) },
                enabled = confirmEnabled(text.trim()),
                modifier = Modifier.weight(1f),
            ) { Text("保存") }
        }
    }
}

/** 模型弹层：手输 + 「从上游获取」列出可选模型（取不到就退回手输，不阻塞）。 */
@Composable
private fun ModelSheet(
    initial: String,
    suggestions: List<String>,
    onFetch: suspend () -> Result<List<String>>,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    var fetched by remember { mutableStateOf<List<String>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var fetching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
    ) {
        Text("模型名", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(AiScheduleConfig.DEFAULT_MODEL) },
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            TextButton(
                onClick = {
                    fetching = true
                    error = null
                    scope.launch {
                        onFetch()
                            .onSuccess { fetched = it }
                            .onFailure { error = aiErrorText(it) }
                        fetching = false
                    }
                },
                enabled = !fetching,
            ) { Text(if (fetching) "获取中…" else "从上游获取") }
            Spacer(Modifier.weight(1f))
        }
        val options = if (fetched.isNotEmpty()) fetched else suggestions
        options.take(12).forEach { name ->
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (name == text) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { text = name }
                    .padding(vertical = 8.dp),
            )
        }
        error?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
            Button(
                onClick = { onConfirm(text.trim()) },
                enabled = text.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) { Text("保存") }
        }
    }
}
