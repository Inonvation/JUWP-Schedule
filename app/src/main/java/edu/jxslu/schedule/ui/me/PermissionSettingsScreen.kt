package edu.jxslu.schedule.ui.me

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import edu.jxslu.schedule.ui.common.PermissionRow
import edu.jxslu.schedule.ui.common.SettingsSection
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.BatteryCharging01
import me.rerere.hugeicons.stroke.Energy
import me.rerere.hugeicons.stroke.Notification01

/**
 * 「我的 → 权限设置」统一出口（DESIGN §3.12）：忽略电池优化 / 允许自启动（锁后台）/
 * 通知权限，一处讲清、逐项申请。
 *
 * 交互口径与桌面小组件设置页（§3.6）相同：进页只读检测，不主动弹任何系统框；
 * 「去开启」都是用户点了才动。跳转复用 [WidgetCapabilities]，不复制第二份
 * （电池优化确认框、厂商自启动页的候选列表与兜底都只在那一处维护）。
 * 通知权限：API 33+ 未授权 → 先弹系统申请框；一旦被拒（含系统已静默拒绝、申请框不再出现）
 * 或 API 26–32 直接跳系统通知设置页，不留在原地反复点。状态徽标 ON_RESUME 重读，
 * 从系统设置回来后一致。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    var batteryWhitelisted by remember {
        mutableStateOf(WidgetCapabilities.isIgnoringBatteryOptimizations(context))
    }
    var notifEnabled by remember {
        mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled())
    }

    // 从系统设置 / 厂商管家页回来时重读徽标（小组件设置页同口径）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryWhitelisted = WidgetCapabilities.isIgnoringBatteryOptimizations(context)
                notifEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        // 以实际系统状态为准重读（拒绝后徽标也要跟着变）
        notifEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        // 拒绝后不再原地重弹：系统第二次起会静默拒绝（申请框根本不出现），
        // 用户在同一个按钮上反复点只会觉得"点了没用"。直接带去系统通知设置页，
        // 那里是拒绝之后唯一还能把开关打开的地方
        if (!granted) jumpNotificationSettings(context)
    }

    val requestNotif = {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            jumpNotificationSettings(context)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("权限设置") },
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
                .padding(16.dp),
        ) {
            SettingsSection(
                title = "保持后台可用",
                subtitle = "上课提醒与桌面小组件靠后台刷新；不开也能用，开启后更及时",
            ) {
                PermissionRow(
                    title = "忽略电池优化",
                    detail = "防止系统冻结后台刷新；不同手机叫「电池优化白名单 / 省电策略无限制」",
                    granted = batteryWhitelisted,
                    icon = HugeIcons.BatteryCharging01,
                    actionText = if (batteryWhitelisted) "查看" else "去开启",
                    onClick = { WidgetCapabilities.jumpBatteryOptimization(context) },
                )
                PermissionRow(
                    title = "允许自启动 / 锁后台",
                    detail = "开机与被杀后能自行恢复；在厂商设置里找「自启动 / 允许后台运行 / 锁后台」",
                    granted = null,
                    icon = HugeIcons.Energy,
                    actionText = "去设置",
                    onClick = { WidgetCapabilities.jumpAutoStart(context) },
                )
            }
            SettingsSection(
                title = "通知",
                subtitle = "上课提醒、作业截止与免费骑行提醒都要通知权限",
            ) {
                PermissionRow(
                    title = "允许通知",
                    detail = if (notifEnabled) {
                        "已开启；在系统设置里可按渠道细分"
                    } else {
                        "关闭时提醒不会弹出；点「去开启」授权"
                    },
                    granted = notifEnabled,
                    icon = HugeIcons.Notification01,
                    actionText = if (notifEnabled) "去设置" else "去开启",
                    onClick = { requestNotif() },
                )
            }
        }
    }
}

/** 跳系统「应用通知设置」页（渠道细分入口）；ROM 不支持该 action 时兜底应用详情页。 */
private fun jumpNotificationSettings(context: Context) {
    val appNotify = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    val started = runCatching {
        if (appNotify.resolveActivity(context.packageManager) == null) {
            return@runCatching false
        }
        context.startActivity(appNotify)
        true
    }.getOrDefault(false)
    if (started) return
    // 兜底：应用详情页（与 WidgetCapabilities 的最终兜底同一落点）
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}")),
    )
}
