package edu.jxslu.schedule.ui.me

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.JwImportActivity
import edu.jxslu.schedule.OnboardingActivity
import edu.jxslu.schedule.R
import edu.jxslu.schedule.TranscriptActivity
import edu.jxslu.schedule.data.session.LoginState
import edu.jxslu.schedule.data.session.LoginStateRules
import edu.jxslu.schedule.data.session.LoginTarget
import edu.jxslu.schedule.data.session.SessionStatus
import edu.jxslu.schedule.data.session.WebViewCookieBridge
import edu.jxslu.schedule.domain.AccountMask
import edu.jxslu.schedule.ui.common.AccountCard
import edu.jxslu.schedule.ui.common.AppCard
import edu.jxslu.schedule.ui.common.SettingItem
import edu.jxslu.schedule.ui.common.SettingsSection
import edu.jxslu.schedule.ui.jwvw.JwImportMode
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Book01
import me.rerere.hugeicons.stroke.CalendarSetting01
import me.rerere.hugeicons.stroke.GridView
import me.rerere.hugeicons.stroke.Settings01
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff

/**
 * 教务账户页（DESIGN §3.3）。
 *
 * 为什么要有它：「我的」页的教务那一行原先一点就开教务导入的 WebView 窗口——而隔壁
 * 「一卡通」点进去是原生的账户页。同样是一行账户，行为不一致；而且用户想看的是
 * 「我是谁、这个账号现在什么状态」，不是立刻跳进一个网页。
 *
 * 这里只展示与账号相关的只读信息 + 几个明确动作（更新密码 / 导入 / 退出），
 * 具体取数交给各自的页面。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JwAccountScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val vault = remember { Graph.credentialVault(context) }
    val prefs = remember { Graph.displayPrefs(context) }
    val cas = remember { Graph.casSession(context) }

    val casCred = remember { vault.readCas() }
    val profileName by prefs.profileName.collectAsStateWithLifecycle(initialValue = "")
    val profileClass by prefs.profileClass.collectAsStateWithLifecycle(initialValue = "")
    val suspendedTargets by SessionStatus.suspended.collectAsStateWithLifecycle()
    // 升级用户没存凭证、但 WebView 里可能还有会话：只读 CookieManager，不联网
    val webSession = remember { WebViewCookieBridge.hasAnyCookie() }
    val jwState = LoginStateRules.derive(
        credentialExists = casCred != null,
        webSessionExists = webSession,
        suspended = LoginTarget.Jw in suspendedTargets,
    )

    val viewModel: MeViewModel = viewModel(
        factory = MeViewModel.Factory(Graph.repository(context)),
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var confirmLogout by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets.statusBars,
                title = { Text("学校统一认证") },
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
            AccountCard(
                username = casCred?.username.orEmpty(),
                name = profileName,
                subtitle = profileClass,
                statusText = when (jwState) {
                    LoginState.LoggedIn -> "已登录 · 会话过期时自动重新登录"
                    LoginState.Expired -> "登录状态已失效，请更新账号密码"
                    LoginState.NotLoggedIn -> "未登录 · 没有保存密码"
                },
                statusState = jwState,
            )

            SettingsSection(title = "学业信息") {
                InfoRow("当前学期", state.semester?.startDate?.let { "$it 起" } ?: "未配置")
                InfoRow("教学周", if (state.currentWeek > 0) "第 ${state.currentWeek} 周" else "未开始")
                InfoRow("课表", "${state.timetableName.ifBlank { "—" }} · ${state.courseCount} 门课")
            }

            SettingsSection(title = "账号与数据") {
                SettingItem(
                    title = "更新账号密码",
                    subtitle = "学校改过密码后在这里重填，自动续登立即恢复",
                    icon = HugeIcons.Settings01,
                    onClick = { OnboardingActivity.start(context, startAtJw = true) },
                )
                SettingItem(
                    title = "导入课表",
                    subtitle = "打开教务学期课表页并导入",
                    icon = HugeIcons.Book01,
                    onClick = { JwImportActivity.start(context) },
                )
                SettingItem(
                    title = "导入成绩",
                    subtitle = "抓全部学期成绩，按学期替换",
                    icon = HugeIcons.GridView,
                    onClick = { JwImportActivity.start(context, JwImportMode.Scores) },
                )
                SettingItem(
                    title = "导出盖章成绩单",
                    subtitle = "签章系统出单，含教务处章",
                    icon = HugeIcons.CalendarSetting01,
                    onClick = { TranscriptActivity.start(context) },
                )
            }

            OutlinedButton(
                onClick = { confirmLogout = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("退出登录并清除密码")
            }
            if (casCred == null) {
                Text(
                    "当前没有保存密码：导入课表时需要手动登录，也不会自动续登。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("退出教务登录？") },
            text = {
                Text(
                    "将清除本机保存的学号与统一认证密码，自动续登随之失效。" +
                        "已导入的课表、成绩不受影响。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmLogout = false
                        cas.logout()
                        onBack()
                    },
                ) { Text("退出并清除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmLogout = false }) { Text("取消") }
            },
        )
    }
}

/** 一行「标签 —— 值」，学业信息用。 */
@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        Spacer(Modifier.weight(1f))
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}
