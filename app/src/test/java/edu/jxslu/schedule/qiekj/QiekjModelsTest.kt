package edu.jxslu.schedule.qiekj

import edu.jxslu.schedule.data.qiekj.ApiEnvelope
import edu.jxslu.schedule.data.qiekj.BalanceData
import edu.jxslu.schedule.data.qiekj.DeviceItem
import edu.jxslu.schedule.data.qiekj.LoginData
import edu.jxslu.schedule.data.qiekj.NoDataPollAction
import edu.jxslu.schedule.data.qiekj.OrderDetailData
import edu.jxslu.schedule.data.qiekj.OrderHistoryItem
import edu.jxslu.schedule.data.qiekj.QIEKJ_EMPTY_DATA_ABORT_STREAK
import edu.jxslu.schedule.data.qiekj.QIEKJ_EMPTY_DATA_SETTLE_STREAK
import edu.jxslu.schedule.data.qiekj.QiekjJson
import edu.jxslu.schedule.data.qiekj.SyncData
import edu.jxslu.schedule.data.qiekj.TokenExpiredException
import edu.jxslu.schedule.data.qiekj.isSessionFinished
import edu.jxslu.schedule.data.qiekj.noDataPollAction
import edu.jxslu.schedule.data.qiekj.shouldSettleOnManualStop
import edu.jxslu.schedule.domain.PromotionLine
import edu.jxslu.schedule.domain.UnlockResult
import edu.jxslu.schedule.domain.calculateActualCost
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 响应包与脏数据容错测试（DESIGN §4.10 决策 1）：
 * 服务端会把 `data` 回成空串、数字字段回成裸数字，解析必须不炸且语义正确。
 */
class QiekjModelsTest {

    // ── ApiEnvelope ──

    @Test
    fun requireData_有data直接返回() {
        assertEquals("ok", ApiEnvelope(data = "ok").requireData())
    }

    @Test(expected = IllegalStateException::class)
    fun requireData_空data抛业务错误() {
        ApiEnvelope<String>(code = 400, msg = "参数错误").requireData()
    }

    @Test
    fun throwIfFailed_0与200都算成功() {
        ApiEnvelope<String>(code = 0).throwIfFailed()
        ApiEnvelope<String>(code = 200).throwIfFailed()
        ApiEnvelope<String>(code = null).throwIfFailed()
    }

    @Test(expected = TokenExpiredException::class)
    fun throwIfFailed_401抛TokenExpired() {
        ApiEnvelope<String>(code = 401, msg = "token invalid").throwIfFailed()
    }

    @Test
    fun isTokenExpired_按码与文案启发式判定() {
        assertTrue(TokenExpiredException.isTokenExpired(401, null))
        assertTrue(TokenExpiredException.isTokenExpired(403, null))
        assertTrue(TokenExpiredException.isTokenExpired(null, "请先登录"))
        assertTrue(TokenExpiredException.isTokenExpired(null, "Token已过期"))
        assertTrue(TokenExpiredException.isTokenExpired(null, "token 无效"))
        assertFalse(TokenExpiredException.isTokenExpired(null, "今日次数已用完"))
        assertFalse(TokenExpiredException.isTokenExpired(0, "成功"))
    }

    // ── 脏数据容错 ──

    @Test
    fun envelope_data为空串时EmptyData正常解码() {
        val env = QiekjJson.json.decodeFromString<ApiEnvelope<edu.jxslu.schedule.data.qiekj.EmptyData>>(
            """{"code":0,"msg":"ok","data":""}""",
        )
        assertEquals(0, env.code)
        assertEquals(edu.jxslu.schedule.data.qiekj.EmptyData(), env.data)
    }

    @Test
    fun envelope_data为对象或null时EmptyData正常解码() {
        QiekjJson.json.decodeFromString<ApiEnvelope<edu.jxslu.schedule.data.qiekj.EmptyData>>(
            """{"code":0,"data":{"anything":1}}""",
        )
        QiekjJson.json.decodeFromString<ApiEnvelope<edu.jxslu.schedule.data.qiekj.EmptyData>>(
            """{"code":0,"data":null}""",
        )
    }

