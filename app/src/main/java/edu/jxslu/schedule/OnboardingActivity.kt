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
        enableEdgeToEdge()
        setContent {
            JuwRoot {
                OnboardingScreen(onFinish = { finish() }, startAtJw = startAtJw)
            }
        }
    }

    companion object {
        private const val EXTRA_START_AT_JW = "start_at_jw"

        fun start(context: Context, startAtJw: Boolean = false) {
            context.startActivity(
                Intent(context, OnboardingActivity::class.java)
                    .putExtra(EXTRA_START_AT_JW, startAtJw),
            )
        }
    }
}
