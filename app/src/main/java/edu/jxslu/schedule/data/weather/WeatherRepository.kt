package edu.jxslu.schedule.data.weather

import edu.jxslu.schedule.domain.WeatherSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 天气仓库（DESIGN §3.29）：进程内一份快照 + TTL 缓存，页面共用一个入口。
 *
 * 口径：
 * - **TTL 30 分钟**：Open-Meteo 明确要求客户端缓存（它的免费档按请求数算），
 *   而天气本身 30 分钟内也不会变；今日页每次回到前台都调 [snapshot]，
 *   命中缓存时**一行网络都不发**。
 * - **刷新失败回退旧快照**（宁旧勿空）：网络抖一下不该把今日页那行提示弄消失；
 *   完全没数据时给 null，页面不占位。
 * - **功能关着时调用方不该进来**（调用点先判开关）——本类不做开关判断，
 *   避免把「显示偏好」搅进数据层；关着还取数是调用点的 bug。
 *
 * 并发：同一时刻只放一个请求出去（[mutex] + 双检），今日页与详情页同时进也只是
 * 共用一次取数。不做跨进程持久化——缓存是「省请求」的手段，不是数据资产，
 * 冷启动重取一次比落盘对账便宜。
 */
class WeatherRepository(
    private val api: WeatherApi = WeatherApi.create(),
    private val lat: Double = WeatherApi.CAMPUS_LAT,
    private val lng: Double = WeatherApi.CAMPUS_LNG,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    companion object {
        /** 快照有效期：30 分钟。 */
        const val DEFAULT_TTL_MILLIS = 30 * 60 * 1000L
    }

    /**
     * 取数结果。[fresh] = 本次真的打了一次成功的网络请求；
     * false 表示「给的是缓存」（TTL 内没发网络，或刷新失败回退旧值）。
     * 页面据此判「要不要提示这是一份可能过期的数据」。
     * [atMs] = 这份数据的取数时刻（沿用旧值时是**旧那份**的时刻，不是本次调用的时刻）。
     */
    data class Load(val snapshot: WeatherSnapshot?, val fresh: Boolean, val atMs: Long)

    /** 缓存里的那一份（含取数时刻），供页面首帧直接铺上、不走骨架。 */
    data class Cached(val snapshot: WeatherSnapshot, val atMs: Long)

    private val mutex = Mutex()

    @Volatile
    private var cached: Cached? = null

    /**
     * 手里这一份缓存（可能已过期，**不判 TTL、不发网络**）。
     * 页面进页先用它铺内容：重新进页时缓存通常还在，不该再闪一次骨架。
     */
    fun peek(): Cached? = cached

    /**
     * 取快照：[force] 时忽略 TTL（详情页下拉刷新）。无任何可用数据时给 null。
     */
    suspend fun snapshot(force: Boolean = false): WeatherSnapshot? = load(force).snapshot

    /**
     * 取数（带「是否网络新取」标记）。无任何可用数据时 snapshot 为 null。
     */
    suspend fun load(force: Boolean = false): Load {
        cached?.let { entry ->
            if (!force && isFresh(entry)) return Load(entry.snapshot, fresh = false, atMs = entry.atMs)
        }
        mutex.withLock {
            // 双检：等锁期间别人可能已经刷过一轮
            cached?.let { entry ->
                if (!force && isFresh(entry)) return Load(entry.snapshot, fresh = false, atMs = entry.atMs)
            }
            val previous = cached
            return try {
                val fresh = api.fetch(lat, lng)
                val at = clock()
                cached = Cached(fresh, at)
                Load(fresh, fresh = true, atMs = at)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Load(previous?.snapshot, fresh = false, atMs = previous?.atMs ?: 0L)
            }
        }
    }

    private fun isFresh(entry: Cached): Boolean = clock() - entry.atMs < ttlMillis
}
