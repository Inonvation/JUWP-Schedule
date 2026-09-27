package edu.jxslu.schedule.data.qzxy

import edu.jxslu.schedule.domain.QzxyFrame
import edu.jxslu.schedule.domain.QzxyProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用水流程状态机。全部走假链路与假网关，网络与蓝牙都不碰——
 * 这里钉的是分支判定（状态 3 跳过停阀、候选命中即停、连续无回包提前收手），
 * 不是收发本身。
 */
class QzxyWaterFlowTest {

    private val address = "AA:BB:CC:DD:EE:FF"

    /** 可编程的假链路：按功能码回包，没登记的功能码视为超时。 */
    private inner class FakeLink : QzxyWaterFlow.Link {
        var connected = true
        val sent = mutableListOf<Int>()

        /** 查询响应的状态值；每次查询后按脚本推进。 */
        val states = ArrayDeque<Int>()

        /** 各功能码的回包数据体；0x85 默认回一条最小记录，其余默认成功标记。 */
        val bodies = mutableMapOf<Int, ByteArray>()

        override fun isConnectedTo(address: String): Boolean = connected

        override suspend fun request(
            functionCode: Int,
            payload: ByteArray,
            timeoutMillis: Int,
        ): ByteArray? {
            sent += functionCode
            val body = when (functionCode) {
                QzxyProtocol.QUERY_DEVICE -> {
                    if (states.isEmpty()) return null
                    this@QzxyWaterFlowTest.stateBody(states.removeFirst())
                }
                else -> bodies[functionCode] ?: byteArrayOf(0x80.toByte())
            }
            return QzxyFrame.wrap(this@QzxyWaterFlowTest.successFrame(functionCode, body))
        }

        /** 消费记录回包（42 字节短记录的最小形态）。 */
        fun consumeBody(): ByteArray {
            val bytes = ByteArray(42)
            bytes[0] = 0x80.toByte()
            byteArrayOf(1, 2, 3, 4, 5, 6).copyInto(bytes, 1)
            writeInt(bytes, 7, 42)
            writeInt(bytes, 11, 268)
            writeInt(bytes, 15, 2605)
            bytes[19] = 0x02
            writeInt(bytes, 20, 1)
            writeInt(bytes, 24, 7000)
            writeInt(bytes, 28, 43)
            writeInt(bytes, 32, 43)
            byteArrayOf(0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F).copyInto(bytes, 36)
            return bytes
        }
    }

    private inner class FakeGateway : QzxyWaterFlow.Gateway {
        var calls = 0
        var lastXfData: String? = null
        var clData: String? = null

        override suspend fun uploadConsume(
            xfData: String,
            randomNumber: String,
            protocolType: String,
        ): String? {
            calls += 1
            lastXfData = xfData
            return clData
        }
    }

    private fun flow(link: FakeLink, gateway: FakeGateway): QzxyWaterFlow =
        QzxyWaterFlow(link, gateway)

    private fun openOutcome(flow: QzxyWaterFlow): QzxyWaterFlow.Outcome {
        var result: QzxyWaterFlow.Outcome? = null
        kotlinx.coroutines.runBlocking {
            result = flow.open(address) { DOWN_DATA }
        }
        return result!!
    }

    private fun stopOutcome(
        flow: QzxyWaterFlow,
        timeout: Long = 12_000L,
    ): QzxyWaterFlow.Outcome {
        var result: QzxyWaterFlow.Outcome? = null
        kotlinx.coroutines.runBlocking {
            result = flow.stop(
                address = address,
                stopPollTimeoutMillis = timeout,
                stopPollIntervalMillis = 1L,
                stopPollMaxFailures = 3,
                verifyDelayMillis = 1L,
                clearTimeoutMillis = 1_000,
                maxClearAttempts = 9,
                preferredClearKey = null,
                nowMillis = { 0L },
                delayMillis = {},
            )
        }
        return result!!
    }

    @Test
    fun `开阀_空闲态下单并写入设备`() {
        val link = FakeLink().apply { states += QzxyProtocol.STATE_IDLE }
        val outcome = openOutcome(flow(link, FakeGateway()))

        assertTrue(outcome is QzxyWaterFlow.Outcome.Opened)
        assertEquals(listOf(QzxyProtocol.QUERY_DEVICE, QzxyProtocol.DOWN_RATE), link.sent)
    }

    @Test
    fun `开阀_状态三被挡且不下单`() {
        val link = FakeLink().apply { states += QzxyProtocol.STATE_FINISHED_UNCOLLECTED }
        val outcome = openOutcome(flow(link, FakeGateway()))

        assertTrue(outcome is QzxyWaterFlow.Outcome.Blocked)
        assertEquals("设备当前不可开", (outcome as QzxyWaterFlow.Outcome.Blocked).reason)
        // 状态 3 不能发开阀，订单也不该建：只有一次查询，没有 0x21
        assertEquals(listOf(QzxyProtocol.QUERY_DEVICE), link.sent)
    }

    @Test
    fun `开阀_状态六的提示与状态三不同`() {
        val link = FakeLink().apply { states += QzxyProtocol.STATE_SETTLING }
        val outcome = openOutcome(flow(link, FakeGateway())) as QzxyWaterFlow.Outcome.Blocked
        assertTrue(outcome.detail.contains("状态 6"))
    }

