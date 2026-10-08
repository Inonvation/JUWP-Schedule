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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.jxslu.schedule.BuildConfig
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.repo.AppCacheCleaner
import edu.jxslu.schedule.data.repo.CrashReporter
import edu.jxslu.schedule.data.repo.UpdateCheckClient
import edu.jxslu.schedule.data.repo.UpdateCheckResult
import edu.jxslu.schedule.data.prefs.NoticeConsentRecord
import edu.jxslu.schedule.domain.FirstRunNotice
import edu.jxslu.schedule.domain.FirstRunNotices
import androidx.core.content.FileProvider
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
import edu.jxslu.schedule.ui.common.SettingsSection
import edu.jxslu.schedule.ui.common.SettingSwitchRow
import edu.jxslu.schedule.ui.common.showNotice
import edu.jxslu.schedule.ui.ebike.EbikeMapCache
import edu.jxslu.schedule.ui.me.MeViewModel
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
private const val REPO_URL = "https://github.com/Inonvation/JUWP-Schedule"

/** 我的 → 关于（DESIGN §3.3）：版本 / 检查更新（手动 + 自动检查开关）/ 免责声明 / 开源仓库 / 权限设置。 */
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
    // 启动自动检查更新（DESIGN §3.3，2026-10-08）：默认开的全局项，独立单流
    // （baseDisplayPrefs combine 已满员）；首帧阻塞读一次避免先按默认渲染再跳
    // （口径同 WidgetCalendarHubScreen 的提醒开关）。
    val repo = remember { Graph.repository(context) }
    val autoUpdateCheck by remember { repo.autoUpdateCheckEnabled }.collectAsStateWithLifecycle(
        initialValue = remember { runBlocking { repo.autoUpdateCheckEnabled.first() } },
    )
    var showDisclaimer by remember { mutableStateOf(false) }
    var showUserNotice by remember { mutableStateOf(false) }
    var showRechargeNotice by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    val showNotice: (String) -> Unit = { message ->
        scope.launch {
            snackbar.showNotice(AppNoticeVisuals(message, tone = NoticeTone.Warning))
        }
    }

    // 检查更新（DESIGN §3.3）：手动路径——点行才拉仓库镜像 latest.json。行状态机
    // idle / checking / result；结果里「有更新」保留清单，点行直接重开下载弹窗，
    // 其余两态展示完即回 idle。网络走 IO 调度器，页面离开随 scope 取消。
    // （自动路径在冷启动链（JuwApplication）检查、经 UpdateAvailableBus 落主界面
    //   根层宿主 UpdateAvailableDialogHost；弹窗组件 UpdateAvailableDialog 两处共用。）
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
    // 清理缓存（DESIGN §3.3，2026-10-08）：进页统计一次占用，点行二次确认后清。
    // 清理中按钮禁用；弹窗先关再 Snackbar（红线上明确：Snackbar 穿不透弹窗窗口）。
    // 顺带 bump crashRefresh——崩溃记录在清理清单里，导出行的份数要跟着归零。
    var cacheBytes by remember { mutableStateOf<Long?>(null) }
    var showClearCache by remember { mutableStateOf(false) }
    var clearingCache by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        cacheBytes = AppCacheCleaner.measure(context)
    }
    // 崩溃留痕（CrashReporter）：列表驱动条目文案；分享走系统 ACTION_SEND（用户自己选
    // 去哪儿——微信/邮件/文件管理，App 不经手网络，符合隐私口径）
    // crashRefresh 是清理缓存的刷新键：crash/ 在清理清单里，清完 bump 重读份数
    // （原来是 remember{} 一次性读，清完后「导出诊断日志」行会一直显示旧份数）。
    var crashRefresh by remember { mutableStateOf(0) }
    val crashFiles = remember(crashRefresh) { CrashReporter.listFiles(context) }
    val crashFileCount = crashFiles.size
    fun runClearCache() {
        if (clearingCache) return // 闸在挂起点之前：清理在飞时按钮虽禁用，程序化重入也不放行
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
                SettingSwitchRow(
                    title = "自动检查更新",
                    subtitle = "启动时静默检查新版本；发现更新弹窗提醒，可忽略此版本",
                    checked = autoUpdateCheck,
                    onCheckedChange = { value ->
                        scope.launch { repo.setAutoUpdateCheckEnabled(value) }
                    },
                    icon = HugeIcons.SystemUpdate01,
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
                SettingItem(
                    title = "清理缓存",
                    subtitle = buildString {
                        append("网页缓存 · 地图瓦片 · 崩溃记录等")
                        cacheBytes?.let { append(" · 共 ").append(EbikeMapCache.sizeLabel(it)) }
                    },
                    icon = HugeIcons.Delete02,
                    onClick = { showClearCache = true },
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
    // 清理缓存确认：交代清什么与不清什么——清缓存最容易被误解成「数据没了」
    // （范式照抄快趣出行「清除地图缓存」；实现与清单见 data/repo/AppCacheCleaner）。
    if (showClearCache) {
        DialogWindowFadeEffect()
        AlertDialog(
            onDismissRequest = { if (!clearingCache) showClearCache = false },
            title = { Text("清理缓存？") },
            text = {
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
    // 发现新版本：日志正文 + SHA-256（镜像通道的核对凭据）+ 双通道下载 + 忽略此版本。
    // 检查失败/已是最新不走弹窗，走 Snackbar（见 checkForUpdate）；弹窗内的反馈
    // （已复制 / 打不开链接）由弹窗自己出——Snackbar 穿不透弹窗窗口。
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
