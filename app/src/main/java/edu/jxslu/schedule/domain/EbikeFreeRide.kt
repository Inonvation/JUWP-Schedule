package edu.jxslu.schedule.domain

import kotlin.math.max

/**
 * 共享单车免费时长倒计时（DESIGN §3.9，2026-09-22 新增；2026-09-23 由 App 通知改系统日历）。
 *
 * 运营方口径：扫码开车后 15 分钟内免费，超时计费。App 无法感知实际开车成功
 * （扫码发生在微信里），计时起点 = 用户点「打开微信扫一扫」的时刻——
 * 偏保守（可能比实际开车早 1~2 分钟），宁可提醒早一点。
 *
 * 本文件只做纯 JVM 的时刻计算与文案格式化，不含任何 Android API。
 * 15 分钟是运营方活动政策、可能变化，文案一律写「按 15 分钟计」，不承诺。
 */
object EbikeFreeRide {

    /** 免费时长（分钟）。运营方政策口径，变了这里和文案一起改。 */
    const val FREE_MINUTES = 15

    /** 提前量下限（分钟）。 */
    const val LEAD_MIN = 1

    /** 提前量上限（分钟）。 */
    const val LEAD_MAX = 5

    /** 提前量默认值（分钟）——用户拍板 2026-09-22。 */
    const val DEFAULT_LEAD_MINUTES = 3

    /** 日历事件标题。 */
    const val EVENT_TITLE = "免费时长倒计时"

    /**
     * 日历事件时长（分钟）：**0 = 只标记时刻，不占时段**。
     * 事件锚在免费结束那一刻，那之后的区间不代表任何事实，给个非 0 值
     * 只会在日历里画出一条会误导人的色块。
     */
    const val EVENT_DURATION_MINUTES = 0

    private const val MINUTE_MS = 60_000L

    /** 提前量吸附到 [LEAD_MIN]..[LEAD_MAX]，防线脏数据。 */
    fun coerceLead(lead: Int): Int = lead.coerceIn(LEAD_MIN, LEAD_MAX)

    /** 免费结束时刻（epoch 毫秒）= 起点 + 15 分钟。日历事件锚在这一刻。 */
    fun freeEndMillis(startAtMillis: Long): Long = startAtMillis + FREE_MINUTES * MINUTE_MS

    /**
     * 日历事件的提醒偏移（分钟，相对事件开始 = 免费结束）：提前量一条 + 0 一条。
     *
     * 系统日历的 `Reminders.MINUTES` 是「事件开始前 N 分钟」且非负，表达不了
     * 「免费结束前 N 分钟」。把免费结束时刻设成事件开始时间，两条偏移就都成立：
     * lead=3 → 结束前 3 分钟响一次，结束那一刻再响一次。
     */
    fun reminderOffsets(leadMinutes: Int): List<Int> = listOf(coerceLead(leadMinutes), 0)

    /** 日历事件备注：用户点开这条事件时能看懂它在提醒什么。 */
    fun eventDescription(leadMinutes: Int): String =
        "按 $FREE_MINUTES 分钟计。免费结束前 ${coerceLead(leadMinutes)} 分钟与结束时刻各提醒一次。"

    /** 免费剩余秒数（到点后为 0，不为负）。 */
    fun remainingSeconds(startAtMillis: Long, nowMillis: Long): Int =
        max(0, ((freeEndMillis(startAtMillis) - nowMillis) / 1000L).toInt())

    /** 进度条比例 0f..1f（1 = 刚开始）。 */
    fun progressFraction(startAtMillis: Long, nowMillis: Long): Float {
        val total = FREE_MINUTES * MINUTE_MS
        val elapsed = (nowMillis - startAtMillis).coerceIn(0L, total)
        return 1f - elapsed.toFloat() / total
    }

    /** 倒计时文案 `mm:ss`；超过一小时不会出现（15 分钟上限），直接分钟兜底。 */
    fun formatRemaining(totalSeconds: Int): String {
        val safe = max(0, totalSeconds)
        return "%d:%02d".format(safe / 60, safe % 60)
    }

    /** 计时是否仍有效（未到点）。到点后 UI 不再显示计时条。 */
    fun isActive(startAtMillis: Long, nowMillis: Long): Boolean =
        startAtMillis > 0 && nowMillis < freeEndMillis(startAtMillis)
}
