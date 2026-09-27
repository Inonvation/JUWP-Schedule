package edu.jxslu.schedule.domain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设备查询响应解析。测试数据按公开资料给的字段偏移手工构造，
 * 真机偏移若有出入，这一组会先红。
 */
class QzxyProtocolTest {

    private fun payload(size: Int): ByteArray {
        val bytes = ByteArray(size)
        bytes[0] = 0x80.toByte()
        writeInt(bytes, 1, 42)
        writeInt(bytes, 5, 268)
        writeInt(bytes, 9, 2605)
        byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06).copyInto(bytes, 13)
        if (size > 19) bytes[19] = 0x01
        return bytes
    }

    private fun writeInt(target: ByteArray, offset: Int, value: Int) {
        for (i in 0 until 4) {
            target[offset + i] = ((value shr (8 * (3 - i))) and 0xFF).toByte()
        }
    }

    @Test
    fun `短包解析出项目设备账号序列号与状态`() {
        val bytes = payload(23)
        bytes[21] = 0xAB.toByte()
        bytes[22] = 0xCD.toByte()

        val state = QzxyProtocol.parseDeviceState(bytes)!!
        assertEquals(42, state.projectId)
        assertEquals(268, state.deviceId)
        assertEquals(2605, state.accountId)
        assertEquals("010203040506", state.snCode)
        assertEquals(QzxyProtocol.STATE_IN_ORDER, state.deviceState)
        assertEquals("abcd", state.randomNumber)
        assertNull(state.mainType)
    }

    @Test
    fun `带类型字段的短包解析出主次类型`() {
        val bytes = payload(26)
        bytes[21] = 0x11
        bytes[22] = 0x22
        bytes[23] = 0x00
        bytes[24] = 0x01

        val state = QzxyProtocol.parseDeviceState(bytes)!!
        assertEquals("1122", state.randomNumber)
        assertEquals(QzxyProtocol.TYPE_WATER_METER, state.mainType)
        assertEquals(1, state.subType)
        assertEquals(true, state.isWaterMeter)
    }

    @Test
    fun `长包解析出四字节随机数`() {
        val bytes = payload(48)
        byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()).copyInto(bytes, 24)
        bytes[28] = 0x00
        bytes[29] = 0x00
        bytes[30] = 0x03

        val state = QzxyProtocol.parseDeviceState(bytes)!!
        assertEquals("deadbeef", state.randomNumber)
        assertEquals(QzxyProtocol.TYPE_WATER_METER, state.mainType)
        assertEquals(3, state.subType)
        // 长包里 [19] 按协议版本读，原始数据体一并带出便于对表
        assertEquals("01", state.protocolType)
        assertEquals(48 * 2, state.rawHex.length)
    }

    @Test
    fun `标记字节不符时拒绝解析`() {
        val bytes = payload(23)
        bytes[0] = 0x81.toByte()
        assertNull(QzxyProtocol.parseDeviceState(bytes))
    }

    @Test
    fun `长度不足时拒绝解析`() {
        assertNull(QzxyProtocol.parseDeviceState(payload(19)))
    }

    // ── 回包成败与错误码 ──

    /** 回包帧的 `[3]` 是 0x81（请求是 0x80），校验和按实际字节算。 */
    private fun responseFrame(functionCode: Int, body: ByteArray): ByteArray {
        val frame = QzxyFrame.encode(functionCode, body)
        frame[3] = 0x81.toByte()
        var sum = 0x81 + functionCode + 0x00
        body.forEach { sum += it.toInt() and 0xFF }
        frame[frame.size - 2] = (sum and 0xFF).toByte()
        return frame
    }

    @Test
    fun `数据体首字节为 0x80 判成功`() {
        val frame = responseFrame(0x85, payload(42))
        val response = QzxyProtocol.decodeResponse(frame)!!
        assertEquals(0x85, response.functionCode)
        assertTrue(response.success)
        assertNull(response.errorCode)
        assertEquals("-", response.errorText)
    }

    @Test
    fun `数据体次字节是错误码`() {
        val frame = responseFrame(0x86, byteArrayOf(0x00, 0x05))
        val response = QzxyProtocol.decodeResponse(frame)!!
        assertFalse(response.success)
        assertEquals(0x05, response.errorCode)
        assertEquals("数据格式错误（参数不对）", response.errorText)
        assertTrue(response.summary.contains("被拒"))
    }

    @Test
    fun `数据体只有一个字节时按未知错误处理`() {
        val frame = responseFrame(0x86, byteArrayOf(0x01))
        val response = QzxyProtocol.decodeResponse(frame)!!
        assertEquals(QzxyProtocol.ERROR_UNKNOWN, response.errorCode)
    }

    @Test
    fun `错误码中文名覆盖官方头文件的取值`() {
        assertEquals("包头错误", QzxyProtocol.errorCodeText(0x01))
        assertEquals("包长度错误", QzxyProtocol.errorCodeText(0x02))
        assertEquals("功能码错误（设备不认这条命令）", QzxyProtocol.errorCodeText(0x04))
        assertEquals("校验和错误", QzxyProtocol.errorCodeText(0x06))
        assertEquals("结束码错误", QzxyProtocol.errorCodeText(0x07))
        assertEquals("来源错误", QzxyProtocol.errorCodeText(0x08))
        assertEquals("未登记错误码", QzxyProtocol.errorCodeText(0x7F))
    }

    // ── 消费记录 ──

    /**
     * 按官方 iOS SDK 反汇编得到的偏移手工构造一条记录：
     * `[0]` 标记、`[1..6]` 时间序号、`[7..10]` 项目、`[11..14]` 设备、`[15..18]` 账号、
     * `[19]` 账户类别、`[20..23]` 使用次数、`[24..27]` 预扣、`[28..31]` 消费、
     * `[32..35]` 费率、`[36..41]` MAC。
     */
    private fun consumptionPayload(size: Int = 42): ByteArray {
        val bytes = ByteArray(size)
        bytes[0] = 0x80.toByte()
        byteArrayOf(0x26, 0x09, 0x27, 0x20, 0x35, 0x33).copyInto(bytes, 1)
        writeInt(bytes, 7, 42)
        writeInt(bytes, 11, 268)
        writeInt(bytes, 15, 2605)
        bytes[19] = 0x02
        writeInt(bytes, 20, 3)
        writeInt(bytes, 24, 7000)
        writeInt(bytes, 28, 43)
        writeInt(bytes, 32, 43)
        byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06).copyInto(bytes, 36)
        return bytes
    }

    @Test
    fun `消费记录按官方偏移解析`() {
        val record = QzxyProtocol.parseConsumption(consumptionPayload())!!
        assertEquals("260927203533", record.timeId)
        assertEquals(42, record.projectId)
        assertEquals(268, record.deviceId)
        assertEquals(2605, record.accountId)
        assertEquals(2, record.accountType)
        assertEquals(3, record.useCount)
        assertEquals(7000, record.preDeductMoney)
        assertEquals(43, record.consumeMoney)
        assertEquals(43, record.rate)
        assertEquals("010203040506", record.macAddress)
        assertNull(record.tac)
    }

    @Test
    fun `六十三字节长记录带出校验码`() {
        val bytes = consumptionPayload(63)
        byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55).copyInto(bytes, 58)
        val record = QzxyProtocol.parseConsumption(bytes)!!
        assertEquals("1122334455", record.tac)
    }

    @Test
    fun `消费记录标记不符或长度不足时拒绝解析`() {
        val wrongMark = consumptionPayload()
        wrongMark[0] = 0x81.toByte()
        assertNull(QzxyProtocol.parseConsumption(wrongMark))
        // 41 字节：短一字节，官方 `length >= 42` 的边界
        assertNull(QzxyProtocol.parseConsumption(consumptionPayload().copyOf(41)))
    }

    // ── 清除候选 ──

    @Test
    fun `服务端凭据的候选排在整条记录之前`() {
        val record = consumptionPayload()
        val clData = "260927203533-abcdef".toByteArray(Charsets.US_ASCII)
        val candidates = QzxyProtocol.clearCandidates(record, clData)

        assertTrue(candidates.all { it.functionCode == QzxyProtocol.CLEAR_CONSUME })
        assertTrue(candidates.any { it.label == "服务端凭据第 1 段" })
        assertTrue(candidates.any { it.label == "服务端凭据两段拼起来" })
        // 两段拼起来是合法十六进制，要按字节解而不是按文本：6 + 3 = 9 字节
        assertEquals(9, candidates.first { it.label == "服务端凭据两段拼起来" }.payload.size)
        val lastCredential = candidates.indexOfLast {
            it.key.startsWith(QzxyProtocol.ClearKey.CL_DATA_PART)
        }
        val wholeRecord = candidates.indexOfFirst { it.key == QzxyProtocol.ClearKey.RECORD_FULL }
        assertTrue(lastCredential in 0 until wholeRecord)
    }

    @Test
    fun `记录摘要按实测布局切片`() {
        val digest = QzxyProtocol.recordDigest(consumptionPayload())!!
        assertEquals(22, digest.size)
        assertEquals("260927203533", QzxyFrame.bytesToHex(digest.copyOfRange(0, 6)))
        assertEquals("0000002a", QzxyFrame.bytesToHex(digest.copyOfRange(6, 10)))
        assertEquals("0000010c", QzxyFrame.bytesToHex(digest.copyOfRange(10, 14)))
        assertEquals("00000a2d", QzxyFrame.bytesToHex(digest.copyOfRange(14, 18)))
        assertEquals("00000003", QzxyFrame.bytesToHex(digest.copyOfRange(18, 22)))
        assertNull(QzxyProtocol.recordDigest(ByteArray(41)))
    }

    @Test
    fun `记录摘要的另一种读法末四字节是账户类别`() {
        val digest = QzxyProtocol.recordDigestWithAccountType(consumptionPayload())!!
        assertEquals(22, digest.size)
        assertEquals("00000002", QzxyFrame.bytesToHex(digest.copyOfRange(18, 22)))
    }

    @Test
    fun `记录摘要排在候选表最前且与服务端凭据同字节时只留一条`() {
        val record = consumptionPayload()
        // 服务端凭据解密后的第二段就是记录摘要本身（2026-09-27 实测），两段拼起来正好相等
        val clData = "1790517647206-2609272035330000002a0000010c00000a2d00000003"
            .toByteArray(Charsets.US_ASCII)
        val candidates = QzxyProtocol.clearCandidates(record, clData)

        assertEquals(QzxyProtocol.ClearKey.RECORD_DIGEST, candidates.first().key)
        // 字节完全相同，去重后服务端那条被摘要吸收
        assertFalse(candidates.any { it.key == QzxyProtocol.ClearKey.CL_DATA_PART + "1" })
        // 没有凭据时第一条仍是摘要
        assertEquals(QzxyProtocol.ClearKey.RECORD_DIGEST, QzxyProtocol.clearCandidates(record).first().key)
    }

    @Test
    fun `状态六按结算中显示`() {
        assertEquals("结算中（记录写入中）", QzxyProtocol.stateText(QzxyProtocol.STATE_SETTLING))
        assertEquals("未知状态（7）", QzxyProtocol.stateText(7))
    }

    @Test
    fun `候选表按参数去重`() {
        val record = consumptionPayload()
        // 凭据原文本身就是合法十六进制，与「按十六进制解」那条重复，只该留一条
        val hex = "0102030405060708090a0b0c0d0e0f10"
        val candidates = QzxyProtocol.clearCandidates(
            record,
            hex.toByteArray(Charsets.US_ASCII),
        )
        val payloads = candidates.map { it.payloadHex }
        assertEquals(payloads.size, payloads.distinct().size)
    }

    @Test
    fun `候选表最后一条是空参数`() {
        val candidates = QzxyProtocol.clearCandidates(consumptionPayload())
        assertEquals("空参数", candidates.last().label)
        assertEquals(1, candidates.last().payload.size)
    }

    @Test
    fun `上次试通的候选排到最前且不重复`() {
        val record = consumptionPayload()
        val plain = QzxyProtocol.clearCandidates(record)
        val target = plain.first { it.key == QzxyProtocol.ClearKey.RECORD_TIME_ID }

        val reordered = QzxyProtocol.clearCandidates(
            record = record,
            preferredKey = QzxyProtocol.ClearKey.RECORD_TIME_ID,
        )
        assertEquals(QzxyProtocol.ClearKey.RECORD_TIME_ID, reordered.first().key)
        assertArrayEquals(target.payload, reordered.first().payload)
        // 只是换顺序，条数不变
        assertEquals(plain.size, reordered.size)
    }

    @Test
    fun `候选标识不认识时保持原顺序`() {
        val record = consumptionPayload()
        val plain = QzxyProtocol.clearCandidates(record).map { it.key }
        val reordered = QzxyProtocol.clearCandidates(record, preferredKey = "不认识").map { it.key }
        assertEquals(plain, reordered)
    }
}
