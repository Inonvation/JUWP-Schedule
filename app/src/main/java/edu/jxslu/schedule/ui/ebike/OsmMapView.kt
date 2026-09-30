package edu.jxslu.schedule.ui.ebike

import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import edu.jxslu.schedule.BuildConfig
import edu.jxslu.schedule.domain.BikeCluster
import edu.jxslu.schedule.domain.BikeNearby
import edu.jxslu.schedule.domain.GcjPoint
import edu.jxslu.schedule.domain.KvcxZones
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import java.io.File

/**
 * 高德栅格瓦片（DESIGN §4.23）：512 像素档声明，主中国大陆路网底图（带注记），
 * 坐标基准 GCJ-02，与运营方给的车辆坐标同一基准，因此**不用做任何坐标转换**。
 *
 * **不要加 `scl=2`**（2026-09-27 试过又撤）：带了它服务器回的是"无注记纯底图"——
 * 路网、绿地块全有，**地名一个都没有**，真机上用户第一眼就报"名称没了"。
 * 不带它回的是 256px 带注记图，按 512 声明渲染会被拉大 2 倍、略有发虚——这是当前
 * 的取舍：注记优先，发虚先接受；要又高清又有注记，栅格这条路没有（得换矢量/付费）。
 * 源名保持 `AmapRoadHD`：osmdroid 瓦片缓存按源名分键，名字不变则此前缓存的 256px
 * 带注记图还能继续用，回退后不用重新下载。
 *
 * 地址是高德的非公开栅格接口：不接官方 SDK、不申请 key。属于灰色用法，页面免责声明
 * 已写明；地址失效时地图白板，底部车辆列表照常可用（降级路径见 `RideScreen`）。
 */
private val AMAP_TILE_SOURCE: OnlineTileSourceBase = object : OnlineTileSourceBase(
    "AmapRoadHD",
    /* aZoomMinLevel = */ 1,
    /* aZoomMaxLevel = */ 19,
    /* aTileSizePixels = */ 512,
    /* aImageFilenameEnding = */ "",
    arrayOf(
        "https://wprd01.is.autonavi.com/",
        "https://wprd02.is.autonavi.com/",
        "https://wprd03.is.autonavi.com/",
        "https://wprd04.is.autonavi.com/",
    ),
) {
    override fun getTileURLString(pMapTileIndex: Long): String =
        baseUrl + "appmaptile?x=" + MapTileIndex.getX(pMapTileIndex) +
            "&y=" + MapTileIndex.getY(pMapTileIndex) +
            "&z=" + MapTileIndex.getZoom(pMapTileIndex) +
            "&lang=zh_cn&size=1&style=7"
}

/** osmdroid 自己的偏好文件（只有瓦片缓存路径这类项，与 App 的 DataStore 无关）。 */
private const val OSMDROID_PREFS = "osmdroid"

private var osmdroidConfigured = false

/**
 * 全局初始化（DESIGN §4.23 的四个坑）。进程内只做一次。
 *
 * 1. 缓存路径必须指到应用私有目录：默认值指向外部存储，Android 10 起那套缓存在
 *    不少机型上静默失效（瓦片每次重下）。
 * 2. 路径要在 `load()` **前后各设一次**：`load()` 会把当时的 basePath 写进偏好，
 *    也会把偏好里的旧值读回来覆盖内存值。夹着设两遍，无论偏好里是什么都落在私有目录。
 * 3. `userAgentValue` 不设成默认值时部分瓦片服务会回 403。
 * 4. 瓦片过期时间必须自己设：高德响应带 `Cache-Control: max-age=3600`，但 osmdroid
 *    6.1.18 不解析任何缓存头（拆包确认：expires / cache-control 字符串为 0），默认
 *    不过期——旧图不会随时间换新，只等缓存超容量被清。设 7 天覆盖一次，高德改了
 *    路网/校名之类，最迟一周内能跟上。
 * 5. 缓存**体积上限也要自己设**：`DefaultConfigurationProvider` 的默认值是上限
 *    600 MiB、回收目标 500 MiB（拆包确认，见 [TILE_CACHE_MAX_BYTES]），校园尺度
 *    用不到这么多，手机存储却被一直占着。缓存目录在 `cacheDir`，系统清理时机
 *    不可控，自己压到几十兆更实在。
 *
 * `internal` 而不是 private：`EbikeMapCache`（「地图缓存」卡的统计与清除）要在
 * 不打开地图的情况下也拿到同一份缓存目录，**别在别处另抄一份路径**。
 */
