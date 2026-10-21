package edu.jxslu.schedule.ui.wenqu

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 预报名执行服务（DESIGN §4.45，2026-10-16；用户拍板）：**短时前台服务**（type `shortService`）。
 *
 * 为什么是前台服务：闹钟落点要在「App 不在前台」时跑两分半钟的网络轮询，普通后台进程
 * 会被系统冻住。Android 官方豁免清单里，「调用精确闹钟来完成用户请求的动作」允许在后台
 * 启动前台服务（`restrictions-bg-start`），项目里骑行自动还车那条链同款用法。
 * `shortService` 给约 3 分钟的额度，正合执行窗口（T−60s 起、总长 ≤150 秒）。
 *
 * 通知 id [WenquPreregReminder.NOTIFICATION_ID]（channel `wenqu_prereg`）：执行中常驻
 * （静默）；结束时按执行结果决定是「摘掉」还是「保留为标准通知」——结果通知与进度通知
 * 共用同一个编号，跑完就把常驻件脱离服务生命周期（结果已就地替换了它）。
 *
 * **重试轮（2026-10-21）**：结果通知 / 仪表板上点「重试」时带 [EXTRA_RETRY] 重新起本服务
 * ——前台启动前台服务合规，执行器同一条幂等闸与通知链路复用；重试轮预算独立
 * （[WenquRules.PREREG_RETRY_MAX_ATTEMPTS] / [WenquRules.PREREG_RETRY_RUN_MS]）。
 *
 * **中断（2026-10-21）**：[onTimeout]（shortService 额度被系统收回）或异常终止时若仪表板
 * 还在跑，置为「被中断」终态——用户在面板上看到的是实情，不是永远的「抢报中」。
 */
class WenquPreregService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 在飞的一轮（重复的 onStartCommand 直接忽略，别把正在跑的那轮掐掉）。 */
    private var runJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification =
            WenquPreregReminder.buildProgressNotification(this, "正在核对该活动的最新状态…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                WenquPreregReminder.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE,
            )
        } else {
            // 低版本没有 shortService 类型（3 分钟额度也不存在）：普通 startForeground，
            // 服务自己按时收工（执行器 150 秒封顶）。
            ServiceCompat.startForeground(this, WenquPreregReminder.NOTIFICATION_ID, notification, 0)
        }
        // 同一实例重复收到启动（闹钟与兜底 Work 撞车 / 重排后旧闹钟又响）：不重复跑，
        // 也**不能**在这里 stopSelf——那会把正在执行的那一轮连同通知一起掐掉。
        if (runJob?.isActive == true) return START_NOT_STICKY
        val retry = intent?.getBooleanExtra(EXTRA_RETRY, false) == true
        runJob = scope.launch {
            val outcome = runCatching {
                WenquPreregRunner.run(this@WenquPreregService, showProgress = true, retry = retry)
            }.getOrNull()
            // 结果通知与进度通知共用同一个编号：跑完就让常驻件「脱离服务生命周期」保留下来
            // （结果就地替换了它）；空跑（没排上 / 已被取消）则直接摘掉，不留残影。
            val keep = outcome != null && outcome.kind != WenquPreregRunner.Outcome.Kind.Skipped
            ServiceCompat.stopForeground(
                this@WenquPreregService,
                if (keep) ServiceCompat.STOP_FOREGROUND_DETACH
                else ServiceCompat.STOP_FOREGROUND_REMOVE,
            )
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // shortService 额度到点（Android 14+）：执行器自带 150 秒上限，正常到不了这里；
        // 真到了就收工，避免系统按 ANR 处理。仪表板若还挂在运行态，如实置「被中断」。
        WenquPreregDashboardHost.markInterrupted("系统收回了执行窗口（前台服务额度到点）")
        stopSelf()
    }

    override fun onDestroy() {
        // 服务被销毁但执行轮还在跑（onTimeout 忘了 / 进程被要求收缩）：同上置中断。
        if (runJob?.isActive == true) {
            WenquPreregDashboardHost.markInterrupted("执行窗口被系统中断")
        }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** 重试轮标记（仪表板「重试」按钮 → 通知 / 面板经 [WenquPreregReminder.startService] 传入）。 */
        const val EXTRA_RETRY = "retry"
    }
}
