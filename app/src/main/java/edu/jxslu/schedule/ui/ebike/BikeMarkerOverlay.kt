package edu.jxslu.schedule.ui.ebike

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import edu.jxslu.schedule.domain.BikeCluster
import edu.jxslu.schedule.domain.BikeStatus
import edu.jxslu.schedule.domain.GcjPoint
import edu.jxslu.schedule.domain.KvcxParkSpot
import edu.jxslu.schedule.domain.KvcxZones
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.abs
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
    /** 校区围栏描边色（半透明主色）。 */
    val fenceStroke: Int,
    /** 校区围栏填充色（更淡的主色）。 */
    val fenceFill: Int,
    /** 当前用车标记的实心色（主色）。 */
    val rideMarker: Int,
    /** 当前用车标记的外晕（半透明主色）。 */
    val rideHalo: Int,
    /** 当前用车标记里那辆小车的颜色。 */
    val rideGlyph: Int,
    /** 禁停区描边 / 填充（深灰，官方口径 `#333333`，透明度压淡一档）。 */
    val nogoStroke: Int,
    val nogoFill: Int,
    /** 还车点范围描边 / 填充（官方口径 `#D7535D`）。 */
    val spotStroke: Int,
    val spotFill: Int,
    /** 还车点图标：圆角方块底色 + 里面的「P」。 */
    val spotBadge: Int,
    val spotGlyph: Int,
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

    /** 校区围栏顶点（GCJ-02，DESIGN §3.9「只看本校」）；不足 3 个点不画。 */
    var fence: List<GcjPoint> = emptyList()

    /** 当前展开的簇键；只有它画加粗描边。 */
    var selectedKey: String? = null

    /** 已取到的用户位置（GCJ-02）；null = 还没定位过。 */
    var userPoint: GcjPoint? = null

    /**
     * 蓝点的罗盘朝向（度，0–360，北 = 0 顺时针；来自 [BikeCompass]，按屏幕顶边计）。
     * null = 没有朝向（无磁力计 / 还没读到），只画无方向的蓝点。
     */
    var userHeading: Float? = null

    /**
     * 用户位置的定位精度（米，半径）；0 = 系统没给 / 还没定位，不画精度圈（2026-10-13）。
     * 固定大小的光晕说明不了"这个点准不准"——精度圈把这份不确定量画出来。
     */
    var userAccuracyMeters: Float = 0f

    /**
     * 当前用车的车位置（GCJ-02，来自 `queryUnderwayOrder`；快趣坐标与地图瓦片同基准，
     * **不要再过 Gcj02 转换**）；null = 没有进行中的订单。
     */
    var ridePoint: GcjPoint? = null

    /**
     * 识别条「地图查看」定位过来的车（2026-09-29「车号识别联动」）：双层圈 + 车号标签。
     * 坐标与瓦片同基准（GCJ-02），**不要再过 Gcj02 转换**；null = 没有要高亮的车。
     * 与「当前用车」的实心标记刻意区分开——它只是"看这辆"，不是"我的车"。
     */
    var highlightPoint: GcjPoint? = null

    /** 高亮标签文案（如「车 …669」）；null / 空白不画标签。 */
    var highlightLabel: String? = null

    /**
     * 还车点 / 禁停区图层（DESIGN §3.9，2026-09-28）；空 = 不画（未登录或还没拉到）。
     * **「P」只是信息，不接点击**（2026-09-29 用户口径）：它常常和车辆聚合圈压在一起，
     * 接点击只会挡着"点这辆车"。
     */
    var zones: KvcxZones = KvcxZones.EMPTY

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
        fenceStroke = Color.GRAY,
        fenceFill = Color.LTGRAY,
        rideMarker = Color.DKGRAY,
        rideHalo = Color.LTGRAY,
        rideGlyph = Color.WHITE,
        nogoStroke = Color.DKGRAY,
        nogoFill = Color.LTGRAY,
        spotStroke = Color.RED,
        spotFill = Color.LTGRAY,
        spotBadge = Color.RED,
        spotGlyph = Color.WHITE,
    )

    /** 点中标记的回调；Compose 侧每次重组刷新，避免闭包捕获旧状态。 */
    var onClusterTap: (String) -> Unit = {}

    /** 点中「当前用车」标记的回调（把镜头移过去）；Compose 侧同样逐次刷新。 */
    var onRideTap: () -> Unit = {}

    /** 命中半径：拇指点得中，又不至于把相邻停车点全吞掉（DESIGN §4.23 定为 24dp）。 */
    private val hitRadiusPx = 24f * density

    /**
     * 屏幕外剔除的余量（dp）：投影点离画布边比这个还远就不画。
     *
     * 48dp 足够盖住标记半径（簇最大 15dp + 描边）与还车点多边形（几十米，z17 下约几十像素），
     * 又能在拖动时把绝大多数离屏标记挡在绘制之前——地图每帧重画全部标记是「有点卡」的来源之一。
     */
    private val cullMarginPx = 48f * density

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

    /** 当前用车标记里的小车 glyph：两轮实心 + 踏板/立管描边。 */
    private val glyphFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val glyphStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val pinPath = Path()

    /** 围栏描边：虚线更有「边界」的感觉，实线像在画一块行政区。dash/空 7/4dp，偏密（2026-09-27 真机反馈调密）。 */
    private val fenceStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        pathEffect = DashPathEffect(floatArrayOf(7f * density, 4f * density), 0f)
    }

    /** 围栏路径，每帧按投影重算（缩放时屏幕坐标全变）。 */
    private val fencePath = Path()

    /** 还车点 / 禁停区多边形共用的一条路径（逐个画完就 reset，不跨帧保留）。 */
    private val zonePath = Path()

    /** 图层描边（实线；围栏那条是虚线，分开两把刷子）。 */
    private val zoneStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }

    /** 还车点图标的底色圆角方块。 */
    private val badgeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /**
     * 用户位置光晕的柔和径向渐变（主题色在中心、向边缘淡到全透明）。**按颜色缓存**：
     * 渐变本身只跟颜色与半径走，逐帧只更新一次平移矩阵（[haloMatrix]），不每帧重建 Shader。
     */
    private val haloMatrix = Matrix()
    private var haloShader: RadialGradient? = null
    private var haloShaderColor = Int.MIN_VALUE

    /** 图标里的「P」：白字 + 一层暗影，压在任何底色上都读得出来。 */
    private val badgeGlyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(1.5f * density, 0f, 0f, 0x99000000.toInt())
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val out = tmpPoint
        val margin = cullMarginPx
        drawFence(canvas, mapView, out)
        // 图层压在围栏之上、车辆标记之下（与官方 zIndex：服务区 3 < 禁停 4 < 还车点 5 < 车 6 同序）
        drawZones(canvas, mapView, out, margin)
        for (cluster in clusters) {
            mapView.project(cluster.lat, cluster.lng, out)
            val x = out.x.toFloat()
            val y = out.y.toFloat()
            // 屏幕外不画（2026-09-29）：拖动时每帧都要重画所有标记，几十个离屏的
            // drawCircle + drawText 纯属白干，正是「有点卡」的一部分
            if (canvas.offscreen(x, y, margin)) continue
            val selected = cluster.key == selectedKey
            val radius = (if (selected) 15f else 12f) * density

            fill.color = clusterColor(cluster)
            canvas.drawCircle(x, y, radius, fill)

            ring.strokeWidth = (if (selected) 3f else 2f) * density
            ring.color = if (selected) colors.selectedRing else Color.WHITE
            canvas.drawCircle(x, y, radius, ring)

            label.color = colors.label
            label.textSize = (if (selected) 13f else 12f) * density
            val count = countLabel(cluster.bikes.size)
            // 基线 = 圆心下移半点字高，比直接减 descent 稳（不同字体的 descent 差得多）
            canvas.drawText(count, x, y - (label.ascent() + label.descent()) / 2f, label)
        }

        // 识别高亮：画在簇标记之上、「当前用车」与蓝点之下——它是"看这辆"的指示，
        // 不该压过真正在骑的车。结果换批后车被骑走 / 被筛掉时，高亮自然消失
        highlightPoint?.let { point ->
            mapView.project(point.lat, point.lng, out)
            val x = out.x.toFloat()
            val y = out.y.toFloat()
            if (!canvas.offscreen(x, y, margin)) {
                // 双层定位圈：外圈主色描边 + 内点带白描边，比实心标记轻
                ring.strokeWidth = 2.5f * density
                ring.color = colors.available
                canvas.drawCircle(x, y, 16f * density, ring)
                fill.color = colors.available
                canvas.drawCircle(x, y, 5f * density, fill)
                ring.strokeWidth = 2f * density
                ring.color = Color.WHITE
                canvas.drawCircle(x, y, 5f * density, ring)
                val text = highlightLabel
                if (!text.isNullOrBlank()) {
                    label.color = colors.label
                    label.textSize = 11f * density
                    val textWidth = label.measureText(text)
                    val chipHeight = 19f * density
                    val chipWidth = textWidth + 14f * density
                    val chipTop = y - 16f * density - 9f * density - chipHeight
                    fill.color = colors.available
                    canvas.drawRoundRect(
                        x - chipWidth / 2, chipTop, x + chipWidth / 2, chipTop + chipHeight,
                        9f * density, 9f * density, fill,
                    )
                    ring.strokeWidth = 1.5f * density
                    ring.color = Color.WHITE
                    canvas.drawRoundRect(
                        x - chipWidth / 2, chipTop, x + chipWidth / 2, chipTop + chipHeight,
                        9f * density, 9f * density, ring,
                    )
                    canvas.drawText(
                        text,
                        x,
                        chipTop + chipHeight / 2f - (label.ascent() + label.descent()) / 2f,
                        label,
                    )
                }
            }
        }

        // 当前用车：画在簇标记之上、蓝点之下——"我的车"比一圈停车点重要，
        // 但"我在哪"仍要在最上层。样式与簇区分开：更大、主色实心 + 白描边 + 白色小车
        ridePoint?.let { point ->
            mapView.project(point.lat, point.lng, out)
            val x = out.x.toFloat()
            val y = out.y.toFloat()
            fill.color = colors.rideHalo
            canvas.drawCircle(x, y, 21f * density, fill)
            fill.color = colors.rideMarker
            canvas.drawCircle(x, y, 14f * density, fill)
            ring.strokeWidth = 3f * density
            ring.color = Color.WHITE
            canvas.drawCircle(x, y, 14f * density, ring)
            drawScooterGlyph(canvas, x, y)
        }

        // 用户位置最后画，压在所有标记之上。
        // 反过来（先画）会被停车点聚合圈盖住——人站在车桩边上时正好是这种情形，
        // 而"我在哪"恰恰是点了定位之后要确认的事。代价是极少数情况下遮住一个聚合圈的
        // 数字，那点信息底部列表里还有。
        //
        // 蓝点正落在镜头中心时，中心针的针尖会插在它身上——两者说的是同一件事
        // （"现在查的是这里"就是"我在这"），藏掉针让"我在哪"干净地读出来（见文件末的常量）
        var userDotAtCenter = false
        userPoint?.let { point ->
            mapView.project(point.lat, point.lng, out)
            val x = out.x.toFloat()
            val y = out.y.toFloat()
            userDotAtCenter = hypot(x - canvas.width / 2f, y - canvas.height / 2f) <=
                CENTER_PIN_HIDE_RADIUS_DP * density
            // 精度圈（2026-10-13）：真实定位精度折算成的屏幕半径，垫在最底下
            drawUserAccuracy(canvas, mapView, point.lat, point.lng, x, y)
            // 朝向扇形（2026-10-08）：蓝点后方的锥形光带，先画、垫在圆点底下。
            // 罗盘朝向画在屏幕坐标——它与地图北方对齐，不随瓦片投影变形，
            // 坐标基准也不相干（方向角不是坐标，不过 Gcj02）。
            userHeading?.let { heading ->
                drawHeadingWedge(canvas, x, y, heading)
            }
            // 光晕：柔和径向渐变，比一层平铺的实心圆更像「位置光圈」，边沿不再是一圈硬边
            drawUserHalo(canvas, x, y)
            // 圆点：白描边，压在明暗不定的瓦片上都读得出边界
            val dotRadius = USER_DOT_RADIUS_DP * density
            fill.color = colors.userDot
            canvas.drawCircle(x, y, dotRadius, fill)
            ring.strokeWidth = USER_DOT_RING_DP * density
            ring.color = Color.WHITE
            canvas.drawCircle(x, y, dotRadius, ring)
        }

        if (!userDotAtCenter) drawCenterPin(canvas)
    }

    override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
        val out = Point()
        // 顺序 = 绘制顺序的倒序：当前用车 > 停车点聚合。
        // 排前面的是压在别人上面的那层，否则被盖住的元素会先把点击接走。
        // **还车点「P」不参与命中**（2026-09-29 用户口径）：它常和聚合圈压在一起，
        // 接点击只会挡着选车（用户报「想点车却点到停车区弹说明」）。
        ridePoint?.let { point ->
            mapView.project(point.lat, point.lng, out)
            if (hypot(out.x - e.x, out.y - e.y) <= hitRadiusPx) {
                onRideTap()
                return true
            }
        }
        if (clusters.isEmpty()) return false
        var hit: String? = null
        var best = Float.MAX_VALUE
        clusters.forEach { cluster ->
            mapView.project(cluster.lat, cluster.lng, out)
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

    /** 当前用车标记里的小车（两轮 + 踏板 + 立管 + 把手），比写字省地方也跨语言。 */
    private fun drawScooterGlyph(canvas: Canvas, x: Float, y: Float) {
        val d = density
        glyphFill.color = colors.rideGlyph
        glyphStroke.color = colors.rideGlyph
        glyphStroke.strokeWidth = 1.9f * d
        val wheelY = y + 3.8f * d
        canvas.drawCircle(x - 5.4f * d, wheelY, 2.2f * d, glyphFill)
        canvas.drawCircle(x + 5.4f * d, wheelY, 2.2f * d, glyphFill)
        canvas.drawLine(x - 5.4f * d, wheelY, x + 3.4f * d, wheelY, glyphStroke)
        canvas.drawLine(x + 3.4f * d, wheelY, x + 5.9f * d, y - 3.6f * d, glyphStroke)
        canvas.drawLine(x + 4.2f * d, y - 3.6f * d, x + 7.2f * d, y - 3.6f * d, glyphStroke)
    }

    /**
     * 用户位置的光晕：柔和径向渐变（主题色在中心、向边缘淡到全透明），比一层平铺的实心圆
     * 更像「位置光圈」。渐变按颜色缓存（见 [haloShader]），逐帧只把中心挪到蓝点上——
     * 不每帧重建 Shader，也不给 [fill] 留下 shader（画完立刻清掉，它后面还要按 color 用）。
     */
    private fun drawUserHalo(canvas: Canvas, x: Float, y: Float) {
        val radius = USER_HALO_RADIUS_DP * density
        val color = colors.userHalo
        val shader = haloShader?.takeIf { haloShaderColor == color } ?: RadialGradient(
            0f, 0f, radius,
            intArrayOf(color, color, color and 0x00FFFFFF),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        ).also {
            haloShader = it
            haloShaderColor = color
        }
        haloMatrix.setTranslate(x, y)
        shader.setLocalMatrix(haloMatrix)
        fill.shader = shader
        canvas.drawCircle(x, y, radius, fill)
        fill.shader = null
    }

    /**
     * 用户位置的精度圈（2026-10-13）：把定位精度（米）按当前投影折成屏幕半径画一个淡色圆。
     *
     * 半径靠投影两个点现算（几百米尺度上投影局部线性，够用）——不引 osmdroid 的米/像素换算，
     * 也不用猜瓦片尺寸与 dpi 的关系。半径不大于蓝点本体时直接不画（藏在圆点底下，白画）。
     */
    private fun drawUserAccuracy(
        canvas: Canvas,
        mapView: MapView,
        lat: Double,
        lng: Double,
        x: Float,
        y: Float,
    ) {
        if (userAccuracyMeters <= 0f) return
        val radius = accuracyRadiusPx(mapView, lat, lng, userAccuracyMeters)
        if (radius <= USER_DOT_RADIUS_DP * density) return
        fill.color = colors.userHalo.scaleAlpha(ACCURACY_FILL_ALPHA_FACTOR)
        canvas.drawCircle(x, y, radius, fill)
        ring.strokeWidth = 1f * density
        ring.color = colors.userHalo.scaleAlpha(ACCURACY_STROKE_ALPHA_FACTOR)
        canvas.drawCircle(x, y, radius, ring)
    }

    /** 定位精度（米）在当前缩放下折算成的屏幕半径（像素）。 */
    private fun accuracyRadiusPx(mapView: MapView, lat: Double, lng: Double, meters: Float): Float {
        mapView.project(lat, lng, tmpPoint2)
        val y0 = tmpPoint2.y.toFloat()
        mapView.project(lat + meters / METERS_PER_DEGREE_LAT, lng, tmpPoint2)
        return abs(tmpPoint2.y - y0)
    }

    /**
     * 缩放 ARGB 颜色的 alpha（位运算，不引 ColorUtils），结果钳在 0..255。
     * 朝向扇形的内层与精度圈都由它派生，避免两处各写一遍位运算。
     */
    private fun Int.scaleAlpha(factor: Float): Int {
        val alpha = (Color.alpha(this) * factor).toInt().coerceIn(0, 255)
        return (this and 0x00FFFFFF) or (alpha shl 24)
    }

    /**
     * 蓝点的朝向扇形光晕（2026-10-08，DESIGN §3.9「蓝点朝向」）。
     *
     * 画在蓝点本体之前（垫底）：两层同心锥形——外层更大更淡、内层同色但更实
     * （不透明度从外晕色推导），近似「光带根部渐实」的观感，不引入渐变 Shader
     * （省一次 SweepGradient 的每帧重建）。角度以蓝点为轴心旋转：0° = 正北
     * （屏幕正上方），顺时针，与 [BikeCompass] 的方位角同口径。
     */
    private fun drawHeadingWedge(canvas: Canvas, x: Float, y: Float, heading: Float) {
        // Canvas.rotate 的角度基准是「3 点钟方向为 0、顺时针为正」，罗盘是「12 点为 0、
        // 顺时针为正」——先转 -90° 把基准挪到正上方，再叠加朝向角。
        canvas.save()
        canvas.rotate(heading - 90f, x, y)
        // 外层：半径约 20dp、半开角 30°（60° 锥形），比外晕（13dp）探出去一圈
        fill.color = colors.userHalo
        canvas.drawArc(setArcRect(x, y, WEDGE_OUTER_RADIUS_DP * density), -30f, 60f, true, fill)
        // 内层：同色更实（约 1.6 倍不透明度），给光带一点根部渐实的层次
        fill.color = colors.userHalo.scaleAlpha(WEDGE_INNER_ALPHA_FACTOR)
        canvas.drawArc(setArcRect(x, y, WEDGE_INNER_RADIUS_DP * density), -30f, 60f, true, fill)
        canvas.restore()
    }

    /** 把矩形按圆心/半径落位（drawArc 用，逐帧复用 [tmpRect]）。 */
    private fun setArcRect(cx: Float, cy: Float, radius: Float): RectF =
        tmpRect.set(cx - radius, cy - radius, cx + radius, cy + radius).let { tmpRect }

    /**
     * 还车点 / 禁停区图层（DESIGN §3.9）：先禁停区（深灰，底）再还车点（红框 + P 图标）。
     * 色相逐字对齐官方小程序的 polygon 口径（`#333333` / `#D7535D`），透明度压淡一档
     * ——官方那个 67% 的填充会把底图与车辆标记一起压得看不清。
     *
     * 每个多边形先过一遍经纬度包围盒（2026-10-01）：接口固定回 15 个还车点，视野里通常
     * 只有三五个，剩下那十个每帧都要逐顶点投影建路径——拖动时的固定开销就在这里。
     */
    private fun drawZones(canvas: Canvas, mapView: MapView, out: Point, margin: Float) {
        if (zones.isEmpty) return
        val box = mapView.boundingBox
        zones.nogoZones.forEach { outline ->
            if (outline.none { it.inside(box, ZONE_CULL_PADDING_DEG) }) return@forEach
            buildPath(mapView, outline, out)
            fill.color = colors.nogoFill
            canvas.drawPath(zonePath, fill)
            zoneStroke.color = colors.nogoStroke
            zoneStroke.strokeWidth = 1f * density
            canvas.drawPath(zonePath, zoneStroke)
        }
        for (spot in zones.parkSpots) {
            // 多边形顶点或点本身有一个落在（带余量的）可视范围里才继续
            val visible = spot.outline.any { it.inside(box, ZONE_CULL_PADDING_DEG) } ||
                (spot.lat >= box.latSouth - ZONE_CULL_PADDING_DEG &&
                    spot.lat <= box.latNorth + ZONE_CULL_PADDING_DEG &&
                    spot.lng >= box.lonWest - ZONE_CULL_PADDING_DEG &&
                    spot.lng <= box.lonEast + ZONE_CULL_PADDING_DEG)
            if (!visible) continue
            mapView.project(spot.lat, spot.lng, out)
            // 屏幕外连多边形一起跳过：还车点范围只有几十米，余量足够盖住
            if (canvas.offscreen(out.x.toFloat(), out.y.toFloat(), margin)) continue
            if (spot.outline.size >= 3) {
                buildPath(mapView, spot.outline, out)
                fill.color = colors.spotFill
                canvas.drawPath(zonePath, fill)
                zoneStroke.color = colors.spotStroke
                zoneStroke.strokeWidth = 1.5f * density
                canvas.drawPath(zonePath, zoneStroke)
            }
            drawSpotBadge(canvas, mapView, spot, out)
        }
    }

    /** 投影点是否落在画布外（留 [margin] 余量，标记半径与边缘弹跳都算进去）。 */
    private fun Canvas.offscreen(x: Float, y: Float, margin: Float): Boolean =
        x < -margin || y < -margin || x > width + margin || y > height + margin

    /**
     * 投影用的临时点：**逐帧复用同一个实例**。原来每簇每帧 new 一个 `GeoPoint`，
     * 二十几个簇拖一秒就是上千个小对象，全是喂给 GC 的。
     */
    private val tmpGeo = GeoPoint(0.0, 0.0)

    /**
     * 投影结果的落点与圆角方块/圆弧共用的矩形：**同样逐帧复用**（2026-10-01 顺手收的）。
     * 每帧一个 `Point` + 每个还车点一个 `RectF` 看着不多，拖动时是每秒上千个小对象进 GC。
     * 用法都是"写完立刻交给 drawXxx"，跨函数共用是安全的（`draw()` 里全是一次性调用）。
     */
    private val tmpPoint = Point()
    private val tmpRect = RectF()

    /** 精度圈折算用的第二个投影落点（不能与 [tmpPoint] 共用：投影基准点与偏移点要同时在场）。 */
    private val tmpPoint2 = Point()

    /**
     * 簇内车辆数的文案缓存：`Int.toString()` 每簇每帧各来一发。数量只会是几十，
     * 直接按值查表（越界回落到 [Int.toString]）。
     */
    private val countText = Array(100) { it.toString() }

    private fun countLabel(size: Int): String = if (size in countText.indices) countText[size] else size.toString()

    private fun MapView.project(lat: Double, lng: Double, out: Point) {
        tmpGeo.latitude = lat
        tmpGeo.longitude = lng
        projection.toPixels(tmpGeo, out)
    }

    /**
     * 这一点在不在（带 [pad] 余量的）可视范围里。
     *
     * 余量按 [ZONE_CULL_PADDING_DEG] 给（约 220 米，比任何一块还车点都大）：
     * 多边形横跨屏幕、顶点全落在屏幕外的情形靠它兜住。
     */
    private fun GcjPoint.inside(box: BoundingBox, pad: Double): Boolean =
        lat >= box.latSouth - pad && lat <= box.latNorth + pad &&
            lng >= box.lonWest - pad && lng <= box.lonEast + pad

    /** 顶点列表 → 闭合路径（屏幕坐标逐帧重算，缩放时不会飘）。 */
    private fun buildPath(mapView: MapView, outline: List<GcjPoint>, out: Point) {
        zonePath.reset()
        outline.forEachIndexed { index, point ->
            mapView.project(point.lat, point.lng, out)
            if (index == 0) {
                zonePath.moveTo(out.x.toFloat(), out.y.toFloat())
            } else {
                zonePath.lineTo(out.x.toFloat(), out.y.toFloat())
            }
        }
        zonePath.close()
    }

    /**
     * 还车点图标：一枚圆角方块 + 白「P」（停车符号，跨语言都认）。
     * 官方那枚是位图资源（24×16），这里自绘省一套图；白描边保证压在红框与底图上都看得见。
     */
    private fun drawSpotBadge(canvas: Canvas, mapView: MapView, spot: KvcxParkSpot, out: Point) {
        mapView.project(spot.lat, spot.lng, out)
        val x = out.x.toFloat()
        val y = out.y.toFloat()
        // 尺寸压到 15dp（原来 20dp 显得比车标还抢眼）；命中半径仍是 24dp，点得中
        val half = 7.5f * density
        val rect = tmpRect.apply { set(x - half, y - half, x + half, y + half) }
        val corner = 3f * density
        badgeFill.color = colors.spotBadge
        canvas.drawRoundRect(rect, corner, corner, badgeFill)
        ring.strokeWidth = 1.5f * density
        ring.color = Color.WHITE
        canvas.drawRoundRect(rect, corner, corner, ring)
        badgeGlyph.color = colors.spotGlyph
        badgeGlyph.textSize = 10f * density
        canvas.drawText("P", x, y - (badgeGlyph.ascent() + badgeGlyph.descent()) / 2f, badgeGlyph)
    }

    /**
     * 校区围栏画在**最底层**（簇标记、蓝点、中心针都要压在它上面）：
     * 它是背景信息，盖住任何一辆车都是本末倒置。
     */
    private fun drawFence(canvas: Canvas, mapView: MapView, out: Point) {
        if (fence.size < 3) return
        fencePath.reset()
        fence.forEachIndexed { index, point ->
            mapView.project(point.lat, point.lng, out)
            if (index == 0) {
                fencePath.moveTo(out.x.toFloat(), out.y.toFloat())
            } else {
                fencePath.lineTo(out.x.toFloat(), out.y.toFloat())
            }
        }
        fencePath.close()
        fill.color = colors.fenceFill
        canvas.drawPath(fencePath, fill)
        fenceStroke.color = colors.fenceStroke
        fenceStroke.strokeWidth = 2f * density
        canvas.drawPath(fencePath, fenceStroke)
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
            tmpRect.apply { set(cx - radius, headY - radius, cx + radius, headY + radius) },
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

/** 朝向扇形外层半径（dp）：比外晕（13dp）探出一圈，光带尖端离蓝点约一指宽。 */
private const val WEDGE_OUTER_RADIUS_DP = 20f

/** 朝向扇形内层半径（dp）：更实的内层，做「根部渐实」的层次。 */
private const val WEDGE_INNER_RADIUS_DP = 13f

/** 朝向扇形内层的 alpha 系数（相对外晕色）：比外层实一点，做出根部渐实。 */
private const val WEDGE_INNER_ALPHA_FACTOR = 1.6f

/** 用户位置光晕半径（dp）：柔和渐变从主题色淡到全透明；与朝向扇形内层同半径，两者不打架。 */
private const val USER_HALO_RADIUS_DP = 13f

/** 用户位置圆点半径（dp）。 */
private const val USER_DOT_RADIUS_DP = 8f

/** 用户位置圆点的白描边宽度（dp）。 */
private const val USER_DOT_RING_DP = 2.5f

/** 精度圈填充的 alpha 系数（相对外晕色）：压淡一档，别盖住底图。 */
private const val ACCURACY_FILL_ALPHA_FACTOR = 0.45f

/** 精度圈描边的 alpha 系数（相对外晕色）：比填充实一点，圈边看得清。 */
private const val ACCURACY_STROKE_ALPHA_FACTOR = 1.1f

/** 蓝点离镜头中心这么近（dp）就不画中心针：针尖与蓝点重合，说的是同一件事。 */
private const val CENTER_PIN_HIDE_RADIUS_DP = 12f

/** 一个纬度约合多少米（精度圈折算用；BikeNearby 里那份是私有的，这边另记一个常量）。 */
private const val METERS_PER_DEGREE_LAT = 111_320.0

/** 图层包围盒剔除的余量（度）：约 220 米，比任何一块还车点 / 禁停区都大。 */
private const val ZONE_CULL_PADDING_DEG = 0.002
