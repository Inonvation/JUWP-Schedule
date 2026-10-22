package edu.jxslu.schedule.ui.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.R
import edu.jxslu.schedule.SubpageScreen
import edu.jxslu.schedule.SubpageRequest
import edu.jxslu.schedule.data.repo.ExamSync
import edu.jxslu.schedule.data.repo.PendingLaunch
import edu.jxslu.schedule.data.repo.PendingNotification
import edu.jxslu.schedule.data.repo.ScoreSync
import edu.jxslu.schedule.domain.ExamChangeDetector
import edu.jxslu.schedule.domain.ScheduleAlertDefaults
import edu.jxslu.schedule.domain.ScoreAlertDefaults
import edu.jxslu.schedule.domain.WenquRules
import edu.jxslu.schedule.subpageLaunchIntent
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import edu.jxslu.schedule.data.prefs.wenquActivityAlertEnabled
import edu.jxslu.schedule.data.prefs.wenquActivityIntervalHours
import edu.jxslu.schedule.data.prefs.wenquGrabPinnedId

/**
 * 成绩/考试变动提醒（DESIGN §4.33）：新出分、复查改分、考试发布/调整时发一条通知。
 *
 * 编排与 [BalanceAlertReminder] 同一套模式（状态驱动，没有可预排的时刻）：
 * WorkManager 周期核对（间隔 = 设置里的档位）+ 冷启动补查 + 设置变更后立即评估。
 * 与余额提醒的差别是数据来源——这里驱动 `ScoreSync` / `ExamSync` 打教务接口，
 * 闸门（成功才落时刻、失败不写、不重试）在各自的 sync 类里，本类只管「拿到结果后
 * 要不要发通知、点通知去哪」。
 *
 * 三条口径（改之前先读）：
 *
 * 1. **首跑/换学期不通知**：第一次抓到的全量是基线不是「变动」；考试基线为空或学期
 *    切换时 `ExamSync` 返回空变更，这里只按变更列表发，天然满足。
 * 2. **没变更就不发**：检查成功但成绩/考试没动静是常态，静默；有变更也各发一条
 *    （成绩一条、考试一条），同一次检查内不合并——两者的点击落点不同。
 * 3. **考试只提醒不写库**：点通知落**考试页**（`SubpageScreen.EXAMS`，2026-09-30 由
 *    教务导入页改过来）——详情、提醒开关、导入入口在同一页，看完再决定导不导入；
 *    写库仍要用户在导入页点「导入考试安排」并过确认弹窗，考试进课表只有手动一条路
 *    （DESIGN §4.33 红线）。
 *
 * **工作名与 Worker 类名一经发布不要改**：`KEEP` 策略下老任务按类名实例化，
 * 改名会让已排的周期任务实例化失败且不会重排，兜底永久消失（同 `BalanceAlertCheckWorker`）。
 */
object ScoreAlertReminder {

    private const val TAG = "ScoreAlert"

    /** 周期核对（间隔 = 设置档位，默认 6 小时）。 */
    private const val PERIODIC_WORK = "score_alert_periodic"

    /** 即时评估（冷启动 / 开关与间隔变更）。 */
    private const val ONE_SHOT_WORK = "score_alert_check"

    /** [enqueueCheck] 的入参键：非空 = 只跑那一路（目前只有问渠用，见 [enqueueWenquCheck]）。 */
    internal const val KEY_ONLY_CHAIN = "only_chain"

    /** 「只跑问渠那一路」的取值。 */
    private const val CHAIN_WENQU = "wenqu"

    private const val CHANNEL_ID = "score_alert"

    /** 成绩通知：固定 id/tag/requestCode，与考试那条及既有通知互不覆盖。 */
    private const val SCORE_NOTIFICATION_ID = 1008
    private const val SCORE_NOTIFICATION_TAG = "score_alert"

    /** 考试通知：id 必须与成绩不同，否则两条通知会互相顶掉。 */
    private const val EXAM_NOTIFICATION_ID = 1009
    private const val EXAM_NOTIFICATION_TAG = "exam_alert"

