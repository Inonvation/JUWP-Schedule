package edu.jxslu.schedule.data.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
         "ccctype":[{"balance":1,"ccctype":"000"}],
         "uuid":null,"passwordMap":{"3744728e574c4058bc65e124b9bae043":"3512079864"},
         "payList":null,"orderid":null},"msg":"操作成功"}
    """.trimIndent()

    /** 下单时拿到的真实订单号（paystep=2 响应里不会回传，必须由调用方带过去）。 */
    private val requestedOrderId = "1790249804015241"

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
     * paystep=2 的真实响应（2026-09-24 复测）：`orderid` 恒为 **null**、`uuid` 为 null、
     * `ccctype` 是数组 `[{balance:1, ccctype:"000"}]`（balance 单位是**元**）。
     *
     * 订单号只能取调用方传进来的那一个——旧代码用 `passwordMap` 的键（uuid）兜底，
     * 支付时把 uuid 当 orderid 发出去，服务端回「订单不存在，请重新预定」。
     */
    @Test
    fun challengeParsing() {
        val c = PowerPayModels.challengeFrom(challengeJson, requestedOrderId)
        assertNotNull(c)
        // 订单号 = 下单那个，绝不是 uuid
        assertEquals(requestedOrderId, c!!.orderId)
        assertTrue(c.orderId != c.uuid)
        assertEquals(mapOf("3744728e574c4058bc65e124b9bae043" to "3512079864"), c.passwordMap)
        assertEquals("000", c.accountType)
        // ccctype.balance=1 元 → 100 分（按分渲染会变成 ¥0.01，就是用户报的那条）
        assertEquals(100L, c.accountBalanceFen)
    }

    /**
     * 密文换算（2026-09-24 按官方前端修正）：数字 d → 在乱序表里的**下标**。
     * 官方键盘第 i 个键显示 `table[i]`，提交的是 `String(i)`——上一版
     * 「d → table[d]」方向反了，正确密码也报错。
     */
    @Test
    fun cipherMapping() {
        val c = PowerPayModels.challengeFrom(challengeJson, requestedOrderId)!!
        // 表 "3512079864"：数字 0→'3', 1→'5', 2→'1', 3→'2', 4→'0', 5→'7', 6→'9', 7→'8', 8→'6', 9→'4'
        assertEquals("3512079864", c.passwordMap[c.uuid])
        // 提交的是「该数字在乱序表里的下标」：1→2、2→3、5→1、6→8
        assertEquals("2318", c.cipherOf("1256"))
        // 边界：表首字符 '3' 的下标 0、表尾字符 '4' 的下标 9
        assertEquals("09", c.cipherOf("34"))
        // 坏表兜底：表不是 0-9 双射（有重复字符）→ 整体拒绝，不猜
        val bad = PowerPayChallenge(
            orderId = "o",
            passwordMap = mapOf("u" to "1122334455"),
            accountType = null,
            accountBalanceFen = null,
        )
        assertNull(bad.cipherOf("1"))
        // 表里没有的字符（表短一位）→ 拒绝
        val short = PowerPayChallenge(
            orderId = "o",
            passwordMap = mapOf("u" to "123456789"),
            accountType = null,
            accountBalanceFen = null,
        )
        assertNull(short.cipherOf("9"))
        assertNull(c.cipherOf("")) // 空串
        assertNull(c.cipherOf("1234567")) // 超长
        assertNull(c.cipherOf("12345a")) // 非数字
    }

    /** 若服务端未来在 data 里带 orderid，优先用显式字段。 */
    @Test
    fun challengePrefersExplicitOrderId() {
        val raw = """{"code":200,"data":{"orderid":"999","passwordMap":{"k1":"123456"}}}"""
        assertEquals("999", PowerPayModels.challengeFrom(raw, "REQUESTED")!!.orderId)
    }

    /** 订单过期 / 已失效 / 不存在要认得出：UI 据此回金额步重下单，而不是让人反复重输密码。 */
    @Test
    fun orderGoneMessages() {
        assertTrue(PowerPayModels.isOrderGone("订单不存在，请重新预定"))
        assertTrue(PowerPayModels.isOrderGone("订单已过期，请重新提交"))
        assertTrue(PowerPayModels.isOrderGone("支付会话已失效"))
        assertFalse(PowerPayModels.isOrderGone("密码错误，请重新输入"))
        assertFalse(PowerPayModels.isOrderGone("余额不足"))
        assertFalse(PowerPayModels.isOrderGone(null))
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
