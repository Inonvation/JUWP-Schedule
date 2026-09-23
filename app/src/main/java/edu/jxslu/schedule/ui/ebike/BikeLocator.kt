package edu.jxslu.schedule.ui.ebike

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import edu.jxslu.schedule.domain.Gcj02
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull

/** 一次定位的结果。 */
internal sealed interface LocateResult {
    /** 成功。[lat] / [lng] **已经是 GCJ-02**，可直接当瓦片与接口的中心点。 */
    data class Ok(val lat: Double, val lng: Double) : LocateResult

    /** 失败，[message] 直接给 Snackbar 用。 */
    data class Failed(val message: String) : LocateResult
}

/**
 * 单车地图页的一次性定位（DESIGN §3.9）。放在 UI 层而不是 `data/`：
 * 它是「点按钮取一次坐标」的界面动作，没有状态、没有缓存、也不给别的页面用。
 *
 * 只用平台 [LocationManager]，不引 Google Play Services 的融合定位——那套在国内机型上
 * 本来也不可用。坐标转换在 [Gcj02]（纯 JVM 可测）。
 *
 * 权限由调用方（页面）负责申请：本类只在**已授权**的前提下工作，没有权限直接返回失败，
 * 不会中途弹系统框。
 */
internal object BikeLocator {

    /** 等一次有效定位的上限。超了就给失败提示，不要挂着转圈。 */
    private const val FIX_TIMEOUT_MS = 8_000L

    /** 缓存定位可接受的新鲜度：两分钟内的上次定位直接拿来用，省一次等待。 */
    private const val MAX_LAST_KNOWN_AGE_MS = 2 * 60_000L

    /** 精确或粗略任一授权即可（API 31+ 用户可能只给「大致位置」）。 */
    fun hasPermission(context: Context): Boolean =
        isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
            isGranted(context, Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * 取一次定位并转成 GCJ-02。
     *
     * 顺序：先看两分钟内的缓存定位（瞬时返回），没有再同时挂网络与 GPS 两条更新，
     * 谁先给结果用谁。网络定位先到也无所谓——把地图中心落到用户那一片，粗略坐标就够。
     */
    suspend fun currentLocation(context: Context): LocateResult {
        if (!hasPermission(context)) {
            return LocateResult.Failed("没有定位权限，请在系统设置里允许后再试")
        }
        val manager = context.getSystemService(LocationManager::class.java)
            ?: return LocateResult.Failed("这台设备没有定位服务")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !manager.isLocationEnabled) {
            return LocateResult.Failed("手机定位服务未开启，请打开后重试")
        }

        freshLastKnown(manager)?.let { return toResult(it) }

        val providers = enabledProviders(manager)
        val fix = awaitFix(manager, providers)
            ?: return LocateResult.Failed("暂时取不到位置，可手动拖动地图找车")
        return toResult(fix)
    }

    /** GPS 与网络定位都给上次结果，取最新的那条；只看两分钟以内的。 */
    private fun freshLastKnown(manager: LocationManager): Location? =
        ALL_PROVIDERS.mapNotNull { provider ->
            try {
                if (manager.isProviderEnabled(provider)) manager.getLastKnownLocation(provider) else null
            } catch (_: SecurityException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }
            .filter { System.currentTimeMillis() - it.time <= MAX_LAST_KNOWN_AGE_MS }
            .maxByOrNull { it.time }

    /** 网络定位排在前面：出结果快，粗略坐标就够把地图中心落过去。 */
    private fun enabledProviders(manager: LocationManager): List<String> =
        listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { provider ->
                try {
                    manager.isProviderEnabled(provider)
                } catch (_: IllegalArgumentException) {
                    false
                }
            }

    private suspend fun awaitFix(manager: LocationManager, providers: List<String>): Location? =
        withTimeoutOrNull(FIX_TIMEOUT_MS) {
            if (providers.isEmpty()) return@withTimeoutOrNull null
            locationUpdates(manager, providers).firstOrNull()
        }

    /**
     * 两个 provider 的更新汇成一条流，谁先来算谁。
     *
     * 四个回调方法**都要写全**：这些方法在 API 30 的 `android.jar` 里才是 default 方法，
     * 编译期不写也能过；但 26~29 的设备上框架会真的调用 `onStatusChanged` 等，
     * 类里没有实现就抛 `AbstractMethodError`。这类崩溃只在低版本机器上出现。
     */
    private fun locationUpdates(manager: LocationManager, providers: List<String>): Flow<Location> =
        callbackFlow {
            val registered = providers.map { provider ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        trySend(location)
                    }

                    @Deprecated("API 29 起废弃，但 26~29 的框架仍会调它")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

                    override fun onProviderEnabled(provider: String) = Unit

                    override fun onProviderDisabled(provider: String) = Unit
                }
                val ok = try {
                    manager.requestLocationUpdates(
                        provider,
                        0L,
                        0f,
                        listener,
                        Looper.getMainLooper(),
                    )
                    true
                } catch (_: SecurityException) {
                    false
                } catch (_: IllegalArgumentException) {
                    false
                }
                provider to (listener to ok)
            }

            if (registered.none { (_, value) -> value.second }) {
                // 一个都没挂上：别让调用方白等满超时
                close()
            }

            awaitClose {
                registered.forEach { (_, value) ->
                    try {
                        manager.removeUpdates(value.first)
                    } catch (_: Exception) {
                        // 注销失败没有补救手段，也不影响结果
                    }
                }
            }
        }

    private fun toResult(location: Location): LocateResult {
        val gcj = Gcj02.toGcj02(location.latitude, location.longitude)
        return LocateResult.Ok(gcj.lat, gcj.lng)
    }

    /** 缓存定位的三个来源，`PASSIVE` 是别的应用请求定位时顺带拿到的结果。 */
    private val ALL_PROVIDERS = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        LocationManager.PASSIVE_PROVIDER,
    )
}