package edu.jxslu.schedule.ui.ebike

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import edu.jxslu.schedule.BuildConfig
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.domain.EbikeUseMode
import edu.jxslu.schedule.domain.WechatRentNotice
import edu.jxslu.schedule.domain.capabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 「精确倒计时」的微信通知监听（DESIGN §3.9，2026-09-24 新增；2026-09-28 起兼听完成通知）。
 *
 * 两个出口：
 * - **起点校准**：运营方租车成功会推一条微信支付通知（「微信支付 · 先享后付使用通知」），
 *   那条通知到达的时刻 = 真正开始计费的那一刻，交给 [EbikeFreeRideReminder.calibrateRideStart]；
 * - **自动结束**（2026-09-28）：骑行结束再推一条「[先享后付]服务完成通知」，交给
 *   [EbikeFreeRideReminder.endRideFromNotice] 把计时收掉。完成通知同样含「先享后付」，
 *   所以**先判完成、再判开始**——顺序反了短骑行会把起点校准到结尾。
 *
 * **隐私**：本类只读通知的**包名与文本**、只用于当次匹配（[WechatRentNotice]），
 * 不落盘、不上传。唯一的例外是 **debug 构建**会把命中的原文打一行日志——那是为了
 * 真机抓取真实文案把匹配规则钉死（release 一行都不打，支付通知属于用户隐私）。
 *
 * 这个服务由系统在**用户授予「通知使用权」之后**绑定，未授权时根本收不到回调，
 * 所以这里不检查授权状态；开关（`ebikePreciseCountdownEnabled`）、使用方式
 * （必须是小程序方式，见 [EbikeUseMode]）与各道闸都在这里 / [EbikeFreeRideReminder]
 * 的入口里。
 */
class WechatRentListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val extras = sbn.notification?.extras ?: return
        // 把几个可能承载文案的字段拼成一整串：微信把「先享后付」放哪个字段不固定
        val content = listOf(
            Notification.EXTRA_TITLE,
            Notification.EXTRA_TEXT,
            Notification.EXTRA_SUB_TEXT,
            Notification.EXTRA_BIG_TEXT,
        ).mapNotNull { key -> extras.getCharSequence(key)?.toString() }
            .filter { it.isNotBlank() }
            .joinToString(" ")
        // 仅 debug：把**微信支付类**通知的原文打一行，用来真机抓租车通知的真实文案、
        // 把匹配规则钉死。只认「微信支付」四个字，所以聊天消息不会被打印；
        // release 一行都不打（支付通知属于用户隐私）。
        if (BuildConfig.DEBUG &&
            sbn.packageName == WechatRentNotice.WECHAT_PACKAGE &&
            content.contains("微信支付")
        ) {
            Log.i(TAG, "wechat pay notice: $content")
        }
        // 先判完成、再判开始：完成通知同样含「先享后付」，倒过来短骑行会被校准到结尾。
        // 两个都不命中（别的先享后付服务，如充电宝）就什么都不做
        val isCompletion = WechatRentNotice.matchesCompletion(sbn.packageName, content)
        if (!isCompletion && !WechatRentNotice.matches(sbn.packageName, content)) return
        // onNotificationPosted 在主线程回调，读 DataStore / 起服务都不能在这里做
        scope.launch {
            runCatching {
                // 使用方式闸（2026-09-29）：这条链路只服务「点打开微信扫一扫」那条路
                // （校准起点 / 收到完成通知就收计时）。账号登录方式的起点是服务端确认的
                // 开锁时刻、也不走微信扫一扫，整条链路在那一档没有任何作用——此时
                // **不读、不处理**用户通知（隐私上也是最稳的一档）。读失败同样按不处理。
                if (!miniProgramMode()) return@launch
                if (isCompletion) {
                    EbikeFreeRideReminder.endRideFromNotice(
                        applicationContext,
                        System.currentTimeMillis(),
                    )
                } else {
                    EbikeFreeRideReminder.calibrateRideStart(
                        applicationContext,
                        System.currentTimeMillis(),
                    )
                }
            }
        }
    }

    /** 当前是否启用微信通知校准（见 [EbikeUseMode] 的能力矩阵）。读不到偏好时不处理：宁漏判不误判。 */
    private suspend fun miniProgramMode(): Boolean =
        runCatching {
            Graph.displayPrefs(applicationContext).ebikeUseMode.first().capabilities()
                .wechatNoticeCalibration
        }.getOrDefault(false)

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WechatRentListener"
    }
}