    /**
     * 通知点击落点的 requestCode（[3008]/[3009]）。**不能复用通知 id**：PendingIntent
     * 的身份是「requestCode + Intent.filterEquals」，两条落点 intent 一个指向成绩页、
     * 一个指向教务导入页，只差 extra——requestCode 撞了会互相改写落点
     * （同 `BalanceAlertReminder` 3005/3006 的纪律）。
     */
    private const val SCORE_REQUEST_CODE = 3008
    private const val EXAM_REQUEST_CODE = 3009

    /** 成绩通知最多列出的门数，多出的折叠成「等 N 门」。 */
    private const val MAX_LINES = 4

    /**
     * 周期核对：**五个开关都关时撤销**任务而不是留着空跑（同 [BalanceAlertReminder]）。
     * 幂等策略：**间隔没变时 KEEP**，免得每次冷启动都把周期相位推后；间隔变了用
     * REPLACE 换周期（2.7.1 没有 UPDATE 策略；REPLACE 取消旧任务重新排，只在用户
     * 改间隔那一刻发生）。已排周期记在 DataStore（`alert_periodic_interval`），
     * 进程重启后也能比对；0 = 当前没有在排的周期任务。
     *
     * 间隔（2026-10-04 起各链独立设置，2026-10-08 加问渠那路）取**五条链的最小档**：
     * 周期任务只负责「按最勤的那条叫醒」，各路自己的 `shouldAttemptAt` 用各自的间隔与
     * 检查时刻决定真抓还是跳过——把成绩设成 1 小时、调课设成 24 小时，任务每小时醒来一次，
     * 调课那路自己会挡住 23 次。
     *
     * 选课提醒（DESIGN §4.35）搭同一趟车：核对时它自己顺带刷新轮次快照。
     */
    suspend fun ensurePeriodicWork(context: Context) {
        runCatching {
            val prefs = Graph.displayPrefs(context)
            val workManager = WorkManager.getInstance(context)
            // 盯了指定活动（问渠蹲点）也要靠这趟周期任务：没有它，「名额空出」永远看不到
            val pinned = prefs.wenquGrabPinnedId.first() > 0L
            val scoreOn = prefs.scoreAlertEnabled.first()
            val examOn = prefs.examAlertEnabled.first()
            val selectionOn = prefs.selectionAlertEnabled.first()
            val scheduleOn = prefs.scheduleAlertEnabled.first()
            val wenquOn = prefs.wenquActivityAlertEnabled.first()
            if (!scoreOn && !examOn && !selectionOn && !scheduleOn && !wenquOn && !pinned) {
                workManager.cancelUniqueWork(PERIODIC_WORK)
                prefs.setAlertPeriodicInterval(0)
                return
            }
            // 只取**已开启**链的最小档：关着的链不该把周期带快（此前全量参与，
            // 只开 24h 调课提醒也会被关着的 6h 成绩/考试档拖成 6h 醒来）。
            val intervalHours = buildList {
                if (scoreOn) add(ScoreAlertDefaults.coerceIntervalHours(prefs.scoreAlertIntervalHours.first()))
                if (examOn) add(ScoreAlertDefaults.coerceIntervalHours(prefs.examAlertIntervalHours.first()))
                if (selectionOn) {
                    add(ScoreAlertDefaults.coerceIntervalHours(prefs.selectionAlertIntervalHours.first()))
                }
                if (scheduleOn) {
                    add(ScheduleAlertDefaults.coerceIntervalHours(prefs.scheduleAlertIntervalHours.first()))
                }
                if (wenquOn) {
                    add(WenquRules.coerceActivityIntervalHours(prefs.wenquActivityIntervalHours.first()))
                }
                // 盯了活动时这一路要 1 小时一轮（其它路的检查时刻各自自判，不会被带快）
                if (pinned) add(WenquRules.GRAB_PIN_CHECK_INTERVAL_HOURS)
            }.min()
            val request = PeriodicWorkRequestBuilder<ScoreAlertCheckWorker>(intervalHours.toLong(), TimeUnit.HOURS)
                .setConstraints(connectedConstraint())
                .build()
            if (prefs.alertPeriodicInterval() == intervalHours) {
                workManager.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
            } else {
                workManager.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.REPLACE, request)
                prefs.setAlertPeriodicInterval(intervalHours)
            }
        }.onFailure { Log.w(TAG, "ensurePeriodicWork failed", it) }
    }

    /** 即时评估（REPLACE：连着改两次只跑最后一次）。冷启动与设置变更都走这里。 */
    fun enqueueCheck(context: Context, onlyChain: String? = null) {
        runCatching {
            val request = OneTimeWorkRequestBuilder<ScoreAlertCheckWorker>()
                .setConstraints(connectedConstraint())
                .setInputData(
                    if (onlyChain == null) workDataOf() else workDataOf(KEY_ONLY_CHAIN to onlyChain),
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(ONE_SHOT_WORK, ExistingWorkPolicy.REPLACE, request)
        }.onFailure { Log.w(TAG, "enqueueCheck failed", it) }
    }

    /**
     * **只跑问渠那一路**的一次性核对（问渠设置页改开关 / 间隔时用）。
     *
     * 为什么不能直接走 [onSettingsChanged]：那个会把五路的检查时刻一起清 0，
     * 于是用户只是动一下「新增志愿活动提醒」，成绩 / 考试 / 选课 / 调课四路就各打一次教务
     * （2026-10-08 复查发现）。问渠是第三方功能，不该顺带骚扰教务。
     */
    fun enqueueWenquCheck(context: Context) = enqueueCheck(context, onlyChain = CHAIN_WENQU)

    /**
     * 设置变更（开关或间隔档位）后的统一入口：清掉三条链路的检查时刻再排即时评估。
     *
     * 清时刻的理由同 `BalanceAlertReminder.onSettingsChanged`：用户刚开的开关/刚改的间隔，
     * 就是想按新规则立刻评估一次；沿用旧时刻的话开关当天不会生效（`shouldAttemptAt`
     * 会直接 Skipped）。选课那条原来靠 SelectionScreen 的开关自己清，考试页一直漏清，
     * 现在统一收口到这里，调用方不用再各管一遍。首次评估抓到的全量会被 `ScoreSync` /
     * `ExamSync` 当基线（firstImport / 空基线），不发通知。
     *
     * **问渠那一路不在这里**——它有自己的 [enqueueWenquCheck]（见上）。
     */
    suspend fun onSettingsChanged(context: Context) {
        runCatching {
            val prefs = Graph.displayPrefs(context)
            prefs.setScoreAlertCheckMillis(0)
            prefs.setExamAlertCheckMillis(0)
            prefs.setSelectionRoundsCheckMillis(0)
            prefs.setScheduleCheckMillis(0)
        }.onFailure { Log.w(TAG, "clear check millis failed", it) }
        ensurePeriodicWork(context)
        enqueueCheck(context)
    }

    /**
     * 核对一次：成绩、考试、选课各查各的（开关各自独立），有变更/到期才发通知。
     * 整体兜异常：后台任务里任何失败都不该让 Worker 报错重试（重试会再打一次教务）。
     *
     * [onlyChain] 非空 = **只跑那一路**（设置页改问渠提醒时用，见 [enqueueWenquCheck]）。
     *
     * 通知总开关只拦「发通知」，不拦核对本身（2026-10-06 收件箱 §4.38）：成绩/考试的
     * 入箱不依赖通知——通知全关的用户恰好是最依赖收件箱的人。选课/调课两路不在此列：
     * 前者的入箱点在「通知发成功」内（发不出就不落已发键也不入箱，下一轮还有机会），
     * 后者的调度开关在 [ScheduleAlertReminder.check] 内部自判。
     */
    suspend fun check(context: Context, onlyChain: String? = null) {
        runCatching {
            val prefs = Graph.displayPrefs(context)
            // 盯了指定活动（问渠蹲点）也要叫醒问渠那一路——见 WenquActivityAlertReminder.checkPinnedSlot
            val wenquPinned = prefs.wenquGrabPinnedId.first() > 0L
            if (onlyChain == CHAIN_WENQU) {
                if (prefs.wenquActivityAlertEnabled.first() || wenquPinned) {
                    WenquActivityAlertReminder.check(context)
                }
                return
            }
            val scoreOn = prefs.scoreAlertEnabled.first()
            val examOn = prefs.examAlertEnabled.first()
            val selectionOn = prefs.selectionAlertEnabled.first()
            val scheduleOn = prefs.scheduleAlertEnabled.first()
            val wenquOn = prefs.wenquActivityAlertEnabled.first()
            if (!scoreOn && !examOn && !selectionOn && !scheduleOn && !wenquOn && !wenquPinned) return
            val notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
            if (scoreOn) checkScores(context, notificationsEnabled)
            if (examOn) checkExams(context, notificationsEnabled)
            // 选课提醒自带落盘去重与闹钟重排（DESIGN §4.35），这里只负责按周期叫醒它
            if (selectionOn) SelectionAlertReminder.check(context)
            // 调课检测（DESIGN §4.17 重立）：第四路搭同一趟车，闸门与静默纪律在 ScheduleSync.check
            if (scheduleOn) ScheduleAlertReminder.check(context)
            // 问渠新活动（DESIGN §4.45）：第五路，自带开关 / 间隔闸门与首跑建基线
            // （盯了指定活动时也走它——那一路自判，不受新活动开关与间隔影响）
            if (wenquOn || wenquPinned) WenquActivityAlertReminder.check(context)
        }.onFailure { Log.w(TAG, "check failed", it) }
    }

    // ------------------------------------------------------------------
    // 成绩
    // ------------------------------------------------------------------

    private suspend fun checkScores(context: Context, notificationsEnabled: Boolean) {
        when (val result = Graph.scoreSync(context).sync()) {
            is ScoreSync.Result.Updated -> {
                if (result.firstImport) return // 首次导入是基线，不是变动
                if (result.changes.isEmpty()) return
                // 免扰时段（DESIGN §3.7）：出分是事实型通知（eventAt=null），拦下入队
                // 待时段结束后补发，不丢弃。入箱在 ScoreSync 已完成，不受此影响
                val held = notificationsEnabled && QuietHoursGate.intercept(
                    context,
                    PendingNotification(
                        tag = SCORE_NOTIFICATION_TAG,
                        notificationId = SCORE_NOTIFICATION_ID,
                        channelId = CHANNEL_ID,
                        category = NotificationCompat.CATEGORY_REMINDER,
                        title = if (result.changes.size == 1) "新成绩：${result.changes[0].name}"
                        else "有 ${result.changes.size} 门新成绩",
                        text = scoresText(result.changes),
                        enqueuedAt = System.currentTimeMillis(),
                        launch = PendingLaunch(
                            kind = PendingLaunch.Kind.SUBPAGE,
                            requestCode = SCORE_REQUEST_CODE,
                            screen = SubpageScreen.SCORES.name,
                            clearTask = true,
                        ),
                    ),
                )
                if (notificationsEnabled && !held) postScores(context, result.changes)
            }
            else -> Unit // Skipped / InProgress / Failed 一律静默，失败闸门不落时刻
        }
    }

    // ------------------------------------------------------------------
    // 考试
    // ------------------------------------------------------------------

    private suspend fun checkExams(context: Context, notificationsEnabled: Boolean) {
        when (val result = Graph.examSync(context).sync()) {
            is ExamSync.Result.Updated -> {
                if (result.changes.isEmpty()) return
                // 免扰时段（DESIGN §3.7）：考试变动是事实型通知，拦下待补发
                val held = notificationsEnabled && QuietHoursGate.intercept(
                    context,
                    PendingNotification(
                        tag = EXAM_NOTIFICATION_TAG,
                        notificationId = EXAM_NOTIFICATION_ID,
                        channelId = CHANNEL_ID,
                        category = NotificationCompat.CATEGORY_REMINDER,
                        title = if (result.changes.size == 1) "考试安排有变动"
                        else "有 ${result.changes.size} 条考试变动",
                        text = examsText(result.changes),
                        enqueuedAt = System.currentTimeMillis(),
                        launch = PendingLaunch(
                            kind = PendingLaunch.Kind.SUBPAGE,
                            requestCode = EXAM_REQUEST_CODE,
                            screen = SubpageScreen.EXAMS.name,
                            clearTask = true,
                        ),
                    ),
                )
                if (notificationsEnabled && !held) postExams(context, result.changes)
            }
            else -> Unit
        }
    }

    // ------------------------------------------------------------------
    // 通知
    // ------------------------------------------------------------------

    /** 成绩通知正文（拦截入队与即时发送共用同一份，两处各写迟早漂）。 */
    private fun scoresText(
        changes: List<edu.jxslu.schedule.domain.ScoreChangeDetector.Change>,
    ): String {
        val lines = changes.take(MAX_LINES).map { "${it.name}：${it.scoreStr}" }
        val rest = changes.size - lines.size
        return buildString {
            append(lines.joinToString("\n"))
            if (rest > 0) append("\n等 $rest 门")
        }
    }

    /** 考试通知正文（共用口径同 [scoresText]）。 */
    private fun examsText(changes: List<ExamChangeDetector.Change>): String {
        val lines = changes.take(MAX_LINES).map { change ->
            val prefix = if (change.kind == ExamChangeDetector.Kind.NEW) "新增" else "调整"
            val e = change.entry
            buildString {
                append(prefix).append("：").append(e.name)
                if (e.date.isNotBlank()) append(' ').append(e.date)
                if (e.startTime.isNotBlank()) append(' ').append(e.startTime)
                if (e.room.isNotBlank()) append(' ').append(e.room)
            }
        }
        val rest = changes.size - lines.size
        return buildString {
            append(lines.joinToString("\n"))
            if (rest > 0) append("\n等 $rest 条")
        }
    }

    /** 成绩通知：正文列「课程名：分数」，超出 [MAX_LINES] 折叠。点击落成绩页。 */
    private fun postScores(
        context: Context,
        changes: List<edu.jxslu.schedule.domain.ScoreChangeDetector.Change>,
    ) {
        val text = scoresText(changes)
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bell)
            .setContentTitle(if (changes.size == 1) "新成绩：${changes[0].name}" else "有 ${changes.size} 门新成绩")
            .setContentText(text.lines().firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    SCORE_REQUEST_CODE,
                    subpageLaunchIntent(context, SubpageRequest(SubpageScreen.SCORES))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(SCORE_NOTIFICATION_TAG, SCORE_NOTIFICATION_ID, notification)
        }.onFailure { Log.w(TAG, "notify scores failed", it) }
    }

    /** 考试通知：正文列「新增/调整：课程 日期 时刻 地点」。点击落考试页。 */
    private fun postExams(
        context: Context,
        changes: List<ExamChangeDetector.Change>,
    ) {
        val text = examsText(changes)
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bell)
            .setContentTitle(if (changes.size == 1) "考试安排有变动" else "有 ${changes.size} 条考试变动")
            .setContentText(text.lines().firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    EXAM_REQUEST_CODE,
                    subpageLaunchIntent(context, SubpageRequest(SubpageScreen.EXAMS))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(EXAM_NOTIFICATION_TAG, EXAM_NOTIFICATION_ID, notification)
        }.onFailure { Log.w(TAG, "notify exams failed", it) }
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        // IMPORTANCE_DEFAULT 而非 HIGH：出分不是「马上要做的事」，不值得横幅打断
        val channel = NotificationChannel(CHANNEL_ID, "成绩与考试提醒", NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = "新出成绩、复查改分或考试安排变动时提醒" }
        manager.createNotificationChannel(channel)
    }

    private fun connectedConstraint(): Constraints =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
}

/**
 * 成绩/考试核对 Worker。**类名不要改**：WorkManager 把类名存进自己的库，老版本排下的
 * 周期任务在应用升级后仍是这个名字；改名会让那些任务实例化失败，而 `KEEP`/`UPDATE`
 * 策略下已排任务不会按新类名重排（同 `BalanceAlertCheckWorker` 的理由）。
 */
class ScoreAlertCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // 入参非空 = 只跑那一路（问渠设置页改开关时用，见 ScoreAlertReminder.enqueueWenquCheck）
        ScoreAlertReminder.check(applicationContext, inputData.getString(ScoreAlertReminder.KEY_ONLY_CHAIN))
        return Result.success()
    }
}
