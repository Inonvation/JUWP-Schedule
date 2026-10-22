package edu.jxslu.schedule.domain

import java.util.Locale

/**
 * 文件体积文案：`B` / `KB` / `MB`（成绩单最近导出、地图缓存共用）。
 *
 * 固定 [Locale.US] 小数点，避免某些区域把「1.5 MB」写成「1,5 MB」。
 */
object SizeText {

    fun format(bytes: Long): String = when {
        bytes >= 1L shl 20 -> "%.1f MB".format(Locale.US, bytes / 1024.0 / 1024.0)
        bytes >= 1024L -> "%d KB".format(Locale.US, bytes / 1024)
        else -> "$bytes B"
    }
}
