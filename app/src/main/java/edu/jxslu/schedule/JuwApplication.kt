package edu.jxslu.schedule

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import edu.jxslu.schedule.data.repo.ScheduleRepository
import edu.jxslu.schedule.ui.ebike.EbikeFreeRideReminder
import edu.jxslu.schedule.ui.reminder.BalanceAlertReminder
import edu.jxslu.schedule.ui.reminder.ClassReminder
import edu.jxslu.schedule.ui.week.warmScheduleBackground
import edu.jxslu.schedule.ui.widget.TodayWidgetRefresh
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class JuwApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // 进程级 context 注入（Graph.appContext）：后台协程落盘等场景免持 Activity 引用
        Graph.contextProvider = { this }
        val repo = Graph.repository(this)
        appScope.launch {
            // 仅保证节次与学期默认值；课表默认空，由教务导入
            repo.ensureDefaults()
            // 桌面小组件（DESIGN §3.6）：冷启动主动刷一次 + 排 15 分钟周期兜底。
            // refreshNow 内部无 widget 绑定时是廉价的空跑（getGlanceIds 为空），
            // 不会白做 RemoteViews 组装。
            TodayWidgetRefresh.refreshNow(this@JuwApplication)
            TodayWidgetRefresh.ensurePeriodicWork(this@JuwApplication)
            // 上课提醒（DESIGN §3.7）：冷启动重排下一个提醒 + 周期核对兜底。
            // 提醒关/无课时 scheduleNext 内部是撤销闹钟的空跑，很廉价。
            ClassReminder.scheduleNext(this@JuwApplication)
            ClassReminder.ensurePeriodicWork(this@JuwApplication)
            // 共享单车免费时长提醒（DESIGN §3.9，2026-09-24 起走 App 通知）：冷启动核一次
            // ——计时中缺常驻倒计时 / 闹钟就补上，过期的清干净。**周期兜底由 check 按需排**
            // （只在计时期间存在，平时零唤醒）；老版本排下的周期任务也会在这一步被撤掉。
            // 无进行中计时 / 开关关时 check 是空跑，很廉价。
            EbikeFreeRideReminder.check(this@JuwApplication)
            // 首版（2026-09-22）通知 channel 的清理：新 channel 用别的 id，
            // 老 channel 一旦建出来就常驻系统，代码不再用它也不会自己消失
            EbikeFreeRideReminder.deleteLegacyChannel(this@JuwApplication)
            // 调课检测 channel 的清理（功能已移除，2026-09-24）：
            // 老版本升级用户设备上留着「调课检测」channel，删掉避免设置页残留死通道
            deleteLegacyDetectChannel(this@JuwApplication)
            // 余额提醒（DESIGN §3.10 / §3.13）：排每日核对（09:00 前后一次），两个开关都关时
            // 内部会撤销任务；冷启动再补核一次，兜住「周期任务今天还没跑」的当天提醒。
            // 已成功检查过的来源当天不会重复打第三方接口（闸门在 check 内部）。
            BalanceAlertReminder.ensurePeriodicWork(this@JuwApplication)
            BalanceAlertReminder.enqueueCheck(this@JuwApplication)
        }
        // 课表数据一变就推给桌面：用户在 App 里改完课，回桌面立刻是新内容，
        // 不必等下一个 15 分钟兜底。flows 本身是 Room 驱动，只在真实写库时发射，无轮询。
        // 提醒也依赖这三份数据：同一处重排下一个提醒，改完课/换课表即刻生效。
        appScope.launch {
            kotlinx.coroutines.flow.combine(
                repo.courses,
                repo.semester,
                repo.timeSlots,
            ) { _, _, _ -> Unit }.collect {
                TodayWidgetRefresh.refreshNow(this@JuwApplication)
                ClassReminder.scheduleNext(this@JuwApplication)
            }
        }
        // 附件孤儿清扫（DESIGN §4.20）：正文里已无引用的图片文件删掉。
        // 保存路径已做增量清理，这里兜住「直接改正文删掉引用」「异常中断」这类残留；
        // 条目量小（全量正文一次读），IO 协程里跑，冷启动不阻塞界面。
        appScope.launch {
            runCatching {
                val noteRepo = Graph.noteRepository(this@JuwApplication)
                val homeworkRepo = Graph.homeworkRepository(this@JuwApplication)
                val store = Graph.attachmentStore(this@JuwApplication)
                val referenced = store.referencedNames(noteRepo.allBodies() + homeworkRepo.allDetails())
                store.sweep(referenced)
            }.onFailure { android.util.Log.w("JuwApplication", "attachment sweep failed", it) }
        }
        // 课表页背景图（DESIGN §4.21）：先清掉目录里多余的旧图，再把当前这张解进内存缓存。
        //
        // 预热是为了首帧：不预热的话，切到课表 Tab 时网格先出、背景晚几十毫秒补上，
        // 能看到一次闪入（该页对首帧闪动的容忍度很低，Pager 与底部抽屉都为这件事改过）。
        // 解码目标按设备长边算，与渲染路径同一口径，命中率才是 100%。
        appScope.launch {
            runCatching {
                val displayPrefs = Graph.repository(this@JuwApplication).displayPrefs.first()
                Graph.scheduleBackground(this@JuwApplication).sweep(displayPrefs.bgImageName)
                displayPrefs.bgImageName?.let { name ->
                    warmScheduleBackground(this@JuwApplication, name, displayPrefs.bgImageBlur)
                }
            }.onFailure { android.util.Log.w("JuwApplication", "background warmup failed", it) }
        }
        // 调课检测功能已移除（2026-09-24）：老版本排下的 WorkManager 周期/一次性任务
        // 按类名实例化 `JwDetectWorker`，类删了会实例化失败——按名字显式取消，彻底清干净。
        // 与 EbikeFreeRideCheckWorker 的「KEEP 保留」不同：这里没有任何要保的兜底，取消即净。
        appScope.launch {
            runCatching {
                val wm = androidx.work.WorkManager.getInstance(this@JuwApplication)
                wm.cancelUniqueWork("tweak_detect_periodic")
                wm.cancelUniqueWork("tweak_detect_onetime")
            }.onFailure { android.util.Log.w("JuwApplication", "detect work cancel failed", it) }
        }
    }

    /** 删掉调课检测的通知 channel（功能已移除；已存在才删，幂等）。 */
    private fun deleteLegacyDetectChannel(context: Context) {
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.deleteNotificationChannel("jw_detect")
        }
    }
}
