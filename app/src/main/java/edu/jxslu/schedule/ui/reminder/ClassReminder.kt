package edu.jxslu.schedule.ui.reminder

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.MainActivity
import edu.jxslu.schedule.R
import edu.jxslu.schedule.SubpageActivity
import edu.jxslu.schedule.SubpageScreen
import edu.jxslu.schedule.data.repo.ScheduleRepository
import edu.jxslu.schedule.domain.ReminderDefaults
import edu.jxslu.schedule.domain.dueReminderPlan
import edu.jxslu.schedule.domain.homeworkReminderPlan
import edu.jxslu.schedule.domain.metaLine
import edu.jxslu.schedule.domain.nextHomeworkReminder
import edu.jxslu.schedule.domain.HomeworkReminderPlan
import edu.jxslu.schedule.domain.homeworkReminderGroup
import edu.jxslu.schedule.domain.nextReminderPlan
import kotlinx.coroutines.flow.first
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * 提醒调度（DESIGN §3.7 上课提醒 + §3.11 作业截止提醒，两者共用一个闹钟）。
 *
 * 与桌面小组件（`ui/widget/TodayWidgetRefresh`）同构的三层兜底：
 * 边界闹钟（上课「上课时刻 − 提前量」/ 作业「提醒点 20:00」，取两者更早的）为主，
 * WorkManager 15 分钟周期核对补发/补排，冷启动 / 数据变化 / 开机广播立即重排。
 * 同样用 `setAndAllowWhileIdle` 而不是精确闹钟：推迟几分钟可接受，
 * 不申请 `SCHEDULE_EXACT_ALARM` 敏感权限。
 */
object ClassReminder {

    private const val TAG = "ClassReminder"
    private const val PERIODIC_WORK = "class_reminder_periodic"
    private const val ONE_SHOT_WORK = "class_reminder_check"
    private const val ALARM_REQUEST_CODE = 4002

    /**
     * 重排下一个提醒：上课与作业两个来源取**更早**的触发时刻，排同一个闹钟。
     * 某一类开关关掉就忽略那一类；两者都没有（提醒关、课表空、不在学期内、没有待提醒作业）
     * 时撤销已排闹钟——「关掉开关后通知还在响」比不响严重得多。
     */
    suspend fun scheduleNext(context: Context) {
        runCatching {
            val repo = Graph.repository(context)
            val manager = context.getSystemService(AlarmManager::class.java)
                ?: return
            val pending = alarmPendingIntent(context)
            val now = LocalDateTime.now()
            val classTriggerAt = if (repo.reminderEnabled.first()) {
                nextReminderPlan(
                    semester = repo.semester.first(),
                    slots = repo.timeSlots.first(),
                    courses = repo.courses.first(),
                    now = now,
                    leadMinutes = repo.reminderLeadMinutes.first(),
                )?.triggerAt
            } else null
            // 作业开关关 → 连库都不读（未完成列表要查 Room）；开着才求下一个作业提醒点
            val homeworkTriggerAt = if (repo.homeworkReminderEnabled.first()) {
                nextHomeworkReminder(
                    items = Graph.homeworkRepository(context).observePending().first(),
                    now = now,
                )?.triggerAt
            } else null
            val triggerAt = listOfNotNull(classTriggerAt, homeworkTriggerAt).minOrNull()
            if (triggerAt == null) {
                manager.cancel(pending)
                return
            }
            val triggerAtMillis = triggerAt
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
        }.onFailure { Log.w(TAG, "schedule reminder failed", it) }
    }

