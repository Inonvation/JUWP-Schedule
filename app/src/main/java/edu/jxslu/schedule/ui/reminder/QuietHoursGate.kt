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
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.MainActivity
import edu.jxslu.schedule.R
import edu.jxslu.schedule.data.repo.PendingLaunch
import edu.jxslu.schedule.data.repo.PendingNotification
import edu.jxslu.schedule.data.repo.QuietHoursQueue
import edu.jxslu.schedule.domain.QuietWindows
import edu.jxslu.schedule.SubpageRequest
import edu.jxslu.schedule.SubpageScreen
import edu.jxslu.schedule.subpageLaunchIntent
import edu.jxslu.schedule.EXTRA_ROUTE
import edu.jxslu.schedule.ROUTE_ME
import java.time.LocalDateTime
import java.time.LocalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 免扰时段的闸门与补发（DESIGN §3.7，quiet hours）。
 *
 * **拦截**：通知链各发送点在构建通知前调 [intercept]——当前处于免扰时段时，
 * 把通知的结构化描述入队（[QuietHoursQueue]）并排一枚「时段结束」补发闹钟，
 * 返回 true；发送点据此**跳过即时发送**并照常落去重键（拦截 = 该通知已处理）。
 * 不在免扰时段返回 false，原路径照走，零开销。
 *
 * **补发**三条路：
 * 1. 补发闹钟（[QuietHoursFlushReceiver]，setAlarmClock 精确触发，goAsync 直跑不绕
 *    WorkManager——同 `EbikeFreeRideAlarmReceiver`，保精度）；
 * 2. 冷启动 [flushIfDue]（兜住关机/重启杀掉的闹钟与「免扰期间手机关机」）；
 * 3. [intercept] 自身（新通知被拦时顺带看一眼队列里有没有已到期的）。
 *
 * 常驻/静默通知（如骑行倒计时）与其所属前台服务**不接本闸**。
 * 收件箱（入箱）在 Sync 层本就独立于通知，不受影响——「打开 App 一定能看到」的保证不变。
 */
object QuietHoursGate {

    private const val TAG = "QuietHours"

    /**
     * 补发闹钟 requestCode。**不能撞**：与上课（4002）、骑行（4003）、选课（4004）、
     * U净（4005）、小组件日界（4001）错开——PendingIntent 的身份是
     * 「requestCode + Intent.filterEquals」，撞了互相顶掉（历史坑，§3.9）。
     */
    private const val ALARM_REQUEST_CODE = 4006

    private const val FLUSH_CHANNEL_ID = "quiet_hours_flush"

    // ------------------------------------------------------------------
    // 拦截
    // ------------------------------------------------------------------

    /**
     * 免扰判定 + 入队。在免扰时段内返回 true（调用方跳过发送）；否则 false 照常发送。
     *
     * @param entry 结构化通知描述；[PendingNotification.eventAt] 为时刻型事件时刻
     *   （上课/截止/轮次事件等），事实型传 null。
     */
    suspend fun intercept(context: Context, entry: PendingNotification): Boolean {
        val repo = Graph.repository(context)
        if (!repo.quietHoursEnabled.first()) return false
        val windows = repo.quietHoursWindows.first()
        val now = java.time.LocalTime.now()
        val minute = now.hour * 60 + now.minute
        if (!QuietWindows.isQuiet(windows, minute)) return false

        Graph.quietHoursQueue(context).enqueue(entry)
        scheduleFlushAlarm(context, windows, minute)
        Log.i(TAG, "quiet hours: held \"${entry.title}\" until window end")
        return true
    }