internal fun ensureOsmdroidConfiguration(context: Context) {
    if (osmdroidConfigured) return
    osmdroidConfigured = true
    val config = Configuration.getInstance()
    val base = File(context.cacheDir, "osmdroid")
    val tiles = File(base, "tiles")
    config.osmdroidBasePath = base
    config.osmdroidTileCache = tiles
    // 用平台 SharedPreferences 而不是 PreferenceManager：不想为一个可选项把
    // androidx.preference 拉进依赖表
    config.load(context, context.getSharedPreferences(OSMDROID_PREFS, Context.MODE_PRIVATE))
    config.osmdroidBasePath = base
    config.osmdroidTileCache = tiles
    config.userAgentValue = context.packageName
    // 瓦片下载线程：osmdroid 默认 2（`DefaultConfigurationProvider` 构造里 `bipush 2`，拆包确认），
    // 2026-09-27 抬到 4，2026-09-28 再抬到 8——把视图拖到没缓存过的地方时，一屏十几块瓦片
    // 是"地图看起来半天不出来"的主要来源（实测单块 ~0.15s，并行发一批就下完）；
    // 8 与 osmdroid 自己的文件系统线程默认值同档，对 CDN 也算客气。
    config.setTileDownloadThreads(8.toShort())
    config.expirationOverrideDuration = TILE_EXPIRATION_MS
    config.tileFileSystemCacheMaxBytes = TILE_CACHE_MAX_BYTES
    config.tileFileSystemCacheTrimBytes = TILE_CACHE_TRIM_BYTES
}

/** 瓦片过期时间：7 天（理由见 [ensureOsmdroidConfiguration] 第 4 条）。 */
private const val TILE_EXPIRATION_MS = 7L * 24 * 60 * 60 * 1000

/**
 * 瓦片缓存上限与回收目标（字节）。超过上限时 osmdroid 回收，收到目标以下。
 *
 * 60 / 50 MiB：单张瓦片几十 KB，几十兆够放几千张，校区周边来回逛也刷不满；
 * 上限与目标留 10 MiB 的差，避免刚回收完又立刻触发一次。
 */
private const val TILE_CACHE_MAX_BYTES = 60L * 1024 * 1024
private const val TILE_CACHE_TRIM_BYTES = 50L * 1024 * 1024

/**
 * 叠加层要画的那几样输入（2026-10-01）。
 *
 * **为什么要有这一份**：`AndroidView` 的 `update` 块**每次外层重组都会跑一遍**（update 的
 * lambda 每轮都是新实例，组合器跳不过去），而它的收尾是 `view.invalidate()` —— 一次整幅
 * 地图重绘（瓦片 + 校园围栏 + 车标 + 还车点）。也就是说，**页面上任何与地图无关的状态变化**
 * （开确认弹窗、按钮 busy、Snackbar、偏好变化…）都会顺带把地图重画一遍，正好撞在同一两帧上，
 * 表现就是"卡一下"（2026-10-01 用户报「点确认开锁后卡」时定位到这条）。
 *
 * 所以 update 里先比对这一份，**只有画的东西真的变了才 invalidate**；回调照旧每轮都写
 * （它们是闭包，换了不需要重画）。
 */
private data class MapOverlayInputs(
    val colors: BikeMarkerColors,
    val clusters: List<BikeCluster>,
    val selectedKey: String?,
    val userPoint: GcjPoint?,
    val ridePoint: GcjPoint?,
    val highlightPoint: GcjPoint?,
    val highlightLabel: String?,
    val zones: KvcxZones,
)

