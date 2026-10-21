package edu.jxslu.schedule.ui.wenqu

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.R
import edu.jxslu.schedule.data.wenqu.WenquActivity
import edu.jxslu.schedule.domain.WenquRules
import edu.jxslu.schedule.ui.reminder.ReminderVibration
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * 预报名（准点自动抢，DESIGN §4.45，2026-10-16；用户拍板）。
 *
 * **全 App 唯一允许自动提交的路径**：用户在活动详情里对单条活动点「预报名」，
 * 到点由精确闹钟拉起 [WenquPreregService]（短时前台服务）自动试发报名；
 * 其余一切写操作仍是「用户点一次发一次」。
 *
 * 排法沿用「每周日提醒」那套：主 = `setAlarmClock`（requestCode **4008**，不需要特殊权限），
 * 触发时刻 = 报名开始前 [WenquRules.PREREG_LEAD_MS]；兜 = 一次性 Work（闹钟到点起前台
 * 服务失败时，直接跑执行器）；冷启动 / 开机 / 覆盖安装再补排。
 *
 * 错过的处理（冷启动 / 开机时发现报名早已开始又结束）：作废并把实情通知出来，
 * 不让用户以为「还排着」。
 *
 * 通知（channel `wenqu_prereg` / id [NOTIFICATION_ID] / 落点 requestCode **3016**）：
 * - 执行中：常驻进度（静默，服务持有）；
 * - 结果：报名成功 / 没抢到（直接震动；**不过免扰时段**——同「名额空出提醒」口径，
 *   这是用户为「抢」显式开启的事）。
 */
object WenquPreregReminder {

    private const val TAG = "WenquPrereg"

    /** 兜底一次性 Work（起服务失败）。 */
    private const val FALLBACK_WORK = "wenqu_prereg_run"

    /** 开机 / 覆盖安装补排 Work。 */
    private const val RESCHEDULE_WORK = "wenqu_prereg_reschedule"

    /**
     * 闹钟的 requestCode。既有占用 4001–4007（登记见 `WenquWeeklyAlertReminder`），
     * 预报名取 4008。PendingIntent 的身份是「requestCode + Intent.filterEquals」，
     * 撞了会互相顶掉。
     */
    private const val ALARM_REQUEST_CODE = 4008

    /** 通知点击落点。落点 requestCode 既有 0 / 3005–3015，预报名取 3016。 */
    private const val CONTENT_REQUEST_CODE = 3016

    /** 重试 action 的 PendingIntent requestCode（服务侧，避开既有 0 / 3005–3016）。 */
    private const val RETRY_REQUEST_CODE = 3017

    private const val CHANNEL_ID = "wenqu_prereg"
    /**
     * 固定通知 id：1013 新增活动 / 1015 每周提醒 / 1016 名额空出；预报名取 1017。
     *
     * **不带 tag**（全 App 唯一的无 tag 通知）：它要和 [WenquPreregService] 的
     * `startForeground(NOTIFICATION_ID, …)` 共用同一个通知键——带 tag 的
     * `notify(tag, id, …)` 是另一个条目，进度更新会变成第二条通知、`stopForeground`
     * 也摘不掉它。
     */
    const val NOTIFICATION_ID = 1017

    /**
     * 排上一条预报名：**先落盘再排闹钟**（顺序反了会让闹钟读不到目标）。
     * [opensAt] = 报名开始时刻（平台墙钟，epoch millis）。
     */
    suspend fun arm(context: Context, activity: WenquActivity, opensAt: Long) {
        val title = activity.title.ifBlank { activity.projectName.ifBlank { "志愿服务活动" } }
        Graph.displayPrefs(context).setWenquPrereg(activity.id, title, opensAt)
        scheduleNext(context)
    }

    /** 取消：清落盘 + 撤闹钟（在飞的一轮由执行器自查落盘后自行收手）。 */
    suspend fun cancel(context: Context) {
        Graph.displayPrefs(context).clearWenquPrereg()
        cancelAlarm(context)
        WorkManager.getInstance(context).cancelUniqueWork(FALLBACK_WORK)
    }

