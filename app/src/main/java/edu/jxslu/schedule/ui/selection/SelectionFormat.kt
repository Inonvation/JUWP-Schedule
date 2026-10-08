package edu.jxslu.schedule.ui.selection

/** 选课模块的展示格式化（选课中心卡片与确认弹窗共用，别各写一份）。 */
internal fun creditText(value: Double): String {
    val rounded = Math.round(value * 100) / 100.0
    return if (rounded == rounded.toLong().toDouble()) {
        rounded.toLong().toString()
    } else {
        rounded.toString().trimEnd('0').trimEnd('.')
    }
}
