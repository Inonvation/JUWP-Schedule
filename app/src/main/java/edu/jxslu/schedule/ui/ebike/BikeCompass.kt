package edu.jxslu.schedule.ui.ebike

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Surface
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.math.min

/**
 * 骑行页蓝点的罗盘朝向（2026-10-08，DESIGN §3.9「蓝点朝向」）。
 *
 * 与 [BikeLocator] 同一层取向：放在 UI 层的小动作，没有状态、不给别的页面用。
 * 语义是**罗盘朝向**——手机顶边指向的地理北偏角（0 = 北、90 = 东），站着不动也实时
 * 显示；不是 GPS 行进方向（那条链 2 秒一跳还要过 `shouldMoveDot` 的 ≥4 米挪点闸门，
 * 站定就没方向，本页「找车」场景下体验差）。
 *
 * 只用平台 [SensorManager]（不引 Play Services），无新权限——磁力计没有运行时权限。
 * 设备没有 `TYPE_ROTATION_VECTOR`（无磁力计的平板、部分模拟器）时 [heading] 返回空流，
 * 蓝点自然退回无方向圆点，调用方零降级分支。
 *
 * **频率纪律**：传感器原生速率远高于 UI 需要的，而 heading 的终点是
 * `MapOverlayInputs` 的值比对 + 一次整幅地图重绘（`OsmMapView.update`）——
 * 直接把每帧读数灌进状态就复刻了 2026-10-01「点确认开锁后卡」的整幅重绘 bug。
 * 所以流内先低通压手抖、再按「变化 > [EMIT_ANGLE_STEP]° 且距上次发射 ≥ [EMIT_INTERVAL_MS]」
 * 节流（约 ≤10Hz），正常不动时根本不发。
 */
internal object BikeCompass {

    /** 低通系数：0 = 完全不动，1 = 完全跟手。0.15 平衡「压抖」与「跟手」。 */
    internal const val SMOOTHING_FACTOR = 0.15f

    /** 角度变化超过这个度数才值得往 UI 发（静止时罗盘常漂 ±1~2°，别跟着漂）。 */
    internal const val EMIT_ANGLE_STEP = 2f

    /** 两次发射的最小间隔（毫秒）：给整幅重绘留出喘息，压到约 ≤10Hz。 */
    internal const val EMIT_INTERVAL_MS = 100L

