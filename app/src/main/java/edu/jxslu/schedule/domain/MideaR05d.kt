package edu.jxslu.schedule.domain

/**
 * 美的 R05D 红外协议（48 位，DESIGN §4.48）：国内美的挂机最常见的一套。
 *
 * 6 字节帧 = 固定头 `B2 4D` + 功能字节 B + `~B` + 模式/温度字节 C + `~C`；
 * 整帧**原样连发两遍**，校验靠成对的反码，没有额外校验和。
 * 关机不是「电源位」：B=0x7B、C=0xE0 是专用关机帧（实测 `B2 4D 7B 84 E0 1F`）。
 *
 * 波形：头 4400/4400、位 mark 550、1/0 间隔 1660/550、
 * 两帧之间 550/5220，末帧 550/8800（约 192ms 一组，仍在小米 HAL 的 400ms 预算内）。
 *
 * 扩展键（2026-10-20 加，编码来自第三方《R05d 电控功能说明书》与早期抓码，
 * **真机未验证**）：
 * - 摆风：`B2 4D 6B 94 E0 1F`，固定帧发两遍；
 * - 强劲：`B5 4A F5 0A A2 5D`（A=0xB5），固定帧发两遍，再按一次关闭；
 * - 经济（ECO）：先发 `B2 4D E0 1F 03 FC`，再发当前状态帧两遍；取消时只发状态帧
 *   两遍（所以任意普通按键都会取消经济）。
 * 睡眠 / 屏显没有公开编码，不做；定时（C/D 字节）未做。
 *
 * 依据：
 * - 中文 R05D 解码说明（帧结构 / 温度表 / 模式表）：
 *   https://pinaland.cn/archives/infrared-remote-nec-midea-r05d-protocol.html
 * - 实测开机 / 关机码（自动风 26℃ 制冷 / 关机）：
 *   `B2 4D BF 40 D0 2F` / `B2 4D 7B 84 E0 1F`
 * - 可工作的 ESP8266 实现（`poboll/esp-media-ir` 的 `IR_2.ino`）。
 */
object MideaR05d {

    /** 载波频率（Hz）。 */
    const val FREQUENCY_HZ = 38_000

    /**
     * 单次 `transmit` 的时长预算（微秒）。小米 `ir-spi` HAL 的上限落在实测区间
     * 583 ms（通过）～1166 ms（拒发）之间，取 400 ms 留余量；单组约 192 ms，
     * ECO 三帧约 286 ms。
     */
    const val TRANSMIT_BUDGET_US = 400_000

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

    /** 摆风固定帧（A=0xB2、B=0x6B、C=0xE0）。 */
    fun swingFrame(): IntArray = fixedFrame(0xB2, 0x6B, 0xE0)

    /** 强劲固定帧（A=0xB5、B=0xF5、C=0xA2）；再按一次关闭。 */
    fun turboFrame(): IntArray = fixedFrame(0xB5, 0xF5, 0xA2)

    /** 经济（ECO）前置帧（A=0xB2、J=0xE0、K=0x03）。 */
    fun ecoFrame(): IntArray = fixedFrame(0xB2, 0xE0, 0x03)

    /** 三个字节 + 反码，组成一条 6 字节帧。 */
    private fun fixedFrame(a: Int, b: Int, c: Int): IntArray = intArrayOf(
        a, a.inv() and 0xFF,
        b, b.inv() and 0xFF,
        c, c.inv() and 0xFF,
    )

    /** 摆风 / 强劲：固定帧按普通帧口径发两遍。 */
    fun swingPattern(): IntArray = pattern(swingFrame())

    fun turboPattern(): IntArray = pattern(turboFrame())

    /** ECO：经济帧 + 当前状态帧两遍；取消时调用方发普通状态帧即可。 */
    fun ecoPattern(state: State): IntArray =
        patternOf(listOf(ecoFrame(), frame(state), frame(state)))

    // ---- 波形常量（微秒）----
    const val HEADER_MARK_US = 4_400
    const val HEADER_SPACE_US = 4_400
    const val BIT_MARK_US = 550
    const val ONE_SPACE_US = 1_660
    const val ZERO_SPACE_US = 550
    const val SECTION_GAP_US = 5_220
    const val FINAL_GAP_US = 8_800

    /** 单帧的脉冲个数：2 头 + 96 位 + 2 尾 = 100。 */
    const val FRAME_LENGTH = 100

    /** 一组（两帧原样连发）的脉冲个数：2×100 = 200。 */
    const val GROUP_LENGTH = 2 * FRAME_LENGTH

    /**
     * 6 字节帧 → ConsumerIrManager 的时长序列（微秒，交替通断、以通开头）。
     *
     * 一组 = 第一帧 + 第二帧，两帧内容**相同**（R05D 不做 A1 那种反码帧）；
     * 第二帧尾部留 [FINAL_GAP_US]。默认一组约 192ms。
     */
    fun pattern(frame: IntArray, repeats: Int = 1): IntArray {
        require(repeats >= 1) { "repeats 至少为 1" }
        // 每组两帧、组尾留 [FINAL_GAP_US]；多组时每组都是完整的一组。
        val group = patternOf(listOf(frame, frame))
        if (repeats == 1) return group
        val out = IntArray(repeats * group.size)
        repeat(repeats) { index -> group.copyInto(out, index * group.size) }
        return out
    }

    /**
     * 任意帧序列 → 时长序列；每帧之间留 [SECTION_GAP_US]，末帧留 [FINAL_GAP_US]。
     * ECO 的三帧序列走这里（普通一组等价于传两帧相同内容）。
     */
    fun patternOf(frames: List<IntArray>): IntArray {
        require(frames.isNotEmpty()) { "至少一帧" }
        frames.forEach { require(it.size == 6) { "每帧 6 字节" } }
        val out = IntArray(frames.size * FRAME_LENGTH)
        var i = 0
        fun put(value: Int) {
            out[i++] = value
        }
        frames.forEachIndexed { index, frame ->
            put(HEADER_MARK_US)
            put(HEADER_SPACE_US)
            for (byte in frame) {
                for (bit in 7 downTo 0) {
                    put(BIT_MARK_US)
                    put(if (((byte shr bit) and 1) == 1) ONE_SPACE_US else ZERO_SPACE_US)
                }
            }
            put(BIT_MARK_US)
            put(if (index == frames.lastIndex) FINAL_GAP_US else SECTION_GAP_US)
        }
        return out
    }

    /** 一条时长序列的总时长（微秒）：给测试与调用方核预算用。 */
    fun totalMicros(pattern: IntArray): Long = pattern.sumOf { it.toLong() }
}
