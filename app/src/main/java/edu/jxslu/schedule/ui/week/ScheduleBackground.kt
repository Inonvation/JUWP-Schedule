package edu.jxslu.schedule.ui.week

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.domain.BgScale
import edu.jxslu.schedule.domain.ScheduleBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * 课表页背景图的位图缓存（DESIGN §4.21）。
 *
 * **只有一格**：界面上同一时刻就一张背景图，按条数或按字节计的 LRU 在这里没有意义；
 * 而笔记附件那套 16MB 的 LRU 装不下背景图（一张 1920×1080 就是 8.3MB），
 * 混用会把笔记里的图挤出去。
 *
 * 键含解码目标长边：拖模糊滑块时目标尺寸变化，旧位图自然作废。
 * 冷启动由 [warmScheduleBackground] 预热，避免切到课表 Tab 时背景晚一帧出现。
 */
private object BackgroundBitmapCache {
    private var cachedKey: String? = null
    private var cached: Bitmap? = null

    @Synchronized
    fun get(key: String): Bitmap? = cached?.takeIf { cachedKey == key }

    @Synchronized
    fun put(key: String, value: Bitmap) {
        cachedKey = key
        cached = value
    }

    /** 换图 / 移除时调用，别让旧位图一直占着 8MB 级的内存。 */
    @Synchronized
    fun clear() {
        cachedKey = null
        cached = null
    }
}

/** 清空背景位图缓存（背景被移除时调用，别让旧位图一直占着 8MB 级的内存）。 */
internal fun clearBackgroundBitmapCache() = BackgroundBitmapCache.clear()

/**
 * 设备屏幕长边（px）。
 *
 * 预热与渲染必须走同一个函数：缓存键里含解码目标尺寸，两处各算一遍
 * （`displayMetrics` 与 `screenWidthDp × density` 会差几个像素）就会互相错过，
 * 冷启动预热等于白做，首帧照样闪一下。
 */
internal fun screenLongSidePx(context: Context): Int {
    val metrics = context.resources.displayMetrics
    return max(metrics.widthPixels, metrics.heightPixels)
}

/**
 * 解码背景图；文件不存在或解码失败返回 null（渲染层当作无背景）。
 * [targetLongSidePx] 由调用方按模糊档位与屏幕尺寸算好，见 [ScheduleBackground.targetLongSide]。
 *
 * [cache] = false 用于设置面板里的缩略图：它只解 200 多像素，进单槽缓存会把整屏那张挤掉，
 * 于是每次开关面板都要重解一次全尺寸图。
 */
internal suspend fun loadScheduleBackground(
    context: Context,
    fileName: String,
    targetLongSidePx: Int,
    cache: Boolean = true,
): ImageBitmap? = withContext(Dispatchers.IO) {
    val file = Graph.scheduleBackground(context).fileFor(fileName) ?: return@withContext null
    if (!file.isFile) return@withContext null

    val cacheKey = "${file.name}@$targetLongSidePx"
    if (cache) {
        BackgroundBitmapCache.get(cacheKey)?.let { return@withContext it.asImageBitmap() }
    }

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    val longSide = max(bounds.outWidth, bounds.outHeight)
    if (bounds.outWidth <= 0 || longSide <= 0) return@withContext null

    var sample = 1
    while (longSide / (sample * 2) >= targetLongSidePx.coerceAtLeast(64)) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return@withContext null
    // inSampleSize 只能按 2 的幂缩，小屏上常常比目标大一截（1920 的图在 1280 的屏幕上仍是全尺寸解码，
    // 白占 14MB）。这里再精确缩一次，让缓存里那一张的大小只由目标尺寸决定。
    val decodedLongSide = max(bitmap.width, bitmap.height)
    val finalBitmap = if (decodedLongSide > targetLongSidePx) {
        val ratio = targetLongSidePx.toFloat() / decodedLongSide
        Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true,
        ).also { if (it !== bitmap) bitmap.recycle() }
    } else {
        bitmap
    }
    if (cache) BackgroundBitmapCache.put(cacheKey, finalBitmap)
    finalBitmap.asImageBitmap()
}

