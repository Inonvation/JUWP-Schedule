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
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.R
import edu.jxslu.schedule.SubpageScreen
import edu.jxslu.schedule.SubpageRequest
import edu.jxslu.schedule.data.repo.ExamSync
import edu.jxslu.schedule.data.repo.ScoreSync
import edu.jxslu.schedule.domain.ExamChangeDetector
import edu.jxslu.schedule.domain.ScoreAlertDefaults
import edu.jxslu.schedule.subpageLaunchIntent
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

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
 * 3. **考试只提醒不写库**：点通知落教务导入页（`JwImportActivity`，Schedule 模式），
 *    用户确认后才写课表。考试进课表只有手动导入一条路（DESIGN §4.33 红线）。
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
     * 周期核对：两个开关都关时**撤销**任务而不是留着空跑（同 `BalanceAlertReminder`）。
     * 幂等策略：**档位没变时 KEEP**，免得每次冷启动都把周期相位推后；档位变了用
     * REPLACE 换周期（2.7.1 没有 UPDATE 策略；REPLACE 取消旧任务重新排，只在用户
     * 改间隔那一刻发生）。已排周期记在 DataStore（`alert_periodic_interval`），
     * 进程重启后也能比对；0 = 当前没有在排的周期任务。
     */
    suspend fun ensurePeriodicWork(context: Context) {
        runCatching {
            val prefs = Graph.displayPrefs(context)
            val workManager = WorkManager.getInstance(context)
            if (!prefs.scoreAlertEnabled.first() && !prefs.examAlertEnabled.first()) {
                workManager.cancelUniqueWork(PERIODIC_WORK)
                prefs.setAlertPeriodicInterval(0)
                return
            }
            val intervalHours = ScoreAlertDefaults.coerceIntervalHours(prefs.alertIntervalHours.first())
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
    fun enqueueCheck(context: Context) {
        runCatching {
            val request = OneTimeWorkRequestBuilder<ScoreAlertCheckWorker>()
                .setConstraints(connectedConstraint())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(ONE_SHOT_WORK, ExistingWorkPolicy.REPLACE, request)
        }.onFailure { Log.w(TAG, "enqueueCheck failed", it) }
    }

    /**
     * 设置变更（开关或间隔档位）后的统一入口：清掉对应链路的检查时刻再排即时评估。
     *
     * 清时刻的理由同 `BalanceAlertReminder.onSettingsChanged`：用户刚开的开关/刚改的间隔，
     * 就是想按新规则立刻评估一次；沿用旧时刻的话开关当天不会生效。首次评估抓到的
     * 全量会被 `ScoreSync` / `ExamSync` 当基线（firstImport / 空基线），不发通知。
     */
    suspend fun onSettingsChanged(context: Context) {
        ensurePeriodicWork(context)
        enqueueCheck(context)
    }

    /**
     * 核对一次：成绩与考试各查各的（开关各自独立），有变更才发通知。
     * 整体兜异常：后台任务里任何失败都不该让 Worker 报错重试（重试会再打一次教务）。
     */
    suspend fun check(context: Context) {
        runCatching {
            val prefs = Graph.displayPrefs(context)
            val scoreOn = prefs.scoreAlertEnabled.first()
            val examOn = prefs.examAlertEnabled.first()
            if (!scoreOn && !examOn) return
            // 通知被系统/用户整体关闭时静默跳过：写不出去也不该白打一次教务
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
            if (scoreOn) checkScores(context)
            if (examOn) checkExams(context)
        }.onFailure { Log.w(TAG, "check failed", it) }
    }

    // ------------------------------------------------------------------
    // 成绩
    // ------------------------------------------------------------------

    private suspend fun checkScores(context: Context) {
        when (val result = Graph.scoreSync(context).sync()) {
            is ScoreSync.Result.Updated -> {
                if (result.firstImport) return // 首次导入是基线，不是变动
                if (result.changes.isEmpty()) return
                postScores(context, result.changes)
            }
            else -> Unit // Skipped / InProgress / Failed 一律静默，失败闸门不落时刻
        }
    }

    // ------------------------------------------------------------------
    // 考试
    // ------------------------------------------------------------------

    private suspend fun checkExams(context: Context) {
        when (val result = Graph.examSync(context).sync()) {
            is ExamSync.Result.Updated -> {
                if (result.changes.isEmpty()) return
                postExams(context, result.changes)
            }
            else -> Unit
        }
    }

    // ------------------------------------------------------------------
    // 通知
    // ------------------------------------------------------------------

    /** 成绩通知：正文列「课程名：分数」，超出 [MAX_LINES] 折叠。点击落成绩页。 */
    private fun postScores(
        context: Context,
        changes: List<edu.jxslu.schedule.domain.ScoreChangeDetector.Change>,
    ) {
        val lines = changes.take(MAX_LINES).map { "${it.name}：${it.scoreStr}" }
        val rest = changes.size - lines.size
        val text = buildString {
            append(lines.joinToString("\n"))
            if (rest > 0) append("\n等 $rest 门")
        }
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bell)
            .setContentTitle(if (changes.size == 1) "新成绩：${changes[0].name}" else "有 ${changes.size} 门新成绩")
            .setContentText(lines.firstOrNull().orEmpty())
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

    /** 考试通知：正文列「新增/调整：课程 日期 时刻 地点」。点击落教务导入页。 */
    private fun postExams(
        context: Context,
        changes: List<ExamChangeDetector.Change>,
    ) {
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
        val text = buildString {
            append(lines.joinToString("\n"))
            if (rest > 0) append("\n等 $rest 条")
        }
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bell)
            .setContentTitle(if (changes.size == 1) "考试安排有变动" else "有 ${changes.size} 条考试变动")
            .setContentText(lines.firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    EXAM_REQUEST_CODE,
                    Intent(context, edu.jxslu.schedule.JwImportActivity::class.java)
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
        ScoreAlertReminder.check(applicationContext)
        return Result.success()
    }
}
