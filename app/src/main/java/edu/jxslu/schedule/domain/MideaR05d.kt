package edu.jxslu.schedule.domain

/**
 * 美的 R05D 红外协议（48 位，DESIGN §4.48）：国内美的挂机最常见的一套。
 *
 * 6 字节帧 = 固定头 `B2 4D` + 功能字节 B + `~B` + 模式/温度字节 C + `~C`；
 * 整帧**原样连发两遍**，校验靠成对的反码，没有额外校验和。
 * 关机不是「电源位」：B=0x7B、C=0xE0 是专用关机帧（实测 `B2 4D 7B 84 E0 1F`）。
 *
 * 波形与 [MideaAc] 同一量级：头 4400/4400、位 mark 550、1/0 间隔 1660/550、
 * 两帧之间 550/5220，末帧 550/8800（约 192ms 一组，仍在小米 HAL 的 400ms 预算内）。
 *
 * 依据：
 * - 中文 R05D 解码说明（帧结构 / 温度表 / 模式表）：
 *   https://pinaland.cn/archives/infrared-remote-nec-midea-r05d-protocol.html
 * - 实测开机 / 关机码（自动风 26℃ 制冷 / 关机）：
 *   `B2 4D BF 40 D0 2F` / `B2 4D 7B 84 E0 1F`
 * - 可工作的 ESP8266 实现（`poboll/esp-media-ir` 的 `IR_2.ino`）。
 */
object MideaR05d {

    /** 温度档位（摄氏度）。 */
    const val MIN_TEMP_C = 17
    const val MAX_TEMP_C = 30

    /** 模式码（C 字节低 4 位；送风与除湿同码，送风另有固定温度码）。 */
    enum class Mode(val code: Int) {
        Auto(0xF8),
        Cool(0xF0),
        Dry(0xF4),
        Heat(0xFC),
        Fan(0xF4),
    }

    /** 风速码（B 字节高 3 位，低 5 位恒 1）。 */
    enum class Fan(val code: Int) {
        Auto(0xBF),
        Low(0x9F),
        Medium(0x5F),
        High(0x3F),
    }

    /** 虚拟遥控状态；R05D 没有睡眠 / 定时 / 切换类功能的公开编码。 */
    data class State(
        val power: Boolean = false,
        val mode: Mode = Mode.Cool,
        val tempC: Int = 26,
        val fan: Fan = Fan.Auto,
    )

    /**
     * 温度码（17–30℃）的 C 字节高 4 位。非二进制顺序，对齐原装遥控的温度表
     * （17→0x0、18→0x1、19→0x3、20→0x2、21→0x6 …）。
     */
    private val TEMP_CODES = intArrayOf(
        0x0F, 0x1F, 0x3F, 0x2F, 0x6F, 0x7F, 0x5F, 0x4F,
        0xCF, 0xDF, 0x9F, 0x8F, 0xAF, 0xBF,
    )

    /** 送风模式没有温度，C 的高 4 位取「无定义」档 0xE。 */
    private const val FAN_MODE_TEMP = 0xEF

    /** 关机帧的 B / C 特殊值（对齐实测 `B2 4D 7B 84 E0 1F`）。 */
    const val OFF_B = 0x7B
    const val OFF_C = 0xE0

    /** 状态 → 6 字节帧（线上顺序）。温度在这里钳到 [MIN_TEMP_C]..[MAX_TEMP_C]。 */
    fun frame(state: State): IntArray {
        val b: Int
        val c: Int
        if (state.power) {
            b = state.fan.code
            val tempCode = if (state.mode == Mode.Fan) {
                FAN_MODE_TEMP
            } else {
                TEMP_CODES[state.tempC.coerceIn(MIN_TEMP_C, MAX_TEMP_C) - MIN_TEMP_C]
            }
            c = tempCode and state.mode.code
        } else {
            b = OFF_B
            c = OFF_C
        }
        return intArrayOf(0xB2, 0x4D, b, b.inv() and 0xFF, c, c.inv() and 0xFF)
    }

    // ---- 波形常量（微秒）----
    const val HEADER_MARK_US = 4_400
    const val HEADER_SPACE_US = 4_400
    const val BIT_MARK_US = 550
    const val ONE_SPACE_US = 1_660
    const val ZERO_SPACE_US = 550
    const val SECTION_GAP_US = 5_220
    const val FINAL_GAP_US = 8_800

    /** 一组（两帧原样连发）的脉冲个数：2×(2 头 + 96 位 + 2 尾) = 200。 */
    const val GROUP_LENGTH = 200

    /**
     * 6 字节帧 → ConsumerIrManager 的时长序列（微秒，交替通断、以通开头）。
     *
     * 一组 = 第一帧 + 第二帧，两帧内容**相同**（R05D 不做 A1 那种反码帧）；
     * 第二帧尾部留 [FINAL_GAP_US]。默认一组约 192ms。
     */
    fun pattern(frame: IntArray, repeats: Int = 1): IntArray {
        require(repeats >= 1) { "repeats 至少为 1" }
        val out = IntArray(repeats * GROUP_LENGTH)
        var i = 0
        fun put(value: Int) {
            out[i++] = value
        }
        fun emitFrame() {
            put(HEADER_MARK_US)
            put(HEADER_SPACE_US)
            for (byte in frame) {
                for (bit in 7 downTo 0) {
                    put(BIT_MARK_US)
                    put(if (((byte shr bit) and 1) == 1) ONE_SPACE_US else ZERO_SPACE_US)
                }
            }
            put(BIT_MARK_US)
            put(SECTION_GAP_US)
        }
        repeat(repeats) {
            emitFrame()
            emitFrame()
            out[i - 1] = FINAL_GAP_US
        }
        return out
    }

    /** 一条时长序列的总时长（微秒）：给测试与调用方核预算用。 */
    fun totalMicros(pattern: IntArray): Long = pattern.sumOf { it.toLong() }
}
