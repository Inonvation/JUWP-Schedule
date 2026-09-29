package edu.jxslu.schedule.ui.me.hub

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import edu.jxslu.schedule.data.prefs.NoticeConsentRecord
import edu.jxslu.schedule.domain.FirstRunNotice
import edu.jxslu.schedule.domain.FirstRunNotices
import edu.jxslu.schedule.domain.RechargeDisclaimer
import edu.jxslu.schedule.ui.common.AppNoticeVisuals
import edu.jxslu.schedule.ui.common.AppSnackbarHost
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
import me.rerere.hugeicons.stroke.Github
import me.rerere.hugeicons.stroke.InformationCircle
import me.rerere.hugeicons.stroke.Megaphone01
import me.rerere.hugeicons.stroke.Shield01
import me.rerere.hugeicons.stroke.SoftwareLicense

/** 公开仓库地址（MIT）；「开源仓库」点击后经系统浏览器打开。 */
private const val REPO_URL = "https://github.com/Inonvation/JUWP-Schedule"

private fun openUrl(context: Context, url: String): String? = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    null
} catch (_: ActivityNotFoundException) {
    "没有可打开链接的应用"
}

/** 我的 → 关于（DESIGN §3.3）：版本 / 免责声明 / 开源仓库 / 权限设置。 */
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
    val userNoticeConsent by remember {
        prefs.noticeConsent(FirstRunNotice.UserNotice)
    }.collectAsStateWithLifecycle(initialValue = NoticeConsentRecord(version = 0, atMs = 0L))
    var showDisclaimer by remember { mutableStateOf(false) }
    var showUserNotice by remember { mutableStateOf(false) }
    var showRechargeNotice by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    val showNotice: (String) -> Unit = { message ->
        scope.launch {
            snackbar.showSnackbar(AppNoticeVisuals(message, tone = NoticeTone.Warning))
        }
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
                    title = "用户须知",
                    subtitle = "非官方 · 数据只存本机 · 第三方接口可能随时失效",
                    icon = HugeIcons.Megaphone01,
                    value = if (userNoticeConsent.version > 0) "已确认" else "未确认",
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
            onDismiss = { showUserNotice = false },
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
}
