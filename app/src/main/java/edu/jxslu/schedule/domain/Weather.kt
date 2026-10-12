package edu.jxslu.schedule.domain

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 天气快照（DESIGN §3.29）：Open-Meteo 一次 `/v1/forecast` 请求的解析结果。
 *
 * 纯数据 + 纯映射，可 JVM 测；取数与缓存归 `data/weather/WeatherRepository`。
 * 接口只回 WMO 数字码，中文短语与 emoji 都在这里映射（客户端职责，见 Open-Meteo 文档）。
 *
 * 单位固定：温度 °C、风速 km/h、降水概率 %。请求里显式带 `timezone=Asia/Shanghai`，
 * 所以时间字段直接落本地钟点，不再做时区换算。
 */
data class WeatherSnapshot(
    val current: WeatherNow,
    val hourly: List<WeatherHour>,
    /** 逐日预报，**下标 0 = 今天**（接口按 start_date 升序给）。 */
    val daily: List<WeatherDay>,
) {
    fun day(index: Int): WeatherDay? = daily.getOrNull(index)
}

/** 实时观测（接口 `current` 块）。 */
data class WeatherNow(
    val tempC: Double,
    val apparentC: Double?,
    val humidity: Int?,
    val windKmh: Double?,
    /** 风向（气象学口径：风从哪个方位吹来；接口给角度，0 = 北）。 */
    val windDirection: Int? = null,
    val code: Int,
    val isDay: Boolean,
)

/** 逐小时预报（详情页画降水概率用）。 */
data class WeatherHour(
    val time: LocalDateTime,
    val tempC: Double,
    val precipProbability: Int?,
    val code: Int,
    /** 该小时是不是白天（接口逐小时给 `is_day`）；缺字段按白天处理。 */
    val isDay: Boolean? = null,
)

/** 单日预报。除日期与天气码外全部可空——接口某些变量会回 null。 */
data class WeatherDay(
    val date: LocalDate,
    val code: Int,
    val tempMaxC: Double?,
    val tempMinC: Double?,
    val precipProbabilityMax: Int?,
    val uvIndexMax: Double?,
    val windMaxKmh: Double?,
    /** "06:14" 形态，接口给 ISO 本地时间串，取时分即可。 */
    val sunrise: String?,
    val sunset: String?,
)

/**
 * WMO 天气码 → emoji + 中文短语（Open-Meteo 文档附录的码表，逐条映射）。
 *
 * 分类函数（[isRain] 等）供提醒文案判优先级用；不要把判断散到 UI 层。
 * [isRain] **不含**雷暴码 95–99：雷暴另有更重的话术，两者不能同时命中。
 */
object WeatherCodes {

    fun isRain(code: Int): Boolean = code in 51..67 || code in 80..82

    fun isSnow(code: Int): Boolean = code in 71..77 || code == 85 || code == 86

    fun isThunder(code: Int): Boolean = code in 95..99

    fun isFog(code: Int): Boolean = code == 45 || code == 48

    /**
     * 这一小时会不会下（雨 / 雪 / 雷暴，或降水概率 ≥ [PRECIP_PERCENT]）。
     *
     * 「快下课有雨」的判据（DESIGN §3.29）：天冷时预报的「有雨」落到地上是雪，
     * 但话术是固定的「可能有雨」，与其拆成两套文案，不如统一按「有降水」判。
     * 概率门槛与主句的软提醒同值（30%）——低于它属于「大概率不下」，不该主动喊人带伞。
     */
    fun willPrecipitate(hour: WeatherHour): Boolean =
        isRain(hour.code) || isSnow(hour.code) || isThunder(hour.code) ||
            (hour.precipProbability ?: 0) >= PRECIP_PERCENT

    /** 逐小时降水概率的门槛：与 [WeatherAdvice.RAIN_HINT_PERCENT] 同口径（软提醒起点）。 */
    const val PRECIP_PERCENT = 30

    /** 白天与夜间的「晴」分开给（夜里给太阳会显得奇怪）。 */
    fun emoji(code: Int, isDay: Boolean = true): String = when (code) {
        0, 1 -> if (isDay) "☀️" else "🌙"
        2 -> if (isDay) "⛅" else "☁️"
        3 -> "☁️"
        45, 48 -> "🌫️"
        51, 53, 55 -> "🌦️"
        56, 57 -> "🌧️"
        61 -> "🌦️"
        63, 65 -> "🌧️"
        66, 67 -> "🌧️"
        71, 73 -> "🌨️"
        75, 77 -> "❄️"
        80, 81 -> "🌦️"
        82 -> "⛈️"
        85, 86 -> "🌨️"
        95 -> "⛈️"
        96, 99 -> "⛈️"
        else -> "🌡️"
    }

    fun label(code: Int): String = when (code) {
        0, 1 -> "晴"
        2 -> "多云"
        3 -> "阴"
        45, 48 -> "有雾"
        51, 53, 55 -> "毛毛雨"
        56, 57 -> "冻雨"
        61 -> "小雨"
        63 -> "中雨"
        65 -> "大雨"
        66, 67 -> "冻雨"
        71 -> "小雪"
        73 -> "中雪"
        75 -> "大雪"
        77 -> "米雪"
        80, 81 -> "阵雨"
        82 -> "强阵雨"
        85, 86 -> "阵雪"
        95 -> "雷阵雨"
        96, 99 -> "雷暴冰雹"
        else -> "天气未知"
    }

    /** 风向角度 → 八方位中文（气象学口径：风来自哪边叫什么风）。 */
    fun windLabel(degrees: Int?): String? {
        if (degrees == null) return null
        val names = listOf("北", "东北", "东", "东南", "南", "西南", "西", "西北")
        val normalized = ((degrees % 360) + 360) % 360
        val index = (((normalized + 22.5) / 45).toInt()) % 8
        return "${names[index]}风"
    }

    /** 紫外线指数档位（WMO 分级：0–2 弱 / 3–5 中等 / 6–7 强 / 8–10 很强 / 11+ 极强）。 */
    fun uvLabel(uv: Double?): String? = when {
        uv == null -> null
        uv < 3 -> "弱"
        uv < 6 -> "中等"
        uv < 8 -> "强"
        uv < 11 -> "很强"
        else -> "极强"
    }
}
