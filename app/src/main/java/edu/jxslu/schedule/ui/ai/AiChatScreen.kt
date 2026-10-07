package edu.jxslu.schedule.ui.ai

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.repo.AiImageStore
import edu.jxslu.schedule.ui.common.AppCardRow
import edu.jxslu.schedule.ui.common.AppNoticeVisuals
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.DialogWindowFadeEffect
import edu.jxslu.schedule.ui.common.ImageSource
import edu.jxslu.schedule.ui.common.ImageViewerDialog
import edu.jxslu.schedule.ui.common.NoticeTone
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import edu.jxslu.schedule.ui.common.rememberResumeTick
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ChevronRight
import me.rerere.hugeicons.stroke.History
import me.rerere.hugeicons.stroke.LockPassword
import me.rerere.hugeicons.stroke.Settings02

/**
 * AI 调课 · 单页工作单（DESIGN §3.25 v2，2026-10-07 重构；技术见 §4.39 / §4.40）。
 *
 * 结构（上→下，水平 16dp、卡间距 10dp，与「调课检测」页同构）：
 * ① 通知输入卡（[AiNoticeCard]：文字 + 截图，状态词在卡头）；
 * ② 上次应用条（回执跨轮保留，撤销直达）；
 * ③ 结果区（[AiProposalCard] / [AiFailCard]）；
 * ④ 贴底动作条（[aiBarSpec] 纯映射：「分析 → 停止 → 重试 / 重新分析 → 应用所选 →
 *    分析新通知」；**撤销不进底栏**，只跟执行走）。
 *
 * 关键纪律（与首版单页工作单相同，形态与语义都保留）：
 * - 模型的 tool_call 只是**提议**，落库必须过用户逐条勾选 + 显式应用；
 * - 写库走 `ScheduleRepository.applyLocalChanges`（与手动改课同语义，**不推基线**）；
 * - **无后台调用**：只有点「分析」才发请求，失败不自动重试；
 * - 会话与图片都**不落盘**（进程内），杀进程即清；
 * - 拒绝项（rejected）**必须可见**；思考过程默认折叠限高。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiChatScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: AiChatViewModel = viewModel(
        factory = AiChatViewModel.Factory(
            Graph.repository(context),
            Graph.aiCredentialStore(context),
            Graph.aiScheduleClient(context),
            Graph.aiImageStore(context),
            Graph.aiApplyHistoryStore(context),
        ),
    )

    val work by viewModel.work.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val attachments by viewModel.attachments.collectAsStateWithLifecycle()
    val courses by viewModel.courses.collectAsStateWithLifecycle()
    val semester by viewModel.semester.collectAsStateWithLifecycle()
    val existingOverrides by viewModel.overrides.collectAsStateWithLifecycle()
    val showReasoning by viewModel.showReasoning.collectAsStateWithLifecycle()
    val hasApiKey by viewModel.hasApiKey.collectAsStateWithLifecycle()
    val privacyPrompt by viewModel.privacyPrompt.collectAsStateWithLifecycle()
    val imagePrompt by viewModel.imagePrompt.collectAsStateWithLifecycle()
    val imageNotice by viewModel.imageNotice.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()

    // 从配置页 / 调课记录页（另外的窗口）返回后：重读凭证快照 + 同步回执的撤销状态
    val resumeTick = rememberResumeTick()
    LaunchedEffect(resumeTick) {
        viewModel.refreshCredentials()
        viewModel.refreshHistoryUndo()
    }

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val haptics = rememberAppHaptics()
    val clipboard = LocalClipboardManager.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    var viewingImage by remember { mutableStateOf<String?>(null) }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = AiImageStore.MAX_IMAGES),
    ) { uris -> uris.forEach(viewModel::addImage) }

    LaunchedEffect(imageNotice) {
        imageNotice?.let {
            snackbar.showSnackbar(AppNoticeVisuals(it, tone = NoticeTone.Warning))
            viewModel.consumeImageNotice()
        }
    }

    // 落库结果由 VM 用一次性提示回吐（写库的 launch 在 viewModelScope 里，
    // 不挂在组合作用域上——点完立刻返回上一页不会把写入取消掉）
    LaunchedEffect(notice) {
        notice?.let {
            if (it.tone == NoticeTone.Success) haptics.success()
            snackbar.showSnackbar(AppNoticeVisuals(it.text, tone = it.tone))
            viewModel.consumeNotice()
        }
    }

    val hasInput = draft.isNotBlank() || attachments.isNotEmpty()
    val barSpec = aiBarSpec(work, hasInput)
    // 「上次应用」条：回执跨轮保留；本轮自己的回执由提案卡内的回执行展示
    val receiptStrip = work.receipt?.takeIf { !it.undone && work.phase != AiPhase.APPLIED }

    // 贴底动作条的实测高度：提示要落在它上缘之上（ui-common 的贴底动作条纪律）
    var barHeightPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI 调课", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenHistory) {
                        Icon(HugeIcons.History, contentDescription = "调课记录")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(HugeIcons.Settings02, contentDescription = "配置")
                    }
                },
            )
        },
        snackbarHost = {
            AppSnackbarHost(
                snackbar,
                Modifier.padding(bottom = with(density) { barHeightPx.toDp() }),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AiNoticeCard(
                    work = work,
                    draft = draft,
                    attachments = attachments,
                    onDraftChange = viewModel::setDraft,
                    onPickImages = {
                        imagePicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    onPasteImage = {
                        val pasted = readClipboardImages(context)
                        if (pasted.isEmpty()) {
                            scope.launch {
                                snackbar.showSnackbar(
                                    AppNoticeVisuals("剪贴板里没有图片", tone = NoticeTone.Warning),
                                )
                            }
                        } else {
                            pasted.forEach(viewModel::addImage)
                        }
                    },
                    onRemoveImage = viewModel::removeImage,
                    onOpenImage = { viewingImage = it },
                    onClear = viewModel::clearInputs,
                )

                // 未配 Key 时先给引导（不拦截发送，失败路径照常给「去配置」）
                if (!hasApiKey && work.phase == AiPhase.IDLE) {
                    ConfigureGuideRow(onOpenSettings)
                }

                AnimatedVisibility(
                    visible = receiptStrip != null,
                    enter = fadeIn(tween(220)) + expandVertically(tween(260)),
                    exit = fadeOut(tween(160)) + shrinkVertically(tween(220)),
                ) {
                    receiptStrip?.let { receipt ->
                        AiReceiptRow(
                            receipt = receipt,
                            undoSkipped = work.undoSkipped,
                            onUndo = viewModel::undoFromUi,
                        )
                    }
                }

                AnimatedVisibility(
                    visible = work.phase != AiPhase.IDLE,
                    enter = fadeIn(tween(220)) + expandVertically(tween(260)),
                    exit = fadeOut(tween(180)) + shrinkVertically(tween(220)),
                ) {
                    // 失败卡与提案卡切换也过一遍淡入淡出（重试、失败落定都走这里）
                    Crossfade(
                        targetState = work.phase == AiPhase.FAILED,
                        animationSpec = tween(220),
                        label = "aiResultSwitch",
                    ) { failed ->
                        if (failed) {
                            AiFailCard(
                                work = work,
                                onOpenSettings = onOpenSettings,
                                onRetryWithoutImages = viewModel::retryWithoutImages,
                            )
                        } else {
                            AiProposalCard(
                                work = work,
                                semester = semester,
                                courses = courses,
                                existingOverrides = existingOverrides,
                                showReasoning = showReasoning,
                                onToggleChange = viewModel::toggleChange,
                                onToggleOverride = viewModel::toggleOverride,
                                onToggleCollapsed = { viewModel.setCollapsed(!work.collapsed) },
                                onUndo = viewModel::undoFromUi,
                                onReadbackToComposer = { text ->
                                    viewModel.setDraft(text)
                                    scope.launch {
                                        snackbar.showSnackbar(
                                            AppNoticeVisuals("识别文字已填进通知框，改对再点「分析」", tone = NoticeTone.Info),
                                        )
                                    }
                                },
                                onCopyHeadline = { text ->
                                    clipboard.setText(AnnotatedString(text))
                                    scope.launch {
                                        snackbar.showSnackbar(
                                            AppNoticeVisuals("已复制", tone = NoticeTone.Success),
                                        )
                                    }
                                },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))
            }

            AiBottomBar(
                spec = barSpec,
                work = work,
                modifier = Modifier.onSizeChanged { barHeightPx = it.height },
                onAction = { action ->
                    focusManager.clearFocus()
                    keyboard?.hide()
                    when (action) {
                        AiBarAction.ANALYZE, AiBarAction.REANALYZE -> viewModel.analyze()
                        AiBarAction.STOP -> viewModel.stopAnalyze()
                        AiBarAction.RETRY -> viewModel.retry()
                        AiBarAction.APPLY -> viewModel.applyFromUi()
                        AiBarAction.NEW_NOTICE -> viewModel.analyzeNew()
                        AiBarAction.APPLYING -> Unit
                    }
                },
            )
        }
    }

    // ---- 全屏看图（复用项目统一的查看器；ai_img 目录的口径在 ImageSource.AiNotice）
    viewingImage?.let { name ->
        ImageViewerDialog(
            fileName = name,
            onDismiss = { viewingImage = null },
            source = ImageSource.AiNotice,
        )
    }

    // ---- 首次隐私确认（声明类弹窗走 M3 AlertDialog + 统一进出场）
    if (privacyPrompt) {
        DialogWindowFadeEffect()
        AlertDialog(
            onDismissRequest = viewModel::dismissPrivacyPrompt,
            title = { Text("使用前确认") },
            text = {
                Text(
                    "使用「AI 调课」时，当前课表的课程名 / 时间 / 周次 / 地点、" +
                        "你粘贴的通知与图片会发送到「配置」里填写的服务。" +
                        "不会发送学号、姓名或其它学期的数据；教师名可在配置里脱敏。此确认只出现一次。",
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmPrivacyAndAnalyze) { Text("同意并分析") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissPrivacyPrompt) { Text("取消") }
            },
        )
    }

    // ---- 「当前模型未标记支持图片」的发送前拦截
    if (imagePrompt) {
        DialogWindowFadeEffect()
        AlertDialog(
            onDismissRequest = viewModel::dismissImagePrompt,
            title = { Text("当前模型未标记支持图片") },
            text = {
                Text(
                    "你在「配置」里没有开启「支持图片识别」。直接把图片发出去，" +
                        "部分上游会返回 400，或者静默忽略图片——后者更麻烦，你会以为模型看过图了。" +
                        "如果你用的确实是带视觉的模型，去打开那个开关再发。",
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmImageSend) { Text("仍然发送") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        viewModel.dismissImagePrompt()
                        onOpenSettings()
                    },
                ) { Text("去开启图片识别") }
            },
        )
    }
}

/** 从系统剪贴板取图片 URI（截图后直接粘，不用去相册）。 */
private fun readClipboardImages(context: Context): List<android.net.Uri> {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return emptyList()
    val clip = manager.primaryClip ?: return emptyList()
    return (0 until clip.itemCount).mapNotNull { index ->
        clip.getItemAt(index).uri?.takeIf { it.scheme == "content" }
    }
}

