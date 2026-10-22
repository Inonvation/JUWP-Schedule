package edu.jxslu.schedule.ui.common

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/** 复制纯文本到系统剪贴板；label 会出现在 Android 13+ 的「已复制」提示里。 */
fun copyPlainText(context: Context, label: String, text: String) {
    context.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText(label, text))
}
