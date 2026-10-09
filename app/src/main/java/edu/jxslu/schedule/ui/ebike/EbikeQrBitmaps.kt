package edu.jxslu.schedule.ui.ebike

import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import edu.jxslu.schedule.domain.EbikeQr
import edu.jxslu.schedule.ui.common.GallerySaveResult
import edu.jxslu.schedule.ui.common.saveBitmapToGallery
import java.io.File

/**
 * 共享单车二维码的位图落盘（DESIGN §3.9 / §4.18）。
 *
 * 矩阵生成在 `domain/EbikeQr`（JVM 可测）；着色渲染已抽到
 * `ui/common/CodeBitmaps`（与校园卡付款码共用，DESIGN §4.19），本对象只做 MediaStore 写入
 * 与「扫完即焚」的删除执行。
 */
object EbikeQrBitmaps {

    /** 保存结果：成功带待焚毁 key（[EbikeQr.pendingMediaKey]/[EbikeQr.pendingFileKey]）。 */
    sealed interface SaveResult {
        data class Saved(val pendingKey: String) : SaveResult
        data class Failed(val message: String) : SaveResult
    }

    /**
     * BitMatrix → Bitmap。黑白两色取当前主题的前景/背景（深色主题出「深底白码」：
     * 微信扫一扫对反色码兼容良好，且与页面观感一致）。委托共用实现。
     */
    fun render(matrix: com.google.zxing.common.BitMatrix, dark: Boolean): Bitmap =
        edu.jxslu.schedule.ui.common.CodeBitmaps.render(matrix, dark)

    /**
     * 保存到系统相册（`Pictures/水贝贝`）。API 29+ 走 MediaStore，无需权限；
     * 26–28 走公共目录 File 直写（manifest 已声明 legacy 外存权限）。
     * 成功返回 [SaveResult.Saved]（附待焚毁 key，扫完即焚用）；失败返回 [SaveResult.Failed]。
     *
     * 落盘本身委托给共用的 [saveBitmapToGallery]（课表导出图片 §4.12 同一条路）；
     * 本对象只保留「待焚毁 key」这段与共享单车绑定的语义。
     */
    fun saveToGallery(context: Context, bitmap: Bitmap, bikeId: String): SaveResult {
        return when (
            val result = saveBitmapToGallery(
                context = context,
                bitmap = bitmap,
                displayName = "ebike-$bikeId.jpg",
                format = Bitmap.CompressFormat.JPEG,
                quality = 90,
            )
        ) {
            is GallerySaveResult.Saved -> SaveResult.Saved(
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    EbikeQr.pendingMediaKey(result.location)
                } else {
                    EbikeQr.pendingFileKey(result.location)
                },
            )
            is GallerySaveResult.Failed -> SaveResult.Failed(result.message)
        }
    }

    /** 本功能落盘的相盘子目录（与 [saveToGallery] 同源，删除校验用）。 */
    private const val GALLERY_SUBDIR = "水贝贝"

    /**
     * 按 key 删除一张已保存的二维码（扫完即焚，DESIGN §3.9）。只处理本功能自己
     * 写入的条目：API 29+ 是自己 insert 的 MediaStore uri（owner 删除免权限免确认）；
     * 26–28 是本功能写的公共目录文件。key 目标再做一次包含性校验——key 唯一
     * 写入方是 [saveToGallery]，校验只防 DataStore 被篡改/损坏后误删别家文件。
     * 返回 true = 已删除；false = 失败/不存在/校验不过（调用方保留 key 下次重试）。
     */
    fun deletePending(context: Context, key: String): Boolean {
        val parsed = EbikeQr.parsePendingKey(key) ?: return false
        return try {
            when (parsed.first) {
                EbikeQr.PendingKind.MediaStore -> if (
                    android.os.Build.VERSION.SDK_INT >= 29
                ) {
                    val uri = android.net.Uri.parse(parsed.second)
                    // 本功能的 MediaStore 条目只会落在 images 集合；host 校验防任意 content uri
                    if (uri.authority != "media" || !uri.path.orEmpty().contains("images")) {
                        false
                    } else {
                        context.contentResolver.delete(uri, null, null) > 0
                    }
                } else {
                    false // 不应出现（29+ 才有 MediaStore key），防御性丢弃
                }
                EbikeQr.PendingKind.FilePath -> {
                    val path = File(parsed.second).canonicalPath
                    val dir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                        GALLERY_SUBDIR,
                    ).canonicalPath
                    // 只删自己目录下的文件（canonicalPath 兼防 ../ 穿越写法）
                    if (!path.startsWith("$dir${File.separatorChar}")) {
                        false
                    } else {
                        File(path).delete()
                    }
                }
            }
        } catch (_: Exception) {
            false
        }
    }
}
