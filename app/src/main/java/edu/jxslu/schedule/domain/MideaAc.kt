package edu.jxslu.schedule.domain

/**
 * 美的空调红外协议（48 位，DESIGN §4.48）：虚拟遥控状态 → 帧 → 脉冲序列。
 *
 * 口径对齐 IRremoteESP8266 的 ir_Midea（Pioneer / Comfee / Danby 等 OEM 与部分美的），
 * 帧结构、校验和与整段波形逐位对齐它的测试向量（由 `MideaAcTest` 钉死）。
 * **国内美的挂机更常见的是 [MideaR05d]**（`B2 4D` 口径）；两套按 [AcProtocol] 切换。
 *
 * 帧 = 6 字节（线上顺序，先发的在前）：
 * 1. `0xA1`：类型 001（命令）+ 固定头 10100；
 * 2. 电源位 7 · 睡眠位 6（恒 0）· 保留位 5 · 风速位 4..3 · 模式位 2..0；
 * 3. **固定位 6（恒 1，原装帧每一帧都有）** · 华氏标志位 5（恒 0）· 温度位 4..0
 *    （摄氏 = 17 + 位值，17–30℃）；
 * 4. 关机定时字节（[timerByte]：30 分钟一档，0xFF = 不定时）；
 * 5. 开机定时字节（同上）；
 * 6. 校验和 = 前 5 字节逐字节按位反序求和，取 256 的补，再按位反序。
 *
 * 切换类功能（摆风 / 屏显 / 强力 / ECO）协议里没有状态位：每条都是「切换」特殊帧
 * （见 [Toggle]），与主帧**连发**（先主帧、后切换帧），空调收到后翻转对应开关。
 *
 * 发射波形（38kHz 载波）：每组 = 头 4480/4480 → 正码 48 位 → 560/5600 →
 * 头 4480/4480 → 按位取反的 48 位 → 560/105600（5600 结束间隔 + 100ms 重发间隔）。
 * 每位 = mark 560 + 间隔（1 → 1680，0 → 560）；默认 [DEFAULT_REPEATS] 组（= 1 组）。
 *
 * **一组 ≈ 291 ms，一次 `transmit` 只许发一组**：小米的 `ir-spi` HAL 会按整条序列的总
 * 时长拒发（真机 logcat：`E ConsumerIrHal: ir-spi total_time out of range 1166075`，
 * 1.17 s 被整条丢掉、一个脉冲都不发；583 ms 通过）。原装遥控与万能遥控也都是「一次
 * 按键 = 一组」的量级（实测 206–303 ms）。要更抗干扰就多调几次 `transmit`
 * （见 `AcRemoteViewModel`），不要在一条序列里堆组数。
 */
object MideaAc {

    /** 载波频率（Hz）。 */
    const val FREQUENCY_HZ = 38_000

    /** 温度档位（摄氏度）。 */
    const val MIN_TEMP_C = 17
    const val MAX_TEMP_C = 30

    /**
     * 一次 `transmit` 里放几组（正码 + 反码算一组）。**固定 1 组 ≈ 291 ms**：
     * 堆到 2 组就是 583 ms，实测这条会被 HAL 拉长到 713 ms，再叠上切换帧直接超限被整条丢弃
     * （见文件头的 `ir-spi total_time out of range`）。
     */
    const val DEFAULT_REPEATS = 1

    /**
     * 单次 `transmit` 的时长预算（微秒）。小米 `ir-spi` HAL 的上限落在实测区间
     * 583 ms（通过）～1166 ms（拒发）之间，取 400 ms 留余量；单组约 291.5 ms。
     */
    const val TRANSMIT_BUDGET_US = 400_000

    /** 一条时长序列的总时长（微秒）：给测试与调用方核预算用。 */
    fun totalMicros(pattern: IntArray): Long = pattern.sumOf { it.toLong() }

    /** 模式位值（帧字节 2 的低 3 位）。 */
    enum class Mode(val bits: Int) {
        Cool(0b000),
        Dry(0b001),
        Auto(0b010),
        Heat(0b011),
        Fan(0b100),
    }

    /** 风速位值（帧字节 2 的位 4..3）。 */
    enum class Fan(val bits: Int) {
        Auto(0b00),
        Low(0b01),
        Medium(0b10),
        High(0b11),
    }

    /** 虚拟遥控的完整状态（都进主帧）；空调本身不记状态，这份由界面替它记。 */
    data class State(
        val power: Boolean = false,
        val mode: Mode = Mode.Cool,
        val tempC: Int = 26,
        val fan: Fan = Fan.Auto,
        /** 睡眠模式（主帧位）。 */
        val sleep: Boolean = false,
        /** 开机定时（分钟，30 的倍数、0.5–24 小时；null = 不定时）。 */
        val onTimerMinutes: Int? = null,
        /** 关机定时（同上）。 */
        val offTimerMinutes: Int? = null,
    )

    /**
     * 切换类功能的界面记忆：协议没有状态位、每次都是「切换」指令，
     * 界面替用户记最近一次（物理遥控动过之后会与实际不同步，接受）。
     */
    data class Toggles(
        val swingOn: Boolean = false,
        val lightOn: Boolean = false,
        val turboOn: Boolean = false,
        val ecoOn: Boolean = false,
    )

