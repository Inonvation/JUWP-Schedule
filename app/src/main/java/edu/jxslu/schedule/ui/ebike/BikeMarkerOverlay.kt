package edu.jxslu.schedule.ui.ebike

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import edu.jxslu.schedule.domain.BikeCluster
import edu.jxslu.schedule.domain.BikeStatus
import edu.jxslu.schedule.domain.GcjPoint
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.hypot

/** 标记配色。由 UI 层按当前主题传进来，本类不碰 Compose。 */
internal data class BikeMarkerColors(
    /** 圈里有一辆能骑的车。 */
    val available: Int,
    /** 圈里没有能骑的，但还有电。 */
    val lowBattery: Int,
    /** 圈里全是离线或被停用的车。 */
    val unavailable: Int,
    /** 圈内数字的颜色。 */
    val label: Int,
    /** 选中态的描边颜色。 */
    val selectedRing: Int,
    /** 用户位置圆点的实心色。 */
    val userDot: Int,
    /** 用户位置圆点的外晕（半透明），让点在深色瓦片上也看得清。 */
    val userHalo: Int,
    /** 正中那枚「当前查的是这里」的针身颜色。 */
    val centerMark: Int,
    /** 针的描边色，压在地图内容上保证任何时候都看得见。 */
    val centerHalo: Int,
)

/**
 * 停车点标记层（DESIGN §3.9）。
 *
 * 自绘而不是用 `Marker` + `Drawable`：省掉一套位图资源，而且聚合圈里要写车辆数，
 * 位图方案还得为每个数字预置图片。
 *
 * 命中测试在 [onSingleTapConfirmed] 里现算投影，不缓存 [draw] 的落点——绘制与触摸的
 * 调用顺序没有保证，缓存在冷路径上会是空表，点了没反应。
 *
 * 所有可变属性都由 Compose 侧在 `AndroidView.update` 里逐次刷新，构造时只固定画笔。
 */
internal class BikeMarkerOverlay(private val density: Float) : Overlay() {

    /** 待绘制的停车点。 */
    var clusters: List<BikeCluster> = emptyList()

    /** 当前展开的簇键；只有它画加粗描边。 */
    var selectedKey: String? = null

    /** 已取到的用户位置（GCJ-02）；null = 还没定位过。 */
    var userPoint: GcjPoint? = null

    /** 配色随主题走。 */
    var colors: BikeMarkerColors = BikeMarkerColors(
        available = Color.DKGRAY,
        lowBattery = Color.DKGRAY,
        unavailable = Color.DKGRAY,
        label = Color.WHITE,
        selectedRing = Color.BLACK,
        userDot = Color.BLUE,
        userHalo = Color.LTGRAY,
        centerMark = Color.DKGRAY,
        centerHalo = Color.WHITE,
    )

    /** 点中标记的回调；Compose 侧每次重组刷新，避免闭包捕获旧状态。 */
    var onClusterTap: (String) -> Unit = {}

