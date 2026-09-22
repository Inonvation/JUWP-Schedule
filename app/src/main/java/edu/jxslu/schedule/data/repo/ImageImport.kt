package edu.jxslu.schedule.data.repo

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * 选图 → 落盘的共用实现（DESIGN §4.20 笔记附件、§4.21 课表背景图）。
 *
 * 两个调用方的差别只有目录与文件名生成，压缩口径完全一致，所以收成一份：
 * 长边超过 [maxSide] 或原始字节超过 [maxBytes] 时下采样 + 长边钳到 [maxSide] 后 JPEG 重编码；
 * 否则流式复制原文件，保留 PNG 透明。改口径只动这里。
 */
internal object ImageImport {

    /**
     * 把 [uri] 指向的图复制/压缩进 [dir]，返回新文件名。失败（读不到、解不出）返回 null，
     * 由调用方提示，不静默丢。调用前需保证 [dir] 存在。
     */
    fun copyInto(
        dir: File,
        uri: Uri,
        resolver: ContentResolver,
        maxSide: Int,
        maxBytes: Long,
        jpegQuality: Int,
        fileNameFor: (ext: String) -> String,
    ): String? {
        val mime = resolver.getType(uri).orEmpty()
        val isPng = mime.contains("png", ignoreCase = true)

        // 第一遍：只读尺寸边界——不分配像素内存（48MP 照片整图 decode 要十几 MB 峰值）
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val longSide = max(bounds.outWidth, bounds.outHeight)
        if (bounds.outWidth <= 0 || longSide <= 0) return null

        // 文件字节数拿不到（部分 provider 返回 -1）时以尺寸为准，够用
        val declaredBytes = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        val needResize = longSide > maxSide || declaredBytes > maxBytes

        if (!needResize) {
            // 小图：流式复制（不整图进内存），保留原格式与 PNG 透明
            val fileName = fileNameFor(if (isPng) "png" else "jpg")
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(File(dir, fileName)).use { output -> input.copyTo(output) }
            } ?: return null
            return fileName
        }

        val options = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(longSide, maxSide) }
        val decoded = resolver.openInputStream(uri)
            ?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return null
        val scaled = if (max(decoded.width, decoded.height) > maxSide) {
            val ratio = maxSide.toFloat() / max(decoded.width, decoded.height)
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * ratio).toInt().coerceAtLeast(1),
                (decoded.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            decoded
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()
        val fileName = fileNameFor("jpg")
        FileOutputStream(File(dir, fileName)).use { it.write(out.toByteArray()) }
        return fileName
    }

    /** 下采样倍数：2 的幂，保证解码后长边不小于目标（再精确缩放一次）。 */
    private fun sampleSizeFor(longSide: Int, target: Int): Int {
        var sample = 1
        while (longSide / (sample * 2) >= target) sample *= 2
        return sample
    }
}
