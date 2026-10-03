package edu.jxslu.schedule

import edu.jxslu.schedule.domain.QuickScanTarget
import edu.jxslu.schedule.domain.quickScanTargetOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 今日页顶栏「+ → 扫一扫」的归类（DESIGN §3.3，2026-10-02）：车身码进骑行页、
 * 其余内容（洗衣机机身码）原文交给 U净。
 */
class QuickScanTest {

    @Test
    fun `车身码链接与裸车号都归骑行`() {
        assertEquals(
            QuickScanTarget.Ebike("100000669"),
            quickScanTargetOf("https://www.kvcoogo.com/ebike?id=100000669"),
        )
        // 别的车队前缀（300000…）原样带过去，不补 100000
        assertEquals(QuickScanTarget.Ebike("300000604"), quickScanTargetOf("300000604"))
        // 3 位尾部按校园车队补前缀
        assertEquals(QuickScanTarget.Ebike("100000669"), quickScanTargetOf("669"))
        // 前后空白是扫码器常见的脏输出，判据里先 trim
        assertEquals(
            QuickScanTarget.Ebike("100000669"),
            quickScanTargetOf("  100000669\n"),
        )
    }

    @Test
    fun `机身码原文归 U净且不被改写`() {
        // U净 服务端要的就是原文（.agents/rules/ujing.md）：这里只 trim，不截取不解析
        val washer = "https://phoenix.ujing.online/w?sn=ABCD1234"
        assertEquals(
            QuickScanTarget.Washer("https://phoenix.ujing.online/w?sn=ABCD1234"),
            quickScanTargetOf("  $washer  "),
        )
    }

    @Test
    fun `空内容按取消处理`() {
        assertNull(quickScanTargetOf(null))
        assertNull(quickScanTargetOf(""))
        assertNull(quickScanTargetOf("   "))
    }

    @Test
    fun `已知取舍 纯数字机身码会被判成车码`() {
        // 判据偏宽是刻意留的（车号前缀不固定，EbikeQr 认 6~12 位纯数字）：
        // 真出现「机身码恰好是 6~12 位数字」的机型再收窄，先把现状钉住
        assertEquals(QuickScanTarget.Ebike("123456"), quickScanTargetOf("123456"))
    }

    @Test
    fun `认得出的别家码就地拦下 不往 U净送`() {
        // 随手扫到别人的码是最常见的误跳来源：这些送 U净 必然回「未识别到设备信息」
        assertEquals(
            QuickScanTarget.Foreign("微信支付"),
            quickScanTargetOf("wxp://f2f0AbCdEfGhIjKlMnOpQrStUvWx"),
        )
        assertEquals(
            QuickScanTarget.Foreign("微信"),
            quickScanTargetOf("http://weixin.qq.com/r/abcdefg"),
        )
        assertEquals(
            QuickScanTarget.Foreign("微信"),
            quickScanTargetOf("https://u.wechat.com/EFGHIJKL"),
        )
        assertEquals(
            QuickScanTarget.Foreign("支付宝"),
            quickScanTargetOf("https://qr.alipay.com/fkx12345678"),
        )
        assertEquals(
            QuickScanTarget.Foreign("支付宝"),
            quickScanTargetOf("alipays://platformapi/startapp?appId=20000056"),
        )
        assertEquals(
            QuickScanTarget.Foreign("QQ"),
            quickScanTargetOf("https://qm.qq.com/q/AbCdEfGh"),
        )
        // 域名大小写不敏感
        assertEquals(
            QuickScanTarget.Foreign("微信"),
            quickScanTargetOf("HTTPS://WEIXIN.QQ.COM/r/abc"),
        )
    }

    @Test
    fun `表里没有的码仍然交给 U净`() {
        // 兜底口径不变：机身码本地认不出，服务端才是唯一判据。
        // 表只收认得准的几条，多写一条错的就把真机身码判成别人的码
        assertEquals(
            QuickScanTarget.Washer("https://example.com/whatever"),
            quickScanTargetOf("https://example.com/whatever"),
        )
        assertEquals(
            QuickScanTarget.Washer("UJING-WASHER-001"),
            quickScanTargetOf("UJING-WASHER-001"),
        )
    }
}
