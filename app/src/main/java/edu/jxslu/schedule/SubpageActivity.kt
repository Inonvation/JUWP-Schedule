package edu.jxslu.schedule

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import edu.jxslu.schedule.ui.me.CalendarSettingsScreen
import edu.jxslu.schedule.ui.me.DataSettingsScreen
import edu.jxslu.schedule.ui.me.JwAccountScreen
import edu.jxslu.schedule.ui.me.PermissionSettingsScreen
import edu.jxslu.schedule.ui.me.ReminderSettingsScreen
import edu.jxslu.schedule.ui.me.ShortcutSettingsScreen
import edu.jxslu.schedule.ui.me.TimetableSettingsScreen
import edu.jxslu.schedule.ui.me.WidgetSettingsScreen
import edu.jxslu.schedule.ui.me.hub.AboutScreen
import edu.jxslu.schedule.ui.me.hub.ExtensionServicesHubScreen
import edu.jxslu.schedule.ui.me.hub.GeneralSettingsScreen
import edu.jxslu.schedule.ui.me.hub.LearningHubScreen
import edu.jxslu.schedule.ui.me.hub.TimetableHubScreen
import edu.jxslu.schedule.ui.me.hub.WidgetCalendarHubScreen
import edu.jxslu.schedule.ui.campus.CampusCardSettingsScreen
import edu.jxslu.schedule.ui.campus.PayCodeScreen
import edu.jxslu.schedule.ui.campus.StatementScreen
import edu.jxslu.schedule.ui.ebike.KvcxScreen
import edu.jxslu.schedule.ui.ebike.RideScreen
import edu.jxslu.schedule.ui.life.PowerBillScreen
import edu.jxslu.schedule.ui.homework.HomeworkCourseScreen
import edu.jxslu.schedule.ui.homework.HomeworkDetailScreen
import edu.jxslu.schedule.ui.homework.HomeworkLibraryScreen
import edu.jxslu.schedule.ui.homework.HomeworkTodoScreen
import edu.jxslu.schedule.ui.notes.NoteCourseScreen
import edu.jxslu.schedule.ui.notes.NoteDetailScreen
import edu.jxslu.schedule.ui.notes.NoteLibraryScreen
import edu.jxslu.schedule.ui.score.ScoreScreen
import edu.jxslu.schedule.ui.score.TranscriptHistoryScreen
import edu.jxslu.schedule.ui.qzxy.QzxyScreen
import edu.jxslu.schedule.ui.qzxy.QzxyDebugScreen
import edu.jxslu.schedule.ui.scholar.ScholarScreen
import edu.jxslu.schedule.ui.timetable.TimetableManageScreen
import edu.jxslu.schedule.ui.tweak.CourseTweakScreen
import edu.jxslu.schedule.ui.water.WaterScreen

