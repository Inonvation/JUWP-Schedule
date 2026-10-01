package edu.jxslu.schedule.data.ujing

import edu.jxslu.schedule.data.qiekj.QiekjJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** U净 响应解析（DESIGN §4.37）：响应壳、宽容数值 / 布尔、会话失效判定。 */
class UjingApiParseTest {

    private fun envelope(text: String): UjingEnvelope =
        QiekjJson.json.decodeFromString(UjingEnvelope.serializer(), text)

    // ---- 响应壳 ----

    @Test
    fun parsesEnvelopeAndDecodesScanResult() {
        val env = envelope(
            """{"code":0,"message":"ok","data":{"result":{"deviceId":"dev1","createOrderEnabled":true}}}""",
        )
        assertEquals(0, env.code)
        val scan = env.decodeData<UjingScanData>()
        assertEquals("dev1", scan?.result?.deviceId)
        assertTrue(scan?.result?.createOrderEnabled == true)
    }

    @Test
    fun lenientNumbersAndBooleans() {
        val env = envelope(
            """{"code":0,"data":{"result":{"deviceId":"d","createOrderEnabled":1,"moduleType":"1"}}}""",
        )
        val scan = env.decodeData<UjingScanData>()
        assertTrue(scan?.result?.createOrderEnabled == true)
        assertEquals(1, scan?.result?.moduleType)
    }

    @Test
    fun decodeDataToleratesUnexpectedShape() {
        // data 是数组而不是对象：宽容返回 null，不抛
        val env = envelope("""{"code":0,"data":[1,2,3]}""")
        assertNull(env.decodeData<UjingScanData>())
        // 完全没有 data 字段
        assertNull(envelope("""{"code":0}""").decodeData<UjingScanData>())
    }

    // ---- 业务 DTO ----

    @Test
    fun deviceGroupsReadStringOrNumberStats() {
        val env = envelope(
            """{"code":0,"data":{"devices":[{"device":{"deviceTypeName":"滚筒洗衣机","free":"2","total":4,"waitTime":"15"}}]}}""",
        )
        val groups = env.decodeData<UjingReserveData>()!!.devices.mapNotNull { it.device }
        assertEquals(1, groups.size)
        assertEquals("滚筒洗衣机", groups[0].deviceTypeName)
        assertEquals(2, groups[0].free)
        assertEquals(4, groups[0].total)
        assertEquals(15, groups[0].waitTime)
    }

    @Test
    fun storeListFiltersIncompleteEntries() {
        val env = envelope(
            """{"code":0,"data":{"storeList":[{"id":"s1","name":"3 舍 2 层"},{"id":null,"name":"无 id"},{"id":"s3","name":""}]}}""",
        )
        val stores = env.decodeData<UjingStoreListData>()!!.storeList.filter { it.isComplete }
        assertEquals(1, stores.size)
        assertEquals("s1", stores[0].id)
    }

    @Test
    fun washModelsParsePricesAndHide() {
        val env = envelope(
            """{"code":0,"data":{"deviceTypeName":"滚筒洗衣机","storeId":"s1","deviceWashModel":[
                {"workModelId":5,"workModelName":"普通洗","basePrice":278,"time":35,"hide":false},
                {"workModelId":9,"workModelName":"内部模式","basePrice":100,"time":10,"hide":true}
            ]}}""",
        )
        val program = env.decodeData<UjingProgramData>()!!
        assertEquals("s1", program.storeId)
        assertEquals(2, program.deviceWashModel.size)
        assertEquals(278, program.deviceWashModel[0].basePrice)
        assertFalse(program.deviceWashModel[0].hide)
        assertTrue(program.deviceWashModel[1].hide)
    }

    @Test
    fun programFlagsGateOrderExtras() {
        // 2026-10-01 下单可选参数的三个服务端开关：布尔可能以数字形态出现
        val env = envelope(
            """{"code":0,"data":{"type":1,"isWashTemperatureEnable":true,
                "isForceDetergent":1,"isForceDisinfectant":0,"deviceWashModel":[]}}""",
        )
        val program = env.decodeData<UjingProgramData>()!!
        assertTrue(program.isWashTemperatureEnable)
        assertTrue(program.isForceDetergent)
        assertFalse(program.isForceDisinfectant)
        // 字段缺失 = 不开放 / 不强制（宽容默认）
        val bare = envelope("""{"code":0,"data":{"type":2,"deviceWashModel":[]}}""")
            .decodeData<UjingProgramData>()!!
        assertFalse(bare.isWashTemperatureEnable)
        assertFalse(bare.isForceDetergent)
        assertFalse(bare.isForceDisinfectant)
    }

    // ---- 失败与失效 ----

    @Test(expected = UjingApiException::class)
    fun requireSuccessThrowsApiException() {
        envelope("""{"code":-2,"message":"验证码错误"}""").requireSuccess()
    }

    @Test(expected = UjingSessionExpiredException::class)
    fun requireSuccessThrowsExpiredOn401() {
        envelope("""{"code":401,"message":"登录已过期"}""").requireSuccess()
    }

    @Test
    fun sessionExpiredMatchesKnownShapes() {
        assertTrue(UjingSessionExpiredException.matches(401, null))
        assertTrue(UjingSessionExpiredException.matches(-99, null))
        assertTrue(UjingSessionExpiredException.matches(1, "请先登录"))
        assertTrue(UjingSessionExpiredException.matches(1, "登录已失效，请重新登录"))
        assertFalse(UjingSessionExpiredException.matches(0, "成功"))
        assertFalse(UjingSessionExpiredException.matches(-2, "验证码错误"))
    }
}
