package edu.jxslu.schedule

import edu.jxslu.schedule.data.ykt.YktCard
import edu.jxslu.schedule.data.ykt.YktModels
import edu.jxslu.schedule.data.ykt.rechargeTargetCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 一卡通响应解析（DESIGN §4.19）：BOM 剥离、queryCard 卡列表（分转元口径）、
 * turnover 流水记录（typeFrom 语义/金额容错/日期）、分页包装、汇总。
 */
class YktModelsTest {

    // ---- BOM 剥离 ----

    @Test
    fun `剥离 UTF-8 BOM 后正常解析`() {
        val env = YktModels.parseEnvelope("\uFEFF{\"code\":200,\"msg\":\"ok\"}")
        assertEquals(200, env.code)
        assertEquals("ok", env.messageOrBlank)
    }

    @Test
    fun `message 三种字段名取第一个非空`() {
        assertEquals("a", YktModels.parseEnvelope("""{"code":1,"msg":"a"}""").messageOrBlank)
        assertEquals("b", YktModels.parseEnvelope("""{"code":1,"message":"b"}""").messageOrBlank)
        assertEquals("c", YktModels.parseEnvelope(
            """{"code":1,"error_description":"c"}""",
        ).messageOrBlank)
    }

    // ---- queryCard 卡列表 ----

    @Test
    fun `卡余额解析与分转元口径`() {
        val data = YktModels.json.parseToJsonElement(
            """{"card":[{
                "card_name":"校园卡","account":"24***64",
                "db_balance":795,"unsettle_amount":1200,"elec_accamt":0,
                "expdate":"20291015","lostflag":"0"
            }]}""",
        )
        val cards = YktModels.cardsFrom(data)
        assertEquals(1, cards.size)
        val card = cards[0]
        // 余额 = (db + unsettle) / 100 = 19.95 元
        assertEquals(1995L, card.cardBalanceFen)
        assertEquals(0L, card.elecBalanceFen)
        assertEquals("20291015", card.expdate)
        assertEquals("0", card.lostflag)
    }

    @Test
    fun `缺 db_balance 的卡丢弃而非崩溃`() {
        val data = YktModels.json.parseToJsonElement("""{"card":[{"account":"x"},{}]}""")
        assertTrue(YktModels.cardsFrom(data).isEmpty())
    }

    @Test
    fun `data 缺 card 数组回空列表`() {
        assertTrue(YktModels.cardsFrom(null).isEmpty())
        assertTrue(YktModels.cardsFrom(YktModels.json.parseToJsonElement("{}")).isEmpty())
    }

    // ---- turnover 流水 ----

    @Test
    fun `流水记录解析与方向语义`() {
        val data = YktModels.json.parseToJsonElement(
            """{"total":726,"pages":73,"records":[
                {"jndatetimeStr":"2026-09-20 18:50:11","tranamt":800,"typeFrom":"2",
                 "turnoverType":"二维码支付","remark":"二维码=4011***","cardBalance":1195,
                 "locationName":"第一食堂","orderId":"PO20260920001"},
                {"jndatetimeStr":"2026-09-19 10:00:00","tranamt":10000,"typeFrom":"1",
                 "turnoverType":"充值","effectdateStr":"2026-09-19 10:01:00"}
            ]}""",
        )
        val page = YktModels.turnoverPageFrom(data)
        assertEquals(726L, page.total)
        assertEquals(73L, page.pages)
        assertEquals(2, page.records.size)

        val spend = page.records[0]
        assertEquals(800L, spend.tranamtFen)
        assertEquals(false, spend.income) // typeFrom "2" = 支出
        assertEquals("二维码支付", spend.turnoverType)
        assertEquals(1195L, spend.balanceAfterFen)
        assertEquals("第一食堂", spend.locationName)

        val income = page.records[1]
        assertEquals(true, income.income) // typeFrom "1" = 收入
        assertEquals(10000L, income.tranamtFen)
        assertNull(income.balanceAfterFen)
    }

