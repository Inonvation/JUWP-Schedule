package edu.jxslu.schedule.data.repo

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import java.io.File
import java.util.Locale
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * AI 调课的图片附件（DESIGN §4.39，2026-10-06）。
 *
 * 与笔记附件 [AttachmentStore] 是**两套**，别合并：差别在生命周期与压缩口径。
 *
 * - **会话级**：AI 会话本身不落盘（含课表与通知原文，落盘要加密），图片也不留痕。
 *   新会话 / 页面销毁 / 冷启动三处清（`JuwApplication` 扫，`AiChatViewModel.onCleared` 扫）。
 * - **口径更紧**：这些图要 base64 进请求体，膨胀约 33%。笔记那套 1920 / 1.5MB / q88
 *   在 4 张时会撑到 ~8MB 请求体，必然超时。这里收到 1600 / q80。
 * - **强制转 JPEG**（`ImageImport.copyInto(forceJpeg = true)`）：小图走流式复制那条分支时
 *   HEIC 会被命名成 `.jpg` 而内容是 HEIC，base64 出去上游必拒。
 *
 * 落盘复用 [ImageImport]，所以 **EXIF 方向**与笔记附件同一份实现（2026-10-06 一起修好）。
 */
class AiImageStore(context: Context) {

    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val dir: File = File(appContext.filesDir, DIR_NAME)

    /**
     * 导入 / 清空 / 清扫三件事**必须串行**：它们都是「读目录 → 动文件」，
     * 无序交错时会出现「新会话 clear 把刚导入完的新图删掉，名字却还留在待发送列表里」——
     * 发送时 `filter { it.isFile }` 又把它静默丢掉，用户看到缩略图退化成占位。
     * （调用方那层还有会话代数校验，两层一起才够。）
     */
    private val io = Mutex()

    /** 一张已落盘的 AI 图片：文件名 + 实际体积 + 像素尺寸（预览页要显示体积，让用户对 token 有概念）。 */
    data class Imported(
        val fileName: String,
        val bytes: Long,
        val width: Int,
        val height: Int,
    )

    fun fileFor(fileName: String): File = File(dir, fileName)

    fun exists(fileName: String): Boolean = fileFor(fileName).isFile

    /** 导入一张图：压缩转正后落盘。任何失败返回 null（调用方给提示，不静默丢）。 */
    suspend fun importUri(uri: Uri): Imported? = io.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                dir.mkdirs()
                val name = ImageImport.copyInto(
                    dir = dir,
                    uri = uri,
                    resolver = resolver,
                    maxSide = MAX_SIDE,
                    maxBytes = MAX_BYTES,
                    jpegQuality = JPEG_QUALITY,
                    fileNameFor = ::newFileName,
                    forceJpeg = true,
                )
                if (name == null) {
                    null
                } else {
                    val file = File(dir, name)
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, bounds)
                    Imported(name, file.length(), bounds.outWidth, bounds.outHeight)
                }
            }.getOrElse {
                Log.w(TAG, "import ai image failed", it)
                null
            }
        }
    }

    /** 清空目录（新会话 / 页面销毁 / 冷启动兜底）。 */
    suspend fun clear() = io.withLock { clearLocked() }

    /** 冷启动兜底：AI 会话不落盘，目录里任何残留都是上一次没清干净的。 */
    suspend fun sweep() = io.withLock {
        if (dir.isDirectory) clearLocked()
    }

    private suspend fun clearLocked() = withContext(Dispatchers.IO) {
        dir.listFiles()?.forEach { runCatching { it.delete() } }
        Unit
    }

    private fun newFileName(ext: String): String {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(java.util.Date())
        val salt = Random.nextInt(0x1000, 0xFFFF).toString(16)
        return "ai_${stamp}_$salt.$ext"
    }

    companion object {
        private const val TAG = "AiImageStore"
        private const val DIR_NAME = "ai_img"

        /** 单次最多几张。每张图都是 token 大户，且请求体线性涨。 */
        const val MAX_IMAGES = 4

        /** 长边上限：比笔记附件（1920）紧一档。 */
        const val MAX_SIDE = 1600

        /** 原始字节上限：超了就重编码。注意 [importUri] 走 `forceJpeg`，这条只在日志口径上生效。 */
        const val MAX_BYTES = 400_000L

        const val JPEG_QUALITY = 80
    }
}
