package edu.jxslu.schedule.data.repo

import android.content.Context
import androidx.core.content.FileProvider
import java.io.File

/**
 * 问渠**星级证书 PDF** 的落盘（2026-10-15，DESIGN §3.28 / §4.45）。
 *
 * 位置固定 `cacheDir/wenqu_cert/`：证书随时能从平台重下，没必要占持久目录；落 cache
 * 也**不进云备份**。用户要留档就走「分享 / 保存到下载」——由系统 SAF 选位置，App 不申请
 * 任何存储权限（同 `TranscriptStore` 的口径）。
 *
 * 文件名用平台给的（**可能含姓名**），只做「取 basename + 去路径分隔符」的清洗，防路径穿越；
 * 打开 / 分享走 [FileProvider] 临时授权的内容 Uri（见 manifest 与 `res/xml/file_paths.xml`
 * 的 `wenqu_cert` 根）。
 */
class WenquCertStore(context: Context) {

    private val appContext = context.applicationContext
    private val dir: File = File(appContext.cacheDir, DIR_NAME)

    /** 写入证书，返回落盘文件。同名直接覆盖（证书是随时能重下的东西，不留历史）。 */
    fun save(bytes: ByteArray, fileName: String): File {
        dir.mkdirs()
        val file = File(dir, safeName(fileName))
        file.writeBytes(bytes)
        return file
    }

    /** 打开 / 分享用的内容 Uri（走 [FileProvider]）。 */
    fun uriOf(file: File) =
        FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file)

    /** 只保留纯文件名：去掉路径部分与分隔符，空名回落到一个安全默认名。 */
    private fun safeName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\').trim()
        val cleaned = base.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return cleaned.ifBlank { "star_cert.pdf" }
    }

    private companion object {
        const val DIR_NAME = "wenqu_cert"
    }
}
