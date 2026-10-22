package edu.jxslu.schedule.ui.me.hub

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.jxslu.schedule.BuildConfig
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.prefs.NoticeConsentRecord
import edu.jxslu.schedule.data.repo.AppCacheCleaner
import edu.jxslu.schedule.data.repo.CrashReporter
import edu.jxslu.schedule.data.repo.UpdateCheckClient
import edu.jxslu.schedule.data.repo.UpdateCheckResult
import edu.jxslu.schedule.domain.FirstRunNotice
import edu.jxslu.schedule.domain.FirstRunNotices
import edu.jxslu.schedule.domain.NoticeConsent
import edu.jxslu.schedule.domain.RechargeDisclaimer
import edu.jxslu.schedule.domain.UpdateManifest
import edu.jxslu.schedule.startActivityOutsideApp
import edu.jxslu.schedule.ui.common.AppNoticeVisuals
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.DisclaimerDialog
import edu.jxslu.schedule.ui.common.DialogWindowFadeEffect
import edu.jxslu.schedule.ui.common.LicensesDialog
import edu.jxslu.schedule.ui.common.NoticeDialog
import edu.jxslu.schedule.ui.common.NoticeTone
import edu.jxslu.schedule.ui.common.SettingItem
import edu.jxslu.schedule.ui.common.SettingSwitchRow
import edu.jxslu.schedule.ui.common.SettingsSection
import edu.jxslu.schedule.ui.common.showNotice
import edu.jxslu.schedule.ui.ebike.EbikeMapCache
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Agreement01
import me.rerere.hugeicons.stroke.Alert02
import me.rerere.hugeicons.stroke.Bug01
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.Github
import me.rerere.hugeicons.stroke.InformationCircle
import me.rerere.hugeicons.stroke.Megaphone01
import me.rerere.hugeicons.stroke.Refresh
import me.rerere.hugeicons.stroke.Shield01
import me.rerere.hugeicons.stroke.SoftwareLicense
import me.rerere.hugeicons.stroke.SystemUpdate01

/** 公开仓库地址（MIT）；「开源仓库」点击后经系统浏览器打开。 */
private const val REPO_URL = "https://github.com/Inonvation/JUWPHub"

