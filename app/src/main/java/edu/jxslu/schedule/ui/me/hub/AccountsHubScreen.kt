package edu.jxslu.schedule.ui.me.hub

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.session.LoginGateRules
import edu.jxslu.schedule.data.session.LoginState
import edu.jxslu.schedule.data.session.LoginStateRules
import edu.jxslu.schedule.data.session.LoginTarget
import edu.jxslu.schedule.data.session.SessionStatus
import edu.jxslu.schedule.data.session.WebViewCookieBridge
import edu.jxslu.schedule.domain.capabilities
import edu.jxslu.schedule.ui.common.DialogWindowFadeEffect
import edu.jxslu.schedule.ui.common.SettingItem
import edu.jxslu.schedule.ui.common.SettingsSection
import edu.jxslu.schedule.ui.common.StaggeredReveal
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import edu.jxslu.schedule.ui.common.rememberResumeTick
import edu.jxslu.schedule.ui.theme.semanticColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiBrain01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.CreditCard
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.Droplet
import me.rerere.hugeicons.stroke.GraduationCap
import me.rerere.hugeicons.stroke.HeartHandshake
import me.rerere.hugeicons.stroke.Shield01
import me.rerere.hugeicons.stroke.ShowerHead
import me.rerere.hugeicons.stroke.ScooterElectric
import me.rerere.hugeicons.stroke.WashingMachine

