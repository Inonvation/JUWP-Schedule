package edu.jxslu.schedule.ui.me.hub

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.jxslu.schedule.BuildConfig
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.repo.CrashReporter
import edu.jxslu.schedule.data.repo.UpdateCheckClient
import edu.jxslu.schedule.data.repo.UpdateCheckResult
import edu.jxslu.schedule.data.prefs.NoticeConsentRecord
import edu.jxslu.schedule.domain.FirstRunNotice
import edu.jxslu.schedule.domain.FirstRunNotices
import androidx.core.content.FileProvider
import edu.jxslu.schedule.domain.NoticeConsent
import edu.jxslu.schedule.domain.RechargeDisclaimer
import edu.jxslu.schedule.domain.UpdateCheck
import edu.jxslu.schedule.domain.UpdateManifest
import edu.jxslu.schedule.startActivityOutsideApp
import edu.jxslu.schedule.ui.common.AppNoticeVisuals
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.DialogWindowFadeEffect
import edu.jxslu.schedule.ui.common.DisclaimerDialog
import edu.jxslu.schedule.ui.common.LicensesDialog
import edu.jxslu.schedule.ui.common.NoticeDialog
import edu.jxslu.schedule.ui.common.NoticeTone
import edu.jxslu.schedule.ui.common.SettingItem
import edu.jxslu.schedule.ui.common.SettingsSection
import edu.jxslu.schedule.ui.me.MeViewModel
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Agreement01
import me.rerere.hugeicons.stroke.Alert02
import me.rerere.hugeicons.stroke.Bug01
import me.rerere.hugeicons.stroke.Github
import me.rerere.hugeicons.stroke.InformationCircle
import me.rerere.hugeicons.stroke.Megaphone01
import me.rerere.hugeicons.stroke.Refresh
import me.rerere.hugeicons.stroke.Shield01
import me.rerere.hugeicons.stroke.SoftwareLicense

/** 公开仓库地址（MIT）；「开源仓库」点击后经系统浏览器打开。 */
private const val REPO_URL = "https://github.com/Inonvation/JUWP-Schedule"