    @Test
    fun loginData_token为裸数字时按字符串读() {
        val data = QiekjJson.json.decodeFromString<ApiEnvelope<LoginData>>(
            """{"code":0,"data":{"token":123456}}""",
        )
        assertEquals("123456", data.requireData().token)
    }

    @Test
    fun balanceData_分转元与未知字段忽略() {
        val data = QiekjJson.json.decodeFromString<ApiEnvelope<BalanceData>>(
            """{"code":0,"data":{"tokenCoin":"1234","integral":500,"extraField":"ignored"}}""",
        )
        val balance = data.requireData()
        assertEquals("12.34", balance.ticketText)
        assertEquals("500", balance.pointsText)
    }

    @Test
    fun balanceData_空值显示占位符() {
        val balance = BalanceData()
        assertEquals("-", balance.ticketText)
        assertEquals("-", balance.pointsText)
        assertNull(balance.pointsDeductText)
    }

    @Test
    fun balanceData_积分折算金额统一到两位小数() {
        // integralAmount 是服务端直给的「积分可抵价格」（元），样本形态不可控：
        // 裸数字、整数串、多位小数都归一到两位
        assertEquals("3.09", BalanceData(integralAmount = "3.09").pointsDeductText)
        assertEquals("5.00", BalanceData(integralAmount = "5").pointsDeductText)
        assertEquals("1.25", BalanceData(integralAmount = "1.250000").pointsDeductText)
        // 裸数字（非字符串）走 LenientStringSerializer，与其余字段同一套容错
        val balance = QiekjJson.json.decodeFromString<ApiEnvelope<BalanceData>>(
            """{"code":0,"data":{"integralAmount":5.0}}""",
        ).requireData()
        assertEquals("5.00", balance.pointsDeductText)
    }

    @Test
    fun balanceData_积分折算为0或取不到时不显示() {
        // 舍入到分后为 0.00 的显示出来只是噪音（「约可抵扣 ¥0.00」）；
        // 解析失败也不能把脏串放进 UI（别显示「¥abc」）
        assertNull(BalanceData(integralAmount = "0").pointsDeductText)
        assertNull(BalanceData(integralAmount = "0.00").pointsDeductText)
        assertNull(BalanceData(integralAmount = "0.004").pointsDeductText)
        assertNull(BalanceData(integralAmount = "").pointsDeductText)
        assertNull(BalanceData(integralAmount = "abc").pointsDeductText)
    }

    // ── 会话状态与使用量（2026-10-05 实测字段） ──

    @Test
    fun syncWater_会话状态与使用量可解码() {
        // 社区文档记录的结束态样本：status=5、workStatus=1、amount=85（非我们抓的原文）
        val env = QiekjJson.json.decodeFromString<ApiEnvelope<SyncData>>(
            """{"code":0,"data":{"status":5,"workStatus":1,"amount":85,"identify":"NO"}}""",
        )
        val data = env.requireData()
        assertEquals(5, data.status)
        assertEquals(1, data.workStatus)
        assertEquals("85", data.amount)
    }

    @Test
    fun syncWater_出水中amount为null不报错() {
        val env = QiekjJson.json.decodeFromString<ApiEnvelope<SyncData>>(
            """{"code":0,"data":{"status":1,"workStatus":2,"amount":null,"identify":null}}""",
        )
        val data = env.requireData()
        assertEquals(2, data.workStatus)
        assertEquals(null, data.amount)
    }

    // ── 「结束用水」的收尾判定（2026-10-05） ──

    @Test
    fun 会话结束判定只认status5() {
        assertTrue(isSessionFinished(SyncData(status = 5, workStatus = 1, amount = "275")))
        // 出水中（断电的设备也长这样）不算结束
        assertFalse(isSessionFinished(SyncData(status = 1, workStatus = 2, identify = "NO")))
        assertFalse(isSessionFinished(SyncData(status = 6, workStatus = 1)))
        assertFalse(isSessionFinished(null))
    }

    @Test
    fun 手动结束_read到账单才结算() {
        // 设备刚好回报结束且带单号 → 照常建单读账单
        assertTrue(shouldSettleOnManualStop(SyncData(status = 5, workStatus = 1, identify = "NO")))
        // 结束态但没单号 → 没账单可读，按未确认收尾
        assertFalse(shouldSettleOnManualStop(SyncData(status = 5, workStatus = 1, identify = null)))
        // 还在出水 → 未确认收尾
        assertFalse(shouldSettleOnManualStop(SyncData(status = 1, workStatus = 2, identify = "NO")))
        assertFalse(shouldSettleOnManualStop(null))
    }

