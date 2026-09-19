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
import edu.jxslu.schedule.ui.me.ReminderSettingsScreen
import edu.jxslu.schedule.ui.me.TimetableSettingsScreen
import edu.jxslu.schedule.ui.me.WidgetSettingsScreen
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
    /** 我的/今日 → 胖乖开水 */
    WATER,
    /** 我的 → 桌面小组件（DESIGN §3.6） */
    WIDGET_SETTINGS,
    /** 我的 → 日历同步（提醒时长 · 一键删除，DESIGN §4.12） */
    CALENDAR_SETTINGS,
    /** 我的 → 上课提醒（DESIGN §3.7） */
    REMINDER_SETTINGS,
    /** 我的 → 成绩查询（按学期存储，DESIGN §4.15） */
    SCORES,
}

/**
 * 设置类二级页的统一独立窗口（与 [JwImportActivity] 同形态）。
 *
 * 根因：这些二级页此前与三个 tab 同 NavHost，底栏在子页上仍然可达，
 * 切 tab 会把子页和主界面栈混在一起（返回栈语义含糊，且子页期间改数据可能撞上
 * 正在重组的主界面）。改为新窗口覆盖：底栏天然不可达，返回键/页内返回都只是关窗口。
 * 数据仍走 Graph 单例 + 响应式流，窗口关闭后主界面自动刷新。
 *
 * 动画：打开=淡入（平台只有 fade 与左滑资源，无 slide_in_right）；关闭（页内返回或
 * 系统返回）= 右侧退出（fade_in + slide_out_right），统一从 [finish] 出口生效。
 */
class SubpageActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 脏 extra 回退到第一个入口：宁可开对一半的页，也不要崩溃
        val screen = intent.getStringExtra(EXTRA_SCREEN)
            ?.let { name -> SubpageScreen.entries.firstOrNull { it.name == name } }
            ?: SubpageScreen.TIMETABLE_MANAGE
        setContent {
            JuwRoot {
                SubpageContent(screen, onBack = { finish() })
            }
        }
    }

    @Composable
    private fun SubpageContent(screen: SubpageScreen, onBack: () -> Unit) {
        when (screen) {
            SubpageScreen.TIMETABLE_MANAGE -> TimetableManageScreen(onBack = onBack)
            SubpageScreen.TIMETABLE_SETTINGS -> TimetableSettingsScreen(onBack = onBack)
            SubpageScreen.DATA_SETTINGS -> DataSettingsScreen(onBack = onBack)
            SubpageScreen.COURSE_TWEAK -> CourseTweakScreen(onBack = onBack)
            SubpageScreen.WATER -> WaterScreen(onBack = onBack)
            SubpageScreen.WIDGET_SETTINGS -> WidgetSettingsScreen(onBack = onBack)
            SubpageScreen.CALENDAR_SETTINGS -> CalendarSettingsScreen(onBack = onBack)
            SubpageScreen.REMINDER_SETTINGS -> ReminderSettingsScreen(onBack = onBack)
            SubpageScreen.SCORES -> ScoreScreen(onBack = onBack)
        }
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION") // API 34+ 的 overrideActivityTransition 需要 34 才可用，minSdk 26 仍走这条
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.slide_out_right)
    }

    companion object {
        private const val EXTRA_SCREEN = "screen"

        fun start(context: Context, screen: SubpageScreen) {
            val intent = Intent(context, SubpageActivity::class.java)
                .putExtra(EXTRA_SCREEN, screen.name)
            context.startActivity(intent)
            // 主窗口退场动画：只有 context 是 Activity 时才有窗口动画可言
            (context as? Activity)?.let {
                @Suppress("DEPRECATION")
                it.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            }
        }
    }
}
