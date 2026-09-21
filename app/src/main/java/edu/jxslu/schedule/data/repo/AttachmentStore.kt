package edu.jxslu.schedule.data.repo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import edu.jxslu.schedule.domain.imageRefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.max
import kotlin.random.Random

/**
 * 笔记·作业的图片附件存储（DESIGN §4.20）。
 *
 * 落盘位置 = 应用私有目录 `filesDir/notes_img/`，正文用 `![](img:文件名)` 引用。
 * 选图走系统 Photo Picker（`PickVisualMedia`，**零权限**），这里只负责把 Uri **复制**进来——
 * 不复制的话媒体库/SAF 授权随时可能失效，笔记里的图就白了。
 *
 * 压缩口径（唯一来源，别在 UI 里另写）：长边 > [MAX_SIDE] 或原始字节 > [MAX_BYTES] 时
 * 下采样 + 长边钳到 [MAX_SIDE]、JPEG 质量 [JPEG_QUALITY]；否则原样复制（保留 PNG 透明）。
 *
 * 清理：正文引用差集（移除即删）由调用方触发 [delete]，冷启动 [sweep] 兜底扫孤儿文件。
 */
class AttachmentStore(context: Context) {

    private val resolver = context.applicationContext.contentResolver
    private val dir: File = File(context.applicationContext.filesDir, DIR_NAME)

    fun fileFor(fileName: String): File = File(dir, fileName)

    fun exists(fileName: String): Boolean = fileFor(fileName).isFile

    /**
     * 导入一张图：压缩后落盘并返回文件名；任何失败返回 null（调用方给提示，不静默丢）。
     */
    suspend fun importUri(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            dir.mkdirs()
            val mime = resolver.getType(uri).orEmpty()
            val isPng = mime.contains("png", ignoreCase = true)

            // 第一遍：只读尺寸边界——不分配像素内存（48MP 照片整图 decode 要十几 MB 峰值）
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val longSide = max(bounds.outWidth, bounds.outHeight)
            if (bounds.outWidth <= 0 || longSide <= 0) return@runCatching null

            // 文件字节数拿不到（部分 provider 返回 -1）时以尺寸为准，够用
            val declaredBytes = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            val needResize = longSide > MAX_SIDE || declaredBytes > MAX_BYTES

            if (!needResize) {
                // 小图：流式复制（不整图进内存），保留原格式与 PNG 透明
                val fileName = newFileName(if (isPng) "png" else "jpg")
                resolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(File(dir, fileName)).use { output -> input.copyTo(output) }
                } ?: return@runCatching null
                fileName
            } else {
                // 大图：第二遍带 inSampleSize 流式解码，长边钳到 MAX_SIDE 后 JPEG 重编码
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSizeFor(longSide, MAX_SIDE)
                }
                val decoded = resolver.openInputStream(uri)
                    ?.use { BitmapFactory.decodeStream(it, null, options) }
                    ?: return@runCatching null
                val scaled = if (max(decoded.width, decoded.height) > MAX_SIDE) {
                    val ratio = MAX_SIDE.toFloat() / max(decoded.width, decoded.height)
                    Bitmap.createScaledBitmap(
                        decoded,
                        (decoded.width * ratio).toInt().coerceAtLeast(1),
                        (decoded.height * ratio).toInt().coerceAtLeast(1),
                        true,
                    )
                } else {
                    decoded
                }
                val out = java.io.ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                if (scaled !== decoded) scaled.recycle()
                decoded.recycle()
                val fileName = newFileName("jpg")
                FileOutputStream(File(dir, fileName)).use { it.write(out.toByteArray()) }
                fileName
            }
        }.getOrElse {
            Log.w(TAG, "import attachment failed", it)
            null
        }
    }

    /** 删除附件文件（不存在即忽略）。 */
    suspend fun delete(fileNames: Collection<String>) = withContext(Dispatchers.IO) {
        fileNames.forEach { name ->
            runCatching { fileFor(name).delete() }
        }
        Unit
    }

    /**
     * 孤儿清扫：目录里存在、但**没有任何正文引用**的文件删掉（冷启动调用一次）。
     * 引用来源由调用方从两个仓库取（笔记正文 + 作业详情）。
     */
    suspend fun sweep(referenced: Set<String>) = withContext(Dispatchers.IO) {
        val files = dir.listFiles() ?: return@withContext
        var removed = 0
        files.forEach { file ->
            if (file.isFile && file.name !in referenced) {
                if (runCatching { file.delete() }.getOrDefault(false)) removed++
            }
        }
        if (removed > 0) Log.i(TAG, "swept $removed orphan attachment(s)")
    }

    /**
     * 删除「本次从正文里移除、且已无任何正文引用」的附件（保存路径调用）。
     * [referenced] 由调用方把笔记正文 + 作业详情全量喂给 [referencedNames] 得到——
     * 同一张图被复制到别处引用时不会被误删；[sweep] 兜住剩下的孤儿。
     */
    suspend fun deleteIfUnreferenced(candidates: Set<String>, referenced: Set<String>) {
        val toDelete = candidates - referenced
        if (toDelete.isNotEmpty()) delete(toDelete)
    }

    /** 收集全部正文（笔记 + 作业）引用的文件名。 */
    fun referencedNames(bodies: List<String>): Set<String> =
        bodies.flatMap { imageRefs(it) }.toSet()

    private fun newFileName(ext: String): String {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(java.util.Date())
        val salt = Random.nextInt(0x1000, 0xFFFF).toString(16)
        return "${stamp}_$salt.$ext"
    }

    /** 下采样倍数：2 的幂，保证解码后长边不小于目标（再精确缩放一次）。 */
    private fun sampleSizeFor(longSide: Int, target: Int): Int {
        var sample = 1
        while (longSide / (sample * 2) >= target) sample *= 2
        return sample
    }

    companion object {
        private const val TAG = "AttachmentStore"
        private const val DIR_NAME = "notes_img"

        /** 长边上限（像素）：再大对手机屏没意义，只增体积。 */
        const val MAX_SIDE = 1920

        /** 原始字节上限：超过就重编码（1.5MB）。 */
        const val MAX_BYTES = 1_500_000

        const val JPEG_QUALITY = 88
    }
}