    // ── 轮询无 data 的处置（2026-10-09 实测：设备侧结算后 sync 回 code=0 / data=null） ──

    @Test
    fun 轮询响应_无data不是业务失败() {
        // 设备侧结算后的响应原文形态：HTTP 200、code=0、msg=成功、data=null
        val env = QiekjJson.json.decodeFromString<ApiEnvelope<SyncData>>(
            """{"code":0,"msg":"成功","data":null}""",
        )
        env.throwIfFailed()
        assertNull(env.data)
    }

    @Test
    fun 轮询无数据_见过出水后连续两次才判定会话结束() {
        // 单次空数据按抖动处理，不急着收尾
        assertEquals(NoDataPollAction.KeepWaiting, noDataPollAction(everWorked = true, emptyStreak = 1))
        assertEquals(
            NoDataPollAction.Settle,
            noDataPollAction(everWorked = true, emptyStreak = QIEKJ_EMPTY_DATA_SETTLE_STREAK),
        )
    }

    @Test
    fun 轮询无数据_从未见到会话时先等满阈值再报设备未响应() {
        // 设备可能还没被平台登记：阈值内继续轮询，不误报
        assertEquals(NoDataPollAction.KeepWaiting, noDataPollAction(everWorked = false, emptyStreak = 1))
        assertEquals(
            NoDataPollAction.KeepWaiting,
            noDataPollAction(everWorked = false, emptyStreak = QIEKJ_EMPTY_DATA_ABORT_STREAK - 1),
        )
        assertEquals(
            NoDataPollAction.AbortOffline,
            noDataPollAction(everWorked = false, emptyStreak = QIEKJ_EMPTY_DATA_ABORT_STREAK),
        )
    }

    // ── 订单快照序列化往返 ──

    @Test
    fun orderHistoryItem_roundTrip() {
        val item = OrderHistoryItem(
            orderNo = "NO001",
            orderId = "ID001",
            goodsName = "教学楼饮水机",
            originPrice = "0.30",
            ticketCost = "0.20",
            integralCost = "0.10",
            otherPromotions = listOf(PromotionLine(promotionType = 12, discountAmount = "0.05")),
            completedAt = 1_726_570_000_000L,
        )
        val json = QiekjJson.json.encodeToString(
            ListSerializer(OrderHistoryItem.serializer()),
            listOf(item),
        )
        val restored = QiekjJson.json.decodeFromString(
            ListSerializer(OrderHistoryItem.serializer()),
            json,
        )
        assertEquals(listOf(item), restored)
    }

    // ── 金额计算 ──

    private fun result(origin: String, integral: String, ticket: String = "-") = UnlockResult(
        orderNo = "NO", orderId = "ID", originPrice = origin,
        ticketCost = ticket, integralCost = integral, completedAt = 0L,
    )

    @Test
    fun actualCost_浮点误差回归_0点12减0点11等于0点01() {
        assertEquals("0.01", calculateActualCost(result("0.12", "0.11")))
    }

    @Test
    fun actualCost_全免与超减夹零() {
        assertEquals("0.00", calculateActualCost(result("0.12", "0.12")))
        assertEquals("0.00", calculateActualCost(result("0.05", "0.11")))
    }

    @Test
    fun actualCost_原价不可解析时原样返回() {
        assertEquals("-", calculateActualCost(result("-", "0.10")))
    }

    // ── 服务端账单口径（DESIGN §4.10，2026-09-20 真机实测回填） ──

