package edu.jxslu.schedule.domain

/**
 * 成绩/考试变动提醒的固定口径（DESIGN §4.33）：检查间隔选项表 + 展示文案。
 *
 * 单一来源：DataStore 默认值、设置页轮选器、通知文案、周期任务的间隔全部从这里取，
 * 不各自写字面量。模式同 [BalanceAlert] / [CalendarSyncDefaults]。
 */
object ScoreAlertDefaults {

    /** 默认检查间隔（小时）：期末出分期一天查 4 次是够用的节奏。 */
    const val INTERVAL_DEFAULT = 6

    /** 检查间隔档位（小时）。用户拍板：1 / 3 / 6 / 12 / 24。 */
    val INTERVAL_CHOICES: List<Int> = listOf(1, 3, 6, 12, 24)

    /** 存储值兜底：不在档位表里归到默认档（旧数据/手改数据防线）。 */
    fun coerceIntervalHours(value: Int): Int =
        if (value in INTERVAL_CHOICES) value else INTERVAL_DEFAULT

    /** 存储值 → 选项下标；不在档位表内的值归到默认档。 */
    fun intervalChoiceIndex(value: Int): Int =
        INTERVAL_CHOICES.indexOf(coerceIntervalHours(value)).coerceAtLeast(0)

    /** 存储值 → 展示文案。 */
    fun intervalLabel(value: Int): String = "每 ${coerceIntervalHours(value)} 小时"
}