private fun openUrl(context: Context, url: String): String? = try {
    // 二级页窗口拉起外部应用统一走 startActivityOutsideApp：抑制右推入过渡，
    // 不然从浏览器回来时本页会凭空重放一次入场动画（devlog 4.26 的「页面跳动」）
    context.startActivityOutsideApp(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    null
} catch (_: ActivityNotFoundException) {
    "没有可打开链接的应用"
}

/** 我的 → 关于（DESIGN §3.3）：版本 / 检查更新 / 免责声明 / 开源仓库 / 权限设置。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onOpenPermissionSettings: () -> Unit,
    viewModel: MeViewModel = viewModel(
        factory = MeViewModel.Factory(Graph.repository(LocalContext.current)),
    ),
) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val prefs = remember { Graph.displayPrefs(context) }
    // 「用户须知」条目上的确认状态：同意过哪一版 + 何时确认（记录见 DisplayPrefsStore）。
    // 这是落盘同意记录的读路径——记录不展示给用户看就只是死数据。
    // 判定走 `domain/NoticeConsent.isConsented`（版本比对）：文案 bump 版本后这里会
    // 自动退回「未确认」，等用户看完新版再确认。
    val userNoticeConsent by remember {
        prefs.noticeConsent(FirstRunNotice.UserNotice)
    }.collectAsStateWithLifecycle(initialValue = NoticeConsentRecord(version = 0, atMs = 0L))
    val userNoticeConfirmed =
        NoticeConsent.isConsented(userNoticeConsent.version, FirstRunNotices.VERSION)
    var showDisclaimer by remember { mutableStateOf(false) }
    var showUserNotice by remember { mutableStateOf(false) }
    var showRechargeNotice by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    val showNotice: (String) -> Unit = { message ->
        scope.launch {
            snackbar.showSnackbar(AppNoticeVisuals(message, tone = NoticeTone.Warning))
        }
    }

    // 检查更新（DESIGN §3.3）：仅手动触发——点行才拉仓库镜像 latest.json，无冷启动任务、
    // 不落盘、不弹通知。行状态机 idle / checking / result；结果里「有更新」保留清单，
    // 点行直接重开下载弹窗，其余两态展示完即回 idle。网络走 IO 调度器，页面离开随
    // scope 取消。
    val updateClient = remember { UpdateCheckClient() }
    var updateChecking by remember { mutableStateOf(false) }
    var updateAvailable by remember { mutableStateOf<UpdateManifest?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    fun checkForUpdate() {
        if (updateChecking) return
        updateChecking = true
        updateAvailable = null
        scope.launch {
            val result = updateClient.check()
            updateChecking = false
            when (result) {
                is UpdateCheckResult.UpdateAvailable -> {
                    updateAvailable = result.latest
                    showUpdateDialog = true
                }
                is UpdateCheckResult.UpToDate -> scope.launch {
                    snackbar.showSnackbar(
                        AppNoticeVisuals(
                            "当前 v${BuildConfig.VERSION_NAME} 已是最新版本",
                            tone = NoticeTone.Success,
                        ),
                    )
                }
                is UpdateCheckResult.Failed -> showNotice("检查失败：网络不可用或更新清单暂时无法访问，稍后再试")
            }
        }
    }
    // 崩溃留痕（CrashReporter）：列表驱动条目文案；分享走系统 ACTION_SEND（用户自己选
    // 去哪儿——微信/邮件/文件管理，App 不经手网络，符合隐私口径）
    val crashFiles = remember { CrashReporter.listFiles(context) }
    val crashFileCount = crashFiles.size
    var shareLatestCrash by remember { mutableStateOf(false) }
    LaunchedEffect(shareLatestCrash) {
        if (!shareLatestCrash) return@LaunchedEffect
        shareLatestCrash = false
        val files = CrashReporter.listFiles(context)
        if (files.isEmpty()) return@LaunchedEffect
        runCatching {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                files.first(),
            )
            context.startActivityOutsideApp(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    "崩溃记录",
                ),
            )
        }.onFailure { showNotice("无法发起分享") }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets.statusBars,
                title = { Text("关于") },
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
            SettingsSection(title = "应用") {
                SettingItem(
                    title = "关于 水贝贝",
                    subtitle = "非学校官方应用 · 教务与第三方接口风险自负",
                    icon = HugeIcons.InformationCircle,
                    value = "v${BuildConfig.VERSION_NAME}",
                    showArrow = false,
                )
                SettingItem(
                    title = "检查更新",
                    subtitle = when {
                        updateChecking -> "正在检查…"
                        updateAvailable != null ->
                            "发现新版本 v${updateAvailable!!.version} · 点此查看"
                        else -> "从发布仓库检查新版本 · 可选国内镜像下载"
                    },
                    icon = HugeIcons.Refresh,
                    enabled = !updateChecking,
                    onClick = {
                        if (updateAvailable != null) showUpdateDialog = true else checkForUpdate()
                    },
                )
                SettingItem(
                    title = "用户须知",
                    subtitle = "非官方 · 数据只存本机 · 第三方接口可能随时失效",
                    icon = HugeIcons.Megaphone01,
                    value = if (userNoticeConfirmed) "已确认" else "未确认",
                    onClick = { showUserNotice = true },
                )
                SettingItem(
                    title = "免责声明",
                    subtitle = "非学校官方应用 · 第三方接口风险 · 仅供学习",
                    icon = HugeIcons.Agreement01,
                    onClick = { showDisclaimer = true },
                )
                SettingItem(
                    title = "充值风险提醒",
                    subtitle = "电费与校园卡充值的风险提示全文",
                    icon = HugeIcons.Alert02,
                    onClick = { showRechargeNotice = true },
                )
                SettingItem(
                    title = "开源仓库",
                    subtitle = "github.com/Inonvation/JUWP-Schedule",
                    icon = HugeIcons.Github,
                    onClick = { openUrl(context, REPO_URL)?.let(showNotice) },
                )
                SettingItem(
                    title = "开源许可",
                    subtitle = "本应用 MIT · 主要第三方组件清单",
                    icon = HugeIcons.SoftwareLicense,
                    onClick = { showLicenses = true },
                )
            }
            SettingsSection(title = "系统") {
                SettingItem(
                    title = "权限设置",
                    subtitle = "通知 · 日历 · 定位 · 电池优化 · 自启动",
                    icon = HugeIcons.Shield01,
                    onClick = onOpenPermissionSettings,
                )
                SettingItem(
                    title = "导出诊断日志",
                    subtitle = crashFileCount.takeIf { it > 0 }?.let { "$it 份崩溃记录 · 经系统分享发送" }
                        ?: "没有崩溃记录",
                    icon = HugeIcons.Bug01,
                    showArrow = crashFileCount > 0,
                    onClick = { shareLatestCrash = crashFileCount > 0 },
                )
            }
        }
    }

    if (showDisclaimer) {
        DisclaimerDialog(onDismiss = { showDisclaimer = false })
    }
    // 用户须知：从关于页打开并关掉（确认键 / 返回 / 点遮罩都算）= 确认当前版本。
    // 这是老安装唯一的确认口——同意记录的写点原本只有首启引导的声明队列，而队列
    // 只在引导里弹；引导键已写过的老安装永远不会再见到队列，状态就永远停在
    // 「未确认」（2026-09-30 用户报）。这里补上后，主动看完全文并关掉即完成确认。
    if (showUserNotice) {
        NoticeDialog(
            title = FirstRunNotices.title(FirstRunNotice.UserNotice),
            intro = FirstRunNotices.intro(FirstRunNotice.UserNotice),
            items = FirstRunNotices.items(FirstRunNotice.UserNotice),
            onDismiss = {
                showUserNotice = false
                scope.launch {
                    prefs.markNoticeConsented(FirstRunNotice.UserNotice, FirstRunNotices.VERSION)
                }
            },
        )
    }
    // 充值风险：正文与每个充值入口弹的那份同源（domain/RechargeDisclaimer.ITEMS），
    // 这里只是随时可查的只读入口，不锁、不写任何记录。
    if (showRechargeNotice) {
        NoticeDialog(
            title = "充值风险提醒",
            intro = "以下五条在每个充值入口都会再提醒一次；这里是随时可查的全文。",
            items = RechargeDisclaimer.ITEMS,
            onDismiss = { showRechargeNotice = false },
        )
    }
    if (showLicenses) {
        LicensesDialog(onDismiss = { showLicenses = false })
    }
    // 发现新版本：日志正文 + SHA-256（镜像通道的核对凭据）+ 双通道下载。
    // 检查失败/已是最新不走弹窗，走 Snackbar（见 checkForUpdate）。
    if (showUpdateDialog) {
        updateAvailable?.let { manifest ->
            UpdateAvailableDialog(
                manifest = manifest,
                onDismiss = { showUpdateDialog = false },
                onNotice = showNotice,
            )
        }
    }
}

/**
 * 「发现新版本」弹窗：更新日志可滚（长日志小屏不裁切）+ SHA-256 行（点按复制）+
 * 双通道动作。镜像链接来自仓库镜像清单里维护的 `mirrorPrefix`，前缀缺失/不合法时
 * 只出「GitHub 下载」一个出口。两键都是 `ACTION_VIEW` 交给浏览器下载，App 不落 APK。
 */
@Composable
private fun UpdateAvailableDialog(
    manifest: UpdateManifest,
    onDismiss: () -> Unit,
    onNotice: (String) -> Unit,
) {
    DialogWindowFadeEffect()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val mirrorUrl = UpdateCheck.mirrorDownloadUrl(manifest)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("发现新版本 v${manifest.version}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (manifest.notes.isNotBlank()) {
                    Text(
                        manifest.notes,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
                if (UpdateCheck.hasUsableDigest(manifest)) {
                    Text(
                        "SHA-256：${manifest.sha256}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                clipboard.setText(AnnotatedString(manifest.sha256))
                                onNotice("已复制 SHA-256")
                            }
                            .padding(vertical = 2.dp),
                    )
                    Text(
                        "点按复制 · 镜像下载为第三方通道，可核对此值确认安装包完整",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                    )
                }
            }
        },
        confirmButton = {
            if (mirrorUrl != null) {
                TextButton(onClick = {
                    onDismiss()
                    openUrl(context, mirrorUrl)?.let(onNotice)
                }) { Text("通过国内镜像下载") }
            } else {
                TextButton(onClick = {
                    onDismiss()
                    openUrl(context, UpdateCheck.githubDownloadUrl(manifest))?.let(onNotice)
                }) { Text("GitHub 下载") }
            }
        },
        dismissButton = {
            if (mirrorUrl != null) {
                TextButton(onClick = {
                    onDismiss()
                    openUrl(context, UpdateCheck.githubDownloadUrl(manifest))?.let(onNotice)
                }) { Text("GitHub 下载") }
            } else {
                TextButton(onClick = onDismiss) { Text("知道了") }
            }
        },
    )
}
