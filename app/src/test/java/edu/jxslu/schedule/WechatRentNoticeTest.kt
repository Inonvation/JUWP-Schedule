package edu.jxslu.schedule

import edu.jxslu.schedule.domain.WechatRentNotice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「精确倒计时」的微信租车通知识别（DESIGN §3.9）：包名与关键词匹配、时间窗口、
 * 使用/完成两类通知的分流（2026-09-28）。纯 JVM，口径见 [WechatRentNotice]。
 */
class WechatRentNoticeTest {

    private val start = 1_000_000_000_000L

    /** 真机抓到的两条原文（微信支付服务号通知，2026-09-28）：标题 + 正文字段拼接。 */
    private val startNotice =
        "「先享后付」服务使用通知 服务商家 快趣出行 先享服务 先骑车后付款 " +
            "先享金额 保证金¥99.00(已免除) 付款方式 优先从农业银行储蓄卡(6174)自动支付"

    private val completionNotice =
        "「先享后付」服务完成通知 商户已完成此服务,订单无需支付 服务商家 快趣出行 " +
            "先享服务 先骑车后付款"

    @Test
    fun `只认微信包名`() {
        assertTrue(WechatRentNotice.matches("com.tencent.mm", "微信支付 先享后付使用通知"))
        // 别的应用里出现同样的字也不能算（QQ、其他支付 App 都可能有这词）
        assertFalse(WechatRentNotice.matches("com.tencent.mobileqq", "先享后付"))
        assertFalse(WechatRentNotice.matches(null, "先享后付"))
    }

    @Test
    fun `关键词命中先享后付`() {
        assertTrue(WechatRentNotice.matches("com.tencent.mm", "先享后付使用通知"))
        assertTrue(WechatRentNotice.matches("com.tencent.mm", "微信支付 先享后付"))
        // 无关的微信支付通知不算（收款、转账、其他服务）
        assertFalse(WechatRentNotice.matches("com.tencent.mm", "微信支付 收款 0.01 元"))
        assertFalse(WechatRentNotice.matches("com.tencent.mm", "微信支付 支付成功"))
        // 空文本不算（有些通知只有图标）
        assertFalse(WechatRentNotice.matches("com.tencent.mm", ""))
        assertFalse(WechatRentNotice.matches("com.tencent.mm", null))
    }

    @Test
    fun `完成通知不算起点信号`() {
        // 完成通知同样含「先享后付」；matches 若不排除它，短骑行（完成通知落在
        // 5 分钟窗口内）会把计时起点校准到骑行的结尾
        assertFalse(WechatRentNotice.matches("com.tencent.mm", completionNotice))
        assertTrue(WechatRentNotice.matches("com.tencent.mm", startNotice))
    }

    @Test
    fun `完成通知只认先享后付底色的`() {
        assertTrue(WechatRentNotice.matchesCompletion("com.tencent.mm", completionNotice))
        // 别的服务商的完成类通知（不含「先享后付」）不算——只认标题会误收
        assertFalse(WechatRentNotice.matchesCompletion("com.tencent.mm", "服务完成通知 感谢使用"))
        assertFalse(WechatRentNotice.matchesCompletion("com.tencent.mobileqq", completionNotice))
        assertFalse(WechatRentNotice.matchesCompletion("com.tencent.mm", null))
    }

    @Test
    fun `真机两条原文分流到两个出口`() {
        assertTrue(WechatRentNotice.matches("com.tencent.mm", startNotice))
        assertFalse(WechatRentNotice.matchesCompletion("com.tencent.mm", startNotice))
        assertFalse(WechatRentNotice.matches("com.tencent.mm", completionNotice))
        assertTrue(WechatRentNotice.matchesCompletion("com.tencent.mm", completionNotice))
    }

    @Test
    fun `窗口只认点扫一扫之后的五分钟`() {
        // 窗口内（含两端）
        assertTrue(WechatRentNotice.isWithinWindow(start, start))
        assertTrue(WechatRentNotice.isWithinWindow(start, start + 60_000L))
        assertTrue(WechatRentNotice.isWithinWindow(start, start + WechatRentNotice.WINDOW_MS))
        // 超出窗口：多半是借充电宝/雨伞之类的另一笔「先享后付」，不能拿它重置计时
        assertFalse(WechatRentNotice.isWithinWindow(start, start + WechatRentNotice.WINDOW_MS + 1))
        // 通知早于计时起点（时钟回拨之类的脏数据）也不算
        assertFalse(WechatRentNotice.isWithinWindow(start, start - 1))
        // 没有在案计时
        assertFalse(WechatRentNotice.isWithinWindow(0L, start))
    }

    @Test
    fun `完成通知窗口覆盖免费时段加迟到窗口`() {
        // 正常骑行 routinely 超过 5 分钟——完成通知窗口不能复用起点校准的 5 分钟，
        // 否则自动结束只在超短骑行下生效。上限 = 免费 15 分钟 + 结束迟到窗口 5 分钟
        assertTrue(WechatRentNotice.isWithinCompletionWindow(start, start + 60_000L))
        assertTrue(
            WechatRentNotice.isWithinCompletionWindow(
                start,
                start + WechatRentNotice.WINDOW_MS + 1,
            ),
        )
        assertTrue(
            WechatRentNotice.isWithinCompletionWindow(
                start,
                start + WechatRentNotice.COMPLETION_WINDOW_MS,
            ),
        )
        // 越过在案视界：计时此时已被兜底核对收干净（起点清零），不该再认
        assertFalse(
            WechatRentNotice.isWithinCompletionWindow(
                start,
                start + WechatRentNotice.COMPLETION_WINDOW_MS + 1,
            ),
        )
        assertFalse(WechatRentNotice.isWithinCompletionWindow(start, start - 1))
        assertFalse(WechatRentNotice.isWithinCompletionWindow(0L, start))
    }
}
