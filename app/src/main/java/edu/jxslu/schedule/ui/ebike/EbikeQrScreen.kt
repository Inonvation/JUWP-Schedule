package edu.jxslu.schedule.ui.ebike

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.app.SearchManager
import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.domain.EbikeFreeRide
import edu.jxslu.schedule.domain.EbikeQr
import edu.jxslu.schedule.ui.common.AppCardRow
import edu.jxslu.schedule.ui.common.AppNoticeVisuals
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.InlineNoticeRow
import edu.jxslu.schedule.ui.common.NoticeTone
import edu.jxslu.schedule.ui.common.SettingChoiceRow
import edu.jxslu.schedule.ui.common.SettingSwitchRow
import edu.jxslu.schedule.ui.common.SettingsSection
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ScooterElectric

/**
 * 共享单车出码页（DESIGN §3.9）。2026-09-22 重排后的纵向顺序：
 * 「打开快趣出行」（次要按钮，置于输入框上方）→ 车号输入（`100000` 固定前缀）
 * → 生成 → **固定方形占位**的出码区 → 保存 / 扫一扫（未出码时置灰）
 * → 最近车号（可一键清空）→ 出码设置两个开关（页面最下方）→ 免责声明。
 *
 * 更新逻辑：生成骑行二维码、自动保存（开关默认关）/手动保存、扫完即焚
 * （开关默认开：回到 App 即清除已保存的码）、微信扫一扫 best-effort。
 * 结果提示走页面 Snackbar（二级页窗口内无更高层弹层，不会穿透问题）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EbikeQrScreen(
    onBack: () -> Unit = {},
    viewModel: EbikeViewModel = viewModel(
        factory = EbikeViewModel.Factory(Graph.displayPrefs(LocalContext.current)),
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val prefs by viewModel.ebikePrefs.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val haptics = rememberAppHaptics()
    // 「结束骑行」二次确认弹窗（2026-09-22 用户口径：误触代价是提醒失效）
    var showEndConfirm by remember { mutableStateOf(false) }
    // 「未安装快趣出行」下载引导弹窗（2026-09-23 用户拍板：弹窗 + 跳参考下载页，
    // 网站仅外部提供、不保证准确性，文案里写清楚）
    var showInstallGuide by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    // 免费提醒开关开启那一刻申请 POST_NOTIFICATIONS（API 33+，照上课提醒口径）
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    val requestNotifPermission = {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    LaunchedEffect(Unit) {
        EbikeFreeRideReminder.check(context)
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is EbikeEvent.Notice -> snackbar.showSnackbar(
                    AppNoticeVisuals(event.text, tone = event.tone),
                )
            }
        }
    }

    // 扫完即焚触发点（DESIGN §3.9）：从微信/桌面回到 App（ON_RESUME）时清掉
    // 已保存的二维码。 DisposableEffect 组合提交晚于 ON_RESUME 的场景（冷启动恢复）
    // 用 isAtLeast(RESUMED) 兜底执行一次；pending 为空时 burnPending 是 no-op，天然幂等。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.burnPending()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            viewModel.burnPending()
        }
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("快趣出行码") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(HugeIcons.ArrowLeft01, contentDescription = "返回")
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 打开「快趣出行」App（DESIGN §3.9）：输入框上方（2026-09-22 用户口径）——
            // 装了 App 的人先开 App，不装的人往下看出码区。描边样式，主操作只有一个实心按钮。
            // 装了才有效，失败走页内 Snackbar
            OutlinedButton(
                onClick = {
                    haptics.tap()
                    openKvcooApp(
                        context,
                        onNotInstalled = { showInstallGuide = true },
                        onError = { message ->
                            scope.launch {
                                snackbar.showSnackbar(
                                    AppNoticeVisuals(message, tone = NoticeTone.Warning),
                                )
                            }
                        },
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("打开快趣出行")
            }

            // 输入区：尾部 3 位车号。`100000` 前缀与示例占位一律用 outline 灰——
            // 2026-09-22 真机反馈：默认色读起来像"已经帮填好了"，置灰后一眼可辨是提示
            val hintGray = MaterialTheme.colorScheme.outline
            OutlinedTextField(
                value = state.tailInput,
                onValueChange = viewModel::onTailInput,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("车身号后三位") },
                prefix = { Text(EbikeQr.TEMPLATE, color = hintGray) },
                placeholder = { Text("669", color = hintGray) },
                supportingText = { Text("只填车身二维码上车号的后三位") },
                isError = state.inputError != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                singleLine = true,
            )
            // 输入校验行内提示；非法车号不发事件，就地展示
            state.inputError?.let { error ->
                InlineNoticeRow(message = error, tone = NoticeTone.Warning)
            }

            Button(
                onClick = {
                    haptics.tap()
                    keyboard?.hide()
                    focusManager.clearFocus()
                    viewModel.generate()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("生成二维码")
            }

            // 出码区：固定方形占位，出码前也占满同一块高度
            QrPanel(bitmap = state.generatedBitmap, bikeId = state.generatedBikeId)

            // 保存 / 扫一扫：常显，未出码时置灰不可点——按钮整行出现或消失同样会顶动布局。
            // 两种动作都会退出本页或落相册，放一起等宽，拇指够得到
            val hasCode = state.generatedBitmap != null
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = {
                        haptics.tap()
                        viewModel.saveCurrent()
                    },
                    enabled = hasCode,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("保存到相册")
                }
                Button(
                    onClick = {
                        haptics.tap()
                        viewModel.onWechatScanClicked()
                        openWechatScan(context) { message ->
                            scope.launch {
                                snackbar.showSnackbar(
                                    AppNoticeVisuals(message, tone = NoticeTone.Warning),
                                )
                            }
                        }
                    },
                    enabled = hasCode,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("打开微信扫一扫")
                }
            }

            // 免费时长计时条（DESIGN §3.9）：开关开 + 计时中才显示；每秒刷新倒计时
            val timerActive = prefs.freeReminderEnabled && EbikeFreeRide.isActive(
                prefs.rideStartAt,
                System.currentTimeMillis(),
            )
            AnimatedVisibility(
                visible = timerActive,
                enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
            ) {
                FreeRideTimerBar(
                    startAtMillis = prefs.rideStartAt,
                    onEnd = { showEndConfirm = true },
                )
            }

            // 最近车号（DESIGN §3.9：8 个，点击回填 + 一键清空）
            if (prefs.recentIds.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "最近生成",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                            modifier = Modifier.weight(1f),
                        )
                        // 一键清空：历史只是回填便利项，不做二次确认，清了给 Snackbar
                        TextButton(onClick = {
                            haptics.tap()
                            viewModel.clearRecent()
                        }) {
                            Text("清空")
                        }
                    }
                    // FlowRow 而非固定两行：字体放大档位下 3 位数字 chip 也可能放不下 4 个
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        prefs.recentIds.forEach { tail ->
                            RecentChip(tail) {
                                haptics.tap()
                                viewModel.onPickRecent(tail)
                            }
                        }
                    }
                }
            }

            // 出码设置：两个开关放页面最下方（2026-09-22 用户口径）——低频调整项，
            // 不再夹在码区与历史之间挡视线。开关行自带触感，调用方不叠
            var autoSaveChecked by remember(prefs.autoSave) { mutableStateOf(prefs.autoSave) }
            var burnChecked by remember(prefs.burnAfterScan) { mutableStateOf(prefs.burnAfterScan) }
            SettingsSection(
                title = "出码设置",
                subtitle = "只影响本页的生成与保存行为，与登录状态无关。",
            ) {
                SettingSwitchRow(
                    title = "生成后自动保存到相册",
                    subtitle = "开启后每次出码即存入相册「水贝贝」",
                    checked = autoSaveChecked,
                    onCheckedChange = { checked ->
                        autoSaveChecked = checked
                        scope.launch { Graph.displayPrefs(context).setEbikeAutoSave(checked) }
                    },
                )
                SettingSwitchRow(
                    title = "扫完码返回后自动删除",
                    subtitle = "保存到相册的二维码会在回到 App 后自动清除",
                    checked = burnChecked,
                    onCheckedChange = { checked ->
                        burnChecked = checked
                        scope.launch { Graph.displayPrefs(context).setEbikeBurnAfterScan(checked) }
                    },
                )
            }

            // 免费时长提醒（DESIGN §3.9）：独立开关 + 提前量 1~5 分钟（用户拍板默认 3）
            var freeEnabled by remember(prefs.freeReminderEnabled) {
                mutableStateOf(prefs.freeReminderEnabled)
            }
            SettingsSection(
                title = "免费时长提醒",
                subtitle = "扫码开车后按 15 分钟计，提前提醒换车或还车。",
            ) {
                SettingSwitchRow(
                    title = "开启免费时长提醒",
                    subtitle = "点「打开微信扫一扫」开始计时，到点前发系统通知",
                    checked = freeEnabled,
                    onCheckedChange = { checked ->
                        freeEnabled = checked
                        if (checked) requestNotifPermission()
                        scope.launch {
                            Graph.displayPrefs(context).setEbikeFreeReminderEnabled(checked)
                            viewModel.onFreeReminderChanged()
                        }
                    },
                )
                if (freeEnabled) {
                    SettingChoiceRow(
                        title = "提前量",
                        subtitle = "免费时段结束前几分钟提醒",
                        options = listOf("1 分钟", "2 分钟", "3 分钟", "4 分钟", "5 分钟"),
                        selectedIndex = prefs.freeLeadMinutes - EbikeFreeRide.LEAD_MIN,
                        onSelect = { index ->
                            scope.launch {
                                Graph.displayPrefs(context)
                                    .setEbikeFreeLeadMinutes(index + EbikeFreeRide.LEAD_MIN)
                                viewModel.onFreeReminderChanged()
                            }
                        },
                    )
                }
            }

            Text(
                text = "非学校官方功能：二维码内容为共享电单车运营方链接，最终以小程序加载结果为准。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            )
        }
    }

    // 结束骑行确认（DESIGN §3.9）：误触 = 提醒失效，比直接清掉多一道闸
    if (showEndConfirm) {
        AlertDialog(
            onDismissRequest = { showEndConfirm = false },
            title = { Text("结束骑行？") },
            text = { Text("结束后将清空免费时长计时，不再提醒换车。") },
            confirmButton = {
                TextButton(onClick = {
                    showEndConfirm = false
                    haptics.tap()
                    viewModel.onEndRide()
                }) {
                    Text("结束骑行", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEndConfirm = false }) {
                    Text("继续骑行")
                }
            },
        )
    }

    // 未安装快趣出行的下载引导（2026-09-23）：弹窗代替之前的一句 Snackbar 提示，
    // 附参考下载页链接（用户拍板：外部网站不保证准确性，文案写免责）
    if (showInstallGuide) {
        AlertDialog(
            onDismissRequest = { showInstallGuide = false },
            title = { Text("未安装快趣出行") },
            text = {
                Text(
                    "需要先安装快趣出行 App 才能扫码开车。\n\n" +
                        "可从参考页面下载安装包，但该网站为外部提供，" +
                        "不保证下载内容安全准确，请自行甄别，由此产生的损失概不负责。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showInstallGuide = false
                    haptics.tap()
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(KVCOO_DOWNLOAD_URL))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }.onFailure {
                        scope.launch {
                            snackbar.showSnackbar(
                                AppNoticeVisuals(
                                    "浏览器打开失败，请手动复制链接访问",
                                    tone = NoticeTone.Warning,
                                ),
                            )
                        }
                    }
                }) {
                    Text("去参考页下载")
                }
            },
            dismissButton = {
                TextButton(onClick = { showInstallGuide = false }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun RecentChip(tail: String, onClick: () -> Unit) {
    Text(
        text = "…$tail",
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/**
 * 免费时长计时条（DESIGN §3.9）：大号倒计时 + 细进度线 + 「结束骑行」。
 * 2026-09-22 再改：恢复进度条（用户口径：点看不出趋势，线更直观），
 * 高度压缩（label 小标签与大数字合并同一行基线，进度线 2dp），
 * 整卡点击 = 弹结束确认（不是直接结束，误触代价是提醒失效）。
 * 内部每秒自刷新（[produceState] 计时循环）；到点后本组件被调用方条件移除。
 */
