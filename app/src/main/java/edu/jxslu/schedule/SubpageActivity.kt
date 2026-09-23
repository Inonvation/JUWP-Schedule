package edu.jxslu.schedule

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import edu.jxslu.schedule.ui.me.CalendarSettingsScreen
import edu.jxslu.schedule.ui.me.DataSettingsScreen
import edu.jxslu.schedule.ui.me.PermissionSettingsScreen
import edu.jxslu.schedule.ui.me.ReminderSettingsScreen
import edu.jxslu.schedule.ui.me.ShortcutSettingsScreen
import edu.jxslu.schedule.ui.me.TimetableSettingsScreen
import edu.jxslu.schedule.ui.me.WidgetSettingsScreen
import edu.jxslu.schedule.ui.campus.CampusCardSettingsScreen
import edu.jxslu.schedule.ui.campus.PayCodeScreen
import edu.jxslu.schedule.ui.campus.StatementScreen
import edu.jxslu.schedule.ui.detect.ScheduleUpdateScreen
import edu.jxslu.schedule.ui.detect.TweakDetectScreen
import edu.jxslu.schedule.ui.ebike.EbikeQrScreen
import edu.jxslu.schedule.ui.ebike.BikeMapScreen
import edu.jxslu.schedule.ui.homework.HomeworkCourseScreen
import edu.jxslu.schedule.ui.homework.HomeworkDetailScreen
import edu.jxslu.schedule.ui.homework.HomeworkLibraryScreen
import edu.jxslu.schedule.ui.homework.HomeworkTodoScreen
import edu.jxslu.schedule.ui.notes.NoteCourseScreen
import edu.jxslu.schedule.ui.notes.NoteDetailScreen
import edu.jxslu.schedule.ui.notes.NoteLibraryScreen
import edu.jxslu.schedule.ui.score.ScoreScreen
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
    /** 我的/今日 → 胖乖生活一键开水（开水卡显示与点击方式并入页内，DESIGN §3.4） */
    WATER,
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
    /** 我的 → 调课自动检测（开关 · 周期 · 凭证，DESIGN §4.17） */
    TWEAK_DETECT,
    /** 调课检测的「更新课表」页（差异勾选合并流程，DESIGN §4.17；通知与气泡直达） */
    SCHEDULE_UPDATE,
    /** 今日 → 共享单车出码（DESIGN §3.9；独立窗口承载二维码展示） */
    EBIKE,
    /** 今日 → 附近单车地图（DESIGN §3.9；从出码页进，选中的车号回填出码页） */
    EBIKE_MAP,
    /** 我的 → 校园卡付款码设置（开关 · 凭证，DESIGN §3.10） */
    CAMPUS_CARD_SETTINGS,
    /** 今日 → 校园卡付款码展示页（DESIGN §3.10；FLAG_SECURE 独立窗口） */
    PAY_CODE,
    /** 付款码/设置 → 消费流水（月汇总 + 分页列表，DESIGN §4.19 B4） */
    CAMPUS_STATEMENT,
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
 * 打开 = 新窗口从右缘平移推入覆盖主窗口（slide_in_right，主窗口不传动画、原地不动）；
 * 关闭（页内返回或系统返回）= 向右滑出露出主窗口（slide_out_right），统一从
 * [finish] 出口生效。打开侧由 [start] 出口触发，三个主 Tab 与全部二级页同一规格。
 */
class SubpageActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 脏 extra 回退到第一个入口：宁可开对一半的页，也不要崩溃
        val screen = intent.getStringExtra(EXTRA_SCREEN)
            ?.let { name -> SubpageScreen.entries.firstOrNull { it.name == name } }
            ?: SubpageScreen.TIMETABLE_MANAGE
        // 快捷方式 Snackbar「去设置」带的定位 id（只对 SHORTCUTS 有意义，其他页忽略）
        val focusItemId = intent.getStringExtra(EXTRA_FOCUS_ITEM)
        // 笔记/作业的定位参数（DESIGN §3.11）：课程名 + 条目 id（0 = 新建）
        val courseName = intent.getStringExtra(EXTRA_COURSE_NAME)
        val itemId = intent.getLongExtra(EXTRA_ITEM_ID, 0L)
        setContent {
            JuwRoot {
                SubpageContent(
                    screen = screen,
                    onBack = { finish() },
                    focusItemId = focusItemId,
                    courseName = courseName,
                    itemId = itemId,
                )
            }
        }
    }

    @Composable
    private fun SubpageContent(
        screen: SubpageScreen,
        onBack: () -> Unit,
        focusItemId: String? = null,
        courseName: String? = null,
        itemId: Long = 0L,
    ) {
        when (screen) {
            SubpageScreen.TIMETABLE_MANAGE -> TimetableManageScreen(onBack = onBack)
            SubpageScreen.TIMETABLE_SETTINGS -> TimetableSettingsScreen(onBack = onBack)
            SubpageScreen.DATA_SETTINGS -> DataSettingsScreen(onBack = onBack)
            SubpageScreen.COURSE_TWEAK -> CourseTweakScreen(onBack = onBack)
            SubpageScreen.WATER -> WaterScreen(onBack = onBack)
            SubpageScreen.WIDGET_SETTINGS -> WidgetSettingsScreen(onBack = onBack)
            SubpageScreen.PERMISSION_SETTINGS -> PermissionSettingsScreen(onBack = onBack)
            SubpageScreen.CALENDAR_SETTINGS -> CalendarSettingsScreen(onBack = onBack)
            SubpageScreen.REMINDER_SETTINGS -> ReminderSettingsScreen(onBack = onBack)
            SubpageScreen.SHORTCUTS ->
                ShortcutSettingsScreen(onBack = onBack, focusItemId = focusItemId)
            SubpageScreen.SCORES -> ScoreScreen(onBack = onBack)
            SubpageScreen.TWEAK_DETECT -> TweakDetectScreen(
                onBack = onBack,
                onOpenScheduleUpdate = { SubpageActivity.start(this, SubpageScreen.SCHEDULE_UPDATE) },
            )
            SubpageScreen.SCHEDULE_UPDATE -> ScheduleUpdateScreen(onBack = onBack)
            SubpageScreen.EBIKE -> EbikeQrScreen(onBack = onBack)
            SubpageScreen.EBIKE_MAP -> BikeMapScreen(
                onBack = onBack,
                onPicked = { carNum ->
                    // 选中的车号用 Activity Result 回传，**不走进程级单例**：
                    // 单例会被任何一个还活着的出码页实例抢先消费掉（被退到后台那个也算），
                    // 结果是用户眼前这一页空手而归（2026-09-23 实测）。
                    setResult(
                        RESULT_OK,
                        Intent().putExtra(EXTRA_PICKED_CAR_NUM, carNum),
                    )
                    onBack()
                },
            )
            SubpageScreen.CAMPUS_CARD_SETTINGS -> CampusCardSettingsScreen(
                onBack = onBack,
                onOpenStatement = { SubpageActivity.start(this, SubpageScreen.CAMPUS_STATEMENT) },
                onOpenPayCode = { SubpageActivity.start(this, SubpageScreen.PAY_CODE) },
            )
            SubpageScreen.PAY_CODE -> PayCodeScreen(
                onBack = onBack,
                onOpenStatement = { SubpageActivity.start(this, SubpageScreen.CAMPUS_STATEMENT) },
            )
            SubpageScreen.CAMPUS_STATEMENT -> StatementScreen(
                onBack = onBack,
                onOpenSettings = { SubpageActivity.start(this, SubpageScreen.CAMPUS_CARD_SETTINGS) },
            )
            // 笔记·课件（DESIGN §3.11）：课程库 → 课程列表 → 详情/编辑
            SubpageScreen.NOTES -> NoteLibraryScreen(
                onBack = onBack,
                onOpenCourse = { name ->
                    SubpageActivity.start(this, SubpageScreen.NOTES_COURSE, courseName = name)
                },
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
                onOpenCourse = { name ->
                    SubpageActivity.start(this, SubpageScreen.HOMEWORK_COURSE, courseName = name)
                },
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
        super.finish()
        @Suppress("DEPRECATION") // API 34+ 的 overrideActivityTransition 需要 34 才可用，minSdk 26 仍走这条
        // 顶层窗口向右滑出；入场传 0 = 露出的主窗口原地不动（覆盖语义）
        overridePendingTransition(0, R.anim.slide_out_right)
    }

    companion object {
        private const val EXTRA_SCREEN = "screen"

        /**
         * 附近单车地图选中的车号，**作为 Activity Result 回传**（DESIGN §3.9）。
         *
         * 别改回进程级单例：那种通道会被任何一个还活着的出码页实例抢先消费掉，
         * 用户眼前这一页反而收不到（2026-09-23 真机排查）。
         */
        const val EXTRA_PICKED_CAR_NUM = "picked_car_num"
        private const val EXTRA_FOCUS_ITEM = "focus_item"
        private const val EXTRA_COURSE_NAME = "course_name"
        private const val EXTRA_ITEM_ID = "item_id"

        /**
         * 通知 PendingIntent 用：只构造意图，不启动（start 里的窗口动画对非 Activity 无意义）。
         * 带参与 [start] 同口径——通知点击直达某条笔记/作业就靠它（DESIGN §3.11）。
         */
        fun intent(
            context: Context,
            screen: SubpageScreen,
            courseName: String? = null,
            itemId: Long = 0L,
        ): Intent = Intent(context, SubpageActivity::class.java)
            .putExtra(EXTRA_SCREEN, screen.name)
            .apply {
                if (courseName != null) putExtra(EXTRA_COURSE_NAME, courseName)
                if (itemId != 0L) putExtra(EXTRA_ITEM_ID, itemId)
            }

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
            val intent = intent(context, screen, courseName, itemId)
            if (focusItemId != null) intent.putExtra(EXTRA_FOCUS_ITEM, focusItemId)
            context.startActivity(intent)
            // 新窗口从右缘推入；退场传 0 = 主窗口原地不动，被覆盖而非被推走。
            // 只有 context 是 Activity 时才有窗口动画可言
            (context as? Activity)?.let {
                @Suppress("DEPRECATION")
                it.overridePendingTransition(R.anim.slide_in_right, 0)
            }
        }
    }
}
