package edu.jxslu.schedule.ui.ebike

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import edu.jxslu.schedule.data.kqcx.KqcxBikeClient
import edu.jxslu.schedule.data.prefs.DisplayPrefsStore
import edu.jxslu.schedule.domain.BikeCluster
import edu.jxslu.schedule.domain.BikeFailure
import edu.jxslu.schedule.domain.BikeNearby
import edu.jxslu.schedule.domain.NearbyBike
import edu.jxslu.schedule.domain.NearbyParseResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 一次「把镜头移过去」的请求。
 *
 * 用 [nonce] 当 LaunchedEffect 的 key：同样的坐标连点两次也要能动，比较坐标做不到这点。
 * [zoom] 为 null 表示保持当前缩放；[animated] 为 false 用于恢复上次视野，那一下不该
 * 从校园中心慢慢滑过去，直接落位。
 */
data class CameraRequest(
    val lat: Double,
    val lng: Double,
    val zoom: Double?,
    val nonce: Long,
    val animated: Boolean = true,
)

/**
 * 附近单车地图页状态（DESIGN §3.9）。
 *
 * [failure] 非空时**不清空** [clusters]：拿用户刚看到的那批车配一条失败提示，
 * 比把列表清成空白有用。空列表 + 无失败 = 这一带确实没车，两件事在 UI 上得分开说。
 */
data class BikeMapUiState(
    /** 请求进行中。 */
    val loading: Boolean = false,
    /** 正在取定位（最长 8 秒）。按钮据此显示进行中状态，别让用户以为点了没反应。 */
    val locating: Boolean = false,
    /** 至少完成过一次请求（含失败）；false = 刚进页面，还没结果。 */
    val queried: Boolean = false,
    val clusters: List<BikeCluster> = emptyList(),
    val failure: BikeFailure? = null,
    /** 最近一次成功刷新的时刻（epoch 毫秒）；0 = 还没成功过。 */
    val updatedAtMillis: Long = 0L,
    /** 当前展开的簇键；null = 全部收起。 */
    val expandedKey: String? = null,
    /** 待执行的镜头移动；null = 不动。 */
    val camera: CameraRequest? = null,
    /** 已取到的用户位置（GCJ-02）；null = 还没定位过。 */
    val userLat: Double? = null,
    val userLng: Double? = null,
    /** 只看可用的车（离线、电量低于运营方阈值的都不显示）。 */
    val onlyAvailable: Boolean = false,
    /**
     * 需要在列表里定位过去的停车点；[focusNonce] 递增用来区分"又点了一次同一个点"。
     *
     * 点地图标记时列表要滚到对应的卡片并高亮（DESIGN §3.9）——标记在屏幕中央，
     * 对应的卡片可能在列表里翻了好几屏，不滚过去用户不知道点中的是哪一条。
     */
    val focusKey: String? = null,
    val focusNonce: Long = 0L,
    /**
     * 底部车辆面板的高度（dp）。
     *
     * 放在状态里而不是页面局部 `remember`：局部状态首帧只能给默认值，等 DataStore 读回来
     * 时面板会跳一下，转屏还会再跳一次。上下限不在这里——夹取要用**当前窗口高度**算，
     * 那只有页面知道，所以 VM 只做「有限且为正」这一道校验。
     */
    val panelHeightDp: Float = DEFAULT_PANEL_HEIGHT_DP,
) {
    /** 列表里的车辆总数（跨停车点，已按 [onlyAvailable] 过滤）。 */
    val bikeCount: Int get() = clusters.sumOf { it.bikes.size }

    /** 距离是否以用户位置为参照。false = 以地图中心为参照（还没定位）。 */
    val distanceFromUser: Boolean get() = userLat != null && userLng != null
}

/**
 * 附近单车地图页（DESIGN §3.9 / §4.23）。
 *
 * 刷新由用户动作驱动，**不做后台轮询**：进页一次、拖动停稳一次、点刷新一次、
 * 定位成功后一次。拖动有 500ms 防抖，且与上次实际请求过的中心点距离不足 30 米就跳过。
 *
 * 一次刷新可能发多个请求（见 [fetchNearby]）：接口只给"离查询点最近的 20 辆"，
 * 单点查不全。稀疏区域只发 1 个，车多的区域才会加撒一圈采样点。
 */
