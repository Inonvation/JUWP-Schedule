package edu.jxslu.schedule.data.repo

import android.content.Context
import android.net.Uri
import android.util.Log
import edu.jxslu.schedule.domain.ScheduleBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.random.Random

/**
 * 课表页背景图的文件存储（DESIGN §4.21）。
 *
 * 落盘位置 = 应用私有目录 `filesDir/schedule_bg/`，**同时只保留一张**，
 * 偏好里存的只是文件名（`TimetablePrefs.bgImageName`）。选图走系统 Photo Picker
 * （`PickVisualMedia`，零权限），这里只负责把 Uri 复制进来。
 *
 * 为什么不并进 `notes_img/`：`AttachmentStore.sweep` 按「笔记正文引用差集」删孤儿文件，
 * 背景图不在任何正文里，并过去会被它当孤儿删掉。
 *
 * 写入顺序由调用方保证：**先写新文件 → 写偏好 → 删旧文件**。反过来会出现
 * 偏好指向一个已删文件（渲染时空白，且要等下次冷启动才自愈）。
 */
class ScheduleBackgroundStore(context: Context) {

    private val resolver = context.applicationContext.contentResolver
    private val dir: File = File(context.applicationContext.filesDir, DIR_NAME)

    /**
     * 取文件句柄；文件名不合法（手改偏好、跨版本残留）返回 null。
     * 合法性白名单在 [ScheduleBackground.isValidFileName]，挡的是越出私有目录的取值。
     */
    fun fileFor(fileName: String): File? =
        if (ScheduleBackground.isValidFileName(fileName)) File(dir, fileName) else null

    fun exists(fileName: String): Boolean = fileFor(fileName)?.isFile == true

    /** 导入一张图：压缩后落盘并返回文件名；失败返回 null（调用方给提示，不静默丢）。 */
    suspend fun importUri(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            dir.mkdirs()
            ImageImport.copyInto(
                dir = dir,
                uri = uri,
                resolver = resolver,
                maxSide = ScheduleBackground.MAX_SIDE,
                maxBytes = ScheduleBackground.MAX_BYTES,
                jpegQuality = ScheduleBackground.JPEG_QUALITY,
                fileNameFor = ::newFileName,
            )
        }.getOrElse {
            Log.w(TAG, "import background failed", it)
            null
        }
    }

    /** 删除单个背景文件（不存在即忽略）。 */
    suspend fun delete(fileName: String?) = withContext(Dispatchers.IO) {
        val file = fileName?.let { fileFor(it) } ?: return@withContext
        runCatching { file.delete() }
        Unit
    }

    /**
     * 孤儿清扫：目录里存在、但不是 [keep] 指向的那个文件删掉（冷启动调用一次）。
     * 兜住「替换图片后旧文件没删成」「异常中断留下半张图」这类残留。
     */
    suspend fun sweep(keep: String?) = withContext(Dispatchers.IO) {
        val files = dir.listFiles() ?: return@withContext
        var removed = 0
        files.forEach { file ->
            if (file.isFile && file.name != keep) {
                if (runCatching { file.delete() }.getOrDefault(false)) removed++
            }
        }
        if (removed > 0) Log.i(TAG, "swept $removed background file(s)")
    }

    private fun newFileName(ext: String): String {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(java.util.Date())
        val salt = Random.nextInt(0x1000, 0xFFFF).toString(16)
        return "bg_${stamp}_$salt.$ext"
    }

    private companion object {
        private const val TAG = "ScheduleBackground"
        private const val DIR_NAME = "schedule_bg"
    }
}
