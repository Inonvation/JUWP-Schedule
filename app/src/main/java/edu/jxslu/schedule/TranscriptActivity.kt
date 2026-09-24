package edu.jxslu.schedule

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import edu.jxslu.schedule.ui.score.TranscriptScreen

/**
 * 导出成绩单独立窗口（DESIGN §3.14）。
 *
 * 为什么不挂在 [SubpageActivity]（其余二级页的统一形态）：这一页里有一个**按需出现的 WebView**
 * 走统一认证登录。转屏会销毁 WebView，密码输到一半就全没了，因此与教务导入窗口一样锁竖屏
 * （DESIGN §4.4 的同一条理由，见 manifest 里的 `screenOrientation`）。锁了方向就没有配置
 * 变更销毁，Compose 侧的状态与 WebView 都不会被重建。
 *
 * 代价是它不进 [SubpageStack] 的「回来还在原来那页」兜底；导出是一次几十秒的短流程，
 * 用完即关，与教务导入窗口的定位一致。
 */
class TranscriptActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 预测性返回（DESIGN §3.1）：34+ 由本窗口声明过渡，系统才会把手势进度交给它
        enablePredictiveBackTransitions()
        setContent {
            JuwRoot {
                TranscriptScreen(onBack = { finish() })
            }
        }
    }

    override fun finish() {
        super.finish()
        applySubpageCloseTransition(this)
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, TranscriptActivity::class.java))
            applySubpageOpenTransition(context)
        }
    }
}
