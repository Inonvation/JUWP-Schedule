package edu.jxslu.schedule.ui.ebike

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.kqcx.KqcxBikeClient
import edu.jxslu.schedule.data.kqcx.KqcxSessionRepository
import edu.jxslu.schedule.data.kqcx.KvcxZoneSource
import edu.jxslu.schedule.data.kqcx.ZoneCacheStore
import edu.jxslu.schedule.data.prefs.DisplayPrefsStore
import edu.jxslu.schedule.domain.BikeCluster
import edu.jxslu.schedule.domain.BikeFailure
import edu.jxslu.schedule.domain.BikeNearby
import edu.jxslu.schedule.domain.EbikeUseMode
import edu.jxslu.schedule.domain.KvcxZones
import edu.jxslu.schedule.domain.NearbyBike
import edu.jxslu.schedule.domain.NearbyParseResult
import edu.jxslu.schedule.domain.ZoneCache
import edu.jxslu.schedule.domain.ZoneCacheEntry
import edu.jxslu.schedule.domain.capabilities
import edu.jxslu.schedule.ui.common.NoticeTone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

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

/** 地图页的一次性提示（用车动作结果等）；[tone] 决定提示语气。 */
sealed interface BikeMapEvent {
    data class Notice(val text: String, val tone: NoticeTone) : BikeMapEvent

    /** 开锁成功：页面补一次成功触感（与出码页同口径）。 */
    data object Unlocked : BikeMapEvent
}

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
    /**
     * 只看可用的车（离线、电量低于运营方阈值的都不显示）。
     * 默认开（2026-09-24 用户拍板）：找车就是要找能骑的；开关态进 DataStore，下次进页保持。
     */
    val onlyAvailable: Boolean = true,
    /**
     * 只看本校的车（快趣同时服务隔壁江西师大，两校坐标只隔一条马路）。
     * 默认开（2026-09-27 用户拍板）：筛的是车队归属 + 校区围栏双条件
     * （[BikeNearby.isOurCampusBike]），开关态进 DataStore，下次进页保持。
     */
    val onlyOurCampus: Boolean = true,
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
     * 时面板会跳一下，转屏还会再跳一次（用户明确要求过面板别跳）。上下限不在这里——
     * 夹取要用**当前窗口高度**算，那只有页面知道，所以 VM 只做「有限且为正」这一道校验。
     */
    val panelHeightDp: Float = DEFAULT_PANEL_HEIGHT_DP,
    /**
     * 中心结果已落地、周围的撒点还在飞（2026-09-28 拆分）：**不再算"刷新中"**——
     * 头部进度圈在中心结果回来时就该收（拖动换地方时等待感主要来自它），
     * 撒点补全只在副标题挂一句「正在补全周围…」。
     */
    val completing: Boolean = false,
    /**
     * 还车点 / 禁停区图层（DESIGN §3.9，2026-09-28）：与车辆列表同一次用户动作驱动
     * （进页 / 拖动停稳 / 刷新 / 定位成功后各拉一次），**不额外轮询**。
     * 失败保留上一层：图层是装饰，空白比旧值更糟。
     */
    val zones: KvcxZones = KvcxZones.EMPTY,
    /**
     * 被识别条「定位」的车号（2026-09-29「车号识别联动」）：地图上画高亮圈。
     * 存车号不存坐标——刷新换批后车可能不在了，按号在最新结果里重找，找得到才画。
     */
    val highlightCarNum: String? = null,
    /** [highlightCarNum] 在当前结果里对应的车；null = 还没找到（等下一笔查询）。 */
    val highlightedCar: NearbyBike? = null,
    /**
     * 列表的**距离参照点**（2026-09-30）：距离数字与排序都用它，两者必须同源——
     * 旧版数字按「距你」算、排序却按查询中心算，拖远之后顺序就和地图对不上了。
     *
     * 只在**一次查询落地时**重算，拖动过程中不会翻：
     * - 用户位置已知且查询中心离用户不超过 [USER_ANCHOR_RADIUS_METERS] → 用用户位置（标「距你」）；
     * - 否则用查询中心（标「距中心」）——地图拖远了就按"你正在看的这一片"排，顺序才和地图对得上。
     */
    val anchorLat: Double? = null,
    val anchorLng: Double? = null,
    val anchorFromUser: Boolean = false,
) {
    /** 列表里的车辆总数（跨停车点，已按 [onlyOurCampus] 与 [onlyAvailable] 过滤）。 */
    val bikeCount: Int get() = clusters.sumOf { it.bikes.size }

    /** 距离是否以用户位置为参照。false = 以查询中心为参照。 */
    val distanceFromUser: Boolean get() = anchorFromUser
}

