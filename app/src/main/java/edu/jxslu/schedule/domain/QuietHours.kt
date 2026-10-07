package edu.jxslu.schedule.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * 免扰时段（quiet hours，DESIGN §3.7）：一个每天重复的墙上时钟区间。
 *
 * 时段内的系统通知不立即发出，推迟到时段结束后补发（队列见
 * `data/repo/QuietHoursQueue`）；时刻型提醒过期丢弃的口径在 flush 层。
 *
 * 时间用「当天第几分钟」（0–1439）表示，纯整数域可 JVM 测；显示层负责
 * 与 `HH:mm` 互转。`startMinute > endMinute` 即跨零点（22:00→次日 07:00）。
 */
@Serializable
data class QuietWindow(
    /** 本机生成（UUID），列表增删改的稳定键。 */
    @SerialName("id") val id: String,
    /** 用户可见名，如「夜间休息」「午休」。 */
    @SerialName("label") val label: String,
    /** 开始分钟（含），0–1439。 */
    @SerialName("startMinute") val startMinute: Int,
    /** 结束分钟（不含），0–1439；等于 start 时区间为空，被 [QuietWindows.validate] 拒绝。 */
    @SerialName("endMinute") val endMinute: Int,
    /** 关掉的窗口保留在列表里（用户不用每次重建），只在判定时跳过。 */
    @SerialName("enabled") val enabled: Boolean = true,
)

/**
 * 免扰时段的判定与序列化（纯逻辑，可 JVM 测）。
 *
 * 序列化照 `SelectionWish` 的纪律：坏 JSON / 缺键不抛，回 [DEFAULT]——
 * 免扰是「默认就该有合理行为」的功能，存储损坏时退回预置时段比静默全放行安全。
 */
object QuietWindows {

    /** 一天的分钟数（区间端点上限，exclusive）。 */
    const val MINUTES_PER_DAY = 24 * 60

    /** 预置：夜间休息 22:00–次日 07:00、午休 12:30–13:30。 */
    val DEFAULT: List<QuietWindow> = listOf(
        QuietWindow(
            id = newId(),
            label = "夜间休息",
            startMinute = 22 * 60,      // 22:00
            endMinute = 7 * 60,         // 次日 07:00（跨零点）
        ),
        QuietWindow(
            id = newId(),
            label = "午休",
            startMinute = 12 * 60 + 30, // 12:30
            endMinute = 13 * 60 + 30,   // 13:30
        ),
    )

    fun newId(): String = UUID.randomUUID().toString()

    private val format = Json { ignoreUnknownKeys = true }

    /** 编码整表（含关掉的窗口，保留用户配置）。 */
    fun encode(windows: List<QuietWindow>): String =
        format.encodeToString(ListSerializer(QuietWindow.serializer()), windows)

    /** 缺键回 [DEFAULT]；坏 JSON 同样兜底，不抛。 */
    fun decode(text: String?): List<QuietWindow> {
        if (text.isNullOrBlank()) return DEFAULT
        return try {
            format.decodeFromString(ListSerializer(QuietWindow.serializer()), text)
        } catch (_: Exception) {
            DEFAULT
        }
    }

    /** 仅拒绝零长区间；端点 0–1439 越界由调用方钳制（滚轮选择器天然不会越界）。 */
    fun validate(startMinute: Int, endMinute: Int): Boolean = startMinute != endMinute

    /**
     * [minuteOfDay]（0–1439）是否落在任一启用窗口内。
     *
     * 区间 `[start, end)` 左闭右开：22:00 起拦、07:00 整放行；跨零点窗口
     * （start > end）等价于 `[start, 1440) ∪ [0, end)`。多窗口取并集。
     */
    fun isQuiet(windows: List<QuietWindow>, minuteOfDay: Int): Boolean {
        val m = ((minuteOfDay % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
        return windows.any { w ->
            w.enabled && contains(w, m)
        }
    }

    private fun contains(w: QuietWindow, minuteOfDay: Int): Boolean =
        if (w.startMinute <= w.endMinute) {
            minuteOfDay in w.startMinute until w.endMinute
        } else {
            // 跨零点：22:00→07:00 = [22:00, 24:00) ∪ [00:00, 07:00)
            minuteOfDay >= w.startMinute || minuteOfDay < w.endMinute
        }

    /**
     * [minuteOfDay] 时刻活跃窗口并集的结束分钟（补发闹钟的落点）。
     * 重叠窗口取最晚的 end——13:00 同时落在「12:30–14:00」与「午休 12:30–13:30」
     * 里时，补发应等 14:00，不能在 13:30 放行后又立刻落进另一个窗口反复横跳。
     * 不在任何窗口时返回 null。
     */
    fun activeEnd(windows: List<QuietWindow>, minuteOfDay: Int): Int? {
        val m = ((minuteOfDay % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
        var best: Int? = null
        for (w in windows) {
            if (!w.enabled || !contains(w, m)) continue
            val end = w.endMinute
            // 端点大小无相对意义（各自锚定自己的窗口），比「距 now 的时长」取最长
            val len = (end - m + MINUTES_PER_DAY) % MINUTES_PER_DAY
            val bestLen = best?.let { (it - m + MINUTES_PER_DAY) % MINUTES_PER_DAY } ?: -1
            if (len > bestLen) best = end
        }
        return best
    }

    /** `HH:mm` → 当天分钟（输入按滚轮选择器产出，天然合法；异常回 0:00）。 */
    fun minutesFromClockText(text: String): Int {
        val parts = text.split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: return 0
        val m = parts.getOrNull(1)?.toIntOrNull() ?: return 0
        if (h !in 0..23 || m !in 0..59) return 0
        return h * 60 + m
    }

    /** 当天分钟 → `HH:mm`。 */
    fun clockText(minuteOfDay: Int): String {
        val m = ((minuteOfDay % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
        return "%02d:%02d".format(m / 60, m % 60)
    }
}