    @Test
    fun `结束用水_状态三直接采集_不发停阀`() {
        val link = FakeLink().apply {
            states += QzxyProtocol.STATE_FINISHED_UNCOLLECTED
            bodies[QzxyProtocol.COLLECT_CONSUME] = consumeBody()
            // 清除后回读状态：设备回到空闲
            states += QzxyProtocol.STATE_IDLE
        }
        val outcome = stopOutcome(flow(link, FakeGateway()))

        assertTrue(outcome is QzxyWaterFlow.Outcome.Settled)
        // 状态 3 发 0x22 会被回 81 01（实测），所以序列里没有停阀
        assertEquals(false, link.sent.contains(QzxyProtocol.END_RATE))
        assertTrue(link.sent.contains(QzxyProtocol.COLLECT_CONSUME))
        assertTrue(link.sent.contains(QzxyProtocol.CLEAR_CONSUME))
    }

    @Test
    fun `结束用水_放水状态先停阀再轮询到状态三`() {
        val link = FakeLink().apply {
            states += QzxyProtocol.STATE_IN_ORDER
            states += QzxyProtocol.STATE_IN_ORDER
            states += QzxyProtocol.STATE_SETTLING
            states += QzxyProtocol.STATE_FINISHED_UNCOLLECTED
            bodies[QzxyProtocol.COLLECT_CONSUME] = consumeBody()
        }
        val outcome = stopOutcome(flow(link, FakeGateway()))

        assertTrue(outcome is QzxyWaterFlow.Outcome.Settled)
        assertTrue(link.sent.contains(QzxyProtocol.END_RATE))
    }

    @Test
    fun `结束用水_连续三次读不到状态提前收手`() {
        val link = FakeLink().apply {
            states += QzxyProtocol.STATE_IN_ORDER
            // 之后三次查询都超时（states 空 = null），到上限就该收手而不是耗完预算
        }
        val outcome = stopOutcome(flow(link, FakeGateway())) as QzxyWaterFlow.Outcome.Blocked

        assertEquals("读不到设备状态", outcome.reason)
        // 收手后不会再发采集
        assertEquals(false, link.sent.contains(QzxyProtocol.COLLECT_CONSUME))
    }

    @Test
    fun `结束用水_设备空闲时提示并跳过停阀`() {
        val link = FakeLink().apply { states += QzxyProtocol.STATE_IDLE }
        val outcome = stopOutcome(flow(link, FakeGateway())) as QzxyWaterFlow.Outcome.Blocked

        assertEquals("设备当前空闲", outcome.reason)
        assertEquals(listOf(QzxyProtocol.QUERY_DEVICE), link.sent)
    }

    @Test
    fun `清除_第一条命中即停_不试后续候选`() {
        val link = FakeLink().apply {
            states += QzxyProtocol.STATE_FINISHED_UNCOLLECTED
            bodies[QzxyProtocol.COLLECT_CONSUME] = consumeBody()
            states += QzxyProtocol.STATE_IDLE
        }
        val gateway = FakeGateway()
        val outcome = stopOutcome(flow(link, gateway))

        assertTrue(outcome is QzxyWaterFlow.Outcome.Settled)
        val cleared = outcome as QzxyWaterFlow.Outcome.Settled
        assertEquals(true, cleared.cleared)
        // 命中的是第一条候选（记录摘要），0x86 只发了一次
        assertEquals(1, link.sent.count { it == QzxyProtocol.CLEAR_CONSUME })
    }

    @Test
    fun `清除_服务端凭据参与候选`() {
        val link = FakeLink().apply {
            states += QzxyProtocol.STATE_FINISHED_UNCOLLECTED
            bodies[QzxyProtocol.COLLECT_CONSUME] = consumeBody()
        }
        val gateway = FakeGateway()
        val outcome = stopOutcome(flow(link, gateway))
        assertTrue(outcome is QzxyWaterFlow.Outcome.Settled)
        // 上报拿到的 clData 会进清除候选表：这里只验证上报确实发生了
        assertEquals(1, gateway.calls)
        assertTrue(gateway.lastXfData!!.length >= 84)
    }

    @Test
    fun `查询超时按没有回应处理`() {
        val link = FakeLink().apply { connected = false }
        val outcome = openOutcome(flow(link, FakeGateway())) as QzxyWaterFlow.Outcome.Blocked
        assertEquals("设备没有回应可解析的数据", outcome.reason)
    }

    /** 48 字节长状态包：与真机样本同构（协议版本在 [19]，状态在 [28]）。 */
    private fun stateBody(state: Int): ByteArray {
        val bytes = ByteArray(48)
        bytes[0] = 0x80.toByte()
        writeInt(bytes, 1, 42)
        writeInt(bytes, 5, 268)
        writeInt(bytes, 9, 2605)
        byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06).copyInto(bytes, 13)
        bytes[19] = 0x10
        writeInt(bytes, 24, 0x4D)
        bytes[28] = state.toByte()
        bytes[29] = 0x00
        bytes[30] = 0x01
        return bytes
    }

    private fun successFrame(functionCode: Int, body: ByteArray): ByteArray {
        val frame = QzxyFrame.encode(functionCode, body)
        frame[3] = 0x81.toByte()
        var sum = 0x81 + functionCode
        body.forEach { sum += it.toInt() and 0xFF }
        frame[frame.size - 2] = (sum and 0xFF).toByte()
        return frame
    }

    private fun writeInt(target: ByteArray, offset: Int, value: Int) {
        for (i in 0 until 4) {
            target[offset + i] = ((value shr (8 * (3 - i))) and 0xFF).toByte()
        }
    }

    private companion object {
        val DOWN_DATA = ByteArray(48) { (it % 251).toByte() }
    }
}
