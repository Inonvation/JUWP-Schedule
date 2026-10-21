package edu.jxslu.schedule.ui.wenqu

import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.MainActivity
import edu.jxslu.schedule.data.wenqu.WenquSessionExpiredException
import edu.jxslu.schedule.domain.WenquRules
import edu.jxslu.schedule.ui.common.DialogWindowFadeEffect
import edu.jxslu.schedule.ui.reminder.ReminderVibration
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 预报名**前台判定**（2026-10-21）：本进程有没有 Activity 处于 started。
 *
 * 不引 `lifecycle-process`（AndroidX 版本边界卡得紧，不为一个布尔值加依赖）：
 * 在 [JuwApplication][edu.jxslu.schedule.JuwApplication] 注册
 * [android.app.Application.ActivityLifecycleCallbacks]，按 started 计数。
 * **用 started 而不是 resumed**：弹窗（Dialog）不改 resumed 计数，但前台判定要的正是
 * 「窗口可见」；started 在二级页多窗口叠层下也稳（主窗口 stop 时计数才落 0）。
 *
 * 只服务预报名仪表板（armed/running 时自动弹 / 回前台前沿触发），与其它模块无关。
 */
object WenquAppForeground {

    private val _foreground = MutableStateFlow(false)

    /** 本进程当前是否在前台（有任一 Activity started）。 */
    val foreground: StateFlow<Boolean> = _foreground.asStateFlow()

    private var started = 0

    /** 冷启动装一次（JuwApplication.onCreate）；重复装会重复计数，别再调。 */
    fun install(application: android.app.Application) {
        application.registerActivityLifecycleCallbacks(
            object : android.app.Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: android.app.Activity) {
                    synchronized(this@WenquAppForeground) {
                        started++
                        if (started == 1) _foreground.value = true
                    }
                }

                override fun onActivityStopped(activity: android.app.Activity) {
                    synchronized(this@WenquAppForeground) {
                        started = (started - 1).coerceAtLeast(0)
                        if (started == 0) _foreground.value = false
                    }
                }

                override fun onActivityCreated(
                    activity: android.app.Activity,
                    savedInstanceState: android.os.Bundle?,
                ) = Unit

                override fun onActivityResumed(activity: android.app.Activity) = Unit
                override fun onActivityPaused(activity: android.app.Activity) = Unit
                override fun onActivitySaveInstanceState(
                    activity: android.app.Activity,
                    outState: android.os.Bundle,
                ) = Unit

                override fun onActivityDestroyed(activity: android.app.Activity) = Unit
            },
        )
    }
}

/**
 * 预报名**仪表板状态**（2026-10-21）：执行器（[WenquPreregRunner]）是唯一写方，
 * 通知与前台弹层各读一份——终态文案只写一次，「结果通知」与「面板结果页」天然同源。
 *
 * [lastMessage] 是最近一次平台返回 / 网络异常的原文（面板上「最近返回」与
 * 结果通知里的「最近一次返回：…」共用）；[resultText] 只在终态非空。
 */
data class WenquPreregDashState(
    val id: Long = 0L,
    val title: String = "",
    val opensAt: Long = 0L,
    val phase: Phase = Phase.Idle,
    /** 试发已发次数（Waiting / 终态时是最近一轮的值）。 */
    val attempts: Int = 0,
    /** 本轮预算（主轮 30 发 / 重试轮 5 发），纯展示。 */
    val maxAttempts: Int = 0,
    val lastMessage: String = "",
    val resultText: String = "",
    /** 终态成败三态：true 成功 / false 没抢到 / null 已取消。 */
    val success: Boolean? = null,
    /** 终态补充说明（如「终检救回」「未能确认」），面板上重点色强调。 */
    val resultNote: String = "",
) {
    enum class Phase {
        /** 没有在跑的执行器（含从未排上 / 已收工清零）。 */
        Idle,
        /** 校对段：预热 token + 并发拉列表与详情。 */
        Checking,
        /** 等待试发点（服务器钟口径）；UI 按本地钟自行倒计时，不逐帧推状态。 */
        Waiting,
        /** 试发段：串行单发。 */
        Firing,
        /** 收工终检：触顶后的最后一次耐心核对。 */
        FinalCheck,
        /** 终态（成功 / 没抢到 / 已取消 / 被中断），[resultText] 非空。 */
        Done,
    }

    val isRunning: Boolean
        get() = phase == Phase.Checking || phase == Phase.Waiting ||
            phase == Phase.Firing || phase == Phase.FinalCheck
}

