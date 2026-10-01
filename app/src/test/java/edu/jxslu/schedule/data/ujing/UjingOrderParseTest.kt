package edu.jxslu.schedule.data.ujing

import edu.jxslu.schedule.data.qiekj.QiekjJson
import edu.jxslu.schedule.domain.UjingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** U净 订单链响应解析（DESIGN §4.37 P2）：下单 / 支付参数 / 详情 / 支付状态 / 控制受理。 */
class UjingOrderParseTest {

    private fun envelope(text: String): UjingEnvelope =
        QiekjJson.json.decodeFromString(UjingEnvelope.serializer(), text)

    // ---- 下单 ----

    @Test
    fun createOrderParsesOrderId() {
        val env = envelope("""{"code":0,"data":{"orderId":"12345","orderNo":"20261001120000"}}""")
        val data = env.decodeData<UjingOrderCreateData>()
        assertEquals("12345", data?.orderId)
        assertEquals("20261001120000", data?.orderNo)
    }

    // ---- 支付参数 ----

    @Test
    fun payArgsParsesOrderInfo() {
        val env = envelope(
            """{"code":0,"data":{"payInfo":{"orderInfo":"app_id=2021001&biz_content=..."}}}""",
        )
        val data = env.decodeData<UjingPayArgsData>()
        assertEquals("app_id=2021001&biz_content=...", data?.payInfo?.orderInfo)
        // payInfo 缺失不崩
        assertNull(envelope("""{"code":0,"data":{}}""").decodeData<UjingPayArgsData>()?.payInfo)
    }

    // ---- 订单详情 ----

    @Test
    fun orderDetailParsesLenientFields() {
        // status / payFlag 都可能是字符串形态
        val env = envelope(
            """{"code":0,"data":{"orderId":"12345","status":"40","statusRemark":"运行中",
                "remainTime":"1550","payFlag":"1","payPrice":"2.78"}}""",
        )
        val data = env.decodeData<UjingOrderDetailData>()!!
        assertEquals("12345", data.orderId)
        assertEquals("40", data.status)
        assertEquals("运行中", data.statusRemark)
        assertEquals(1550, data.remainTime)
        assertEquals(1, data.payFlag)
        assertEquals("2.78", data.payPrice)
    }

    @Test
    fun orderDetailMissingRemarkKeepsNull() {
        val env = envelope("""{"code":0,"data":{"orderId":"1","status":"20"}}""")
        val data = env.decodeData<UjingOrderDetailData>()!!
        assertEquals("20", data.status)
        assertNull(data.statusRemark)
    }

    // ---- 支付状态 ----

    @Test
    fun payStatusParses() {
        val env = envelope("""{"code":0,"data":{"payFlag":1,"status":"20"}}""")
        val data = env.decodeData<UjingPayStatusData>()!!
        assertEquals(1, data.payFlag)
        assertEquals("20", data.status)
    }

    // ---- 控制受理（含 1703） ----

    @Test
    fun controlAcceptedInnerErrorCode() {
        // code = 1703（受理码）+ 内层 errorCode = 0 → 成功
        val env = envelope("""{"code":1703,"message":"ok","data":{"errorCode":0}}""")
        val data = env.decodeData<UjingControlData>()
        assertEquals(0, data?.errorCode)
        assertTrue(UjingState.commandAccepted(env.code, data?.errorCode))
    }

    @Test
    fun controlRejectedInnerError() {
        val env = envelope("""{"code":1703,"message":"ok","data":{"errorCode":1,"errorMessage":"机器离线"}}""")
        val data = env.decodeData<UjingControlData>()
        assertEquals(1, data?.errorCode)
        assertFalse(UjingState.commandAccepted(env.code, data?.errorCode))
        assertEquals("机器离线", data?.errorMessage)
    }

    // ---- 静默拒绝（社区实测：HTTP 200 + 空 data 对象） ----

    @Test
    fun silentRejectParsesAsEmptyData() {
        // 服务端对"无需启动"的订单返回 {}：解析不崩，data 存在但字段全默认
        val env = envelope("""{"code":0,"data":{}}""")
        val data = env.decodeData<UjingOrderDetailData>()
        // 有 data 但无有效内容 → 字段全默认（status 空）
        assertTrue(data != null && data.status.isNullOrBlank())
    }
}
