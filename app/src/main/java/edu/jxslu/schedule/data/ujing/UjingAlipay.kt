package edu.jxslu.schedule.data.ujing

import android.app.Activity
import com.alipay.sdk.app.PayTask

/**
 * 支付宝支付封装（DESIGN §4.37 P2）。
 *
 * 职责收得很窄：把服务端签发的 `orderInfo` 串交给**官方 SDK** 的 [PayTask]，同步等
 * 结果并把 resultStatus 归一成三态。金额、商户、签名全在 orderInfo 里（服务端签发），
 * 本类不经手资金、不拼任何支付参数。
 *
 * `PayTask.pay` 自带 IO 阻塞（内部建网络连接），调用方须在后台线程执行。
 */
object UjingAlipay {

    /** 支付结果三态。 */
    enum class Outcome {
        /** resultStatus == 9000：支付成功。 */
        Success,

        /** 6001：用户取消（含锁屏 / 进程被杀），订单仍在待支付，可重试或取消订单。 */
        Canceled,

        /** 其余状态码（4000 等确定性失败 / 8000 等待确认）：按失败报，详情给原文码。 */
        Failed,
    }

    /** 拉起支付宝并等待结果。**后台线程调用**（PayTask.pay 是阻塞调用）。 */
    fun pay(activity: Activity, orderInfo: String): Pair<Outcome, String> {
        val result = PayTask(activity).payV2(orderInfo, true)
        val status = result["resultStatus"].orEmpty()
        val memo = result["memo"].orEmpty()
        val outcome = when (status) {
            "9000" -> Outcome.Success
            "6001" -> Outcome.Canceled
            else -> Outcome.Failed
        }
        return outcome to (if (memo.isBlank()) "resultStatus=$status" else memo)
    }
}
