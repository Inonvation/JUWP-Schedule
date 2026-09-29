package edu.jxslu.schedule

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import edu.jxslu.schedule.ui.life.PowerBankPayScreen

/**
 * 农行支付收银台独立窗口（DESIGN §4.24「农行支付」）。
 *
 * 与 [JwImportActivity] / 学工表单同一个形态：**窗口里有第三方网页表单**，所以
 * 独立窗口 + 锁竖屏 + `adjustResize`（键盘起来时表单要能被顶出来，收银台的
 * 卡号 / 验证码 / 密码字段全靠它）。URL 走 extra——链接是一次性会话，不落盘、
 * 不进日志、也没有全局状态可查（进程被杀就是重下单，见 [LifeViewModel] 那头）。
 *
 * 支付完成后回到商户域（`charge.juwp.edu.cn`）即关闭本窗口：剩下的「查单确认到账」
 * 由生活页弹层的等待步接管（`PowerRechargeSheet` 的 BankPay 步）。
 */
class PowerBankPayActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (url.isBlank()) {
            finish()
            return
        }
        enableEdgeToEdge()
        // 预测性返回（DESIGN §3.1）：34+ 由本窗口声明过渡，系统才会把手势进度交给它
        enablePredictiveBackTransitions()
        setContent {
            JuwRoot {
                PowerBankPayScreen(
                    url = url,
                    onMerchantReturn = { finish() },
                    onClose = { finish() },
                )
            }
        }
    }

    override fun finish() {
        super.finish()
        applySubpageCloseTransition(this)
    }

    companion object {
        private const val EXTRA_URL = "url"

        /** 打开收银台。[url] 由 `PowerRepository.bankCashier` 现取（一次性会话）。 */
        fun start(context: Context, url: String) {
            val intent = Intent(context, PowerBankPayActivity::class.java)
                .putExtra(EXTRA_URL, url)
            context.startActivity(intent)
            applySubpageOpenTransition(context)
        }
    }
}
