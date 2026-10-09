package edu.jxslu.schedule.ui.campus

import android.content.Context
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.ui.reminder.BalanceAlertReminder
import edu.jxslu.schedule.ui.widget.LifeWidgetSync
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 付款码消费后的余额自动刷新（2026-10-09）。
 *
 * 问题：付款码页检测到扣款后自动退出、弹「支付成功」，但各处的余额快照还是扣款前的
 * 数字（生活页钱包卡、校园卡设置页、桌面小组件）——快照只在进页/手动刷新时更新，
 * 「钱刚动过」这件事本身不触发任何刷新，用户不点一下就只看得到旧值。
 *
 * 拿到了「确有一笔消费」这个事实，强拉一次余额就是正当的（同到账判定的
 * `force = true` 口径，DESIGN §4.24）。收敛成一个进程级动作、**只取一次数**：
 *
 * 1. 作废仓库里的正式卡余额缓存（也是兜底：万一这次强拉失败，后续任何
 *    `force = false` 读取都会去拿新值）；
 * 2. 强拉一次正式卡余额，写回仓库缓存，顺手推给桌面小组件（零额外请求）；
 * 3. 成功后 [revision] 自增，各处余额快照订阅它就地重建（吃刚写入的缓存，零额外请求）。
 * 4. 同一份余额再交给余额提醒当场评估一次（[BalanceAlertReminder.evaluateYktBalance]，
 *    零额外请求）：付款把余额刷低时不必等下一个检查档（默认 6 小时）。
 *
 * 触发点 = [CampusPayResultDialogHost] 收到这笔消费的那一刻（用户刚回到页面前），
 * 比「检测到扣款」再晚几秒——离用户真正盯着余额看最近，也最接近手动刷新的时机。
 * 失败静默、不重试：余额取不到不打扰，卡片维持旧值，下次进页/手动刷新照旧。
 */
object CampusBalanceSync {

    /**
     * 后台强拉余额成功一次 +1；余额快照持有者（[CampusCardViewModel]）据此重建展示。
     *
     * 计数而不是事件对象：StateFlow 天然支持多个订阅者（生活页与设置页各一份 VM），
     * 后置的订阅者只关心「当前值之后」的变化（订阅时 `drop(1)`）。
     */
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    /**
     * 付款码消费命中时调用一次：作废缓存 → 强拉 → 推小组件 → 通知各处重建。
     *
     * 跑在 [Graph.appScope] 上：宿主弹「支付成功」的窗口可能马上被关闭/切换，
     * 这次刷新不该跟着任何界面生命周期被掐断。
     */
    fun refreshAfterPayment(context: Context) {
        val appContext = context.applicationContext
        val credentials = Graph.yktCredentialStore(appContext).read() ?: return
        val repo = Graph.yktRepository(appContext)
        repo.invalidateCardBalanceCache()
        Graph.appScope.launch {
            val cards = runCatching {
                repo.cards(credentials.username, credentials.password, force = true)
            }.getOrNull().orEmpty()
            if (cards.isEmpty()) return@launch
            val cardFen = cards.sumOf { it.cardBalanceFen }
            runCatching { LifeWidgetSync.pushCampusBalance(appContext, cardFen) }
            _revision.update { it + 1 }
            // 余额提醒的即时评估（开关关 / 免扰 / 「偏低只提醒一次」等规则都在提醒侧）
            runCatching { BalanceAlertReminder.evaluateYktBalance(appContext, cardFen) }
        }
    }
}
