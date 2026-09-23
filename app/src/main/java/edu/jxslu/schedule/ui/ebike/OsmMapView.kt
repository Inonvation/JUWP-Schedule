package edu.jxslu.schedule.ui.ebike

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import edu.jxslu.schedule.domain.BikeCluster
import edu.jxslu.schedule.domain.BikeNearby
import edu.jxslu.schedule.domain.GcjPoint
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import java.io.File

/**
 * 高德栅格瓦片（DESIGN §4.23）：512 像素档，主中国大陆路网底图，坐标基准 GCJ-02，
 * 与运营方给的车辆坐标同一基准，因此**不用做任何坐标转换**。
 *
 * 地址是高德的非公开栅格接口：不接官方 SDK、不申请 key。属于灰色用法，页面免责声明
 * 已写明；地址失效时地图白板，底部车辆列表照常可用（降级路径见 BikeMapScreen）。
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
 * 全局初始化（DESIGN §4.23 的三个坑）。进程内只做一次。
 *
 * 1. 缓存路径必须指到应用私有目录：默认值指向外部存储，Android 10 起那套缓存在
 *    不少机型上静默失效（瓦片每次重下）。
 * 2. 路径要在 `load()` **前后各设一次**：`load()` 会把当时的 basePath 写进偏好，
 *    也会把偏好里的旧值读回来覆盖内存值。夹着设两遍，无论偏好里是什么都落在私有目录。
 * 3. `userAgentValue` 不设成默认值时部分瓦片服务会回 403。
 */
private fun ensureOsmdroidConfiguration(context: Context) {
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
    config.setTileDownloadThreads(4.toShort())
}

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
 */
@Composable
internal fun OsmMapView(
    clusters: List<BikeCluster>,
    selectedKey: String?,
    userLat: Double?,
    userLng: Double?,
    colors: BikeMarkerColors,
    camera: CameraRequest?,
    onCenterChanged: (Double, Double, Double) -> Unit,
    onClusterTap: (String) -> Unit,
    onCameraApplied: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // 回调每次重组刷新：地图监听器只建一次，闭包捕获旧 lambda 会读到过期状态
    val centerCallback by rememberUpdatedState(onCenterChanged)
    val tapCallback by rememberUpdatedState(onClusterTap)
    val appliedCallback by rememberUpdatedState(onCameraApplied)

    // 程序性移动期间吃掉地图中心回调，见 CameraSuppressor。监听器只建一次，
    // 所以要用一个可变持有者，不能靠重组时新建的闭包
    val suppressor = remember { CameraSuppressor() }

    // 地图视图的实际尺寸。osmdroid 的 setCenter/animateTo 按当前尺寸算滚动量，
    // 尺寸还是 0 的时候算出来的落点是错的——入口那次定位正好撞上这个窗口
    var mapSize by remember { mutableStateOf(IntSize.Zero) }

    val overlay = remember {
        BikeMarkerOverlay(context.resources.displayMetrics.density)
    }
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
                        // 缩放也会触发 onScroll；位移判断统一交给 ViewModel 的 30 米阈值
                        centerCallback(center.latitude, center.longitude, zoomLevelDouble)
                        return false
                    }

                    override fun onZoom(event: ZoomEvent?): Boolean {
                        val center = mapCenter ?: return false
                        if (suppressor.shouldSwallow(center.latitude, center.longitude)) {
                            return false
                        }
                        centerCallback(center.latitude, center.longitude, zoomLevelDouble)
                        return false
                    }
                },
            )
            overlays.add(overlay)
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier.onSizeChanged { size -> mapSize = size },
        update = { view ->
            overlay.colors = colors
            overlay.clusters = clusters
            overlay.selectedKey = selectedKey
            overlay.userPoint = if (userLat != null && userLng != null) {
                GcjPoint(userLat, userLng)
            } else {
                null
            }
            overlay.onClusterTap = tapCallback
            view.invalidate()
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
