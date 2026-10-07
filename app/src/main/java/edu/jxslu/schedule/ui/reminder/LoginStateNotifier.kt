package edu.jxslu.schedule.ui.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import edu.jxslu.schedule.EXTRA_ROUTE
import edu.jxslu.schedule.MainActivity
import edu.jxslu.schedule.ROUTE_ME
import edu.jxslu.schedule.data.session.LoginTarget
import edu.jxslu.schedule.data.session.SessionStatus
import edu.jxslu.schedule.data.repo.PendingLaunch
import edu.jxslu.schedule.data.repo.PendingNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 登录状态失效提醒（DESIGN §3.16 / §4.27）。
 *
 * **只在「转停用」那一刻发一条**：会话过期但凭证有效时不打扰用户（那时会自动续登）。
 * 这才是「失效」与「过期」必须分开的意义——混在一起，用户会被无意义提示淹没，
 * 真正的密码错误反而失去信号。
 *
 * channel 新开 `login_state`，不复用 `balance_alert`：用户想把登录提醒静音时，
 * 不该连带把余额提醒一起静音。id 一次定死——建出来之后改不动（§3.9 那条踩坑记录）。
 */
object LoginStateNotifier {

    private const val CHANNEL_ID = "login_state"
    private const val NOTIFICATION_ID = 1007

    /** 通知 id 与 requestCode 分离：两者共用一个值会让不同通知的落点互相改写（§3.9）。 */
    private const val REQUEST_CODE = 3007

    private const val TAG = "LoginStateNotifier"

    /** 冷启动挂一次（[edu.jxslu.schedule.JuwApplication]）。 */
    fun observe(context: Context, scope: CoroutineScope) {
        val appContext = context.applicationContext
        scope.launch {
            // StateFlow 自带去重：值没变不会重发，不需要再套 distinctUntilChanged
            SessionStatus.suspended
                .collect { suspended ->
                    // 恢复成空集（用户更新了密码）不发通知；空集本身也没有内容可写
                    if (suspended.isEmpty()) return@collect
                    // 免扰时段（DESIGN §3.7）：登录失效是事实型通知（不改密码就一直好不了），
                    // 拦下入队待时段结束后补发。判定要读 DataStore（挂起），collect 本就是协程
                    val held = runCatching { holdsLoginNotice(appContext, suspended) }
                        .getOrDefault(false)
                    if (held) return@collect
                    runCatching { post(appContext, suspended) }
                        .onFailure { Log.w(TAG, "notify failed", it) }
                }
        }
    }

    /**
     * 免扰判定（供 [observe] 在拦截语境下调用）：在免扰时段内则入队待补发。
     * 单独成函数是因为拦截描述符在这里组装（title/text 由 targets 渲染）。
     */
    internal suspend fun holdsLoginNotice(context: Context, targets: Set<LoginTarget>): Boolean {
        val names = targets.joinToString("、") { it.displayName() }
        return QuietHoursGate.intercept(
            context,
            PendingNotification(
                tag = null,
                notificationId = NOTIFICATION_ID,
                channelId = CHANNEL_ID,
                category = NotificationCompat.CATEGORY_ERROR,
                title = "登录状态已失效",
                text = "$names 的自动登录已暂停，请在「我的」页更新账号密码",
                enqueuedAt = System.currentTimeMillis(),
                launch = PendingLaunch(
                    kind = PendingLaunch.Kind.MAIN_ROUTE,
                    requestCode = REQUEST_CODE,
                    route = ROUTE_ME,
                ),
            ),
        )
    }

    private fun post(context: Context, targets: Set<LoginTarget>) {
        val manager = NotificationManagerCompat.from(context)
        // 通知被系统/用户整体关掉时静默跳过：写不出去也不该崩
        if (!manager.areNotificationsEnabled()) return
        ensureChannel(context)

        val names = targets.joinToString("、") { it.displayName() }
        val text = "$names 的自动登录已暂停，请在「我的」页更新账号密码"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(edu.jxslu.schedule.R.drawable.ic_stat_bell)
            .setContentTitle("登录状态已失效")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setContentIntent(contentIntent(context))
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    /** 落「我的」页——账户卡就在那里，用户点进去直接改密码。 */
    private fun contentIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(EXTRA_ROUTE, ROUTE_ME)
        }
        return PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        // DEFAULT 而非 HIGH：失效不紧急（功能仍可用，只是不再自动登录），
        // 不值得横幅打断当前操作
        val channel = NotificationChannel(
            CHANNEL_ID,
            "登录状态",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = "学校账号或一卡通登录失效时提醒更新密码" }
        manager.createNotificationChannel(channel)
    }

    /** 状态卡上的显示名，与 §3.16 三格一致。 */
    private fun LoginTarget.displayName(): String = when (this) {
        LoginTarget.Jw -> "教务"
        LoginTarget.Ykt -> "一卡通"
        LoginTarget.Qiekj -> "胖乖生活"
    }
}
