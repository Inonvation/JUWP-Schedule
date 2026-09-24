package edu.jxslu.schedule.domain

import kotlin.math.max

/**
 * 共享单车免费时长倒计时（DESIGN §3.9）。
 *
 * 运营方口径：扫码开车后 15 分钟内免费，超时计费。App 无法感知实际开车成功
 * （扫码发生在微信里），计时起点 = 用户点「打开微信扫一扫」的时刻——
 * 偏保守（可能比实际开车早 1~2 分钟），宁可提醒早一点。
 *
 * 本文件只做纯 JVM 的时刻计算与文案格式化，不含任何 Android API。
 * 15 分钟是运营方活动政策、可能变化，文案一律写「按 15 分钟计」，不承诺。
 *
 * 提醒通道的演变：2026-09-22 首版 = App 通知；2026-09-23 改系统日历；
 * **2026-09-24 改回 App 通知**（用户拍板）——日历 App 发提醒时 App 自己没法展示
 * 倒计时，体验与 App 内是两套。现在两个提醒点由 [NotificationIds] 那两条通知承担，
 * 倒计时由前台服务的常驻通知展示（数字由系统 chronometer 渲染，见 `EbikeFreeRideService`）。
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

    /**
     * 结束提醒的迟到窗口（毫秒）：免费结束已过但还在窗口内就补发一次，
     * 越过就静默——「已经骑了 20 分钟」再弹一句只是噪音（用户拍板 2026-09-24）。
     */
    const val END_WINDOW_MS = 5 * 60_000L

    private const val MINUTE_MS = 60_000L

    /**
     * 通知与 channel 的标识（DESIGN §3.9）。
     *
     * 放在 domain 而不是 Service 里，是为了让「不与上课/作业提醒撞 id」这条契约
     * 能被单测锁住（上课 1001/1004、作业 1002/1003，见 `ui/reminder/ClassReminder.kt`）。
     *
     * channel 一律用**新 id**，不复用首版的 `ebike_free_ride`：那个 channel 在
     * 2026-09-23 之后被 `deleteLegacyChannel` 清过，但用户设备上可能还在，
     * 而 `createNotificationChannel` 改不动已存在 channel 的 importance
     * ——留着它就会继承旧级别，观感对不上。
     */
    object NotificationIds {
        /** 常驻倒计时（前台服务通知）。 */
        const val COUNTDOWN = 1000
        const val COUNTDOWN_CHANNEL = "ebike_free_ride_countdown"

        /** 提前量提醒。 */
        const val LEAD = 1005

        /** 结束提醒。 */
        const val END = 1006

        /** 两条到点提醒共用的 channel。 */
        const val ALERT_CHANNEL = "ebike_free_ride_alert_v2"

        /**
         * 提醒 channel 的早期 id（2026-09-24 当天建的，**没有震动**）。
         *
         * 为什么直接换 id 而不是原地改：channel 一旦建出来，`importance` 与**震动**
         * 都改不动，`createNotificationChannel` 只认 name/description。试过「删掉再重建」，
         * 但 `deleteNotificationChannel` 是异步的——紧接着 `create` 会被系统当成
         * 「更新已存在的 channel」，震动设置照样不生效（真机实测确认）。
         * 这个 channel 从未随版本发布过，换 id 的代价为零，只需把旧 id 清掉。
         */
        const val LEGACY_ALERT_CHANNEL = "ebike_free_ride_alert"
    }

    /** 常驻倒计时通知的标题（剩余时间由 [countdownText] 写进正文）。 */
    const val COUNTDOWN_TITLE = "免费时长倒计时"

    /** 常驻倒计时通知的正文（无计时可显示时的兜底，正常路径走 [countdownText]）。 */
    const val COUNTDOWN_TEXT = "按 $FREE_MINUTES 分钟计，超时开始计费"

    /**
     * 常驻倒计时通知的正文 = 「免费剩余 mm:ss」。
     *
     * **不依赖系统 chronometer**（2026-09-24 真机实测后改）：`setUsesChronometer` +
     * `setChronometerCountDown` 在 Redmi K70（澎湃OS）上展开面板能显示，但**面板静止时
     * 系统不主动重绘**，用户看到的是一个不动的数字。改成服务每秒主动 `notify` 一次，
     * 任何 ROM 上都一定跳秒——代价是 15 分钟里约 900 次通知更新（每秒一次是系统
     * 允许的上限，不会触发限流）。
     */
    fun countdownText(startAtMillis: Long, nowMillis: Long): String =
        "免费剩余 ${formatRemaining(remainingSeconds(startAtMillis, nowMillis))}"

    /** 提前量提醒的标题。 */
    const val LEAD_TITLE = "免费时长快结束了"

    /** 结束提醒的标题与正文。 */
    const val END_TITLE = "免费时长已结束"
    const val END_TEXT = "按 $FREE_MINUTES 分钟计，现在起开始计费，请尽快还车"

    /**
     * 提前量提醒的正文：按**实际剩余**写，不写设定的提前量——
     * 闹钟被 ROM 推迟时，写「还剩 3 分钟」而实际只剩 40 秒是假信息。
     */
    fun leadText(startAtMillis: Long, nowMillis: Long): String =
        "按 $FREE_MINUTES 分钟计，还剩 ${formatRemaining(remainingSeconds(startAtMillis, nowMillis))}，超时开始计费"

    /** 提前量吸附到 [LEAD_MIN]..[LEAD_MAX]，防线脏数据。 */
    fun coerceLead(lead: Int): Int = lead.coerceIn(LEAD_MIN, LEAD_MAX)

    /** 免费结束时刻（epoch 毫秒）= 起点 + 15 分钟。 */
    fun freeEndMillis(startAtMillis: Long): Long = startAtMillis + FREE_MINUTES * MINUTE_MS

    /** 提前量提醒的触发时刻 = 免费结束 − 提前量。 */
    fun leadReminderAt(startAtMillis: Long, leadMinutes: Int): Long =
        freeEndMillis(startAtMillis) - coerceLead(leadMinutes) * MINUTE_MS

    /** 结束提醒的触发时刻 = 免费结束那一刻。 */
    fun endReminderAt(startAtMillis: Long): Long = freeEndMillis(startAtMillis)

    /**
     * 下一个还没到点的提醒时刻（严格在 [nowMillis] 之后），供排精确闹钟；
     * 两个点都过了返回 null。闹钟触发后由 Receiver 核对并重排下一个。
     */
    fun nextReminderAt(startAtMillis: Long, leadMinutes: Int, nowMillis: Long): Long? =
        listOf(leadReminderAt(startAtMillis, leadMinutes), endReminderAt(startAtMillis))
            .filter { it > nowMillis }
            .minOrNull()

    /**
     * 提前量提醒现在该不该发：触发点已到，且免费时段**还没结束**。
     *
     * 闹钟被 ROM 推迟时照样补发（用户拍板 2026-09-24）——晚一点知道还剩多少，
     * 好过不知道；免费时段已经结束了就不发，那是 [isEndDue] 的活。
     */
    fun isLeadDue(startAtMillis: Long, leadMinutes: Int, nowMillis: Long): Boolean =
        isActive(startAtMillis, nowMillis) && nowMillis >= leadReminderAt(startAtMillis, leadMinutes)

    /** 结束提醒现在该不该发：免费结束已过、且没越过 [END_WINDOW_MS]。 */
    fun isEndDue(startAtMillis: Long, nowMillis: Long): Boolean {
        if (startAtMillis <= 0L) return false
        val end = freeEndMillis(startAtMillis)
        return nowMillis >= end && nowMillis < end + END_WINDOW_MS
    }

    /**
     * 提醒去重键。**带上计时起点**：换车重新计时后是两个不同的键，
     * 上一轮的「已发」记录不会把这一轮的提醒吃掉。落 DataStore 的已发集合，
     * 闹钟与周期核对共用（与上课提醒 `ReminderKeyKind` 同思路）。
     */
    fun leadDedupKey(startAtMillis: Long): String = "lead|$startAtMillis"

    fun endDedupKey(startAtMillis: Long): String = "end|$startAtMillis"

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

    /**
     * 常驻倒计时服务要不要**重新下发起点**（重建 tick）。
     *
     * 判据是**起点是否变化**，不是「服务在不在跑」：上一轮没结束就换车时，服务还在跑、
     * 但起点变了，必须重建，否则 tick 循环还握着旧起点、通知栏刷的是旧剩余时间
     * （2026-09-24 用户报的 bug——当时判据写成「服务在跑就跳过」）。
     *
     * 起点没变（`check` 每轮都会调一次）时返回 false，省掉一次 `startForegroundService`
     * 与 tick 循环的推倒重建；非法起点（≤ 0）也返回 false，调用方据此报失败。
     */
    fun shouldStartCountdown(runningStartAtMillis: Long, newStartAtMillis: Long): Boolean =
        newStartAtMillis > 0L && runningStartAtMillis != newStartAtMillis
}