/**
 * `update` 块里记「上一次真正画上去的输入」。**别换成 `mutableStateOf`**：update 跑在应用的
 * apply 阶段，往里写状态会再触发一轮重组；这里只是记账，不进组合、不驱动任何东西。
 *
 * 生命周期与 [OsmMapView] 里的 `mapView` / `overlay` 一致（同一个 `remember` 作用域），
 * 所以地图视图被重建时这一份也一起重置，不会出现"新视图什么都没画"。
 */
private class DrawnOverlayInputs(var value: MapOverlayInputs? = null)

/**
 * 附近单车地图（DESIGN §3.9）。
 *
 * osmdroid 是 View 体系的库，用 [AndroidView] 桥接；它的生命周期不认 Compose，
 * `onResume` / `onPause` / `onDetach` 要手动转发。
 *
 * 地图**不放在可滚动容器里**：拖动地图与滚动页面抢同一个竖直手势，套进 `Column` +
 * `verticalScroll` 只能靠拦截指针事件打补丁，那是把问题挪个地方。
 *
 * @param camera 待执行的镜头移动（定位 / 回到校区）；按 `nonce` 触发，连点同一个坐标也生效。
 * @param onCameraApplied 镜头移动执行完的回调；调用方据此把请求清掉，别让它被重放。
 * @param userLat / userLng 已取到的用户位置（GCJ-02），画成蓝点；null 不画。
 * @param rideLat / rideLng 当前用车的车位置（GCJ-02，与瓦片同基准），画成「我的车」标记；null 不画。
 * @param onRideTap 点中「我的车」标记（把镜头移过去）。
 * @param zones 还车点 / 禁停区图层（只读；空 = 不画）。「P」**不接点击**（只作信息展示）。
 */
