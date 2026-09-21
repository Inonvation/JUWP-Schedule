package edu.jxslu.schedule.ui.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import edu.jxslu.schedule.Graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Image01

/**
 * 笔记·作业的图片附件展示（DESIGN §4.20）。
 *
 * 图片本体在应用私有目录（`data/repo/AttachmentStore`），这里按**目标宽度**解码
 * （BitmapFactory 下采样）并用一张小 LRU 缓存挡住滚动/重组造成的重复解码——
 * 没有引入任何图片库（体积与 `--offline` 构建是硬约束）。
 */
private object AttachmentImageCache {
    /**
     * 上限按**字节**计（不是条数）：一张 1080px 宽的竖图约 6MB 位图，按条数记 24 条
     * 最坏能堆到上百 MB——中低端机滚一遍图片多的笔记就 OOM。
     */
    private const val MAX_BYTES = 16 * 1024 * 1024

    private val cache = object : LruCache<String, Bitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun get(key: String): Bitmap? = cache.get(key)

    fun put(key: String, bitmap: Bitmap) {
        cache.put(key, bitmap)
    }
}

/** 按目标宽度（px）解码附件；文件不存在或解码失败返回 null。 */
suspend fun loadAttachmentBitmap(
    context: Context,
    fileName: String,
    targetWidthPx: Int,
): ImageBitmap? = withContext(Dispatchers.IO) {
    val key = "$fileName@$targetWidthPx"
    AttachmentImageCache.get(key)?.let { return@withContext it.asImageBitmap() }
    val file = Graph.attachmentStore(context).fileFor(fileName)
    if (!file.isFile) return@withContext null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0) return@withContext null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= targetWidthPx.coerceAtLeast(64)) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return@withContext null
    // 缓存里放 android.graphics.Bitmap（byteCount 可计量），ImageBitmap 只是薄包装
    AttachmentImageCache.put(key, bitmap)
    bitmap.asImageBitmap()
}

/**
 * 满宽附件图：宽撑满、按原始比例给高（解码后才有比例，未解码前占位 16:9）。
 * 文件缺失（被清理 / 迁移丢文件）时给「图片已丢失」灰块——比什么都不显示更诚实。
 */
@Composable
fun AttachmentImage(
    fileName: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(initialValue = null, fileName) {
        value = loadAttachmentBitmap(context, fileName, targetWidthPx = 1080)
    }
    val shape = RoundedCornerShape(12.dp)
    val bitmap = image
    if (bitmap == null) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = HugeIcons.Image01,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    "图片已丢失",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }
        }
        return
    }
    val ratio = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1).toFloat()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(ratio.coerceIn(0.4f, 3f))
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
