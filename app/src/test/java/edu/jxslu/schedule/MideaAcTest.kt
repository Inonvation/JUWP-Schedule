package edu.jxslu.schedule

import edu.jxslu.schedule.domain.MideaAc
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 美的空调红外协议（DESIGN §4.48）：校验和、帧字节、波形结构与重复组。
 *
 * 校验和与波形向量取自 IRremoteESP8266 `ir_Midea` 的测试（2026-10-16 对照核验）：
 * - calcChecksum(0xA1826FFFFF62) = 0x62、0xA18177FFFF70 = 0x70、0x1234567890AB = 0xDF、
 *   全零 = 0x00、全 F = 0xA0；
 * - sendMidea 的完整波形字符串（0x0 与 0x55AA55AA55AA 两例）。
 * - 定时 7 小时 = raw `A1A34DFF9B34` 的开关字节 0x9B；特殊切换帧常量自带校验和。
 */
class MideaAcTest {

    private fun bytes(vararg values: Int) = intArrayOf(*values)

    // ---- 校验和 ----

    @Test
    fun `校验和对齐上游已知值`() {
        assertEquals(0x62, MideaAc.checksum(bytes(0xA1, 0x82, 0x6F, 0xFF, 0xFF)))
        assertEquals(0x70, MideaAc.checksum(bytes(0xA1, 0x81, 0x77, 0xFF, 0xFF)))
        assertEquals(0xDF, MideaAc.checksum(bytes(0x12, 0x34, 0x56, 0x78, 0x90)))
        assertEquals(0x00, MideaAc.checksum(bytes(0x00, 0x00, 0x00, 0x00, 0x00)))
        assertEquals(0xA0, MideaAc.checksum(bytes(0xFF, 0xFF, 0xFF, 0xFF, 0xFF)))
    }

    // ---- 帧字节 ----

    @Test
    fun `开机 制冷 26 度 自动风`() {
        val frame = MideaAc.frame(
            MideaAc.State(power = true, mode = MideaAc.Mode.Cool, tempC = 26, fan = MideaAc.Fan.Auto),
        )
        assertEquals(listOf(0xA1, 0x80, 0x09, 0xFF, 0xFF, 0x37), frame.toList())
    }

    @Test
    fun `关机帧只翻电源位`() {
        val frame = MideaAc.frame(
            MideaAc.State(power = false, mode = MideaAc.Mode.Cool, tempC = 26, fan = MideaAc.Fan.Auto),
        )
        assertEquals(listOf(0xA1, 0x00, 0x09, 0xFF, 0xFF, 0xB7), frame.toList())
    }

    @Test
    fun `模式与风速各占自己的位`() {
        val frame = MideaAc.frame(
            MideaAc.State(power = true, mode = MideaAc.Mode.Heat, tempC = 26, fan = MideaAc.Fan.High),
        )
        assertEquals(listOf(0xA1, 0x9B, 0x09, 0xFF, 0xFF, 0x28), frame.toList())
    }

    @Test
    fun `温度写入低 5 位并钳制`() {
        assertEquals(0x00, MideaAc.frame(MideaAc.State(tempC = 17))[2])
        assertEquals(0x0D, MideaAc.frame(MideaAc.State(tempC = 30))[2])
        assertEquals(
            MideaAc.frame(MideaAc.State(tempC = 17)).toList(),
            MideaAc.frame(MideaAc.State(tempC = 10)).toList(),
        )
        assertEquals(
            MideaAc.frame(MideaAc.State(tempC = 30)).toList(),
            MideaAc.frame(MideaAc.State(tempC = 35)).toList(),
        )
    }

    @Test
    fun `帧尾字节就是前五字节的校验和`() {
        val frame = MideaAc.frame(
            MideaAc.State(power = true, mode = MideaAc.Mode.Dry, tempC = 22, fan = MideaAc.Fan.Medium),
        )
        assertEquals(listOf(0xA1, 0x91, 0x05, 0xFF, 0xFF), frame.take(5))
        assertEquals(frame[5], MideaAc.checksum(frame.copyOf(5)))
    }

    // ---- 波形 ----

