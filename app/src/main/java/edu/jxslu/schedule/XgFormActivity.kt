package edu.jxslu.schedule

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import edu.jxslu.schedule.data.xg.XgForm
import edu.jxslu.schedule.data.xg.XgUrls
import edu.jxslu.schedule.ui.campus.XgFormScreen
import edu.jxslu.schedule.ui.reminder.DormRepairReminder
import kotlinx.coroutines.launch

/**
 * 学工表单独立窗口（DESIGN §3.15）：宿舍报修、请假等共用。
 *
 * 为什么不挂在 [SubpageActivity]（其余二级页的统一形态）：这一页里会随时出现统一认证的
 * 登录表单，转屏销毁 WebView 等于让用户把密码重打一遍。与教务导入（[JwImportActivity]）、
 * 成绩单导出（[TranscriptActivity]）同一条理由，见 manifest 里的 `screenOrientation`。
 *
 * 代价与那两者一样：不进 [SubpageStack] 的「回来还在原来那页」兜底。
 * 学工表单是「进去填完就走」的流程，与教务导入窗口的定位一致。
 */
class XgFormActivity : ComponentActivity() {

    /**
     * 本窗口展示哪张表单。`by lazy` 与 [SubpageActivity] 同款：解析只发生一次，
     * 与 onCreate 的读取口径必须完全一致。脏 extra 回退到报修，宁可开对一半的页也别崩。
     */
    private val form: XgForm by lazy {
        XgUrls.formById(intent.getStringExtra(EXTRA_FORM_ID)) ?: XgUrls.REPAIR
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 预测性返回（DESIGN §3.1）：34+ 由本窗口声明过渡，系统才会把手势进度交给它
        enablePredictiveBackTransitions()
        setContent {
            JuwRoot {
                XgFormScreen(form = form, onBack = { finish() })
            }
        }
        // 报修进度（DESIGN §4.49）：用户用过报修一次，之后 App 打开检查才持续运行；
        // 只写本机快照的 tracking 标记，不碰 WebView 里的任何表单数据。
        if (form.id == XgUrls.REPAIR.id) {
            Graph.appScope.launch {
                runCatching { Graph.dormRepairSync(applicationContext).onRepairWindowOpened() }
            }
        }
    }

    override fun finish() {
        // 关闭报修窗口（可能刚提交完）：延迟刷新一次，让今日页卡片尽快拿到新记录。
        // 用 applicationContext，后台协程不持有 Activity。
        if (form.id == XgUrls.REPAIR.id) {
            Graph.appScope.launch {
                runCatching { DormRepairReminder.onRepairWindowClosed(applicationContext) }
            }
        }
        super.finish()
        applySubpageCloseTransition(this)
    }

    companion object {
        private const val EXTRA_FORM_ID = "form_id"

        fun start(context: Context, form: XgForm) {
            context.startActivity(
                Intent(context, XgFormActivity::class.java)
                    .putExtra(EXTRA_FORM_ID, form.id),
            )
            applySubpageOpenTransition(context)
        }
    }
}