    @Test
    fun `金额字符串数字与缺字段容错`() {
        val data = YktModels.json.parseToJsonElement(
            """{"records":[
                {"tranamt":"950","typeFrom":"2","jndatetimeStr":"2026-09-19 19:11:00"},
                {"jndatetimeStr":"2026-09-18 08:00:00"}
            ]}""",
        )
        val records = YktModels.turnoverPageFrom(data).records
        assertEquals(950L, records[0].tranamtFen)
        // 缺 tranamt 的记录丢弃（无法展示金额）
        assertEquals(1, records.size)
    }

    // ---- 汇总 ----

    @Test
    fun `汇总解析与缺省`() {
        val ok = YktModels.turnoverSummaryFrom(
            YktModels.json.parseToJsonElement("""{"expenses":26800,"income":10000}"""),
        )
        assertEquals(26800L, ok.expensesFen)
        assertEquals(10000L, ok.incomeFen)
        val empty = YktModels.turnoverSummaryFrom(null)
        assertEquals(0L, empty.expensesFen)
        assertEquals(0L, empty.incomeFen)
    }

    // ---- 交易账户（2026-09-28：消费流水「充到哪个账户」）----

    @Test
    fun `流水带出交易账户的卡号与类型`() {
        val data = YktModels.json.parseToJsonElement(
            """{"records":[{"jndatetimeStr":"2026-09-28 12:47:55","tranamt":2000,"typeFrom":"1",
                 "turnoverType":"充值","fromAccount":"241364","accType":"###","orderId":"o1"}]}""",
        )
        val rec = YktModels.turnoverPageFrom(data).records.single()
        assertEquals("241364", rec.fromAccount)
        assertEquals("###", rec.accType)
        assertEquals("正式卡 241364", YktModels.accountLabelOf(rec.accType, rec.fromAccount))
    }

    /**
     * 账户文案口径（**认不出就不显示**）：`###` = 正式卡、`000` = 电子账户（实测只有这两个），
     * 其余值（含 null）返回 null——显示错账户比不显示更糟，用户就是靠它判断有没有充错。
     */
    @Test
    fun `账户文案只认实测的两个类型`() {
        assertEquals("正式卡 241364", YktModels.accountLabelOf("###", "241364"))
        assertEquals("电子账户 241364", YktModels.accountLabelOf("000", "241364"))
        // 没有卡号也能给账户类型；卡号为空串当没有
        assertEquals("正式卡", YktModels.accountLabelOf("###", null))
        assertEquals("电子账户", YktModels.accountLabelOf("000", "  "))
        // 未知类型 / 缺字段 → 不显示
        assertNull(YktModels.accountLabelOf("001", "241364"))
        assertNull(YktModels.accountLabelOf(null, "241364"))
        assertNull(YktModels.accountLabelOf("", "241364"))
    }

    // ---- 充值目标卡（2026-09-28：确认弹窗与下单链路共用这一份判断）----

    @Test
    fun `充值目标卡取第一张非挂失卡`() {
        fun card(account: String, lost: String? = null) = YktCard(
            cardName = "校园卡",
            account = account,
            cardBalanceFen = 0L,
            elecBalanceFen = 0L,
            expdate = null,
            lostflag = lost,
        )
        // 挂失的跳过（lostflag "1"），空值视作未挂失
        val cards = listOf(card("111111", lost = "1"), card("222222"), card("333333", lost = "0"))
        assertEquals("222222", cards.rechargeTargetCard()?.account)
        // 全是挂失 → null（下单链路据此报「没有可充值的卡账户」）
        assertNull(listOf(card("111111", lost = "1")).rechargeTargetCard())
        assertNull(emptyList<YktCard>().rechargeTargetCard())
        // 单张未挂失卡：确认弹窗给用户看的就是它的卡号
        assertEquals("654321", listOf(card("654321", lost = "0")).rechargeTargetCard()?.account)
    }
}