class BikeMapViewModel(
    private val client: KqcxBikeClient,
    private val prefs: DisplayPrefsStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BikeMapUiState())
    val uiState: StateFlow<BikeMapUiState> = _uiState.asStateFlow()

    /** 防抖与查询**必须分两个 Job**，见 [startQuery] 的注释。 */
    private var debounceJob: Job? = null
    private var queryJob: Job? = null

    /**
     * 最近一次抓到的原始列表：距离以**查询中心**为参照，且没过筛选。
     *
     * 展示用的簇每次都由它重新算（[rebuildClusters]），这样换了参照点（定位）或改了筛选
     * 都不用重新请求接口。
     */
    private var fetched: List<NearbyBike> = emptyList()
    private var fetchedLat: Double? = null
    private var fetchedLng: Double? = null

    private var pendingLat = BikeNearby.DEFAULT_CENTER_LAT
    private var pendingLng = BikeNearby.DEFAULT_CENTER_LNG
    private var pendingZoom = BikeNearby.DEFAULT_ZOOM
    private var lastAttemptLat: Double? = null
    private var lastAttemptLng: Double? = null
    private var cameraNonce = 0L

    /**
     * 是否已经拿到过定位。
     *
     * 用来解决一个真实的时序竞争：读 DataStore 里的上次视野是异步的，而定位用系统缓存
     * 常常**先**回来。两者都对镜头下过指令，晚到的那个说了算——于是「恢复上次视野」
     * 会把刚定位好的镜头又拉回去，表现是蓝点不在正中（2026-09-23 真机实测偏了约 75 米）。
     */
    private var locatedOnce = false

    init {
        // 面板高度先读回来：首帧就用上用户上次拖出来的高度，否则进页面会先按默认高度
        // 画一帧再跳一下（用户明确说过面板不要跳）
        viewModelScope.launch {
            prefs.ebikePanelHeightDp.first()?.let(::setPanelHeight)
        }
        viewModelScope.launch {
            val saved = prefs.ebikeMapViewport.first()
            // 定位已经先回来了：它要的是"我在哪"，不是"我上次看哪"，别再拉回去
            if (locatedOnce) return@launch
            // 恢复上次的视野：没定位权限的人不必每次进页面都从校园中心重拖一遍。
            // 放在查询之前，这样首屏查的就是用户上次看的那一片
            if (saved != null) {
                pendingLat = saved.lat
                pendingLng = saved.lng
                pendingZoom = saved.zoom
                pushCamera(saved.lat, saved.lng, saved.zoom, animated = false)
            }
            scheduleQuery(immediate = true)
        }
    }

    /**
     * 地图中心或缩放变了。防抖后视位移决定要不要真的发请求。
     *
     * 程序性移动（定位、回到校区、点分组联动）期间地图侧会把回调吃掉，走不到这里，
     * 所以这里收到的都是用户自己拖出来的。
     */
    fun onCenterChanged(lat: Double, lng: Double, zoom: Double) {
        if (!lat.isFinite() || !lng.isFinite()) return
        if (zoom.isFinite() && zoom > 0) pendingZoom = zoom
        pendingLat = lat
        pendingLng = lng
        scheduleQuery(immediate = false)
    }

    /** 刷新按钮：跳过位移判断，问就是重查。 */
    fun refresh() {
        scheduleQuery(immediate = true)
    }

    /** 取定位期间置位，让按钮有个"在做事"的样子。 */
    fun setLocating(value: Boolean) {
        _uiState.update { it.copy(locating = value) }
    }

    /**
     * 拖动把手改底部面板高度（dp）。调用方传进来的值已经按当前窗口夹过一道，
     * 这里只挡 NaN / 负数这类不可能值。
     */
    fun setPanelHeight(value: Float) {
        if (!value.isFinite() || value <= 0f) return
        _uiState.update { it.copy(panelHeightDp = value) }
    }

    /**
     * 把手拖动：按**状态里的当前值**加一个增量再夹取，而不是收"算好的结果值"。
     *
     * 收增量是为了避开一个真实的坑：拖动事件一帧可能来好几个（还有多指），
     * 调用方手里的 `panelHeightDp` 只是上一帧组合时的快照，用它连算两次会丢掉一次位移，
     * 表现为拖了不走。夹取在这里做，读的是同一份状态，增量不会丢。
     *
     * [maxDp] 由页面按当前窗口高度算（VM 看不到窗口尺寸），窗口太矮时下限会高过上限，
     * 那时保持原值不动。
     */
    fun resizePanelBy(deltaDp: Float, minDp: Float, maxDp: Float) {
        if (!deltaDp.isFinite() || !minDp.isFinite() || !maxDp.isFinite()) return
        if (maxDp < minDp) return
        _uiState.update { current ->
            current.copy(
                panelHeightDp = (current.panelHeightDp + deltaDp).coerceIn(minDp, maxDp),
            )
        }
    }

    /** 把手松手才落盘：拖动途中每帧都写 DataStore 没必要。 */
    fun persistPanelHeight() {
        val value = _uiState.value.panelHeightDp
        viewModelScope.launch { prefs.setEbikePanelHeightDp(value) }
    }

    /** 只看可用的车。不重新请求接口——数据已经在手上，重算一遍簇即可。 */
    fun setOnlyAvailable(value: Boolean) {
        if (_uiState.value.onlyAvailable == value) return
        _uiState.update { it.copy(onlyAvailable = value) }
        rebuildClusters()
    }

    /**
     * 点标记或列表分组：展开/收起该停车点。
     *
     * 展开时顺手把地图移过去：列表给的是查询半径内的车，远的那些确实在屏幕外，
     * 只展开列表用户还是看不到它在哪。移动是程序性的，不会触发重查
     * （那批数据已经取回来了，没必要再问一次接口）。
     */
    fun onClusterTap(key: String) {
        val cluster = _uiState.value.clusters.firstOrNull { it.key == key }
        val expanding = _uiState.value.expandedKey != key
        _uiState.update {
            it.copy(
                expandedKey = if (expanding) key else null,
                focusKey = if (expanding) key else null,
                // 每次点都递增：同一个点连点两次也要能再滚一次
                focusNonce = it.focusNonce + 1,
            )
        }
        if (expanding && cluster != null) {
            pushCamera(cluster.lat, cluster.lng, zoom = null, animated = true)
        }
    }

    /** 「回到校区」：拉回默认中心并重查。 */
    fun onResetToCampus() {
        pushCamera(
            lat = BikeNearby.DEFAULT_CENTER_LAT,
            lng = BikeNearby.DEFAULT_CENTER_LNG,
            zoom = BikeNearby.DEFAULT_ZOOM,
            animated = true,
        )
        pendingLat = BikeNearby.DEFAULT_CENTER_LAT
        pendingLng = BikeNearby.DEFAULT_CENTER_LNG
        pendingZoom = BikeNearby.DEFAULT_ZOOM
        scheduleQuery(immediate = true)
    }

    /**
     * 定位成功（坐标已是 GCJ-02）。记下蓝点位置，把镜头移过去并立即重查。
     *
     * 同时**换参照点**重算距离：距离从此以"你"为准，拖动地图不会让这些数字变形。
     *
     * [animated] 为 false 用于进页面那一次：直接落位。进页面又没有起点可看，
     * 从校园中心滑过去纯属多此一举；用户主动点「定位」才需要一个缓动告诉他镜头动了。
     */
    fun onLocated(lat: Double, lng: Double, animated: Boolean = true) {
        if (!lat.isFinite() || !lng.isFinite()) return
        locatedOnce = true
        _uiState.update { it.copy(userLat = lat, userLng = lng) }
        rebuildClusters()
        pushCamera(lat, lng, zoom = null, animated = animated)
        pendingLat = lat
        pendingLng = lng
        scheduleQuery(immediate = true)
    }

    /**
     * 地图执行完一次镜头移动后回调，把请求清掉。
     *
     * 不清的话，Activity 一重建（转屏、内存回收、改字号）新组合就会拿同一个 nonce
     * 再放一次 LaunchedEffect —— 表现为「我把地图拖到别处，回来它自己跳回去了」。
     * 镜头请求是一次性动作，不是状态。
     */
    fun onCameraApplied(nonce: Long) {
        _uiState.update { current ->
            if (current.camera?.nonce == nonce) current.copy(camera = null) else current
        }
    }

    private fun pushCamera(lat: Double, lng: Double, zoom: Double?, animated: Boolean) {
        cameraNonce += 1
        _uiState.update {
            it.copy(camera = CameraRequest(lat, lng, zoom, cameraNonce, animated))
        }
    }

    /**
     * 用 [fetched] 重算展示用的簇：换参照点（有定位就用用户位置）、按上限与筛选过一遍、再聚簇。
     *
     * 簇的顺序来自距离，而距离以用户为参照时**不随地图中心变化**——用户拖地图时列表
     * 不会重排，只会换一批（服务端返回范围内）内容。
     */
    private fun rebuildClusters() {
        val state = _uiState.value
        val originLat = state.userLat ?: fetchedLat
        val originLng = state.userLng ?: fetchedLng
        val anchored = if (originLat != null && originLng != null) {
            BikeNearby.reanchor(fetched, originLat, originLng)
        } else {
            fetched
        }
        val shown = anchored
            // 撒点采样会把两公里外的车也捞回来，那些不算"附近"
            .filter { it.distanceMeters <= BikeNearby.MAX_NEARBY_DISTANCE_METERS }
            .let { list -> if (state.onlyAvailable) list.filter { it.available } else list }
        val clusters = BikeNearby.cluster(shown)
        _uiState.update { current ->
            current.copy(
                clusters = clusters,
                // 列表换了一批，展开态只在那个停车点还在时保留
                expandedKey = current.expandedKey?.takeIf { key -> clusters.any { it.key == key } },
            )
        }
    }

    private fun scheduleQuery(immediate: Boolean) {
        debounceJob?.cancel()
        debounceJob = viewModelScope.launch {
            if (!immediate) delay(QUERY_DEBOUNCE_MS)
            val lat = pendingLat
            val lng = pendingLng
            if (!immediate && !movedEnough(lat, lng)) return@launch
            startQuery(lat, lng)
        }
    }

    /**
     * 起一次查询。
     *
     * 只取消上一次**查询**，不碰防抖 Job：镜头移动期间地图每一帧都会走
     * `onCenterChanged`，两者共用一个 Job 的话，刚发出去的请求会被下一帧掐掉，
     * 页面就一直转圈。
     */
    private fun startQuery(lat: Double, lng: Double) {
        queryJob?.cancel()
        queryJob = viewModelScope.launch { query(lat, lng) }
    }

    /** 与上次**尝试过**的中心点比，位移是否够大。从没查过一律算够。 */
    private fun movedEnough(lat: Double, lng: Double): Boolean {
        val lastLat = lastAttemptLat ?: return true
        val lastLng = lastAttemptLng ?: return true
        return BikeNearby.distanceMeters(lastLat, lastLng, lat, lng) >=
            BikeNearby.MIN_REQUERY_SHIFT_METERS
    }

    private suspend fun query(lat: Double, lng: Double) {
        _uiState.update { it.copy(loading = true, failure = null) }

        val centerBikes = try {
            when (val parsed = BikeNearby.parse(client.queryNearbyJson(lat, lng), lat, lng)) {
                is NearbyParseResult.Ok -> parsed.bikes
                NearbyParseResult.ServiceError -> {
                    failQuery(lat, lng, BikeFailure.Service)
                    return
                }
                NearbyParseResult.Malformed -> {
                    failQuery(lat, lng, BikeFailure.Malformed)
                    return
                }
            }
        } catch (cancel: CancellationException) {
            // 取消要原样抛出去：吞掉它会让协程的取消变成一次"查询失败"，页面留下假错误
            throw cancel
        } catch (error: Exception) {
            failQuery(lat, lng, KqcxBikeClient.classify(error))
            return
        }

        // 中心点没返回满，说明这一带能查到的就这么多，不用再撒点浪费请求
        val bikes = if (centerBikes.size >= SAMPLE_PAGE_SIZE) {
            mergeSamples(lat, lng, centerBikes)
        } else {
            centerBikes
        }

        fetched = bikes
        fetchedLat = lat
        fetchedLng = lng
        lastAttemptLat = lat
        lastAttemptLng = lng
        _uiState.update {
            it.copy(
                loading = false,
                queried = true,
                failure = null,
                updatedAtMillis = System.currentTimeMillis(),
            )
        }
        rebuildClusters()
        // 记视野。写完这一次就够了，不需要在退出时再写一遍
        prefs.setEbikeMapViewport(lat, lng, pendingZoom)
    }

    private fun failQuery(lat: Double, lng: Double, failure: BikeFailure) {
        lastAttemptLat = lat
        lastAttemptLng = lng
        _uiState.update { it.copy(loading = false, queried = true, failure = failure) }
    }

    /**
     * 撒点补齐：中心的结果不够（返回满 20 辆，说明还有更远的没给），
     * 就在周围八个方位各查一次，按车号合并。
     *
     * 采样请求并行发，单个点失败只丢这个点——少看到一辆车，总比整页报错强。
     */
    private suspend fun mergeSamples(
        lat: Double,
        lng: Double,
        center: List<NearbyBike>,
    ): List<NearbyBike> {
        val merged = LinkedHashMap<String, NearbyBike>()
        center.forEach { bike -> merged[bike.carNum] = bike }
        val rings = BikeNearby.samplePoints(lat, lng).drop(1)
        val extra = coroutineScope {
            rings.map { point -> async { sampleAt(point.lat, point.lng) } }.awaitAll()
        }
        extra.forEach { list -> list.forEach { bike -> merged.putIfAbsent(bike.carNum, bike) } }
        return merged.values.toList()
    }

    /** 单个采样点。服务端报错、结构不符、网络异常一律算空——采样点失败不该拖垮整页。 */
    private suspend fun sampleAt(lat: Double, lng: Double): List<NearbyBike> = try {
        when (val parsed = BikeNearby.parse(client.queryNearbyJson(lat, lng), lat, lng)) {
            is NearbyParseResult.Ok -> parsed.bikes
            NearbyParseResult.ServiceError -> emptyList()
            NearbyParseResult.Malformed -> emptyList()
        }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        emptyList()
    }

    companion object {
        /** 拖动停稳后才发请求（毫秒）。地图滑动事件很密，不防抖会连发十几次。 */
        private const val QUERY_DEBOUNCE_MS = 500L

        /** 接口一次返回的封顶条数（2026-09-23 实测）。返回满这个数就认为还有更远的没给。 */
        private const val SAMPLE_PAGE_SIZE = 20

        /** 失败类别的用户文案（UI 层唯一出处）。 */
        fun failureText(failure: BikeFailure): String = when (failure) {
            BikeFailure.Network -> "网络连接失败，请检查网络后重试"
            BikeFailure.Timeout -> "请求超时，请稍后重试"
            BikeFailure.Service -> "车辆服务暂时不可用，请稍后重试"
            BikeFailure.Malformed -> "车辆数据解析失败，请稍后重试"
        }
    }

    class Factory(
        private val client: KqcxBikeClient,
        private val prefs: DisplayPrefsStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            BikeMapViewModel(client, prefs) as T
    }
}

/**
 * 底部车辆面板的高度档位（dp），页面与状态共用一套口径。
 *
 * 存**定值**而不是屏幕比例：比例在窗口变化时会自己变，面板跟着跳，用户明确要求过别跳。
 * [PANEL_MAX_RATIO] 是第二道上限，窗口再矮也要给地图留三成，否则拖到顶只剩一条缝。
 */
internal const val DEFAULT_PANEL_HEIGHT_DP = 300f
internal const val MIN_PANEL_HEIGHT_DP = 180f
internal const val MAX_PANEL_HEIGHT_DP = 520f
internal const val PANEL_MAX_RATIO = 0.7f
