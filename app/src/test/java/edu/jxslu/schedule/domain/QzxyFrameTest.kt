package edu.jxslu.schedule.domain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 帧编解码。样本取自公开逆向资料里那条实测的查询设备命令，
 * 钉的是「与样本逐字节一致」——设备真机是否也这么收发，要抓包才能确认。
 */
class QzxyFrameTest {

    @Test
    fun `查询设备帧与实测样本一致`() {
        val frame = QzxyFrame.encode(QzxyProtocol.QUERY_DEVICE)
        assertEquals("60000480230000a316", QzxyFrame.bytesToHex(frame))
    }

    @Test
    fun `解析实测样本取回数据体`() {
        val frame = QzxyFrame.hexToBytes("60000480230000a316")!!
        assertArrayEquals(byteArrayOf(0x00), QzxyFrame.parse(frame))
    }

    @Test
    fun `多字节数据体长度与校验和同步变化`() {
        val payload = byteArrayOf(0x11, 0x22, 0x33)
        val frame = QzxyFrame.encode(QzxyProtocol.DOWN_RATE, payload)
        // 长度字段 = 数据体 + 3；校验和 = 0x80 + 功能码 + 数据体字节和
        assertEquals(0x00, frame[1].toInt())
        assertEquals(0x06, frame[2].toInt())
        val expectedSum = (0x80 + QzxyProtocol.DOWN_RATE + 0x11 + 0x22 + 0x33) and 0xFF
        assertEquals(expectedSum.toByte(), frame[frame.size - 2])
        assertArrayEquals(payload, QzxyFrame.parse(frame))
    }

    @Test
    fun `文本包装与还原互逆`() {
        val frame = QzxyFrame.encode(QzxyProtocol.QUERY_DEVICE)
        val wrapped = QzxyFrame.wrap(frame)
        assertEquals('#'.code, wrapped.first().toInt())
        assertEquals('\n'.code, wrapped.last().toInt())
        assertEquals("#60000480230000a316\n", wrapped.toString(Charsets.US_ASCII))
        assertArrayEquals(frame, QzxyFrame.unwrap(wrapped))
    }

    @Test
    fun `校验和被改动时解析失败`() {
        val frame = QzxyFrame.hexToBytes("60000480230000a316")!!
        frame[frame.size - 2] = 0x00
        assertNull(QzxyFrame.parse(frame))
    }

    @Test
    fun `回包的控制字节换成 0x81 也能解出功能码`() {
        val body = byteArrayOf(0x80.toByte(), 0x01, 0x02)
        val frame = QzxyFrame.encode(0x85, body)
        frame[3] = 0x81.toByte()
        var sum = 0x81 + 0x85
        body.forEach { sum += it.toInt() and 0xFF }
        frame[frame.size - 2] = (sum and 0xFF).toByte()

        val parsed = QzxyFrame.parseFrame(frame)!!
        assertEquals(0x85, parsed.functionCode)
        assertArrayEquals(body, parsed.payload)
    }

    @Test
    fun `长度字段与包体不一致时解析失败`() {
        val frame = QzxyFrame.hexToBytes("60000580230000a316")!!
        assertNull(QzxyFrame.parse(frame))
    }

    @Test
    fun `非十六进制文本拒绝解析`() {
        assertNull(QzxyFrame.hexToBytes("zz"))
        assertNull(QzxyFrame.hexToBytes("abc"))
        assertNull(QzxyFrame.hexToBytes(""))
    }
}