    /** 命中半径：拇指点得中，又不至于把相邻停车点全吞掉（DESIGN §4.23 定为 24dp）。 */
    private val hitRadiusPx = 24f * density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        // 数字底下垫一层柔和的暗影：圈里三种底色（主色 / 琥珀 / 灰）在深浅主题下
        // 与 onPrimary 的对比度不一样，靠阴影兜住最差的那一档
        setShadowLayer(1.5f * density, 0f, 0f, 0x99000000.toInt())
    }

    /** 指针的描边与针身。 */
    private val pinStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }

    private val pinFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val pinPath = Path()

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val out = Point()
        clusters.forEach { cluster ->
            mapView.projection.toPixels(GeoPoint(cluster.lat, cluster.lng), out)
            val x = out.x.toFloat()
            val y = out.y.toFloat()
            val selected = cluster.key == selectedKey
            val radius = (if (selected) 15f else 12f) * density

            fill.color = clusterColor(cluster)
            canvas.drawCircle(x, y, radius, fill)

            ring.strokeWidth = (if (selected) 3f else 2f) * density
            ring.color = if (selected) colors.selectedRing else Color.WHITE
            canvas.drawCircle(x, y, radius, ring)

            label.color = colors.label
            label.textSize = (if (selected) 13f else 12f) * density
            val count = cluster.bikes.size.toString()
            // 基线 = 圆心下移半点字高，比直接减 descent 稳（不同字体的 descent 差得多）
            canvas.drawText(count, x, y - (label.ascent() + label.descent()) / 2f, label)
        }

        // 用户位置最后画，压在所有标记之上。
        // 反过来（先画）会被停车点聚合圈盖住——人站在车桩边上时正好是这种情形，
        // 而"我在哪"恰恰是点了定位之后要确认的事。代价是极少数情况下遮住一个聚合圈的
        // 数字，那点信息底部列表里还有。
        userPoint?.let { point ->
            mapView.projection.toPixels(GeoPoint(point.lat, point.lng), out)
            val x = out.x.toFloat()
            val y = out.y.toFloat()
            fill.color = colors.userHalo
            canvas.drawCircle(x, y, 13f * density, fill)
            fill.color = colors.userDot
            canvas.drawCircle(x, y, 7f * density, fill)
            ring.strokeWidth = 2.5f * density
            ring.color = Color.WHITE
            canvas.drawCircle(x, y, 7f * density, ring)
        }

        drawCenterPin(canvas)
    }

    override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
        if (clusters.isEmpty()) return false
        val out = Point()
        var hit: String? = null
        var best = Float.MAX_VALUE
        clusters.forEach { cluster ->
            mapView.projection.toPixels(GeoPoint(cluster.lat, cluster.lng), out)
            val distance = hypot(out.x - e.x, out.y - e.y)
            if (distance <= hitRadiusPx && distance < best) {
                best = distance
                hit = cluster.key
            }
        }
        val key = hit ?: return false
        onClusterTap(key)
        return true
    }

    /**
     * 画哪个颜色：簇里只要有一辆能骑就画「可用」，否则退到「电量低」，再退到灰。
     * 一个停车点十几辆车，用户关心的是"这里有没有能骑的"。
     */
    private fun clusterColor(cluster: BikeCluster): Int = when {
        cluster.bikes.any { it.available } -> colors.available
        cluster.bikes.any { it.status == BikeStatus.LowBattery } -> colors.lowBattery
        else -> colors.unavailable
    }

    /**
     * 正中那枚固定的针：水滴形（圆头收成尖），**尖端正好落在窗口中心**。
     *
     * 为什么是针不是准星：针的尖端天然表达"就是这个点"，与各家地图 App 的选点样式一致；
     * 四根刻线的准星还要用户自己脑补交点在哪。针身立在中心上方，不与用户位置蓝点打架，
     * 定位之后正好指着那个蓝点。
     *
     * 针身画在窗口坐标上（不是地图坐标）：地图内容在它下面滚动，它钉在屏幕上，
     * 表示"现在查的是这里"。
     *
     * 先描一层宽白边再填深色针身：地图底色明暗不定，只画一层总有看不清的地方。
     */
    private fun drawCenterPin(canvas: Canvas) {
        val cx = canvas.width / 2f
        val cy = canvas.height / 2f
        val radius = 8f * density
        val headY = cy - 27f * density

        pinPath.reset()
        pinPath.moveTo(cx, cy)
        pinPath.cubicTo(
            cx - radius * 0.3f, cy - 13f * density,
            cx - radius, headY + radius * 0.55f,
            cx - radius, headY,
        )
        pinPath.addArc(
            RectF(cx - radius, headY - radius, cx + radius, headY + radius),
            180f,
            180f,
        )
        pinPath.cubicTo(
            cx + radius, headY + radius * 0.55f,
            cx + radius * 0.3f, cy - 13f * density,
            cx, cy,
        )
        pinPath.close()

        pinStroke.strokeWidth = 3.5f * density
        pinStroke.color = colors.centerHalo
        canvas.drawPath(pinPath, pinStroke)

        pinFill.color = colors.centerMark
        canvas.drawPath(pinPath, pinFill)
    }
}