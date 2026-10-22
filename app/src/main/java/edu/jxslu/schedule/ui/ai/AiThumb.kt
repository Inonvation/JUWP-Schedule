package edu.jxslu.schedule.ui.ai

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.ui.common.decodeFileSampled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AI 图片缩略图与缓存（自旧对话式 UI 的 `AiMessageBubbles` 迁来，2026-10-07）。
 *
 * 解码目标分两档：缩略条几百像素就够，全屏查看器 1600。
 * LRU 缓存按字节计，12MB 上限——截图 4 张压缩后约 1–3MB/张，绰绰有余且不会撑爆内存。
 */

/** 缩略图与全屏查看器的解码目标长边（别一律 720：缩略条只需要几百像素）。 */
private const val THUMB_TARGET_PX = 480
private const val VIEWER_TARGET_PX = 1600

private object AiImageCache {
    private const val MAX_BYTES = 12 * 1024 * 1024

    private val cache = object : LruCache<String, Bitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun get(key: String): Bitmap? = cache.get(key)

    fun put(key: String, bitmap: Bitmap) {
        cache.put(key, bitmap)
    }

    fun clear() {
        cache.evictAll()
    }
}

/** 内存压力 / AI 会话结束时清空位图缓存；文件仍由 AiImageStore 管。 */
internal fun clearAiImageCache() = AiImageCache.clear()

/** 一张本地 AI 图片。[fullScreen] = true 时按查看器的尺寸解码。 */
@Composable
internal fun AiThumb(
    fileName: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    fullScreen: Boolean = false,
    contentDescription: String? = null,
) {
    val context = LocalContext.current
    val file = remember(fileName) { Graph.aiImageStore(context).fileFor(fileName) }
    val target = if (fullScreen) VIEWER_TARGET_PX else THUMB_TARGET_PX
    val key = remember(fileName, target) { "ai:$fileName@$target" }
    val bitmap by produceState<ImageBitmap?>(AiImageCache.get(key)?.asImageBitmap(), key) {
        val cached = AiImageCache.get(key)
        if (cached != null) {
            value = cached.asImageBitmap()
            return@produceState
        }
        val decoded = withContext(Dispatchers.IO) { decodeFileSampled(file, target) }
        if (decoded != null) {
            AiImageCache.put(key, decoded)
            value = decoded.asImageBitmap()
        }
    }
    Box(
        modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        } ?: Text(
            "图片",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
        )
    }
}
