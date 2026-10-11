package edu.jxslu.schedule.data.weather

import edu.jxslu.schedule.data.net.AppHttp
import edu.jxslu.schedule.domain.WeatherDay
import edu.jxslu.schedule.domain.WeatherHour
import edu.jxslu.schedule.domain.WeatherNow
import edu.jxslu.schedule.domain.WeatherSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Open-Meteo 天气客户端（DESIGN §3.29）。裸 OkHttp + 宽容序列化，
 * 形态对齐 [edu.jxslu.schedule.data.wenqu.WenquApi]：**不用 Retrofit**——
 * 一共一个端点、无鉴权、无凭证，裸 OkHttp 更直白。
 *
 * 选型理由（2026-10-11 定，勿轻改）：
 * - **免 API Key、免注册**：密钥写进 App 会泄露，让每个用户自己申请 key 不现实；
 * - 覆盖中国（模型池含 CMA 的 GFS GRAPES），返回 WMO 码 + 降水概率 + 紫外线，
 *   查看页与提示文案需要的字段一次齐；
 * - 许可 CC-BY 4.0，**要求署名**：界面上「数据来源 Open-Meteo」由 UI 层负责，
 *   本类删不得这句来源注释。
 *
 * 红线：
 * - **无日志拦截器**（与一卡通 / 电费 / 快趣 / 问渠一致）；
 * - 请求只发坐标与字段清单，**不带任何设备或账号标识**；
 * - 超时收紧到 20s：详情页转圈可接受，但挂死不行。
 */
class WeatherApi private constructor(private val http: OkHttpClient) {

    companion object {

        /** 预报端点（免费开放，无 key）。 */
        const val BASE = "https://api.open-meteo.com/v1/forecast"

        /**
         * 默认查询点：瑶湖校区教学北大楼一带。
         *
         * 取值与 `domain/BikeNearby.DEFAULT_CENTER_*` 同一个点（那里是 **GCJ-02**，
         * 这里是 **WGS84** 口径，两者在南昌一带差几百米）。天气网格分辨率以公里计
         * （实测落格点在 28.717 / 115.993，离校区几公里），几百米的坐标口径差不影响结果，
         * 所以不做 GCJ-02 → WGS84 反算，直接用同一组数字。
         */
        const val CAMPUS_LAT = 28.688320
        const val CAMPUS_LNG = 116.028466

        /**
         * 界面上的地点名。天气查的是固定坐标、没有反查地名这一步，
         * 所以地点名与坐标一起写在常量里（瑶湖校区，南昌市高新技术开发区天祥大道 289 号）。
         */
        const val CAMPUS_LABEL = "南昌 · 瑶湖校区"

        /**
         * 逐日取 **7 天**（主流天气页的日数），逐小时只取 **24 小时**——
         * 小时条只铺到次日同一时刻，不为了 7 天把 168 条小时数据也拉回来。
         */
        private const val FORECAST_DAYS = 7

        private const val FORECAST_HOURS = 24

        private val TIMEZONE = "Asia/Shanghai"

        private val CURRENT_FIELDS =
            "temperature_2m,relative_humidity_2m,apparent_temperature,is_day,weather_code," +
                "wind_speed_10m,wind_direction_10m"

        // is_day 必须带着：夜里给 ☀️ 会很怪（逐小时格按它选昼夜图标）
        private val HOURLY_FIELDS = "temperature_2m,precipitation_probability,weather_code,is_day"

        private val DAILY_FIELDS =
            "weather_code,temperature_2m_max,temperature_2m_min," +
                "precipitation_probability_max,uv_index_max,wind_speed_10m_max,sunrise,sunset"

        /**
         * 宽容 JSON：接口加字段是常态（文档明说「不会新增必填参数」，但可选项会长），
         * `ignoreUnknownKeys` 是必须的；数组里的 null 也要接得住（uv_index_max 会回 null）。
         */
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
        }