@Composable
internal fun OsmMapView(
    clusters: List<BikeCluster>,
    selectedKey: String?,
    userLat: Double?,
    userLng: Double?,
    rideLat: Double?,
    rideLng: Double?,
    /** 识别高亮车的坐标（GCJ-02）；null = 没有要高亮的车。 */
    highlightLat: Double? = null,
    highlightLng: Double? = null,
    /** 高亮标签（如「车 …669」）；null 不画标签。 */
    highlightLabel: String? = null,
    colors: BikeMarkerColors,
    camera: CameraRequest?,
    /**
     * 地图**停手**后回调一次（中心 + 缩放）：用户拖完/滑停才通知，滑行途中一次都不发。
     *
     * 判据在 [SettleWatcher]（手指松开 + 地图中心连续 120ms 没动；**不是**事件间隔，也不是
     * `scroller.isFinished`——那两条都真机否掉过，理由写在那个类的注释里），不在调用方：
     * View 层才知道地图是不是还在滑。程序性移动（定位 / 回到校区 / 点分组联动）的事件被
     * [CameraSuppressor] 吃掉，走不到这里，那些路径各自安排查询。
     */
    onCenterSettled: (Double, Double, Double) -> Unit,
    zones: KvcxZones,
    onClusterTap: (String) -> Unit,
    onRideTap: () -> Unit,
    onCameraApplied: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // 回调每次重组刷新：地图监听器只建一次，闭包捕获旧 lambda 会读到过期状态
    val settleCallback by rememberUpdatedState(onCenterSettled)
    val tapCallback by rememberUpdatedState(onClusterTap)
    val rideTapCallback by rememberUpdatedState(onRideTap)
    val appliedCallback by rememberUpdatedState(onCameraApplied)

    // 程序性移动期间吃掉地图中心回调，见 CameraSuppressor。监听器只建一次，
    // 所以要用一个可变持有者，不能靠重组时新建的闭包
    val suppressor = remember { CameraSuppressor() }

    // 「停手」判定（见 SettleWatcher 的注释）：手指状态 + 轮询地图中心，滑行途中不发查询
    val touchTracker = remember { TouchTracker() }
    val settleScope = rememberCoroutineScope()
    val settleWatcher = remember { SettleWatcher(settleScope) { touchTracker.down } }

    // 地图视图的实际尺寸。osmdroid 的 setCenter/animateTo 按当前尺寸算滚动量，
    // 尺寸还是 0 的时候算出来的落点是错的——入口那次定位正好撞上这个窗口
    var mapSize by remember { mutableStateOf(IntSize.Zero) }

    val overlay = remember {
        BikeMarkerOverlay(context.resources.displayMetrics.density).apply {
            // 围栏顶点是常量（BikeNearby.CAMPUS_FENCE），进页面就有，不随状态变
            fence = BikeNearby.CAMPUS_FENCE
        }
    }
    // 上一次真正画上去的叠加层输入（见 MapOverlayInputs / DrawnOverlayInputs 的注释）
    val drawn = remember { DrawnOverlayInputs() }
    val mapView = remember {
        ensureOsmdroidConfiguration(context)
        MapView(context).apply {
            setTileSource(AMAP_TILE_SOURCE)
            // 512 像素瓦片按屏幕像素 1:1 画，别再按 dpi 缩放一遍
            setTilesScaledToDpi(false)
            setMultiTouchControls(true)
            setUseDataConnection(true)
            // 内置的 +/- 按钮在 512 瓦片下偏占地方；捏合缩放够用
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(BikeNearby.DEFAULT_ZOOM)
            controller.setCenter(GeoPoint(BikeNearby.DEFAULT_CENTER_LAT, BikeNearby.DEFAULT_CENTER_LNG))
            addMapListener(
                object : MapListener {
                    override fun onScroll(event: ScrollEvent?): Boolean {
                        val center = mapCenter ?: return false
                        if (suppressor.shouldSwallow(center.latitude, center.longitude)) {
                            return false
                        }
                        // 每次事件只喂给停手判定；查询由它确认"真的停了"之后再发
                        settleWatcher.onEvent(this@apply) { lat, lng, zoom ->
                            settleCallback(lat, lng, zoom)
                        }
                        return false
                    }

                    override fun onZoom(event: ZoomEvent?): Boolean {
                        val center = mapCenter ?: return false
                        if (suppressor.shouldSwallow(center.latitude, center.longitude)) {
                            return false
                        }
                        settleWatcher.onEvent(this@apply) { lat, lng, zoom ->
                            settleCallback(lat, lng, zoom)
                        }
                        return false
                    }
                },
            )
            overlays.add(overlay)
            // 只读的手指状态跟踪（不吃事件）：SettleWatcher 靠它区分「手指还按着」与「松手了」
            overlays.add(touchTracker)
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier.onSizeChanged { size -> mapSize = size },
        update = { view ->
            // 只重画"画的东西真变了"的那一次（见 MapOverlayInputs 的注释）：页面上任何与地图
            // 无关的重组都会跑到这里，无脑 invalidate 就会把地图整幅重绘一遍
            val inputs = MapOverlayInputs(
                colors = colors,
                clusters = clusters,
                selectedKey = selectedKey,
                userPoint = if (userLat != null && userLng != null) {
                    GcjPoint(userLat, userLng)
                } else {
                    null
                },
                ridePoint = if (rideLat != null && rideLng != null) {
                    GcjPoint(rideLat, rideLng)
                } else {
                    null
                },
                // 识别高亮（2026-09-29「车号识别联动」）：识别条定位过来的那辆车
                highlightPoint = if (highlightLat != null && highlightLng != null) {
                    GcjPoint(highlightLat, highlightLng)
                } else {
                    null
                },
                highlightLabel = highlightLabel,
                zones = zones,
            )
            if (drawn.value != inputs) {
                overlay.colors = inputs.colors
                overlay.clusters = inputs.clusters
                overlay.selectedKey = inputs.selectedKey
                overlay.userPoint = inputs.userPoint
                overlay.ridePoint = inputs.ridePoint
                overlay.highlightPoint = inputs.highlightPoint
                overlay.highlightLabel = inputs.highlightLabel
                overlay.zones = inputs.zones
                drawn.value = inputs
                view.invalidate()
            }
            // 回调不进上面那份比对：它们只是闭包，每轮重组换新实例很正常，换了不需要重画地图
            overlay.onClusterTap = tapCallback
            overlay.onRideTap = rideTapCallback
        },
    )

    DisposableEffect(mapView) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
    }

    LaunchedEffect(camera?.nonce, mapSize) {
        val request = camera ?: return@LaunchedEffect
        // 还没量到尺寸：这次先不执行，也**不消费请求**。等尺寸到位后 key 变化会再来一遍
        if (mapSize.width == 0 || mapSize.height == 0) return@LaunchedEffect
        val target = GeoPoint(request.lat, request.lng)
        val zoom = request.zoom
        suppressor.aim(request.lat, request.lng)
        // osmdroid 的 animateTo 是**非阻塞**的：启动动画就返回，帧要等后面几帧才跑。
        // 所以静默只能按时间窗开，不能"等动画结束再关"——那样第一帧就已经漏出去了
        suppressor.quietFor(if (request.animated) MOVE_QUIET_MS else RESTORE_QUIET_MS)
        when {
            // 恢复上次视野：直接落位，不该从校园中心慢慢滑过去
            !request.animated -> {
                if (zoom != null) mapView.controller.setZoom(zoom)
                mapView.controller.setCenter(target)
            }
            // 定位 / 点分组联动：保持用户当前缩放，别把他调好的视野拉回默认级别
            zoom == null -> mapView.controller.animateTo(target)
            else -> mapView.controller.animateTo(target, zoom, 400L)
        }
        // 瞄准点**不清**：移动结束不等于事件结束，osmdroid 之后还会补一次滚动回调，
        // 由瞄准点的 30 米半径兜住。清早了照样会多触发一次重查
        appliedCallback(request.nonce)
    }
}

