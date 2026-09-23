package edu.jxslu.schedule.data.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 电费下单/支付解析与签名（DESIGN §4.24，fixture 全部取自 2026-09-23 真实响应）。
 */
class PowerPayTest {

    private val orderJson = """
        {"code":200,"success":true,"data":{"paystep":2,"accountno":null,"ccctype":null,"uuid":null,
         "passwordMap":null,"payList":[
           {"id":4,"pay_type_name":"农行支付","pay_type_code":"NEWPAYMENTPLATFORM","icon":"ABCpay","nopassword":0,"partner_key":"1000003","flag":"181"},
           {"id":59,"pay_type_name":"电子账户","pay_type_code":"ACCOUNT","icon":"accountpay","nopassword":0,"partner_key":"1000001","flag":"181"}],
         "payExpDate":"2026-09-23 22:17:12","orderid":"1790171232923431"},"msg":"操作成功"}
    """.trimIndent()

    private val challengeJson = """
        {"code":200,"success":true,"data":{"paystep":2,"accountno":null,
         "ccctype":[{"balance":0,"ccctype":"000"}],
         "uuid":null,"passwordMap":{"3744728e574c4058bc65e124b9bae043":"3512079864"},"payList":null},"msg":"操作成功"}
    """.trimIndent()

    @Test
    fun orderParsing() {
        val order = PowerPayModels.orderFrom(orderJson)
        assertNotNull(order)
        assertEquals("1790171232923431", order!!.orderId)
        assertEquals("2026-09-23 22:17:12", order.payExpDate)
    }

    @Test
    fun channelsParsing() {
        val channels = PowerPayModels.channelsFrom(orderJson)
        assertEquals(2, channels.size)
        assertEquals("59", channels[1].payId)
        assertEquals("ACCOUNT", channels[1].code)
        assertEquals("电子账户", channels[1].name)
        // nopassword=0 = 需要密码
        assertTrue(!channels[1].noPassword)
    }

    /**
     * paystep=2 的真实响应（2026-09-23）：没有 orderid 字段、uuid 为 null、
     * ccctype 是数组 [{balance, ccctype}]。解析层按 passwordMap 键兜底订单号，
     * 电子账户余额与类型从数组首项取。
     */
    @Test
    fun challengeParsing() {
        val c = PowerPayModels.challengeFrom(challengeJson)
        assertNotNull(c)
        assertEquals("3744728e574c4058bc65e124b9bae043", c!!.orderId)
        assertEquals(mapOf("3744728e574c4058bc65e124b9bae043" to "3512079864"), c.passwordMap)
        assertEquals("000", c.accountType)
        assertEquals(0L, c.accountBalanceFen)
    }

    /** 密文换算：用户数字 d → 乱序表 table[d]（一卡通登录同协议）。 */
    @Test
    fun cipherMapping() {
        val c = PowerPayModels.challengeFrom(challengeJson)!!
        // 表 "3512079864"：数字 0→'3', 1→'5', 2→'1', 3→'2', 4→'0', 5→'7', 6→'9', 7→'8', 8→'6', 9→'4'
        assertEquals("3512079864", c.passwordMap[c.uuid])
        // 数字 1,2,5,6 → 表位 5,1,7,9
        assertEquals("5179", c.cipherOf("1256"))
        assertNull(c.cipherOf("")) // 空串
        assertNull(c.cipherOf("1234567")) // 超长
        assertNull(c.cipherOf("12345a")) // 非数字
    }

    /** 若服务端未来在 data 里带 orderid，优先用显式字段。 */
    @Test
    fun challengePrefersExplicitOrderId() {
        val raw = """{"code":200,"data":{"orderid":"999","passwordMap":{"k1":"123456"}}}"""
        assertEquals("999", PowerPayModels.challengeFrom(raw)!!.orderId)
    }

    @Test
    fun statusParsing() {
        assertEquals(0, PowerPayModels.orderStatusFrom("""{"code":200,"order":{"status":0}}"""))
        assertEquals(1, PowerPayModels.orderStatusFrom("""{"code":200,"order":{"status":1}}"""))
        assertNull(PowerPayModels.orderStatusFrom("""{"code":500,"msg":"未知异常"}"""))
    }

    @Test
    fun envelopeCodes() {
        assertEquals(500, PowerPayModels.codeOf("""{"code":500,"msg":"未知异常，请联系管理员"}"""))
        assertEquals("未知异常，请联系管理员", PowerPayModels.messageOf("""{"code":500,"msg":"未知异常，请联系管理员"}"""))
        assertEquals(200, PowerPayModels.codeOf("""{"code":200,"data":{}}"""))
    }

    @Test
    fun signatureIsDeterministicAndUpperHex() {
        val signed = PowerPaySign.signed(
            mapOf("feeitemid" to "181", "tranamt" to "0.01", "paystep" to "0"),
            timestamp = "20260923214712000",
            nonce = "abc123xyz00",
        )
        assertEquals(64, signed["SIGN"]!!.length)
        assertEquals(signed["SIGN"], PowerPaySign.signOf(signed))
        // 空值不参与签名
        val withBlank = PowerPaySign.signed(mapOf("a" to "1", "b" to ""), timestamp = "20260923214712000", nonce = "abc123xyz00")
        assertEquals(
            PowerPaySign.signOf(mapOf("a" to "1", "APP_ID" to "56321", "TIMESTAMP" to "20260923214712000",
                "NONCE" to "abc123xyz00", "SIGN_TYPE" to "SHA256")),
            withBlank["SIGN"],
        )
    }

    @Test
    fun timestampAndNonceFormats() {
        val ts = PowerPaySign.buildTimestamp(1790171232000L)
        assertEquals(17, ts.length)
        assertTrue(ts.all { it.isDigit() })
        assertEquals(11, PowerPaySign.buildNonce().length)
    }
}
