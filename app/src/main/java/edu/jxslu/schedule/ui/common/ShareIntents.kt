package edu.jxslu.schedule.ui.common

import android.content.Context
import android.content.Intent
import android.net.Uri
import edu.jxslu.schedule.startActivityOutsideApp

/**
 * 用系统分享面板发一个文件；返回 false = 没有可用目标。
 *
 * 走 [startActivityOutsideApp]：从分享面板返回时二级页不会重放右推入过渡
 * （`.agents/rules/nav-window.md`「二级页里拉起外部应用一律走它」）。
 */
fun shareFile(context: Context, uri: Uri, mime: String, chooserTitle: String): Boolean =
    runCatching {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivityOutsideApp(Intent.createChooser(send, chooserTitle))
    }.isSuccess
