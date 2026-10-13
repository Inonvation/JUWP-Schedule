package edu.jxslu.schedule.ui.common

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.google.zxing.BarcodeFormat
import com.google.zxing.client.android.Intents
import edu.jxslu.schedule.domain.QuickScanTarget
import edu.jxslu.schedule.domain.quickScanTargetOf
import edu.jxslu.schedule.ui.ebike.EbikeScanActivity

/**
 * 扫一扫入口的组合封装（DESIGN §3.1 悬浮球）。
 *
 * 注册取景页 launcher、构造 intent，把识别结果按 [quickScanTargetOf] 三档分流回三个回调：
 * 车码 → [onEbike]、机身码 → [onWasher]、别家码 → [onForeign]（就地提示，不跳页）。
 * 取景窗口走**原文模式**（`MODE_RAW`）——车号解析归本地判据、机身码原文归 U净 服务端，
 * 本地只负责「去哪一页」这一件事。
 *
 * 悬浮球用这一份；今日页 / 生活页各自的同款实现本轮不动（后续可收敛到这里）。
 *
 * @return 触发扫一扫的动作（调用即拉起取景页）。
 */
@Composable
fun rememberQuickScan(
    onEbike: (String) -> Unit,
    onWasher: (String) -> Unit,
    onForeign: (String) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val raw = result.data?.getStringExtra(Intents.Scan.RESULT)
        when (val target = quickScanTargetOf(raw)) {
            // 空内容 = 取消（没扫到就退出取景页），静默处理，别把放弃扫码报成失败
            null -> Unit
            is QuickScanTarget.Ebike -> onEbike(target.carNum)
            is QuickScanTarget.Washer -> onWasher(target.raw)
            is QuickScanTarget.Foreign -> onForeign(target.label)
        }
    }
    return remember(launcher) {
        {
            launcher.launch(
                Intent(context, EbikeScanActivity::class.java).apply {
                    putExtra(Intents.Scan.PROMPT_MESSAGE, "对准车身或洗衣机机身上的二维码")
                    putExtra(EbikeScanActivity.EXTRA_RESULT_MODE, EbikeScanActivity.MODE_RAW)
                    // 车身码与机身码都是二维码：限定格式，省得商品一维码也当码送出去。
                    // 键走 Intents.Scan.FORMATS 的**逗号分隔字符串**（DecodeFormatManager 只读
                    // StringExtra 这一种形态，别改传 ArrayList，那样等于没设）
                    putExtra(Intents.Scan.FORMATS, BarcodeFormat.QR_CODE.name)
                },
            )
        }
    }
}