        fun create(): WeatherApi = WeatherApi(AppHttp.withCallTimeout(20))
    }

    /**
     * 取一次预报。失败抛异常（网络 / HTTP / 解析），由仓库决定回退策略。
     * 坐标系：WGS84（与接口口径一致，见 [CAMPUS_LAT] 的说明）。
     */
    suspend fun fetch(lat: Double, lng: Double): WeatherSnapshot = withContext(Dispatchers.IO) {
        val url = buildString {
            append(BASE)
            append("?latitude=").append(lat)
            append("&longitude=").append(lng)
            append("&current=").append(CURRENT_FIELDS)
            append("&hourly=").append(HOURLY_FIELDS)
            append("&daily=").append(DAILY_FIELDS)
            append("&timezone=").append(TIMEZONE)
            append("&forecast_days=").append(FORECAST_DAYS)
            append("&forecast_hours=").append(FORECAST_HOURS)
        }
        val request = Request.Builder().url(url).get().build()
        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw WeatherRequestException("HTTP ${response.code}")
                val body = response.body?.string().orEmpty()
                parse(body)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: WeatherRequestException) {
            throw e
        } catch (e: Exception) {
            throw WeatherRequestException(e.message ?: "request failed", e)
        }
    }

    internal fun parse(body: String): WeatherSnapshot {
        val dto = try {
            json.decodeFromString(ForecastResponse.serializer(), body)
        } catch (e: Exception) {
            throw WeatherRequestException("malformed response", e)
        }
        val current = dto.current
        val daily = dto.daily
        val now = WeatherNow(
            tempC = current?.temperature_2m ?: throw WeatherRequestException("no current block"),
            apparentC = current.apparent_temperature,
            humidity = current.relative_humidity_2m?.toInt(),
            windKmh = current.wind_speed_10m,
            windDirection = current.wind_direction_10m,
            code = current.weather_code ?: 0,
            // 缺字段按白天处理：夜里给个太阳总比给月亮却配着防晒话术好
            isDay = (current.is_day ?: 1) == 1,
        )
        return WeatherSnapshot(
            current = now,
            hourly = hourlyOf(dto.hourly),
            daily = dailyOf(daily),
        )
    }

    private fun hourlyOf(dto: HourlyDto?): List<WeatherHour> {
        val times = dto?.time ?: return emptyList()
        val temps = dto.temperature_2m.orEmpty()
        val probs = dto.precipitation_probability.orEmpty()
        val codes = dto.weather_code.orEmpty()
        val days = dto.is_day.orEmpty()
        return times.indices.mapNotNull { index ->
            val time = parseDateTime(times.getOrNull(index)) ?: return@mapNotNull null
            WeatherHour(
                time = time,
                tempC = temps.getOrNull(index) ?: return@mapNotNull null,
                precipProbability = probs.getOrNull(index)?.toInt(),
                code = codes.getOrNull(index) ?: 0,
                isDay = days.getOrNull(index)?.let { it == 1 },
            )
        }
    }

    private fun dailyOf(dto: DailyDto?): List<WeatherDay> {
        val dates = dto?.time ?: return emptyList()
        val codes = dto.weather_code.orEmpty()
        val maxes = dto.temperature_2m_max.orEmpty()
        val mins = dto.temperature_2m_min.orEmpty()
        val probs = dto.precipitation_probability_max.orEmpty()
        val uvs = dto.uv_index_max.orEmpty()
        val winds = dto.wind_speed_10m_max.orEmpty()
        val sunrises = dto.sunrise.orEmpty()
        val sunsets = dto.sunset.orEmpty()
        return dates.indices.mapNotNull { index ->
            val date = parseDate(dates.getOrNull(index)) ?: return@mapNotNull null
            WeatherDay(
                date = date,
                code = codes.getOrNull(index) ?: 0,
                tempMaxC = maxes.getOrNull(index),
                tempMinC = mins.getOrNull(index),
                precipProbabilityMax = probs.getOrNull(index)?.toInt(),
                uvIndexMax = uvs.getOrNull(index),
                windMaxKmh = winds.getOrNull(index),
                sunrise = clockOf(sunrises.getOrNull(index)),
                sunset = clockOf(sunsets.getOrNull(index)),
            )
        }
    }

    /** "2026-10-11T06:14" → "06:14"；取不到给 null（UI 不占位）。 */
    private fun clockOf(value: String?): String? {
        val time = parseDateTime(value) ?: return null
        return "%02d:%02d".format(time.hour, time.minute)
    }

    private fun parseDate(value: String?): LocalDate? = try {
        value?.let(LocalDate::parse)
    } catch (e: Exception) {
        null
    }

    private fun parseDateTime(value: String?): LocalDateTime? = try {
        value?.let(LocalDateTime::parse)
    } catch (e: Exception) {
        null
    }
}

/** 取数失败（网络 / HTTP 非 200 / 解析失败），由仓库兜。 */
class WeatherRequestException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

// ---- 线上 DTO：字段名与接口一一对应，全部可空，缺字段当没给 ----

@Serializable
private data class ForecastResponse(
    val current: CurrentDto? = null,
    val hourly: HourlyDto? = null,
    val daily: DailyDto? = null,
)

@Serializable
private data class CurrentDto(
    val temperature_2m: Double? = null,
    val relative_humidity_2m: Double? = null,
    val apparent_temperature: Double? = null,
    val is_day: Int? = null,
    val weather_code: Int? = null,
    val wind_speed_10m: Double? = null,
    val wind_direction_10m: Int? = null,
)

@Serializable
private data class HourlyDto(
    val time: List<String>? = null,
    val temperature_2m: List<Double?>? = null,
    val precipitation_probability: List<Double?>? = null,
    val weather_code: List<Int?>? = null,
    val is_day: List<Int?>? = null,
)

@Serializable
private data class DailyDto(
    val time: List<String>? = null,
    val weather_code: List<Int?>? = null,
    val temperature_2m_max: List<Double?>? = null,
    val temperature_2m_min: List<Double?>? = null,
    val precipitation_probability_max: List<Double?>? = null,
    val uv_index_max: List<Double?>? = null,
    val wind_speed_10m_max: List<Double?>? = null,
    val sunrise: List<String?>? = null,
    val sunset: List<String?>? = null,
)
