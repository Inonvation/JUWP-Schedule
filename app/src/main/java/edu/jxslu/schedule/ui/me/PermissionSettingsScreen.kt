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
import androidx.compose.foundation.layout.Arrangement
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
import edu.jxslu.schedule.ui.common.AppPermissions
import edu.jxslu.schedule.ui.common.PermissionRow
import edu.jxslu.schedule.ui.common.SettingsSection
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.BatteryCharging01
import me.rerere.hugeicons.stroke.CalendarSetting01
import me.rerere.hugeicons.stroke.Energy
import me.rerere.hugeicons.stroke.Image01
import me.rerere.hugeicons.stroke.MapsLocation02
import me.rerere.hugeicons.stroke.Notification01

/**
 * 「我的 → 权限设置」统一出口（DESIGN §3.12）：忽略电池优化 / 允许自启动（锁后台）/
 * 通知 / 日历 / 定位（+ Android 9 及以下才有的相册写入），一处讲清、逐项申请。
 *
 * 交互口径与桌面小组件设置页（§3.6）相同：进页只读检测，不主动弹任何系统框；
 * 「去开启」都是用户点了才动。跳转复用 [WidgetCapabilities]，不复制第二份
 * （电池优化确认框、厂商自启动页的候选列表与兜底都只在那一处维护）；
 * 权限检测与「应用详情页」落点走 [AppPermissions]，同样只有一份。
 *
 * 运行时权限（通知 / 日历 / 定位 / 相册）一律：先弹系统申请框，**被拒后立刻跳系统设置页**，
 * 不在原地重弹——系统从第二次起静默拒绝，申请框根本不出现，用户反复点只会觉得按钮坏了；
 * 通知在 API 26–32 没有申请框，直接跳通知设置页。状态徽标在 launcher 回调与 ON_RESUME
 * 两处重读，自己改完和去系统设置改完都能一致。
 *
 * 相册写入整块只在 Android 9 及以下出现：10 起二维码走 MediaStore，系统不要这个权限，
 * 在老系统之外显示一行「未开启」只会误导。
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
    var calendarGranted by remember { mutableStateOf(AppPermissions.calendarGranted(context)) }
    var locationGranted by remember { mutableStateOf(AppPermissions.locationGranted(context)) }
    // 权限给全了不代表定位能用：系统「位置信息」总开关另算，两种失败要分开讲
    var locationServiceOn by remember { mutableStateOf(AppPermissions.locationServiceEnabled(context)) }
    var albumWriteGranted by remember { mutableStateOf(AppPermissions.albumWriteGranted(context)) }

    // 从系统设置 / 厂商管家页回来时重读徽标（小组件设置页同口径）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryWhitelisted = WidgetCapabilities.isIgnoringBatteryOptimizations(context)
                notifEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
                calendarGranted = AppPermissions.calendarGranted(context)
                locationGranted = AppPermissions.locationGranted(context)
                locationServiceOn = AppPermissions.locationServiceEnabled(context)
                albumWriteGranted = AppPermissions.albumWriteGranted(context)
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

    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        calendarGranted = grants.values.all { it }
        if (!grants.values.all { it }) AppPermissions.jumpAppDetails(context)
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        // 精确给不给都算可用，粗略那一档也够定位到那一片
        locationGranted = grants.values.any { it }
        locationServiceOn = AppPermissions.locationServiceEnabled(context)
        if (!locationGranted) AppPermissions.jumpAppDetails(context)
    }

    val albumWriteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        albumWriteGranted = granted
        if (!granted) AppPermissions.jumpAppDetails(context)
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

    // 申请前只挑缺的那几个：已经给过的权限再申请一次，系统会当拒绝处理（框都不弹）
    val requestCalendar = {
        val needed = AppPermissions.missing(context, AppPermissions.calendar)
        if (needed.isEmpty()) {
            AppPermissions.jumpAppDetails(context)
        } else {
            calendarPermissionLauncher.launch(needed.toTypedArray())
        }
    }

    val requestLocation = {
        val needed = AppPermissions.missing(context, AppPermissions.location)
        if (needed.isEmpty()) {
            AppPermissions.jumpAppDetails(context)
        } else {
            locationPermissionLauncher.launch(needed.toTypedArray())
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
            // 分区卡之间的 12dp 换气（其余设置页统一口径）
            verticalArrangement = Arrangement.spacedBy(12.dp),
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
            SettingsSection(
                title = "日历",
                subtitle = "同步课表进手机日历、骑行免费时长提醒写日历，都要这一对权限",
            ) {
                PermissionRow(
                    title = "日历读写",
                    detail = if (calendarGranted) {
                        "已开启；课表同步与骑行提醒都能写进系统日历"
                    } else {
                        "未开启时日历同步会停在提示上；点「去开启」授权"
                    },
                    granted = calendarGranted,
                    icon = HugeIcons.CalendarSetting01,
                    actionText = if (calendarGranted) "查看" else "去开启",
                    onClick = { requestCalendar() },
                )
            }
            SettingsSection(
                title = "定位",
                subtitle = "只给「附近单车」里看车在哪用；不给也能手动拖动地图找车",
            ) {
                PermissionRow(
                    title = "位置信息",
                    detail = when {
                        !locationGranted -> "未开启时「附近单车」定位不动；点「去开启」授权，选「大致位置」也够用"
                        !locationServiceOn -> "App 已授权，但系统的「位置信息」总开关关着，定位仍会失败"
                        else -> "已开启；只在单车地图页取一次坐标，不做后台跟踪"
                    },
                    granted = locationGranted && locationServiceOn,
                    icon = HugeIcons.MapsLocation02,
                    actionText = if (locationGranted) "查看" else "去开启",
                    // 只有权限真的缺了才申请；总开关关着时带用户去应用详情页没有意义
                    onClick = {
                        if (locationGranted) AppPermissions.jumpAppDetails(context) else requestLocation()
                    },
                )
            }
            // Android 10 起系统相册走 MediaStore，不需要权限；这一段只服务老系统的设备
            if (AppPermissions.albumWriteNeeded) {
                SettingsSection(
                    title = "相册",
                    subtitle = "把共享单车二维码存进相册「水贝贝」；Android 10 起系统相册不再需要权限",
                ) {
                    PermissionRow(
                        title = "相册写入",
                        detail = if (albumWriteGranted) {
                            "已开启；保存二维码能落到相册目录"
                        } else {
                            "未开启时「保存到相册」会提示没有相册写入权限，二维码出得来但存不下"
                        },
                        granted = albumWriteGranted,
                        icon = HugeIcons.Image01,
                        actionText = if (albumWriteGranted) "查看" else "去开启",
                        onClick = {
                            if (albumWriteGranted) {
                                AppPermissions.jumpAppDetails(context)
                            } else {
                                albumWriteLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            }
                        },
                    )
                }
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
    AppPermissions.jumpAppDetails(context)
}
