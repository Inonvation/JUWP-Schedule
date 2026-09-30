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

    /** 电量低于强提示档位（< [BikeNearby.LOW_BATTERY_BADGE_PERCENT]），列表里加图标标注。 */
    val batteryLowBadge: Boolean
        get() = batteryPercent?.let { it < BikeNearby.LOW_BATTERY_BADGE_PERCENT } ?: false
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

    /**
     * 「电量低」特殊标注的阈值（2026-09-24 用户需求）。
     *
     * 与 [LOW_BATTERY_HINT_PERCENT] 的分工：30% 只决定百分比数字的着色（弱提示），
     * 低于 20% 的车在展开列表里加图标 + 标签（强提示）——低于这个数的车多半骑不到
     * 目的地，不值得专门走过去。
     */
    const val LOW_BATTERY_BADGE_PERCENT = 20.0

    /**
     * 采样环半径（米），见 [samplePoints]。
     *
     * 2026-09-29 从 700 收到 **450**（用户口径：「稍微减小视图刷新的范围，现在太多了有点卡」）：
     * 环半径决定"一次刷新能捞回多大一片车"，700 米那圈把 1.4 公里外的车也捞进列表与地图，
     * 聚合圈多、每帧要画的标记就多（拖动时掉帧），列表也长。
     * 450 米（配 [MAX_NEARBY_DISTANCE_METERS] 1.2 公里）刚好覆盖校园尺度。
     */
    const val SAMPLE_RADIUS_METERS = 450.0

    /**
     * 展示距离上限（米）。多点采样会把更远的车也捞回来，那些不算"附近"，不进列表。
     *
     * 2026-09-29 从 2000 收到 **1200**（与 [SAMPLE_RADIUS_METERS] 一起收，理由同上）：
     * 校园对角线约 1.4 公里，站在校园中心 1.2 公里内已覆盖全校；再远的车既走不到、
     * 又白占列表与地图标记。
     */
    const val MAX_NEARBY_DISTANCE_METERS = 1200

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

    /**
     * 本校车辆在运营方台账里的校区名关键词（2026-09-27 实测）。
     *
     * 运营方把「附近车辆」接口的每辆车都挂在一个校区名下：本校的车
     * `servicesiteName` 是「南昌工程学院」（学校 2024 年已更名「江西水利电力大学」，
     * 但运营方台账至今用的旧名），隔壁江西师范大学的车是「江西师大」。
     * 两校紧挨着，车辆坐标重叠不过一条马路，不筛的话师大校园里的车会成片出现。
     *
     * 白名单按关键词匹配而不是黑名单：快趣以后在附近铺到第三所学校时不用改代码。
     * 「水利电力」同时兼容运营方将来把台账更新成新校名的情况。
     */
    private val OUR_CAMPUS_KEYWORDS = listOf("南昌工程学院", "水利电力")

    /**
     * 校区名是否属于本校。校区名**为空按本校算**：缺数据不该把一辆可能是本校的车
     * 悄悄藏掉（与电量的容错同一口径），栏外的漏网由地理围栏兜住。
     */
    fun isOurCampus(campusName: String): Boolean =
        campusName.isBlank() || OUR_CAMPUS_KEYWORDS.any { campusName.contains(it) }

    /**
     * 校区地理围栏（DESIGN §3.9「只看本校」）。**GCJ-02 顶点**，与车辆坐标、高德瓦片
     * 同一基准，判定与绘制都直接用，不要再过 [Gcj02.toGcj02]。
     *
     * 边界 2026-09-27 按**运营方官方运营区域**逐边确定（用户对照官方小程序指认，
     * 后按用户反馈的路段偏差复核）。顶点一律取四条界路的**中心线**，中心线由高德
     * z17/z18 瓦片按路面色带逐列提取（不是目测、不是车队坐标拟合）：
     * - 南沿**瑶湖西一路**（环岛向东至瑶湖西大道，此路自西向东走低约 1 个纬度分，
     *   顶点纬差是路的真实走向）；
     * - 东沿**瑶湖西大道**（师大附中一侧微弯，中段略西凸）；
     * - 北沿**瑶湖西二路**（自瑶湖西大道向西北缓升，与纬线斜交约 10°）；
     * - 西沿**天祥大道**（自环岛向东北，斜交约 20°）。
     * 四个角点即四条路的路口：西南=环岛东侧、东南=瑶湖西一路×瑶湖西大道、
     * 东北=瑶湖西二路×瑶湖西大道、西北=天祥大道×瑶湖西二路（路口信号灯处）。
     * 瑶湖西二路东段与瑶湖西大道同时也是江西师大活动区域的边界，两校以此相邻
     * 不重叠（师大车辆坐标全部在栏外，`BikeNearbyTest` 钉住）。**要改顶点就重新
     * 按路网中心线提取，别手挪**——2026-09-27 首版凭截图配准，四条边一度整体
     * 偏移 30~70 米，就是被"看着差不多"害的。
     */
    val CAMPUS_FENCE: List<GcjPoint> = listOf(
        GcjPoint(28.685040, 116.023880), // 西南 · 瑶湖西一路出环岛处
        GcjPoint(28.685000, 116.025200), // 南 · 沿瑶湖西一路
        GcjPoint(28.684780, 116.028600), // 南 · 沿瑶湖西一路
        GcjPoint(28.684170, 116.034600), // 南 · 沿瑶湖西一路
        GcjPoint(28.683930, 116.038210), // 东南 · 瑶湖西一路×瑶湖西大道
        GcjPoint(28.687200, 116.037690), // 东 · 沿瑶湖西大道
        GcjPoint(28.688000, 116.037590), // 东 · 沿瑶湖西大道
        GcjPoint(28.689600, 116.037290), // 东 · 沿瑶湖西大道
        GcjPoint(28.690400, 116.037230), // 东 · 沿瑶湖西大道（西凸顶点）
        GcjPoint(28.691200, 116.037200), // 东 · 沿瑶湖西大道（西凸顶点）
        GcjPoint(28.691800, 116.037240), // 东 · 沿瑶湖西大道
        GcjPoint(28.692400, 116.037320), // 东 · 沿瑶湖西大道
        GcjPoint(28.693440, 116.037520), // 东北 · 瑶湖西二路×瑶湖西大道
        GcjPoint(28.693620, 116.036800), // 北 · 沿瑶湖西二路
        GcjPoint(28.693880, 116.036000), // 北 · 沿瑶湖西二路
        GcjPoint(28.694160, 116.034800), // 北 · 沿瑶湖西二路
        GcjPoint(28.694860, 116.032400), // 北 · 沿瑶湖西二路
        GcjPoint(28.695150, 116.031200), // 北 · 沿瑶湖西二路
        GcjPoint(28.695810, 116.028800), // 北 · 沿瑶湖西二路
        GcjPoint(28.696040, 116.027510), // 西北 · 天祥大道×瑶湖西二路（路口信号灯）
        GcjPoint(28.694800, 116.027000), // 西 · 沿天祥大道
        GcjPoint(28.694000, 116.026740), // 西 · 沿天祥大道
        GcjPoint(28.690600, 116.025370), // 西 · 沿天祥大道
        GcjPoint(28.689400, 116.024930), // 西 · 沿天祥大道
        GcjPoint(28.686000, 116.023520), // 西 · 天祥大道近环岛
    )

    /**
     * 坐标是否落在校区围栏内。射线法（PNPOLY），顶点顺序不区分凹凸。
     *
     * 只用来给「只看本校」兜底，不必处理顶点正好落在边上的退化情形
     * （±1 厘米级的开闭差异对一辆停着的车毫无意义）。
     */
    fun inCampusFence(lat: Double, lng: Double): Boolean {
        var inside = false
        var j = CAMPUS_FENCE.lastIndex
        for (i in CAMPUS_FENCE.indices) {
            val a = CAMPUS_FENCE[i]
            val b = CAMPUS_FENCE[j]
            if (a.lng > lng != b.lng > lng) {
                val crossingLat = (b.lat - a.lat) * (lng - a.lng) / (b.lng - a.lng) + a.lat
                if (lat < crossingLat) inside = !inside
            }
            j = i
        }
        return inside
    }

    /**
     * 「只看本校」的完整判定（DESIGN §3.9）：车队归属与地理围栏**两条件都过**。
     *
     * 校区名筛的是运营方的车队台账，围栏筛的是坐标，互为兜底——台账挂错校区
     * （比如师大的车误挂本校名）会被围栏拦下，台账改了名或没填（本校车被判成
     * 非本校）会被名字白名单的空名放行 + 围栏兜住。任一条件单独失真都不会放进师大的车。
     */
    fun isOurCampusBike(lat: Double, lng: Double, campusName: String): Boolean =
        isOurCampus(campusName) && inCampusFence(lat, lng)

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
     * 采样点：中心加一圈方位点（默认 8 个，正北起每 45 度一个；[ringCount] = 4 时取四个对角）。
     *
     * 为什么需要撒点：服务端一次只返回**离查询点最近的 20 辆**。校园里一个车桩就停十几辆，
     * 于是以地图中心查一次，返回的全是那一个桩，别处的车根本不会出现（2026-09-23 用户实测：
     * 把窗口移过去才看得到）。多撒几个点、各查一次再按车号合并，覆盖范围就上来了。
     *
     * 半径取 [SAMPLE_RADIUS_METERS]：比校区尺度略小，相邻采样点的"最近 20"有重叠，
     * 中间不至于漏出空档。
     *
     * [ringCount] 由调用方按缩放给（2026-10-01，见 `BikeMapViewModel.ringSampleCount`）：
     * 默认校区尺度只撒 4 个对角点（请求数 9 → 5），缩得更小才用 8 个。
     * **别传 0**：z17 在纬度 28.7° 下的视野约 1.1×1.7 公里，比采样环还大，只查中心
     * 会让地图上只剩中心一小片有车。
     */
    fun samplePoints(
        lat: Double,
        lng: Double,
        ringCount: Int = RING_SAMPLE_COUNT,
    ): List<GcjPoint> {
        val count = ringCount.coerceIn(RING_MIN_SAMPLE_COUNT, RING_SAMPLE_COUNT)
        val points = mutableListOf(GcjPoint(lat, lng))
        repeat(count) { index ->
            // 4 点时从 45° 起（四个对角）：正东西南北那四个方向的车会全落在轴线上，
            // 对角围出来的覆盖更均匀
            val bearing = index * (360.0 / count) + if (count == RING_MIN_SAMPLE_COUNT) 45.0 else 0.0
            points += offsetBy(lat, lng, SAMPLE_RADIUS_METERS, bearing)
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

    /** 采样环最少撒几个（4 个对角点）：再少就退化成"只查中心"，覆盖会明显掉。 */
    private const val RING_MIN_SAMPLE_COUNT = 4

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
     * 解析「按车号查单车」（`/v2.0.0/queryOneCar`，2026-09-30）的响应为一辆车。
     *
     * 附近列表里没有目标车（被筛掉 / 还没查到那一片）时，App 拿完整车号向快趣点名查一次，
     * 拿到真实坐标与电量后高亮定位——官方扫码后的 `loadOneCar` 就是这条路。
     *
     * 响应 `result` 是**单个车辆对象**（官方只消费 `carNum/lat/lng/deviceType/currentPercent`
     * 五个字段）；信封非成功或解析不出车返回 null，调用方提示"查不到"。
     */
    fun parseSingle(raw: String, centerLat: Double, centerLng: Double): NearbyBike? {
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull()
        val obj = root as? JsonObject ?: return null
        val errorCode = obj["errorCode"].intValue()
        if (errorCode == null || errorCode != 0) return null
        val car = obj["result"] as? JsonObject ?: return null
        return parseSingleCar(car, centerLat, centerLng)
    }

    /**
     * 单车响应 → [NearbyBike]。与 [parseBike] 的差别在**缺字段的假设**：
     * 附近列表是"一次拿一批、坏了丢一条"，在线 / 启用字段缺失按坏数据处理；
     * 这里是用户拿着完整车号**点名**查这一辆，详情接口的字段口径只确认过五个，
     * 在线 / 启用缺了就当在线可用——缺数据不该把一辆真车标成"失联"。
     */
    private fun parseSingleCar(car: JsonObject, centerLat: Double, centerLng: Double): NearbyBike? {
        val carNum = car["carNum"].stringValue()?.trim().orEmpty()
        if (EbikeQr.bikeUrl(carNum) == null) return null
        val lat = car["lat"].doubleValue() ?: return null
        val lng = car["lng"].doubleValue() ?: return null
        if (!isUsableCoordinate(lat, lng)) return null

        val battery = car["currentPercent"].doubleValue()?.takeIf { it in 0.0..100.0 }
        val lowBattery = car["lowBattery"].doubleValue()?.takeIf { it in 0.0..100.0 }
        val online = car["onlineStatus"].intValue()
        val enabled = car["status"].intValue()
        val status = when {
            online == 0 -> BikeStatus.Offline
            enabled == 0 -> BikeStatus.Disabled
            online == null || enabled == null -> BikeStatus.Available
            else -> deriveStatus(online == 1, enabled == 1, battery, lowBattery)
        }
        return NearbyBike(
            carNum = carNum,
            lat = lat,
            lng = lng,
            batteryPercent = battery,
            status = status,
            model = car["carTypeName"].stringValue().orEmpty().trim(),
            siteName = correctSiteName(car["givecarName"].stringValue().orEmpty().trim()),
            campusName = car["servicesiteName"].stringValue().orEmpty().trim(),
            distanceMeters = distanceMeters(centerLat, centerLng, lat, lng)
                .roundToInt().coerceAtLeast(0),
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
