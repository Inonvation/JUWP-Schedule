package edu.jxslu.schedule.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 附近共享单车（DESIGN §3.9 / §4.23）。纯 JVM：解析运营方接口的响应、算距离、聚簇，
 * 不碰网络与 Android 类型（网络在 `data/kqcx/KqcxBikeClient`）。
 *
 * 坐标基准：接口给的是 **GCJ-02**，与高德栅格瓦片同一基准，渲染时**不要再转换**
 * （详见 §4.23「坐标基准」）。
 */

/** 车辆可用性。文案直接给 UI 用，省得两处各写一份。 */
enum class BikeStatus(val label: String) {
    /** 在线 + 启用 + 电量足。 */
    Available("可用"),

    /** 在线启用但电量低于运营方的阈值。仍可出码，只是跑不远。 */
    LowBattery("电量低"),

    /** 在线但被运营方置为不可用（维修、调度中）。 */
    Disabled("不可用"),

    /** 失联。位置是最后一次上报的，可能已经被人骑走。 */
    Offline("离线"),
}

/** 一辆车。字段只保留出码与展示要用的部分，`sn` / `bluetoothKey` 一类一律不取。 */
data class NearbyBike(
    /** 完整车号（9 位，如 `100000652`），直接进出行链接。 */
    val carNum: String,
    val lat: Double,
    val lng: Double,
    /** 电量百分比；接口没给时为 null。 */
    val batteryPercent: Double?,
    val status: BikeStatus,
    val model: String,
    /** 停车点，如「教学北大楼左侧」；接口没给时为空串。 */
    val siteName: String,
    /** 校区，如「南昌工程学院」。 */
    val campusName: String,
    /** 离本次查询中心点的距离（本地算，见 [BikeNearby.distanceMeters]）。 */
    val distanceMeters: Int,
) {
    val available: Boolean get() = status == BikeStatus.Available

    /** 电量文案：没有数据就说没有，不要显示成 0%。 */
    val batteryText: String
        get() = batteryPercent?.let { "${it.roundToInt()}%" } ?: "电量未知"

    /**
     * 电量偏低（**展示用**）。
     *
     * 与运营方的 `lowBattery` 阈值无关：那个是"能不能骑"的判定，这里只是"值不值得走过去"
     * 的视觉提示。低于固定档位就把数字标成警告色；电量未知时不算低，缺数据不该变成警告。
     */
    val batteryLow: Boolean
        get() = batteryPercent?.let { it < BikeNearby.LOW_BATTERY_HINT_PERCENT } ?: false
}

/**
 * 一个停车点。地图上的一枚标记对应一个簇。
 *
 * 为什么必须聚合：2026-09-23 校园实测，接口一次返回的 20 辆车坐标全部落在 10 米见方内，
 * 逐个画标记会叠成一坨，点也点不中（§4.23）。
 */
data class BikeCluster(
    /** 聚簇键（停车点名或坐标网格），既作列表 key 也作选中态标识。 */
    val key: String,
    /** 展示用停车点名；空串表示接口没给，此时回落到坐标网格。 */
    val siteName: String,
    /** 簇的展示坐标 = 成员坐标均值。 */
    val lat: Double,
    val lng: Double,
    /** 簇内车辆，按距离升序（与传入顺序一致）。 */
    val bikes: List<NearbyBike>,
) {
    /** 簇内最近一辆车的距离，用来在列表里排序与展示。 */
    val nearestDistanceMeters: Int get() = bikes.firstOrNull()?.distanceMeters ?: 0

    /** 簇的展示标题：没有停车点名就说「未标注停车点」。 */
    val title: String get() = siteName.ifBlank { "未标注停车点" }
}

/** 接口响应解析结果。 */
sealed interface NearbyParseResult {
    /** 解析成功；`bikes` 可能为空（这一带确实没车），空不是错误。 */
    data class Ok(val bikes: List<NearbyBike>) : NearbyParseResult

    /** 服务端明确报错（`errorCode != 0`）。 */
    data object ServiceError : NearbyParseResult

    /** 结构与预期不符（不是 JSON、缺 `result.carList`）。 */
    data object Malformed : NearbyParseResult
}

/** 查询失败的类别，文案由 UI 层给（见 `BikeMapViewModel`）。 */
enum class BikeFailure { Network, Timeout, Service, Malformed }