/**
 * 预报名**前台仪表板**（2026-10-21）：
 *
 * - [WenquPreregDashboardHost]：挂在根组合（`JuwRoot` + `SettingsActivity` 各一处），
 *   订阅 [WenquPreregRunner.dash]，执行期间在**任何界面**上弹 [WenquPreregDashboardDialog]。
 *   自动弹的时机：起跑时 App 在前台、或后台起跑后用户回到 App（前台沿 false→true）。
 *   用户手动关掉后**不再自动弹**（执行照常，进度走通知），重开入口 = 进度 / 结果通知点击、
 *   悬浮球单击（预报名期间）、蹲点页「已预报名」行。
 * - [WenquPreregDashboardActivityJump]：通知落点。Intent 带 [EXTRA_DASH_REOPEN] 把主窗口
 *   拉起来，MainActivity 读到 extra 调 [WenquPreregDashboardHost.reopen]——通知点进来的
 *   重开不受「关过不再自动弹」限制。
 * - 操作：**取消**（二次确认；正在跑的那一发可能已落库，面板如实说明）、
 *   **手动报名**（走共享 30 秒客户端，成功即与脚本互锁）、**重试**（终态失败时，
 *   前台重新拉起执行器跑短预算的重试轮）。
 */
object WenquPreregDashboardHost {

    /** 「把面板弹出来」请求计数（通知 extra / 悬浮球单击都会 +1，Host 订阅到即弹）。 */
    internal val reopenRequest = MutableStateFlow(0)

    /** 服务被系统中断时置的终态（执行器无法自己报告「我没跑完」）。 */
    internal fun markInterrupted(reason: String) {
        WenquPreregRunner.updateDash { cur ->
            if (cur.isRunning) {
                cur.copy(
                    phase = WenquPreregDashState.Phase.Done,
                    success = false,
                    resultText = reason + "。本轮没能跑完，请进活动详情确认是否已报上。",
                )
            } else {
                cur
            }
        }
    }

    /**
     * 进程内重开（2026-10-21）：App 已在前台时悬浮球单击走这里；通知点进来由
     * MainActivity 消费 [EXTRA_DASH_REOPEN] 后也走这里——都能突破「关过不再自动弹」。
     */
    fun reopen() {
        reopenRequest.value += 1
    }
}

/** 通知「点击打开仪表板」的 Intent 工厂（[WenquPreregReminder.dashboardPendingIntent] 用）。 */
object WenquPreregDashboardActivityJump {
    /**
     * 直指 [MainActivity]（同 `subpageLaunchIntent` 的跳板口径：根恒为主窗口、
     * NEW_TASK|CLEAR_TOP 清掉二级页），不附 screen extra——纯「回到 App + 弹面板」；
     * [EXTRA_DASH_REOPEN] 由 MainActivity 消费后调 [WenquPreregDashboardHost.reopen]。
     */
    fun intent(context: Context): Intent =
        Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_DASH_REOPEN, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
}

/**
 * 通知落点 Intent 上的 extra：MainActivity 在 onCreate / onNewIntent 读到它就调
 * [WenquPreregDashboardHost.reopen]——通知点进来的重开不受「用户关过不再自动弹」限制
 * （启动新进程时进程内标记本来就没了，自动弹那条路径只看 running + 前台，天然覆盖；
 * 这个 extra 补的是**进程活着但面板被关过**的情形）。
 */
