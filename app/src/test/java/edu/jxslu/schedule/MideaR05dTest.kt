package edu.jxslu.schedule

import edu.jxslu.schedule.domain.MideaAc
import edu.jxslu.schedule.domain.MideaR05d
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 美的 R05D 协议（DESIGN §4.48）：帧字节、反码对、温度 / 模式 / 风速表与波形。
 *
 * 开机 / 关机向量取自实测遥控（自动风 26℃ 制冷 / 关机）：
 * `B2 4D BF 40 D0 2F` / `B2 4D 7B 84 E0 1F`。
 */
class MideaR05dTest {

    private fun frame(
        power: Boolean = true,
        mode: MideaR05d.Mode = MideaR05d.Mode.Cool,
        tempC: Int = 26,
        fan: MideaR05d.Fan = MideaR05d.Fan.Auto,
    ) = MideaR05d.frame(MideaR05d.State(power = power, mode = mode, tempC = tempC, fan = fan))

    @Test
    fun `开机 制冷 26 度 自动风对齐实测码`() {
        assertEquals(
            listOf(0xB2, 0x4D, 0xBF, 0x40, 0xD0, 0x2F),
            frame(power = true).toList(),
        )
    }

    @Test
    fun `关机帧是专用码不是电源位`() {
        assertEquals(
            listOf(0xB2, 0x4D, 0x7B, 0x84, 0xE0, 0x1F),
            frame(power = false).toList(),
        )
    }

    @Test
    fun `风速码`() {
        assertEquals(0xBF, frame(fan = MideaR05d.Fan.Auto)[2])
        assertEquals(0x9F, frame(fan = MideaR05d.Fan.Low)[2])
        assertEquals(0x5F, frame(fan = MideaR05d.Fan.Medium)[2])
        assertEquals(0x3F, frame(fan = MideaR05d.Fan.High)[2])
    }

    @Test
    fun `温度码不是二进制顺序`() {
        assertEquals(0x00, frame(tempC = 17)[4])
        assertEquals(0x10, frame(tempC = 18)[4])
        assertEquals(0x30, frame(tempC = 19)[4])
        assertEquals(0x20, frame(tempC = 20)[4])
        assertEquals(0xD0, frame(tempC = 26)[4])
        assertEquals(0xB0, frame(tempC = 30)[4])
    }

    @Test
    fun `温度写入时钳制`() {
        assertEquals(frame(tempC = 17).toList(), frame(tempC = 10).toList())
        assertEquals(frame(tempC = 30).toList(), frame(tempC = 35).toList())
    }

    @Test
    fun `模式码`() {
        assertEquals(0xD8, frame(mode = MideaR05d.Mode.Auto)[4])
        assertEquals(0xD0, frame(mode = MideaR05d.Mode.Cool)[4])
        assertEquals(0xD4, frame(mode = MideaR05d.Mode.Dry)[4])
        assertEquals(0xDC, frame(mode = MideaR05d.Mode.Heat)[4])
        // 送风没有温度目标，C 的高 4 位取「无定义」档 0xE
        assertEquals(0xE4, frame(mode = MideaR05d.Mode.Fan)[4])
    }

    @Test
    fun `固定头与成对反码`() {
        val frame = frame()
        assertEquals(0xB2, frame[0])
        assertEquals(0x4D, frame[1])
        assertEquals(frame[2], frame[3].inv() and 0xFF)
        assertEquals(frame[4], frame[5].inv() and 0xFF)
    }

    @Test
    fun `一组两帧原样连发`() {
        val out = MideaR05d.pattern(frame(), repeats = 1)
        assertEquals(200, out.size)
        assertEquals(MideaR05d.GROUP_LENGTH, out.size)
        assertEquals(listOf(4400, 4400), out.take(2))
        // 第二帧内容与第一帧相同（R05D 不做 A1 的反码帧）
        assertEquals(out.copyOfRange(0, 98).toList(), out.copyOfRange(100, 198).toList())
        assertEquals(5220, out[99])
        assertEquals(550, out[198])
        assertEquals(8800, out[199])
    }

    @Test
    fun `单组约 192ms 在发射预算内`() {
        val pattern = MideaR05d.pattern(frame())
        assertEquals(191_600L, MideaR05d.totalMicros(pattern))
        assertTrue(
            "R05D 单组必须留在小米 HAL 的预算内",
            MideaR05d.totalMicros(pattern) <= MideaAc.TRANSMIT_BUDGET_US,
        )
    }

    @Test
    fun `重复组`() {
        val out = MideaR05d.pattern(frame(), repeats = 2)
        assertEquals(400, out.size)
        assertEquals(8800, out[199])
        assertEquals(listOf(4400, 4400), out.drop(200).take(2))
    }
}
