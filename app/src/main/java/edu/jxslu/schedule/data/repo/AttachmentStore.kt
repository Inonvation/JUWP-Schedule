package edu.jxslu.schedule.data.repo

import android.content.Context
import android.net.Uri
import android.util.Log
import edu.jxslu.schedule.domain.imageRefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.random.Random

/**
 * 笔记·作业的图片附件存储（DESIGN §4.20）。
 *
 * 落盘位置 = 应用私有目录 `filesDir/notes_img/`，正文用 `![](img:文件名)` 引用。
 * 选图走系统 Photo Picker（`PickVisualMedia`，**零权限**），这里只负责把 Uri **复制**进来——
 * 不复制的话媒体库/SAF 授权随时可能失效，笔记里的图就白了。
 *
 * 压缩口径见 [ImageImport]（与课表背景图 §4.21 共用同一实现，别在 UI 里另写）。
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
            ImageImport.copyInto(
                dir = dir,
                uri = uri,
                resolver = resolver,
                maxSide = MAX_SIDE,
                maxBytes = MAX_BYTES,
                jpegQuality = JPEG_QUALITY,
                fileNameFor = ::newFileName,
            )
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

    companion object {
        private const val TAG = "AttachmentStore"
        private const val DIR_NAME = "notes_img"

        /** 长边上限（像素）：再大对手机屏没意义，只增体积。 */
        const val MAX_SIDE = 1920

        /** 原始字节上限：超过就重编码（1.5MB）。 */
        const val MAX_BYTES = 1_500_000L

        const val JPEG_QUALITY = 88
    }
}