    /**
     * 排补发闹钟：落点 = 当前活跃窗口并集的结束时刻（重叠窗口取最晚 end）。
     * `FLAG_UPDATE_CURRENT`：后入队若落点更晚则覆盖——正确语义（并集只会变长）。
     * 闹钟只是补发的第一路，被 ROM 推迟/关机丢失时冷启动兜底，不追求必达。
     */
    private fun scheduleFlushAlarm(context: Context, windows: List<edu.jxslu.schedule.domain.QuietWindow>, minute: Int) {
        val endMinute = QuietWindows.activeEnd(windows, minute) ?: return
        val zone = java.time.ZoneId.systemDefault()
        val now = LocalDateTime.now(zone).withNano(0)
        val trigger = LocalDateTime.of(java.time.LocalDate.now(zone), LocalTime.of(0, 0))
            .plusMinutes(endMinute.toLong())
            // endMinute 锚定的是「当天墙上时钟」；若算出的时刻已经过去（窗口跨零点、
            // now 已越过 00:00），+1 天修正到未来
            .let { if (it.isBefore(now)) it.plusDays(1) else it }
        runCatching {
            val manager = context.getSystemService(AlarmManager::class.java) ?: return
            val clockPending = PendingIntent.getActivity(
                context,
                0,
                Intent(AlarmClock.ACTION_SHOW_ALARMS),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            manager.setAlarmClock(
                AlarmManager.AlarmClockInfo(trigger.atZone(zone).toInstant().toEpochMilli(), clockPending),
                flushPendingIntent(context),
            )
            Log.i(TAG, "flush alarm at $trigger (now $now)")
        }.onFailure { Log.w(TAG, "schedule flush alarm failed", it) }
    }


    /** 补发闹钟落点（幂等 PendingIntent）。 */
    private fun flushPendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        ALARM_REQUEST_CODE,
        Intent(context, QuietHoursFlushReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    // ------------------------------------------------------------------
    // 补发
    // ------------------------------------------------------------------

    /**
     * 补发核对（幂等，可任意重复调用）：仍在免扰时段则重排闹钟直接返回；
     * 否则按 [QuietHoursQueue.flushPlan] 发出未过期的、写回剩余队列。
     */
    suspend fun flush(context: Context) {
        val repo = Graph.repository(context)
        val windows = repo.quietHoursWindows.first()
        val enabled = repo.quietHoursEnabled.first()
        val nowTime = LocalTime.now()
        val minute = nowTime.hour * 60 + nowTime.minute
        if (enabled && QuietWindows.isQuiet(windows, minute)) {
            // 被提前叫醒（闹钟早了/窗口刚被改长）：重新排到真正的结束点
            if (QuietWindows.activeEnd(windows, minute) != null) {
                scheduleFlushAlarm(context, windows, minute)
            }
            return
        }
        // 总开关被关（窗口配置保留）或已出时段：把积压的全部放行
        val queue = Graph.quietHoursQueue(context)
        val entries = queue.state.value
        if (entries.isEmpty()) return
        val now = System.currentTimeMillis()
        val (toSend, expired) = QuietHoursQueue.flushPlan(entries, now)
        val strongVibration = repo.strongReminderVibration.first()
        for (e in toSend.asReversed()) {
            post(context, e, strongVibration)
        }
        // 锁内原子移除已发条目：flush 期间可能又有新通知被拦入队（补发闹钟到点常与
        // 周期核对重叠），整表清空会把它们静默吞掉；按幂等键移除则新条目自然留存。
        // 刚发出去的条目若恰好又被重拦，重拦发生（互斥）在移除前后都各自完整，
        // 不产生重复通知——重拦在前：键已不在，照发；重拦在后：入队的新值留下，等下一轮。
        val sentKeys = toSend.map { it.key() }.toSet()
        val removed = queue.removeAllKeys(sentKeys)
        Log.i(TAG, "flushed ${toSend.size} held notification(s), expired $expired, removed $removed")
    }

    /** 冷启动挂点（JuwApplication）：兜住关机/重启丢掉的补发闹钟。吞错不挡启动链。 */
    fun flushIfDue(context: Context, scope: kotlinx.coroutines.CoroutineScope) {
        scope.launch {
            runCatching { flush(context.applicationContext) }
                .onFailure { Log.w(TAG, "cold-start flush failed", it) }
        }
    }

    /**
     * 重建并发一条被拦的通知。失败只记日志——补发是尽力而为，不该把整轮 flush 搞崩。
     *
     * [strongVibrationNow] = **补发时刻**的强提醒开关值：震动按当下重读（拦截时的
     * [PendingNotification.vibrate] 只说明「这条提醒属于会震的三类」），用户可能在
     * 免扰期间改了开关——补发当下开着就震，与即时发送同一口径。
     */
    private fun post(context: Context, entry: PendingNotification, strongVibrationNow: Boolean) {
        runCatching {
            ensureChannel(context, entry.channelId)
            val builder = NotificationCompat.Builder(context, entry.channelId)
                .setSmallIcon(R.drawable.ic_stat_bell)
                .setContentTitle(entry.title)
                // 折叠态与即时发送同口径：单行文本原样，多行文本显首行（BigText 才是全文）
                .setContentText(entry.text.lines().firstOrNull().orEmpty())
                .setStyle(NotificationCompat.BigTextStyle().bigText(entry.text))
                .setAutoCancel(true)
            entry.category?.let { builder.setCategory(it) }
            entry.launch?.let { builder.setContentIntent(contentIntent(context, it)) }
            val nm = NotificationManagerCompat.from(context)
            if (entry.tag != null) {
                nm.notify(entry.tag, entry.notificationId, builder.build())
            } else {
                nm.notify(entry.notificationId, builder.build())
            }
            if (entry.vibrate && strongVibrationNow) {
                ReminderVibration.vibrate(context)
            }
        }.onFailure { Log.w(TAG, "flush post failed: ${entry.title}", it) }
    }

    /** 按 [PendingLaunch] 重建落点（三种形态与原发送点逐字对应）。 */
    private fun contentIntent(context: Context, launch: PendingLaunch): PendingIntent {
        val intent = when (launch.kind) {
            // route 为 null 的语义 = 落今日页（原链路：生活 Tab 关闭时不给 extra，
            // 免得落在一个被用户隐藏的 Tab 上）——不能兜成 ROUTE_LIFE
            PendingLaunch.Kind.MAIN_ROUTE -> Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                launch.route?.let { putExtra(EXTRA_ROUTE, it) }
            }
            PendingLaunch.Kind.MAIN_PLAIN -> Intent(context, MainActivity::class.java)
            else -> subpageLaunchIntent(
                context,
                SubpageRequest(
                    screen = SubpageScreen.entries.firstOrNull { it.name == launch.screen }
                        ?: SubpageScreen.values().first(),
                    focusItemId = launch.focusItemId,
                    courseName = launch.courseName,
                    itemId = launch.itemId?.toLongOrNull() ?: 0L,
                ),
            ).let { if (launch.clearTask) it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) else it }
        }
        return PendingIntent.getActivity(
            context,
            launch.requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * 补发前的渠道兜底：渠道可能被用户在系统设置里删掉（删除后 getNotificationChannel
     * 返回 null，notify 会静默失败）。按原 channelId 重建一个默认重要级的同名渠道——
     * 重要性以用户系统设置为准（渠道一旦建出，代码里的 importance 只在首次生效）。
     */
    private fun ensureChannel(context: Context, channelId: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(channelId) != null) return
        manager.createNotificationChannel(
            NotificationChannel(channelId, "提醒", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }
}

/** 补发闹钟落点：goAsync + IO 直跑 flush（纯本地 IO 无网络，不绕 WorkManager 保精度）。 */
class QuietHoursFlushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                QuietHoursGate.flush(context.applicationContext)
            } catch (t: Throwable) {
                Log.w("QuietHours", "flush receiver failed", t)
            } finally {
                pending.finish()
            }
        }
    }
}