    @Test
    fun `全零帧的完整波形对齐上游向量`() {
        // 上游 sendMidea(0x0)：头 → 48 个零位 → 560/5600 → 头 → 48 个一位 → 560/105600
        val expected = buildList {
            addAll(listOf(4480, 4480))
            repeat(48) { addAll(listOf(560, 560)) }
            addAll(listOf(560, 5600))
            addAll(listOf(4480, 4480))
            repeat(48) { addAll(listOf(560, 1680)) }
            addAll(listOf(560, 105600))
        }.toIntArray()
        assertEquals(200, MideaAc.GROUP_LENGTH)
        assertEquals(expected.toList(), MideaAc.pattern(intArrayOf(0, 0, 0, 0, 0, 0), repeats = 1).toList())
    }

    @Test
    fun `字节按 MSB 先发 反码段逐位取反`() {
        // 上游 sendMidea(0x55AA55AA55AA)：0x55 = 0101…，反码段为 1010…
        val out = MideaAc.pattern(intArrayOf(0x55, 0xAA, 0x55, 0xAA, 0x55, 0xAA), repeats = 1)
        assertEquals(
            listOf(4480, 4480, 560, 560, 560, 1680, 560, 560, 560, 1680, 560, 560, 560, 1680, 560, 560, 560, 1680),
            out.take(18),
        )
        // 第 100 个元素起是第二段的头（2 头 + 96 位 + 2 尾 + 2 头），首个数据位取反成 1
        assertEquals(listOf(4480, 4480, 560, 1680), out.drop(100).take(4))
        assertEquals(5600, out[99])
        assertEquals(105600, out.last())
    }

    @Test
    fun `重复组之间保留 100ms 间隔`() {
        val frame = MideaAc.frame(MideaAc.State(power = true))
        val out = MideaAc.pattern(frame, repeats = 2)
        assertEquals(400, out.size)
        assertEquals(105600, out[199])
        assertEquals(listOf(4480, 4480), out.drop(200).take(2))
        assertEquals(105600, out[399])
    }

    // ---- 睡眠 / 定时 / 切换帧 ----

    @Test
    fun `睡眠位在第二位字节`() {
        val frame = MideaAc.frame(MideaAc.State(power = true, sleep = true, tempC = 26))
        assertEquals(listOf(0xA1, 0xC0, 0x09, 0xFF, 0xFF, 0x57), frame.toList())
    }

    @Test
    fun `定时字节对齐上游七小时向量`() {
        // 上游 raw A1A34DFF9B34 = 开机定时 7 小时（14 个半小时档）
        assertEquals(0x9B, MideaAc.timerByte(7 * 60))
        assertEquals(0x81, MideaAc.timerByte(30))
        assertEquals(0x83, MideaAc.timerByte(60))
        assertEquals(0xFF, MideaAc.timerByte(null))
        assertEquals("不足 30 分钟按不定时", 0xFF, MideaAc.timerByte(20))
        assertEquals(0xDF, MideaAc.timerByte(24 * 60))
        assertEquals("24 小时封顶", MideaAc.timerByte(24 * 60), MideaAc.timerByte(48 * 60))
    }

    @Test
    fun `开关机定时写在各自的字节`() {
        assertEquals(
            listOf(0xA1, 0x80, 0x09, 0xFF, 0x9B, 0x48),
            MideaAc.frame(MideaAc.State(power = true, tempC = 26, onTimerMinutes = 7 * 60)).toList(),
        )
        assertEquals(
            listOf(0xA1, 0x80, 0x09, 0x83, 0xFF, 0x54),
            MideaAc.frame(MideaAc.State(power = true, tempC = 26, offTimerMinutes = 60)).toList(),
        )
    }

    @Test
    fun `切换帧常量自成校验和`() {
        MideaAc.Toggle.entries.forEach { toggle ->
            assertEquals("${toggle.name} 长度", 6, toggle.frameBytes.size)
            assertEquals(
                "${toggle.name} 校验和",
                toggle.frameBytes[5],
                MideaAc.checksum(toggle.frameBytes.copyOf(5)),
            )
        }
        assertEquals(listOf(0xA2, 0x01, 0xFF, 0xFF, 0xFF, 0x7C), MideaAc.Toggle.SwingV.frameBytes.toList())
        assertEquals(listOf(0xA2, 0x02, 0xFF, 0xFF, 0xFF, 0x7E), MideaAc.Toggle.Eco.frameBytes.toList())
        assertEquals(listOf(0xA2, 0x08, 0xFF, 0xFF, 0xFF, 0x75), MideaAc.Toggle.Light.frameBytes.toList())
        assertEquals(listOf(0xA2, 0x09, 0xFF, 0xFF, 0xFF, 0x74), MideaAc.Toggle.Turbo.frameBytes.toList())
    }
}
