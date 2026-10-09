package edu.jxslu.schedule.data.repo

import android.content.Context
import androidx.core.content.FileProvider
import java.io.File

/**
 * 课表导出图片的**分享**落盘（DESIGN §4.12）。
 *
 * 位置固定 `cacheDir/schedule_share/`：
 * - 分享用的图是一次性的——系统分享面板把内容 Uri 读完就没用了，落 cache 既不进
 *   云备份（只有 filesDir 进备份，也省掉往两份备份排除 xml 里再登记一条），
 *   也不污染私有目录；
 * - 「保存到相册」**不走这里**——那条路直接进 MediaStore（`ui/common/BitmapSave`）。
 *
 * 目录只留最近 [KEEP] 份，保存后顺带清理，不留后台任务。
 */
class ScheduleShareStore(context: Context) {

    private val appContext = context.applicationContext
    private val dir: File = File(appContext.cacheDir, DIR_NAME)

    /**
     * 写入一份待分享的图片，返回落盘文件。
     *
     * 先写 `.part` 再改名：中途失败（磁盘满、进程被杀）只留一个半成品，
     * 不会让截断的文件占着正式名被分享出去。
     */
    fun save(bytes: ByteArray, fileName: String): File {
        dir.mkdirs()
        val target = File(dir, fileName)
        val temp = File(dir, "$fileName.part")
        temp.writeBytes(bytes)
        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) {
            target.writeBytes(bytes)
            temp.delete()
        }
        prune()
        return target
    }

    /** 分享用的内容 Uri（走 FileProvider，见 manifest 与 `res/xml/file_paths.xml`）。 */
    fun uriOf(file: File) = FileProvider.getUriForFile(appContext, authority(), file)

    /**
     * 只留最近 [keep] 份，其余删除；顺手清掉写盘半成品（`.part`）。
     * 崩溃/被杀留下的残骸既不完整，又会占掉保留名额。
     */
    fun prune(keep: Int = KEEP) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        files.filter { it.name.endsWith(PART_SUFFIX) }
            .forEach { runCatching { it.delete() } }
        files.filterNot { it.name.endsWith(PART_SUFFIX) }
            .sortedByDescending { it.lastModified() }
            .drop(keep)
            .forEach { runCatching { it.delete() } }
    }

    private fun authority(): String = "${appContext.packageName}.fileprovider"

    private companion object {
        const val DIR_NAME = "schedule_share"
        const val KEEP = 5
        const val PART_SUFFIX = ".part"
    }
}