    @Test
    fun orderDetail_真实响应形态解析() {
        // 脱敏后的 order/detail 真实结构：后付单现金实付 0.00，小票（余额）抵扣 0.09
        val data = QiekjJson.json.decodeFromString<ApiEnvelope<OrderDetailData>>(
            """
            {"code":0,"msg":"成功","data":{
              "orderNo":"NO","orderType":3,"orderStatus":2,
              "markPrice":"0.09","payPrice":"0.00","payType":15,"payTypeName":"支付宝-代扣",
              "tokenCoinDiscount":"0.09",
              "tradeOrderItem":[{"originPrice":"0.09","realPrice":"0.00"}],
              "promotionList":[{"promotionType":4,"discountAmount":"0.09","subsidyAmount":"0.00"}]
            }}
            """.trimIndent(),
        )
        val detail = data.requireData()
        assertEquals("0.00", detail.payPrice)
        assertEquals("支付宝-代扣", detail.payTypeName)
        assertEquals("0.09", detail.tokenCoinDiscount)
        assertEquals("0.09", detail.tradeOrderItem.first().originPrice)
        assertEquals("0.00", detail.tradeOrderItem.first().realPrice)
        assertEquals(4, detail.promotionList.first().promotionType)
    }

    // ── 账单口径（DESIGN §4.10，2026-09-21 修订：小票是余额支付，不是平台优惠） ──

    @Test
    fun actualCost_小票支付计入花费_真机形态() {
        // 2026-09-21 真机：原价 0.16、小票抵 0.16、现金实付 0.00（支付宝-代扣）
        // —— 用户实际花了 0.16（小票余额），不能显示成「实付 ¥0.00」
        val r = result("0.16", "-", ticket = "0.16")
            .copy(realPrice = "0.00", payTypeName = "支付宝-代扣", tokenCoinDiscount = "0.16")
        assertEquals("0.16", calculateActualCost(r))
    }

    @Test
    fun actualCost_小票与现金混合支付() {
        val r = result("0.16", "-", ticket = "0.06").copy(realPrice = "0.10")
        assertEquals("0.16", calculateActualCost(r))
    }

    @Test
    fun actualCost_纯现金支付() {
        val r = result("0.16", "-").copy(realPrice = "0.16")
        assertEquals("0.16", calculateActualCost(r))
    }

    @Test
    fun actualCost_积分抵扣不计入花费() {
        // 原价 0.16、积分抵 0.10、现金实付 0.06 —— 积分是省下的钱，花费 = 0.06
        val r = result("0.16", "0.10").copy(realPrice = "0.06")
        assertEquals("0.06", calculateActualCost(r))
    }

    @Test
    fun actualCost_小票明细缺失时回退tokenCoinDiscount() {
        val r = result("0.16", "-").copy(realPrice = "0.00", tokenCoinDiscount = "0.16")
        assertEquals("0.16", calculateActualCost(r))
    }

    @Test
    fun actualCost_旧快照无服务端字段时小票照常计入() {
        // 2026-09-19 旧快照：原价 0.09、小票 0.09、无 realPrice/tokenCoinDiscount
        assertEquals("0.09", calculateActualCost(result("0.09", "-", ticket = "0.09")))
    }

    @Test
    fun actualCost_realPrice缺失回退本地公式() {
        assertEquals("0.30", calculateActualCost(result("0.30", "-").copy(realPrice = null)))
    }

    @Test
    fun orderHistoryItem_旧快照无新字段可解码() {
        // 2026-09-20 之前的快照没有 realPrice/payTypeName/tokenCoinDiscount，decode 必须兼容
        val legacy = """[{"orderNo":"NO","orderId":"ID","goodsName":"设备","originPrice":"0.09",
            "ticketCost":"0.09","integralCost":"-","otherPromotions":[],"completedAt":100}]"""
        val restored = QiekjJson.json.decodeFromString(
            ListSerializer(OrderHistoryItem.serializer()),
            legacy,
        )
        assertEquals(1, restored.size)
        assertEquals(null, restored[0].realPrice)
        assertEquals(null, restored[0].payTypeName)
    }

    @Test
    fun orderHistoryItem_新字段往返() {
        val item = OrderHistoryItem(
            orderNo = "NO", orderId = "ID", goodsName = "设备",
            originPrice = "0.09", ticketCost = "-", integralCost = "-",
            completedAt = 0L, realPrice = "0.00",
            payTypeName = "支付宝-代扣", tokenCoinDiscount = "0.09",
        )
        val json = QiekjJson.json.encodeToString(
            ListSerializer(OrderHistoryItem.serializer()),
            listOf(item),
        )
        val restored = QiekjJson.json.decodeFromString(
            ListSerializer(OrderHistoryItem.serializer()),
            json,
        )
        assertEquals(listOf(item), restored)
    }
}