/**
 * 设置 → 关于 → 关于与更新：版本、手动 / 自动检查更新、开源仓库与许可。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutUpdateScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val repo = remember { Graph.repository(context) }
    val autoUpdateCheck by remember { repo.autoUpdateCheckEnabled }.collectAsStateWithLifecycle(
        initialValue = remember { runBlocking { repo.autoUpdateCheckEnabled.first() } },
    )
    var showLicenses by remember { mutableStateOf(false) }
    val showNotice: (String) -> Unit = { message ->
        scope.launch {
            snackbar.showNotice(AppNoticeVisuals(message, tone = NoticeTone.Warning))
        }
    }

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
                    snackbar.showNotice(
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

    Scaffold(
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets.statusBars,
                title = { Text("关于与更新") },
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
                    title = "关于 JUWPHub",
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
                SettingSwitchRow(
                    title = "自动检查更新",
                    subtitle = "启动时静默检查新版本；发现更新弹窗提醒，可忽略此版本",
                    checked = autoUpdateCheck,
                    onCheckedChange = { value ->
                        scope.launch { repo.setAutoUpdateCheckEnabled(value) }
                    },
                    icon = HugeIcons.SystemUpdate01,
                )
            }

            SettingsSection(title = "开源") {
                SettingItem(
                    title = "开源仓库",
                    subtitle = "github.com/Inonvation/JUWPHub",
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
        }
    }

    if (showLicenses) {
        LicensesDialog(onDismiss = { showLicenses = false })
    }
    if (showUpdateDialog) {
        updateAvailable?.let { manifest ->
            UpdateAvailableDialog(
                manifest = manifest,
                onDismiss = { showUpdateDialog = false },
                onIgnore = {
                    showUpdateDialog = false
                    scope.launch { repo.setIgnoredUpdateVersion(manifest.version) }
                    scope.launch {
                        snackbar.showNotice(
                            AppNoticeVisuals(
                                "已忽略该版本，启动时不再自动提醒",
                                tone = NoticeTone.Success,
                            ),
                        )
                    }
                },
            )
        }
    }
}

/**
 * 设置 → 关于 → 权限与存储：权限设置入口、缓存清理与诊断日志。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsStorageScreen(
    onBack: () -> Unit,
    onOpenPermissionSettings: () -> Unit,
) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val showNotice: (String) -> Unit = { message ->
        scope.launch {
            snackbar.showNotice(AppNoticeVisuals(message, tone = NoticeTone.Warning))
        }
    }

    var cacheBytes by remember { mutableStateOf<Long?>(null) }
    var showClearCache by remember { mutableStateOf(false) }
    var clearingCache by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        cacheBytes = AppCacheCleaner.measure(context)
    }

    var crashRefresh by remember { mutableStateOf(0) }
    val crashFiles = remember(crashRefresh) { CrashReporter.listFiles(context) }
    val crashFileCount = crashFiles.size
    fun runClearCache() {
        if (clearingCache) return
        clearingCache = true
        scope.launch {
            val result = AppCacheCleaner.clear(context)
            cacheBytes = AppCacheCleaner.measure(context)
            crashRefresh++
            clearingCache = false
            showClearCache = false
            scope.launch {
                snackbar.showNotice(
                    AppNoticeVisuals(
                        if (result.allOk) "已清理缓存" else "部分缓存没清干净，可稍后重试",
                        tone = if (result.allOk) NoticeTone.Success else NoticeTone.Warning,
                    ),
                )
            }
        }
    }

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
                title = { Text("权限与存储") },
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
            SettingsSection(title = "权限") {
                SettingItem(
                    title = "权限设置",
                    subtitle = "通知 · 日历 · 定位 · 电池优化 · 自启动",
                    icon = HugeIcons.Shield01,
                    onClick = onOpenPermissionSettings,
                )
            }
            SettingsSection(title = "存储与诊断") {
                SettingItem(
                    title = "清理缓存",
                    subtitle = buildString {
                        append("网页缓存 · 地图瓦片 · 崩溃记录等")
                        cacheBytes?.let { append(" · 共 ").append(EbikeMapCache.sizeLabel(it)) }
                    },
                    icon = HugeIcons.Delete02,
                    onClick = { showClearCache = true },
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

    if (showClearCache) {
        AlertDialog(
            onDismissRequest = { if (!clearingCache) showClearCache = false },
            title = { Text("清理缓存？") },
            text = {
                DialogWindowFadeEffect()
                Text(
                    buildString {
                        append("将删除网页缓存与登录状态、离线地图瓦片、崩溃记录、校历图片等临时数据")
                        cacheBytes?.let { append("（共 ").append(EbikeMapCache.sizeLabel(it)).append("）") }
                        append("。不影响课表成绩、笔记作业、账号密码等数据；下次打开教务网页需要重新登录。")
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { runClearCache() },
                    enabled = !clearingCache,
                ) {
                    Text(
                        text = if (clearingCache) "清理中…" else "清理",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showClearCache = false },
                    enabled = !clearingCache,
                ) { Text("取消") }
            },
        )
    }
}

/**
 * 设置 → 关于 → 条款与声明：用户须知、免责声明与充值风险。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TermsDeclarationsScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { Graph.displayPrefs(context) }
    val userNoticeConsent by remember {
        prefs.noticeConsent(FirstRunNotice.UserNotice)
    }.collectAsStateWithLifecycle(initialValue = NoticeConsentRecord(version = 0, atMs = 0L))
    val userNoticeConfirmed =
        NoticeConsent.isConsented(userNoticeConsent.version, FirstRunNotices.VERSION)
    var showDisclaimer by remember { mutableStateOf(false) }
    var showUserNotice by remember { mutableStateOf(false) }
    var showRechargeNotice by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets.statusBars,
                title = { Text("条款与声明") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsSection(title = "条款") {
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
            }
        }
    }

    if (showDisclaimer) {
        DisclaimerDialog(onDismiss = { showDisclaimer = false })
    }
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
    if (showRechargeNotice) {
        NoticeDialog(
            title = "充值风险提醒",
            intro = "以下三条在每个充值入口都会再提醒一次；这里是随时可查的全文。",
            items = RechargeDisclaimer.ITEMS,
            onDismiss = { showRechargeNotice = false },
        )
    }
}
