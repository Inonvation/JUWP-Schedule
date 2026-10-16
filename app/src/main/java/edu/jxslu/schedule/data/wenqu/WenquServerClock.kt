package edu.jxslu.schedule.data.wenqu

import edu.jxslu.schedule.domain.WenquRules
import okhttp3.Interceptor
import okhttp3.Response
import kotlin.math.abs

/**
 * 问渠服务器的时钟偏移（DESIGN §4.45，2026-10-16）：预报名算「提前量」的校对。
 *
 * 平台写接口会**提前约 10 秒放行**（用户实测：活动写着 12:00 开始报名，11:59:50
 * 就能抢），所以「开始前 15 秒起试发」落在服务器几点几分，得用服务器自己的钟来算。
 * 不依赖 NTP：HTTP 响应自带 `Date` 头，每次请求顺手记一笔偏移
 * （[WenquServerClockInterceptor]）。
 *
 * 只保留**最近一次**测量；偏移大得离谱（差 10 分钟以上）视为异常样本丢弃——
 * 那种情况下错的是手机时间，不是这一发请求能修的。
 */
object WenquServerClock {

    /** 合理偏移上限；超过视为样本异常（手机钟本身偏了，先修手机时间）。 */
    const val MAX_ABS_OFFSET_MS: Long = 10 * 60 * 1000L

    /** 服务器 − 手机（毫秒）。没测到过时是 0（按手机钟跑，与旧行为一致）。 */
    @Volatile
    var offsetMs: Long = 0L
        private set

    /** 最近一次测量时刻（手机钟）；0 = 没测到过。 */
    @Volatile
    var measuredAt: Long = 0L
        private set

    fun record(offset: Long, at: Long) {
        if (abs(offset) > MAX_ABS_OFFSET_MS) return
        offsetMs = offset
        measuredAt = at
    }
}

/**
 * 记 `Date` 头的拦截器：把「服务器时间 − 手机时间」写进 [WenquServerClock]。
 * 只做本地记账，不读也不改请求 / 响应体（**不是日志拦截器**，没有任何数据出站）。
 */
class WenquServerClockInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val sentAt = System.currentTimeMillis()
        val response = chain.proceed(chain.request())
        val receivedAt = System.currentTimeMillis()
        val serverAt = WenquRules.parseHttpDate(response.header("Date"))
        if (serverAt != null) {
            WenquServerClock.record(
                WenquRules.clockOffsetMs(serverAt, sentAt, receivedAt),
                receivedAt,
            )
        }
        return response
    }
}