/**
 * 账号与登录（DESIGN §3.3，2026-10-20）。
 *
 * 「我的」页账号卡那一行的落点，也是全 App **登录态的唯一管理面**：一页三组
 * （学校账号 / 生活服务 / 可选），一行一个服务，行尾状态即动作。
 *
 * 三条口径：
 * 1. **不写「未登录」**：未开启的行显示「登录」胶囊——它同时是状态和动作；
 *    已失效才显示红色「重新登录」。
 * 2. **本页不做表单**：点行进该服务的原生页（教务账户 / 校园卡设置 / 开水 / 洗澡 /
 *    洗衣 / 快趣 / 问渠），登录表单仍归各页，避免八套表单在这里再写一遍。
 * 3. **状态与「我的」页同源**：全部走 [LoginStateRules.derive] 与各仓库的 `loggedIn` 流，
 *    不新增判定；「失效」只由仓库层的 credentialFailure 上报，不做后台探测。
 *
 * 快趣**始终列出**：账号登录方式下显示登录态；小程序方式下显示「无需登录」（中性胶囊），
 * 点行进骑行页切使用方式（DESIGN §3.9 / §4.32）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsHubScreen(
    onBack: () -> Unit,
    onOpenJw: () -> Unit,
    onOpenYkt: () -> Unit,
    onOpenQiekj: () -> Unit,
    onOpenQzxy: () -> Unit,
    onOpenUjing: () -> Unit,
    onOpenKvcx: () -> Unit,
    /** 快趣的小程序方式下没有账号可登：点行进骑行页切使用方式（DESIGN §3.9）。 */
    onOpenRide: () -> Unit,
    onOpenWenqu: () -> Unit,
    onOpenAiSettings: () -> Unit,
) {
    val context = LocalContext.current
    val haptics = rememberAppHaptics()
    val vault = remember { Graph.credentialVault(context) }
    val cas = remember { Graph.casSession(context) }
    // 从别的窗口（登录页 / 导入页 / 校园卡设置）回来时组合不重建，凭证快照要跟着 tick 重读
    val resumeTick = rememberResumeTick()
    // 本页自己清凭证后要立刻重读（resumeTick 只跟窗口切换走，不会因为清空而变）
    var localTick by remember { mutableIntStateOf(0) }
    val casUsername = remember(resumeTick, localTick) { vault.readCas()?.username }
    val yktUsername = remember(resumeTick, localTick) { vault.readYkt()?.username }
    // 升级用户可能没存凭证、但 WebView 里还有有效会话（与「我的」页同口径）
    val webSession = remember(resumeTick, localTick) { WebViewCookieBridge.hasAnyCookie() }
    val suspendedTargets by SessionStatus.suspended.collectAsStateWithLifecycle()

    val qiekjLoggedIn by remember { Graph.qiekj(context).loggedIn }
        .collectAsStateWithLifecycle()
    val qzxyLoggedIn by remember { Graph.qzxy(context).loggedIn }
        .collectAsStateWithLifecycle()
    val ujingLoggedIn by remember { Graph.ujing(context).loggedIn }
        .collectAsStateWithLifecycle()
    val kqcxLoggedIn by remember { Graph.kqcx(context).loggedIn }
        .collectAsStateWithLifecycle()
    val wenquSession by remember { Graph.wenqu(context).session }
        .collectAsStateWithLifecycle()
    val aiKey = remember(resumeTick, localTick) { Graph.aiCredentialStore(context).readApiKey() }

    // 快趣账号入口只属于「账号登录」使用方式；小程序方式下仍保留一行（否则用户会以为漏了），
    // 状态显示「无需登录」，点行进骑行页切使用方式。
    val prefs = remember(context) { Graph.displayPrefs(context) }
    val useMode by prefs.ebikeUseMode.collectAsStateWithLifecycle(
        initialValue = remember { runBlocking { prefs.ebikeUseMode.first() } },
    )
    val kvcxAccountMode = useMode.capabilities().inAppRide

    val jwState = LoginStateRules.derive(
        credentialExists = casUsername != null,
        webSessionExists = webSession,
        suspended = LoginTarget.Jw in suspendedTargets,
    )
    val yktState = LoginStateRules.derive(
        credentialExists = yktUsername != null,
        webSessionExists = false,
        suspended = LoginTarget.Ykt in suspendedTargets,
    )
    val qiekjState = LoginStateRules.derive(
        credentialExists = qiekjLoggedIn,
        webSessionExists = false,
        suspended = LoginTarget.Qiekj in suspendedTargets,
    )
    // 趣智 / U净 / 快趣 / 问渠没有「停用」上报（会话只存在本机，失效只能在页面里撞），
    // 与胖乖生活同一档：只分已登录 / 未开启
    val qzxyState = LoginStateRules.derive(qzxyLoggedIn, false, false)
    val ujingState = LoginStateRules.derive(ujingLoggedIn, false, false)
    // 小程序方式下快趣没有账号：固定按未开启算，不计入「已开启」，但行仍保留
    val kvcxState = if (kvcxAccountMode) {
        LoginStateRules.derive(kqcxLoggedIn, false, false)
    } else {
        LoginState.NotLoggedIn
    }
    val wenquState = LoginStateRules.derive(wenquSession?.isComplete == true, false, false)

    val accountStates = buildList {
        add(jwState)
        add(yktState)
        add(qiekjState)
        add(qzxyState)
        add(ujingState)
        add(kvcxState)
        add(wenquState)
    }
    val enabledCount = accountStates.count { it == LoginState.LoggedIn }
    val totalCount = accountStates.size

    var confirmClear by remember { mutableStateOf(false) }
    var cleared by remember { mutableStateOf(false) }
    LaunchedEffect(cleared) {
        if (cleared) {
            delay(2_500)
            cleared = false
        }
    }

    /**
     * 清除全部凭证（2026-10-20 用户拍板）：只删本机凭证，不动平台账号本身。
     *
     * 教务走 `CasSession.logout`（连 OkHttp / WebView cookie 一起清），一卡通连 token
     * 一起清，五个第三方仓库各走自己的 logout；AI Key 与学籍缓存一并清。清完 `localTick++`
     * 让本页的凭证快照立刻重读（仓库流会自己翻成未登录）。
     */
    fun clearAll() {
        // 先收弹窗，避免连点。清理由进程级 appScope 跑完——用户中途返回也不留下
        // 「一半清了一半没清」的状态；UI 状态最后切回 Main 更新。
        confirmClear = false
        Graph.appScope.launch {
            try {
                cas.logout()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 教务退出失败不挡其余凭证：能清多少清多少
            }
            runCatching { vault.clearYkt() }
            runCatching { Graph.yktRepository(context).clearTokenCache() }
            runCatching { vault.writeGate(LoginTarget.Ykt, LoginGateRules.reset()) }
            runCatching { vault.writeGate(LoginTarget.Qiekj, LoginGateRules.reset()) }
            SessionStatus.clear(LoginTarget.Ykt)
            SessionStatus.clear(LoginTarget.Qiekj)
            runCatching { Graph.qiekj(context).logout() }
            runCatching { Graph.qzxy(context).logout() }
            runCatching { Graph.ujing(context).logout() }
            runCatching { Graph.kqcx(context).logout() }
            runCatching { Graph.wenqu(context).logout() }
            runCatching { Graph.aiCredentialStore(context).clear() }
            try {
                Graph.displayPrefs(context).clearProfile()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 学籍缓存清不掉不影响凭证已经清掉的事实
            }
            withContext(Dispatchers.Main) {
                localTick++
                cleared = true
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("账号与登录") },
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
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StaggeredReveal(0) {
                SummaryCard(enabled = enabledCount, total = totalCount)
            }

            StaggeredReveal(1) {
                SettingsSection(
                    title = "学校账号",
                    subtitle = "统一身份认证 · 账号与数据都在学校服务器",
                ) {
                    AccountRow(
                        title = "教务",
                        subtitle = "课表 · 成绩 · 学工 · 成绩单",
                        icon = HugeIcons.GraduationCap,
                        state = jwState,
                        onClick = onOpenJw,
                    )
                    AccountRow(
                        title = "一卡通 · 电费",
                        subtitle = "余额 · 付款码 · 流水 · 寝室电费",
                        icon = HugeIcons.CreditCard,
                        state = yktState,
                        onClick = onOpenYkt,
                    )
                }
            }

            StaggeredReveal(2) {
                SettingsSection(
                    title = "生活服务",
                    subtitle = "第三方账号 · 凭证由各平台自己保管",
                ) {
                    AccountRow(
                        title = "胖乖生活",
                        subtitle = "开水房 · 余额 · 订单",
                        icon = HugeIcons.Droplet,
                        state = qiekjState,
                        onClick = onOpenQiekj,
                    )
                    AccountRow(
                        title = "趣智校园",
                        subtitle = "洗澡开热水 · 余额 · 账单",
                        icon = HugeIcons.ShowerHead,
                        state = qzxyState,
                        onClick = onOpenQzxy,
                    )
                    AccountRow(
                        title = "U净",
                        subtitle = "洗衣房空闲 · 扫码识别",
                        icon = HugeIcons.WashingMachine,
                        state = ujingState,
                        onClick = onOpenUjing,
                    )
                    AccountRow(
                        title = "快趣出行",
                        subtitle = if (kvcxAccountMode) {
                            "扫码用车 · 骑行记录"
                        } else {
                            "当前为小程序方式 · 点此切换"
                        },
                        icon = HugeIcons.ScooterElectric,
                        state = kvcxState,
                        labels = if (kvcxAccountMode) {
                            StatusLabels()
                        } else {
                            StatusLabels(login = "无需登录", loginMuted = true)
                        },
                        onClick = if (kvcxAccountMode) onOpenKvcx else onOpenRide,
                    )
                    AccountRow(
                        title = "问渠 · 志愿服务",
                        subtitle = "志愿工时 · 活动报名",
                        icon = HugeIcons.HeartHandshake,
                        state = wenquState,
                        onClick = onOpenWenqu,
                    )
                }
            }

            StaggeredReveal(3) {
                SettingsSection(
                    title = "可选",
                    subtitle = "不用也不影响其他功能",
                ) {
                    AccountRow(
                        title = "AI 调课",
                        subtitle = "自带 API Key · 可选功能",
                        icon = HugeIcons.AiBrain01,
                        state = if (aiKey.isNullOrBlank()) LoginState.NotLoggedIn else LoginState.LoggedIn,
                        labels = StatusLabels(loggedIn = "已配置", login = "去配置"),
                        onClick = onOpenAiSettings,
                    )
                }
            }

            StaggeredReveal(4) {
                Column {
                    TrustNote()
                    TextButton(
                        onClick = {
                            haptics.tap()
                            confirmClear = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            HugeIcons.Delete02,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("清除全部凭证", color = MaterialTheme.colorScheme.error)
                    }
                    AnimatedVisibility(visible = cleared) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "已清除全部凭证",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.semanticColors.success,
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清除全部凭证？") },
            text = {
                DialogWindowFadeEffect()
                Text(
                    "将退出教务、一卡通、胖乖生活、趣智校园、U净、快趣出行、问渠，" +
                        "并删除本机保存的密码、会话与 AI Key。此操作不可撤销；" +
                        "各平台账号本身不受影响。",
                )
            },
            confirmButton = {
                TextButton(onClick = { clearAll() }) {
                    Text("全部清除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            },
        )
    }
}

/** 顶部总览：已开启数 + 进度条。数字随登录态平滑变化，进度条 420ms 落位。 */
@Composable
private fun SummaryCard(enabled: Int, total: Int) {
    val target = if (total <= 0) 0f else enabled.toFloat() / total
    val progress = remember { Animatable(0f) }
    LaunchedEffect(target) {
        progress.animateTo(target, tween(420, easing = FastOutSlowInEasing))
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.07f))
            .padding(horizontal = 14.dp, vertical = 13.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "已开启",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(4.dp))
            AnimatedContent(
                targetState = enabled,
                transitionSpec = {
                    fadeIn(tween(180)) togetherWith fadeOut(tween(120))
                },
                label = "enabledCount",
            ) { count ->
                Text(
                    "$count",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                " / $total",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            "已登录的服务可以直接使用，其余点「登录」按需开启。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        )
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(progress.value)
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/** 一行一个服务：图标 + 名称/说明 + 状态胶囊 + 箭头。点行进该服务的原生页。 */
@Composable
private fun AccountRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    state: LoginState,
    onClick: () -> Unit,
    labels: StatusLabels = StatusLabels(),
) {
    SettingItem(
        title = title,
        subtitle = subtitle,
        icon = icon,
        onClick = onClick,
        trailing = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StatusPill(state = state, labels = labels)
                Icon(
                    HugeIcons.ArrowRight01,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                    modifier = Modifier.size(18.dp),
                )
            }
        },
    )
}

/** 状态胶囊的文案：默认是「已登录 / 登录 / 重新登录」，AI 那类改叫「已配置 / 去配置」。 */
private data class StatusLabels(
    val loggedIn: String = "已登录",
    val login: String = "登录",
    val expired: String = "重新登录",
    /** 未开启时用中性样式（不是可点的「登录」动作），如快趣的小程序方式。 */
    val loginMuted: Boolean = false,
)

/**
 * 状态胶囊：已登录 = 绿点实底，未开启 = 主色描边，已失效 = 错误色描边。
 *
 * 文案切换走 [AnimatedContent]（登录成功回来时不会硬切），颜色走 180ms 过渡——
 * 与全 App 其它状态过渡同档。
 */
@Composable
private fun StatusPill(state: LoginState, labels: StatusLabels = StatusLabels()) {
    val success = MaterialTheme.semanticColors.success
    val primary = MaterialTheme.colorScheme.primary
    val error = MaterialTheme.colorScheme.error
    val (text, fg, bg, border) = when (state) {
        LoginState.LoggedIn -> Quad(labels.loggedIn, success, success.copy(alpha = 0.12f), Color.Transparent)
        LoginState.NotLoggedIn -> if (labels.loginMuted) {
            Quad(
                labels.login,
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                Color.Transparent,
                Color.Transparent,
            )
        } else {
            Quad(labels.login, primary, Color.Transparent, primary.copy(alpha = 0.42f))
        }
        LoginState.Expired -> Quad(labels.expired, error, error.copy(alpha = 0.08f), error.copy(alpha = 0.35f))
    }
    val fgColor by animateColorAsState(fg, tween(180), label = "pillFg")
    val bgColor by animateColorAsState(bg, tween(180), label = "pillBg")
    val borderColor by animateColorAsState(border, tween(180), label = "pillBorder")
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(20.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (state == LoginState.LoggedIn) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(fgColor),
            )
        }
        AnimatedContent(
            targetState = text,
            transitionSpec = {
                fadeIn(tween(160)) togetherWith fadeOut(tween(120))
            },
            label = "pillText",
        ) { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = fgColor,
                maxLines = 1,
            )
        }
    }
}

/** 四元组（Compose 没有内置的 4 元组，只为胶囊那几项颜色）。 */
private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

/** 底部信任说明：整页唯一的「凭证怎么存」出口。 */
@Composable
private fun TrustNote() {
    val success = MaterialTheme.semanticColors.success
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(success.copy(alpha = 0.12f))
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            HugeIcons.Shield01,
            contentDescription = null,
            tint = success,
            modifier = Modifier.size(17.dp),
        )
        Spacer(Modifier.width(9.dp))
        Text(
            "凭证只保存在本机。用 Android Keystore 加密，不进云备份、不上传任何服务器；" +
                "退出登录会同时清除密码与网页会话。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
        )
    }
}