    /**
     * 重排闹钟（幂等）：没排就撤，已过窗口就作废并说明，到点没到就排上。
     * 新排 / 取消 / 开机 / 覆盖安装 / 冷启动都调它。
     */
    suspend fun scheduleNext(context: Context) {
        runCatching {
            val prefs = Graph.displayPrefs(context)
            val id = prefs.wenquPreregId.first()
            val title = prefs.wenquPreregTitle.first()
            val opensAt = prefs.wenquPreregOpensAt.first()
            val manager = context.getSystemService(AlarmManager::class.java)
            if (id <= 0L || opensAt <= 0L) {
                manager?.cancel(alarmPendingIntent(context))
                WorkManager.getInstance(context).cancelUniqueWork(FALLBACK_WORK)
                return
            }
            val now = System.currentTimeMillis()
            when {
                now > opensAt + WenquRules.PREREG_END_AFTER_MS -> {
                    // 错过（关机 / 长时间没开 App）：作废 + 说清实情
                    prefs.clearWenquPrereg()
                    manager?.cancel(alarmPendingIntent(context))
                    WorkManager.getInstance(context).cancelUniqueWork(FALLBACK_WORK)
                    postResult(
                        context,
                        "预报名未执行：$title",
                        "已过报名时间（手机当时可能未开机 / 应用没运行），请手动查看。",
                        vibrate = false,
                    )
                }

                now >= WenquRules.preregAlarmAt(opensAt) -> {
                    // 已经进窗口（冷启动 / 开机赶上）：1 秒后立刻拉起
                    manager?.setAlarmClock(
                        AlarmManager.AlarmClockInfo(now + 1_000L, clockPendingIntent(context)),
                        alarmPendingIntent(context),
                    )
                    enqueueSafetyNet(context, now + 1_000L)
                }

                else -> {
                    manager?.setAlarmClock(
                        AlarmManager.AlarmClockInfo(
                            WenquRules.preregAlarmAt(opensAt),
                            clockPendingIntent(context),
                        ),
                        alarmPendingIntent(context),
                    )
                    enqueueSafetyNet(context, WenquRules.preregAlarmAt(opensAt))
                }
            }
        }.onFailure { Log.w(TAG, "scheduleNext failed", it) }
    }

    /**
     * 安全网：按触发时刻排一条**延迟的一次性 Work**（REPLACE 旧的）。
     *
     * 精确闹钟在个别 ROM 上可能被丢 / 被延迟；这条兜底到点再试一次（WorkManager 不保证
     * 准点，晚几分钟算兜住窗口尾巴）。闹钟正常时它到点会发现落盘已清（跑完了）或服务
     * 已在跑，直接空跑——执行器本身幂等（见 [WenquPreregRunner]）。
     */
    private fun enqueueSafetyNet(context: Context, triggerAt: Long) {
        val delayMs = (triggerAt - System.currentTimeMillis()).coerceAtLeast(0L)
        WorkManager.getInstance(context).enqueueUniqueWork(
            FALLBACK_WORK,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<WenquPreregFallbackWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .build(),
        )
    }

    /** 由 Receiver / Worker 调：起短时前台服务跑执行器；起不来回 false（调用方走兜底）。 */
    fun startService(context: Context): Boolean = runCatching {
        context.startForegroundService(Intent(context, WenquPreregService::class.java))
    }.onFailure { Log.w(TAG, "start service failed", it) }.isSuccess

    /**
     * 起服务跑**重试轮**（2026-10-21）：仪表板「重试」按钮与结果通知共用这一条。
     * 调用方都在前台（面板按钮）或经通知点击（先弹回 App），前台启动前台服务合规。
     */
    fun startRetry(context: Context): Boolean = runCatching {
        context.startForegroundService(
            Intent(context, WenquPreregService::class.java)
                .putExtra(WenquPreregService.EXTRA_RETRY, true),
        )
    }.onFailure { Log.w(TAG, "start retry failed", it) }.isSuccess