/** 程序性移动的静默窗口：盖住动画本身（400 毫秒）与收尾的几帧。 */
private const val MOVE_QUIET_MS = 1_200L

/** 恢复视野没有动画，只要盖住 setCenter 之后补发的那一帧。 */
private const val RESTORE_QUIET_MS = 300L

/**
 * 程序性移动的「静默区」。
 *
 * 为什么需要：定位、回到校区、点分组联动都会让地图动，动了就一定触发滚动回调，
 * 回调又会走 ViewModel 的重查判定。点分组那条尤其不能重查：那批车已经取回来了，
 * 再问一次接口只会让用户刚展开的列表当场换一批内容。
 *
 * 两条规则：
 * 1. **时间窗**（[quietFor]）——窗口内一律吃掉。中间帧可能离瞄准点几百米，只看半径兜不住；
 * 2. **瞄准点 30 米半径**——窗口过后还剩一个收尾回调，它与 ViewModel 的重查阈值同半径，
 *    也就是说被它吃掉的回调本来也触发不了重查，行为没有旁路。用户一旦拖出 30 米，
 *    瞄准点立刻作废，之后的回调照常上报。
 *
 * 用时间而不是标志位：animateTo 不阻塞，协程里"动画之后"仍在第一帧之前，标志位来不及摆；
 * 时间窗还天然不会挂住，不存在"忘了清标志导致再也查不了"的故障模式。
 */
private class CameraSuppressor {
    private var quietUntil = 0L
    private var lat: Double? = null
    private var lng: Double? = null

    fun aim(lat: Double, lng: Double) {
        this.lat = lat
        this.lng = lng
    }

    /** 接下来这段时间内的中心回调一律吃掉。 */
    fun quietFor(millis: Long) {
        quietUntil = SystemClock.uptimeMillis() + millis
    }

    fun release() {
        lat = null
        lng = null
    }