/**
 * 冷启动预热：把当前背景图先解好放进缓存。
 * 课表页首帧要等它，不预热就会出现「网格先出、背景半秒后才补上」的闪入。
 * 目标尺寸按设备长边算，与渲染路径同一口径，命中率才是 100%。
 */
internal suspend fun warmScheduleBackground(context: Context, fileName: String, blur: Float) {
    loadScheduleBackground(
        context = context,
        fileName = fileName,
        targetLongSidePx = ScheduleBackground.targetLongSide(screenLongSidePx(context), blur),
    )
}

/**
 * 背景层（DESIGN §4.21）：图片 + 压暗遮罩 + 顶部渐变，铺在课表页所有内容之下。
 *
 * 无背景（[fileName] 为 null）或解码未完成时什么都不画，页面回到主题底色，
 * 不占位、不留灰块。
 */
@Composable
internal fun ScheduleBackgroundLayer(
    fileName: String?,
    opacity: Float,
    dim: Float,
    blur: Float,
    scale: BgScale,
    modifier: Modifier = Modifier,
) {
    if (fileName == null) {
        // 移除背景后缓存里那张已经没人用了，留着就是白占内存
        LaunchedEffect(Unit) { clearBackgroundBitmapCache() }
        return
    }
    val context = LocalContext.current
    // 平铺按原图像素铺，不能再按屏幕长边压：压过之后「一块」的大小会随设备变
    val targetLongSidePx = if (scale == BgScale.Tile) {
        ScheduleBackground.decodeLongSide(blur)
    } else {
        ScheduleBackground.targetLongSide(screenLongSidePx(context), blur)
    }
    val image by produceState<ImageBitmap?>(initialValue = null, fileName, targetLongSidePx) {
        value = loadScheduleBackground(context, fileName, targetLongSidePx)
    }
    val bitmap = image ?: return

    Box(modifier) {
        when (scale) {
            BgScale.Fill -> Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = opacity,
                modifier = Modifier.fillMaxSize(),
            )

            BgScale.Fit -> Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                alpha = opacity,
                modifier = Modifier.fillMaxSize(),
            )

            BgScale.Tile -> Canvas(Modifier.fillMaxSize()) {
                val src = IntSize(bitmap.width, bitmap.height)
                var y = 0
                while (y < size.height) {
                    var x = 0
                    while (x < size.width) {
                        drawImage(
                            image = bitmap,
                            srcOffset = IntOffset.Zero,
                            srcSize = src,
                            dstOffset = IntOffset(x, y),
                            dstSize = src,
                            alpha = opacity,
                            filterQuality = FilterQuality.None,
                        )
                        x += bitmap.width
                    }
                    y += bitmap.height
                }
            }
        }
        // 压暗遮罩：亮图上白色课名与时间轴读不出来，靠它把对比度拉回来。
        // 铺在图片之上、网格之下——网格的文字不吃这层灰。
        if (dim > 0f) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))
        }
        // 顶部渐变：图铺到状态栏之后，状态栏图标的深浅由系统按主题定，压在亮图或暗图上
        // 都可能失去对比；顺带把顶栏文字托住。高度取「状态栏 + 40dp」，到顶栏下缘已淡出。
        val scrimHeight = with(LocalDensity.current) {
            WindowInsets.statusBars.getTop(this).toDp() + 40.dp
        }
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(scrimHeight)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.background.copy(alpha = 0.6f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )
    }
}

/** 设置面板里的缩略图长边（px）。够 56×40dp 的槽位用，不占内存。 */
private const val ThumbLongSidePx = 240

/**
 * 显示设置面板里的背景缩略图（DESIGN §4.21）。
 * 解码走 [loadScheduleBackground] 的 `cache = false` 分支：缩略图不该顶掉整屏那张。
 */
@Composable
internal fun ScheduleBackgroundThumb(
    fileName: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(initialValue = null, fileName) {
        value = loadScheduleBackground(context, fileName, ThumbLongSidePx, cache = false)
    }
    Box(modifier) {
        image?.let { bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
