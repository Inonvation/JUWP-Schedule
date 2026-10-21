package edu.jxslu.schedule

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import edu.jxslu.schedule.ui.me.SettingsScreen
import edu.jxslu.schedule.ui.theme.JuwTheme

/**
 * 设置（2026-10-20）：**独立窗口**，不再是主窗口里的隐藏路由。
 *
 * 为什么要拆出来：主窗口挂着 NavHost + 侧栏 + 底栏，设置页在它里面当路由时，
 * 返回栈 / 状态恢复会和侧栏的开合、底栏的显隐互相干扰（用户实测到 bug）。
 * 设置本来就是「配完就走」的一次性页面，独立窗口的返回语义最干净。
 *
 * 左侧栏点齿轮时**不先收起侧栏**：本窗口直接覆盖，返回后侧栏仍保持展开
 * （2026-10-21 用户口径，见 `.agents/rules/nav-window.md`）。
 *
 * 页面内部的所有入口仍走 `SubpageActivity`，与主窗口那套完全一致。
 */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        enablePredictiveBackTransitions()
        setContent {
            JuwTheme {
                SettingsScreen(
                    onOpenAppearanceSettings = {
                        SubpageActivity.start(this, SubpageScreen.APPEARANCE_SETTINGS)
                    },
                    onOpenFeatureSettings = {
                        SubpageActivity.start(this, SubpageScreen.FEATURE_SETTINGS)
                    },
                    onOpenTimetableHub = {
                        SubpageActivity.start(this, SubpageScreen.TIMETABLE_HUB)
                    },
                    onOpenWidgetCalendarHub = {
                        SubpageActivity.start(this, SubpageScreen.WIDGET_CALENDAR_HUB)
                    },
                    onOpenPermissionsStorage = {
                        SubpageActivity.start(this, SubpageScreen.PERMISSIONS_STORAGE)
                    },
                    onOpenAboutUpdate = {
                        SubpageActivity.start(this, SubpageScreen.ABOUT_UPDATE)
                    },
                    onOpenTerms = {
                        SubpageActivity.start(this, SubpageScreen.TERMS_DECLARATIONS)
                    },
                    onBack = { finish() },
                )
            }
        }
    }

    override fun finish() {
        super.finish()
        applySubpageCloseTransition(this)
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, SettingsActivity::class.java))
        }
    }
}