@Composable
private fun FreeRideTimerBar(startAtMillis: Long, onEnd: () -> Unit) {
    // 每秒刷新一次剩余时间；页面离开组合时自动取消
    val remaining by produceState(
        initialValue = EbikeFreeRide.remainingSeconds(startAtMillis, System.currentTimeMillis()),
        key1 = startAtMillis,
    ) {
        while (EbikeFreeRide.isActive(startAtMillis, System.currentTimeMillis())) {
            value = EbikeFreeRide.remainingSeconds(startAtMillis, System.currentTimeMillis())
            kotlinx.coroutines.delay(1000L)
        }
    }
    val progress = EbikeFreeRide.progressFraction(startAtMillis, System.currentTimeMillis())
    AppCardRow(
        onClick = onEnd,
        onClickLabel = "结束骑行",
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = EbikeFreeRide.formatRemaining(remaining),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontFeatureSettings = "tnum", // 等宽数字：每秒跳动不抖
                    ),
                )
                Text(
                    text = "  免费剩余",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 5.dp),
                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
            )
        }
        Text(
            text = "结束骑行",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * 出码位（DESIGN §3.9）：**固定方形、始终占位**。
 *
 * 旧版「没码就没有这一块」，点生成后整页往下跳一次（2026-09-22 用户反馈）。
 * 现在未生成时先摆一块描边空框 + 提示，出码后原地换成码图，高度不变，
 * 下方按钮与历史不动。码图 1:1（[EbikeQr.QR_SIZE_PX] 方图）与占位框同比，无二次形变。
 */
@Composable
private fun QrPanel(bitmap: Bitmap?, bikeId: String?) {
    val shape = RoundedCornerShape(14.dp)
    val onSurface = MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(shape)
            .background(
                if (bitmap == null) {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                } else {
                    MaterialTheme.colorScheme.surface
                },
            )
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "骑行二维码 · ${bikeId.orEmpty()}",
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    HugeIcons.ScooterElectric,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                    modifier = Modifier.size(40.dp),
                )
                Text(
                    text = "填好后三位，点「生成二维码」\n再用微信「扫一扫」即可开车",
                    style = MaterialTheme.typography.bodySmall,
                    color = onSurface.copy(alpha = 0.55f),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * 拉起微信「扫一扫」。入口按可靠性排序：
 * 1. `ShortCutDispatchAction` + `launch_type_scan_qrcode`——微信桌面长按「扫一扫」
 *    快捷方式的真身（`dumpsys shortcut com.tencent.mm` 实测），直达扫一扫相机页；
 * 2. `BIZSHORTCUT` + `LauncherUI.From.Scaner.Shortcut`——旧式快捷入口，部分版本
 *    只落微信首页（真机实测），仅作兜底；
 * 3. 打开微信首页给手动引导——出码本身已成功，这一步只是省一次手动切 App。
 */
private fun openWechatScan(context: android.content.Context, onError: (String) -> Unit) {
    val dispatchScan = Intent("com.tencent.mm.ui.ShortCutDispatchAction")
        .setPackage("com.tencent.mm")
        .putExtra("LauncherUI.Shortcut.LaunchType", "launch_type_scan_qrcode")
    try {
        context.startActivity(dispatchScan)
        return
    } catch (_: Exception) {
        // 落到下一级
    }
    val bizShortcut = Intent("com.tencent.mm.action.BIZSHORTCUT")
        .setPackage("com.tencent.mm")
        .addFlags(0x14000000) // NEW_TASK | CLEAR_TOP（沿用微信 shortcut 的 launchFlags）
        .putExtra("LauncherUI.From.Scaner.Shortcut", true)
    try {
        context.startActivity(bizShortcut)
        return
    } catch (_: Exception) {
        // 落到手动引导
    }
    try {
        context.startActivity(
            context.packageManager.getLaunchIntentForPackage("com.tencent.mm"),
        )
        onError("微信已打开，请在「发现 → 扫一扫」对准二维码")
    } catch (e2: Exception) {
        onError("无法自动打开微信，请手动打开「扫一扫」扫码")
    }
}

/** 「快趣出行」App 包名（DESIGN §3.9）。首页未导出，普通直启会被系统拒绝。 */
private const val KVCOO_PACKAGE = "com.kvcoo.go"

/** 快趣首页组件（assistant 设置用的扁平字符串）；「助手通道」的目标。 */
private const val KVCOO_HOME_ASSISTANT = "com.kvcoo.go/com.kvcoo.go.sections.home.HomeActivity"

/** 系统「助手」设置键（`Settings.Secure.ASSISTANT`）。 */
private const val KEY_SECURE_ASSISTANT = "assistant"

/** 写入「助手」后等待 SystemUI 取用的时间（含启动耗时），随后恢复原值。 */
private const val ASSISTANT_RESTORE_DELAY_MS = 1500L

/** 参考下载页（外部提供，不保证准确性；UI 文案里写免责）。 */
private const val KVCOO_DOWNLOAD_URL = "https://m.itmop.com/downinfo/288264.html"

/**
 * 打开「快趣出行」App（需已安装）。
 * 先用「桌面意图 + CLEAR_TASK」重启快趣（含启动页，等效冷启动刷新地图）；
 * 重启失败再走「助手通道」直达首页；都失败提示。
 * 1. 桌面意图 + CLEAR_TASK（2026-09-23 起，用户口径「先停止再打开」）：清掉现存
 *    任务栈重建首页——进程就算活着，Activity 全销毁，地图跟着重新初始化。
 *    比 killBackgroundProcesses 稳：后者杀不掉挂前台服务（定位）的进程，
 *    CLEAR_TASK 不依赖系统肯不肯杀。
 * 2. 「助手通道」（2026-09-21 真机实测，同级「快捷方式」工具同款路径）：临时把系统「助手」
 *    设置指到快趣首页 → 反射 `SearchManager.launchAssist` → 由 SystemUI（uid 1000）以
 *    `ACTION_ASSIST` 代启未导出的首页，直达、跳过启动页。需要**一次性** adb 授权：
 *    `adb shell pm grant edu.jxslu.schedule.debug android.permission.WRITE_SECURE_SETTINGS`
 *    （release 包名去掉 .debug）；未授权/反射被拦时静默走下一级；
 * 3. 桌面启动意图（启动页）兜底——启动页必然导出、无权限门槛；
 * 4. 未安装 → [onNotInstalled]（弹下载引导）；打开失败 → [onError]（Snackbar）。
 */
private fun openKvcooApp(
    context: android.content.Context,
    onNotInstalled: () -> Unit,
    onError: (String) -> Unit,
) {
    val installed = try {
        context.packageManager.getApplicationInfo(KVCOO_PACKAGE, 0)
        true
    } catch (_: Exception) {
        false
    }
    if (!installed) {
        onNotInstalled()
        return
    }
    if (restartKvcoo(context)) return
    if (launchViaAssistant(context)) return
    fallbackOpenKvcoo(context, onError)
}

/**
 * 「先停再开」的等效实现（2026-09-23 改）：拉快趣桌面意图 + `FLAG_ACTIVITY_CLEAR_TASK`
 * + `FLAG_ACTIVITY_NEW_TASK`——清掉它现存的任务栈再重建首页。Activity 全销毁 →
 * 地图跟着重建，视觉上就是「关掉重开」。不需要杀进程，不依赖系统许可。
 * @return true = 已拉起（调用方直接返回，不要再走助手通道，免得开两次）。
 */
private fun restartKvcoo(context: android.content.Context): Boolean {
    try {
        val launch = context.packageManager.getLaunchIntentForPackage(KVCOO_PACKAGE)
        if (launch != null) {
            context.startActivity(
                launch.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
                ),
            )
            return true
        }
        return false
    } catch (_: Exception) {
        return false
    }
}

/** 「助手通道」：写设置 → 反射 launchAssist → 延迟恢复。任一步失败恢复原值并返回 false。 */
private fun launchViaAssistant(context: android.content.Context): Boolean {
    val resolver = context.contentResolver
    val previous = try {
        Settings.Secure.getString(resolver, KEY_SECURE_ASSISTANT)
    } catch (_: Exception) {
        null
    }
    try {
        Settings.Secure.putString(resolver, KEY_SECURE_ASSISTANT, KVCOO_HOME_ASSISTANT)
    } catch (_: Exception) {
        return false // 无 WRITE_SECURE_SETTINGS（一次性 adb 授权）→ 走兜底
    }
    return try {
        val searchManager = context.getSystemService(SearchManager::class.java)
        if (searchManager == null) {
            restoreAssistant(resolver, previous)
            false
        } else {
            SearchManager::class.java
                .getMethod("launchAssist", Bundle::class.java)
                .invoke(searchManager, Bundle())
            Handler(Looper.getMainLooper()).postDelayed(
                { restoreAssistant(resolver, previous) },
                ASSISTANT_RESTORE_DELAY_MS,
            )
            true
        }
    } catch (_: Exception) {
        restoreAssistant(resolver, previous)
        false
    }
}

private fun restoreAssistant(
    resolver: android.content.ContentResolver,
    previous: String?,
) {
    try {
        Settings.Secure.putString(resolver, KEY_SECURE_ASSISTANT, previous)
    } catch (_: Exception) {
        // 恢复失败：下次触发会重写；助手设置本身可被用户手动改回
    }
}

/** 启动页兜底：拉快趣桌面启动意图（导出，无权限门槛）。 */
private fun fallbackOpenKvcoo(context: android.content.Context, onError: (String) -> Unit) {
    try {
        val launch = context.packageManager.getLaunchIntentForPackage(KVCOO_PACKAGE)
        if (launch != null) {
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
    } catch (_: Exception) {
        // 落到失败提示
    }
    onError("打开快趣出行失败，请手动打开")
}
