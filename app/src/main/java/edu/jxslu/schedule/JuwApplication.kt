package edu.jxslu.schedule

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import edu.jxslu.schedule.data.repo.CrashReporter
import edu.jxslu.schedule.data.repo.ScheduleRepository
import edu.jxslu.schedule.data.repo.UpdateCheckClient
import edu.jxslu.schedule.data.repo.UpdateCheckResult
import edu.jxslu.schedule.data.session.AutoSyncRules
import edu.jxslu.schedule.domain.UpdateCheck
import edu.jxslu.schedule.ui.ebike.EbikeFreeRideReminder
import edu.jxslu.schedule.ui.me.hub.UpdateAvailableBus
import edu.jxslu.schedule.ui.reminder.BalanceAlertReminder
import edu.jxslu.schedule.ui.reminder.ClassReminder
import edu.jxslu.schedule.ui.reminder.QuietHoursGate
import edu.jxslu.schedule.ui.reminder.UjingDoneReminder
import edu.jxslu.schedule.ui.reminder.LoginStateNotifier
import edu.jxslu.schedule.ui.reminder.ScoreAlertReminder
import edu.jxslu.schedule.ui.reminder.SelectionAlertReminder
import edu.jxslu.schedule.ui.reminder.WenquWeeklyAlertReminder
import edu.jxslu.schedule.ui.wenqu.WenquPreregReminder
import edu.jxslu.schedule.ui.week.warmScheduleBackground
import edu.jxslu.schedule.ui.widget.LifeWidgetSync
import edu.jxslu.schedule.ui.widget.TodayWidgetRefresh
import edu.jxslu.schedule.ui.widget.WaterWidgetSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class JuwApplication : Application() {
    /**
     * 进程级协程作用域。2026-09-27 起改用 [Graph.appScope] 那一份——引导页登录成功后
     * 要丢后台的自动导入也往这里投，全进程一个池，不各开各的。
     */
    private val appScope: CoroutineScope = Graph.appScope

    override fun onCreate() {
        super.onCreate()
        // 进程级 context 注入（Graph.appContext）：后台协程落盘等场景免持 Activity 引用
        Graph.contextProvider = { this }
        // 崩溃留痕（2026-10-02，体检第三批）：JVM 级未捕获异常 + appScope 协程异常都落
        // filesDir/crash/（只写本机不上传）。「我的 → 关于」有导出入口；没有它，用户报
        // 「闪退了」时手上什么都没有。必须在任何 appScope.launch 之前装。
        CrashReporter.installUncaughtHandler(this)
        val repo = Graph.repository(this)
        appScope.launch {
            // 仅保证节次与学期默认值；课表默认空，由教务导入。
            // runCatching 兜底：DataStore 损坏 / Room 异常不兜住就是每次冷启动必崩
            // 且无任何留痕（appScope 有 handler 只留痕不阻止 launch 的失败继续往下）。
            runCatching { repo.ensureDefaults() }
                .onFailure { CrashReporter.logCoroutine(this@JuwApplication, "ensureDefaults", it) }
            // 桌面小组件（DESIGN §3.6）：冷启动主动刷一次 + 排 15 分钟周期兜底。
            // refreshNow 内部无 widget 绑定时是廉价的空跑（getGlanceIds 为空），
            // 不会白做 RemoteViews 组装。
            TodayWidgetRefresh.refreshNow(this@JuwApplication)
            TodayWidgetRefresh.ensurePeriodicWork(this@JuwApplication)
            // 生活小组件（DESIGN §3.6 二条目改版，2026-09-27）：电费副行镜像读 Room
            // （零网络），校园卡余额过 2 小时闸门后取一次。无实例 / 无凭证 / 闸门未到
            // 都是空跑，内部各自吞错，不会打断后面几步启动协程
            LifeWidgetSync.onColdStart(this@JuwApplication)
            // 开水两卡小组件（DESIGN §3.6「开水两卡」，2026-09-28）：本地镜像 +
            // 胖乖 / 趣智余额过 2 小时闸门各取一次。无实例 / 未登录 / 闸门未到都是
            // 空跑，内部各自吞错，不会打断后面几步启动协程
            WaterWidgetSync.onColdStart(this@JuwApplication)
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
            // 余额提醒（DESIGN §3.10 / §3.13）：排周期核对（间隔 = 两链设置的最小档），
            // 两个开关都关时内部会撤销任务；冷启动再补核一次，兜住「间隔闸门已到期」的核对。
            // 未到间隔的来源不会重复打第三方接口（闸门在 check 内部）。
            BalanceAlertReminder.ensurePeriodicWork(this@JuwApplication)
            BalanceAlertReminder.enqueueCheck(this@JuwApplication)
            // 成绩/考试变动提醒（DESIGN §4.33）：排周期核对（间隔 = 设置档位），两个开关
            // 都关时内部会撤销任务；冷启动再补查一次，兜住「周期任务还没跑」的间隔。
            // 间隔闸门在 ScoreSync / ExamSync 内部（成功才落时刻），不会重复打教务。
            ScoreAlertReminder.ensurePeriodicWork(this@JuwApplication)
            ScoreAlertReminder.enqueueCheck(this@JuwApplication)
            // 选课轮次提醒（DESIGN §4.35）：周期核对搭成绩/考试那一趟车（间隔共用），
            // 冷启动只补一次本地重排——闹钟丢了/换代就补上，开关关着时是撤销闹钟的空跑。
            SelectionAlertReminder.scheduleNext(this@JuwApplication)
            // 问渠「每周日 12:00 抢活动」提醒（DESIGN §4.45，2026-10-11）：精确闹钟同样不跨
            // 重启/安装存活，冷启动补一次本地重排——开关关着时是撤销闹钟的空跑，零网络。
            WenquWeeklyAlertReminder.scheduleNext(this@JuwApplication)
            // 问渠「预报名」（DESIGN §4.45，2026-10-16）：同一条精确闹钟口径，冷启动补排——
            // 没排上时是撤闹钟的空跑；错过窗口的会作废并通知，不让用户以为还排着。
            WenquPreregReminder.scheduleNext(this@JuwApplication)
            // U净洗衣完成提醒（DESIGN §4.37，2026-10-02 补冷启动核对）：它的精确闹钟
            // 与其他提醒一样不跨安装/重启存活，但此前只有订单卡刷新时排——覆盖安装后
            // 「洗涤中」的完成提醒会静默消失。check 内部：无在案订单撤销闹钟（空跑）；
            // 有订单先查一次状态再按剩余/总时长补排，失败静默。
            UjingDoneReminder.check(this@JuwApplication)
            // 收件箱保留策略（DESIGN §4.38）：软删 30 天、已读 90 天、总量 200 条，
            // 冷启动裁一次。量级很小，廉价；失败不挡后面的启动协程。
            runCatching { Graph.inboxRepository(this@JuwApplication).purge() }
                .onFailure { CrashReporter.logCoroutine(this@JuwApplication, "inboxPurge", it) }
            // 免扰时段补发（DESIGN §3.7，2026-10-07）：兜住关机/重启丢掉的补发闹钟
            // ——免扰期间积下的通知在启动时放行。队列为空 / 不在免扰出口点时是空跑，
            // 纯本地 IO 无网络；失败不挡启动链。
            QuietHoursGate.flushIfDue(this@JuwApplication, appScope)
        }
        // 冷启动先把 CAS 会话建起来（DESIGN §4.27）：三个 WebView 入口（导入 / 学工 / 签章）
        // 落到登录页时，自动填表提交能立刻成功，用户少等一轮；OkHttp 那条链
        // （学籍卡 / 成绩导入）也直接吃这份会话。
        //
        // 为什么可以放心每次都跑：`login_gate` 里的 `last_success` 是落盘的，10 分钟信任期
        // 内直接返回不发请求；超出才探一次会话（一个轻量 GET）。失败静默——打开 WebView
        // 时还会再试，而且用户在页面上手登永远可以。
        appScope.launch {
            runCatching {
                Graph.casSession(this@JuwApplication).ensureValid()
                // 会话可用就补抓学业数据（DESIGN §4.29）：首次（库里没数据）立即抓，
                // 之后 7 天一次；不满足闸门时内部是零网络的空跑。失败静默——它们是
                // 后台补充，不该在冷启动弹任何东西。顺序上先成绩后学业：成绩条数少、
                // 先落库，用户打开「我的 → 学习」时大概率已经有数据。
                //
                // 成绩检查内部自带两套闸门（DESIGN §4.33）：提醒开着走小时级间隔
                // （`score_check_millis`），关着走 7 天日期闸门，所以这里不必判开关。
                // 通知的发出在 ScoreAlertReminder 的 worker 里，不走这条路——
                // 这只是「打开应用后台查一次」的那一次（用户要求的口径）。
                // 几条 sync 彼此隔着随机小间隔（AutoSyncRules.interRequestDelayMs）：
                // 各自内部还有闸门，多数时候是零网络空跑；真都到期时也不再是
                // 「成绩→考试→学业→培养方案→教材」背靠背连发。delay 放在 runCatching 外内皆可，
                // 这里选内：某条失败不影响后续。
                runCatching { Graph.scoreSync(this@JuwApplication).sync() }
                delay(AutoSyncRules.interRequestDelayMs())
                runCatching { Graph.examSync(this@JuwApplication).sync() }
                delay(AutoSyncRules.interRequestDelayMs())
                runCatching { Graph.scholarProgressSync(this@JuwApplication).sync() }
                // 培养方案（DESIGN §4.41）：首次（库里没数据）立即抓，之后 7 天一次；
                // 与学业共用同一把日期闸门，不满足时零网络空跑
                delay(AutoSyncRules.interRequestDelayMs())
                runCatching { Graph.curriculumSync(this@JuwApplication).sync() }
                // 教材补抓（DESIGN §4.31，2026-10-06）：当前课表有学期号、但该学期一条教材
                // 都没有时补抓一次。教材原本只跟「导入课表」抓那一次，而那次会因为会话被
                // 别处登录顶掉（强智单会话）静默失败——导入成功、详情里就是没有教材。
                // 这一步把「一次性失败」变成可恢复：还空着就下次冷启动再试（6 小时闸门在
                // AutoSyncRules）。教材非空 / 闸门未到 / 课表没学期号都是零网络的空跑。
                delay(AutoSyncRules.interRequestDelayMs())
                runCatching { Graph.textbookSync(this@JuwApplication).backfillIfMissing() }
            }
        }
        // 校历镜像（DESIGN §4.34）：冷启动静默刷一次，7 天闸门在 store 内部——
        // 未到期是零网络空跑；学年没变不重复下载图；失败静默（内置兜底图顶上）。
        // 与教务自动导入互不相干，独立一条不进 CAS 会话那组
        appScope.launch {
            runCatching { Graph.campusCalendar(this@JuwApplication).refresh() }
        }
        // 启动自动检查更新（DESIGN §3.3，2026-10-08）：进程冷启动静默查一次
        // （无闸门——需求就是每次启动查一次；清单是公开仓库文件，与教务伺服无关），
        // 发现新版本经 UpdateAvailableBus 交主界面根层弹窗（用户关掉弹窗才消费）。
        // 开关（默认开）关时零网络空跑；已忽略的版本不弹（手动检查不受影响）；
        // 已最新 / 失败都静默——与校历同纪律。logcat `JuwApplication` 有每次结论。
        appScope.launch {
            runCatching {
                if (!repo.autoUpdateCheckEnabled.first()) return@runCatching
                when (val result = UpdateCheckClient().check()) {
                    is UpdateCheckResult.UpdateAvailable -> {
                        val latest = result.latest
                        if (UpdateCheck.shouldAutoPrompt(latest.version, repo.ignoredUpdateVersion())) {
                            UpdateAvailableBus.publish(latest)
                            android.util.Log.i("JuwApplication", "update available: ${latest.version}")
                        } else {
                            android.util.Log.i("JuwApplication", "update ignored: ${latest.version}")
                        }
                    }
                    is UpdateCheckResult.UpToDate ->
                        android.util.Log.i("JuwApplication", "update check: up to date (${result.latest.version})")
                    is UpdateCheckResult.Failed ->
                        android.util.Log.i("JuwApplication", "update check: failed (network)")
                }
            }.onFailure { android.util.Log.w("JuwApplication", "update auto-check failed", it) }
        }
        // 登录失效提醒（DESIGN §3.16 / §4.27）：只在「转停用」那一刻发一条。
        // 会话过期但凭证有效时不打扰用户——那时会自动续登，发通知只会制造噪音。
        LoginStateNotifier.observe(this, appScope)
        // 课表数据一变就推给桌面：用户在 App 里改完课，回桌面立刻是新内容，
        // 不必等下一个 15 分钟兜底。flows 本身是 Room 驱动，只在真实写库时发射，无轮询。
        // 提醒也依赖这三份数据：同一处重排下一个提醒，改完课/换课表即刻生效。
        // 日期覆盖（调休 / 停课，§4.40）也在这条链上：AI 调课的放假/补课只写
        // `date_overrides`、不动 courses 表，不盯它的话已排的上课闹钟不会撤，
        // 「调完课今天没课了」照样在原时间点响（2026-10-07 实报）。
        appScope.launch {
            kotlinx.coroutines.flow.combine(
                repo.courses,
                repo.semester,
                repo.timeSlots,
                repo.overrides,
            ) { _, _, _, _ -> Unit }.collect {
                TodayWidgetRefresh.refreshNow(this@JuwApplication)
                ClassReminder.scheduleNext(this@JuwApplication)
            }
        }
        // 附件孤儿清扫（DESIGN §4.20）：正文里已无引用的图片文件删掉。
        // 保存路径已做增量清理，这里兜住「直接改正文删掉引用」「异常中断」这类残留。
        // **每日一次闸门**（2026-10-02，同校历 7 天闸门的型）：referencedNames 要把全部
        // 笔记正文 + 作业详情读进内存做正则，条目一多就是冷启动一次全表扫；一天扫一次
        // 足够——增量清理已覆盖绝大多数场景，这里只是兜底。
        appScope.launch {
            runCatching {
                val prefs = getSharedPreferences(ATTACHMENT_SWEEP_PREFS, Context.MODE_PRIVATE)
                val now = System.currentTimeMillis()
                if (now - prefs.getLong(KEY_LAST_ATTACHMENT_SWEEP, 0L) < ATTACHMENT_SWEEP_INTERVAL_MILLIS) {
                    return@launch
                }
                prefs.edit().putLong(KEY_LAST_ATTACHMENT_SWEEP, now).apply()
                val noteRepo = Graph.noteRepository(this@JuwApplication)
                val homeworkRepo = Graph.homeworkRepository(this@JuwApplication)
                val store = Graph.attachmentStore(this@JuwApplication)
                val referenced = store.referencedNames(noteRepo.allBodies() + homeworkRepo.allDetails())
                store.sweep(referenced)
            }.onFailure { android.util.Log.w("JuwApplication", "attachment sweep failed", it) }
        }
        // AI 调课的图片（DESIGN §4.39）：**会话级**，AI 会话本身不落盘，图片也不留痕。
        // 目录里任何残留都是上一次进程没清干净的（杀进程走不到 onCleared），冷启动直接清空。
        // 与笔记附件不同：这里不做引用追踪，也没有频次闸门——目录通常就是空的。
        appScope.launch {
            runCatching { Graph.aiImageStore(this@JuwApplication).sweep() }
                .onFailure { android.util.Log.w("JuwApplication", "ai image sweep failed", it) }
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

/** 附件孤儿清扫的每日闸门（JuwApplication）：时刻与间隔单独一份 prefs，别混进业务文件。 */
private const val ATTACHMENT_SWEEP_PREFS = "maintenance_gates"
private const val KEY_LAST_ATTACHMENT_SWEEP = "last_attachment_sweep"
private const val ATTACHMENT_SWEEP_INTERVAL_MILLIS = 24L * 60 * 60 * 1000