    /** true = 这次回调要吃掉。 */
    fun shouldSwallow(lat: Double, lng: Double): Boolean {
        if (SystemClock.uptimeMillis() < quietUntil) return true
        val aimLat = this.lat ?: return false
        val aimLng = this.lng ?: return false
        if (BikeNearby.distanceMeters(aimLat, aimLng, lat, lng) < SUPPRESS_RADIUS_METERS) {
            return true
        }
        release()
        return false
    }

    private companion object {
        const val SUPPRESS_RADIUS_METERS = BikeNearby.MIN_REQUERY_SHIFT_METERS
    }
}

/**
 * 「地图停手」判定（2026-09-29）：**手指松开了** + **地图中心连续 [SETTLE_QUIET_MS] 没动**。
 *
 * 两条判据都不是随手挑的，各自补掉一个真机踩到的坑：
 * 1. **只看事件间隔不行**：惯性滑动（fling）期间地图渲染很重（瓦片 + 几十个标记），帧率可能
 *    低到 5~10fps，事件间隔超过防抖阈值，于是滑行途中被误判成「停手」——实测一次 swipe
 *    连发 7 次查询（每次 1 中心 + 8 撒点 + 1 图层），既费流量、又让列表在地图还在滑的时候
 *    反复重排（观感就是「卡」）。所以这里**直接轮询 `mapView.mapCenter`**：滑行时中心一直在变，
 *    与事件流密不密无关。
 * 2. **`scroller.isFinished` 不能用**（同日实测）：拖完之后它**一直是 false**（日志里 44 轮
 *    都没变过），拿它当硬闸门会永远等下去——一个查询都发不出去。
 * 3. **手指还按着不算停手**：慢拖时中心可能连着几百毫秒只挪几米，只看「没动」会在拖的过程中
 *    反复发查询；[TouchTracker] 给出「手指在不在屏幕上」，按住期间一律等。
 *
 * 用法：每次未被 [CameraSuppressor] 吃掉的滚动/缩放事件调一次 [onEvent]（它会取消上一次等待）。
 */
private class SettleWatcher(
    private val scope: CoroutineScope,
    /** 手指是否还按在地图上（[TouchTracker]）。 */
    private val fingerDown: () -> Boolean,
) {

    private var job: Job? = null

    fun onEvent(mapView: MapView, onSettled: (Double, Double, Double) -> Unit) {
        job?.cancel()
        job = scope.launch {
            var last = mapView.mapCenter ?: return@launch
            var rounds = 0
            while (true) {
                delay(SETTLE_QUIET_MS)
                rounds += 1
                val now = mapView.mapCenter ?: return@launch
                val moved = BikeNearby.distanceMeters(
                    last.latitude,
                    last.longitude,
                    now.latitude,
                    now.longitude,
                )
                if (fingerDown() || moved >= SETTLE_MOVE_METERS) {
                    // 还按着 / 还在动（惯性滑动也算）：把基准挪到当前位置，再等一轮
                    last = now
                    continue
                }
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "fire rounds=$rounds center=${now.latitude},${now.longitude}")
                }
                onSettled(now.latitude, now.longitude, mapView.zoomLevelDouble)
                return@launch
            }
        }
    }

    private companion object {
        /** 轮询间隔，也是「停住」的判定窗口。 */
        const val SETTLE_QUIET_MS = 120L

        /** 一个轮询周期内中心挪动超过这个距离就算「还在动」（米）。 */
        const val SETTLE_MOVE_METERS = 5.0

        const val TAG = "BikeMapSettle"
    }
}

/**
 * 手指状态跟踪：`ACTION_DOWN` 到 `ACTION_UP`/`CANCEL` 之间算「按着」。
 *
 * 只读不拦（[onTouchEvent] 返回 false），地图照常处理手势；存在的唯一理由是
 * [SettleWatcher] 需要知道「用户是不是还在拖」，而 osmdroid 的滚动事件给不出这个信息。
 */
private class TouchTracker : Overlay() {

    var down = false
        private set

    override fun onTouchEvent(event: MotionEvent, mapView: MapView): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> down = true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> down = false
        }
        return false
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) = Unit
}