/**
 * 上次查看的地图视野（DESIGN §3.9）。进页面还没定位时从这里恢复，
 * 免得每次都从校园中心开局、让用户重新拖一遍。
 */
data class BikeMapViewport(val lat: Double, val lng: Double, val zoom: Double)

object BikeNearby {

    /**
     * 默认地图中心（GCJ-02）：瑶湖校区教学北大楼一带，取自 2026-09-23 实测的车辆聚集点。
     *
     * 硬编码而非取当前位置：v1 不申请位置权限，开局就落在有车的地方；用户拖动地图换区域。
     */
    const val DEFAULT_CENTER_LAT = 28.688320
    const val DEFAULT_CENTER_LNG = 116.028466

    /** 默认缩放级别：校区尺度，一屏能看见教学楼与宿舍。 */
    const val DEFAULT_ZOOM = 17.0

    /** 无停车点名时的聚簇网格精度（4 位小数约 11 米）。 */
    private const val CLUSTER_GRID_DECIMALS = 4

    /** 查询中心点位移小于该值不重复发请求（DESIGN §4.23 数据纪律）。 */
    const val MIN_REQUERY_SHIFT_METERS = 30.0

    /** 电量低于这个百分比就把数字标成警告色（纯展示阈值，与运营方的 lowBattery 无关）。 */
    const val LOW_BATTERY_HINT_PERCENT = 30.0

    /** 采样环半径（米），见 [samplePoints]。 */
    const val SAMPLE_RADIUS_METERS = 700.0

    /**
     * 展示距离上限（米）。多点采样会把两公里外的车也捞回来，
     * 那些不算"附近"，不进列表。
     */
    const val MAX_NEARBY_DISTANCE_METERS = 2000

    /**
     * 运营方停车点名里的错别字 → 校内实际楼名。
     *
     * 这些名字来自运营方的车辆台账，不是我们写的，写错了也只能在展示层纠正。
     * 2026-09-23 拉全了校区内的 `givecarName`，只有「济民楼」这一个楼名带错别字，
     * 而且同时存在两种错法（`挤名楼` / `挤明楼`），两个都映射到正确写法。
     *
     * 副作用是好的：改在**聚簇之前**，同一个停车点因错别字分裂成两簇的情况一并消失了。
     * 以后发现新的错名往这张表里加，别在 UI 层做字符串替换。
     */
    private val SITE_NAME_FIXES = mapOf(
        "挤名楼" to "济民楼",
        "挤明楼" to "济民楼",
    )