    /** 兜底 Work（幂等）：闹钟到点起前台服务失败时由 Receiver 排上。 */
    fun enqueueFallback(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            FALLBACK_WORK,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<WenquPreregFallbackWorker>().build(),
        )
    }

    /** 开机 / 覆盖安装补排（挂在 WorkManager 里做，Receiver 不做挂起 IO）。 */
    fun enqueueReschedule(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            RESCHEDULE_WORK,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<WenquPreregRescheduleWorker>().build(),
        )
    }

    // ---- 通知 -----------------------------------------------------------------

    /**
     * 执行中的常驻进度通知（静默；服务结束时由服务决定摘掉还是保留）。
     *
     * 点击 = 打开 App 并**直接弹出抢活动仪表板**：落点 Intent 直指 MainActivity
     * （[WenquPreregDashboardActivityJump.intent]）并带 [EXTRA_DASH_REOPEN]，
     * MainActivity 在 onCreate / onNewIntent 读到后调 `WenquPreregDashboardHost.reopen`
     * ——关过面板的用户靠它找回来，不被「dismissed 不再自动弹」挡住。
     */
    fun buildProgressNotification(context: Context, text: String): Notification {
        ensureChannel(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bell)
            .setContentTitle("正在为你抢志愿活动")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(dashboardPendingIntent(context))
            .build()
    }

    /** 刷新常驻进度（服务持有同一个通知编号，就地替换文案）。 */
    fun updateProgress(context: Context, text: String) {
        runCatching {
            // 无 tag：与 startForeground 的通知键对齐（见 NOTIFICATION_ID 注释）
            NotificationManagerCompat.from(context)
                .notify(NOTIFICATION_ID, buildProgressNotification(context, text))
        }.onFailure { Log.w(TAG, "progress notify failed", it) }
    }

    /**
     * 结果通知（报名成功 / 没抢到 / 没执行）：直接震动、不过免扰（同名额空出提醒口径）。
     *
     * 没抢到的结果带一枚**重试** action（2026-10-21）：点击走前台重拉执行器跑重试轮
     * （预算 5 发 / 30 秒）；通知落点也换成「打开仪表板」——用户要看的就是结果与后续操作。
     */
    fun postResult(context: Context, title: String, text: String, vibrate: Boolean = true) {
        ensureChannel(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bell)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(dashboardPendingIntent(context))
        if (title.startsWith("没抢到")) {
            builder.addAction(
                0,
                "重试",
                // **必须是 getForegroundService**：点 action 时 App 多半在后台，
                // getService 拉起的普通 service 启动里调 startForeground 会被
                // `ForegroundServiceStartNotAllowedException` 打掉（后台 FGS 限制）；
                // getForegroundService 声明了 FGS 启动意图，用户交互（点通知 action）
                // 属于允许的后台启动场景。
                PendingIntent.getForegroundService(
                    context,
                    RETRY_REQUEST_CODE,
                    Intent(context, WenquPreregService::class.java)
                        .putExtra(WenquPreregService.EXTRA_RETRY, true),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        if (vibrate) ReminderVibration.vibrate(context)
        runCatching {
            // 无 tag：与 startForeground 的通知键对齐（见 NOTIFICATION_ID 注释）
            NotificationManagerCompat.from(context)
                .notify(NOTIFICATION_ID, builder.build())
        }.onFailure { Log.w(TAG, "result notify failed", it) }
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "预报名抢活动",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "预报名执行的进度与结果（报名成功 / 没抢到）"
            enableVibration(true)
            vibrationPattern = ReminderVibration.PATTERN
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * 「打开仪表板」落点（2026-10-21）：拉起主窗口 + 附「重开面板」标记（结果通知与进度
     * 通知都用它；面板关过的用户从通知点进来不受「不再自动弹」限制）。
     */
    private fun dashboardPendingIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        CONTENT_REQUEST_CODE,
        WenquPreregDashboardActivityJump.intent(context),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun alarmPendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        ALARM_REQUEST_CODE,
        Intent(context, WenquPreregAlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun clockPendingIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun cancelAlarm(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(alarmPendingIntent(context))
    }
}

/** 预报名闹钟落点：直接起短时前台服务；起不来排兜底 Work。 */
class WenquPreregAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!WenquPreregReminder.startService(context)) {
            WenquPreregReminder.enqueueFallback(context)
        }
    }
}

/**
 * 兜底执行（闹钟到点但前台服务起不来、或安全网 Work 到点）：先自查落盘——
 * 没排上（取消 / 已跑完 / 错过）直接空跑；太早（WorkManager 提前唤醒）交还闹钟；
 * 然后先试起服务，起不来的话**在本 Worker 里直接跑执行器**（无进度通知，结果通知照发）。
 */
class WenquPreregFallbackWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val prefs = Graph.displayPrefs(applicationContext)
        val id = prefs.wenquPreregId.first()
        val opensAt = prefs.wenquPreregOpensAt.first()
        if (id <= 0L || opensAt <= 0L) return Result.success()
        if (System.currentTimeMillis() < WenquRules.preregAlarmAt(opensAt) - 5_000L) {
            return Result.success()
        }
        if (!WenquPreregReminder.startService(applicationContext)) {
            runCatching { WenquPreregRunner.run(applicationContext, showProgress = false) }
        }
        return Result.success()
    }
}

/** 开机 / 覆盖安装补排（类名不要改：WorkManager 把类名存进自己的库）。 */
class WenquPreregRescheduleWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        WenquPreregReminder.scheduleNext(applicationContext)
        return Result.success()
    }
}
