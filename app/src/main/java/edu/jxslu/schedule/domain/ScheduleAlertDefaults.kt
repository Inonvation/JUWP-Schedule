package edu.jxslu.schedule.domain

/**
 * 调课检测提醒的固定口径（DESIGN §4.17，2026-10-04 重立）。
 *
 * 档位表与 [ScoreAlertDefaults] 共用一份（选项 UI 同一套）；默认档独立取 **24 小时**——
 * 调课检测每次核对要抓理论+实验两张整页 HTML，比考试/成绩的 JSON 接口重一截，
 * 低频起步，用户想更勤快再自己调。
 */
object ScheduleAlertDefaults {

    /** 默认检查间隔（小时）：一学期调不了几次课，一天一查足够。 */
    const val INTERVAL_DEFAULT = 24

    /** 检查间隔档位（小时）：与成绩/考试提醒同一份选项表。 */
    val INTERVAL_CHOICES: List<Int> = ScoreAlertDefaults.INTERVAL_CHOICES

    /** 存储值兜底：不在档位表里归到本链默认档。 */
    fun coerceIntervalHours(value: Int): Int =
        if (value in INTERVAL_CHOICES) value else INTERVAL_DEFAULT

    /** 存储值 → 选项下标；不在档位表内的值归到默认档。 */
    fun intervalChoiceIndex(value: Int): Int =
        INTERVAL_CHOICES.indexOf(coerceIntervalHours(value)).coerceAtLeast(0)

    /** 存储值 → 展示文案。 */
    fun intervalLabel(value: Int): String = "每 ${coerceIntervalHours(value)} 小时"
}