/** 二级页种类；通过 extra 传给 [SubpageActivity]，值必须与 enum 名一致。 */
enum class SubpageScreen {
    /** 我的 → 课表管理 */
    TIMETABLE_MANAGE,
    /** 我的 → 课表设置（学期 · 作息） */
    TIMETABLE_SETTINGS,
    /** 我的 → 课表数据 */
    DATA_SETTINGS,
    /** 我的 → 调课（快捷操作，DESIGN §4.11） */
    COURSE_TWEAK,
    /** 我的/今日 → 胖乖生活（开水卡显示与点击方式并入页内，DESIGN §3.4） */
    WATER,
    /** 今日 → 趣智校园开热水（DESIGN §4.30；与胖乖生活并排的半行卡） */
    QZXY,
    /** 趣智校园 → 诊断与调试（DESIGN §3.18；设备现场 · 服务表 · 试签名 · 日志） */
    QZXY_DEBUG,
    /** 我的 → 桌面小组件（DESIGN §3.6） */
    WIDGET_SETTINGS,
    /** 我的 → 权限设置（电池优化 · 自启动/锁后台 · 通知，DESIGN §3.12） */
    PERMISSION_SETTINGS,
    /** 我的 → 日历同步（提醒时长 · 一键删除，DESIGN §4.12） */
    CALENDAR_SETTINGS,
    /** 我的 → 上课提醒（DESIGN §3.7） */
    REMINDER_SETTINGS,
    /** 我的 → 快捷方式（DESIGN §3.8） */
    SHORTCUTS,
    /** 我的 → 成绩查询（按学期存储，DESIGN §4.15） */
    SCORES,
    /** 我的 → 学习 → 学业完成情况（培养方案达成度，DESIGN §3.17 / §4.29） */
    SCHOLAR,
    /** 导出成绩单 → 最近导出（DESIGN §3.14；列表即 filesDir/transcripts，最多 10 份） */
    TRANSCRIPTS,
    /** 今日 → 快趣出行 · 骑行页（DESIGN §3.9；地图 + 抽屉三态，两档共用一套结构） */
    RIDE,
    /** 我的 → 校园卡付款码设置（开关 · 凭证，DESIGN §3.10） */
    CAMPUS_CARD_SETTINGS,
    /** 我的 → 教务账户（原生信息页，DESIGN §3.3；不再一点就开导入 WebView） */
    JW_ACCOUNT,
    /** 我的 → 通用设置汇总页（主题 · 触感 · 布局 · 权限入口，DESIGN §3.3） */
    GENERAL_SETTINGS,
    /** 我的 → 课表汇总页（配置 · 使用 · 数据，DESIGN §3.3） */
    TIMETABLE_HUB,
    /** 我的 → 学习汇总页（笔记·课件 / 作业，DESIGN §3.3） */
    LEARNING_HUB,
    /** 我的 → 提醒与桌面汇总页（2026-09-28 自「小组件与日历」改名，DESIGN §3.3） */
    WIDGET_CALENDAR_HUB,
    /** 我的 → 校园服务汇总页（2026-09-28 自「扩展服务」改名瘦身，DESIGN §3.3） */
    EXT_SERVICES_HUB,
    /** 我的 → 关于页（版本 · 免责 · 仓库 · 权限入口，DESIGN §3.3） */
    ABOUT,
    /** 今日 → 校园卡付款码展示页（DESIGN §3.10；FLAG_SECURE 独立窗口） */
    PAY_CODE,
    /** 付款码/设置 → 消费流水（月汇总 + 分页列表，DESIGN §4.19 B4） */
    CAMPUS_STATEMENT,
    /** 生活页 → 缴费账单（寝室电费充值/退款，按月汇总 + 明细，DESIGN §3.13） */
    POWER_BILL,
    /** 我的 → 笔记·课件库（按课程分组，DESIGN §3.11） */
    NOTES,
    /** 某课程的笔记列表（需 `courseName`，DESIGN §3.11） */
    NOTES_COURSE,
    /** 笔记详情/编辑（需 `courseName` + `itemId`，itemId=0 表示新建，DESIGN §3.11） */
    NOTE_DETAIL,
    /** 我的 → 作业库（按课程分组） */
    HOMEWORK,
    /** 某课程的作业列表（需 `courseName`） */
    HOMEWORK_COURSE,
    /** 作业详情/编辑（需 `courseName` + `itemId`，itemId=0 表示新建） */
    HOMEWORK_DETAIL,
    /** 今日页作业卡/截止提醒 → 作业中心（未完成汇总，DESIGN §3.11） */
    HOMEWORK_TODO,
    /** 我的 → 校园服务 → 快趣出行（登录 · 骑行状态只读查询，DESIGN §4.32） */
    KVCX,
}

/**
 * 设置类二级页的统一独立窗口（与 [JwImportActivity] 同形态）。
 *
 * 根因：这些二级页此前与三个 tab 同 NavHost，底栏在子页上仍然可达，
 * 切 tab 会把子页和主界面栈混在一起（返回栈语义含糊，且子页期间改数据可能撞上
 * 正在重组的主界面）。改为新窗口覆盖：底栏天然不可达，返回键/页内返回都只是关窗口。
 * 数据仍走 Graph 单例 + 响应式流，窗口关闭后主界面自动刷新。
 *
 * 动画（res/anim，平台只有 fade 与左滑资源，右推入必须自备）：
 * 打开 = 新窗口从右缘平移推入覆盖主窗口（slide_in_right，主窗口 stay_still、原地不动）；
 * 关闭（页内返回或系统返回）= 向右滑出露出主窗口（slide_out_right），统一从
 * [finish] 出口生效。打开侧由 [start] 出口触发，三个主 Tab 与全部二级页同一规格。
 */
class SubpageActivity : ComponentActivity() {