    /**
     * 纠正停车点名里的已知错别字；不认识的照原样返回。
     *
     * 另外把**纯数字**的名字当成"没写"（返回空串）：运营方台账里真有这种占位值
     * （2026-09-23 实测见过一个停车点直接叫 `22222`）。返回空串会让 UI 显示成
     * 「未标注停车点」，比把一串编号摆给用户看强。
     */
    fun correctSiteName(name: String): String {
        val fixed = SITE_NAME_FIXES[name] ?: name
        if (fixed.isNotEmpty() && fixed.all { it.isDigit() }) return ""
        return fixed
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * 解析附近车辆响应。[centerLat] / [centerLng] 是本次查询用的中心点，用来算距离。
     *
     * 容错口径：
     * - 单个条目坏掉（车号非数字、坐标越界或全 0、出不了码的车号）只丢这一条，不牵连整批；
     * - 数字字段写成字符串（`"28.688"`）也认；
     * - `errorCode` 缺失或非 0 一律归服务端错误；
     * - `result.carList` 缺失 → 结构不符；存在但为 null 或空数组 → 空列表。
     *
     * `carList` 缺失判成结构不符而不是"没车"：接口改了形状却显示成"这一带暂时没有车"，
     * 那是个会一直骗下去的谎。
     */
    fun parse(raw: String, centerLat: Double, centerLng: Double): NearbyParseResult {
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull()
        val obj = root as? JsonObject ?: return NearbyParseResult.Malformed

        val errorCode = obj["errorCode"].intValue()
        if (errorCode == null || errorCode != 0) return NearbyParseResult.ServiceError

        val result = obj["result"] as? JsonObject ?: return NearbyParseResult.Malformed
        if (!result.containsKey("carList")) return NearbyParseResult.Malformed
        val list = result["carList"]
        if (list is JsonNull) return NearbyParseResult.Ok(emptyList())
        val array = list as? JsonArray ?: return NearbyParseResult.Malformed

        val bikes = array.mapNotNull { element ->
            (element as? JsonObject)?.let { parseBike(it, centerLat, centerLng) }
        }
        return NearbyParseResult.Ok(sortBikes(bikes))
    }

    /** 按距离升序，距离相同时按车号升序——排序必须稳定，否则列表每次刷新都在跳。 */
    fun sortBikes(bikes: List<NearbyBike>): List<NearbyBike> =
        bikes.sortedWith(compareBy({ it.distanceMeters }, { it.carNum }))

    /**
     * 换一个参照点重算距离并重排（DESIGN §3.9）。
     *
     * 距离的意义全看参照点：定位之后一律按**用户位置**算，这样用户拖动地图时，
     * 列表里那个「473 米」不会悄悄变成"离屏幕中心 473 米"。没定位才退回按地图中心算。
     */
    fun reanchor(bikes: List<NearbyBike>, lat: Double, lng: Double): List<NearbyBike> =
        sortBikes(
            bikes.map { bike ->
                bike.copy(
                    distanceMeters = distanceMeters(lat, lng, bike.lat, bike.lng)
                        .roundToInt()
                        .coerceAtLeast(0),
                )
            },
        )

    /**
     * 采样点：中心加一圈八个方位（正北起，每 45 度一个）。
     *
     * 为什么需要撒点：服务端一次只返回**离查询点最近的 20 辆**。校园里一个车桩就停十几辆，
     * 于是以地图中心查一次，返回的全是那一个桩，别处的车根本不会出现（2026-09-23 用户实测：
     * 把窗口移过去才看得到）。多撒几个点、各查一次再按车号合并，覆盖范围就上来了。
     *
     * 半径取 [SAMPLE_RADIUS_METERS]：比校区尺度略小，相邻采样点的"最近 20"有重叠，
     * 中间不至于漏出空档。
     */
    fun samplePoints(lat: Double, lng: Double): List<GcjPoint> {
        val points = mutableListOf(GcjPoint(lat, lng))
        repeat(RING_SAMPLE_COUNT) { index ->
            points += offsetBy(lat, lng, SAMPLE_RADIUS_METERS, index * (360.0 / RING_SAMPLE_COUNT))
        }
        return points
    }

    /**
     * 从 [lat]/[lng] 沿 [bearingDegrees] 走 [meters] 米。
     *
     * 几百米尺度上用等距圆柱近似（把经纬度当平面），误差在厘米级，不值得为它上正算公式。
     */
    private fun offsetBy(
        lat: Double,
        lng: Double,
        meters: Double,
        bearingDegrees: Double,
    ): GcjPoint {
        val bearing = Math.toRadians(bearingDegrees)
        val dLat = meters * Math.cos(bearing) / METERS_PER_DEGREE_LAT
        val dLng = meters * Math.sin(bearing) /
            (METERS_PER_DEGREE_LAT * Math.cos(Math.toRadians(lat)))
        return GcjPoint(lat + dLat, lng + dLng)
    }

    /** 采样环上的点位数（8 个方位）。 */
    private const val RING_SAMPLE_COUNT = 8

    /** 一个纬度约合多少米。 */
    private const val METERS_PER_DEGREE_LAT = 111_320.0

    /**
     * 按停车点聚簇：`givecarName` 非空就用它分组，为空的车回落 4 位小数网格
     * （约 11 米，同一排车桩归一组）。
     *
     * 用运营方自己的停车点字段而不是纯几何距离：它不受定位抖动影响，
     * 20 辆车挤在一个点上时也不会因为几米误差被切成两簇。代价是相距很近但名字不同的
     * 两堆车会分成两枚标记，那是无害的。
     *
     * 输出顺序跟随入参首次出现的顺序（入参已按距离排序），所以簇也是按距离排的。
     */
    fun cluster(bikes: List<NearbyBike>): List<BikeCluster> {
        val groups = LinkedHashMap<String, MutableList<NearbyBike>>()
        bikes.forEach { bike ->
            groups.getOrPut(clusterKey(bike)) { mutableListOf() }.add(bike)
        }
        return groups.map { (key, members) ->
            BikeCluster(
                key = key,
                // 成员已按距离升序，第一个就是最近的那辆，用它当簇名
                siteName = members.first().siteName,
                lat = members.sumOf { it.lat } / members.size,
                lng = members.sumOf { it.lng } / members.size,
                bikes = members.toList(),
            )
        }
    }

    /**
     * 两点间的球面距离（米），haversine。
     *
     * 不用接口返回的 `distance` 字段：那是相对服务端理解的查询点算的，而我们自己发出去的
     * 中心点就在手上，本地算一份口径唯一，也省掉"字段缺失怎么办"的分支。
     * GCJ-02 的偏移在几百米尺度上近乎刚性平移，不影响相对距离。
     */
    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val earthRadius = 6371008.8
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val h = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).let { it * it }
        return 2 * earthRadius * asin(min(1.0, sqrt(h)))
    }

    /** 距离文案：1 公里以内给整米，超过给一位小数的公里。 */
    fun formatDistance(meters: Int): String = when {
        meters < 1000 -> "$meters 米"
        // 固定 Locale.US：系统 Locale 为部分欧洲语言时默认格式会给成「1,9 公里」
        else -> String.format(Locale.US, "%.1f 公里", meters / 1000.0)
    }

    // ---- 内部 ----

    private fun parseBike(car: JsonObject, centerLat: Double, centerLng: Double): NearbyBike? {
        val carNum = car["carNum"].stringValue()?.trim().orEmpty()
        // 车号拿来就是要出码的：出不了码的车不显示，免得点进去只得到一句报错
        if (EbikeQr.bikeUrl(carNum) == null) return null

        val lat = car["lat"].doubleValue() ?: return null
        val lng = car["lng"].doubleValue() ?: return null
        if (!isUsableCoordinate(lat, lng)) return null

        // 电量与阈值只认 0~100 的有限数：上游把"没有数据"写成 -1，直接信它会显示成
        // 「-1%」还判成电量低（见 `缺电量字段不把车说成电量低`）。NaN / 无穷被
        // 区间判断顺手排除，不用再单独写 isFinite
        val battery = car["currentPercent"].doubleValue()?.takeIf { it in 0.0..100.0 }
        val lowBattery = car["lowBattery"].doubleValue()?.takeIf { it in 0.0..100.0 }
        val online = car["onlineStatus"].intValue() == 1
        val enabled = car["status"].intValue() == 1

        val distance = distanceMeters(centerLat, centerLng, lat, lng)
        return NearbyBike(
            carNum = carNum,
            lat = lat,
            lng = lng,
            batteryPercent = battery,
            status = deriveStatus(online, enabled, battery, lowBattery),
            model = car["carTypeName"].stringValue().orEmpty().trim(),
            siteName = correctSiteName(car["givecarName"].stringValue().orEmpty().trim()),
            campusName = car["servicesiteName"].stringValue().orEmpty().trim(),
            distanceMeters = distance.roundToInt().coerceAtLeast(0),
        )
    }

    /**
     * 状态推导。电量判定的前提是两项都拿到了：接口少给一个字段时不该把车说成电量低，
     * 那会让一辆正常车在地图上变灰。真正不可用的信号（离线、运营方停用）照旧生效。
     */
    private fun deriveStatus(
        online: Boolean,
        enabled: Boolean,
        battery: Double?,
        lowBattery: Double?,
    ): BikeStatus = when {
        !online -> BikeStatus.Offline
        !enabled -> BikeStatus.Disabled
        battery != null && lowBattery != null && battery <= lowBattery -> BikeStatus.LowBattery
        else -> BikeStatus.Available
    }

    private fun clusterKey(bike: NearbyBike): String {
        val site = bike.siteName.trim()
        if (site.isNotEmpty()) return "s:$site"
        val factor = Math.pow(10.0, CLUSTER_GRID_DECIMALS.toDouble())
        val lat = Math.round(bike.lat * factor) / factor
        val lng = Math.round(bike.lng * factor) / factor
        return "g:$lat,$lng"
    }

    private fun isUsableCoordinate(lat: Double, lng: Double): Boolean =
        lat.isFinite() && lng.isFinite() &&
            lat in -90.0..90.0 && lng in -180.0..180.0 &&
            (lat != 0.0 || lng != 0.0)

    // ---- JSON 取值：数字与字符串两种写法都认 ----

    private fun JsonElement?.doubleValue(): Double? {
        val primitive = this as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        return primitive.content.trim().toDoubleOrNull()
    }

    private fun JsonElement?.intValue(): Int? {
        val value = doubleValue() ?: return null
        return value.toInt()
    }

    private fun JsonElement?.stringValue(): String? {
        val primitive = this as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        return primitive.content
    }
}
