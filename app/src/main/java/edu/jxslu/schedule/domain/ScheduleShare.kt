package edu.jxslu.schedule.domain

import java.time.LocalDate

/**
 * 课表导出为图片（DESIGN §4.12）的纯逻辑：文件名、周次日期范围。
 *
 * 渲染与截图在 ui 层（`ui/week/ScheduleShareScreen.kt`），这里只放能 JVM 测的部分——
 * 与 `ScheduleExporter` 同一纪律：日期算术落在 domain，不反向引用 ui 包。
 * 板的网格几何（列宽 / 行高）**不在这里**：2026-10-10 起与课表页同源，
 * 走 `ui/week` 的 `buildGridLayout`（见 `shareBoardGridHeightDp` 与 ScheduleShareScreen 的注释）。
 */
object ScheduleShare {

    /** 导出文件名：`JUWPHub课表_20261009_第3周.png`。[dateTag] 形如 `yyyyMMdd`。 */
    fun exportFileName(dateTag: String, week: Int): String =
        "JUWPHub课表_${dateTag}_第${week}周.png"

    /**
     * 周次日期范围文案：`10/6 – 10/12`。
     *
     * [start] / [end] 是当周**可见列**的首尾日期（关掉周六后末列是周五、只关周日则末列是周六，
     * 所以不能按「周一 + 6 天」硬算）。任一端为空（学期未配置）返回 null，由调用方决定怎么显示。
     */
    fun weekRangeText(start: LocalDate?, end: LocalDate?): String? {
        if (start == null || end == null) return null
        return "${start.format(SHORT_DAY_FORMAT)} – ${end.format(SHORT_DAY_FORMAT)}"
    }
}
