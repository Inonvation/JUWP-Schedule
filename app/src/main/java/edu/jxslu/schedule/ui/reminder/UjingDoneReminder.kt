package edu.jxslu.schedule.ui.reminder

import android.app.AlarmManager
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
import edu.jxslu.schedule.SubpageRequest
import edu.jxslu.schedule.SubpageScreen
import edu.jxslu.schedule.subpageLaunchIntent

/**
 * 洗衣完成提醒（DESIGN §3.23 / §4.37 P4）：订单进入洗涤中（status 40）时按
 * 「快照时刻 + remainTime」排一枚 `setAlarmClock` 精确闹钟，到点查一次订单详情——
 * 已完成就发「衣服洗好了」，还在跑就按新剩余时间重排，别的状态（取消 / 超时）静默收摊。
 *
 * 与选课提醒（`SelectionAlertReminder`）同构，但**简单一档**：没有周期核对与已发键——
 * 提醒点完全由在案订单推导，订单没了（取消 / 知道了 / 退出登录）闹钟就地撤销；
 * 页面开着时的 15 秒轮询会不断校准闹钟落点，页面不在也只是晚这一枚闹钟兜着。
 * **没有开机补排**：一单洗衣最长个把小时，重启丢一枚闹钟的代价远小于多一条常驻链路；
 * 重启后进页刷新订单会重新排上。
 *
 * 通知开关走**系统渠道**（`ujing_done`）：提醒不是周期行为，而是用户下单的直接后果，
 * 不设 App 内开关；不想收就在系统设置里关这个渠道。
 */
object UjingDoneReminder {

    private const val TAG = "UjingDone"

    /** 一次性核对（闹钟落点）：Receiver 里不能做长任务（要查一次网络）。 */
    private const val ONE_SHOT_WORK = "ujing_done_check"

    /**
     * 闹钟 requestCode。与上课（4002）、骑行（4003）、选课（4004）错开：
     * PendingIntent 的身份是「requestCode + Intent.filterEquals」，撞了会互相顶掉。
     */
    private const val ALARM_REQUEST_CODE = 4005

    private const val CHANNEL_ID = "ujing_done"

    /** 固定通知 id：与上课（1001/1004）、作业（1002/1003）、选课（1010）等互不覆盖。 */
    private const val NOTIFICATION_ID = 1011

    /**
     * 通知点击落点的 requestCode。不能复用通知 id（PendingIntent 身份纪律，
     * 与既有落点 0/1002/3008/3009/3010 错开）。
     */
    private const val CONTENT_REQUEST_CODE = 3011

    /**
     * 排 / 重排完成闹钟（幂等）：同一 PendingIntent，后一次落点覆盖前一次。
     * 由 ViewModel 在订单变为洗涤中（含每次轮询校准）时调用。
     */
    fun schedule(context: Context, orderId: String, endAtMillis: Long) {
        runCatching {
            val manager = context.getSystemService(AlarmManager::class.java) ?: return
            // 系统级精确闹钟：到点即触发，不受 Doze/省电推迟，无需特殊权限。
            // showIntent 交给系统时钟的闹钟页（同上课/选课提醒：那枚图标只表示「有个闹钟排着」）
            val clockPending = PendingIntent.getActivity(
                context,
                0,
                Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            manager.setAlarmClock(
                AlarmManager.AlarmClockInfo(endAtMillis, clockPending),
                alarmPendingIntent(context),
            )
        }.onFailure { Log.w(TAG, "schedule failed", it) }
    }

    /** 撤销完成闹钟（订单取消 / 清掉 / 退出登录 / 已终结）。没排过时是空操作。 */
    fun cancel(context: Context) {
        runCatching {
            context.getSystemService(AlarmManager::class.java)
                ?.cancel(alarmPendingIntent(context))
        }.onFailure { Log.w(TAG, "cancel failed", it) }
    }

    /** 一次性核对（闹钟落点）。 */
    fun enqueueCheck(context: Context) {
        val request = OneTimeWorkRequestBuilder<UjingDoneCheckWorker>().build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(ONE_SHOT_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    /**
     * 核对一次：查在案订单的最新状态。**会话失效 / 网络失败就静默放弃**——
     * 查不到状态不能猜「洗完了」，错过提醒的代价小于误报。
     *
     * 兜底闹钟（按模式总时长排的，常早于真实结束）提前响时订单多半还在跑：
     * 按最新剩余时间重排即可；极端情况下订单卡在启动中（21）也会按总时长顺延重排，
     * 直到服务端给出终态（完成 / 启动失败）为止。
     */
    suspend fun check(context: Context) {
        runCatching {
            val repo = Graph.ujing(context)
            val order = repo.currentOrder() ?: run {
                cancel(context)
                return
            }
            val refreshed = repo.refreshOrder(order.orderId)
            when {
                refreshed.status == "50" -> postNotification(context, refreshed.deviceName)
                // 还在跑：按新剩余时间重排
                refreshed.status == "40" && refreshed.remainSeconds > 0 ->
                    schedule(
                        context,
                        refreshed.orderId,
                        refreshed.snapshotAt + refreshed.remainSeconds * 1000L,
                    )
                // 已支付但还没运行（兜底闹钟早响了 / 启动卡住）：按总时长顺延，等下一轮
                !refreshed.isTerminal && refreshed.paid && refreshed.durationSeconds > 0 ->
                    schedule(
                        context,
                        refreshed.orderId,
                        refreshed.snapshotAt + refreshed.durationSeconds * 1000L,
                    )
                // 其余（取消 / 超时 / 启动失败 / 状态不明）：收摊不发
                else -> cancel(context)
            }
        }.onFailure { Log.w(TAG, "check failed", it) }
    }

    private fun alarmPendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        ALARM_REQUEST_CODE,
        Intent(context, UjingDoneReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun postNotification(context: Context, deviceName: String) {
        ensureChannel(context)
        val text = buildString {
            append("「")
            append(deviceName.ifBlank { "洗衣机" })
            append("」的洗衣已结束，记得取衣服。")
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bell)
            .setContentTitle("衣服洗好了")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    CONTENT_REQUEST_CODE,
                    subpageLaunchIntent(context, SubpageRequest(SubpageScreen.UJING))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(TAG, NOTIFICATION_ID, notification)
        }.onFailure { Log.w(TAG, "post notification failed", it) }
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        // IMPORTANCE_HIGH：衣服放着不取会被别人拿出来，值得横幅（弹不弹由系统/ROM 决定）
        val channel = NotificationChannel(CHANNEL_ID, "洗衣完成提醒", NotificationManager.IMPORTANCE_HIGH)
            .apply {
                description = "U净 订单洗涤结束到点提醒"
                enableVibration(true)
            }
        manager.createNotificationChannel(channel)
    }
}

/** 完成提醒闹钟落点：只把核对排进 WorkManager（核对要查一次网络，不能在 Receiver 里等）。 */
class UjingDoneReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        UjingDoneReminder.enqueueCheck(context.applicationContext)
    }
}

/**
 * 洗衣完成核对 Worker。**类名不要改**：WorkManager 把类名存进自己的库，
 * 老版本排下的一次性任务在应用升级后仍是这个名字（同 `ScoreAlertCheckWorker` 纪律）。
 */
class UjingDoneCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        UjingDoneReminder.check(applicationContext)
        return Result.success()
    }
}
