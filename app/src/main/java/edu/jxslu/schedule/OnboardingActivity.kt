package edu.jxslu.schedule

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import edu.jxslu.schedule.ui.onboarding.OnboardingScreen

/**
 * 首次配置引导窗口（DESIGN §3.16）。
 *
 * 独立窗口、**不进 NavHost 路由**：首启时主窗口还没有数据，引导走完要整窗退场，
 * 在可返回的导航图里加一步会让「返回」的语义变得可疑。锁竖屏与教务导入 / 成绩单 /
 * 学工表单同一理由——第 2 步会带出统一认证表单，转屏销毁 WebView 等于让用户重打密码。
 *
 * 由 [MainActivity] 在 `onboarding_seen` 为 false 时拉起，**只服务新安装**：老用户
 * 没有这个键，也不该被重新引导一遍。
 *
 * 也能被**重复进入**（「我的」页账户卡在登录失效时点进去）：那时直接落在「学校统一认证」
 * 那一步，只改密码，不走欢迎页。
 */
class OnboardingActivity : ComponentActivity() {

    /** 是否直接落在统一认证那一步（重复进入改密码用）。 */
    private val startAtJw: Boolean by lazy { intent.getBooleanExtra(EXTRA_START_AT_JW, false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        liveInstances++
        enableEdgeToEdge()
        setContent {
            JuwRoot {
                OnboardingScreen(onFinish = { finish() }, startAtJw = startAtJw)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        liveInstances--
    }

    companion object {
        private const val EXTRA_START_AT_JW = "start_at_jw"

        /**
         * 本进程内活着的引导实例数（onCreate / onDestroy 夹逼，回调全在主线程）。
         * MainActivity 拉新窗口前看它：>0 说明任务里还有一层没退场的引导，此时再把
         * 主窗口叠上来，引导完成后一按返回就会露出这具「僵尸引导」（2026-09-28 真机
         * 踩过：任务叠成 [主窗口1, 引导1, 主窗口2, 引导2]，完成的是上面一对，返回
         * 露出引导1 的欢迎页）。
         */
        internal var liveInstances = 0
            private set

        fun start(context: Context, startAtJw: Boolean = false) {
            context.startActivity(
                Intent(context, OnboardingActivity::class.java)
                    // 引导已在任务里时提到前台复用，别再叠一个新实例（正常路径到不了
                    // 这里——活着的引导挡住一切入口；这层是兜底）。REORDER 不回传 intent，
                    // 所以 startAtJw 只在「没有活实例」的常规路径生效。
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    .putExtra(EXTRA_START_AT_JW, startAtJw),
            )
        }
    }
}
