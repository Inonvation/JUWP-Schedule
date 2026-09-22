package edu.jxslu.schedule.ui.campus

import edu.jxslu.schedule.domain.YktPayment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 付款码页 → 上一页的「这笔消费」通道（DESIGN §3.10）。
 *
 * 付款码页检测到扣款后会**自动退出**，结果要落在退出后那个页面上（今日页或
 * 「我的 → 校园卡」），两者分属不同 Activity，没有共同的 CompositionLocal，
 * 用一个进程内单例转发。
 *
 * 只存内存：进程被杀就丢，这笔消费在「消费流水」里照样查得到，不值得为一次提示
 * 落库。取值方**取到即消费**（[consume]），避免被压在后台的另一个页面稍后再弹一次
 * （充值到账弹窗那次的教训，2026-09-22）。
 */
object PayCodeResultBus {

    private val _result = MutableStateFlow<YktPayment?>(null)
    val result: StateFlow<YktPayment?> = _result.asStateFlow()

    fun publish(payment: YktPayment) {
        _result.value = payment
    }

    fun consume() {
        _result.value = null
    }
}
