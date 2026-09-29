package edu.jxslu.schedule.domain

/**
 * 还车点 / 禁停区图层的**缓存策略**（DESIGN §3.9，2026-09-28 用户口径：停车点缓存）。
 *
 * 这里只放**纯判定**：一条缓存管多大范围、多久算新鲜、新的结果怎么写回列表；读写落盘在
 * `data/kqcx/ZoneCacheStore`，编排在 `BikeMapViewModel`。
 *
 * **缓存的定位（2026-09-29 校正）**：接口回的是"离查询点最近的 15 个"，集合极其局部
 * （实测挪 100 米换 5 个、挪 400 米换 13 个），所以缓存**不是**"整片区域免加载"，只服务
 * 两件事：① **进页把上次那片先摆上**（缓存中心就是上次的查询中心，与恢复的视野重合，
 * 省掉冷启动那一次等待）；② **几乎没动的微调**不重复问。真正的移动一律重新拉（0.2~0.3s），
 * 与官方"每次 drag-end 都拉"同口径——覆盖半径一旦放大，就会拿旧视野的点冒充新视野的图层。
 *
 * 一条缓存 = 「以某点为中心查到的这批图层」。点查询天然带中心，所以缓存也按中心存；
 * 距离用 [BikeNearby.distanceMeters]（与列表同口径，GCJ-02 直接量，不过 Gcj02）。
 */
data class ZoneCacheEntry(
    /** 这次查询的中心（GCJ-02）。 */
    val lat: Double,
    val lng: Double,
    /** 拉到这批图层的时刻（epoch 毫秒）。 */
    val fetchedAt: Long,
    /** 原始图层（**未过滤**：「只看本校」是展示口径，开关变化不重拉）。 */
    val zones: KvcxZones,
)

object ZoneCache {

    /**
     * 一条缓存覆盖的中心半径（米）。**只能取"几乎同一视野"这么小**。
     *
     * 2026-09-29 实测（本校车号，校园内五个查询点）：接口回的 `givecarList` 是**离查询点
     * 最近的 15 个还车点**，而这个集合极其局部——中心往东 400 米，真实 15 个里有 **13 个**
     * 不在原地那 15 个里；往东 800 米是 **15/15** 全换；就算只挪 100 米也换掉 5 个。
     * 所以半径一旦放大（初版是 1 公里），就会出现"视图移到有停车点的地方，P 却刷不出来"：
     * 我们拿旧中心的 15 个点当新视野的图层了（用户 2026-09-29 报的就是这个）。
     *
     * 50 米 = 只服务两种场景：**进页时把上次那片摆上**（缓存中心就是上次的查询中心，
     * 与恢复的视野重合）和**几乎没动的微调**。真正的移动一律重新拉（实测 0.2~0.3s，
     * 用户察觉不到），与官方"每次 drag-end 都拉"同口径。
     */
    const val COVER_RADIUS_METERS = 50.0

    /**
     * 与已有缓存中心近到什么程度就把那条替换掉（米）：同一片视野别越攒越多条。
     *
     * 略大于 [COVER_RADIUS_METERS]（100 > 50）：两条相距 100 米以内的缓存覆盖范围本来
     * 就几乎重合，留两条只是浪费名额；再远就值得各留一条（"上次看的那一片"要能找回来）。
     */
    const val REPLACE_RADIUS_METERS = 100.0

    /**
     * 缓存新鲜期。期内命中覆盖半径就不发请求；过期仍会**先摆上旧图层**再刷新
     * （见 `BikeMapViewModel.fetchZones`），所以过期不等于空白。
     *
     * 12 小时：半天刷一次，运营方早晚调整了还车点最迟半天内跟上。图层是装饰性提示，
     * 最终仍以快趣判定为准（页面免责已写明），半天的新鲜度与它相称。
     */
    const val TTL_MS = 12L * 60 * 60 * 1000

    /** 最多留几条（超了丢最旧的）：按"最近看过的视野"记，12 条够把常用几处都找回来。 */
    const val MAX_ENTRIES = 12

    /** [entry] 是否还在新鲜期内。[now] 为当前时刻（epoch 毫秒）。 */
    fun isFresh(entry: ZoneCacheEntry, now: Long): Boolean =
        now - entry.fetchedAt < TTL_MS

    /**
     * 覆盖 ([lat], [lng]) 的缓存里**最新**的一条；没有覆盖的返回 null。
     *
     * 取最新而不是最近的：两条都覆盖时，新拉到的那条才是当前服务端的说法。
     */
    fun bestCovering(entries: List<ZoneCacheEntry>, lat: Double, lng: Double): ZoneCacheEntry? =
        entries
            .filter { BikeNearby.distanceMeters(it.lat, it.lng, lat, lng) <= COVER_RADIUS_METERS }
            .maxByOrNull { it.fetchedAt }

    /**
     * 写入一条结果：落在 [REPLACE_RADIUS_METERS] 内的旧条目被替换（同一个片区
     * 反复拖动不会攒出一串几乎重合的条目），随后只保留最新的 [MAX_ENTRIES] 条。
     */
    fun put(entries: List<ZoneCacheEntry>, entry: ZoneCacheEntry): List<ZoneCacheEntry> {
        val kept = entries.filter {
            BikeNearby.distanceMeters(it.lat, it.lng, entry.lat, entry.lng) >
                REPLACE_RADIUS_METERS
        }
        return (kept + entry)
            .sortedByDescending { it.fetchedAt }
            .take(MAX_ENTRIES)
    }
}