const val EXTRA_DASH_REOPEN = "edu.jxslu.schedule.extra.WENQU_PREREG_DASH_REOPEN"

@Composable
fun WenquPreregDashboardHost() {
    val context = LocalContext.current
    val state by WenquPreregRunner.dash.collectAsState()
    val reopen by WenquPreregDashboardHost.reopenRequest.collectAsState()
    val foreground by WenquAppForeground.foreground.collectAsState()
    // 用户关过面板后本轮不再自动弹（结果通知与悬浮球仍可重开）
    var dismissedThisRun by remember { mutableStateOf(false) }
    var show by remember { mutableStateOf(false) }

    // 通知重开：标记一变且状态非 Idle 就弹（清掉 dismissed）
    LaunchedEffect(reopen) {
        if (reopen > 0 && state.phase != WenquPreregDashState.Phase.Idle) {
            dismissedThisRun = false
            show = true
        }
    }

    // 自动弹：前台 + 在跑 + 没关过。后台起跑（state 变 Running 但前台 false）不弹，
    // 等用户回前台时（foreground 变 true）这条已满足，自然弹出。
    LaunchedEffect(state.phase, foreground) {
        val running = state.isRunning
        if (running && foreground && !dismissedThisRun) {
            show = true
        }
        if (!running) {
            dismissedThisRun = false
            if (state.phase == WenquPreregDashState.Phase.Idle) show = false
        }
    }    // 终态震一下（结果通知同口径；面板开着时通知里那条已震过，这里只补「面板在场」的反馈）
    LaunchedEffect(state.phase, state.success) {
        if (state.phase == WenquPreregDashState.Phase.Done) {
            if (state.success == true || state.success == false) {
                ReminderVibration.vibrate(context)
            }
        }
    }

    if (show && state.phase != WenquPreregDashState.Phase.Idle) {
        WenquPreregDashboardDialog(
            state = state,
            onDismiss = {
                show = false
                if (state.isRunning) dismissedThisRun = true
            },
        )
    }
}

@Composable
private fun WenquPreregDashboardDialog(
    state: WenquPreregDashState,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmCancel by remember { mutableStateOf(false) }
    var manualBusy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (state.isRunning) "正在为你抢志愿活动" else dashDoneTitle(state)) },
        text = {
            DialogWindowFadeEffect()
            Column {
                Text(
                    text = state.title.ifBlank { "志愿服务活动" },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                if (state.opensAt > 0L) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "报名开始 ${WenquRules.preregOpenLabel(state.opensAt, System.currentTimeMillis())}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                Spacer(Modifier.height(12.dp))
                when (state.phase) {
                    WenquPreregDashState.Phase.Checking -> DashSpinnerLine("正在核对该活动的最新状态…")
                    WenquPreregDashState.Phase.Waiting -> DashCountdown(state.opensAt)
                    WenquPreregDashState.Phase.Firing -> DashFiring(state)
                    WenquPreregDashState.Phase.FinalCheck -> DashSpinnerLine("收工核对：确认是否已报上…")
                    WenquPreregDashState.Phase.Done -> DashResult(state)
                    WenquPreregDashState.Phase.Idle -> Unit
                }
                if (state.isRunning && state.phase == WenquPreregDashState.Phase.Firing) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = {
                            if (state.maxAttempts > 0) state.attempts / state.maxAttempts.toFloat() else 0f
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            when {
                state.isRunning -> TextButton(onClick = { confirmCancel = true }) { Text("取消") }
                state.phase == WenquPreregDashState.Phase.Done && state.success == false ->
                    TextButton(onClick = {
                        WenquPreregReminder.startRetry(context)
                    }) { Text("重试（5 发）") }
                else -> TextButton(onClick = onDismiss) { Text("完成") }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (state.isRunning) {
                    TextButton(
                        enabled = !manualBusy,
                        onClick = {
                            manualBusy = true
                            scope.launch {
                                manualApplyFromDashboard(context, state.id)
                                manualBusy = false
                            }
                        },
                    ) { Text(if (manualBusy) "报名中…" else "手动报名") }
                }
                if (state.phase == WenquPreregDashState.Phase.Done) {
                    TextButton(onClick = onDismiss) { Text("关闭") }
                } else {
                    TextButton(onClick = onDismiss) { Text("收起") }
                }
            }
        },
    )
    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text("取消这次自动抢？") },
            text = {
                DialogWindowFadeEffect()
                Text(
                    text = "取消后不再自动提交。若正在提交的那一发已在平台生效，" +
                        "活动详情页会显示「已报名」，不会丢失名额。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmCancel = false
                    scope.launch { WenquPreregReminder.cancel(context) }
                }) { Text("确认取消") }
            },
            dismissButton = {
                TextButton(onClick = { confirmCancel = false }) { Text("继续抢") }
            },
        )
    }
}

