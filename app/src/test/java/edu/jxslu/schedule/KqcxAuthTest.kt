package edu.jxslu.schedule

import edu.jxslu.schedule.domain.KqcxAuth
import edu.jxslu.schedule.domain.KvcBusinessError
import edu.jxslu.schedule.domain.KvcProtocolException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 快趣登录与骑行查询的解析口径（DESIGN §4.32，2026-09-28）：信封判定、登录/查询
 * 响应解析、宽容 result 形态、MD5 口径。fixture 全部是合成数据，不含真实账号。
 * 纯 JVM，口径见 [KqcxAuth]（协议逆向记录在 `docs/kvcoo-miniprogram-analysis.md`）。
 */
class KqcxAuthTest {

    // ── 信封判定 ──

    @Test
    fun `信封判定逐字对齐小程序 client`() {
        // resultCode==1 && errorCode==0 才是成功；字段缺失按失败
        assertTrue(KqcxAuth.isSuccess(1, 0))
        assertFalse(KqcxAuth.isSuccess(1, null))
        assertFalse(KqcxAuth.isSuccess(null, 0))
        assertFalse(KqcxAuth.isSuccess(0, 0))
        assertFalse(KqcxAuth.isSuccess(1, 30015))
    }

    @Test
    fun `token 疑似失效的保守启发式`() {
        assertTrue(KqcxAuth.looksLikeTokenError("请先登录"))
        assertTrue(KqcxAuth.looksLikeTokenError("token 已过期"))
        assertTrue(KqcxAuth.looksLikeTokenError("身份校验失败"))
        // 业务错误不能误判成 token 失效（否则会反复重登）
        assertFalse(KqcxAuth.looksLikeTokenError("车辆不存在"))
        assertFalse(KqcxAuth.looksLikeTokenError(null))
    }

    // ── 登录解析 ──

    @Test
    fun `登录响应解析`() {
        val json = """{"resultCode":1,"errorCode":0,"resultMsg":"ok",
            "result":{"token":"abc123","mobile":"13800000000","passwordStatus":true}}"""
        val result = KqcxAuth.parseLogin(json)
        assertEquals("abc123", result.token)
        assertEquals("13800000000", result.mobile)
    }

    @Test
    fun `登录响应未知字段与缺失字段`() {
        // ignoreUnknownKeys：result 里多出的字段不炸
        val withExtra = """{"resultCode":1,"errorCode":0,
            "result":{"token":"t","mobile":"m","extraField":{"x":1}}}"""
        assertEquals("t", KqcxAuth.parseLogin(withExtra).token)
        // mobile 缺失给空串（调用方回填输入的手机号）
        val noMobile = """{"resultCode":1,"errorCode":0,"result":{"token":"t"}}"""
        assertEquals("", KqcxAuth.parseLogin(noMobile).mobile)
    }

    @Test
    fun `登录业务错误抛 KvcBusinessError`() {
        val json = """{"resultCode":0,"errorCode":10001,"resultMsg":"密码错误"}"""
        try {
            KqcxAuth.parseLogin(json)
            throw AssertionError("应当抛业务错误")
        } catch (e: KvcBusinessError) {
            assertEquals(10001, e.errorCode)
            assertEquals("密码错误", e.message)
        }
    }

    @Test
    fun `非信封响应抛协议异常`() {
        try {
            KqcxAuth.parseLogin("<html>nginx 404</html>")
            throw AssertionError("应当抛协议异常")
        } catch (_: KvcProtocolException) {
        }
    }

    // ── 骑行查询解析 ──

    @Test
    fun `骑行订单解析`() {
        val json = """{"resultCode":1,"errorCode":0,
            "result":{"carNum":"100000669","bluetoothName":"KVC-0669","beginTime":"x"}}"""
        val ride = KqcxAuth.parseUnderway(json)!!
        assertEquals("100000669", ride.carNum)
        assertEquals("KVC-0669", ride.bluetoothName)
    }

