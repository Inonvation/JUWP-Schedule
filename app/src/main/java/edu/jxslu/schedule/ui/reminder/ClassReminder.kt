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
import edu.jxslu.schedule.SubpageRequest
import edu.jxslu.schedule.SubpageScreen
import edu.jxslu.schedule.subpageLaunchIntent
import edu.jxslu.schedule.data.repo.ScheduleRepository
import edu.jxslu.schedule.data.prefs.ReminderKeyKind
import edu.jxslu.schedule.data.repo.PendingLaunch
import edu.jxslu.schedule.data.repo.PendingNotification
import edu.jxslu.schedule.domain.ReminderDefaults
import edu.jxslu.schedule.domain.Course
import edu.jxslu.schedule.domain.TimeSlot
import edu.jxslu.schedule.domain.dueReminderPlan
import edu.jxslu.schedule.domain.homeworkReminderPlan
import edu.jxslu.schedule.domain.dueClassStartPlan
import edu.jxslu.schedule.domain.metaLine
import edu.jxslu.schedule.domain.nextHomeworkReminder
import edu.jxslu.schedule.domain.HomeworkReminderPlan
import edu.jxslu.schedule.domain.homeworkReminderGroup
import edu.jxslu.schedule.domain.homeworkDisplayTitle
import edu.jxslu.schedule.domain.nextClassStartPlan
import edu.jxslu.schedule.domain.nextReminderPlan
import edu.jxslu.schedule.domain.remarkPreview
import kotlinx.coroutines.flow.first
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * 提醒调度（DESIGN §3.7 上课提醒 + §3.11 作业截止提醒，两者共用一个闹钟）。
 *
 * 与桌面小组件（`ui/widget/TodayWidgetRefresh`）同构的三层兜底：
 * 边界闹钟（上课「提前量点 / 上课时刻」取更早的 / 作业「提醒点 20:00」，三者再取更早）为主，
 * WorkManager 15 分钟周期核对补发/补排，冷启动 / 数据变化 / 开机广播立即重排。
 * 闹钟用 `setAlarmClock`（2026-09-23 自 `setAndAllowWhileIdle` 改）：
 * 真机上提醒迟到几分钟是反复出现的问题——非精确闹钟可被 ROM 省电策略推迟。
 * `setAlarmClock` 是系统级精确闹钟（系统时钟应用同款），到点即触发、不受推迟，
 * 且不需要 SCHEDULE_EXACT_ALARM 等任何特殊权限；代价仅是触发时状态栏短暂
 * 显示闹钟图标，对「上课提醒」语义贴切。小组件刷新保持非精确闹钟不动（见 §3.6）。
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
            // 渠道迁移（删旧建 v2）顺手挂在这里：冷启动 / 设置变更 / 核对后都会走到，
            // 升级后不用等下一次提醒真的触发，系统设置里就能看到带振动的 v2 渠道
            ReminderNotifications.ensureChannels(context)
            val repo = Graph.repository(context)
            val manager = context.getSystemService(AlarmManager::class.java)
                ?: return
            val pending = alarmPendingIntent(context)
            val now = LocalDateTime.now()
            val classTriggerAt = if (repo.reminderEnabled.first()) {
                val semester = repo.semester.first()
                val slots = repo.timeSlots.first()
                val courses = repo.courses.first()
                // 日期覆盖（调休 / 停课，§4.40）：放假那天不排上课提醒，
                // 补课那天按借来的课排——与今日页同一口径
                val overrides = repo.overridesFor()
                // 上课提醒现在有两个触发点（DESIGN §3.7）：提前量与上课时刻，
                // 都开着时取更早的排闹钟；核对 Worker 会先补发窗口内的、再重排
                listOfNotNull(
                    nextReminderPlan(
                        semester = semester,
                        slots = slots,
                        courses = courses,
                        now = now,
                        leadMinutes = repo.reminderLeadMinutes.first(),
                        overrides = overrides,
                    )?.triggerAt,
                    nextClassStartPlan(
                        semester = semester,
                        slots = slots,
                        courses = courses,
                        now = now,
                        overrides = overrides,
                    )?.startAt,
                ).minOrNull()
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
            // 系统级精确闹钟：到点即触发，不受 Doze/省电推迟，无需特殊权限。
            // showIntent 交给系统时钟的闹钟页：用户点状态栏那枚闹钟图标时落到「下一个闹钟列表」，
            // 而不是本 App 的某个页面——那枚图标只表示"有个闹钟排着"，不是提醒本身
            val clockPending = PendingIntent.getActivity(
                context,
                0,
                Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            manager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAtMillis, clockPending), pending)
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

    // 渠道 id 用 _v2：旧 id 的渠道在用户设备上已按「无振动」建成，而渠道建出来后
    // vibration 就改不动（与免费时长提醒 _v2 同坑，见 EbikeFreeRide.NotificationIds 的 KDoc）。
    // 旧 id 只剩清理职责，见 ensureChannels。
    private const val CHANNEL_ID = "class_reminder_v2"
    private const val LEGACY_CHANNEL_ID = "class_reminder"
    private const val NOTIFICATION_TAG = "class_reminder"
    /** 固定 id：新提醒覆盖上一条，不堆叠。 */
    private const val NOTIFICATION_ID = 1001

    /** 「上课」通知（上课时刻触发，2026-09-22 新增）：固定 id 1004，与提前量（1001）、
     *  作业（1002/1003）互不覆盖。 */
    private const val CLASS_START_NOTIFICATION_ID = 1004

    /** 作业截止提醒通道（DESIGN §3.11）。与上课提醒同档 importance，用户可在系统设置降级。 */
    private const val HOMEWORK_CHANNEL_ID = "homework_deadline_v2"

    /** 作业渠道的旧 id（无振动版，见 [CHANNEL_ID] 的注释），只剩清理职责。 */
    private const val LEGACY_HOMEWORK_CHANNEL_ID = "homework_deadline"

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
        ensureChannels(context, homework = false)
    }

    /** 两个渠道一起确保。冷启动重排（[ClassReminder.scheduleNext]）也调它：升级后旧渠道
     *  （无振动版）能尽快被 v2 取代，不用等下一次提醒真的触发。 */
    fun ensureChannels(context: Context) {
        ensureChannels(context, homework = true)
    }

    private fun ensureChannels(context: Context, homework: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        // 旧渠道（2026-09-19 建的无振动版）清掉免得设置页留死通道；
        // delete 与 create 用的是不同 id，不会踩「delete 异步、紧接 create 被当成更新」的坑
        manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "上课提醒",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "上课前按设定的提前量发出通知"
                enableVibration(true)
                vibrationPattern = ReminderVibration.PATTERN
            }
            manager.createNotificationChannel(channel)
        }
        if (!homework) return
        manager.deleteNotificationChannel(LEGACY_HOMEWORK_CHANNEL_ID)
        if (manager.getNotificationChannel(HOMEWORK_CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                HOMEWORK_CHANNEL_ID,
                "作业截止提醒",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "截止前一天 20:00 与截止当天 20:00 提醒未完成的作业"
                enableVibration(true)
                vibrationPattern = ReminderVibration.PATTERN
            }
            manager.createNotificationChannel(channel)
        }
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
        val now = LocalDateTime.now()
        val semester = repo.semester.first()
        val courses = repo.courses.first()
        val lead = repo.reminderLeadMinutes.first()
        // 日期覆盖（调休 / 停课，§4.40）与排闹钟同口径（scheduleNext 已传）：
        // 这里是「要不要发」的最后一道闸，漏了它，AI 调课/校历调休把今天的课
        // 停掉后，周期核对仍按裸课表在原上课时间点发通知（2026-10-07 实报）。
        val overrides = repo.overridesFor()

        // 两个通知点各自核对、各自去重（DESIGN §3.7）：同一轮可能只命中一个
        // （两触发点相差一个提前量，通常分处两轮），也可能同时命中（闹钟被推迟跨过两点）。
        // 先算「上课开始」（更晚的点）：它的窗口不依赖提前量，先判可避免 lead=0 时
        // 两个窗口重叠导致的重复计算。
        val startDue = dueClassStartPlan(semester, slots, courses, now, overrides)
        val startKey = startDue?.dedupKey
        // 通知强提醒（2026-10-06，通用设置）：默认关，开了才在发出后直接震动
        val strongVibration = repo.strongReminderVibration.first()
        // 免扰拦截描述要存 epoch 毫秒（eventAt），LocalDateTime → Instant 需要时区
        val zone = ZoneId.systemDefault()
        if (startKey != null && repo.reminderLastKey(ReminderKeyKind.Start) != startKey) {
            // 免扰时段（DESIGN §3.7）：拦下入队待补发，拦截 = 已处理（照常落去重键），
            // 否则 15 分钟周期核对会在免扰期内反复入队
            val held = QuietHoursGate.intercept(
                context,
                PendingNotification(
                    tag = NOTIFICATION_TAG,
                    notificationId = CLASS_START_NOTIFICATION_ID,
                    channelId = CHANNEL_ID,
                    category = NotificationCompat.CATEGORY_EVENT,
                    title = "上课 · ${startDue.course.name}",
                    text = notificationLine(slots, startDue.course),
                    eventAt = startDue.startAt.atZone(zone).toInstant().toEpochMilli(),
                    enqueuedAt = System.currentTimeMillis(),
                    vibrate = strongVibration,
                    launch = PendingLaunch(kind = PendingLaunch.Kind.MAIN_PLAIN, requestCode = 0),
                ),
            )
            if (held) {
                repo.setReminderLastKey(ReminderKeyKind.Start, startKey)
                return
            }
            val posted = postClassNotification(
                context,
                id = CLASS_START_NOTIFICATION_ID,
                title = "上课 · ${startDue.course.name}",
                text = notificationLine(slots, startDue.course),
                strongVibration = strongVibration,
            )
            if (posted) repo.setReminderLastKey(ReminderKeyKind.Start, startKey)
        }

        val due = dueReminderPlan(semester, slots, courses, now, leadMinutes = lead, overrides = overrides)
        val key = due?.dedupKey
        if (key != null && repo.reminderLastKey(ReminderKeyKind.Before) != key) {
            val held = QuietHoursGate.intercept(
                context,
                PendingNotification(
                    tag = NOTIFICATION_TAG,
                    notificationId = NOTIFICATION_ID,
                    channelId = CHANNEL_ID,
                    category = NotificationCompat.CATEGORY_EVENT,
                    title = "${ReminderDefaults.leadLabel(lead)}后上课 · ${due.course.name}",
                    text = notificationLine(slots, due.course),
                    eventAt = due.startAt.atZone(zone).toInstant().toEpochMilli(),
                    enqueuedAt = System.currentTimeMillis(),
                    vibrate = strongVibration,
                    launch = PendingLaunch(kind = PendingLaunch.Kind.MAIN_PLAIN, requestCode = 0),
                ),
            )
            if (held) {
                repo.setReminderLastKey(ReminderKeyKind.Before, key)
                return
            }
            val posted = postClassNotification(
                context,
                id = NOTIFICATION_ID,
                title = "${ReminderDefaults.leadLabel(lead)}后上课 · ${due.course.name}",
                text = notificationLine(slots, due.course),
                strongVibration = strongVibration,
            )
            if (posted) repo.setReminderLastKey(ReminderKeyKind.Before, key)
        }
    }

    /**
     * 上课类通知正文（2026-10-08）：`metaLine`（时刻 · @地点 · 教师）+ 备注
     * （有备注才换行追加，无备注维持原样）；备注过 [remarkPreview] 截断，
     * 通知 BigText 能放多行但用户手写的备注长短不受控，与卡片共用一个上限。
     * internal：RemarkPreviewTest 直调钉住「通知带备注」契约（private 测不到，
     * 同文件 ReminderNotifications 已有 internal 先例）。
     */
    internal fun notificationLine(slots: List<TimeSlot>, course: Course): String {
        val meta = metaLine(slots, course)
        val remark = remarkPreview(course.remark) ?: return meta
        return "$meta\n$remark"
    }

    /** 上课类通知的共用构建（提前量与上课开始两处共用，标题与 id 不同）。 */
    private fun postClassNotification(
        context: Context,
        id: Int,
        title: String,
        text: String,
        strongVibration: Boolean,
    ): Boolean {
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bell)
            .setContentTitle(title)
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
            NotificationManagerCompat.from(context).notify(NOTIFICATION_TAG, id, notification)
        }.isSuccess
        // 开了「通知强提醒」才在发出后直接震动（2026-10-06 收成通用设置开关，默认关）：
        // HyperOS 的通知策略默认把渠道振动关掉（见 ReminderVibration），直接震动不看那个开关，
        // 但它也穿免打扰——所以只能由用户显式打开，关 = 回到纯渠道震动
        if (posted && strongVibration) ReminderVibration.vibrate(context)
        return posted
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

        ensureChannels(context, homework = true)
        // 口径同上课提醒：开了「通知强提醒」才直接震动（默认关）
        val strongVibration = repo.strongReminderVibration.first()
        // 免扰时段（DESIGN §3.7）：整组拦下入队待补发。汇总与单条的描述在此时就定稿
        // （标题/正文/落点/去重键），补发时按描述重建；拦截 = 已处理，照常落全组去重键
        val zone = ZoneId.systemDefault()
        val held = if (fresh.size == 1) {
            val plan = fresh.single()
            QuietHoursGate.intercept(
                context,
                PendingNotification(
                    tag = HOMEWORK_NOTIFICATION_TAG,
                    notificationId = HOMEWORK_NOTIFICATION_ID,
                    channelId = HOMEWORK_CHANNEL_ID,
                    category = NotificationCompat.CATEGORY_REMINDER,
                    title = "${plan.kind.label} · ${plan.homework.courseName}",
                    text = homeworkDisplayTitle(plan.homework.detail),
                    eventAt = plan.triggerAt.atZone(zone).toInstant().toEpochMilli(),
                    enqueuedAt = System.currentTimeMillis(),
                    vibrate = strongVibration,
                    launch = PendingLaunch(
                        kind = PendingLaunch.Kind.SUBPAGE,
                        requestCode = HOMEWORK_CONTENT_REQUEST_CODE,
                        screen = SubpageScreen.HOMEWORK_DETAIL.name,
                        courseName = plan.homework.courseName,
                        itemId = plan.homework.id.toString(),
                    ),
                ),
            )
        } else {
            val kindLabel = fresh.first().kind.label
            val titles = fresh.map { homeworkDisplayTitle(it.homework.detail) }
            val body = buildString {
                append(titles.take(SUMMARY_TITLE_LIMIT).joinToString(" · "))
                if (titles.size > SUMMARY_TITLE_LIMIT) append(" 等 ${titles.size} 项")
            }
            QuietHoursGate.intercept(
                context,
                PendingNotification(
                    tag = HOMEWORK_SUMMARY_TAG,
                    notificationId = HOMEWORK_SUMMARY_ID,
                    channelId = HOMEWORK_CHANNEL_ID,
                    category = NotificationCompat.CATEGORY_REMINDER,
                    title = "${fresh.size} 项作业$kindLabel",
                    text = body,
                    eventAt = fresh.minOf { it.triggerAt }.atZone(zone).toInstant().toEpochMilli(),
                    enqueuedAt = System.currentTimeMillis(),
                    vibrate = strongVibration,
                    launch = PendingLaunch(
                        kind = PendingLaunch.Kind.SUBPAGE,
                        requestCode = HOMEWORK_SUMMARY_REQUEST_CODE,
                        screen = SubpageScreen.HOMEWORK_TODO.name,
                    ),
                ),
            )
        }
        if (held) {
            fresh.forEach { repo.addHomeworkRemindedKey(it.dedupKey) }
            return
        }
        val posted = if (fresh.size == 1) {
            postSingleHomework(context, fresh.single())
        } else {
            postHomeworkSummary(context, fresh)
        }
        if (posted && strongVibration) ReminderVibration.vibrate(context)
        // 发出后才落键（发失败不落，下一轮还有机会）；整组一起记，免得下轮把同组再发一遍
        if (posted) fresh.forEach { repo.addHomeworkRemindedKey(it.dedupKey) }
    }

    /** 单条作业提醒：标题「明天截止 · 课名」，正文作业标题，点击直达该作业。 */
    private fun postSingleHomework(context: Context, plan: HomeworkReminderPlan): Boolean {
        val homework = plan.homework
        val text = homeworkDisplayTitle(homework.detail)
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
                    // 跳板是 MainActivity 而非 SubpageActivity：进程没起时若让二级页当 task 根，
                    // 返回会直接退出 App，见 subpageLaunchIntent 的 KDoc
                    subpageLaunchIntent(
                        context,
                        SubpageRequest(
                            screen = SubpageScreen.HOMEWORK_DETAIL,
                            courseName = homework.courseName,
                            itemId = homework.id,
                        ),
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
        val titles = plans.map { homeworkDisplayTitle(it.homework.detail) }
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
                    subpageLaunchIntent(context, SubpageRequest(SubpageScreen.HOMEWORK_TODO)),
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

/**
 * 重启与**应用更新**后闹钟全部丢失，各补排一次（上课/作业与选课提醒各自重排）。
 * `MY_PACKAGE_REPLACED`（2026-10-02，体检第三批）：覆盖安装不走 BOOT_COMPLETED，
 * 而精确闹钟不跨安装存活——不加这条，用户每次升级后所有提醒静默失效到下一次冷启动。
 */
class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> {
                ClassReminder.enqueueCheck(context)
                SelectionAlertReminder.enqueueCheck(context)
            }
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
