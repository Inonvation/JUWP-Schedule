package edu.jxslu.schedule.ui.common

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import edu.jxslu.schedule.domain.GalleryPaths
import java.io.File
import java.io.FileOutputStream

/**
 * 位图落盘到系统相册的通用实现（共享单车出码 §4.18 与课表导出图片 §4.12 共用）。
 *
 * API 29+ 走 MediaStore，无需权限；26–28 走公共目录 `Pictures/<subDir>` File 直写
 * （manifest 已声明 legacy 外存权限，`maxSdkVersion=28`）。目录口径与文件名由调用方给。
 */
sealed interface GallerySaveResult {
    /**
     * 落盘位置：API 29+ 是 MediaStore 的 content uri 字符串；26–28 是落盘文件的绝对路径。
     * 调用方按 SDK 判据决定怎么用它（例如换算成「待焚毁 key」）。
     */
    data class Saved(val location: String) : GallerySaveResult

    data class Failed(val message: String) : GallerySaveResult
}

/**
 * 保存一张位图到相册。成功返回 [GallerySaveResult.Saved]，失败返回带原因的
 * [GallerySaveResult.Failed]（调用方负责给用户提示，不静默丢）。
 */
fun saveBitmapToGallery(
    context: Context,
    bitmap: Bitmap,
    displayName: String,
    format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG,
    quality: Int = 100,
    // 当前相册目录；旧目录 `Pictures/水贝贝` 由 GalleryMigration 首次冷启动迁移。
    subDir: String = GalleryPaths.CURRENT_SUBDIR,
): GallerySaveResult {
    return try {
        if (Build.VERSION.SDK_INT >= 29) {
            val mime = if (format == Bitmap.CompressFormat.PNG) "image/png" else "image/jpeg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/" + subDir,
                )
            }
            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values,
            ) ?: return GallerySaveResult.Failed("相册创建失败（系统拒绝写入）")
            val encoded = context.contentResolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(format, quality, out)
            } ?: return GallerySaveResult.Failed("相册创建失败（系统拒绝写入）")
            if (!encoded) {
                // 编码失败要回收已 insert 的空行：留着就是相册里一张 0 字节孤儿图
                runCatching { context.contentResolver.delete(uri, null, null) }
                return GallerySaveResult.Failed("图片编码失败")
            }
            GallerySaveResult.Saved(uri.toString())
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                subDir,
            )
            if (!dir.exists() && !dir.mkdirs()) return GallerySaveResult.Failed("无法创建相册目录")
            val file = File(dir, displayName)
            FileOutputStream(file).use { out ->
                if (!bitmap.compress(format, quality, out)) {
                    return GallerySaveResult.Failed("图片编码失败")
                }
            }
            GallerySaveResult.Saved(file.absolutePath)
        }
    } catch (e: SecurityException) {
        GallerySaveResult.Failed("没有相册写入权限")
    } catch (e: Exception) {
        GallerySaveResult.Failed("保存失败：${e.message ?: "未知错误"}")
    }
}
