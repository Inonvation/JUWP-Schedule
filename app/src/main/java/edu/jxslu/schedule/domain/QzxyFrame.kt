package edu.jxslu.schedule.domain

/**
 * 趣智校园蓝牙水控的帧编解码（DESIGN §4.30）。
 *
 * 帧结构来自公开逆向资料（看雪《趣智校园 app 分析》，2025-11-29），非官方文档：
 *
 * ```
 * 下标 0      包头 0x60
 * 下标 1..2   长度，大端，值 = 数据体长度 + 3
 * 下标 3      固定 0x80
 * 下标 4      功能码
 * 下标 5      固定 0x00
 * 下标 6..    数据体
 * 倒数第 2    校验和 = (数据体各字节之和 + 0x80 + 功能码 + 0x00) & 0xFF
 * 最后 1      结束码 0x16
 * ```
 *
 * 写入设备前过一层文本包装：`#` + 帧的小写十六进制 + `\n`，整串按 ASCII 字节发出；
 * 设备回包同格式，[unwrap] 负责还原。
 *
 * **可信度边界**：唯一实测样本是查询设备命令 `60000480230000a316`（功能码 0x23，
 * 数据体 1 字节 0x00，见 [QzxyProtocol.QUERY_DEVICE]）。下标 3、5 两个固定字节、
 * 以及长度字段的 +3 偏移，都由这一个样本反推，换命令是否仍成立要等真机抓包核对。
 * 单测 [QzxyFrameTest] 钉的是「与样本逐字节一致」，不是「与设备一致」。
 */
object QzxyFrame {
    const val HEADER: Int = 0x60
    const val FIXED_AT_3: Int = 0x80
    const val FIXED_AT_5: Int = 0x00
    const val FOOTER: Int = 0x16

    /**
     * 一帧解析出来的内容。
     *
     * 设备回包的 `[3]` 不是请求里的 `0x80` 而是 `0x81`，成败要看**数据体**首字节，
     * 见 [edu.jxslu.schedule.domain.QzxyProtocol.decodeResponse]。所以这里把功能码
     * 和数据体一起交出去，不要只返回数据体。
     */
    data class Frame(val functionCode: Int, val payload: ByteArray)

    /** 长度字段相对数据体长度的偏移：数据体 + 下标 3、4、5 三字节。 */
    private const val LENGTH_BIAS: Int = 3

    /** 包头(1) + 长度(2) + 固定(3) 共 6 字节在数据体之前，校验和与结束码共 2 字节在其后。 */
    private const val BODY_OFFSET: Int = 6
    private const val TAIL_SIZE: Int = 2

    /** 最短合法帧：空数据体也占 8 字节。 */
    const val MIN_FRAME_SIZE: Int = BODY_OFFSET + TAIL_SIZE

    private const val WRAP_PREFIX: Char = '#'
    private const val WRAP_SUFFIX: Char = '\n'

    private val HEX = "0123456789abcdef".toCharArray()

    /**
     * 组装一帧。[payload] 缺省为单字节 0x00，即查询类命令的空参数。
     * [functionCode] 取 [QzxyProtocol] 里的常量。
     */
    fun encode(functionCode: Int, payload: ByteArray = ByteArray(1)): ByteArray {
        val length = payload.size + LENGTH_BIAS
        val out = ByteArray(BODY_OFFSET + payload.size + TAIL_SIZE)
        out[0] = HEADER.toByte()
        out[1] = ((length shr 8) and 0xFF).toByte()
        out[2] = (length and 0xFF).toByte()
        out[3] = FIXED_AT_3.toByte()
        out[4] = (functionCode and 0xFF).toByte()
        out[5] = FIXED_AT_5.toByte()
        payload.copyInto(out, BODY_OFFSET)

        var sum = FIXED_AT_3 + (functionCode and 0xFF) + FIXED_AT_5
        payload.forEach { sum += it.toInt() and 0xFF }
        out[BODY_OFFSET + payload.size] = (sum and 0xFF).toByte()
        out[BODY_OFFSET + payload.size + 1] = FOOTER.toByte()
        return out
    }

    /**
     * 解析一帧，校验包头、长度、校验和与结束码，全过才返回内容。
     * 任何一项对不上返回 null，调用方据此走「设备响应无法解析」的诊断分支。
     */
    fun parseFrame(frame: ByteArray): Frame? {
        if (frame.size < MIN_FRAME_SIZE) return null
        if ((frame[0].toInt() and 0xFF) != HEADER) return null
        if ((frame[frame.size - 1].toInt() and 0xFF) != FOOTER) return null

        val length = ((frame[1].toInt() and 0xFF) shl 8) or (frame[2].toInt() and 0xFF)
        val payloadSize = length - LENGTH_BIAS
        if (payloadSize < 0) return null
        if (BODY_OFFSET + payloadSize + TAIL_SIZE != frame.size) return null

        val payload = frame.copyOfRange(BODY_OFFSET, BODY_OFFSET + payloadSize)
        var sum = (frame[3].toInt() and 0xFF) + (frame[4].toInt() and 0xFF) + (frame[5].toInt() and 0xFF)
        payload.forEach { sum += it.toInt() and 0xFF }
        if ((sum and 0xFF) != (frame[BODY_OFFSET + payloadSize].toInt() and 0xFF)) return null
        return Frame(functionCode = frame[4].toInt() and 0xFF, payload = payload)
    }

    /** 只要数据体。功能码与成败判定走 [parseFrame] / [edu.jxslu.schedule.domain.QzxyProtocol.decodeResponse]。 */
    fun parse(frame: ByteArray): ByteArray? = parseFrame(frame)?.payload

    /** 帧 → 可写入设备的字节流：`#<小写十六进制>\n` 的 ASCII 编码。 */
    fun wrap(frame: ByteArray): ByteArray {
        val sb = StringBuilder(frame.size * 2 + 2)
        sb.append(WRAP_PREFIX)
        frame.forEach { b ->
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        sb.append(WRAP_SUFFIX)
        return sb.toString().toByteArray(Charsets.US_ASCII)
    }

    /** [wrap] 的逆操作：从设备回包里还原帧字节。格式不符返回 null。 */
    fun unwrap(raw: ByteArray): ByteArray? {
        if (raw.size < 3) return null
        val text = raw.toString(Charsets.US_ASCII)
        val start = text.indexOf(WRAP_PREFIX)
        if (start < 0) return null
        val end = text.indexOf(WRAP_SUFFIX, start + 1).let { if (it < 0) text.length else it }
        return hexToBytes(text.substring(start + 1, end))
    }

    /** 十六进制文本 → 字节；含非十六进制字符或长度为奇数时返回 null。 */
    fun hexToBytes(hex: String): ByteArray? {
        val trimmed = hex.trim()
        if (trimmed.isEmpty() || trimmed.length % 2 != 0) return null
        val out = ByteArray(trimmed.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(trimmed[i * 2], 16)
            val lo = Character.digit(trimmed[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    /** 字节 → 小写十六进制，与设备侧编码口径一致。 */
    fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        bytes.forEach { b ->
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }
}
