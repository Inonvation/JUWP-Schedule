package edu.jxslu.schedule.domain

import java.time.LocalDateTime
import kotlin.math.roundToInt

/**
 * 今日页顶栏那一行天气提示（DESIGN §3.29）：emoji + 一句话。
 *
 * 两条口径（用户 2026-10-11 定的，别自行改）：
 * 1. **18:00 分界**：18:00 前讲今天，18:00 起讲明天（[referenceDayIndex]）；
 * 2. 提示按优先级取**第一条命中**的话术，从「要带伞」这类行动项往下排，
 *    全不命中才退回「天气 + 温度区间」的中性描述。
 *
 * 纯函数，JVM 可测（WeatherAdviceTest 钉住每条优先级）。
 */
object WeatherAdvice {

    /** 18:00 起把参考日切到明天（用户口径）。 */
    const val TOMORROW_FROM_HOUR = 18

    /** 降水概率：≥ 这个值提「可能下雨」（带伞的软提醒）。 */
    const val RAIN_HINT_PERCENT = 30

    /** 降水概率：≥ 这个值直接说「记得带伞」。 */
    const val RAIN_CARRY_PERCENT = 60

    /** 紫外线指数 ≥ 这个值提醒防晒（WMO 分级：6 起为「强」）。 */
    const val UV_STRONG = 6.0

    /** 最高温 ≥ 这个值提醒防暑。 */
    const val HOT_TEMP_C = 33.0

    /** 最低温 ≤ 这个值提醒添衣。 */
    const val COLD_TEMP_C = 5.0

    /** 日最大风速 ≥ 这个值提醒风大。 */
    const val WINDY_KMH = 30.0

    /**
     * 参考日下标：18:00 前 = 今天（0），18:00 起 = 明天（1）。
     * 越界时调用方按「取不到」处理（快照只有 3 天，正常取得到）。
     */
    fun referenceDayIndex(now: LocalDateTime): Int =
        if (now.hour < TOMORROW_FROM_HOUR) 0 else 1

    /**
     * 今日页顶栏那一行。快照里没有参考日时给 null（页面不占位，不显示半截提示）。
     */
    fun line(snapshot: WeatherSnapshot, now: LocalDateTime): String? {
        val index = referenceDayIndex(now)
        val day = snapshot.day(index) ?: return null
        // 只有「讲今天」才用实时码判「正在下雨」；讲明天时实时码与主题无关
        val emoji = WeatherCodes.emoji(day.code, isDay = index == 0 && snapshot.current.isDay)
        return "$emoji ${tip(snapshot, day, today = index == 0)}"
    }

    /**
     * 一句话话术，按优先级命中即返回。顺序 = 行动价值的顺序：
     * 「正在下雨」> 雪 / 雷暴 > 有雨 > 可能下雨 > 雾 > 防晒 > 防暑 > 添衣 > 风大 > 中性描述。
     */
    fun tip(snapshot: WeatherSnapshot, day: WeatherDay, today: Boolean): String {
        val label = if (today) "今天" else "明天"
        val probability = day.precipProbabilityMax
        if (today && WeatherCodes.isRain(snapshot.current.code)) {
            return "外面正在下雨，出门记得带伞"
        }
        if (WeatherCodes.isSnow(day.code)) return "${label}有雪，注意保暖防滑"
        if (WeatherCodes.isThunder(day.code)) return "${label}有雷暴，尽量别在户外"
        if (WeatherCodes.isRain(day.code)) return "${label}有雨，记得带伞"
        if (probability != null && probability >= RAIN_CARRY_PERCENT) {
            return "${label}可能有雨，记得带伞"
        }
        if (probability != null && probability >= RAIN_HINT_PERCENT) {
            return "${label}可能有雨，带把伞稳妥"
        }
        if (WeatherCodes.isFog(day.code)) return "${label}有雾，出行注意安全"
        val uv = day.uvIndexMax
        if (uv != null && uv >= UV_STRONG) return "${label}紫外线强，注意防晒"
        val max = day.tempMaxC
        if (max != null && max >= HOT_TEMP_C) return "${label}很热，注意补水防暑"
        val min = day.tempMinC
        if (min != null && min <= COLD_TEMP_C) return "${label}气温低，记得添衣"
        val wind = day.windMaxKmh
        if (wind != null && wind >= WINDY_KMH) return "${label}风大，出门注意"
        val range = tempRange(day)
        val weather = WeatherCodes.label(day.code)
        return if (range != null) "$label$weather，$range" else "$label$weather"
    }

    /** "19~29°C"；缺任一端给 null（不显示半截区间）。 */
    fun tempRange(day: WeatherDay): String? {
        val min = day.tempMinC ?: return null
        val max = day.tempMaxC ?: return null
        return "${min.roundToInt()}~${max.roundToInt()}°C"
    }
}