    /** 切换类特殊帧（与主帧连发）：第 1 字节 `0xA2` 标记特殊帧，第 6 字节是自带校验和。 */
    enum class Toggle(val frameBytes: IntArray) {
        /** 风向（上下摆风 / 扫风）开关。 */
        SwingV(intArrayOf(0xA2, 0x01, 0xFF, 0xFF, 0xFF, 0x7C)),

        /** ECO 节能开关。 */
        Eco(intArrayOf(0xA2, 0x02, 0xFF, 0xFF, 0xFF, 0x7E)),

        /** 屏显（机身灯光）开关。 */
        Light(intArrayOf(0xA2, 0x08, 0xFF, 0xFF, 0xFF, 0x75)),

        /** 强力开关。 */
        Turbo(intArrayOf(0xA2, 0x09, 0xFF, 0xFF, 0xFF, 0x74)),
    }

    // ---- 波形常量（微秒）----
    const val HEADER_MARK_US = 4_480
    const val HEADER_SPACE_US = 4_480
    const val BIT_MARK_US = 560
    const val ONE_SPACE_US = 1_680
    const val ZERO_SPACE_US = 560
    const val FOOTER_SPACE_US = 5_600
    const val MESSAGE_GAP_US = 100_000

    private const val HEAD_BYTE = 0xA1

    /**
     * 温度字节的固定位 6。原装遥控的每一帧都置 1（17℃ = 0x40、30℃ = 0x4D，
     * 上游 stateReset 的 `A1 82 6F FF FF 62` 同款）；漏掉它空调可能整帧不认。
     */
    private const val TEMP_CONSTANT_BIT = 0x40

    /** 单组（正 + 反）的脉冲个数：2 头 + 2×48 位×2 + 2 尾×2 = 200。 */
    const val GROUP_LENGTH = 2 + 48 * 2 + 2 + 2 + 48 * 2 + 2

    /** 状态 → 6 字节帧（线上顺序）。温度在这里钳到 [MIN_TEMP_C]..[MAX_TEMP_C]。 */
    fun frame(state: State): IntArray {
        val temp = state.tempC.coerceIn(MIN_TEMP_C, MAX_TEMP_C)
        val byte1 = (if (state.power) 0x80 else 0) or
            (if (state.sleep) 0x40 else 0) or
            (state.fan.bits shl 3) or state.mode.bits
        val byte2 = TEMP_CONSTANT_BIT or (temp - MIN_TEMP_C)
        val five = intArrayOf(
            HEAD_BYTE,
            byte1,
            byte2,
            timerByte(state.offTimerMinutes),
            timerByte(state.onTimerMinutes),
        )
        return five + checksum(five)
    }

    /**
     * 定时字节（开关机定时共用）：30 分钟一档、0.5–24 小时；不足 30 分钟 / null = 不定时（0xFF）。
     *
     * 档位 N（N = 半小数）时 = `0x80 | ((N-1) shl 1) | 1`——位 7 与位 0 保持 1，与上游
     * encode 一致；7 小时 = 14 档 = 0x9B（对齐上游 raw `A1A34DFF9B34`）。
     */
    fun timerByte(minutes: Int?): Int {
        val halfHours = ((minutes ?: 0).coerceIn(0, 24 * 60)) / 30
        return if (halfHours >= 1) 0x80 or ((halfHours - 1) shl 1) or 1 else 0xFF
    }

    /** 校验和：前 5 字节按位反序求和 → 取 256 的补 → 再按位反序（= 第 6 字节）。 */
    fun checksum(firstFive: IntArray): Int {
        var sum = 0
        for (byte in firstFive) sum += reverseBits8(byte)
        return reverseBits8((256 - (sum and 0xFF)) and 0xFF)
    }

    /**
     * 6 字节帧 → ConsumerIrManager 的时长序列（微秒，交替通断、以通开头）。
     *
     * [repeats] 组；每组尾部带 100ms 重发间隔（末组的尾部间隔留在序列里，无害）。
     */
    fun pattern(frame: IntArray, repeats: Int = DEFAULT_REPEATS): IntArray {
        require(repeats >= 1) { "repeats 至少为 1" }
        val out = IntArray(repeats * GROUP_LENGTH)
        var i = 0
        fun put(value: Int) {
            out[i++] = value
        }
        fun emitHalf(inverted: Boolean) {
            put(HEADER_MARK_US)
            put(HEADER_SPACE_US)
            for (byte in frame) {
                for (bit in 7 downTo 0) {
                    val raw = ((byte shr bit) and 1) == 1
                    val one = if (inverted) !raw else raw
                    put(BIT_MARK_US)
                    put(if (one) ONE_SPACE_US else ZERO_SPACE_US)
                }
            }
            put(BIT_MARK_US)
            put(FOOTER_SPACE_US)
        }
        repeat(repeats) {
            emitHalf(inverted = false)
            emitHalf(inverted = true)
            out[i - 1] = FOOTER_SPACE_US + MESSAGE_GAP_US
        }
        return out
    }

    private fun reverseBits8(value: Int): Int {
        var v = value and 0xFF
        var result = 0
        repeat(8) {
            result = (result shl 1) or (v and 1)
            v = v shr 1
        }
        return result
    }
}
