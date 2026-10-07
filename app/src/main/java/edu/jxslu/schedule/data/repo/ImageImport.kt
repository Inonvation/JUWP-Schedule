package edu.jxslu.schedule.data.repo

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * 选图 → 落盘的共用实现（DESIGN §4.20 笔记附件、§4.21 课表背景图、§4.39 AI 图片）。
 *
 * 各调用方的差别只有目录、文件名生成与压缩口径，流程完全一致，所以收成一份：
 * 长边超过 [maxSide] 或原始字节超过 [maxBytes] 时下采样 + 长边钳到 [maxSide] 后 JPEG 重编码；
 * 否则流式复制原文件，保留 PNG 透明。改口径只动参数，别在 UI 里另写。
 *
 * **EXIF 方向**（2026-10-06 补）：`BitmapFactory` 不认 `TAG_ORIENTATION`，横拍的照片
 * 解出来是躺倒的。方向不为 1 时必须重编码把方向**烘进像素**，否则 App 内渲染
 * （`ui/common/AttachmentImage` 走 `decodeFile`）与发给上游都是躺的。
 * 重编码时 `Bitmap.compress` 不写 EXIF，正好——方向已经在像素里了。
 *
 * [forceJpeg] 给 AI 图片用：**必须**保证落盘字节与声明的 mime 一致。小图走流式复制那条
 * 分支时，HEIC 会被命名成 `.jpg` 而内容是 HEIC（扩展名骗人），base64 出去上游必拒。
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
        /** true = 无条件解码重编码成 JPEG（AI 图片：保证字节与扩展名/mime 一致）。 */
        forceJpeg: Boolean = false,
    ): String? {
        val mime = resolver.getType(uri).orEmpty()
        val isPng = mime.contains("png", ignoreCase = true)

        // 第一遍：只读尺寸边界——不分配像素内存（48MP 照片整图 decode 要十几 MB 峰值）
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val longSide = max(bounds.outWidth, bounds.outHeight)
        if (bounds.outWidth <= 0 || longSide <= 0) return null

        // 方向不为 1 也得走重编码：像素不转正，后面谁看都是躺的
        val orientation = readOrientation(resolver, uri)

        // 文件字节数拿不到（部分 provider 返回 -1）时以尺寸为准，够用
        val declaredBytes = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        val needResize = forceJpeg || longSide > maxSide || declaredBytes > maxBytes ||
            orientation != ExifInterface.ORIENTATION_NORMAL

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
        // 顺序要紧：先转正（90/270 时长宽互换），再按 maxSide 钳
        val upright = applyOrientation(decoded, orientation)
        if (upright !== decoded) decoded.recycle()
        val scaled = if (max(upright.width, upright.height) > maxSide) {
            val ratio = maxSide.toFloat() / max(upright.width, upright.height)
            Bitmap.createScaledBitmap(
                upright,
                (upright.width * ratio).toInt().coerceAtLeast(1),
                (upright.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            upright
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
        if (scaled !== upright) scaled.recycle()
        upright.recycle()
        val fileName = fileNameFor("jpg")
        FileOutputStream(File(dir, fileName)).use { it.write(out.toByteArray()) }
        return fileName
    }

    /** 读 EXIF 方向；读不到（PNG / 无 EXIF / provider 不支持）按正常处理。 */
    private fun readOrientation(resolver: ContentResolver, uri: Uri): Int = runCatching {
        resolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    /** 按 EXIF 方向把像素转正；正常方向原样返回（**同一个实例**，调用方靠 `!==` 判断）。 */
    private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f); matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f); matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrDefault(bitmap)
    }

    /** 下采样倍数：2 的幂，保证解码后长边不小于目标（再精确缩放一次）。 */
    private fun sampleSizeFor(longSide: Int, target: Int): Int {
        var sample = 1
        while (longSide / (sample * 2) >= target) sample *= 2
        return sample
    }
}
