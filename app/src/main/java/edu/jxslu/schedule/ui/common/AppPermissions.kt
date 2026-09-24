package edu.jxslu.schedule.ui.common

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * 运行时权限的统一检测与跳转（DESIGN §3.12）。
 *
 * 为什么集中这一份：日历那一对权限原先在课表页分享弹层、日历同步页、出码页各写了一遍
 * `listOf(READ_CALENDAR, WRITE_CALENDAR).filter { 未授予 }`，定位在单车地图与权限设置页
 * 各写了一次「精确或粗略」的或运算。口径散在三处以上时，改一条要翻四个文件。
 * 现在申请方只取清单（[calendar] / [location] / [albumWrite]）与 [missing]。
 *
 * 原则与 [edu.jxslu.schedule.ui.me.WidgetCapabilities] 一致：**检测全部只读**，
 * 不给系统弹任何东西；跳转只跳「应用详情页」这一个稳定落点，不看厂商私有页面脸色。
 */
object AppPermissions {

    /** 日历读写（DESIGN §4.12 课表同步、§3.9 骑行免费时长提醒）。两个一起申请。 */
    val calendar: List<String> = listOf(
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR,
    )

    /**
     * 定位（DESIGN §4.23 单车地图）。精确与粗略一起申请——API 31+ 的对话框分开问，
     * 用户很可能只给「大致位置」，那一档也够把地图中心落到他所在的那片车桩。
     */
    val location: List<String> = listOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    /**
     * 相册写入。**只有 Android 9 及以下（API ≤ 28）需要**：10 起二维码走 MediaStore 落
     * `Pictures/水贝贝`，系统不要求权限（manifest 里也用 `maxSdkVersion="28"` 限定了）。
     */
    val albumWriteNeeded: Boolean
        get() = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P

    /** 相册写入的权限名；本机不需要时为 null（调用方据此隐藏整行）。 */
    val albumWritePermission: String?
        get() = if (albumWriteNeeded) Manifest.permission.WRITE_EXTERNAL_STORAGE else null

    /** 相册写入是否已可用；不需要该权限的系统恒为 true。 */
    fun albumWriteGranted(context: Context): Boolean =
        albumWritePermission?.let { granted(context, it) } ?: true

    /**
     * 通知（DESIGN §3.7 上课提醒、§3.9 免费时长提醒）。**API 33+ 才有这个运行时权限**：
     * 更低版本装上即视为已授予，对它调 `checkSelfPermission` 没有意义——所以申请方一律用
     * [missingNotification]，别直接把它塞进 `RequestMultiplePermissions`。
     */
    val notification: List<String> = listOf(Manifest.permission.POST_NOTIFICATIONS)

    /** 还没拿到的通知权限；API 33 以下（那时没有这个权限）恒为空表。 */
    fun missingNotification(context: Context): List<String> =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            emptyList()
        } else {
            missing(context, notification)
        }

    /** 单个权限是否已授予。 */
    fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** [permissions] 里还没拿到的那几个；全都有则返回空表——申请前先过它，别重复弹框。 */
    fun missing(context: Context, permissions: List<String>): List<String> =
        permissions.filterNot { granted(context, it) }

    /** 日历读写是否都给全（同步与删除都要求两个都在）。 */
    fun calendarGranted(context: Context): Boolean = missing(context, calendar).isEmpty()

    /** 定位是否可用：精确或粗略**任一**授权即可（与 [edu.jxslu.schedule.ui.ebike.BikeLocator] 同口径）。 */
    fun locationGranted(context: Context): Boolean = location.any { granted(context, it) }

    /**
     * 系统「位置信息」总开关。权限给全了它也可能是关的——两种失败在用户眼里长得一样
     * （都是「定位不动」），分开报才能指出该去改哪个。
     *
     * API 28 起框架给了 `isLocationEnabled`；更低版本只能看两个 provider 有没有开着。
     * 取不到 LocationManager（无定位硬件的设备）返回 false。
     */
    fun locationServiceEnabled(context: Context): Boolean {
        val manager = context.getSystemService(LocationManager::class.java) ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return manager.isLocationEnabled
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).any { provider ->
            runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)
        }
    }

    /**
     * 跳本应用的系统详情页（权限开关、通知设置都从这一页进）。
     *
     * 弹框被系统静默拒绝之后就只剩这一条路能改授权状态，所以拒绝分支、各类兜底
     * 都用它。跳不出去时静默——此时没有任何补救手段，再弹一句提示只是噪音。
     */
    fun jumpAppDetails(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:${context.packageName}")),
            )
        }
    }

    /**
     * 「通知使用权」（`NotificationListenerService`）是否已授予本应用（DESIGN §3.9 精确倒计时）。
     *
     * 与 [notification] 是两个完全不同的东西：那个是「本应用能不能发通知」，
     * 这个是「本应用能不能读**别人的**通知」——后者隐私敏感度高得多，只能由用户在
     * 系统设置里手动勾选，应用没有任何 API 能主动申请（`RequestPermission` 那种弹框不存在）。
     */
    fun notificationListenerGranted(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /**
     * 跳系统的「通知使用权」列表页。它是标准 Settings action，没有厂商私有坑；
     * 用户在这一页里勾选本应用后才生效。跳不出去时静默（与 [jumpAppDetails] 同口径）。
     */
    fun jumpNotificationListenerSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
