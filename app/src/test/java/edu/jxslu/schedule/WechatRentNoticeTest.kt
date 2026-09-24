package edu.jxslu.schedule

import edu.jxslu.schedule.domain.WechatRentNotice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「精确倒计时」的微信租车通知识别（DESIGN §3.9）：包名与关键词匹配、时间窗口。
 * 纯 JVM，口径见 [WechatRentNotice]。
 */
class WechatRentNoticeTest {

    private val start = 1_000_000_000_000L

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
}
