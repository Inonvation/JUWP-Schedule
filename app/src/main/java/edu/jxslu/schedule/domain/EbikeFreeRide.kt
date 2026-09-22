package edu.jxslu.schedule.domain

import kotlin.math.max

/**
 * 共享单车免费时长提醒（DESIGN §3.9，2026-09-22 新增）。
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

    private const val MINUTE_MS = 60_000L

    /** 提前量吸附到 [LEAD_MIN]..[LEAD_MAX]，防线脏数据。 */
    fun coerceLead(lead: Int): Int = lead.coerceIn(LEAD_MIN, LEAD_MAX)

    /**
     * 通知触发时刻（epoch 毫秒）：起点 +（免费时长 − 提前量）。
     * lead=3 → 12 分钟后响；lead=1 → 14 分钟后响（临期才提醒）。
     */
    fun triggerAtMillis(startAtMillis: Long, leadMinutes: Int): Long =
        startAtMillis + (FREE_MINUTES - coerceLead(leadMinutes)) * MINUTE_MS

    /** 免费剩余秒数（到点后为 0，不为负）。 */
    fun remainingSeconds(startAtMillis: Long, nowMillis: Long): Int =
        max(0, ((startAtMillis + FREE_MINUTES * MINUTE_MS - nowMillis) / 1000L).toInt())

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
        startAtMillis > 0 && nowMillis < startAtMillis + FREE_MINUTES * MINUTE_MS

    /** 通知文案：剩余分钟数。 */
    fun noticeText(leadMinutes: Int): String =
        "按 15 分钟计，约 ${coerceLead(leadMinutes)} 分钟后免费时段结束，留意换车或还车"
}
