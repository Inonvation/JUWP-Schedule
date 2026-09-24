package edu.jxslu.schedule.data.session

/**
 * 落盘 token 的新鲜度判定（DESIGN §4.27）。
 *
 * 一卡通 token 服务端给的有效期是 6047999 秒（≈70 天），但服务端可能提前让它失效。
 * 所以落盘的 token 只当「冷启动少登一次」的加速，**不当授权依据**——真失效了服务端会回
 * 401，既有的重登逻辑照常接管。
 *
 * 留一天余量再判过期：卡在边界上只会多一次无谓的登录请求，而少留余量会在 token 刚好
 * 过期的那一刻白跑一趟。
 */
object TokenFreshness {

    /** 落盘 token 的最大可用时长（60 天，比服务端标称的 70 天保守）。 */
    const val MAX_AGE_MS = 60L * 24 * 60 * 60 * 1000

    fun isFresh(savedAtMs: Long, nowMs: Long): Boolean {
        if (savedAtMs <= 0L) return false
        val age = nowMs - savedAtMs
        // 负数 = 设备时钟被往回拨过，这种情况宁可当它不新鲜
        return age in 0..MAX_AGE_MS
    }
}
