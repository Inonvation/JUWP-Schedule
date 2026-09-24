package edu.jxslu.schedule.ui.score

import android.content.Context
import android.content.Intent
import android.net.Uri
import edu.jxslu.schedule.ui.common.NoticeTone

/** 成绩单的 MIME（打开与分享共用）。 */
internal const val PDF_MIME = "application/pdf"

/**
 * 打开成绩单：交给系统里能看 PDF 的应用。
 *
 * Uri 是 FileProvider 的临时授权地址（`FLAG_GRANT_READ_URI_PERMISSION`），
 * 目标应用只在这一次读取里有权限。失败只有一种情况：设备上没有任何 PDF 阅读器，
 * 这时给一句提示并引导改用「分享」，而不是静默什么都不发生。
 */
internal fun openPdf(context: Context, uri: Uri, notify: (String, NoticeTone) -> Unit) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, PDF_MIME)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    val ok = runCatching { context.startActivity(intent) }.isSuccess
    if (!ok) notify("没有能打开 PDF 的应用，可以改用「分享」", NoticeTone.Warning)
}

/** 分享成绩单：走系统分享面板（微信、QQ、邮件都在这条路上）。 */
internal fun sharePdf(context: Context, uri: Uri, notify: (String, NoticeTone) -> Unit) {
    val send = Intent(Intent.ACTION_SEND)
        .setType(PDF_MIME)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    val ok = runCatching {
        context.startActivity(Intent.createChooser(send, "分享成绩单"))
    }.isSuccess
    if (!ok) notify("没有可用的分享目标", NoticeTone.Warning)
}
