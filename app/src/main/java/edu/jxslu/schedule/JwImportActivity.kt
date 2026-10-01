package edu.jxslu.schedule

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import edu.jxslu.schedule.ui.jwvw.JwImportMode
import edu.jxslu.schedule.ui.jwvw.JwImportScreen

/**
 * 教务导入独立窗口。
 *
 * 根因：此前 jw_import 与三个 tab 同 NavHost，底栏显隐绑定路由——进入导入页的瞬间
 * 外层 Scaffold padding 变化，切换动画里的课表被突然拉伸。改成独立 Activity：
 * 新窗口直接覆盖，底层课表的布局完全不动；导入写库走 Graph 单例 + 响应式流，
 * 返回后课表自动刷新。Cookie 在 CookieManager 全局生效，登录会话不受影响。
 *
 * [EXTRA_MODE] 决定导入对象：课表（默认）或成绩（DESIGN §4.15）。
 * [EXTRA_START_EXAM] 只对课表模式有意义：**开在考试安排查询页**而不是理论课表页
 * （DESIGN §4.33 考试页的导入入口、考试变动通知的确认口）。
 * [EXTRA_ROUND_ID] 只对选课模式有意义：登录后直达该轮次的「进入选课」页
 * （DESIGN §4.35；轮次 id 过 [edu.jxslu.schedule.data.jw.JwUrls.ROUND_ID_PATTERN] 白名单，
 * 不合法退回选课中心列表）。
 *
 * 动画与 [SubpageActivity] 同款：打开 = 新窗口从右缘推入覆盖主窗口（slide_in_right），
 * 关闭 = 向右滑出（slide_out_right），主窗口全程原地不动。此前该窗口没配转场，
 * 教务导入是从主界面进入频率最高的页面，缺席反而最扎眼。
 */
class JwImportActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 预测性返回（DESIGN §3.1）：34+ 由本窗口声明过渡，系统才会把手势进度交给它
        enablePredictiveBackTransitions()
        val mode = intent.getStringExtra(EXTRA_MODE)
            ?.let { name -> JwImportMode.entries.firstOrNull { it.name == name } }
            ?: JwImportMode.Schedule
        val startAtExam = intent.getBooleanExtra(EXTRA_START_EXAM, false)
        val selectionRoundId = intent.getStringExtra(EXTRA_ROUND_ID)
        setContent {
            JuwRoot {
                JwImportScreen(
                    onBack = { finish() },
                    mode = mode,
                    startAtExam = startAtExam,
                    selectionRoundId = selectionRoundId,
                )
            }
        }
    }

    override fun finish() {
        super.finish()
        applySubpageCloseTransition(this)
    }

    companion object {
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_START_EXAM = "start_exam"
        private const val EXTRA_ROUND_ID = "selection_round_id"

        /**
         * [startAtExam] = true：登录后直接落到 `xsksap_query`（考试安排查询页），
         * 一键导入不会顺带跑起来——从考试页进来的人要的是「导入考试安排」。
         *
         * [selectionRoundId]（选课模式）：登录后直达该轮次的「进入选课」页；
         * 空 = 落选课中心列表（轮次页）。
         */
        fun start(
            context: Context,
            mode: JwImportMode = JwImportMode.Schedule,
            startAtExam: Boolean = false,
            selectionRoundId: String? = null,
        ) {
            val intent = Intent(context, JwImportActivity::class.java)
                .putExtra(EXTRA_MODE, mode.name)
                .putExtra(EXTRA_START_EXAM, startAtExam)
                .putExtra(EXTRA_ROUND_ID, selectionRoundId)
            context.startActivity(intent)
            applySubpageOpenTransition(context)
        }
    }
}