    /** 一次性核对（闹钟触发 / 开机 / 设置变更落点）：BroadcastReceiver 里不能做长任务。 */
    fun enqueueCheck(context: Context) {
        val request = OneTimeWorkRequestBuilder<ReminderCheckWorker>()
            .setConstraints(Constraints.NONE)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(ONE_SHOT_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    /** 15 分钟周期核对兜底：闹钟被 ROM 推迟/丢失时还能在窗口内补发。幂等（KEEP）。 */
    fun ensurePeriodicWork(context: Context) {
        val request = PeriodicWorkRequestBuilder<ReminderCheckWorker>(15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private fun alarmPendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        ALARM_REQUEST_CODE,
        Intent(context, ReminderAlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/**
 * 通知的构建与去重落盘。同一节课只发一次：DataStore 记「已发键」，
 * 闹钟与周期核对共用；窗口已过（迟到型）不发，避免「已经上课了还提醒 10 分钟后上课」。
 *
 * 2026-09-21 起（DESIGN §3.11）这里也发作业截止提醒：通道/通知 id/tag/去重键
 * 都与上课提醒各自独立（作业是多条、两个提醒点，共用一套键会互相顶掉），
 * 但落点相同——都是核对 Worker 里先发后重排。
 */
internal object ReminderNotifications {

    private const val CHANNEL_ID = "class_reminder"
    private const val NOTIFICATION_TAG = "class_reminder"
    /** 固定 id：新提醒覆盖上一条，不堆叠。 */
    private const val NOTIFICATION_ID = 1001

    /** 作业截止提醒通道（DESIGN §3.11）。与上课提醒同档 importance，用户可在系统设置降级。 */
    private const val HOMEWORK_CHANNEL_ID = "homework_deadline"

    /** 单条作业提醒（只提醒到一条作业时）：点击直达该作业（DESIGN §3.11）。 */
    private const val HOMEWORK_NOTIFICATION_TAG = "homework_reminder"

    /** 固定 id 1002：与上课提醒（1001）不同，两者不得互相覆盖。 */
    private const val HOMEWORK_NOTIFICATION_ID = 1002

    /** 多条同时到点时的汇总提醒（2026-09-21，优化：不再一条一条发）点击进作业中心。 */
    private const val HOMEWORK_SUMMARY_TAG = "homework_reminder_summary"
    private const val HOMEWORK_SUMMARY_ID = 1003

    /**
     * 作业通知点击落点的 requestCode。**不能沿用 0**：PendingIntent 的身份是
     * 「requestCode + Intent.filterEquals」（不含 extras），而 `JwDetectNotifier`
     * 也用 SubpageActivity + requestCode 0 建 PendingIntent——两者会被系统视为同一个，
     * FLAG_UPDATE_CURRENT 会把彼此的 EXTRA_SCREEN 互相改掉（点了调课通知跳作业中心）。
     * 取与通知 id 同值便于排查。
     */
    private const val HOMEWORK_CONTENT_REQUEST_CODE = HOMEWORK_NOTIFICATION_ID

    /** 汇总通知的 requestCode：与单条（1002）不同，否则两条通知的落点会互相改写。 */
    private const val HOMEWORK_SUMMARY_REQUEST_CODE = HOMEWORK_SUMMARY_ID

    /** 汇总通知正文最多列几条标题（超出用「等 N 项」收口，别把通知撑成一屏）。 */
    private const val SUMMARY_TITLE_LIMIT = 3

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "上课提醒",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = "上课前按设定的提前量发出通知" }
        manager.createNotificationChannel(channel)
    }

    private fun ensureHomeworkChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(HOMEWORK_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            HOMEWORK_CHANNEL_ID,
            "作业截止提醒",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = "截止前一天 20:00 与截止当天 20:00 提醒未完成的作业" }
        manager.createNotificationChannel(channel)
    }

    /** 核对入口：上课先、作业后，两者互不阻塞（各自开关关掉时是空跑）。 */
    suspend fun postDueReminderIfAny(context: Context) {
        val repo = Graph.repository(context)
        postClassReminderIfAny(context, repo)
        postHomeworkReminderIfAny(context, repo)
    }

    private suspend fun postClassReminderIfAny(context: Context, repo: ScheduleRepository) {
        if (!repo.reminderEnabled.first()) return
        // 通知被系统/用户整体关闭时静默跳过：写不出去也不该在核对里报错
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val slots = repo.timeSlots.first()
        val due = dueReminderPlan(
            semester = repo.semester.first(),
            slots = slots,
            courses = repo.courses.first(),
            now = LocalDateTime.now(),
            leadMinutes = repo.reminderLeadMinutes.first(),
        ) ?: return
        val key = due.dedupKey
        if (repo.reminderLastKey() == key) return

        ensureChannel(context)
        val lead = repo.reminderLeadMinutes.first()
        val text = metaLine(slots, due.course)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bell)
            .setContentTitle("${ReminderDefaults.leadLabel(lead)}后上课 · ${due.course.name}")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
        // 无 POST_NOTIFICATIONS 权限时系统静默丢弃；SecurityException 等异常也不外漏
        val posted = runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_TAG, NOTIFICATION_ID, notification)
        }.isSuccess
        if (posted) repo.setReminderLastKey(key)
    }

