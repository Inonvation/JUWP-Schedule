package edu.jxslu.schedule.domain

/**
 * 微信租车「先享后付」通知的识别口径（DESIGN §3.9「精确倒计时」，2026-09-24 新增；
 * 2026-09-28 起也识别「服务完成通知」用于自动结束计时）。
 *
 * 背景：免费时长的计时起点原本只能取「点打开微信扫一扫」的时刻，比真正开车早 1~2 分钟
 * （用户还要在微信里选车、确认、等开锁）。运营方的租车成功会推一条微信支付通知
 * （「微信支付 · 先享后付使用通知」），那条通知出现的时刻 ≈ 真正开始计费的时刻。
 *
 * 本文件只做纯 JVM 的文本匹配与时间窗口判断，不含任何 Android API。
 *
 * **隐私口径**：只读通知的包名与文本用于**当次**匹配，不落盘、不上传、不进日志
 * （debug 构建是唯一例外，见 `WechatRentListener`——那是为了抓真实文案，release 不打）。
 */
object WechatRentNotice {

    /** 微信包名。 */
    const val WECHAT_PACKAGE = "com.tencent.mm"

    /**
     * 命中的关键词。**「先享后付」是运营方租车走的支付方式**，比「单车」「骑行」之类的
     * 泛词精确得多——后者会把营销推送也算进来。
     */
    val KEYWORDS = listOf("先享后付")

    /**
     * 「[先享后付]服务完成通知」的标题词。骑行结束时微信会再推一条（标题与「服务使用通知」
     * 只差两个字），**它同样含「先享后付」**——[matches] 必须先把它排除，否则短骑行
     * （完成通知落在 [WINDOW_MS] 内）会把计时起点校准到骑行的**结尾**。
     * 文案取自真机通知标题：`「先享后付」服务完成通知`。
     */
    const val COMPLETION_TITLE = "服务完成通知"

    /**
     * 时间窗口：只有「点过打开微信扫一扫」之后 [WINDOW_MS] 内收到的这条通知，
     * 才当作**这次**租车的信号。
     *
     * 必要性：共享充电宝 / 雨伞 / 其他租借服务同样走「先享后付」。没有这道窗口，
     * 用户扫完车顺手借个充电宝就会把计时重置一次（起点被推后 = 提醒变晚）。
     */
    const val WINDOW_MS = 5 * 60_000L

    /**
     * 完成通知的时间窗口：起点之后 [COMPLETION_WINDOW_MS] 内收到的完成通知才当作
     * **这次**骑行的结束信号。**不能复用起点校准的 [WINDOW_MS]**——正常骑行 routinely
     * 超过 5 分钟，复用它会让自动结束只在超短骑行下生效。
     * 上限 = 免费时长（15 分钟）+ 结束提醒的迟到窗口（`EbikeFreeRide.END_WINDOW_MS`，
     * 5 分钟）：与兜底核对把过期计时收干净的视界一致。骑得更久时起点迟早被 `check`
     * 清零，那时「有在案计时」这道闸自然挡住。
     */
    const val COMPLETION_WINDOW_MS = 20 * 60_000L

    /**
     * 这条通知是否是「先享后付」**使用**通知（= 计时起点信号）。
     *
     * [content] = 调用方把通知的标题 / 正文 / 子标题等字段拼成的**一整串**文本：
     * 微信把「先享后付」放在哪个字段并不固定，逐字段各写一套匹配容易漏。
     *
     * 完成通知同样含「先享后付」，命中 [COMPLETION_TITLE] 的不算——
     * 那是 [matchesCompletion] 的信号，两个出口在此分流。
     */
    fun matches(packageName: String?, content: String?): Boolean {
        if (!matchesBase(packageName, content)) return false
        return !content!!.contains(COMPLETION_TITLE)
    }

    /**
     * 这条通知是否是「先享后付」**完成**通知（= 结束计时信号）。
     * 底部要求：先有「先享后付」（完成通知正文同样走这套支付口径），再带完成标题；
     * 顺序反着写（只认标题）会把别的完成类通知误收进来。
     */
    fun matchesCompletion(packageName: String?, content: String?): Boolean {
        if (!matchesBase(packageName, content)) return false
        return content!!.contains(COMPLETION_TITLE)
    }

    /** 两类通知共用的底闸：微信包名 + 非空文本 + 含「先享后付」。 */
    private fun matchesBase(packageName: String?, content: String?): Boolean {
        if (packageName != WECHAT_PACKAGE) return false
        if (content.isNullOrBlank()) return false
        return KEYWORDS.any { content.contains(it) }
    }

    /**
     * 这条通知是否落在「这次计时」的窗口内（含两端）。起点为 0（没有在案计时）时恒为 false。
     *
     * 用「起点 + [WINDOW_MS]」而不是「收到通知的时间戳与通知自带时间」：`StatusBarNotification`
     * 的 `postTime` 与 `System.currentTimeMillis()` 同源，但直接拿当前时刻做比较更简单，
     * 也少一个可被脏数据影响的输入。
     */
    fun isWithinWindow(rideStartAtMillis: Long, noticeAtMillis: Long): Boolean {
        if (rideStartAtMillis <= 0L) return false
        val delta = noticeAtMillis - rideStartAtMillis
        return delta in 0..WINDOW_MS
    }

    /**
     * 完成通知是否落在「这次骑行」的在案视界内（含两端）。起点为 0 时恒为 false。
     * 窗口口径见 [COMPLETION_WINDOW_MS]——**不是** [isWithinWindow] 的 5 分钟。
     */
    fun isWithinCompletionWindow(rideStartAtMillis: Long, noticeAtMillis: Long): Boolean {
        if (rideStartAtMillis <= 0L) return false
        val delta = noticeAtMillis - rideStartAtMillis
        return delta in 0..COMPLETION_WINDOW_MS
    }
}