// ------------------------------------------------------------------
// 未配置引导
// ------------------------------------------------------------------

@Composable
private fun ConfigureGuideRow(onOpenSettings: () -> Unit) {
    AppCardRow(onClick = onOpenSettings) {
        Icon(
            HugeIcons.LockPassword,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("还没有配置 AI 服务", style = MaterialTheme.typography.titleSmall)
            Text(
                "填一个 API Key 就能用；地址与模型默认已填好",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        Icon(
            HugeIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            modifier = Modifier.size(18.dp),
        )
    }
}

// ------------------------------------------------------------------
// 贴底动作条（唯一动作入口；规格来自 aiBarSpec 纯映射）
// ------------------------------------------------------------------

@Composable
private fun AiBottomBar(
    spec: AiBarSpec,
    work: AiWorkState,
    onAction: (AiBarAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            spec.secondary?.let { secondary ->
                TextButton(
                    onClick = { onAction(secondary) },
                    enabled = spec.secondaryEnabled,
                    modifier = Modifier.heightIn(min = 44.dp),
                ) {
                    Text(barLabel(secondary, work), maxLines = 1)
                }
            }
            Button(
                onClick = { onAction(spec.primary) },
                enabled = spec.primaryEnabled,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp),
            ) {
                Text(barLabel(spec.primary, work), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun barLabel(action: AiBarAction, work: AiWorkState): String = when (action) {
    AiBarAction.ANALYZE -> "分析"
    AiBarAction.STOP -> "停止"
    AiBarAction.RETRY -> "重试"
    AiBarAction.REANALYZE -> "重新分析"
    AiBarAction.APPLY -> "应用所选（${work.selectedCount}）"
    AiBarAction.APPLYING -> "应用中…"
    AiBarAction.NEW_NOTICE -> "分析新通知"
}
