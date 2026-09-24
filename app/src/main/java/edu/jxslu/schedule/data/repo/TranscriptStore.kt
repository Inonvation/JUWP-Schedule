package edu.jxslu.schedule.data.repo

import android.content.Context
import androidx.core.content.FileProvider
import edu.jxslu.schedule.domain.TranscriptEntry
import edu.jxslu.schedule.domain.TranscriptHistory
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
 * 只留最近 [TranscriptHistory.DEFAULT_KEEP] 份：手机存储有限，成绩单又是随时能重导的东西。
 * 清理在每次保存后顺带做，不留后台任务。保留与排序的口径全在
 * [TranscriptHistory]（纯逻辑、可单测），这里只做目录 I/O。
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

    /**
     * 目录里现有的成绩单，最新在前（「最近导出」列表的数据源）。
     *
     * 顺手清掉写盘半成品（`.part`）：那是崩溃/被杀留下的残骸，留在目录里既不完整、
     * 又会占掉保留名额，还可能被用户从文件管理器里当成可用文件打开。
     */
    fun recent(): List<TranscriptEntry> {
        val files = dir.listFiles() ?: return emptyList()
        val entries = ArrayList<TranscriptEntry>(files.size)
        files.forEach { file ->
            if (!file.isFile) return@forEach
            when {
                TranscriptHistory.isPartialName(file.name) -> runCatching { file.delete() }
                TranscriptHistory.isEntryName(file.name) ->
                    entries += TranscriptEntry(file.name, file.length(), file.lastModified())
            }
        }
        return TranscriptHistory.sortNewestFirst(entries)
    }

    /** 删一份。[name] 只接受纯文件名（来自 [recent]），带路径分隔符的一律拒绝。 */
    fun delete(name: String): Boolean {
        if (!TranscriptHistory.isSafeName(name)) return false
        return runCatching { File(dir, name).delete() }.getOrDefault(false)
    }

    /**
     * 取一份仍存在的文件；不在就返回 null。
     *
     * 列表与动作之间有时间差（新导出一份会触发保留策略删旧的），所以动作前必须复核，
     * 否则打开/分享会把一个不存在的 Uri 交给阅读器，用户看到的是系统报错。
     */
    fun existingFile(name: String): File? {
        if (!TranscriptHistory.isSafeName(name)) return null
        val file = File(dir, name)
        return if (file.isFile) file else null
    }

    /** 清空。返回实际删掉的份数。 */
    fun deleteAll(): Int = recent().count { delete(it.name) }

    /** 保留最新的 [keep] 份，其余删除。名单由 [TranscriptHistory.filesToPrune] 给。 */
    fun prune(keep: Int = TranscriptHistory.DEFAULT_KEEP) {
        val names = TranscriptHistory.filesToPrune(recent(), keep).toSet()
        if (names.isEmpty()) return
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.name in names) runCatching { file.delete() }
        }
    }

    /** 分享/打开用的内容 Uri（走 [FileProvider]，见 manifest 与 `res/xml/file_paths.xml`）。 */
    fun uriOf(file: File) = FileProvider.getUriForFile(appContext, authority(), file)

    private fun authority(): String = "${appContext.packageName}.fileprovider"

    private companion object {
        const val DIR_NAME = "transcripts"
    }
}
