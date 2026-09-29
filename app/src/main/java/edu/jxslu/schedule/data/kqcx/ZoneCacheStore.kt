package edu.jxslu.schedule.data.kqcx

import android.content.Context
import edu.jxslu.schedule.domain.GcjPoint
import edu.jxslu.schedule.domain.KvcxParkSpot
import edu.jxslu.schedule.domain.KvcxZones
import edu.jxslu.schedule.domain.ZoneCache
import edu.jxslu.schedule.domain.ZoneCacheEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 还车点 / 禁停区图层的落盘缓存（DESIGN §3.9，2026-09-28；策略在 [ZoneCache]）。
 *
 * `filesDir` 下一份 JSON，存「以某点为中心查到的那批图层」（最多 [ZoneCache.MAX_ENTRIES] 条）。
 * 冷启动进地图、或拖回缓存覆盖的片区时先用它把图层顶起来，命中新鲜缓存连请求都不发
 * （判定全在 domain 层，本类只负责读写）。
 *
 * 读写都在 IO 线程（本类自己调度）；两端 runCatching——缓存坏了等于没有缓存，不影响主链路。
 * **只存公开的图层数据**：不含 token、不含账号信息（与 `PowerHistoryCache` 同一纪律）。
 *
 * 文件可注入（主构造收 [File]）：缓存文件格式是隐式契约，用临时文件往返一遍钉在单测里，
 * 改字段名这类"读旧文件读空"的静默回归当场就红。
 */
class ZoneCacheStore(private val file: File) {

    constructor(context: Context) : this(File(context.filesDir, CACHE_FILE))

    private val json = Json { ignoreUnknownKeys = true }
    /** 一份进程内互斥就够：地图页只有一个 VM 会写；`EbikeMapCache` 的删除是单向的。 */
    private val mutex = Mutex()

    /** 全部缓存条目（读不到/坏了回空表，调用方当"没有缓存"处理）。 */
    suspend fun load(): List<ZoneCacheEntry> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching { decode(file.readText()) }.getOrDefault(emptyList())
        }
    }

    /** 覆盖写整份缓存（条数由 [ZoneCache.put] 控制在小表，整写比增量稳）。 */
    suspend fun save(entries: List<ZoneCacheEntry>) {
        val text = encode(entries) ?: return
        withContext(Dispatchers.IO) {
            mutex.withLock { runCatching { file.writeText(text) } }
        }
    }

    /** 当前占用字节。**调用方负责 IO 线程**（`EbikeMapCache` 统计时用）。 */
    fun bytes(): Long = file.length()

    /** 删除缓存文件（「清除地图缓存」用）。缓存删掉即失效，不需要额外状态。 */
    fun delete() {
        runCatching { file.delete() }
    }

    // ---------- 序列化影子（本体不是 @Serializable，字段一一对应；与 PowerHistoryCache 同手法） ----------

    private fun decode(text: String): List<ZoneCacheEntry> =
        json.decodeFromString<List<EntryRow>>(text).map { row ->
            ZoneCacheEntry(
                lat = row.lat,
                lng = row.lng,
                fetchedAt = row.fetchedAt,
                zones = KvcxZones(
                    parkSpots = row.spots.map { spot ->
                        KvcxParkSpot(
                            lat = spot.lat,
                            lng = spot.lng,
                            outline = spot.outline.map { GcjPoint(it.lat, it.lng) },
                        )
                    },
                    nogoZones = row.nogo.map { outline -> outline.map { GcjPoint(it.lat, it.lng) } },
                ),
            )
        }

    private fun encode(entries: List<ZoneCacheEntry>): String? = runCatching {
        json.encodeToString(
            entries.map { entry ->
                EntryRow(
                    lat = entry.lat,
                    lng = entry.lng,
                    fetchedAt = entry.fetchedAt,
                    spots = entry.zones.parkSpots.map { spot ->
                        SpotRow(
                            lat = spot.lat,
                            lng = spot.lng,
                            outline = spot.outline.map { PointRow(it.lat, it.lng) },
                        )
                    },
                    nogo = entry.zones.nogoZones.map { outline ->
                        outline.map { PointRow(it.lat, it.lng) }
                    },
                )
            },
        )
    }.getOrNull()

    @Serializable
    private data class EntryRow(
        val lat: Double,
        val lng: Double,
        val fetchedAt: Long,
        val spots: List<SpotRow> = emptyList(),
        val nogo: List<List<PointRow>> = emptyList(),
    )

    @Serializable
    private data class SpotRow(
        val lat: Double,
        val lng: Double,
        val outline: List<PointRow> = emptyList(),
    )

    @Serializable
    private data class PointRow(val lat: Double, val lng: Double)

    companion object {
        /** 缓存文件名（`filesDir` 下；「清除地图缓存」就删它）。 */
        const val CACHE_FILE = "bike_zones_cache.json"
    }
}