/**
 * 附近单车地图页（DESIGN §3.9 / §4.23）。
 *
 * 刷新由用户动作驱动，**不做后台轮询**：进页一次、拖动停稳一次、点刷新一次、
 * 定位成功后一次。拖动由 View 层判定"停手"（`SettleWatcher`：事件安静 + `scroller.isFinished`）
 * 后回调 [onMapSettled]，再经 [QUERY_CONFIRM_MS] 的合并窗口发出——滑行途中一次都不发；
 * 与上次实际请求过的中心点距离不足 30 米同样跳过。
 *
 * 还车点 / 禁停区图层在用户动作基础上多一层**落盘缓存**（2026-09-28，[ZoneCacheStore]）：
 * 命中就先摆上、新鲜就不联网，只有「刷新」按钮强制重拉——缓存省的是重复往返，不是替代接口。
 *
 * 一次刷新可能发多个请求（见 [query] / [mergeSamples]）：接口只给"离查询点最近的 20 辆"，
 * 单点查不全。稀疏区域只发 1 个，车多的区域才会加撒一圈采样点。中心点那批先落列表，
 * 撒点回来再合并刷一次，不让用户等最慢的那个请求。
 */
class BikeMapViewModel(
    private val client: KqcxBikeClient,
    private val prefs: DisplayPrefsStore,
    /** 快趣会话（DESIGN §4.32）：本机开锁 / 锁车 / 还车与「当前用车」标记都靠它。 */
    kqcx: KqcxSessionRepository? = null,
    /** 还车点 / 禁停区图层的数据源（只读；未登录时为 null）。 */
    private val zoneSource: KvcxZoneSource? = null,
    /**
     * 图层的落盘缓存（DESIGN §3.9「停车点缓存」）：命中就先摆上、新鲜就不联网；
     * 每次成功拉取写回。null = 不缓存（只影响图层，车辆列表照旧）。
     */
    private val zoneCache: ZoneCacheStore? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BikeMapUiState())
    val uiState: StateFlow<BikeMapUiState> = _uiState.asStateFlow()

    private val _events = Channel<BikeMapEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /**
     * 本机用车（DESIGN §4.32，与出码页共用同一份编排）：骑行状态与写动作。
     * 地图页在本页就能开锁、锁车、还车——官方小程序也是在地图上直接操作当前用车。
     * **只在账号登录方式下使用**，见 [useMode] / [queryRideQuietly]。
     */
    val kvcx: KvcxRideController = KvcxRideController(
        session = kqcx,
        scope = viewModelScope,
        onNotice = { text, tone -> _events.trySend(BikeMapEvent.Notice(text, tone)) },
        onUnlocked = { _events.trySend(BikeMapEvent.Unlocked) },
    )

    /**
     * 使用方式（DESIGN §3.9 / §4.32，2026-09-29）：地图上的「开锁」按钮与「当前用车」卡
     * 只属于账号登录方式，小程序方式只保留查车与图层。
     *
     * 初值阻塞读一次（同 `MeViewModel.initialPrefs` 模式）：模式决定首帧露不露出用车入口，
     * 初值给错会先按另一档画一帧再翻过来——那是看得见的闪。DataStore 读过一次后常驻内存。
     */
    val useMode: StateFlow<EbikeUseMode> = prefs.ebikeUseMode.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        runBlocking {
            runCatching { prefs.ebikeUseMode.first() }.getOrDefault(EbikeUseMode.Default)
        },
    )

    /**
     * 查一次骑行状态（仅账号登录方式）。小程序方式直接不查：地图上根本没有用车入口，
     * 查了没人看，还要为一次登录态查询打扰第三方接口。
     *
     * 读原始流判定（不读 [useMode] 的 StateFlow）——行为判定按仓库既有口径走真值，
     * 读失败按「不是账号方式」处理（宁可不查，也不拿不准就打扰接口）。
     */
    fun queryRideQuietly() {
        viewModelScope.launch {
            if (!accountMode()) return@launch
            kvcx.query(quiet = true)
        }
    }

    /** 手动刷新骑行状态（「当前用车」卡上的刷新按钮）：失败给出提示，仍不做自动轮询。 */
    fun refreshRide() {
        viewModelScope.launch {
            if (!accountMode()) return@launch
            kvcx.query(quiet = false)
        }
    }

    private suspend fun accountMode(): Boolean =
        runCatching { prefs.ebikeUseMode.first().capabilities().inAppRide }.getOrDefault(false)

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

    /**
     * 最近一次拉到的原始图层（未过滤）：「只看本校」开关变化时从这里重算，
     * 与车辆列表同一套口径——开关不重新请求接口。
     */
    private var fetchedZones: KvcxZones = KvcxZones.EMPTY

    /** 落盘缓存的当前内容（进页读一次；之后每次成功拉取追加，判定见 [ZoneCache]）。 */
    private var zoneEntries: List<ZoneCacheEntry> = emptyList()

    /** 本次会话是否已经拿到过网络图层：拿到之后缓存只用于"换片区的即时展示"，不再补种。 */
    private var zonesFromNetwork = false

    /**
     * 上一次图层请求用的车号上下文（车号 + 它所属校区）。
     *
     * `queryZoneList` 的还车点 / 禁停区是**按车号所属校区**出的（2026-09-28 实测：拿本校
     * 车号、查询点放到 6 公里外，回来的仍是本校那批还车点；换一辆师大车号问同一个点，
     * 回的是师大校区的点）。所以换校区要换车号重拉——官方就是拿查询点附近那辆车的车号
     * 当上下文（`loadNearbyElements` 里 `carNum: list[0].name`）。
     *
     * 2026-09-29 追加：**这一带没车时继续拿上一次的上下文**，而不是不发请求。接口只需要
     * "哪个校区"，不需要车真的在旁边；旧规则（没车就不拉）会让"视图落在没车的角落、
     * 但看得到还车点"的情况下一个「P」都没有（用户报「移到有停车区的地方却刷不出来」）。
     */
    private data class ZoneCarContext(val carNum: String, val campusName: String)

    private var lastZoneCar: ZoneCarContext? = null

    /** 图层请求序号：旧响应回来时若已有更新的请求发出，直接作废（别盖掉新区域的图层）。 */
    private var zoneNonce = 0L

    /** 在飞的图层请求：新的一发就把旧的取消（图层只跟最新中心点）。 */
    private var zoneJob: Job? = null

    /** 还没有车当上下文时的待发图层请求（只有本次会话第一次会用到）。 */
    private data class ZonePending(val lat: Double, val lng: Double, val force: Boolean)

    private var pendingZone: ZonePending? = null
    private var fetchedLat: Double? = null
    private var fetchedLng: Double? = null

    private var pendingLat = BikeNearby.DEFAULT_CENTER_LAT
    private var pendingLng = BikeNearby.DEFAULT_CENTER_LNG
    private var pendingZoom = BikeNearby.DEFAULT_ZOOM
    private var lastAttemptLat: Double? = null
    private var lastAttemptLng: Double? = null
    private var cameraNonce = 0L

    /** 导航带入、还没在查询结果里找到的待定位车号（见 [setPendingFocusCar]）。 */
    private var pendingFocusCar: String? = null

    /**
     * 是否已经拿到过定位。
     *
     * 用来解决一个真实的时序竞争：读 DataStore 里的上次视野是异步的，而定位用系统缓存
     * 常常**先**回来。两者都对镜头下过指令，晚到的那个说了算——于是「恢复上次视野」
     * 会把刚定位好的镜头又拉回去，表现是蓝点不在正中（2026-09-23 真机实测偏了约 75 米）。
     */
    private var locatedOnce = false

    init {
        // 图层缓存先读回来（几十 KB 的文件，毫秒级）：进页那一刻就把上次那片还车点摆上，
        // 命中的话连请求都不发（判定在 domain/ZoneCache）。与下面几条并行，互不依赖
        viewModelScope.launch {
            zoneEntries = zoneCache?.load().orEmpty()
            seedZonesFromCache()
        }
        // 面板高度与筛选先读回来：首帧就用上用户上次的值，否则进页面会先按默认值画一帧
        // 再跳一下（用户明确说过面板不要跳）
        viewModelScope.launch {
            prefs.ebikePanelHeightDp.first()?.let(::setPanelHeight)
        }
        viewModelScope.launch {
            setOnlyAvailable(prefs.ebikeMapOnlyAvailable.first())
        }
        viewModelScope.launch {
            setOnlyOurCampus(prefs.ebikeMapOnlyOurCampus.first())
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
     * 地图**停手**了（View 层判定：事件安静 + `scroller.isFinished`，见 `SettleWatcher`）。
     *
     * 拖动 / 惯性滑动期间不会走到这里，所以不需要在这里再等一个防抖窗口；[QUERY_CONFIRM_MS]
     * 只用来合并"停手信号 + 紧随其后的一两个收尾事件"。
     *
     * 程序性移动（定位、回到校区、点分组联动）的事件被镜头静默区吃掉，走不到这里，
     * 那些路径各自安排查询（[onLocated] / [onResetToCampus] / [onClusterTap]）。
     */
    fun onMapSettled(lat: Double, lng: Double, zoom: Double) {
        if (!lat.isFinite() || !lng.isFinite()) return
        if (zoom.isFinite() && zoom > 0) pendingZoom = zoom
        pendingLat = lat
        pendingLng = lng
        scheduleQuery(immediate = false)
    }

    /**
     * 刷新按钮：跳过位移判断，问就是重查。**图层也强制重拉**（缓存只替用户动作省请求，
     * 「刷新」就是用户要看最新的意思）。
     */
    fun refresh() {
        scheduleQuery(immediate = true, forceZones = true)
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

    /**
     * 把面板高度收进 [minDp]..[maxDp]（页面在窗口尺寸变化后调用）。
     *
     * 渲染时虽然也夹了一道，但状态本身不会跟着变，于是会出现"拖了不动"：存着 560dp 的
     * 人转成横屏（上限只剩 280dp），面板画在 280dp，而拖动是从 560 开始算的，
     * 手指得先走完那 280dp 才见效。让状态自己收敛就没这回事。
     *
     * 已经在范围内时原样返回同一个实例：StateFlow 按相等去重，不会多触发一次重组。
     */
    fun clampPanelHeight(minDp: Float, maxDp: Float) {
        if (!minDp.isFinite() || !maxDp.isFinite() || maxDp < minDp) return
        _uiState.update { current ->
            val clamped = current.panelHeightDp.coerceIn(minDp, maxDp)
            if (clamped == current.panelHeightDp) current else current.copy(panelHeightDp = clamped)
        }
    }

    /**
     * 只看可用的车。不重新请求接口——数据已经在手上，重算一遍簇即可。
     *
     * 进页时也会拿它同步 DataStore 存的值（读回来的就是存的，写回等于空操作），
     * 这样 UI 只需要一条口径，不必区分「用户点的」与「进页恢复的」。
     */
    fun setOnlyAvailable(value: Boolean) {
        if (_uiState.value.onlyAvailable == value) return
        _uiState.update { it.copy(onlyAvailable = value) }
        rebuildClusters()
        viewModelScope.launch { prefs.setEbikeMapOnlyAvailable(value) }
    }

    /**
     * 只看本校的车。与 [setOnlyAvailable] 同一套口径：数据已在手上，重算簇即可，
     * 不重新请求接口；进页时也会拿 DataStore 存的值同步一遍。
     */
    fun setOnlyOurCampus(value: Boolean) {
        if (_uiState.value.onlyOurCampus == value) return
        _uiState.update { it.copy(onlyOurCampus = value) }
        rebuildClusters()
        // 图层同吃这一份筛选（还车点按围栏过滤，见 KvcxZones.campusOnly）
        applyZones()
        viewModelScope.launch { prefs.setEbikeMapOnlyOurCampus(value) }
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

    /**
     * 把镜头移到某个点（点「当前用车」标记 / 卡片上的「定位到车」）。
     * 程序性移动，静默窗口会吃掉随之而来的滚动回调，因此**不触发重查**——
     * 那批车已经取回来了，重查只会让列表当场换一批内容。
     */
    fun onFocusPoint(lat: Double, lng: Double) {
        if (!lat.isFinite() || !lng.isFinite()) return
        pushCamera(lat, lng, zoom = null, animated = true)
    }

    /** 找一辆车（按完整车号）并展开所在簇、把镜头移过去；找不到就提示等下一笔查询。 */
    fun focusCar(carNum: String) {
        if (!carNum.isNotBlank()) return
        _uiState.update { it.copy(highlightCarNum = carNum) }
        rebuildClusters()
        val cluster = _uiState.value.clusters.firstOrNull { c -> c.bikes.any { it.carNum == carNum } }
        if (cluster == null) {
            // 当前列表里没有：可能是筛掉了、车被骑走了或还没查到这一片。
            // 号先记着（下一笔查询找到就亮），提示给条出路而不是干等
            _events.trySend(
                BikeMapEvent.Notice("附近列表里暂时没有这辆车；移动地图或点刷新后再试", NoticeTone.Info),
            )
            return
        }
        _uiState.update {
            it.copy(
                expandedKey = cluster.key,
                focusKey = cluster.key,
                focusNonce = it.focusNonce + 1,
            )
        }
        pushCamera(cluster.lat, cluster.lng, zoom = null, animated = true)
    }

    /**
     * 导航带入的待定位车号（出码页识别条「地图查看」经 focusItemId 传进来）：
     * 第一笔查询结果里找到就自动定位（[focusCar]），找不到就安静等后面的查询。
     */
    fun setPendingFocusCar(carNum: String) {
        pendingFocusCar = carNum
        tryResolvePendingFocusCar()
    }

    private fun tryResolvePendingFocusCar() {
        val num = pendingFocusCar ?: return
        val found = _uiState.value.clusters.any { c -> c.bikes.any { it.carNum == num } }
        if (found) {
            pendingFocusCar = null
            focusCar(num)
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
     * 连续定位的最新读数（坐标已是 GCJ-02，DESIGN §3.9「蓝点实时更新」）：
     * **只挪蓝点**。
     *
     * **不重设列表的参照点**（2026-09-30）：蓝点每动一下就把整张列表按新位置重排，
     * 用户站着不动也会看到顺序在变。参照点只在查询落地时定一次（见 [BikeMapUiState.anchorLat]）。
     *
     * 与 [onLocated] 的分工：镜头与重查仍归一次性定位（进页 / 点「定位」按钮）管。
     * 这里不移镜头——用户刚拖好的视野不能被走动的自己抢回去；也不重查接口——
     * 车辆列表是用户动作驱动（DESIGN §3.9 禁止轮询第三方接口），下次拖动 / 点刷新
     * 自然用上最新的参照点。
     */
    fun onUserLocationChanged(lat: Double, lng: Double) {
        if (!lat.isFinite() || !lng.isFinite()) return
        locatedOnce = true
        _uiState.update { it.copy(userLat = lat, userLng = lng) }
        rebuildClusters()
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
        val originLat = state.anchorLat ?: fetchedLat
        val originLng = state.anchorLng ?: fetchedLng
        val anchored = if (originLat != null && originLng != null) {
            BikeNearby.reanchor(fetched, originLat, originLng)
        } else {
            fetched
        }
        val shown = anchored
            // 撒点采样会把上限外的车也捞回来，那些不算"附近"
            .filter { it.distanceMeters <= BikeNearby.MAX_NEARBY_DISTANCE_METERS }
            // 只看本校：车队归属 + 围栏双条件（师大校园的车在这里被挡掉）
            .let { list ->
                if (state.onlyOurCampus) {
                    list.filter { BikeNearby.isOurCampusBike(it.lat, it.lng, it.campusName) }
                } else {
                    list
                }
            }
            .let { list -> if (state.onlyAvailable) list.filter { it.available } else list }
        val clusters = BikeNearby.cluster(shown)
        _uiState.update { current ->
            // 高亮车按号在最新结果里重找：找得到才画（车被骑走 / 被筛掉时高亮自然消失）
            val highlighted = current.highlightCarNum?.let { num ->
                clusters.asSequence().flatMap { it.bikes }.firstOrNull { it.carNum == num }
            }
            current.copy(
                clusters = clusters,
                // 列表换了一批，展开态只在那个停车点还在时保留
                expandedKey = current.expandedKey?.takeIf { key -> clusters.any { it.key == key } },
                highlightedCar = highlighted,
            )
        }
    }

    /**
     * [forceZones] = 图层不吃新鲜缓存（只有「刷新」按钮用；「回到校区」是换视野，不是要最新）。
     *
     * [immediate] = 跳过等待与 30 米位移闸门（进页 / 定位成功 / 回到校区 / 刷新按钮）。
     * 拖动走的那条（[onMapSettled]）已经由 View 层确认"地图停了"，这里只留
     * [QUERY_CONFIRM_MS] 合并收尾事件，再按 30 米闸门判断要不要真发。
     */
    private fun scheduleQuery(immediate: Boolean, forceZones: Boolean = false) {
        debounceJob?.cancel()
        debounceJob = viewModelScope.launch {
            if (!immediate) delay(QUERY_CONFIRM_MS)
            val lat = pendingLat
            val lng = pendingLng
            if (!immediate && !movedEnough(lat, lng)) return@launch
            startQuery(lat, lng, forceZones)
        }
    }

    /**
     * 起一次查询。
     *
     * 只取消上一次**查询**，不碰防抖 Job：镜头移动期间地图每一帧都会走
     * `onMapSettled`，两者共用一个 Job 的话，刚发出去的请求会被下一帧掐掉，
     * 页面就一直转圈。
     */
    private fun startQuery(lat: Double, lng: Double, forceZones: Boolean) {
        queryJob?.cancel()
        queryJob = viewModelScope.launch { query(lat, lng, forceZones) }
    }

    /** 与上次**尝试过**的中心点比，位移是否够大。从没查过一律算够。 */
    private fun movedEnough(lat: Double, lng: Double): Boolean {
        val lastLat = lastAttemptLat ?: return true
        val lastLng = lastAttemptLng ?: return true
        return BikeNearby.distanceMeters(lastLat, lastLng, lat, lng) >=
            BikeNearby.MIN_REQUERY_SHIFT_METERS
    }

    private suspend fun query(lat: Double, lng: Double, forceZones: Boolean) {
        _uiState.update { it.copy(loading = true, failure = null) }
        // 图层只服务账号方式（2026-10-01）：`queryZones` 要 token，而小程序方式承诺
        // 「只出码与计时、不打扰第三方接口」——本机若还留着可静默重登的凭证，旧写法会
        // 在这条路上偷偷登一次快趣账号，同一个使用方式在两台手机上表现还不一样。
        // 缓存那一层不受影响：它是本地数据（见 [fetchZones]）。
        val inAppRide = accountMode()
        // 图层与车辆列表**并行**发（2026-09-28）：它只需要坐标与一个"哪类车"的上下文，
        // 车号用上一次那批车里的任意一辆即可——旧写法等中心结果回来才发，白多一个往返
        fetchZones(lat, lng, forceZones, inAppRide)

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

        // 中心点先落列表，别等撒点跑完：九次请求里最慢的那个不该压在用户眼前。
        // 撒点还在飞时不摘 loading——头部那枚进度圈就是「还在补全周围」的提示
        val sampling = centerBikes.size >= SAMPLE_PAGE_SIZE
        // 中心结果落地即结束"刷新中"：拖动换地方时，用户等的就是这一批（撒点只是补全）
        applyResult(lat, lng, centerBikes, stillLoading = false)
        // 记视野。写完这一次就够了，不需要在退出时再写一遍。
        // 不占关键路径：DataStore 写入是磁盘事务，压在两批请求之间只是白等它
        val zoomForViewport = pendingZoom
        viewModelScope.launch { prefs.setEbikeMapViewport(lat, lng, zoomForViewport) }
        // 没有车当上下文时（本次会话第一次查询）在这里补发一次图层
        flushPendingZone(inAppRide)
        // 换校区了（比如从本校拖到隔壁师大）：图层要用这一带的车号重拉，官方同口径
        reloadZonesForSite(lat, lng, centerBikes, inAppRide)

        // 中心点没返回满，说明这一带能查到的就这么多，不用再撒点浪费请求
        if (!sampling) return
        _uiState.update { it.copy(completing = true) }
        // 采样结果合并后再刷一遍：多出来的远车按距离插进列表，簇与展开态照旧重算
        applyResult(lat, lng, mergeSamples(lat, lng, centerBikes), stillLoading = false)
        _uiState.update { it.copy(completing = false) }
    }

    /**
     * 拉还车点 / 禁停区图层。[fetched] 里挑第一辆车当 `carNum` 上下文——没有车就不发请求
     * （接口要车号，编一个出来没有意义）。失败静默、保留上一层。
     *
     * 2026-09-28 起**先吃落盘缓存**（用户口径：停车点缓存）：命中覆盖当前中心的缓存就
     * **立即**摆上（不等网络，"P" 与车辆列表同时出现），新鲜的话连请求都不发——图层是
     * 最不容易变的一层，而旧写法拖动一下就要等一个往返才有 "P"，正是用户报的慢。
     * 判定全在 [ZoneCache]（纯逻辑、有单测），这里只管编排。
     *
     * [inAppRide] = 账号登录方式。小程序方式**只吃缓存、不发请求**（见 [query] 里的说明）：
     * 缓存是本地数据，摆旧图层比什么都不摆好；联网那条要 token，那一档不该有。
     */
    private fun fetchZones(lat: Double, lng: Double, force: Boolean, inAppRide: Boolean) {
        val source = zoneSource ?: return
        val needFetch = applyCachedZones(lat, lng, force)
        if (!inAppRide) return
        // 这一带有车就用这一带的车号（官方同口径）；没车就用上一次的上下文——
        // 接口只要"哪个校区"，没车不等于没还车点（见 ZoneCarContext 的注释）
        val context = zoneContextOf(fetched) ?: lastZoneCar
        if (context == null) {
            // 本次会话还没拿到过任何车：先记下这次的中心，等中心结果回来再发
            if (needFetch) pendingZone = ZonePending(lat, lng, force)
            return
        }
        if (needFetch) launchZoneRequest(source, lat, lng, context)
    }

    /**
     * 这一带的图层上下文该用哪辆车。
     *
     * **「只看本校」开着时优先本校车**（2026-09-29）：图层是按车号所属校区出的，若这一带
     * 最近的是隔壁师大（或别的校区）的车，拿它当上下文就会把图层换成那个校区的还车点——
     * 再经「只看本校」的围栏过滤，本片的「P」会**整片消失**（用户 2026-09-29 报的
     * 「加载出来又突然消失」就是这个）。开着只看本校时固定用本校车，图层就始终是本校校区的，
     * 围栏只削掉边缘那一两个点，不会整片空掉。
     *
     * 关着时用这一带最近的那辆（官方口径：`carNum: list[0].name`），图层跟着所看区域走。
     */
    private fun zoneContextOf(bikes: List<NearbyBike>): ZoneCarContext? {
        val car = if (_uiState.value.onlyOurCampus) {
            bikes.firstOrNull { BikeNearby.isOurCampus(it.campusName) }
        } else {
            bikes.firstOrNull()
        }
        return car?.let { ZoneCarContext(it.carNum, it.campusName) }
    }

    /**
     * 缓存命中就立刻把图层摆进状态；返回 true = 还要联网。
     *
     * 缓存里有覆盖当前中心的条目、但已过期时不返回 true 也不行——过期就该刷新，
     * 只是刷新期间用户看的是旧图层而不是空白（[fetchZones] 调用点不阻塞它）。
     * [force]（用户点了「刷新」）直接要求联网。
     */
    private fun applyCachedZones(lat: Double, lng: Double, force: Boolean): Boolean {
        val cached = ZoneCache.bestCovering(zoneEntries, lat, lng) ?: return true
        // 在飞的旧请求（更早的中心）回来时不该盖掉刚摆上的缓存：序号往前推一格让它作废，
        // 顺手取消——那发的数据已经用不上了，别白占一次往返
        zoneNonce += 1
        zoneJob?.cancel()
        fetchedZones = cached.zones
        applyZones()
        if (force) return true
        return !ZoneCache.isFresh(cached, System.currentTimeMillis())
    }

    /** 缓存读回来 / 补发之前，把覆盖当前中心的图层先摆上（网上还没结果时才摆）。 */
    private fun seedZonesFromCache() {
        // 网络结果（哪怕是空图层）已经是"当前的说法"，别拿旧缓存把它盖回去
        if (zonesFromNetwork || fetchedZones != KvcxZones.EMPTY) return
        val best = ZoneCache.bestCovering(zoneEntries, pendingLat, pendingLng) ?: return
        fetchedZones = best.zones
        applyZones()
    }

    /** 中心结果落地后补发那次被挂起的图层请求（[pendingZone]）。 */
    private fun flushPendingZone(inAppRide: Boolean) {
        val source = zoneSource ?: return
        if (!inAppRide) return
        val pending = pendingZone ?: return
        pendingZone = null
        val context = zoneContextOf(fetched) ?: lastZoneCar ?: return
        // 补发前再吃一次缓存：等中心结果这段时间里，缓存可能刚读回来（进页那次）
        if (applyCachedZones(pending.lat, pending.lng, pending.force)) {
            launchZoneRequest(source, pending.lat, pending.lng, context)
        }
    }

    /**
     * 新一带的图层上下文与当前那层**不同校区**时，用它重拉一遍（官方同口径）。
     *
     * 只在换校区时多发一次：同校区拖动时 [lastZoneCar] 的校区与它一致，直接返回。
     * 缓存命中（没联网）时也走这里——缓存条目不记校区，跨校区命中旧图层时靠这一步纠正。
     */
    private fun reloadZonesForSite(
        lat: Double,
        lng: Double,
        bikes: List<NearbyBike>,
        inAppRide: Boolean,
    ) {
        val source = zoneSource ?: return
        if (!inAppRide) return
        val context = zoneContextOf(bikes) ?: return
        if (context.campusName == lastZoneCar?.campusName) return
        launchZoneRequest(source, lat, lng, context)
    }

    private fun launchZoneRequest(
        source: KvcxZoneSource,
        lat: Double,
        lng: Double,
        context: ZoneCarContext,
    ) {
        val nonce = ++zoneNonce
        lastZoneCar = context
        zoneJob?.cancel()
        zoneJob = viewModelScope.launch {
            // 取消要原样抛出去（与 `sampleAt` 同一口径）：吞掉它会让取消变成一次"图层失败"
            val zones = try {
                source.queryZones(lat, lng, context.carNum)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                null
            } ?: return@launch
            // 旧响应作废：拖动期间可能有更新的请求已经发出（各自并发，回来的顺序不保证）
            if (nonce != zoneNonce) return@launch
            // **空图层不覆盖上一层**（2026-09-29 用户报「加载出来又突然消失」）：解析失败
            // （非 JSON / 结构不符）与业务错误码都会回空，拿它盖掉整片「P」是最糟的表现
            // ——「失败静默保留上一层」的同一口径。真的没有还车点的校区也走这条：旧图层画在
            // 它自己的坐标上，视野移过去自然看不见，不比整片空白差
            if (zones.isEmpty) return@launch
            zonesFromNetwork = true
            fetchedZones = zones
            applyZones()
            // 成功结果写回缓存（只存非空，上面已挡掉空表）
            zoneEntries = ZoneCache.put(
                zoneEntries,
                ZoneCacheEntry(lat, lng, System.currentTimeMillis(), zones),
            )
            zoneCache?.save(zoneEntries)
        }
    }

    /** 按当前筛选口径把 [fetchedZones] 落进状态（开关变化 / 新数据到达时都走这里）。 */
    private fun applyZones() {
        val zones = if (_uiState.value.onlyOurCampus) {
            fetchedZones.campusOnly(BikeNearby::inCampusFence)
        } else {
            fetchedZones
        }
        _uiState.update { it.copy(zones = zones) }
    }

    /**
     * 把一批车落成当前结果：状态、聚类、锚点记账都走这一条路。
     *
     * [stillLoading] = true 用于撒点在飞的中间态：列表已经可用，头部留着进度圈。
     */
    private fun applyResult(
        lat: Double,
        lng: Double,
        bikes: List<NearbyBike>,
        stillLoading: Boolean,
    ) {
        fetched = bikes
        fetchedLat = lat
        fetchedLng = lng
        lastAttemptLat = lat
        lastAttemptLng = lng
        // 参照点只在**查询落地时**定一次：拖动过程中不翻，列表顺序才稳（2026-09-30 用户反馈）
        val current = _uiState.value
        val userLat = current.userLat
        val userLng = current.userLng
        val fromUser = userLat != null && userLng != null &&
            BikeNearby.distanceMeters(userLat, userLng, lat, lng) <= USER_ANCHOR_RADIUS_METERS
        _uiState.update {
            it.copy(
                loading = stillLoading,
                queried = true,
                failure = null,
                updatedAtMillis = System.currentTimeMillis(),
                anchorLat = if (fromUser) userLat else lat,
                anchorLng = if (fromUser) userLng else lng,
                anchorFromUser = fromUser,
            )
        }
        rebuildClusters()
        // 出码页带车号跳进来的「地图查看」：结果到了就自动定位
        tryResolvePendingFocusCar()
    }

    private fun failQuery(lat: Double, lng: Double, failure: BikeFailure) {
        lastAttemptLat = lat
        lastAttemptLng = lng
        // 中心结果都没拿到：撒点也不会发，把"补全中"一并收掉（否则副标题会挂着）
        _uiState.update { it.copy(loading = false, queried = true, failure = failure, completing = false) }
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
        /**
         * 拖动停手后到发请求之间的一小段合并窗口（毫秒）。
         *
         * View 层（`SettleWatcher`）已经确认"地图停了"才调 [onMapSettled]，所以这里**不再做
         * 停手判定**，只用来合并"停手信号 + 紧随其后的收尾事件"：osmdroid 在滑动结束前后会补
         * 一两个滚动回调，没有这个窗口就会多发一次请求。官方小程序在 drag-end 上是零延迟发，
         * 我们这 80ms 就是那点差距。
         */
        private const val QUERY_CONFIRM_MS = 80L

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
            // 快趣会话按 context 现取（Graph 单例）；不可用时用户只是看不到本机用车入口。
            // 图层缓存同样包在 runCatching 里：它只是"省一个往返"的优化，取不到就不缓存，
            // 不该把 VM 的创建整个拖崩（旁边那条 kqcx 也是这个口径）
            BikeMapViewModel(
                client,
                prefs,
                runCatching { Graph.kqcx(Graph.appContext) }.getOrNull(),
                runCatching { Graph.kqcx(Graph.appContext) }.getOrNull(),
                runCatching { Graph.zoneCacheStore(Graph.appContext) }.getOrNull(),
            ) as T
    }
}

/**
 * 底部车辆面板的高度档位（dp），页面与状态共用一套口径。
 *
 * 存**定值**而不是屏幕比例：比例在窗口变化时会自己变，面板跟着跳，用户明确要求过别跳。
 * [PANEL_MAX_RATIO] 是第二道上限，窗口再矮也要给地图留三成，否则拖到顶只剩一条缝。
 *
 * **2026-10-01 起面板只装列表**：把手 + 头行 + 列表。主动作条与免责那行搬到面板**之外**
 * （页面底部的常驻块，见 `RideScreen`）。旧版把动作条塞进这个定高面板的 footer 里，
 * 骑行态的仪表盘一长，面板最矮时的把手 + 仪表盘 + 免责就已经超过面板高度，
 * 底部按钮被面板的圆角裁掉——高度是定值，内容却是变量，这个组合迟早出事。
 *
 * 下限 240 = 把手 22 + 头行约 48 + 列表约 170。默认值从 380 收到 270：动作条搬出去之后
 * 底部的总高度是「面板 + 常驻块」，不把默认值收回来，地图会比改动前少一块。
 * 这只是默认值，用户拖过的档位（[panelHeightDp]）照旧保留，只受上限约束。
 */
internal const val DEFAULT_PANEL_HEIGHT_DP = 270f
internal const val MIN_PANEL_HEIGHT_DP = 240f
internal const val MAX_PANEL_HEIGHT_DP = 620f

/**
 * 「面板 + 页面底部常驻块」合计占窗口高度的上限（用户可拖的那部分是面板）。
 * 留出的三成给地图——把动作条搬到面板外之后，比例要按**整条底部**算，否则地图还是会被挤。
 */
internal const val PANEL_MAX_RATIO = 0.72f

/**
 * 列表用「距你」而不是「距查询中心」排的半径（米，2026-09-30）。
 *
 * 查询中心离用户在这个半径内 = 用户在看自己周围，距离按用户位置算（能直接决定走哪辆）；
 * 超出 = 用户把地图拖到别处了，按查询中心算——顺序才和地图对得上，也不会因为蓝点在动
 * 而反复重排。
 */
internal const val USER_ANCHOR_RADIUS_METERS = 150.0