    @Test
    fun `字段类型不稳定也能解析（真机回归）`() {
        // 2026-09-28 真机：小程序开车后查询直接抛「骑行订单字段解析失败」——
        // 后端字段类型不稳定（数值给字符串 / 带小数点的 number、布尔给 0/1）。
        // 宽容取值必须全吃下，且不能因为未知字段（含数组/嵌套对象）翻车。
        val json = """{"resultCode":1,"errorCode":0,"result":{
            "carNum":100000669,"bluetoothName":"KVC-0669","totalDate":"3665",
            "payMoney":150.0,"lat":"28.6832","lng":115.9123,"lockStatus":true,
            "currentPercent":85.0,"phone":13800000000,"subList":["a","b"],
            "nested":{"x":1}}}"""
        val ride = KqcxAuth.parseUnderway(json)!!
        assertEquals("100000669", ride.carNum)
        assertEquals(3665L, ride.totalDateSeconds)
        assertEquals(150L, ride.payMoneyCents)
        assertEquals(28.6832, ride.lat!!, 1e-9)
        assertEquals(115.9123, ride.lng!!, 1e-9)
        assertEquals(true, ride.locked)
        assertEquals(85, ride.batteryPercent)
    }

    @Test
    fun `骑行订单完整字段`() {
        // 字段名与单位来自小程序骑行页消费代码：totalDate 秒、payMoney 分、lockStatus 1=锁
        val json = """{"resultCode":1,"errorCode":0,"result":{
            "carNum":"100000669","bluetoothName":"KVC-0669","totalDate":3665,
            "payMoney":150,"lat":28.6832,"lng":115.9123,"lockStatus":1,"currentPercent":85}}"""
        val ride = KqcxAuth.parseUnderway(json)!!
        assertEquals(3665L, ride.totalDateSeconds)
        assertEquals(150L, ride.payMoneyCents)
        assertEquals(28.6832, ride.lat!!, 1e-9)
        assertEquals(115.9123, ride.lng!!, 1e-9)
        assertEquals(true, ride.locked)
        assertEquals(85, ride.batteryPercent)
    }

    @Test
    fun `无订单错误码 12003 按无骑行处理`() {
        // 解包确认：小程序 catch 分支 case 12003 → processEndOrder（无进行中订单）
        val json = """{"resultCode":0,"errorCode":12003,"resultMsg":"订单已结束"}"""
        assertNull(KqcxAuth.parseUnderway(json))
        // 其余业务错误照抛
        try {
            KqcxAuth.parseUnderway("""{"resultCode":0,"errorCode":9999,"resultMsg":"其他错误"}""")
            throw AssertionError("应当抛业务错误")
        } catch (_: KvcBusinessError) {
        }
    }

    @Test
    fun `时长文案逐字对齐 simplehumantime`() {
        // 小程序怪渲染：秒 ceil 成分钟数填进「分」段，首段恒 00
        // 3865s = ceil 65 分 → "00:01:05"；60s → 1 分 → "00:01"
        assertEquals("00:01:05", KqcxAuth.formatRideDuration(3865))
        assertEquals("00:01", KqcxAuth.formatRideDuration(60))
        assertEquals("00:01", KqcxAuth.formatRideDuration(1))
        assertEquals("00:00", KqcxAuth.formatRideDuration(0))
        // 负数脏数据兜底成 00:00；null 透传
        assertEquals("00:00", KqcxAuth.formatRideDuration(-5))
        assertNull(KqcxAuth.formatRideDuration(null))
    }

    @Test
    fun `无骑行订单的三种宽容形态`() {
        // result 为 null
        assertNull(KqcxAuth.parseUnderway("""{"resultCode":1,"errorCode":0,"result":null}"""))
        // result 为空串（胖乖 EmptyData 同款脏形态）
        assertNull(KqcxAuth.parseUnderway("""{"resultCode":1,"errorCode":0,"result":""}"""))
        // result 为空对象
        assertNull(KqcxAuth.parseUnderway("""{"resultCode":1,"errorCode":0,"result":{}}"""))
    }

    @Test
    fun `result 缺 carNum 不当骑行在案`() {
        assertNull(
            KqcxAuth.parseUnderway("""{"resultCode":1,"errorCode":0,"result":{"foo":1}}"""),
        )
        // bluetoothName 空串归一成 null
        val ride = KqcxAuth.parseUnderway(
            """{"resultCode":1,"errorCode":0,"result":{"carNum":"100000669","bluetoothName":""}}""",
        )!!
        assertNull(ride.bluetoothName)
    }

    // ── MD5 口径 ──

    @Test
    fun `密码 MD5 是标准小写 hex`() {
        // RFC 1321 经典向量
        assertEquals("5d41402abc4b2a76b9719d911017c592", KqcxAuth.passwordCipher("hello"))
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", KqcxAuth.passwordCipher(""))
    }

    // ── 创建订单 / 还车 / 未支付（B/C 档） ──