    /**
     * 本窗口的定位参数。onCreate 解析一次，[onResume] / [finish] 拿它记账
     * （见 [SubpageStack]）。用 `by lazy` 不用 lateinit：解析只发生在 intents 上，
     * 与 onCreate 的读取口径必须完全一致，写成两处迟早跑偏。
     */
    private val request: SubpageRequest by lazy {
        // 脏 extra 回退到第一个入口：宁可开对一半的页，也不要崩溃
        SubpageRequest.from(intent) ?: SubpageRequest(SubpageScreen.TIMETABLE_MANAGE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = request
        SubpageStack.onWindowCreated(request)
        // 小组件「去开水」直达令牌（WaterAutoStart）：进页前武装、本窗口首帧消费。
        // 在 composable 外消费一次——组合重组再读会拿到恒定值，消费语义就没了
        val autoStart = WaterAutoStart.consume(request.screen)
        enableEdgeToEdge()
        // 预测性返回（DESIGN §3.1）：34+ 由本窗口声明过渡，系统才会把手势进度交给它
        enablePredictiveBackTransitions()
        setContent {
            JuwRoot {
                SubpageContent(
                    screen = request.screen,
                    onBack = { finish() },
                    focusItemId = request.focusItemId,
                    courseName = request.courseName,
                    itemId = request.itemId,
                    autoStart = autoStart,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 本页成为「离开 App 时看到的页」，见 SubpageStack 的类 KDoc
        SubpageStack.onWindowResumed()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 从外部应用（微信 / 浏览器 / 系统设置…）回来时，要等返回过渡放完、窗口重新
        // 拿到焦点，才能还原推入/滑出覆盖——更早还原会把拉起前换上的静止过渡又改回去，
        // 被抑制的右推入就重回返回动画（见 WindowTransitions.startActivityOutsideApp）。
        // 平时焦点恢复（系统弹框关掉、上层二级页关闭）只是重复声明同样的覆盖，无副作用。
        if (hasFocus) enablePredictiveBackTransitions()
    }

    override fun onDestroy() {
        SubpageStack.onWindowDestroyed(request)
        super.onDestroy()
    }

    @Composable
    private fun SubpageContent(
        screen: SubpageScreen,
        onBack: () -> Unit,
        focusItemId: String? = null,
        courseName: String? = null,
        itemId: Long = 0L,
        /** 小组件「去开水」直达：仅 WATER / QZXY 两个页面响应，见 [WaterAutoStart]。 */
        autoStart: Boolean = false,
    ) {
        when (screen) {
            SubpageScreen.TIMETABLE_MANAGE -> TimetableManageScreen(onBack = onBack)
            SubpageScreen.TIMETABLE_SETTINGS -> TimetableSettingsScreen(onBack = onBack)
            SubpageScreen.DATA_SETTINGS -> DataSettingsScreen(onBack = onBack)
            SubpageScreen.COURSE_TWEAK -> CourseTweakScreen(onBack = onBack)
            SubpageScreen.WATER -> WaterScreen(onBack = onBack, autoStart = autoStart)
            SubpageScreen.KVCX -> KvcxScreen(onBack = onBack)
            SubpageScreen.QZXY -> QzxyScreen(
                onBack = onBack,
                onOpenDiagnostics = { address ->
                    SubpageActivity.start(this, SubpageScreen.QZXY_DEBUG, focusItemId = address)
                },
                autoStart = autoStart,
            )
            SubpageScreen.QZXY_DEBUG -> QzxyDebugScreen(
                focusAddress = focusItemId,
                onBack = onBack,
            )
            SubpageScreen.WIDGET_SETTINGS -> WidgetSettingsScreen(onBack = onBack)
            SubpageScreen.PERMISSION_SETTINGS -> PermissionSettingsScreen(onBack = onBack)
            SubpageScreen.CALENDAR_SETTINGS -> CalendarSettingsScreen(onBack = onBack)
            SubpageScreen.REMINDER_SETTINGS -> ReminderSettingsScreen(onBack = onBack)
            SubpageScreen.SHORTCUTS ->
                ShortcutSettingsScreen(onBack = onBack, focusItemId = focusItemId)
            SubpageScreen.SCORES -> ScoreScreen(onBack = onBack)
            SubpageScreen.SCHOLAR -> ScholarScreen(onBack = onBack)
            SubpageScreen.TRANSCRIPTS -> TranscriptHistoryScreen(onBack = onBack)
            // focusItemId 对 RIDE 复用为「进页即定位的车号」（2026-09-29）：识别 / 深链
            // 带车号进来，第一笔查询找到就高亮定位（不轮询）
            SubpageScreen.RIDE -> RideScreen(onBack = onBack, initialFocusCarNum = focusItemId)
            SubpageScreen.CAMPUS_CARD_SETTINGS -> CampusCardSettingsScreen(
                onBack = onBack,
                onOpenStatement = { SubpageActivity.start(this, SubpageScreen.CAMPUS_STATEMENT) },
                onOpenPayCode = { SubpageActivity.start(this, SubpageScreen.PAY_CODE) },
            )
            SubpageScreen.JW_ACCOUNT -> JwAccountScreen(onBack = onBack)
            SubpageScreen.LEARNING_HUB -> LearningHubScreen(
                onBack = onBack,
                onOpenNotes = { SubpageActivity.start(this, SubpageScreen.NOTES) },
                onOpenHomework = { SubpageActivity.start(this, SubpageScreen.HOMEWORK) },
                onOpenScores = { SubpageActivity.start(this, SubpageScreen.SCORES) },
                onOpenScholar = { SubpageActivity.start(this, SubpageScreen.SCHOLAR) },
            )
            SubpageScreen.TIMETABLE_HUB -> TimetableHubScreen(
                onBack = onBack,
                onOpenJwImport = { JwImportActivity.start(this) },
                onOpenTimetableManage = {
                    SubpageActivity.start(this, SubpageScreen.TIMETABLE_MANAGE)
                },
                onOpenTimetableSettings = {
                    SubpageActivity.start(this, SubpageScreen.TIMETABLE_SETTINGS)
                },
                onOpenDataSettings = { SubpageActivity.start(this, SubpageScreen.DATA_SETTINGS) },
                onOpenCourseTweak = { SubpageActivity.start(this, SubpageScreen.COURSE_TWEAK) },
            )
            SubpageScreen.GENERAL_SETTINGS -> GeneralSettingsScreen(
                onBack = onBack,
                // 功能开关节 → 快捷方式编辑页（2026-09-28 自扩展服务迁入）
                onOpenShortcuts = { SubpageActivity.start(this, SubpageScreen.SHORTCUTS) },
            )
            SubpageScreen.WIDGET_CALENDAR_HUB -> WidgetCalendarHubScreen(
                onBack = onBack,
                onOpenWidgetSettings = {
                    SubpageActivity.start(this, SubpageScreen.WIDGET_SETTINGS)
                },
                onOpenCalendarSettings = {
                    SubpageActivity.start(this, SubpageScreen.CALENDAR_SETTINGS)
                },
                // 上课提醒 2026-09-28 自课表 hub 迁入（与小组件、日历同属「课表送达渠道」）
                onOpenReminderSettings = {
                    SubpageActivity.start(this, SubpageScreen.REMINDER_SETTINGS)
                },
                onOpenPermissionSettings = {
                    SubpageActivity.start(this, SubpageScreen.PERMISSION_SETTINGS)
                },
            )
            SubpageScreen.EXT_SERVICES_HUB -> ExtensionServicesHubScreen(
                onBack = onBack,
                onOpenCampusCard = {
                    SubpageActivity.start(this, SubpageScreen.CAMPUS_CARD_SETTINGS)
                },
                onOpenWater = { SubpageActivity.start(this, SubpageScreen.WATER) },
                onOpenQzxy = { SubpageActivity.start(this, SubpageScreen.QZXY) },
                onOpenKvcx = { SubpageActivity.start(this, SubpageScreen.KVCX) },
                // 学工表单走独立窗口（DESIGN §3.15）：页里有统一认证表单，需要锁竖屏
                onOpenXgForm = { form -> XgFormActivity.start(this, form) },
            )
            SubpageScreen.ABOUT -> AboutScreen(
                onBack = onBack,
                onOpenPermissionSettings = {
                    SubpageActivity.start(this, SubpageScreen.PERMISSION_SETTINGS)
                },
            )
            SubpageScreen.PAY_CODE -> PayCodeScreen(
                onBack = onBack,
                onOpenStatement = { SubpageActivity.start(this, SubpageScreen.CAMPUS_STATEMENT) },
            )
            SubpageScreen.CAMPUS_STATEMENT -> StatementScreen(
                onBack = onBack,
                onOpenSettings = { SubpageActivity.start(this, SubpageScreen.CAMPUS_CARD_SETTINGS) },
            )
            SubpageScreen.POWER_BILL -> PowerBillScreen(
                onBack = onBack,
                onOpenSettings = { SubpageActivity.start(this, SubpageScreen.CAMPUS_CARD_SETTINGS) },
            )
            // 笔记·课件（DESIGN §3.11）：课程库 → 课程列表 → 详情/编辑
            SubpageScreen.NOTES -> NoteLibraryScreen(
                onBack = onBack,
                onOpenNote = { name, id ->
                    SubpageActivity.start(
                        this,
                        SubpageScreen.NOTE_DETAIL,
                        courseName = name,
                        itemId = id,
                    )
                },
            )
            SubpageScreen.NOTES_COURSE -> NoteCourseScreen(
                courseName = courseName.orEmpty(),
                onBack = onBack,
                onOpenNote = { id ->
                    SubpageActivity.start(
                        this,
                        SubpageScreen.NOTE_DETAIL,
                        courseName = courseName,
                        itemId = id,
                    )
                },
            )
            SubpageScreen.NOTE_DETAIL -> NoteDetailScreen(
                courseName = courseName.orEmpty(),
                noteId = itemId,
                onBack = onBack,
            )
            // 作业：课程库 → 课程列表 → 详情/编辑 + 作业中心
            SubpageScreen.HOMEWORK -> HomeworkLibraryScreen(
                onBack = onBack,
                onOpenHomework = { name, id ->
                    SubpageActivity.start(
                        this,
                        SubpageScreen.HOMEWORK_DETAIL,
                        courseName = name,
                        itemId = id,
                    )
                },
            )
            SubpageScreen.HOMEWORK_COURSE -> HomeworkCourseScreen(
                courseName = courseName.orEmpty(),
                onBack = onBack,
                onOpenHomework = { id ->
                    SubpageActivity.start(
                        this,
                        SubpageScreen.HOMEWORK_DETAIL,
                        courseName = courseName,
                        itemId = id,
                    )
                },
            )
            SubpageScreen.HOMEWORK_DETAIL -> HomeworkDetailScreen(
                courseName = courseName.orEmpty(),
                homeworkId = itemId,
                onBack = onBack,
            )
            SubpageScreen.HOMEWORK_TODO -> HomeworkTodoScreen(
                onBack = onBack,
                onOpenHomework = { name, id ->
                    SubpageActivity.start(
                        this,
                        SubpageScreen.HOMEWORK_DETAIL,
                        courseName = name,
                        itemId = id,
                    )
                },
                onOpenLibrary = { SubpageActivity.start(this, SubpageScreen.HOMEWORK) },
            )
        }
    }

    override fun finish() {
        // 用户主动关窗（页内返回 / 系统返回键 / 扫码完成自动退出）才从记录里摘掉。
        // clearTop 与系统回收不走这里，记录必须留着，否则恢复不了。
        SubpageStack.onWindowFinished(request)
        super.finish()
        // 顶层窗口向右滑出；露出的主窗口原地不动（stay_still / ≤33 的 legacy 路径传 0）。
        // 34+ 由 enablePredictiveBackTransitions 声明，这里不碰旧 API
        applySubpageCloseTransition(this)
    }

    companion object {
        /**
         * [focusItemId] 只对 [SubpageScreen.SHORTCUTS] 生效：非空时设置页打开后
         * 直接展开该条目的编辑弹层（Snackbar「去设置」的就近修正闭环）。
         *
         * [courseName] / [itemId] 只对笔记·作业的二级页生效（DESIGN §3.11）：
         * 前者是归属课程名（课程库 → 课程列表的必带参数），后者是条目 id（0 = 新建）。
         */
        fun start(
            context: Context,
            screen: SubpageScreen,
            focusItemId: String? = null,
            courseName: String? = null,
            itemId: Long = 0L,
        ) {
            openSubpage(
                context,
                SubpageRequest(
                    screen = screen,
                    focusItemId = focusItemId,
                    courseName = courseName,
                    itemId = itemId,
                ),
            )
        }
    }
}