    /**
     * 页面可见期间的罗盘朝向流（度，0–360，北 = 0 顺时针）。
     *
     * 订阅即注册 `TYPE_ROTATION_VECTOR` 监听（`SENSOR_DELAY_UI`），取消（页面退后台 /
     * 离开页面，收口在 `repeatOnLifecycle(STARTED)`）自动注销。无传感器发空流。
     */
    fun heading(context: Context): Flow<Float> = callbackFlow {
        val manager = context.getSystemService(SensorManager::class.java)
        val displayManager =
            context.getSystemService(DisplayManager::class.java)
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (manager == null || displayManager == null || sensor == null) {
            close()
            return@callbackFlow
        }
        val listener = object : SensorEventListener {
            private var smoothed: Float? = null
            private var lastEmitted: Float? = null
            private var lastEmitAt = 0L

            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
                // 屏幕旋转档取 DisplayManager（API 17+，minSdk 26 全覆盖）：
                // `SensorManager.getDisplay` 要 API 30，低版本直接 NoSuchMethodError
                val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: return
                val next = headingFromRotation(event.values, display.rotation) ?: return
                val value = smoothAngle(smoothed, next, SMOOTHING_FACTOR)
                smoothed = value
                val now = System.currentTimeMillis()
                if (shouldEmit(lastEmitted, value, now, lastEmitAt)) {
                    lastEmitted = value
                    lastEmitAt = now
                    trySend(value)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        awaitClose { manager.unregisterListener(listener) }
    }

    // ---------- 以下为纯函数，JVM 单测钉住（BikeCompassTest） ----------

    /**
     * 从 rotation vector 读数算屏幕顶边的北偏角（度，0–360）。
     *
     * 链路：`getRotationMatrixFromVector`（设备坐标系）→ `remapCoordinateSystem`
     * （重投影到**屏幕坐标系**——`SubpageActivity` 未锁竖屏，横屏时必须让方位角
     * 按屏幕顶边计，而不是设备 y 轴）→ `getOrientation` 取方位角。
     *
     * remap 的轴向映射是官方 `getOrientation` 文档的示例口径：
     * ROTATION_0 → (AXIS_X, AXIS_Y)、ROTATION_90 → (AXIS_Y, AXIS_MINUS_X)、
     * ROTATION_180 → (AXIS_MINUS_X, AXIS_MINUS_Y)、ROTATION_270 → (AXIS_MINUS_Y, AXIS_X)。
     *
     * 读数非法（矩阵退化 / NaN，如全零 rotation vector）返回 null，调用方直接丢帧——
     * 不丢的话 NaN 会顺着 `smoothed`/`lastEmitted` 污染状态机（`NaN > 2f` 恒 false），
     * 罗盘流从此整段哑掉。
     */
    internal fun headingFromRotation(rotationVector: FloatArray, displayRotation: Int): Float? {
        if (rotationVector.isEmpty()) return null
        val rotationMatrix = FloatArray(9)
        val remapped = FloatArray(9)
        val orientation = FloatArray(3)
        SensorManager.getRotationMatrixFromVector(rotationMatrix, rotationVector)
        val (axisX, axisY) = remapAxes(displayRotation)
        SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, remapped)
        SensorManager.getOrientation(remapped, orientation)
        return normalizeHeading(Math.toDegrees(orientation[0].toDouble()).toFloat())
    }

    /**
     * 方位角出口的唯一防线（可 JVM 测）：NaN / 无穷（矩阵退化，如全零 rotation vector）
     * 返回 null 丢帧——不丢的话 NaN 会顺着 `smoothed`/`lastEmitted` 污染状态机
     * （`NaN > 2f` 恒 false），罗盘流从此整段哑掉；有限值归一化到 [0, 360)。
     */
    internal fun normalizeHeading(radiansToDegrees: Float): Float? {
        if (!radiansToDegrees.isFinite()) return null
        return (radiansToDegrees + 360f) % 360f
    }

    /** 各屏幕旋转档对应的 remap 轴向；见 [headingFromRotation] 注释。 */
    internal fun remapAxes(displayRotation: Int): Pair<Int, Int> = when (displayRotation) {
        Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
        Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
        Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
        else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
    }

    /**
     * 低通滤波，角度按**环形**插值：359° 与 2° 之间走 3° 的短路，不绕 357° 的远路。
     * 首帧（previous 为 null）直接落位，不从 0° 慢慢爬。
     */
    internal fun smoothAngle(previous: Float?, next: Float, factor: Float): Float {
        val prev = previous ?: return normalize(next)
        var delta = next - prev
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        return normalize(prev + delta * factor)
    }

    /** 归一化到 [0, 360)。 */
    internal fun normalize(degrees: Float): Float = (degrees % 360f + 360f) % 360f

    /**
     * 节流判定：与上个**已发射**值的角度差（环形）超过 [EMIT_ANGLE_STEP]、
     * 且距上次发射 ≥ [EMIT_INTERVAL_MS] 才发。首个值（previous 为 null）立即发，
     * 蓝点别等两秒才长出扇形（间隔闸门只对后续帧生效）。
     */
    internal fun shouldEmit(
        previousEmitted: Float?,
        next: Float,
        now: Long,
        lastEmitAt: Long,
        angleStep: Float = EMIT_ANGLE_STEP,
        intervalMs: Long = EMIT_INTERVAL_MS,
    ): Boolean {
        if (previousEmitted == null) return true
        return emittedAngleDistance(previousEmitted, next) > angleStep &&
            now - lastEmitAt >= intervalMs
    }

    /** 两个方位角的最短环形距离（0–180）。 */
    internal fun emittedAngleDistance(a: Float, b: Float): Float {
        val diff = kotlin.math.abs(a - b)
        return min(diff, 360f - diff)
    }
}
