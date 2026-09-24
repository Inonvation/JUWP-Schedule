package edu.jxslu.schedule

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import edu.jxslu.schedule.ui.campus.DormRepairScreen

/**
 * 宿舍报修独立窗口（DESIGN §3.15）。
 *
 * 为什么不挂在 [SubpageActivity]（其余二级页的统一形态）：这一页里会随时出现统一认证的
 * 登录表单，转屏销毁 WebView 等于让用户把密码重打一遍。与教务导入（[JwImportActivity]）、
 * 成绩单导出（[TranscriptActivity]）同一条理由，见 manifest 里的 `screenOrientation`。
 *
 * 代价与那两者一样：不进 [SubpageStack] 的「回来还在原来那页」兜底。
 * 报修是「进去填完就走」的流程，与教务导入窗口的定位一致。
 */
class DormRepairActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 预测性返回（DESIGN §3.1）：34+ 由本窗口声明过渡，系统才会把手势进度交给它
        enablePredictiveBackTransitions()
        setContent {
            JuwRoot {
                DormRepairScreen(onBack = { finish() })
            }
        }
    }

    override fun finish() {
        super.finish()
        applySubpageCloseTransition(this)
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, DormRepairActivity::class.java))
            applySubpageOpenTransition(context)
        }
    }
}