private fun dashDoneTitle(state: WenquPreregDashState): String = when (state.success) {
    true -> "报名成功"
    false -> "没抢到"
    null -> "已取消"
}

@Composable
private fun DashSpinnerLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val transition = rememberInfiniteTransition(label = "dashSpin")
        val alpha by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
            label = "dashSpinAlpha",
        )
        Spacer(
            Modifier
                .size(10.dp)
                .alpha(alpha)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
        )
        Spacer(Modifier.size(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
        )
    }
}

@Composable
private fun DashCountdown(opensAt: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(opensAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    val tryStart = WenquRules.preregTryStartAt(opensAt, 0L) // 展示口径：本地钟，服务器偏移由执行器折算
    val left = ((tryStart - now) / 1000L).coerceAtLeast(0L)
    Column {
        Text(
            text = "%d:%02d".format(left / 60, left % 60),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "距开始试发（已按平台提前放行折算）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}

@Composable
private fun DashFiring(state: WenquPreregDashState) {
    Column {
        Text(
            text = "第 ${state.attempts} 发" + if (state.maxAttempts > 0) " / ${state.maxAttempts}" else "",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        if (state.lastMessage.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "最近返回：${state.lastMessage}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun DashResult(state: WenquPreregDashState) {
    val color = when (state.success) {
        true -> MaterialTheme.colorScheme.primary
        false -> MaterialTheme.colorScheme.error
        null -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    }
    Column {
        Text(
            text = when (state.success) {
                true -> "✓ 已自动提交成功"
                false -> "✗ 本轮没抢到"
                null -> "已取消自动抢"
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = color,
        )
        if (state.resultText.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = state.resultText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            )
        }
        if (state.resultNote.isNotBlank() && state.success != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = state.resultNote,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * 面板上的「手动报名」：走共享 30 秒客户端（[WenquRepository.applyActivity]，写一次 +
 * 结果不明补只读核对），成功 / 「已报名」话术都把互锁点亮（索引置已报名），执行器
 * 下一轮自查收手。失败原因落回面板的「最近返回」。
 */
private suspend fun manualApplyFromDashboard(context: Context, activityId: Long) {
    if (activityId <= 0L) return
    val repo = Graph.wenqu(context)
    try {
        repo.applyActivity(activityId)
        WenquPreregRunner.updateDash { it.copy(lastMessage = "手动报名成功，自动抢已停止") }
    } catch (e: WenquSessionExpiredException) {
        WenquPreregRunner.updateDash {
            it.copy(lastMessage = "登录态失效（${e.message ?: "请重新登录"}）")
        }
    } catch (e: Exception) {
        val msg = e.message?.takeIf { it.isNotBlank() } ?: "报名失败"
        // 平台的「已报名」话术走这里也按已报名呈现（互锁靠平台终裁）
        val applied = WenquRules.preregRejectIsApplied(WenquRules.classifyPreregReject(msg))
        WenquPreregRunner.updateDash {
            it.copy(lastMessage = if (applied) "平台：$msg（已报上，无需重复）" else "手动报名：$msg")
        }
    }
}
