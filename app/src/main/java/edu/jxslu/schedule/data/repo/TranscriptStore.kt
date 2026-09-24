package edu.jxslu.schedule.data.repo

import android.content.Context
import androidx.core.content.FileProvider
import java.io.File

/**
 * 导出成绩单的落盘（DESIGN §4.25 E4）。
 *
 * 位置固定 `filesDir/transcripts/`：成绩单含姓名、学号、证件照与全部成绩，属于隐私文件，
 * 默认只进应用私有目录，不写相册、不写公共下载目录。用户要给别人时走「分享」或
 * 「保存到下载」——后者由系统 SAF 选位置，App 不申请任何存储权限。
 *
 * 目录**不要**并到 `notes_img/`：那边有 `AttachmentStore.sweep`，会按笔记引用差集删文件
 * （与课表背景图踩过同一个坑，DESIGN §4.21）。
 *
 * 只留最近 [KEEP_FILES] 份：手机存储有限，成绩单又是随时能重导的东西，
 * 没有留存历史的必要。清理在每次保存后顺带做，不留后台任务。
 */
class TranscriptStore(context: Context) {

    private val appContext = context.applicationContext
    private val dir: File = File(appContext.filesDir, DIR_NAME)

    /**
     * 写入一份成绩单，返回落盘文件。
     *
     * 先写 `.part` 再改名：中途失败（磁盘满、进程被杀）只会留下一个 `.part`，
     * 不会让一个截断的 PDF 占着正式文件名被用户当成可用文件打开。
     */
    fun save(bytes: ByteArray, fileName: String): File {
        dir.mkdirs()
        val target = File(dir, fileName)
        val temp = File(dir, "$fileName.part")
        temp.writeBytes(bytes)
        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) {
            // 改名失败（少见）：退回直接写，仍要让用户拿到文件
            target.writeBytes(bytes)
            temp.delete()
        }
        prune()
        return target
    }

    /** 保留最新的 [keep] 份，其余删除。按最后修改时间倒序，改名失败的文件不影响判定。 */
    fun prune(keep: Int = KEEP_FILES) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        files.sortedByDescending { it.lastModified() }
            .drop(keep)
            .forEach { runCatching { it.delete() } }
    }

    /** 分享/打开用的内容 Uri（走 [FileProvider]，见 manifest 与 `res/xml/file_paths.xml`）。 */
    fun uriOf(file: File) = FileProvider.getUriForFile(appContext, authority(), file)

    private fun authority(): String = "${appContext.packageName}.fileprovider"

    private companion object {
        const val DIR_NAME = "transcripts"

        /** 保留份数：够「刚导过的那几张」来回分享，又不至于把私有目录撑起来。 */
        const val KEEP_FILES = 10
    }
}
