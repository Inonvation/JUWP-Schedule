package edu.jxslu.schedule.data.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缴费平台响应解析（DESIGN §4.24）。
 *
 * fixture 全部取自 2026-09-23 实测原文（只裁掉无关字段），字段改名/改类型时这里先红。
 */
class PowerModelsTest {

    private val feeItemJson = """
        {"msg":"success","view":"choose","code":200,
         "sceneinfo":"campus:0#南昌工程学院;building:0#9A;room:14600#9A101",
         "feeitem":{"feeitemid":181,"name":"房间电费","price":0.62,"billing_unit":"度",
           "impl_interface":"iECSceneServiceImpl","feetypeBean":{"feetypeid":142,"name":"生活缴费"}}}
    """.trimIndent()

    private val meterJson = """
        {"msg":"success","code":200,"map":{"showData":{"当前剩余电量":"55.57"},
         "data":{"campus":"江西水利电力大学","tsmAbstract":"校区#江西水利电力大学;楼栋#9A;房间#9A101",
          "campusid":"0","yktmercacc":"1000001","remark":"{\"当前剩余电量\":\"55.57\"}","sroomid":2887,
          "building":"9A","roomid":"14600","room":"9A101","buildingid":"0"},"dataType":"IEC"}}
    """.trimIndent()

    private val turnoverJson = """
        {"msg":"success","code":200,"list":[
          {"turnoverid":4964633,"feeitemid":181,"payid":4,"feerange":"202608","tranamt":20,
           "createdate":"2026-08-25 12:20:23","abstracts":"校区-江西水利电力大学;楼栋-9A;房间-9A101"},
          {"turnoverid":3955331,"feeitemid":181,"payid":4,"feerange":"202507","tranamt":50,
           "createdate":"2025-07-03 13:08:57","abstracts":"校区-江西水利电力大学;楼栋-9A;房间-9A101"}]}
    """.trimIndent()

    @Test
    fun feeItemCarriesPriceUnitAndBoundRoom() {
        val item = PowerModels.parseFeeItem(feeItemJson)
        assertEquals(181, item.id)
        assertEquals("房间电费", item.name)
        assertEquals(0.62, item.priceYuan!!, 1e-9)
        assertEquals("度", item.unit)
        assertEquals("生活缴费", item.feetypeName)
        assertEquals(listOf("campus", "building", "room"), item.scene.map { it.code })
        // sceneinfo 里的校区名是学校旧名，房间名照抄；真实房间名以读数返回为准
        assertEquals("14600", item.room?.id)
        assertEquals("9A101", item.room?.name)
    }

    @Test
    fun meterReadsRemainFromChineseFields() {
        val meter = PowerModels.parseMeter(meterJson, nowMs = 1234L)
        assertEquals(55.57, meter.remain!!, 1e-9)
        assertEquals("当前剩余电量", meter.remainField)
        assertEquals("9A101", meter.room.room)
        assertEquals("9A", meter.room.building)
        assertEquals("江西水利电力大学", meter.room.campus)
        assertEquals("14600", meter.room.roomId)
        assertEquals(1234L, meter.fetchedAtMs)
        // 原始字段整份留着：平台加字段时只多键，展示层仍能拿到
        assertEquals(mapOf("当前剩余电量" to "55.57"), meter.fields)
    }

    /** 参数不全时平台回的是 `code=500 未知异常`，不是网络错误——解析层要能区分。 */
    @Test
    fun incompleteParamsEnvelopeIsNotAMeter() {
        val raw = """{"msg":"未知异常，请联系管理员","code":500}"""
        assertEquals(500, PowerModels.codeOf(raw))
        assertEquals("未知异常，请联系管理员", PowerModels.messageOf(raw))
        assertThrows(PowerException.Protocol::class.java) { PowerModels.parseMeter(raw, 0L) }
    }

    @Test
    fun turnoverParsesAmountsAndRoomLabel() {
        val rows = PowerModels.parseTurnovers(turnoverJson)
        assertEquals(2, rows.size)
        // 升序：早的在前
        assertEquals("2025-07-03 13:08:57", rows[0].dateText)
        assertEquals(5000L, rows[0].amountFen)
        assertEquals("9A101", PowerModels.roomLabelOf(rows[0].room))
        assertEquals(2000L, rows[1].amountFen)
        assertEquals("202608", rows[1].month)
        assertTrue("充值时间应能解析成 epoch 毫秒", rows[1].epochMs > 0L)
    }

    @Test
    fun remainFallsBackToWeakerKeys() {
        assertEquals(12.5 to "剩余电量", PowerModels.pickRemain(mapOf("剩余电量" to "12.5")))
        assertEquals(3.0 to "可用余额", PowerModels.pickRemain(mapOf("可用余额" to "3.0")))
        assertEquals(null to null, PowerModels.pickRemain(mapOf("表号" to "A12")))
        // 键对了但值不是数：当认不出，不给 0
        assertEquals(null to null, PowerModels.pickRemain(mapOf("当前剩余电量" to "暂无")))
    }

    @Test
    fun sceneInfoToleratesGarbage() {
        assertEquals(emptyList<PowerSceneKey>(), PowerModels.parseSceneInfo(null))
        assertEquals(emptyList<PowerSceneKey>(), PowerModels.parseSceneInfo("  "))
        assertEquals(
            listOf(PowerSceneKey("room", "1", "A101")),
            PowerModels.parseSceneInfo(";;room:1#A101;  "),
        )
    }

    @Test
    fun loginTokenAndTimeParsing() {
        assertEquals(
            "abc",
            PowerModels.parseToken("""{"access_token":"abc","token_type":"bearer"}"""),
        )
        assertNull(PowerModels.parseToken("""{"error":"unauthorized"}"""))
        assertEquals(0L, PowerModels.parseTimeMs(""))
        assertEquals(0L, PowerModels.parseTimeMs("2026-08-25"))
    }
}