    @Test
    fun `创建订单解析含头盔分支`() {
        val normal = """{"resultCode":1,"errorCode":0,"result":{
            "carNum":"100000669","helmet":0,"helmetConfig":0,"subList":["t1"]}}"""
        val created = KqcxAuth.parseCreatedOrder(normal)
        assertEquals("100000669", created.carNum)
        assertFalse(created.helmetFlowRequired)

        val helmet = """{"resultCode":1,"errorCode":0,"result":{
            "carNum":"100000669","helmet":1,"helmetConfig":"1"}}"""
        assertTrue(KqcxAuth.parseCreatedOrder(helmet).helmetFlowRequired)

        // 缺车号 = 协议异常（不静默吞）
        try {
            KqcxAuth.parseCreatedOrder("""{"resultCode":1,"errorCode":0,"result":{}}""")
            throw AssertionError("应当抛协议异常")
        } catch (_: KvcProtocolException) {
        }
    }

    @Test
    fun `还车成功解析支付状态`() {
        val needPay = """{"resultCode":1,"errorCode":0,"result":{
            "needPay":true,"wechatScore":true,"payMoney":150}}"""
        val ended = KqcxAuth.parseEndOrder(needPay) as KqcxAuth.EndOutcome.Ended
        assertEquals(true, ended.needPay)
        assertEquals(true, ended.wechatScore)

        // 免费时段还车：订单无需支付（对照先享后付「服务完成通知」的语义）
        val free = """{"resultCode":1,"errorCode":0,"result":{"needPay":false}}"""
        assertEquals(false, (KqcxAuth.parseEndOrder(free) as KqcxAuth.EndOutcome.Ended).needPay)
    }

    @Test
    fun `还车被拒解析调度费与出围栏`() {
        // 官方「调度费」分支：错误里带 result.dispatchMoney（小程序弹确认面板，App 降级）
        val dispatch = """{"resultCode":0,"errorCode":50012,"resultMsg":"需支付调度费",
            "result":{"dispatchMoney":200,"payMoney":350}}"""
        val rejected = KqcxAuth.parseEndOrder(dispatch) as KqcxAuth.EndOutcome.Rejected
        assertEquals(50012, rejected.code)
        assertEquals(200L, rejected.dispatchMoneyCents)

        // 官方「出围栏」错误码 50011
        val outOfArea = """{"resultCode":0,"errorCode":50011,"resultMsg":"不在还车区域"}"""
        val rejected2 = KqcxAuth.parseEndOrder(outOfArea) as KqcxAuth.EndOutcome.Rejected
        assertEquals(50011, rejected2.code)
        assertNull(rejected2.dispatchMoneyCents)
    }

    @Test
    fun `未支付查询含 12004 结清口径`() {
        val owed = """{"resultCode":1,"errorCode":0,"result":{"unPayMoney":350,"orderNum":"x"}}"""
        assertEquals(350L, (KqcxAuth.parseUnpayState(owed) as KqcxAuth.UnpayState.Owed).amountCents)

        // 金额 0 也是结清
        val zero = """{"resultCode":1,"errorCode":0,"result":{"unPayMoney":0}}"""
        assertTrue(KqcxAuth.parseUnpayState(zero) is KqcxAuth.UnpayState.Settled)

        // 12004 = 无未支付订单（官方扣款确认循环的结束条件）
        val none = """{"resultCode":0,"errorCode":12004,"resultMsg":"无未支付订单"}"""
        assertTrue(KqcxAuth.parseUnpayState(none) is KqcxAuth.UnpayState.Settled)
    }

    @Test
    fun `用车错误码映射为可操作文案`() {
        // 表格命中：给固定文案（不依赖服务端措辞）
        assertTrue(KqcxAuth.errorMessage(11004, "x").contains("实名"))
        assertTrue(KqcxAuth.errorMessage(12022, "x").contains("进行中的订单"))
        assertTrue(KqcxAuth.errorMessage(11035, "x").contains("免押"))
        assertTrue(KqcxAuth.errorMessage(50011, "x").contains("还车区域"))
        assertTrue(KqcxAuth.errorMessage(16015, "x").contains("头盔"))
        assertTrue(KqcxAuth.errorMessage(20001, "x").contains("登录"))
        // 表外：回退服务端原文，不编解释
        assertEquals("服务端说了什么", KqcxAuth.errorMessage(123456, "服务端说了什么"))
        assertEquals("兜底", KqcxAuth.errorMessage(null, "兜底"))
    }
}