    /**
     * 作业截止提醒核对（DESIGN §3.11）：对每条未完成作业求「当前有效」的提醒点，
     * 取触发时刻最早的一条（同一时刻的多条作业只发一条，不堆叠），
     * 去重键没发过才发；发出后才落键（发失败不落，下一轮还有机会）。
     */
    private suspend fun postHomeworkReminderIfAny(context: Context, repo: ScheduleRepository) {
        if (!repo.homeworkReminderEnabled.first()) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val now = LocalDateTime.now()
        // 汇总口径（DESIGN §3.11）：本次处于有效期内的提醒点；有「今天截止」优先发那一组
        val group = homeworkReminderGroup(Graph.homeworkRepository(context).observePending().first(), now)
        if (group.isEmpty()) return
        val alreadySent = repo.homeworkRemindedKeys()
        val fresh = group.filter { it.dedupKey !in alreadySent }
        if (fresh.isEmpty()) return

        ensureHomeworkChannel(context)
        val posted = if (fresh.size == 1) {
            postSingleHomework(context, fresh.single())
        } else {
            postHomeworkSummary(context, fresh)
        }
        // 发出后才落键（发失败不落，下一轮还有机会）；整组一起记，免得下轮把同组再发一遍
        if (posted) fresh.forEach { repo.addHomeworkRemindedKey(it.dedupKey) }
    }

    /** 单条作业提醒：标题「明天截止 · 课名」，正文作业标题，点击直达该作业。 */
    private fun postSingleHomework(context: Context, plan: HomeworkReminderPlan): Boolean {
        val homework = plan.homework
        val text = homework.title.ifBlank { "未命名作业" }
        val notification = NotificationCompat.Builder(context, HOMEWORK_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bell)
            .setContentTitle("${plan.kind.label} · ${homework.courseName}")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    HOMEWORK_CONTENT_REQUEST_CODE,
                    // 带 courseName/itemId：点通知直达这条作业（DESIGN §3.11）
                    SubpageActivity.intent(
                        context,
                        SubpageScreen.HOMEWORK_DETAIL,
                        courseName = homework.courseName,
                        itemId = homework.id,
                    ),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
        return runCatching {
            NotificationManagerCompat.from(context)
                .notify(HOMEWORK_NOTIFICATION_TAG, HOMEWORK_NOTIFICATION_ID, notification)
        }.isSuccess
    }

    /**
     * 多条同时到点：发一条汇总（「3 项作业明天截止」），正文列出前几条标题，
     * 点击进作业中心逐条处理——比连发 N 条通知更少打扰、信息量也更大。
     */
    private fun postHomeworkSummary(context: Context, plans: List<HomeworkReminderPlan>): Boolean {
        val kindLabel = plans.first().kind.label
        val titles = plans.map { it.homework.title.ifBlank { "未命名作业" } }
        val body = buildString {
            append(titles.take(SUMMARY_TITLE_LIMIT).joinToString(" · "))
            if (titles.size > SUMMARY_TITLE_LIMIT) append(" 等 ${titles.size} 项")
        }
        val notification = NotificationCompat.Builder(context, HOMEWORK_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bell)
            .setContentTitle("${plans.size} 项作业$kindLabel")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    HOMEWORK_SUMMARY_REQUEST_CODE,
                    SubpageActivity.intent(context, SubpageScreen.HOMEWORK_TODO),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
        return runCatching {
            NotificationManagerCompat.from(context)
                .notify(HOMEWORK_SUMMARY_TAG, HOMEWORK_SUMMARY_ID, notification)
        }.isSuccess
    }
}

/** 边界闹钟落点：只把核对排进 WorkManager，立即返回。 */
class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ClassReminder.enqueueCheck(context)
    }
}

/** 重启后闹钟全部丢失，开机补排一次。 */
class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            ClassReminder.enqueueCheck(context)
        }
    }
}

/** 真正的核对：先看窗口内有没有该发的（去重后发），再重排下一个闹钟。 */
class ReminderCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        runCatching {
            ReminderNotifications.postDueReminderIfAny(applicationContext)
            ClassReminder.scheduleNext(applicationContext)
        }
        return Result.success()
    }
}
