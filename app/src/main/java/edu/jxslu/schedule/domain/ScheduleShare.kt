package edu.jxslu.schedule.domain

import java.time.LocalDate

/**
 * 课表导出为图片（DESIGN §4.12）的纯逻辑：文件名、周次日期范围、导出板行高。
 *
 * 渲染与截图在 ui 层（`ui/week/ScheduleShareDialog.kt`），这里只放能 JVM 测的部分——
 * 与 `ScheduleExporter` 同一纪律：日期算术落在 domain，不反向引用 ui 包。
 */
object ScheduleShare {

    /** 导出图片的行高下限（dp）。再矮课名放不下。 */
    const val MIN_ROW_HEIGHT_DP = 44f

    /**
     * 导出图片的行高上限（dp）。
     * 不设上限的话大屏上 11 行会被摊得过高：图变长、行距松散，分享出去比例也难看。
     */
    const val MAX_ROW_HEIGHT_DP = 64f

    /** 导出文件名：`水贝贝课表_20261009_第3周.png`。[dateTag] 形如 `yyyyMMdd`。 */
    fun exportFileName(dateTag: String, week: Int): String =
        "水贝贝课表_${dateTag}_第${week}周.png"

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

    /**
     * 导出板的行高（dp）：把可用高度尽量摊给 [sectionCount] 行，再夹进 [minDp]..[maxDp]。
     *
     * [fixedHeightDp] = 不参与「摊」的固定占用（表头高度 + 全部行间距），由调用方按
     * 布局常量算好传入——间距口径（大节内 3dp / 大节间 6dp）只有 `WeekGridLayout` 一处，
     * 不在 domain 里重算。
     */
    fun boardRowHeightDp(
        availableHeightDp: Float,
        fixedHeightDp: Float,
        sectionCount: Int,
        minDp: Float = MIN_ROW_HEIGHT_DP,
        maxDp: Float = MAX_ROW_HEIGHT_DP,
    ): Float {
        if (sectionCount <= 0) return minDp
        val raw = (availableHeightDp - fixedHeightDp) / sectionCount
        return raw.coerceIn(minDp, maxDp)
    }
}
