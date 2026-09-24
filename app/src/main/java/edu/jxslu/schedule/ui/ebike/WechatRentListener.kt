package edu.jxslu.schedule.ui.ebike

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import edu.jxslu.schedule.BuildConfig
import edu.jxslu.schedule.domain.WechatRentNotice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 「精确倒计时」的微信通知监听（DESIGN §3.9，2026-09-24 新增）。
 *
 * 目的只有一个：把免费时长的**计时起点**从「点打开微信扫一扫的时刻」校准到
 * 「真正开始计费的那一刻」——运营方租车成功会推一条微信支付通知
 * （「微信支付 · 先享后付使用通知」），那条通知到达的时刻就是它。
 *
 * **隐私**：本类只读通知的**包名与文本**、只用于当次匹配（[WechatRentNotice.matches]），
 * 不落盘、不上传。唯一的例外是 **debug 构建**会把命中的原文打一行日志——那是为了
 * 真机抓取真实文案把匹配规则钉死（release 一行都不打，支付通知属于用户隐私）。
 *
 * 这个服务由系统在**用户授予「通知使用权」之后**绑定，未授权时根本收不到回调，
 * 所以这里不检查授权状态；开关（`ebikePreciseCountdownEnabled`）与其余四道闸
 * 都在 [EbikeFreeRideReminder.calibrateRideStart] 里。
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
        if (!WechatRentNotice.matches(sbn.packageName, content)) return
        // onNotificationPosted 在主线程回调，读 DataStore / 起服务都不能在这里做
        scope.launch {
            runCatching {
                EbikeFreeRideReminder.calibrateRideStart(applicationContext, System.currentTimeMillis())
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WechatRentListener"
    }
}
